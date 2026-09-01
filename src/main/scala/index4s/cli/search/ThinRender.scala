package index4s.cli.search

import index4s.client.Target
import index4s.domain.SearchResult
import upickle.default.*

/** Rendering for thin `search`: a markdown list of org/repo + first artifact
  * names in Scaladex relevance order, or a `{"meta":...,"results":[...]}`
  * envelope for `--json`. NO stars, NO enrichment, NO color: this mode is thin
  * BY DESIGN (`--rank` owns the comparison table).
  */
object ThinRender {

  /** `--json` envelope header — everything needed to reproduce the search: the
    * effective (topic-folded) query, the wire target kind, the scala binary
    * version, and the number of rows SHOWN (not the server total).
    */
  final case class SearchMeta(
      query: String,
      target: String,
      scalaVersion: String,
      count: Int
  ) derives ReadWriter

  final case class SearchJson(
      meta: SearchMeta,
      results: List[SearchResult]
  ) derives ReadWriter

  /** Human target label for headers: `jvm · scala 3`, platform version included
    * for non-JVM targets so the header fully identifies what was searched.
    */
  def targetLabel(target: Target): String = target match {
    case Target.Jvm(sv)        => s"jvm · scala $sv"
    case Target.Js(sv, sjs)    => s"js · scala $sv · sjs $sjs"
    case Target.Native(sv, nv) => s"native · scala $sv · native $nv"
    case Target.Sbt(sv, sbtv)  => s"sbt · scala $sv · sbt $sbtv"
  }

  /** Wire target kind for --json meta (`jvm|js|native|sbt`, lowercase). */
  def targetKind(target: Target): String = target match {
    case Target.Jvm(_)       => "jvm"
    case Target.Js(_, _)     => "js"
    case Target.Native(_, _) => "native"
    case Target.Sbt(_, _)    => "sbt"
  }

  private def scalaVersionOf(target: Target): String = target match {
    case Target.Jvm(sv)       => sv
    case Target.Js(sv, _)     => sv
    case Target.Native(sv, _) => sv
    case Target.Sbt(sv, _)    => sv
  }

  /** Count header — `N` is the number of rows SHOWN (post-`--limit`), never the
    * server total.
    */
  def header(shown: Int, target: Target): String = {
    val n = if shown == 1 then "1 project" else s"$shown projects"
    s"$n (Scaladex relevance, target ${targetLabel(target)}) — use --rank for stars/freshness comparison"
  }

  /** One list row: two-space indent, org/repo left-justified to `width` (the
    * longest org/repo among shown rows) + a 4-space gutter, first 3 artifact
    * names with `…` when truncated, `(deprecated: a, b)` appended when the row
    * carries deprecated artifacts. An artifact-less row renders `—` (the shared
    * missing-cell convention).
    */
  def row(r: SearchResult, width: Int): String = {
    val orgRepo = s"${r.organization}/${r.repository}"
    val arts =
      if r.artifacts.isEmpty then "—"
      else
        r.artifacts.take(3).mkString(", ") +
          (if r.artifacts.length > 3 then "…" else "")
    val deprecated =
      if r.deprecatedArtifacts.isEmpty then ""
      else s" (deprecated: ${r.deprecatedArtifacts.mkString(", ")})"
    s"  ${orgRepo.padTo(width, ' ')}    artifacts: $arts$deprecated"
  }

  /** The full thin payload: header, blank line, rows in relevance order. */
  def thin(results: List[SearchResult], target: Target): String = {
    val width = results
      .map(r => s"${r.organization}/${r.repository}".length)
      .maxOption
      .getOrElse(0)
    (header(results.length, target) :: "" :: results.map(row(_, width)))
      .mkString("\n")
  }

  /** The --json payload: raw SearchResult objects (full wire fidelity — upickle
    * re-encodes exactly what /api/search returned) under a meta envelope.
    * Round-trips: `read[ThinRender.SearchJson](jsonPayload(...))`.
    */
  def jsonPayload(
      query: String,
      target: Target,
      results: List[SearchResult]
  ): String =
    write(
      SearchJson(
        meta = SearchMeta(
          query = query,
          target = targetKind(target),
          scalaVersion = scalaVersionOf(target),
          count = results.length
        ),
        results = results
      )
    )
}
