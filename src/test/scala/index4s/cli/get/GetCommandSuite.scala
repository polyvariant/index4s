package index4s.cli.get

import cats.effect.IO
import cats.syntax.all.*
import index4s.cli.{CliConfig, CliCommand, CliError, ExitCodes, GetConfig, Out}
import index4s.client.ApiError
import index4s.core.{EnrichedProject, Enrichment}
import index4s.domain.{ArtifactRef, DefaultArtifact, Fixture, ProjectResponse}
import index4s.{TestStubs}
import sttp.client4.testing.BackendStub
import sttp.model.StatusCode
import upickle.default.*
import weaver.SimpleIOSuite

/** GetCommand flows over the stub backend — zero network, exact route sets
  * (first-match-wins discipline: every test declares its full non-overlapping
  * path→response map up front).
  */
object GetCommandSuite extends SimpleIOSuite {

  private enum Route {
    case Body(text: String)
    case NotFound
  }

  private def routes(rs: (String, Route)*): BackendStub[IO] => BackendStub[IO] =
    stub =>
      rs.foldLeft(stub) { case (s, (path, resp)) =>
        val matched = s.whenRequestMatches(TestStubs.pathString(_) == path)
        resp match {
          case Route.Body(t)  => matched.thenRespondAdjust(t)
          case Route.NotFound =>
            matched.thenRespondWithCode(StatusCode.NotFound)
        }
      }

  private def cfgOf(get: GetConfig): CliConfig = CliConfig(CliCommand.Get(get))

  private def runGet(
      get: GetConfig,
      client: index4s.client.ScaladexClient,
      json: Boolean = false
  ): IO[Either[CliError, Out]] =
    GetCommand.run(cfgOf(get).copy(json = json), get, client, None)

  private val circeCoordRoutes: List[(String, Route)] = List(
    "/api/v1/projects/circe/circe" -> Route.Body(
      Fixture.text("project-circe.json")
    ),
    "/api/v1/projects/circe/circe/versions/latest" -> Route.Body(
      Fixture.text("latest-circe.json")
    ),
    "/api/v1/artifacts/io.circe/circe-core_3/latest" ->
      Route.Body(Fixture.text("artifact-circe-core-latest.json")),
    "/repos/circe/circe/readme" -> Route.Body(Fixture.text("readme-circe.md"))
  )

  private val circeCore0149 =
    """{"groupId":"io.circe","artifactId":"circe-core_3","version":"0.14.9","name":"circe-core","binaryVersion":"_3","language":"3","platform":"jvm","project":{"organization":"circe","repository":"circe"},"releaseDate":"2025-06-24T16:34:49Z","licenses":["Apache-2.0"]}"""

  test(
    "coordinate-miss: weaver moved-namespace — golden suggestion on stderr + --json payload"
  ) {
    val (client, _) = TestStubs.clientWith(
      routes(
        "/api/v1/artifacts/com.disneystreaming/weaver-cats_3/latest" -> Route.NotFound,
        "/api/search" -> Route.Body(
          """[{"organization":"typelevel","repository":"weaver-test","logo":"","artifacts":["weaver-cats","weaver-framework","weaver-discipline"],"deprecatedArtifacts":[]}]"""
        ),
        "/api/v1/projects/typelevel/weaver-test/versions/latest" ->
          Route.Body(Fixture.text("latest-weaver.json"))
      )
    )

    final case class SuggestionPayload(
        groupId: String,
        artifactId: String,
        version: String,
        project: String
    ) derives ReadWriter
    final case class MissedPayload(
        error: String,
        suggestions: List[SuggestionPayload]
    ) derives ReadWriter

    runGet(GetConfig("com.disneystreaming:weaver-cats_3"), client, json = true)
      .flatMap {
        case Left(CliError.Missed(name, suggestions, json)) =>
          IO.pure {
            val expectedLine =
              "com.disneystreaming:weaver-cats_3 not found — did you mean org.typelevel:weaver-cats:0.13.0?" +
                " (project typelevel/weaver-test)"
            val payloadOk = json match {
              case Some(j) =>
                read[MissedPayload](j) == MissedPayload(
                  "com.disneystreaming:weaver-cats_3 not found",
                  List(
                    SuggestionPayload(
                      "org.typelevel",
                      "weaver-cats",
                      "0.13.0",
                      "typelevel/weaver-test"
                    )
                  )
                )
              case None => false
            }
            expect.all(
              name == "weaver-cats",
              suggestions == List(expectedLine),
              ExitCodes.codeOf(
                CliError.Missed(name, suggestions, json)
              ) == ExitCodes.NotFound,
              ExitCodes.message(
                CliError.Missed(name, suggestions, json)
              ) == expectedLine,
              payloadOk
            )
          }
        case other => IO.pure(failure(s"expected Missed, got $other"))
      }
  }

