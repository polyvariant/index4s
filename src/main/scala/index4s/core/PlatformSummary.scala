package index4s.core

import cats.kernel.Eq
import index4s.domain.{ArtifactRef, ArtifactSuffix, Platform}

/** Distinct scala/platform coverage of a project's versions/latest refs — pure,
  * total, deterministic (distinct + lexicographically sorted). The data behind
  * the get card / rank-table platform line.
  *
  *   - scalaVersions: NORMALIZED binaries ("3.3.8" → "3") — Suffix.scalaBinary
  *   - js entries: "sjs<v>" (e.g. "sjs1"); native entries: "native<v>"
  *   - sbtPlugins: at least one sbt-suffixed ref exists
  */
final case class PlatformSummary(
    scalaVersions: List[String],
    js: List[String],
    native: List[String],
    sbtPlugins: Boolean
) {

  /** Rendered text: `scala 2.12, 2.13, 3 · sjs1 · native0.5 · sbt-plugin` —
    * absent segments omitted entirely (empty summary renders as the empty
    * string, ready for a `—` fallback in rendering).
    */
  def render: String = {
    val segments =
      (if scalaVersions.nonEmpty then List(
         s"scala ${scalaVersions.mkString(", ")}"
       )
       else Nil) :::
        js :::
        native :::
        (if sbtPlugins then List("sbt-plugin") else Nil)
    segments.mkString(" · ")
  }
}

object PlatformSummary {

  given Eq[PlatformSummary] = Eq.fromUniversalEquals

  /** Collects distinct, sorted coverage from fully-suffixed artifactIds.
    * ArtifactSuffix.parse is total — garbage ids simply contribute nothing (or
    * a bare scala version when only that is recognizable).
    */
  def from(refs: List[ArtifactRef]): PlatformSummary = {
    val suffixes = refs.map(r => ArtifactSuffix.parse(r.artifactId))
    PlatformSummary(
      scalaVersions = suffixes.flatMap(_.scalaBinary).distinct.sorted,
      js = suffixes
        .flatMap(_.platform.collect { case Platform.Js(v) => s"sjs$v" })
        .distinct
        .sorted,
      native = suffixes
        .flatMap(_.platform.collect { case Platform.Native(v) => s"native$v" })
        .distinct
        .sorted,
      sbtPlugins = suffixes.exists(_.platform.exists {
        case Platform.SbtPlugin(_) => true
        case _                     => false
      })
    )
  }
}
