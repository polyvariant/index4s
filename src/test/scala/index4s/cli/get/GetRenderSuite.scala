package index4s.cli.get

import index4s.cli.{CliConfig, CliCommand, GetConfig, ReadmeMode}
import index4s.client.ApiError
import index4s.core.EnrichedProject
import index4s.domain.{ArtifactRef, Fixture, ProjectResponse, Readme}
import upickle.default.*
import weaver.FunSuite

/** GetRender: the get-card byte-shape over the REAL circe capture set (golden,
  * line-normalized), the degraded card (versions 404 → `—` cells), the --json
  * view round-trip, and npm-style positional field paths. Pure — views are
  * assembled directly, no client involved.
  */
object GetRenderSuite extends FunSuite {

  private val circeProject =
    read[ProjectResponse](Fixture.text("project-circe.json"))
  private val circeRefs =
    read[List[ArtifactRef]](Fixture.text("latest-circe.json"))
  private val circeDetails =
    read[index4s.domain.ArtifactResponse](
      Fixture.text("artifact-circe-core-latest.json")
    )
  private val readmeMd = Fixture.text("readme-circe.md").replace("\r\n", "\n")
  private val readmeLines = readmeMd.split("\n").toList

  private def circeView(
      readme: Option[Readme] = Some(Readme.Available(readmeMd)),
      pinned: Option[index4s.domain.ArtifactResponse] = None
  ): GetCommand.GetView =
    GetCommand.GetView(
      enriched = EnrichedProject(
        org = "circe",
        repo = "circe",
        project = Some(circeProject),
        latestRefs = Some(circeRefs),
        defaultArtifact = circeRefs.find(r => r.artifactId == "circe-core_3"),
        artifactDetails = Some(circeDetails)
      ),
      readme = readme,
      pinned = pinned,
      resolution = GetCommand.Resolution("circe/circe", "circe/circe", None)
    )

  private val defaultOpts = GetRender.RenderOpts(
    readmeMode = ReadmeMode.Head,
    section = None,
    artifacts = false,
    noHints = false,
    cellarDetected = false
  )

  private def lines(s: String): List[String] =
    s.replace("\r\n", "\n").split("\n").toList

  test("golden card: circe — full layout (cellar not detected)") {
    val expected =
      List(
        "circe/circe | Apache-2.0 | category: json | ★ 2,542 | forks 546 | issues 128",
        "Yet another JSON library for Scala — http://circe.io/circe/",
        "topics: generic-derivation, json, scala",
        "latest: 0.14.16 (2026-06) — scala 2.12, 2.13, 3 · sjs1 · native0.5   [108 versions]",
        "default: io.circe:circe-core_3:0.14.16",
        """  sbt       "io.circe" %% "circe-core" % "0.14.16"""",
        """  mill      ivy"io.circe::circe-core:0.14.16"""",
        """  scala-cli dep"io.circe::circe-core::0.14.16"""",
        "docs: none",
        "scaladoc: https://www.javadoc.io/doc/io.circe/circe-core_3/0.14.16",
        "readme:",
        "```"
      )
    val card = lines(GetRender.card(circeView(), defaultOpts))
    val readmeBlock = readmeLines.take(30)
    // The card splices the 30-line readme between the two fence lines.
    val tail = List(
      "```",
      "---",
      "Scala API inspection — via cellar:",
      "  cellar deps io.circe:circe-core_3:0.14.16",
      "  cellar get-external io.circe:circe-core_3:0.14.16 <symbol>",
      "Install: cs install --contrib cellar",
      "full data: index4s get circe/circe --json"
    )
    val full = expected ::: readmeBlock ::: tail
    expect.eql(full.size, card.size) && expect.eql(full, card)
  }

  test(
    "golden card: cellar detected → no Install line; --no-hints → no block at all"
  ) {
    val withCellar = lines(
      GetRender.card(circeView(), defaultOpts.copy(cellarDetected = true))
    )
    expect.all(
      !withCellar.contains("Install: cs install --contrib cellar"),
      withCellar.contains("  cellar deps io.circe:circe-core_3:0.14.16"),
      withCellar.contains(
        "  cellar get-external io.circe:circe-core_3:0.14.16 <symbol>"
      )
    )
    val noHints =
      lines(GetRender.card(circeView(), defaultOpts.copy(noHints = true)))
    expect.all(
      !noHints.exists(_.startsWith("Scala API inspection")),
      !noHints.contains("---")
    )
  }

