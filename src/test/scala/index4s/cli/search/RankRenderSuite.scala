package index4s.cli.search

import index4s.cli.Sort
import index4s.client.{ApiError, Target}
import index4s.core.EnrichedProject
import index4s.domain.*
import weaver.FunSuite

import java.time.Instant
import upickle.default.*

/** Rendering tests (pure — no IO, no stubs): the golden decision table
  * (line-by-line, small diffs per the weaver-native lesson), column-width
  * rules, age humanization, degradation notices, and the --json envelope
  * round-trip.
  *
  * Golden width derivation (Scala String.length semantics — 💤/💀 are surrogate
  * pairs and count 2): LIBRARY max(20, 23+2)=25 · STARS max(5,5)=5 · RELEASE
  * max(14,16)=16 · LICENSE max(7,10)=10 · CATEGORY is the unpadded terminal
  * column.
  */
object RankRenderSuite extends FunSuite {

  // expect.eql needs Eq — test-scoped given riding structural ==.
  private given cats.kernel.Eq[RankRender.RankJson] =
    cats.kernel.Eq.fromUniversalEquals

  private val now: Instant = Instant.parse("2026-09-01T00:00:00Z")

  private def enriched(
      org: String,
      repo: String,
      stars: Option[Int],
      version: Option[String],
      date: Option[Instant],
      license: Option[String] = None,
      category: Option[String] = None,
      failures: List[ApiError] = Nil
  ): EnrichedProject =
    EnrichedProject(
      org = org,
      repo = repo,
      project = Some(
        ProjectResponse(
          org,
          repo,
          stars = stars,
          license = license,
          category = category
        )
      ),
      latestRefs = version.map(v => List(ArtifactRef(org, s"${repo}_3", v))),
      defaultArtifact = version.map(v => ArtifactRef(org, s"${repo}_3", v)),
      artifactDetails =
        for {
          v <- version
          d <- date
        } yield ArtifactResponse(
          groupId = org,
          artifactId = s"${repo}_3",
          version = v,
          name = repo,
          binaryVersion = "_3",
          language = "3",
          platform = "jvm",
          project = ProjectRef(org, repo),
          releaseDate = d,
          licenses = Nil
        ),
      failures = failures
    )

  private def daysAgo(n: Long): Instant = now.minusSeconds(n * 86400L)

  private val golden: List[EnrichedProject] = List(
    enriched(
      "circe",
      "circe",
      Some(2542),
      Some("0.14.16"),
      Some(daysAgo(69)),
      license = Some("Apache-2.0"),
      category = Some("json")
    ),
    enriched(
      "playframework",
      "play-json",
      Some(375),
      Some("3.0.2"),
      Some(daysAgo(340)),
      license = Some("Apache-2.0"),
      category = Some("json")
    ),
    enriched(
      "json4s",
      "json4s",
      Some(1485),
      Some("4.0.7"),
      Some(daysAgo(1000)),
      license = Some("Apache-2.0"),
      category = Some("json")
    ),
    enriched("tethys-json", "tethys", None, Some("0.29.8"), Some(daysAgo(40))),
    EnrichedProject(
      org = "jawn",
      repo = "jawn",
      project = None,
      latestRefs = None,
      defaultArtifact = None,
      artifactDetails = None,
      failures = List(ApiError.NotFound("jawn/jawn"))
    )
  )

  private val goldenLines: List[String] =
    RankRender
      .table(golden, Sort.Stars, Target.Jvm(), now)
      .split("\n", -1)
      .toList

  test("golden table: header line carries count, target, scala, sort") {
    expect.eql(
      "5 candidates · target jvm · scala 3 · sorted by stars",
      goldenLines.head
    )
  }

  test("golden table: column header row is exactly this line") {
    expect.eql(
      "  LIBRARY                    STARS  LATEST RELEASE    LICENSE     CATEGORY",
      goldenLines(2)
    )
  }

  test("golden table: fresh row — ✓ glyph, thousands stars, license+category") {
    expect.eql(
      "  circe/circe                2,542  0.14.16 · 2mo  ✓  Apache-2.0  json",
      goldenLines(3)
    )
  }