  test(
    "coordinate-miss: plain mode (no --json) — stderr suggestion only, no stdout payload"
  ) {
    val (client, _) = TestStubs.clientWith(
      routes(
        "/api/v1/artifacts/com.example/weaver-cats_3/latest" -> Route.NotFound,
        "/api/search" -> Route.Body(
          """[{"organization":"typelevel","repository":"weaver-test","logo":"","artifacts":["weaver-cats"],"deprecatedArtifacts":[]}]"""
        ),
        "/api/v1/projects/typelevel/weaver-test/versions/latest" ->
          Route.Body(Fixture.text("latest-weaver.json"))
      )
    )
    runGet(GetConfig("com.example:weaver-cats_3"), client, json = false).map {
      case Left(CliError.Missed("weaver-cats", suggestions, None)) =>
        expect(
          suggestions.head.contains(
            "did you mean org.typelevel:weaver-cats:0.13.0?"
          )
        )
      case other => failure(s"expected payload-less Missed, got $other")
    }
  }

  test(
    "coordinate-miss: truly absent artifact — no suggestions, plain exit-1 wording"
  ) {
    val (client, _) = TestStubs.clientWith(
      routes(
        "/api/v1/artifacts/com.example/nonexistent-xyz_3/latest" -> Route.NotFound,
        "/api/search" -> Route.Body("[]")
      )
    )
    runGet(GetConfig("com.example:nonexistent-xyz_3"), client).map {
      case Left(err @ CliError.Missed(name, Nil, None)) =>
        expect.all(
          name == "nonexistent-xyz",
          ExitCodes.codeOf(err) == 1,
          ExitCodes.message(
            err
          ) == "'nonexistent-xyz' not in index. Try: index4s search nonexistent-xyz"
        )
      case other => failure(s"expected plain Missed, got $other")
    }
  }

  test("org/repo: project NotFound → exit 1 with the standard wording") {
    val (client, _) = TestStubs.clientWith(
      routes(
        "/api/v1/projects/circe/nope" -> Route.NotFound
      )
    )
    runGet(GetConfig("circe/nope"), client).map {
      case Left(err @ CliError.Missed("circe/nope", Nil, None)) =>
        expect(
          ExitCodes.message(
            err
          ) == "'circe/nope' not in index. Try: index4s search circe/nope"
        )
      case other => failure(s"expected Missed('circe/nope'), got $other")
    }
  }

  test(
    "coordinate + concrete version: pinned context replaces default/snippets/scaladoc/cellar"
  ) {
    val (client, _) = TestStubs.clientWith(
      routes(
        (List(
          "/api/v1/artifacts/io.circe/circe-core_3/0.14.9" -> Route.Body(
            circeCore0149
          )
        ) ::: circeCoordRoutes): _*
      )
    )
    runGet(GetConfig("io.circe:circe-core_3:0.14.9"), client).map {
      case Right(out: Out) =>
        expect.all(
          out.payload.contains("default: io.circe:circe-core_3:0.14.9"),
          out.payload.contains(
            """  sbt       "io.circe" %% "circe-core" % "0.14.9""""
          ),
          out.payload.contains(
            """  mill      ivy"io.circe::circe-core:0.14.9""""
          ),
          out.payload.contains(
            """  scala-cli dep"io.circe::circe-core::0.14.9""""
          ),
          out.payload.contains(
            "scaladoc: https://www.javadoc.io/doc/io.circe/circe-core_3/0.14.9"
          ),
          out.payload.contains("  cellar deps io.circe:circe-core_3:0.14.9"),
          out.payload.contains("latest: 0.14.16 (2026-06)"),
          out.notes.isEmpty
        )
      case other => failure(s"expected Right(Out), got $other")
    }
  }

