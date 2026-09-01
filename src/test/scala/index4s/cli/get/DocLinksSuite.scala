package index4s.cli.get

import index4s.domain.{DocumentationLink, Fixture, ProjectResponse}
import upickle.default.*
import weaver.FunSuite

/** DocLinks: placeholder evaluation against the SELECTED artifact, and the
  * scaladoc resolution order (customScalaDoc → documentationLinks → javadoc.io
  * fallback). Uses the real play-json project capture — the only fixture with a
  * non-empty documentationLinks array.
  */
object DocLinksSuite extends FunSuite {

  given cats.kernel.Eq[DocLinks.Evaluated] = cats.kernel.Eq.fromUniversalEquals

  private val ctx = DocLinks.Context(
    groupId = "io.circe",
    artifactId = "circe-core_3",
    version = "0.14.16",
    name = "circe-core"
  )

  private val playJson =
    read[ProjectResponse](Fixture.text("project-play-json.json"))

  test("evaluate: all placeholders substituted against the selected artifact") {
    expect.eql(
      "g=io.circe a=circe-core_3 v=0.14.16 n=circe-core major=0.14 minor=0.14.16",
      DocLinks.evaluate(
        "g=[groupId] a=[artifactId] v=[version] n=[name] major=[major] minor=[minor]",
        ctx
      )
    )
  }

  test("major: 0.14.16 → 0.14 · single-segment and long versions") {
    expect.all(
      DocLinks.major("0.14.16") == "0.14",
      DocLinks.major("3") == "3",
      DocLinks.major("1.0.0-M1") == "1.0"
    )
  }

  test("docs: play-json User Guide link evaluated verbatim (no placeholders)") {
    val guide = DocLinks.docs(playJson, ctx)
    expect.eql(
      List(
        DocLinks.Evaluated(
          "User Guide",
          "https://www.playframework.com/documentation/latest/ScalaJson"
        )
      ),
      guide
    )
  }

  test("docs: empty documentationLinks (circe) → empty list") {
    val circe = read[ProjectResponse](Fixture.text("project-circe.json"))
    expect.eql(List.empty[DocLinks.Evaluated], DocLinks.docs(circe, ctx))
  }

  test("scaladoc: customScalaDoc pattern wins over documentationLinks") {
    val project = playJson.copy(customScalaDoc =
      Some(
        "https://static.javadoc.io/[groupId]/[artifactId]/[major]/index.html"
      )
    )
    expect.eql(
      "https://static.javadoc.io/io.circe/circe-core_3/0.14/index.html",
      DocLinks.scaladoc(project, ctx)
    )
  }

  test("scaladoc: no customScalaDoc → first documentationLink pattern") {
    expect.eql(
      "https://www.playframework.com/documentation/latest/ScalaJson",
      DocLinks.scaladoc(playJson, ctx)
    )
  }

  test(
    "scaladoc: neither → javadoc.io/doc fallback with fully-suffixed coordinates"
  ) {
    val bare = ProjectResponse(organization = "o", repository = "r")
    expect.eql(
      "https://www.javadoc.io/doc/io.circe/circe-core_3/0.14.16",
      DocLinks.scaladoc(bare, ctx)
    )
  }
}
