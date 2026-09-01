package index4s.cli.search

import cats.kernel.Eq
import index4s.client.Target
import index4s.domain.SearchResult
import weaver.FunSuite

import upickle.default.*

object ThinRenderSuite extends FunSuite {

  private given Eq[SearchResult] = Eq.fromUniversalEquals

  private val zioJson = SearchResult(
    organization = "zio",
    repository = "zio-json",
    artifacts =
      List("zio-json", "zio-json-docs", "zio-json-golden", "zio-json-yaml")
  )
  private val playJson = SearchResult(
    organization = "playframework",
    repository = "play-json",
    artifacts = List("play-functional", "play-json", "play-json-joda")
  )
  private val legacy = SearchResult(
    organization = "old",
    repository = "legacy",
    artifacts = List("legacy-core", "legacy-extra"),
    deprecatedArtifacts = List("legacy-old", "legacy-older")
  )

  test(
    "golden: 3-row thin list — header, blank line, column alignment, 3-artifact truncation, deprecated suffix"
  ) {
    val expected = List(
      "3 projects (Scaladex relevance, target jvm · scala 3) — use --rank for stars/freshness comparison",
      "",
      "  zio/zio-json               artifacts: zio-json, zio-json-docs, zio-json-golden…",
      "  playframework/play-json    artifacts: play-functional, play-json, play-json-joda",
      "  old/legacy                 artifacts: legacy-core, legacy-extra (deprecated: legacy-old, legacy-older)"
    )
    expect.eql(
      expected,
      ThinRender
        .thin(List(zioJson, playJson, legacy), Target.Jvm("3"))
        .split("\n", -1)
        .toList
    )
  }

  test(
    "header variants: singular count; non-JVM target label carries the platform version"
  ) {
    expect.all(
      ThinRender
        .header(1, Target.Jvm("3"))
        .startsWith("1 project (Scaladex relevance"),
      ThinRender
        .header(1, Target.Js("2.13", "0.6"))
        .contains("target js · scala 2.13 · sjs 0.6"),
      ThinRender
        .header(4, Target.Native("3", "0.5"))
        .contains("target native · scala 3 · native 0.5"),
      ThinRender
        .header(4, Target.Sbt("2.12", "1.0"))
        .contains("target sbt · scala 2.12 · sbt 1.0")
    )
  }

  test(
    "row edge: artifact-less rows render the missing-cell marker; exactly-3 artifacts get no ellipsis"
  ) {
    expect.eql(
      "  x/y    artifacts: —",
      ThinRender.row(SearchResult("x", "y"), 3)
    ) && expect.eql(
      "  a/b    artifacts: one, two, three",
      ThinRender.row(
        SearchResult("a", "b", artifacts = List("one", "two", "three")),
        3
      )
    )
  }

  test(
    "--json: exact envelope prefix and full round-trip through ThinRender.SearchJson"
  ) {
    val rows = List(zioJson, playJson)
    val payload = ThinRender.jsonPayload(
      "json AND topics:json",
      Target.Js("2.13", "1"),
      rows
    )
    val decoded = read[ThinRender.SearchJson](payload)
    expect(
      payload.startsWith(
        """{"meta":{"query":"json AND topics:json","target":"js","scalaVersion":"2.13","count":2},"results":["""
      )
    ) && expect.all(
      decoded.meta.query == "json AND topics:json",
      decoded.meta.target == "js",
      decoded.meta.scalaVersion == "2.13",
      decoded.meta.count == 2,
      decoded.results == rows
    )
  }
}