  test(
    "refineDefault: zio-json declared default beats the lexicographic golden quirk"
  ) {
    val project = read[ProjectResponse](Fixture.text("project-zio-json.json"))
      .copy(defaultArtifact = Some("zio-json"))
    val refs = read[List[ArtifactRef]](Fixture.text("latest-zio-json.json"))
    val quirked = EnrichedProject(
      org = "zio",
      repo = "zio-json",
      project = Some(project),
      latestRefs = Some(refs),
      defaultArtifact = DefaultArtifact.select(refs, Some("3")),
      artifactDetails = None
    )
    val (client, requests) = TestStubs.clientWith(
      routes(
        "/api/v1/artifacts/dev.zio/zio-json_3/latest" ->
          Route.Body(Fixture.text("artifact-zio-json-latest.json"))
      )
    )
    GetCommand.refineDefault(client, quirked, "3").flatMap { refined =>
      requests.map { reqs =>
        expect.all(
          quirked.defaultArtifact.map(_.artifactId) == Some(
            "zio-json-golden_3"
          ),
          refined.defaultArtifact.map(_.artifactId) == Some("zio-json_3"),
          refined.artifactDetails.map(_.version) == Some("0.9.2"),
          reqs.size == 1
        )
      }
    }
  }

  test("refineDefault: unchanged selection (circe-core) → no re-fetch") {
    val project = read[ProjectResponse](Fixture.text("project-circe.json"))
    val refs = read[List[ArtifactRef]](Fixture.text("latest-circe.json"))
    val enriched = EnrichedProject(
      org = "circe",
      repo = "circe",
      project = Some(project),
      latestRefs = Some(refs),
      defaultArtifact = refs.find(_.artifactId == "circe-core_3"),
      artifactDetails = None
    )
    GetCommand
      .refineDefault(TestStubs.clientWith(routes())._1, enriched, "3")
      .map { refined =>
        expect.eql(enriched, refined)
      }
  }

  test(
    "refineDefault: undeclared default → repo-name rule picks zio-json_3 over golden"
  ) {
    // project-zio-json.json as captured live: defaultArtifact ABSENT — the
    // case that produced the live `default: dev.zio:zio-json-golden_3` bug.
    val project = read[ProjectResponse](Fixture.text("project-zio-json.json"))
    val refs = read[List[ArtifactRef]](Fixture.text("latest-zio-json.json"))
    val quirked = EnrichedProject(
      org = "zio",
      repo = "zio-json",
      project = Some(project),
      latestRefs = Some(refs),
      defaultArtifact = DefaultArtifact.select(refs, Some("3")),
      artifactDetails = None
    )
    val (client, requests) = TestStubs.clientWith(
      routes(
        "/api/v1/artifacts/dev.zio/zio-json_3/latest" ->
          Route.Body(Fixture.text("artifact-zio-json-latest.json"))
      )
    )
    GetCommand.refineDefault(client, quirked, "3").flatMap { refined =>
      requests.map { reqs =>
        expect.all(
          project.defaultArtifact.isEmpty,
          quirked.defaultArtifact.map(_.artifactId) == Some(
            "zio-json-golden_3"
          ),
          refined.defaultArtifact.map(_.artifactId) == Some("zio-json_3"),
          refined.artifactDetails.map(_.version) == Some("0.9.2"),
          reqs.size == 1
        )
      }
    }
  }

