package index4s.cli.search

import cats.effect.IO
import index4s.TestStubs.*
import index4s.cli.{CliCommand, CliConfig, ExitCodes, SearchConfig}
import index4s.client.Target
import index4s.domain.SearchResult
import sttp.client4.testing.BackendStub
import sttp.model.StatusCode
import weaver.SimpleIOSuite

import upickle.default.*

/** Thin-search orchestration tests (stub backend only — zero network): query
  * building (topic folding, --cli), per-platform target params, internal paging
  * over the 20-per-page API (sequential pages, early stop, --limit cap),
  * relevance-order preservation, empty→exit-1 mapping, API-failure mapping, and
  * --rank delegation.
  */
object SearchCommandSuite extends SimpleIOSuite {

  private val plain: SearchConfig = SearchConfig("json", Nil, Target.Jvm())

  private def cfg(limit: Int = 20, json: Boolean = false): CliConfig =
    CliConfig(command = CliCommand.Search(plain), limit = limit, json = json)

  /** A distinct page body: `n` rows whose org encodes page+index so relevance
    * order is observable in the rendered payload.
    */
  private def pageBody(orgPrefix: String, n: Int): String =
    write(
      (1 to n)
        .map(i =>
          SearchResult(
            organization = s"$orgPrefix-$i",
            repository = "repo",
            artifacts = List(s"$orgPrefix-artifact-$i")
          )
        )
        .toList
    )

  /** Non-overlapping per-page routes: BackendStub matchers are first-match-wins
    * — never re-route the same predicate. Curried so page routes compose with
    * `andThen`.
    */
  private def onPage(page: Int, body: String)(
      stub: BackendStub[IO]
  ): BackendStub[IO] =
    stub
      .whenRequestMatches(r => r.uri.params.get("page") == Some(page.toString))
      .thenRespondAdjust(body)

  private def linesOf(result: Either[_, String]): Array[String] =
    result.fold(e => Array(e.toString), _.split("\n", -1))

  test(
    "query building: buildQuery folds topics as ' AND topics:<t>'; no topics = verbatim"
  ) {
    IO.pure(
      expect.eql(
        "json AND topics:json AND topics:http",
        SearchCommand.buildQuery("json", List("json", "http"))
      ) &&
        expect.eql("*", SearchCommand.buildQuery("*", Nil))
    )
  }

  test(
    "query building: --cli adds cli=true; the folded q reaches the wire verbatim"
  ) {
    val (client, requests) = clientWith(onPage(1, "[]"))
    SearchCommand
      .run(
        cfg(),
        SearchConfig("json", List("json", "http"), Target.Jvm(), cli = true),
        client
      )
      .flatMap { _ =>
        requests.map { reqs =>
          val params = reqs.head.uri.params
          expect.all(
            reqs.size == 1,
            params.get("q") == Some("json AND topics:json AND topics:http"),
            params.get("cli") == Some("true"),
            params.get("page") == Some("1")
          )
        }
      }
  }

  test("target params per platform: js/native/sbt carry their version params") {
    val (jsC, jsR) = clientWith(onPage(1, "[]"))
    val (nativeC, nativeR) = clientWith(onPage(1, "[]"))
    val (sbtC, sbtR) = clientWith(onPage(1, "[]"))
    for {
      _ <- SearchCommand.run(
        cfg(),
        SearchConfig("x", Nil, Target.Js("2.13", "1")),
        jsC
      )
      _ <- SearchCommand.run(
        cfg(),
        SearchConfig("x", Nil, Target.Native("3", "0.4")),
        nativeC
      )
      _ <- SearchCommand.run(
        cfg(),
        SearchConfig("x", Nil, Target.Sbt("2.12", "1.0")),
        sbtC
      )
      j <- jsR
      n <- nativeR
      s <- sbtR
    } yield {
      val jp = j.head.uri.params
      val np = n.head.uri.params
      val sp = s.head.uri.params
      expect.all(
        jp.get("target") == Some("JS"),
        jp.get("scalaVersion") == Some("2.13"),
        jp.get("scalaJsVersion") == Some("1"),
        np.get("target") == Some("NATIVE"),
        np.get("scalaNativeVersion") == Some("0.4"),
        np.get("scalaVersion") == Some("3"),
        sp.get("target") == Some("SBT"),
        sp.get("sbtVersion") == Some("1.0"),
        sp.get("scalaVersion") == Some("2.12")
      )
    }
  }

