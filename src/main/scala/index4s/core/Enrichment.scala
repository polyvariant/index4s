package index4s.core

import cats.effect.IO
import index4s.client.{ApiError, Bounded, ScaladexClient}
import index4s.domain.*

/** The enrichment pipeline behind `get` (single entity) and `search --rank`
  * (bounded fan-out). Merges project + versions/latest + artifacts/latest into
  * [[EnrichedProject]] with graceful degradation.
  *
  * Merge semantics:
  *   - The project lookup is the entity anchor: ANY ApiError there → Left
  *     (`get` maps that to exit 1; `enrichMany` converts it into a degraded
  *     `EnrichedProject.failed` row so a rank table NEVER drops a candidate).
  *   - After a successful project fetch the pipeline NEVER fails: each
  *     sub-lookup degrades to None with its error appended to `failures`.
  *   - Short-circuits (request-count tests assert this): no versions/latest
  *     call after a failed project; no artifacts/latest call without a selected
  *     default ref; no call at all past a failed versions/latest.
  */
object Enrichment {

  /** Enrich one org/repo. `scalaBinary` steers DefaultArtifact.select (the same
    * ref set yields e.g. circe-core_3 vs circe-core_2.13).
    */
  def enrichOne(
      client: ScaladexClient,
      org: String,
      repo: String,
      scalaBinary: String = "3"
  ): IO[Either[ApiError, EnrichedProject]] =
    client.project(org, repo).flatMap {
      case Left(err)      => IO.pure(Left(err))
      case Right(project) =>
        enrichRefs(client, org, repo, scalaBinary).map {
          (refs, default, details, failures) =>
            Right(
              EnrichedProject(
                org = org,
                repo = repo,
                project = Some(project),
                latestRefs = refs,
                defaultArtifact = default,
                artifactDetails = details,
                failures = failures
              )
            )
        }
    }

  /** `search --rank` fan-out over thin search results.
    *
    * A candidate whose PROJECT lookup failed is NOT dropped — it becomes
    * `EnrichedProject.failed(searchResult, err)`, a row with project-level
    * minimal data (org/repo/artifact names from the SearchResult) and the
    * failure recorded, so the rank table keeps the row with `—` cells.
    *
    *   - `limit` is applied via Paging.cap BEFORE fanning out: only capped rows
    *     exist to the network (limit 2 over 4 candidates ⇒ 6 requests).
    *   - `concurrency` bounds in-flight enrichments
    *     (Bounded.parTraverseBounded).
    *   - Results are in INPUT order regardless of completion order.
    */
  def enrichMany(
      client: ScaladexClient,
      candidates: List[SearchResult],
      limit: Int,
      concurrency: Int = 4
  ): IO[List[EnrichedProject]] = {
    val capped = candidates.take(Paging.cap(limit, candidates.length))
    Bounded.parTraverseBounded(concurrency)(capped) { candidate =>
      enrichOne(client, candidate.organization, candidate.repository)
        .map(_.fold(EnrichedProject.failed(candidate, _), identity))
    }
  }

  /** versions/latest → select default → artifacts/{default}/latest, never
    * failing; degradation levels accumulate as (None, None, None, failures).
    */
  private def enrichRefs(
      client: ScaladexClient,
      org: String,
      repo: String,
      scalaBinary: String
  ): IO[
    (
        Option[List[ArtifactRef]],
        Option[ArtifactRef],
        Option[ArtifactResponse],
        List[ApiError]
    )
  ] =
    client.versionsLatest(org, repo).flatMap {
      case Left(err) =>
        IO.pure((None, None, None, List(err)))
      case Right(refs) =>
        DefaultArtifact.select(refs, Some(scalaBinary)) match {
          case None =>
            IO.pure((Some(refs), None, None, Nil))
          case Some(selected) =>
            client.artifactLatest(selected.groupId, selected.artifactId).map {
              case Right(details) =>
                (Some(refs), Some(selected), Some(details), Nil)
              case Left(err) => (Some(refs), Some(selected), None, List(err))
            }
        }
    }
}
