package index4s.cli.search

import cats.Applicative
import cats.effect.{Clock, IO}
import index4s.TestStubs.*
import index4s.cli.{CliCommand, CliConfig, SearchConfig, Sort}
import index4s.client.{ScaladexClient, Target}
import index4s.domain.*
import sttp.client4.testing.BackendStub
import sttp.model.StatusCode
import weaver.SimpleIOSuite

import java.time.Instant
import scala.concurrent.duration.*
import upickle.default.*

/** Orchestration tests (stub backend only — zero network): seed reuse
  * (thin-search query/paging), enrichment wiring + exact request sets, sort
  * orders incl. tie-breakers, degradation notices, --limit capping, --json
  * round-trip, determinism, and the empty-seed case (empty table, exit 0).
  *
  * Route stubs are a NON-OVERLAPPING path→response map (BackendStub matchers
  * are first-match-wins); degraded paths are decided up front.
  */
object RankSearchSuite extends SimpleIOSuite {

  // expect.eql needs Eq — test-scoped givens riding structural ==.
  private given cats.kernel.Eq[RankRender.RankMeta] =
    cats.kernel.Eq.fromUniversalEquals
  private given cats.kernel.Eq[RankRender.RankResult] =
    cats.kernel.Eq.fromUniversalEquals

  private val fixedNow: Instant = Instant.parse("2026-09-01T00:00:00Z")

  private val fixedClock: Clock[IO] = new Clock[IO] {
    def applicative: Applicative[IO] = Applicative[IO]
    def monotonic: IO[FiniteDuration] = IO.pure(0.seconds)
    def realTime: IO[FiniteDuration] =
      IO.pure(fixedNow.getEpochSecond.seconds + fixedNow.getNano.nanos)
  }

  private def rankCfg(sort: Sort = Sort.Stars): SearchConfig =
    SearchConfig("json", Nil, Target.Jvm(), rank = true, sort = sort)

  private def cfg(
      limit: Int = 20,
      json: Boolean = false,
      noHints: Boolean = false
  ): CliConfig =
    CliConfig(
      command = CliCommand.Search(rankCfg()),
      limit = limit,
      json = json,
      noHints = noHints
    )

  private def rank(
      client: ScaladexClient,
      config: CliConfig = cfg(),
      search: SearchConfig = rankCfg()
  ): IO[Either[index4s.cli.CliError, index4s.cli.Out]] =
    RankSearch.run(client, config, search, clock = fixedClock)

  private def daysAgo(n: Long): Instant = fixedNow.minusSeconds(n * 86400L)

  private sealed trait Resp
  private object Resp {
    case class Body(text: String) extends Resp
    case object Empty404 extends Resp // Scaladex 404s have EMPTY bodies
  }

  private def searchBody(results: (String, String)*): String =
    write(
      results
        .map((org, repo) => SearchResult(organization = org, repository = repo))
        .toList
    )

  private def projectBody(org: String, repo: String, stars: Int): String =
    write(
      ProjectResponse(
        organization = org,
        repository = repo,
        stars = Some(stars)
      )
    )

  private def latestBody(
      groupId: String,
      artifactId: String,
      version: String
  ): String =
    write(List(ArtifactRef(groupId, artifactId, version)))

  private def artifactBody(
      groupId: String,
      artifactId: String,
      version: String,
      org: String,
      repo: String,
      date: Instant
  ): String =
    write(
      ArtifactResponse(
        groupId = groupId,
        artifactId = artifactId,
        version = version,
        name = artifactId.stripSuffix("_3"),
        binaryVersion = "_3",
        language = "3",
        platform = "jvm",
        project = ProjectRef(org, repo),
        releaseDate = date,
        licenses = Nil
      )
    )

  /** Fully synthetic 3-request enrichment for one candidate. */
  private def synth(
      org: String,
      repo: String,
      stars: Int,
      version: String,
      date: Instant
  ): Map[String, Resp] = {
    val artifactId = s"${repo}_3"
    Map(
      s"api/v1/projects/$org/$repo" -> Resp.Body(projectBody(org, repo, stars)),
      s"api/v1/projects/$org/$repo/versions/latest" ->
        Resp.Body(latestBody(s"$org.example", artifactId, version)),
      s"api/v1/artifacts/$org.example/$artifactId/latest" ->
        Resp.Body(
          artifactBody(s"$org.example", artifactId, version, org, repo, date)
        )
    )
  }