  test(
    "paging: --limit 45 requests pages 1,2,3 sequentially; relevance order preserved; count = shown"
  ) {
    val stub = onPage(1, pageBody("p1", 20)) andThen onPage(
      2,
      pageBody("p2", 20)
    ) andThen onPage(3, pageBody("p3", 5))
    val (client, requests) = clientWith(stub)
    SearchCommand.run(cfg(limit = 45), plain, client).flatMap { result =>
      requests.map { reqs =>
        val lines = linesOf(result.map(_.payload))
        expect.all(
          result.isRight,
          lines.length == 2 + 45,
          // lines: header, blank, then row N at index N+1
          lines(2).contains("p1-1/repo"),
          lines(21).contains("p1-20/repo"),
          lines(22).contains("p2-1/repo"),
          lines(41).contains("p2-20/repo"),
          lines(42).contains("p3-1/repo"),
          lines(46).contains("p3-5/repo")
        ) && expect.eql(
          List("1", "2", "3"),
          reqs.flatMap(_.uri.params.get("page"))
        ) &&
        expect.eql(
          "45 projects (Scaladex relevance, target jvm · scala 3) — use --rank for stars/freshness comparison",
          lines.head
        )
      }
    }
  }

  test("paging: --limit 15 fetches exactly one page and caps rows at 15") {
    val (client, requests) = clientWith(onPage(1, pageBody("p1", 20)))
    SearchCommand.run(cfg(limit = 15), plain, client).flatMap { result =>
      requests.map { reqs =>
        val lines = linesOf(result.map(_.payload))
        expect.all(
          result.isRight,
          reqs.size == 1,
          lines.length == 2 + 15,
          lines.head.startsWith("15 projects (Scaladex relevance")
        )
      }
    }
  }

  test(
    "paging: early stop — a short page (< 20) ends paging before the next page is requested"
  ) {
    // No page-3 route is stubbed: an (incorrect) page-3 request makes the stub
    // throw, which surfaces as a Network error — the test fails either way.
    val stub =
      onPage(1, pageBody("p1", 20)) andThen onPage(2, pageBody("p2", 7))
    val (client, requests) = clientWith(stub)
    SearchCommand.run(cfg(limit = 45), plain, client).flatMap { result =>
      requests.map { reqs =>
        val lines = linesOf(result.map(_.payload))
        expect.all(
          result.isRight,
          lines.length == 2 + 27,
          lines.head.startsWith("27 projects (Scaladex relevance")
        ) && expect.eql(List("1", "2"), reqs.flatMap(_.uri.params.get("page")))
      }
    }
  }

  test(
    "empty results → Left with the stderr wording and exit-1 mapping; topic-folded q in the message"
  ) {
    val (client, _) = clientWith(_.whenAnyRequest.thenRespondAdjust("[]"))
    val (topicClient, _) = clientWith(_.whenAnyRequest.thenRespondAdjust("[]"))
    for {
      plainResult <- SearchCommand.run(cfg(), plain, client)
      topicResult <- SearchCommand
        .run(cfg(), SearchConfig("json", List("x"), Target.Jvm()), topicClient)
    } yield {
      val plainExpectation = plainResult.fold(
        err =>
          expect.eql(
            "no results for 'json' (target jvm · scala 3)",
            ExitCodes.message(err)
          ) &&
            expect(ExitCodes.codeOf(err) == ExitCodes.NotFound),
        _ => failure("expected Left for empty results")
      )
      val topicExpectation = topicResult.fold(
        err =>
          expect.eql(
            "no results for 'json AND topics:x' (target jvm · scala 3)",
            ExitCodes.message(err)
          ),
        _ => failure("expected Left for empty results")
      )
      plainExpectation && topicExpectation
    }
  }

  test("--json routes to the meta envelope payload") {
    val (client, _) = clientWith(onPage(1, pageBody("a", 2)))
    SearchCommand.run(cfg(json = true), plain, client).map { result =>
      result.fold(
        err => failure(s"unexpected Left: $err"),
        out => expect(out.payload.startsWith("""{"meta":{"query":"json""""))
      )
    }
  }

  test("API failure maps to CliError.Api (exit 1) after the retry budget") {
    val (client, requests) =
      clientWith(
        _.whenAnyRequest.thenRespondWithCode(StatusCode.InternalServerError)
      )
    SearchCommand.run(cfg(), plain, client).flatMap { result =>
      requests.map { reqs =>
        expect(reqs.size == 3) && // 1 send + 2 retries (tinyDelays)
        result.fold(
          err => expect(ExitCodes.codeOf(err) == ExitCodes.NotFound),
          out => failure(s"unexpected Right: ${out.payload}")
        )
      }
    }
  }

  test(
    "--rank delegates to RankSearch: seed request issued, empty seed → exit 0"
  ) {
    val (client, requests) = clientWith(onPage(1, "[]"))
    SearchCommand
      .run(cfg(), SearchConfig("json", Nil, Target.Jvm(), rank = true), client)
      .flatMap { result =>
        requests.map { reqs =>
          expect(reqs.size == 1) && // the seed search; zero fan-out calls
          result.fold(
            err => failure(s"unexpected Left: $err"),
            out =>
              expect.eql(
                "0 candidates · target jvm · scala 3 · sorted by stars",
                out.payload.split("\n", -1).head
              )
          )
        }
      }
  }
}