  test("refineDefault: declared defaultArtifact outranks the repo-name rule") {
    val project = read[ProjectResponse](Fixture.text("project-zio-json.json"))
      .copy(defaultArtifact = Some("zio-json-golden"))
    val refs = read[List[ArtifactRef]](Fixture.text("latest-zio-json.json"))
    val selected = EnrichedProject(
      org = "zio",
      repo = "zio-json",
      project = Some(project),
      latestRefs = Some(refs),
      defaultArtifact = refs.find(_.artifactId == "zio-json-golden_3"),
      artifactDetails = None
    )
    GetCommand
      .refineDefault(TestStubs.clientWith(routes())._1, selected, "3")
      .map { refined =>
        expect.eql(selected, refined)
      }
  }

  test(
    "refineDefault: no declared default and no repo_<scala> ref → select's pick survives (tethys)"
  ) {
    val project = read[ProjectResponse](Fixture.text("project-tethys.json"))
    val refs = read[List[ArtifactRef]](Fixture.text("latest-tethys.json"))
    val selected = EnrichedProject(
      org = "tethys-json",
      repo = "tethys",
      project = Some(project),
      latestRefs = Some(refs),
      defaultArtifact = DefaultArtifact.select(refs, Some("3")),
      artifactDetails = None
    )
    expect.all(
      project.defaultArtifact.isEmpty,
      !refs.exists(_.artifactId == "tethys_3"),
      selected.defaultArtifact.map(_.artifactId) == Some("tethys-cats_3")
    )
    GetCommand
      .refineDefault(TestStubs.clientWith(routes())._1, selected, "3")
      .map { refined =>
        expect.eql(selected, refined)
      }
  }

  test(
    "refineDefault --target native: declared circe-core → circe-core_native0.5_3"
  ) {
    val project = read[ProjectResponse](Fixture.text("project-circe.json"))
    val refs = read[List[ArtifactRef]](Fixture.text("latest-circe.json"))
    val enriched = EnrichedProject(
      org = "circe",
      repo = "circe",
      project = Some(project),
      latestRefs = Some(refs),
      defaultArtifact = refs.find(_.artifactId == "circe-core_3"),
      artifactDetails = None
    )
    val (client, requests) = TestStubs.clientWith(
      routes(
        "/api/v1/artifacts/io.circe/circe-core_native0.5_3/latest" ->
          Route.Body(Fixture.text("artifact-circe-core-latest.json"))
      )
    )
    GetCommand
      .refineDefault(
        client,
        enriched,
        "3",
        index4s.client.Target.Native("3", "0.5")
      )
      .flatMap { refined =>
        requests.map { reqs =>
          expect.all(
            refined.defaultArtifact.map(_.artifactId) == Some(
              "circe-core_native0.5_3"
            ),
            refined.artifactDetails.isDefined,
            reqs.size == 1
          )
        }
      }
  }

  test("refineDefault --target js: declared circe-core → circe-core_sjs1_3") {
    val project = read[ProjectResponse](Fixture.text("project-circe.json"))
    val refs = read[List[ArtifactRef]](Fixture.text("latest-circe.json"))
    val enriched = EnrichedProject(
      org = "circe",
      repo = "circe",
      project = Some(project),
      latestRefs = Some(refs),
      defaultArtifact = refs.find(_.artifactId == "circe-core_3"),
      artifactDetails = None
    )
    val (client, requests) = TestStubs.clientWith(
      routes(
        "/api/v1/artifacts/io.circe/circe-core_sjs1_3/latest" ->
          Route.Body(Fixture.text("artifact-circe-core-latest.json"))
      )
    )
    GetCommand
      .refineDefault(client, enriched, "3", index4s.client.Target.Js("3", "1"))
      .flatMap { refined =>
        requests.map { reqs =>
          expect.all(
            refs.exists(_.artifactId == "circe-core_sjs1_3"),
            refined.defaultArtifact.map(_.artifactId) == Some(
              "circe-core_sjs1_3"
            ),
            reqs.size == 1
          )
        }
      }
  }