  test("golden table: sleepy row — 💤 glyph, humanized 11mo") {
    expect.eql(
      "  playframework/play-json      375  3.0.2 · 11mo  💤  Apache-2.0  json",
      goldenLines(4)
    )
  }

  test("golden table: dead row — 💀 glyph, 2y8m humanization") {
    expect.eql(
      "  json4s/json4s              1,485  4.0.7 · 2y8m  💀  Apache-2.0  json",
      goldenLines(5)
    )
  }

  test(
    "golden table: missing stars → right-justified —; missing license/category → —"
  ) {
    expect.eql(
      "  tethys-json/tethys             —  0.29.8 · 1mo  ✓   —           —",
      goldenLines(6)
    )
  }

  test(
    "golden table: fully degraded row — — in every cell, row NEVER dropped"
  ) {
    expect.eql(
      "  jawn/jawn                      —  —                 —           —",
      goldenLines(7)
    )
  }

  test("golden table: footer is legend + inspect; 11 lines total") {
    expect.eql(11, goldenLines.length) &&
    expect.eql("", goldenLines(1)) &&
    expect.eql("", goldenLines(8)) &&
    expect.eql(
      "  ✓ <9mo · 💤 9–18mo · 💀 >18mo · — = signal unavailable",
      goldenLines(9)
    ) &&
    expect.eql(
      "  inspect: index4s get <org/repo> · API: cellar deps <coordinate> (see get output)",
      goldenLines(10)
    )
  }

  test("table: no-hints drops the inspect line, keeps the legend") {
    val lines =
      RankRender
        .table(golden, Sort.Stars, Target.Jvm(), now, noHints = true)
        .split("\n", -1)
        .toList
    expect.eql(10, lines.length) &&
    expect.eql(RankRender.LegendLine, lines.last)
  }

  test("table: empty row set renders the empty table") {
    val lines = RankRender
      .table(Nil, Sort.Fresh, Target.Js("2.13", "1"), now)
      .split("\n", -1)
      .toList
    expect.eql(6, lines.length) &&
    expect.eql(
      "0 candidates · target js · scala 2.13 · sorted by fresh",
      lines.head
    ) &&
    expect.eql(
      "  LIBRARY               STARS  LATEST RELEASE  LICENSE  CATEGORY",
      lines(2)
    ) &&
    expect.eql(RankRender.LegendLine, lines(4)) &&
    expect.eql(RankRender.InspectLine, lines(5))
  }

  test(
    "table: long org/repo widens LIBRARY past the 20 floor; singular count"
  ) {
    val rows = List(
      enriched(
        "very-long-organization-name",
        "very-long-repository-name",
        Some(12),
        Some("1.0.0"),
        Some(daysAgo(10))
      )
    )
    val lines = RankRender
      .table(rows, Sort.Stars, Target.Jvm(), now)
      .split("\n", -1)
      .toList
    // library 53 chars + 2 → width 55
    expect.eql(
      "1 candidate · target jvm · scala 3 · sorted by stars",
      lines.head
    ) &&
    expect.eql(
      "  very-long-organization-name/very-long-repository-name       12  1.0.0 · 0mo  ✓  —        —",
      lines(3)
    )
  }

  test(
    "humanizeMonths: <12 → n mo; ≥12 → y m with zero months omitted; future → 0mo"
  ) {
    expect.eql("0mo", RankRender.humanizeMonths(0L)) &&
    expect.eql("0mo", RankRender.humanizeMonths(29L)) &&
    expect.eql("2mo", RankRender.humanizeMonths(69L)) &&
    expect.eql("9mo", RankRender.humanizeMonths(274L)) &&
    expect.eql("11mo", RankRender.humanizeMonths(335L)) &&
    expect.eql("1y", RankRender.humanizeMonths(366L)) &&
    expect.eql("1y3m", RankRender.humanizeMonths(457L)) &&
    expect.eql("2y8m", RankRender.humanizeMonths(1000L)) &&
    expect.eql("0mo", RankRender.humanizeMonths(-100L))
  }

  test("fmt: thousands grouping") {
    expect.eql("2,542", RankRender.fmt(2542)) &&
    expect.eql("431", RankRender.fmt(431)) &&
    expect.eql("1,234,567", RankRender.fmt(1234567)) &&
    expect.eql("0", RankRender.fmt(0))
  }

