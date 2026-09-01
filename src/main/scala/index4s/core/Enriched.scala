package index4s.core

import cats.kernel.Eq
import index4s.client.ApiError
import index4s.domain.*

import java.time.Instant

/** The merged view of one Scaladex project — graceful degradation as data. The
  * single data source for the `get` card and the `--rank` table; neither
  * re-fetches anything.
  *
  * Field semantics (degradation levels, outermost first):
  *   - `project = None` ⇔ the project lookup itself failed. `enrichOne` returns
  *     Left in that case, so None only occurs on rows built by
  *     [[EnrichedProject.failed]] — the "row never dropped" guarantee for
  *     --rank. On every enrichOne Right, project is Some.
  *   - `latestRefs = None` ⇔ versions/latest failed (versions/latest errors
  *     degrade; the row survives with `—` version/platform cells).
  *   - `defaultArtifact = None` ⇔ latestRefs is None, or nothing in it was
  *     selectable ([[index4s.domain.DefaultArtifact.select]] over empty or
  *     unsuffixed refs).
  *   - `artifactDetails = None` ⇔ no default ref was selected, or
  *     artifacts/{g}/{a}/latest failed for it (the jawn-style empty-404 case) —
  *     releaseDate/licenses degrade, tier renders `—`.
  *   - `failures` records EVERY degraded sub-lookup in degradation order;
  *     rendering turns each into a stderr notice + `—` cells, never a crash.
  *
  * `artifactNames` carries the thin-search artifact *names* — populated only on
  * failed rows (from the SearchResult) so degraded rank rows still show which
  * artifacts the project publishes.
  */
final case class EnrichedProject(
    org: String,
    repo: String,
    project: Option[ProjectResponse],
    latestRefs: Option[List[ArtifactRef]],
    defaultArtifact: Option[ArtifactRef],
    artifactDetails: Option[ArtifactResponse],
    failures: List[ApiError] = Nil,
    artifactNames: List[String] = Nil
) {

  def stars: Option[Int] = project.flatMap(_.stars)

  def releaseDate: Option[Instant] = artifactDetails.map(_.releaseDate)

  /** Freshness tier of the default artifact at `now`; None when degraded. */
  def tier(now: Instant): Option[Tier] = Freshness.tier(releaseDate, now)

  /** Platform coverage of the latest refs; None when versions/latest failed. */
  def platformSummary: Option[PlatformSummary] =
    latestRefs.map(PlatformSummary.from)
}

object EnrichedProject {

  given Eq[EnrichedProject] = Eq.fromUniversalEquals

  /** Degraded row for a candidate whose PROJECT lookup failed: org/repo and
    * thin-search artifact names survive; every enriched cell is None; the
    * failure is recorded. `search --rank` keeps the row (`—` cells); `get`
    * instead surfaces the error (enrichOne Left → exit 1).
    */
  def failed(candidate: SearchResult, err: ApiError): EnrichedProject =
    EnrichedProject(
      org = candidate.organization,
      repo = candidate.repository,
      project = None,
      latestRefs = None,
      defaultArtifact = None,
      artifactDetails = None,
      failures = List(err),
      artifactNames = candidate.artifacts
    )
}