  test("refineDefault --target sbt: repo-named sbt-plugin ref (synthetic)") {
    val sbtRefs = List(
      ArtifactRef("dev.zio", "zio-sbt-plugin_2.12_1.0", "1.0.0"),
      ArtifactRef("dev.zio", "zio-sbt-plugin_3_1.0", "1.0.0"),
      ArtifactRef("dev.zio", "other_3", "1.0.0")
    )
    val enriched = EnrichedProject(
      org = "zio",
      repo = "zio-sbt-plugin",
      project = Some(
        ProjectResponse(organization = "zio", repository = "zio-sbt-plugin")
      ),
      latestRefs = Some(sbtRefs),
      defaultArtifact = DefaultArtifact.select(sbtRefs, Some("3")),
      artifactDetails = None
    )
    val (client, requests) = TestStubs.clientWith(
      routes(
        "/api/v1/artifacts/dev.zio/zio-sbt-plugin_3_1.0/latest" ->
          Route.Body(
            """{"groupId":"dev.zio","artifactId":"zio-sbt-plugin_3_1.0","version":"1.0.0","name":"zio-sbt-plugin","binaryVersion":"_3_1.0","language":"3","platform":"sbtPlugin","project":{"organization":"zio","repository":"zio-sbt-plugin"},"releaseDate":"2026-01-01T00:00:00Z","licenses":[]}"""
          )
      )
    )
    GetCommand
      .refineDefault(
        client,
        enriched,
        "3",
        index4s.client.Target.Sbt("3", "1.0")
      )
      .flatMap { refined =>
        requests.map { reqs =>
          expect.all(
            enriched.defaultArtifact.map(_.artifactId) == Some("other_3"),
            refined.defaultArtifact.map(_.artifactId) == Some(
              "zio-sbt-plugin_3_1.0"
            ),
            reqs.size == 1
          )
        }
      }
  }

  test(
    "refineDefault --target native with NO native refs (tethys) → unfiltered pick survives"
  ) {
    val project = read[ProjectResponse](Fixture.text("project-tethys.json"))
    val refs = read[List[ArtifactRef]](Fixture.text("latest-tethys.json"))
    val selected = EnrichedProject(
      org = "tethys-json",
      repo = "tethys",
      project = Some(project),
      latestRefs = Some(refs),
      defaultArtifact = DefaultArtifact.select(refs, Some("3")),
      artifactDetails = None
    )
    expect(selected.defaultArtifact.map(_.artifactId) == Some("tethys-cats_3"))
    GetCommand
      .refineDefault(
        TestStubs.clientWith(routes())._1,
        selected,
        "3",
        index4s.client.Target.Native("3", "0.5")
      )
      .map { refined =>
        expect.eql(selected, refined)
      }
  }

  test(
    "bare ambiguous: plain mode — candidates table rides the stdout payload, exit 2"
  ) {
    val (client, _) = TestStubs.clientWith(
      routes(
        "/api/autocomplete" -> Route.Body(
          """[{"organization":"zio","repository":"zio-json","description":"Fast JSON for ZIO"},
           {"organization":"playframework","repository":"play-json","description":"The Play JSON library"}]"""
        )
      )
    )
    runGet(GetConfig("json"), client, json = false).map {
      case Left(err @ CliError.AmbiguousName("json", _, Some(payload))) =>
        expect.all(
          ExitCodes.codeOf(err) == ExitCodes.Ambiguous,
          ExitCodes.message(
            err
          ) == "ambiguous 'json' — 2 candidates on stdout (exit 2)",
          payload.contains(
            "'json' is ambiguous — 2 candidates (pin with org/repo):"
          ),
          payload.contains("| # | project | description |"),
          payload.contains("| 1 | zio/zio-json | Fast JSON for ZIO |"),
          payload.contains(
            "| 2 | playframework/play-json | The Play JSON library |"
          )
        )
      case other =>
        failure(s"expected payload-carrying AmbiguousName, got $other")
    }
  }