  test("tierName: wire strings") {
    expect.eql("active", RankRender.tierName(Tier.Active)) &&
    expect.eql("sleepy", RankRender.tierName(Tier.Sleepy)) &&
    expect.eql("dead", RankRender.tierName(Tier.Dead))
  }

  test("degradationNotes: clean rows → no notes at all") {
    expect.eql(Nil: List[String], RankRender.degradationNotes(golden.take(3)))
  }

  test(
    "degradationNotes: one notice per failure + count line when any row degraded"
  ) {
    val rows = List(
      enriched(
        "a",
        "a",
        Some(1),
        Some("1.0.0"),
        Some(daysAgo(10)),
        failures =
          List(ApiError.Server(503), ApiError.NotFound("a/a latest versions"))
      ),
      enriched(
        "b",
        "b",
        None,
        None,
        None,
        failures = List(ApiError.NotFound("b/b"))
      )
    )
    expect.eql(
      List(
        "— a/a: HTTP 503",
        "— a/a: a/a latest versions not found",
        "— b/b: b/b not found",
        "2 of 2 rows have missing signals (— cells)"
      ),
      RankRender.degradationNotes(rows)
    )
  }

  test("installNote: exactly when cellar absent AND hints enabled") {
    expect.eql(
      List(RankRender.InstallLine),
      RankRender.installNote(cellarDetected = false, noHints = false)
    ) &&
    expect.eql(
      Nil: List[String],
      RankRender.installNote(cellarDetected = true, noHints = false)
    ) &&
    expect.eql(
      Nil: List[String],
      RankRender.installNote(cellarDetected = false, noHints = true)
    )
  }

  private val jsonRows: List[EnrichedProject] = List(
    enriched(
      "circe",
      "circe",
      Some(2542),
      Some("0.14.16"),
      Some(Instant.parse("2026-06-24T16:34:49Z")),
      license = Some("Apache-2.0"),
      category = Some("json")
    ),
    enriched(
      "jawn",
      "jawn",
      None,
      None,
      None,
      failures = List(ApiError.NotFound("jawn/jawn"))
    ),
    // jawn-style partial: ref selected (version survives) but no artifact
    // details — date/tier null, latestVersion falls back to the ref version.
    enriched("zio", "zio-json", Some(431), Some("1.0.0"), None)
  )

  test("json: envelope prefix, meta fields, round-trip re-decode") {
    val payload = RankRender.jsonPayload(
      "json AND topics:json",
      Target.Jvm(),
      Sort.Fresh,
      jsonRows,
      now
    )
    expect(
      payload.startsWith(
        """{"meta":{"query":"json AND topics:json","target":"jvm","sort":"fresh","count":3}"""
      )
    ) &&
    expect.eql(
      RankRender.RankJson(
        meta = RankRender.RankMeta("json AND topics:json", "jvm", "fresh", 3),
        results = List(
          RankRender.RankResult(
            id = "circe/circe",
            stars = Some(2542),
            latestVersion = Some("0.14.16"),
            releaseDate = Some("2026-06-24T16:34:49Z"),
            tier = Some("active"),
            license = Some("Apache-2.0"),
            category = Some("json"),
            platformSummary = Some("scala 3"),
            defaultCoordinate = Some("circe:circe_3:0.14.16"),
            degraded = Nil
          ),
          RankRender.RankResult(
            id = "jawn/jawn",
            stars = None,
            latestVersion = None,
            releaseDate = None,
            tier = None,
            license = None,
            category = None,
            platformSummary = None,
            defaultCoordinate = None,
            degraded = List("jawn/jawn not found")
          ),
          RankRender.RankResult(
            id = "zio/zio-json",
            stars = Some(431),
            latestVersion = Some("1.0.0"),
            releaseDate = None,
            tier = None,
            license = None,
            category = None,
            platformSummary = Some("scala 3"),
            defaultCoordinate = Some("zio:zio-json_3:1.0.0"),
            degraded = Nil
          )
        )
      ),
      read[RankRender.RankJson](payload)
    )
  }
}