  // Fixture-backed routes (captured Scaladex bytes, frozen 2026-09-01). The
  // tethys-cats_3 ArtifactResponse body is hand-written from the captured
  // latest-tethys.json ref (com.tethys-json:tethys-cats_3:0.29.8) — same
  // provenance as EnrichmentSuite. zio-json routes the GOLDEN artifact path
  // (DefaultArtifact.select's lexicographic pick) to the
  // captured zio-json artifact body — same 0.9.2 version, rank uses
  // version+releaseDate only.
  private val tethysArtifactBody =
    """{"groupId":"com.tethys-lang","artifactId":"tethys-cats_3",""" +
      """"version":"0.29.8","name":"tethys-cats","binaryVersion":"_3",""" +
      """"language":"3","platform":"jvm","project":{"organization":"tethys-json",""" +
      """"repository":"tethys"},"releaseDate":"2026-01-15T10:00:00Z",""" +
      """"licenses":["Apache-2.0"]}"""

  private val fixtureRoutes: Map[String, Resp] = Map(
    "api/v1/projects/circe/circe" -> Resp.Body(
      Fixture.text("project-circe.json")
    ),
    "api/v1/projects/circe/circe/versions/latest" -> Resp.Body(
      Fixture.text("latest-circe.json")
    ),
    "api/v1/artifacts/io.circe/circe-core_3/latest" -> Resp.Body(
      Fixture.text("artifact-circe-core-latest.json")
    ),
    "api/v1/projects/tethys-json/tethys" -> Resp.Body(
      Fixture.text("project-tethys.json")
    ),
    "api/v1/projects/tethys-json/tethys/versions/latest" -> Resp.Body(
      Fixture.text("latest-tethys.json")
    ),
    "api/v1/artifacts/com.tethys-json/tethys-cats_3/latest" -> Resp.Body(
      tethysArtifactBody
    ),
    "api/v1/projects/zio/zio-json" -> Resp.Body(
      Fixture.text("project-zio-json.json")
    ),
    "api/v1/projects/zio/zio-json/versions/latest" -> Resp.Body(
      Fixture.text("latest-zio-json.json")
    ),
    "api/v1/artifacts/dev.zio/zio-json-golden_3/latest" -> Resp.Body(
      Fixture.text("artifact-zio-json-latest.json")
    ),
    "api/v1/projects/typelevel/weaver-test" -> Resp.Body(
      Fixture.text("project-weaver-test.json")
    )
  )

  private def routes(
      search: String,
      enrichment: Map[String, Resp] = Map.empty
  )(
      degraded: String*
  ): BackendStub[IO] => BackendStub[IO] = {
    val effective =
      enrichment ++ degraded.map(_ -> Resp.Empty404: (String, Resp))
    stub =>
      val withSearch = stub
        .whenRequestMatches(_.uri.path.mkString("/") == "api/search")
        .thenRespondAdjust(search)
      effective.foldLeft(withSearch) { (s, entry) =>
        val matcher = s.whenRequestMatches(_.uri.path.mkString("/") == entry._1)
        entry._2 match {
          case Resp.Body(text) => matcher.thenRespondAdjust(text)
          case Resp.Empty404 => matcher.thenRespondWithCode(StatusCode.NotFound)
        }
      }
  }

  private val fourCandidates: String =
    searchBody(
      ("circe", "circe"),
      ("tethys-json", "tethys"),
      ("zio", "zio-json"),
      ("typelevel", "weaver-test")
    )

  private val weaverProject404 = "api/v1/projects/typelevel/weaver-test"

  /** Table rows = the lines between the column header and the blank line before
    * the footer (footer lines are also 2-space indented, so an
    * indentation-based scan would swallow them).
    */
  private def rowsOf(payload: String): List[String] =
    payload.split("\n", -1).drop(3).takeWhile(_.nonEmpty).toList

  test("wiring: seed search + bounded fan-out — 13 requests, exact path set") {
    val (client, requests) =
      clientWith(routes(fourCandidates, fixtureRoutes)(weaverProject404))
    rank(client, cfg(limit = 10)).flatMap { result =>
      requests.map { reqs =>
        result.fold(
          err => failure(s"unexpected Left: $err"),
          out =>
            expect.eql(11, reqs.size) &&
              expect.eql(
                List(
                  "/api/search",
                  "/api/v1/projects/circe/circe",
                  "/api/v1/projects/circe/circe/versions/latest",
                  "/api/v1/artifacts/io.circe/circe-core_3/latest",
                  "/api/v1/projects/tethys-json/tethys",
                  "/api/v1/projects/tethys-json/tethys/versions/latest",
                  "/api/v1/artifacts/com.tethys-json/tethys-cats_3/latest",
                  "/api/v1/projects/typelevel/weaver-test",
                  "/api/v1/projects/zio/zio-json",
                  "/api/v1/projects/zio/zio-json/versions/latest",
                  "/api/v1/artifacts/dev.zio/zio-json-golden_3/latest"
                ).sorted,
                paths(reqs).sorted
              ) &&
              expect.eql(
                "4 candidates · target jvm · scala 3 · sorted by stars",
                out.payload.split("\n", -1)(0)
              )
        )
      }
    }
  }

