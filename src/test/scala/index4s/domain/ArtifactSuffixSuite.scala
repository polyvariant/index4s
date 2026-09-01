package index4s.domain

import Platform.*
import weaver.FunSuite

object ArtifactSuffixSuite extends FunSuite {

  private def suffixOf(id: String) = ArtifactSuffix.parse(id)

  test("suffix table") {
    val cases = List(
      ("cats-core_3", Suffix(Some("3"), Some(Jvm))),
      ("cats-core_2.13", Suffix(Some("2.13"), Some(Jvm))),
      ("circe_sjs1_3", Suffix(Some("3"), Some(Js("1")))),
      ("cats-core_sjs0.6_2.11", Suffix(Some("2.11"), Some(Js("0.6")))),
      ("circe_native0.5_3", Suffix(Some("3"), Some(Native("0.5")))),
      ("circe-core_native0.4_2.12", Suffix(Some("2.12"), Some(Native("0.4")))),
      ("weaver-cats_native0.5_3", Suffix(Some("3"), Some(Native("0.5")))),
      (
        "sbt-scala-native_2.12_1.0",
        Suffix(Some("2.12"), Some(SbtPlugin("1.0")))
      ),
      ("sbt-microsites_2.12_1.0", Suffix(Some("2.12"), Some(SbtPlugin("1.0")))),
      ("foo_sbt1.0_2.12", Suffix(Some("2.12"), Some(SbtPlugin("1.0")))),
      ("scalafix-core_3.3.8", Suffix(Some("3.3.8"), Some(Jvm))),
      ("scalafix-core_2.13.16", Suffix(Some("2.13.16"), Some(Jvm))),
      ("circe_cats-core_3", Suffix(Some("3"), Some(Jvm))),
      ("banana_jvm_2.11", Suffix(Some("2.11"), Some(Jvm))),
      ("pan-domain-auth-play_2-8_2.12", Suffix(Some("2.12"), Some(Jvm)))
    )
    forEach(cases) { case (id, expected) => expect.eql(suffixOf(id), expected) }
  }

  test("mill plugins are NOT plain JVM (unsupported platform → None)") {
    val s = suffixOf("mill-scalafix_mill0.10_2.13")
    expect.all(
      s.scalaBinaryVersion == Some("2.13"),
      s.platform == None
    )
  }

  test("garbage falls back to (no scala, Jvm) and never throws") {
    val garbage =
      List("", "sparrow", "%%%", "foo_", "foo_2-8", "foo_2.0", "foo_1.0")
    forEach(garbage) { id =>
      expect.eql(suffixOf(id), Suffix(None, Some(Jvm)))
    }
  }

  test("degenerate empty-base ids still parse as their suffix (never throw)") {
    expect.all(
      suffixOf("_3") == Suffix(Some("3"), Some(Jvm)),
      suffixOf("__3") == Suffix(Some("3"), Some(Jvm))
    )
  }

  test("scalaBinary normalizes full versions to binary form") {
    expect.all(
      suffixOf("scalafix-core_3.3.8").scalaBinary == Some("3"),
      suffixOf("scalafix-core_2.13.16").scalaBinary == Some("2.13"),
      suffixOf("cats-core_3").scalaBinary == Some("3"),
      suffixOf("cats-core_2.12").scalaBinary == Some("2.12"),
      suffixOf("sparrow").scalaBinary == None
    )
  }

  test("render produces the canonical suffix text") {
    expect.all(
      suffixOf("cats-core_3").render == "3",
      suffixOf("circe_sjs1_3").render == "sjs1_3",
      suffixOf("circe_native0.5_3").render == "native0.5_3",
      suffixOf("sbt-scala-native_2.12_1.0").render == "2.12_1.0",
      suffixOf("scalafix-core_3.3.8").render == "3.3.8"
    )
  }

  test("every captured versions/latest artifactId parses and re-renders") {
    val ids = List(
      "latest-circe.json",
      "latest-tethys.json",
      "latest-weaver.json",
      "latest-zio-json.json"
    )
      .flatMap(name =>
        upickle.default
          .read[List[ArtifactRef]](Fixture.text(name))
          .map(_.artifactId)
      )
      .distinct
    expect(ids.size > 100) &&
    forEach(ids) { id =>
      val s = ArtifactSuffix.parse(id)
      expect.all(
        s.scalaBinaryVersion.isDefined,
        id.endsWith("_" + s.render)
      )
    }
  }
}
