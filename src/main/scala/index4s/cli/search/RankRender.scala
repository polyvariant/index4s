package index4s.cli.search

import index4s.cli.Sort
import index4s.cli.get.GetCommand
import index4s.client.Target
import index4s.core.EnrichedProject
import index4s.domain.{Freshness, Tier}
import java.time.Instant
import upickle.default.*

/** Rendering for `search --rank`: the decision table and its `--json` envelope.
  * PURE: RankSearch assembles the ordered [[EnrichedProject]] rows + a fixed
  * `now`; this object turns them into strings.
  *
  * Column model (deterministic; cells joined by exactly TWO spaces, each cell
  * padded to its own width, last column unpadded so lines carry no trailing
  * whitespace):
  *   - LIBRARY left-justified, width = max(20, longest org/repo + 2)
  *   - STARS right-justified, width = max(5, longest stars cell)
  *   - LATEST RELEASE left-justified, width = max(14, longest release cell)
  *   - LICENSE left-justified, width = max(7, longest license cell)
  *   - CATEGORY left-justified (unpadded, terminal column)
  */
object RankRender {

  /** Floor for the LIBRARY column (`max org/repo len + 2`, min 20). */
  val LibraryMinWidth: Int = 20

  /** The tier legend / provenance line. The en-dash in `9–18mo` and the glyphs
    * match Freshness's day-based boundaries.
    */
  val LegendLine: String =
    "  ✓ <9mo · 💤 9–18mo · 💀 >18mo · — = signal unavailable"

  /** Next-steps line: the natural flow into `get` and on to cellar. */
  val InspectLine: String =
    "  inspect: index4s get <org/repo> · API: cellar deps <coordinate> (see get output)"

  /** Duplicated from [[index4s.cli.get.CellarHint.block]]; keep the two
    * literals in sync.
    */
  val InstallLine: String = "Install: cs install --contrib cellar"

  def sortLabel(sort: Sort): String = sort match {
    case Sort.Stars => "stars"
    case Sort.Fresh => "fresh"
  }

  def tierName(t: Tier): String = t match {
    case Tier.Active => "active"
    case Tier.Sleepy => "sleepy"
    case Tier.Dead   => "dead"
  }

  /** Thousands-grouped decimal ("2542" → "2,542"). Hand-rolled so rendering
    * never depends on native Formatter coverage.
    */
  def fmt(v: Int): String = v.toString.reverse.grouped(3).mkString(",").reverse

  /** Humanized age on Freshness's 30.44-day month: <12 → `2mo`; ≥12 → `1y9m`
    * with zero months omitted (`2y`); future/negative ages clamp to `0mo`.
    * Display only — tiering itself stays day-based (Freshness.tierOf), so a row
    * can legitimately read `18mo 💀` at the exact 548-day edge.
    */
  def humanizeMonths(days: Long): String = {
    val months = math.max(0L, (days.toDouble / 30.44).toLong)
    if months < 12 then s"${months}mo"
    else {
      val y = (months / 12).toInt
      val m = (months % 12).toInt
      if m == 0 then s"${y}y" else s"${y}y${m}m"
    }
  }

  private def starsCell(e: EnrichedProject): String = e.stars.fold("—")(fmt)

  /** `{version} · {age}  {glyph}` — version and releaseDate come from the same
    * artifactDetails, so they are missing TOGETHER → `—` with no age and no
    * glyph (the only way tier is None).
    */
  private def releaseCell(e: EnrichedProject, now: Instant): String =
    e.artifactDetails match {
      case None    => "—"
      case Some(d) =>
        s"${d.version} · ${humanizeMonths(Freshness.ageDays(d.releaseDate, now))}" +
          e.tier(now).fold("")(t => s"  ${t.symbol}")
    }

  private def padLeft(s: String, w: Int): String =
    " " * math.max(0, w - s.length) + s