  test("wiring: stars sort orders fixture rows 2542 > 431 > 121 > degraded-0") {
    val (client, _) =
      clientWith(routes(fourCandidates, fixtureRoutes)(weaverProject404))
    rank(client, cfg(limit = 10)).map { result =>
      result.fold(
        err => failure(s"unexpected Left: $err"),
        out => {
          val starts =
            rowsOf(out.payload).map(_.stripPrefix("  ").takeWhile(_ != ' '))
          expect.eql(
            List(
              "circe/circe",
              "zio/zio-json",
              "tethys-json/tethys",
              "typelevel/weaver-test"
            ),
            starts
          ) && expect(rowsOf(out.payload).last.contains("—"))
        }
      )
    }
  }

  test(
    "wiring: seed reuses thin-search query building — topics fold into q verbatim"
  ) {
    val (client, requests) =
      clientWith(routes(searchBody(("circe", "circe")), fixtureRoutes)())
    val search = SearchConfig("json", List("json"), Target.Jvm(), rank = true)
    rank(client, search = search).flatMap { _ =>
      requests.map { reqs =>
        expect.eql(Some("json AND topics:json"), reqs.head.uri.params.get("q"))
      }
    }
  }

  private val sortCandidates: String =
    searchBody(("aaa", "old"), ("bbb", "brt"), ("ccc", "crt"))
  private val sortRoutes: Map[String, Resp] =
    synth("aaa", "old", 100, "1.0.0", daysAgo(1000)) ++
      synth("bbb", "brt", 200, "1.0.0", daysAgo(40)) ++
      synth("ccc", "crt", 300, "1.0.0", daysAgo(340))

  test(
    "sort: default stars — 300 above 200 above 100 regardless of staleness"
  ) {
    val (client, _) = clientWith(routes(sortCandidates, sortRoutes)())
    rank(client).map { result =>
      result.fold(
        err => failure(s"unexpected Left: $err"),
        out =>
          expect.eql(
            List("ccc/crt", "bbb/brt", "aaa/old"),
            rowsOf(out.payload).map(_.stripPrefix("  ").takeWhile(_ != ' '))
          )
      )
    }
  }

  test("sort: --sort fresh reorders by releaseDate desc; header says fresh") {
    val (client, _) = clientWith(routes(sortCandidates, sortRoutes)())
    rank(client, search = rankCfg(Sort.Fresh)).map { result =>
      result.fold(
        err => failure(s"unexpected Left: $err"),
        out =>
          expect.eql(
            "3 candidates · target jvm · scala 3 · sorted by fresh",
            out.payload.split("\n", -1)(0)
          ) &&
            expect.eql(
              List("bbb/brt", "ccc/crt", "aaa/old"),
              rowsOf(out.payload).map(_.stripPrefix("  ").takeWhile(_ != ' '))
            )
      )
    }
  }

  test(
    "sort: tie-breakers — equal stars → org/repo asc; missing date LAST in fresh"
  ) {
    val candidates = searchBody(("aab", "xxx"), ("aaa", "xxx"), ("zzz", "zzz"))
    val enrichment = synth("aaa", "xxx", 100, "1.0.0", daysAgo(40)) ++
      synth("aab", "xxx", 100, "1.0.0", daysAgo(340)) ++
      Map(
        "api/v1/projects/zzz/zzz" -> Resp.Body(projectBody("zzz", "zzz", 999)),
        "api/v1/projects/zzz/zzz/versions/latest" -> Resp.Empty404
      )
    val (starsC, _) = clientWith(routes(candidates, enrichment)())
    val (freshC, _) = clientWith(routes(candidates, enrichment)())
    def ids(payload: String): List[String] =
      rowsOf(payload).map(_.stripPrefix("  ").takeWhile(_ != ' '))
    for {
      stars <- rank(starsC)
      fresh <- rank(freshC, search = rankCfg(Sort.Fresh))
    } yield (stars, fresh) match {
      case (Right(starOut), Right(freshOut)) =>
        expect.eql(
          List("zzz/zzz", "aaa/xxx", "aab/xxx"),
          ids(starOut.payload)
        ) &&
        expect.eql(List("aaa/xxx", "aab/xxx", "zzz/zzz"), ids(freshOut.payload))
      case (l, r) => failure(s"unexpected Left: stars=$l fresh=$r")
    }
  }

