package index4s.cli.search

import cats.effect.{Clock, IO}
import cats.syntax.all.*
import index4s.cli.{CliConfig, CliError, Out, SearchConfig, Sort}
import index4s.cli.get.CellarHint
import index4s.client.{ApiError, ScaladexClient}
import index4s.core.{EnrichedProject, Enrichment}
import index4s.domain.Ranked
import java.time.Instant

/** `search --rank` orchestration: thin seed (same query building and paged
  * fetch as thin search — same relevance order, same early-stop paging) →
  * bounded enrichment fan-out → client-side sort ([[Ranked]]) → decision table
  * / JSON via [[RankRender]].
  *
  * Determinism contract:
  *   - candidate pool = the FIRST `--limit` thin-search results (pages of 20,
  *     Scaladex relevance order), then `enrichMany` caps again pre-fan-out;
  *   - a candidate whose PROJECT lookup failed is never dropped — it renders
  *     with `—` cells and rides stderr as a degradation notice;
  *   - missing stars sort as 0 (documented compromise: Ranked is Int-starred;
  *     the failure is recorded, so the row explains itself);
  *   - an EMPTY seed renders an empty table and exits 0 — unlike thin search,
  *     which maps empty to exit 1;
  *   - `now` comes from the injected [[Clock]] — real in production, fixed in
  *     tests, so tier glyphs and ages are reproducible.
  */
object RankSearch {

  /** Enrichment fan-out width. */
  val Concurrency: Int = 4

  def run(
      client: ScaladexClient,
      cfg: CliConfig,
      search: SearchConfig,
      clock: Clock[IO] = Clock[IO],
      pathEnv: Option[String] = None
  ): IO[Either[CliError, Out]] = {
    val q = SearchCommand.buildQuery(search.query, search.topics)
    SearchCommand
      .fetch(client, q, search.target, cfg.limit, search.cli)
      .flatMap {
        case Left(err)   => IO.pure(Left(CliError.Api(err)))
        case Right(seed) =>
          val candidates = seed.take(math.max(cfg.limit, 0))
          clock.realTime
            .map(d => Instant.ofEpochMilli(d.toMillis))
            .flatMap { now =>
              Enrichment
                .enrichMany(client, candidates, cfg.limit, Concurrency)
                .map { rows =>
                  val ordered = orderRows(rows, search.sort)
                  val notes = RankRender.degradationNotes(ordered) ++
                    RankRender.installNote(
                      CellarHint.detect(pathEnv),
                      cfg.noHints
                    )
                  val payload =
                    if cfg.json then RankRender.jsonPayload(
                      q,
                      search.target,
                      search.sort,
                      ordered,
                      now
                    )
                    else
                      RankRender.table(
                        ordered,
                        search.sort,
                        search.target,
                        now,
                        cfg.noHints
                      )
                  Right(Out(payload, notes))
                }
            }
      }
  }

  /** Sorts enriched rows by projecting each row to a [[Ranked]] key, sorting
    * the keys with Ranked's comparators, and re-associating keys with their
    * rows through per-org/repo pools (encounter order preserved, so even
    * hypothetical duplicate org/repo rows reorder deterministically) — no
    * comparator is re-implemented here.
    */
  private def orderRows(
      rows: List[EnrichedProject],
      sort: Sort
  ): List[EnrichedProject] = {
    val ranked = rows.map(r =>
      Ranked(r.stars.getOrElse(0), s"${r.org}/${r.repo}", r.releaseDate)
    )
    val sorted =
      if sort == Sort.Fresh then Ranked.sortByFresh(ranked)
      else Ranked.sortByStars(ranked)
    val pools0 = rows.groupBy(r => s"${r.org}/${r.repo}")
    sorted
      .foldLeft((pools0, List.newBuilder[EnrichedProject])) {
        case ((pools, out), rk) =>
          pools.get(rk.orgRepo) match {
            case Some(head :: tail) =>
              (pools.updated(rk.orgRepo, tail), out += head)
            case _ => (pools, out)
          }
      }
      ._2
      .result()
  }
}