  test(
    "degraded card: versions/latest failed → — cells, default: —, no cellar block"
  ) {
    val view = GetCommand.GetView(
      enriched = EnrichedProject(
        org = "circe",
        repo = "circe",
        project = Some(circeProject),
        latestRefs = None,
        defaultArtifact = None,
        artifactDetails = None,
        failures = List(ApiError.NotFound("circe/circe latest versions"))
      ),
      readme = None,
      pinned = None,
      resolution = GetCommand.Resolution("circe/circe", "circe/circe", None),
      notices = List("notice: circe/circe latest versions not found")
    )
    val card = lines(GetRender.card(view, defaultOpts))
    expect.eql(
      List(
        "circe/circe | Apache-2.0 | category: json | ★ 2,542 | forks 546 | issues 128",
        "Yet another JSON library for Scala — http://circe.io/circe/",
        "topics: generic-derivation, json, scala",
        "latest: — (—) — —   [— versions]",
        "default: —",
        "docs: none",
        "scaladoc: —",
        "readme: off",
        "full data: index4s get circe/circe --json"
      ),
      card
    )
  }

  test("--artifacts: full 108-ref list appended, fully-suffixed") {
    val card =
      lines(GetRender.card(circeView(), defaultOpts.copy(artifacts = true)))
    expect.all(
      card.contains("artifacts (108):"),
      card.contains("  io.circe:circe-core_3:0.14.16"),
      card.contains("  io.circe:circe-numbers-testing_native0.5_3:0.14.16")
    )
  }

  test("platform-suffixed default → single-colon fully-qualified snippets") {
    // --target native selection: circe-core_native0.5_3 (in the fixture refs)
    val native = circeView().copy(enriched =
      circeView().enriched.copy(
        defaultArtifact =
          circeRefs.find(_.artifactId == "circe-core_native0.5_3")
      )
    )
    val card = lines(GetRender.card(native, defaultOpts))
    expect.all(
      card.contains("default: io.circe:circe-core_native0.5_3:0.14.16"),
      card.contains(
        """  sbt       "io.circe" % "circe-core_native0.5_3" % "0.14.16""""
      ),
      card.contains(
        """  mill      ivy"io.circe:circe-core_native0.5_3:0.14.16""""
      ),
      card.contains(
        """  scala-cli dep"io.circe:circe-core_native0.5_3:0.14.16""""
      ),
      !card.exists(_.contains("%%"))
    )
  }

  test("--json round-trip: our own output re-decodes to the same GetJson") {
    val json =
      GetRender.toJson(circeView(readme = None), cellarDetected = false)
    val decoded = read[GetRender.GetJson](write(json))
    expect.eql(json, decoded)
  }

  test("field paths: stars → fixture truth 2542 (no trailing .0)") {
    GetRender
      .field(circeView(), cellarDetected = false, "stars")
      .fold(failure, expect.eql("2542", _))
  }

  test("field paths: org → bare string; scalars stay pipe-friendly") {
    GetRender
      .field(circeView(), false, "org")
      .fold(failure, expect.eql("circe", _)) and
      GetRender
        .field(circeView(), false, "default.version")
        .fold(failure, expect.eql("0.14.16", _))
  }

  test("field paths: docLinks.0.label over the play-json view (User Guide)") {
    val playJson = read[ProjectResponse](Fixture.text("project-play-json.json"))
    val view = GetCommand.GetView(
      enriched = EnrichedProject(
        org = "playframework",
        repo = "play-json",
        project = Some(playJson),
        latestRefs = None,
        defaultArtifact =
          Some(ArtifactRef("org.playframework", "play-json_3", "3.0.6")),
        artifactDetails = None
      ),
      readme = None,
      pinned = None,
      resolution = GetCommand.Resolution(
        "playframework/play-json",
        "playframework/play-json",
        None
      )
    )
    GetRender
      .field(view, false, "docLinks.0.label")
      .fold(failure, expect.eql("User Guide", _))
  }

  test(
    "field paths: unknown path → Left listing available keys; int index into empty array"
  ) {
    val unknown = GetRender.field(circeView(), false, "bogus")
    val badIndex = GetRender.field(circeView(), false, "docLinks.0.label")
    expect.all(
      unknown.isLeft,
      unknown.left.exists(_.contains("unknown field 'bogus'")),
      unknown.left.exists(_.contains("available:")),
      unknown.left.exists(_.contains("stars")),
      badIndex.left.exists(_.contains("invalid index '0'"))
    )
  }
}