  /** The decision table: header line, blank, column header, rows, blank,
    * legend, inspect line (the inspect line is cellar-hint territory —
    * `--no-hints` drops it; the legend stays, it is table provenance). `rows`
    * render in the order given — sorting is RankSearch's job.
    */
  def table(
      rows: List[EnrichedProject],
      sort: Sort,
      target: Target,
      now: Instant,
      noHints: Boolean = false
  ): String = {
    val n = rows.length
    val head =
      s"${if n == 1 then "1 candidate" else s"$n candidates"} · target ${ThinRender.targetKind(target)}" +
        s" · scala ${scalaVersionOf(target)} · sorted by ${sortLabel(sort)}"

    val libraries = rows.map(r => s"${r.org}/${r.repo}")
    val starsCells = rows.map(starsCell)
    val releaseCs = rows.map(releaseCell(_, now))
    val licenseCs = rows.map(r => r.project.flatMap(_.license).getOrElse("—"))
    val categoryCs = rows.map(r => r.project.flatMap(_.category).getOrElse("—"))
    val wLib = math.max(
      LibraryMinWidth,
      libraries.map(_.length).maxOption.getOrElse(0) + 2
    )
    val wStars =
      math.max("STARS".length, starsCells.map(_.length).maxOption.getOrElse(0))
    val wRel = math.max(
      "LATEST RELEASE".length,
      releaseCs.map(_.length).maxOption.getOrElse(0)
    )
    val wLic =
      math.max("LICENSE".length, licenseCs.map(_.length).maxOption.getOrElse(0))

    def line(
        lib: String,
        stars: String,
        rel: String,
        lic: String,
        cat: String
    ): String =
      "  " + lib.padTo(wLib, ' ') + "  " + padLeft(stars, wStars) + "  " +
        rel.padTo(wRel, ' ') + "  " + lic.padTo(wLic, ' ') + "  " + cat

    val columns =
      line("LIBRARY", "STARS", "LATEST RELEASE", "LICENSE", "CATEGORY")
    val body = libraries.indices.toList.map { i =>
      line(
        libraries(i),
        starsCells(i),
        releaseCs(i),
        licenseCs(i),
        categoryCs(i)
      )
    }

    val footer = LegendLine :: (if noHints then Nil else List(InspectLine))
    (head :: "" :: columns :: body ::: List("") ::: footer).mkString("\n")
  }

  private def scalaVersionOf(target: Target): String = target match {
    case Target.Jvm(sv)       => sv
    case Target.Js(sv, _)     => sv
    case Target.Native(sv, _) => sv
    case Target.Sbt(sv, _)    => sv
  }

  // --- degradation notices (stderr) ---------------------------------------------

  /** One `— {org}/{repo}: {ApiError message}` per failed sub-lookup (same
    * wording as `get` notices), in table order; then the count line exactly
    * when at least one row degraded. These ride Out.notes → stderr.
    */
  def degradationNotes(rows: List[EnrichedProject]): List[String] = {
    val perRow = rows.flatMap { r =>
      r.failures.map(err =>
        s"— ${r.org}/${r.repo}: ${GetCommand.describe(err)}"
      )
    }
    val degraded = rows.count(_.failures.nonEmpty)
    perRow ++ Option
      .when(degraded > 0)(
        s"$degraded of ${rows.length} rows have missing signals (— cells)"
      )
      .toList
  }

  /** The cellar Install notice rides stderr (not the table) when cellar is
    * absent and hints are enabled — mirrors CellarHint.block's rule.
    */
  def installNote(cellarDetected: Boolean, noHints: Boolean): List[String] =
    if !noHints && !cellarDetected then List(InstallLine) else Nil

  final case class RankMeta(
      query: String,
      target: String,
      sort: String,
      count: Int
  ) derives ReadWriter

  /** One row of the `--json` results array. `latestVersion` falls back to the
    * selected default ref's version (knowable from versions/latest even when
    * artifacts/latest 404'd — the jawn case); `tier`/`releaseDate` stay null
    * there because the date is genuinely unavailable. `degraded` carries the
    * same messages as the stderr notes ([] when the row is fully enriched).
    */
  final case class RankResult(
      id: String,
      stars: Option[Int],
      latestVersion: Option[String],
      releaseDate: Option[String],
      tier: Option[String],
      license: Option[String],
      category: Option[String],
      platformSummary: Option[String],
      defaultCoordinate: Option[String],
      degraded: List[String]
  ) derives ReadWriter

  final case class RankJson(
      meta: RankMeta,
      results: List[RankResult]
  ) derives ReadWriter

  def jsonPayload(
      query: String,
      target: Target,
      sort: Sort,
      rows: List[EnrichedProject],
      now: Instant
  ): String =
    write(
      RankJson(
        meta = RankMeta(
          query,
          ThinRender.targetKind(target),
          sortLabel(sort),
          rows.length
        ),
        results = rows.map { r =>
          RankResult(
            id = s"${r.org}/${r.repo}",
            stars = r.stars,
            latestVersion = r.artifactDetails
              .map(_.version)
              .orElse(r.defaultArtifact.map(_.version)),
            releaseDate = r.releaseDate.map(_.toString),
            tier = r.tier(now).map(tierName),
            license = r.project.flatMap(_.license),
            category = r.project.flatMap(_.category),
            platformSummary = r.platformSummary.map(_.render),
            defaultCoordinate = r.defaultArtifact.map(a =>
              s"${a.groupId}:${a.artifactId}:${a.version}"
            ),
            degraded = r.failures.map(GetCommand.describe)
          )
        }
      )
    )
}
