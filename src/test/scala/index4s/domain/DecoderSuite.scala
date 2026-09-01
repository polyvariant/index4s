package index4s.domain

import java.time.Instant
import upickle.default.*
import weaver.FunSuite

object DecoderSuite extends FunSuite {

  test("ProjectResponse — circe (full decode + spot checks)") {
    val p = read[ProjectResponse](Fixture.text("project-circe.json"))
    expect.all(
      p.organization == "circe",
      p.repository == "circe",
      p.stars == Some(2542),
      p.forks == Some(546),
      p.issues == Some(128),
      p.license == Some("Apache-2.0"),
      p.category == Some("json"),
      p.defaultArtifact == Some("circe-core"),
      p.topics.contains("json"),
      p.documentationLinks == Nil,
      p.contributorsWanted == false,
      p.contributingGuide.isDefined,
      p.customScalaDoc == None,
      p.chatroom == None
    )
  }

  test("ProjectResponse — play-json documentationLinks shape (label/pattern)") {
    val p = read[ProjectResponse](Fixture.text("project-play-json.json"))
    expect.eql(
      p.documentationLinks,
      List(
        DocumentationLink(
          "User Guide",
          "https://www.playframework.com/documentation/latest/ScalaJson"
        )
      )
    ) && expect.all(
      p.contributorsWanted == true,
      p.homepage == Some(""),
      p.license == Some("Apache-2.0")
    )
  }

  test(
    "ProjectResponse — weaver-test (absent optional fields decode as None/Nil)"
  ) {
    val p = read[ProjectResponse](Fixture.text("project-weaver-test.json"))
    expect.all(
      p.organization == "typelevel",
      p.repository == "weaver-test",
      p.license == None,
      p.category == None,
      p.defaultArtifact == None,
      p.topics == Nil,
      p.stars == Some(85),
      p.chatroom == None
    )
  }

  test("versions/latest — array of fully-suffixed ArtifactRefs") {
    val refs = read[List[ArtifactRef]](Fixture.text("latest-circe.json"))
    expect.all(
      refs.size == 108,
      refs.exists(r =>
        r.groupId == "io.circe" && r.artifactId == "circe-core_3"
      ),
      refs.exists(r => r.artifactId == "circe-core_native0.5_3"),
      refs.exists(r => r.artifactId == "circe-core_sjs1_3")
    )
  }

  test("ArtifactResponse — ISO-8601 releaseDate → Instant") {
    val a =
      read[ArtifactResponse](Fixture.text("artifact-circe-core-latest.json"))
    expect.all(
      a.groupId == "io.circe",
      a.artifactId == "circe-core_3",
      a.version == "0.14.16",
      a.name == "circe-core",
      a.binaryVersion == "_3",
      a.language == "3",
      a.platform == "jvm",
      a.project == ProjectRef("circe", "circe"),
      a.releaseDate == Instant.parse("2026-06-24T16:34:49Z"),
      a.licenses == List("Apache-2.0")
    )
  }

  test("ArtifactResponse — zio-json + play-json decode") {
    val zio =
      read[ArtifactResponse](Fixture.text("artifact-zio-json-latest.json"))
    val play =
      read[ArtifactResponse](Fixture.text("artifact-play-json-latest.json"))
    expect.all(
      zio.releaseDate == Instant.parse("2026-04-22T12:18:34Z"),
      zio.version == "0.9.2",
      play.groupId == "com.typesafe.play",
      play.releaseDate == Instant.parse("2025-10-10T10:25:20Z")
    )
  }

  test("thin search array decodes (logo null tolerated, order preserved)") {
    val results = read[List[SearchResult]](Fixture.text("search-json.json"))
    expect.all(
      results.size == 20,
      results.head.organization == "zio",
      results.head.repository == "zio-json",
      results.head.logo.isDefined,
      results.exists(r =>
        r.organization == "playframework" && r.artifacts.contains("play-json")
      ),
      results.head.deprecatedArtifacts == Nil
    )
  }

  test("autocomplete decodes (description required on wire)") {
    val hits =
      read[List[AutocompleteResult]](Fixture.text("autocomplete-circe.json"))
    expect.all(
      hits.size == 5,
      hits.head == AutocompleteResult(
        "circe",
        "circe",
        "Yet another JSON library for Scala"
      )
    )
  }

  test("jawn_3 404 — empty body fails to decode (expected-failure path)") {
    val body = Fixture.text("artifact-jawn-404.txt")
    expect(scala.util.Try(read[ArtifactResponse](body)).isFailure)
  }

  test("readme fixture loads as markdown") {
    val md = Fixture.text("readme-circe.md")
    expect(md.startsWith("# circe") && md.length > 1000)
  }
}