  test(
    "bare ambiguous: --json mode — machine-readable candidates on stdout, exit 2"
  ) {
    final case class Cand(
        organization: String,
        repository: String,
        description: String
    ) derives ReadWriter
    final case class AmbigPayload(
        error: String,
        query: String,
        candidates: List[Cand]
    ) derives ReadWriter

    val (client, _) = TestStubs.clientWith(
      routes(
        "/api/autocomplete" -> Route.Body(
          """[{"organization":"zio","repository":"zio-json","description":"Fast JSON for ZIO"},
           {"organization":"playframework","repository":"play-json","description":"The Play JSON library"}]"""
        )
      )
    )
    runGet(GetConfig("json"), client, json = true).map {
      case Left(err @ CliError.AmbiguousName("json", _, Some(payload))) =>
        expect.all(
          ExitCodes.codeOf(err) == ExitCodes.Ambiguous,
          read[AmbigPayload](payload) == AmbigPayload(
            "ambiguous",
            "json",
            List(
              Cand("zio", "zio-json", "Fast JSON for ZIO"),
              Cand("playframework", "play-json", "The Play JSON library")
            )
          )
        )
      case other =>
        failure(s"expected payload-carrying AmbiguousName, got $other")
    }
  }

  test("bare name: non-confident pin emits the stderr resolved-note") {
    val (client, _) = TestStubs.clientWith(
      routes(
        "/api/autocomplete" -> Route.Body(
          """[{"organization":"circe","repository":"circe-derivation","description":"Fast type class instance derivation for Circe"}]"""
        ),
        "/api/v1/projects/circe/circe-derivation" -> Route.Body(
          Fixture.text("project-circe.json")
        ),
        "/api/v1/projects/circe/circe-derivation/versions/latest" ->
          Route.Body(Fixture.text("latest-circe.json")),
        "/api/v1/artifacts/io.circe/circe-core_3/latest" ->
          Route.Body(Fixture.text("artifact-circe-core-latest.json")),
        "/repos/circe/circe-derivation/readme" -> Route.Body(
          Fixture.text("readme-circe.md")
        )
      )
    )
    runGet(GetConfig("deriving"), client).map {
      case Right(out: Out) =>
        expect.eql(
          List(
            "resolved: deriving → circe/circe-derivation (pin with circe/circe-derivation)"
          ),
          out.notes
        )
      case other => failure(s"expected Right(Out), got $other")
    }
  }

  test("bare name: exact repo match is confident — silent (no note)") {
    val (client, _) = TestStubs.clientWith(
      routes(
        (List(
          "/api/autocomplete" -> Route.Body(
            Fixture.text("autocomplete-circe.json")
          )
        ) ::: circeCoordRoutes): _*
      )
    )
    runGet(GetConfig("circe"), client).map {
      case Right(out: Out) => expect.eql(List.empty[String], out.notes)
      case other           => failure(s"expected Right(Out), got $other")
    }
  }

  test("--web: prints the Scaladex project URL only") {
    val webRoutes = List(
      "/api/v1/projects/circe/circe" -> Route.Body(
        Fixture.text("project-circe.json")
      ),
      "/api/v1/projects/circe/circe/versions/latest" -> Route.Body(
        Fixture.text("latest-circe.json")
      ),
      "/api/v1/artifacts/io.circe/circe-core_3/latest" ->
        Route.Body(Fixture.text("artifact-circe-core-latest.json"))
    )
    val (client, _) = TestStubs.clientWith(routes(webRoutes: _*))
    runGet(GetConfig("circe/circe", web = true), client).map {
      case Right(out: Out) =>
        expect.eql("https://index.scala-lang.org/circe/circe", out.payload)
      case other => failure(s"expected Right(Out), got $other")
    }
  }
}