  test(
    "degradation: artifact 404 (jawn-style) — row survives with — cells + notes"
  ) {
    val (client, _) =
      clientWith(
        routes(searchBody(("circe", "circe")), fixtureRoutes)(
          "api/v1/artifacts/io.circe/circe-core_3/latest"
        )
      )
    rank(client).map { result =>
      result.fold(
        err => failure(s"unexpected Left: $err"),
        out => {
          val row = rowsOf(out.payload).head
          expect.all(
            row.startsWith("  circe/circe"),
            row.contains("—")
          ) &&
          expect.eql(
            List(
              "— circe/circe: io.circe:circe-core_3 not found",
              "1 of 1 rows have missing signals (— cells)",
              "Install: cs install --contrib cellar"
            ),
            out.notes
          )
        }
      )
    }
  }

  test(
    "degradation: notes come after clean rows produce none; install note only undetected"
  ) {
    val (client, _) =
      clientWith(routes(searchBody(("circe", "circe")), fixtureRoutes)())
    rank(client, cfg(noHints = true)).map { result =>
      result.fold(
        err => failure(s"unexpected Left: $err"),
        out => expect.eql(Nil: List[String], out.notes)
      )
    }
  }

  test("limit 2 over 4 candidates — exactly 2 rows enriched, 7 requests") {
    val (client, requests) =
      clientWith(routes(fourCandidates, fixtureRoutes)(weaverProject404))
    rank(client, cfg(limit = 2)).flatMap { result =>
      requests.map { reqs =>
        result.fold(
          err => failure(s"unexpected Left: $err"),
          out =>
            expect.eql(7, reqs.size) &&
              expect.eql(
                "2 candidates · target jvm · scala 3 · sorted by stars",
                out.payload.split("\n", -1)(0)
              ) &&
              expect.eql(
                List("circe/circe", "tethys-json/tethys"),
                rowsOf(out.payload).map(_.stripPrefix("  ").takeWhile(_ != ' '))
              )
        )
      }
    }
  }

  test("json: envelope round-trip with tier serialization + degraded nulls") {
    val (client, _) =
      clientWith(
        routes(
          searchBody(("circe", "circe"), ("typelevel", "weaver-test")),
          fixtureRoutes
        )(weaverProject404)
      )
    rank(client, cfg(json = true)).map { result =>
      result.fold(
        err => failure(s"unexpected Left: $err"),
        out => {
          val decoded = read[RankRender.RankJson](out.payload)
          expect.eql(
            RankRender.RankMeta("json", "jvm", "stars", 2),
            decoded.meta
          ) &&
          expect.eql(
            List("circe/circe", "typelevel/weaver-test"),
            decoded.results.map(_.id)
          ) &&
          expect.eql(
            RankRender.RankResult(
              id = "circe/circe",
              stars = Some(2542),
              latestVersion = Some("0.14.16"),
              releaseDate = Some("2026-06-24T16:34:49Z"),
              tier = Some("active"),
              license = Some("Apache-2.0"),
              category = Some("json"),
              platformSummary = Some("scala 2.12, 2.13, 3 · sjs1 · native0.5"),
              defaultCoordinate = Some("io.circe:circe-core_3:0.14.16"),
              degraded = Nil
            ),
            decoded.results.head
          ) &&
          expect.eql(
            RankRender.RankResult(
              id = "typelevel/weaver-test",
              stars = None,
              latestVersion = None,
              releaseDate = None,
              tier = None,
              license = None,
              category = None,
              platformSummary = None,
              defaultCoordinate = None,
              degraded = List("typelevel/weaver-test not found")
            ),
            decoded.results(1)
          )
        }
      )
    }
  }

  test(
    "determinism: identical stubbed input twice → byte-identical payload + notes"
  ) {
    val (clientA, _) =
      clientWith(routes(fourCandidates, fixtureRoutes)(weaverProject404))
    val (clientB, _) =
      clientWith(routes(fourCandidates, fixtureRoutes)(weaverProject404))
    for {
      a <- rank(clientA, cfg(limit = 10))
      b <- rank(clientB, cfg(limit = 10))
    } yield (a, b) match {
      case (Right(outA), Right(outB)) =>
        expect.eql(outA.payload, outB.payload) && expect.eql(
          outA.notes,
          outB.notes
        )
      case (l, r) => failure(s"unexpected Left: $l / $r")
    }
  }

  test("empty seed — 1 request only, empty table, Right (exit 0)") {
    val (client, requests) = clientWith(routes("[]", fixtureRoutes)())
    rank(client).flatMap { result =>
      requests.map { reqs =>
        result.fold(
          err => failure(s"unexpected Left: $err"),
          out =>
            expect.eql(List("/api/search"), paths(reqs)) &&
              expect.eql(
                "0 candidates · target jvm · scala 3 · sorted by stars",
                out.payload.split("\n", -1)(0)
              ) &&
              expect.eql(Nil: List[String], rowsOf(out.payload)) &&
              expect.eql(
                List("Install: cs install --contrib cellar"),
                out.notes
              )
        )
      }
    }
  }
}
