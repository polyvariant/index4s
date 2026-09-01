package index4s.cli.search

import cats.effect.IO
import cats.syntax.all.*
import index4s.cli.{CliConfig, CliError, Out, SearchConfig}
import index4s.client.{ApiError, ScaladexClient, Target}
import index4s.domain.{Paging, SearchResult}

/** `search` command orchestration, thin mode: build the q-syntax query, page
  * /api/search sequentially (Scaladex serves 20 per page), keep Scaladex
  * relevance order EXACTLY as returned, render via [[ThinRender]].
  *
  * Thin BY DESIGN: exactly one API endpoint touched (no project fan-out, no
  * stars) — the `--rank` branch delegates to [[RankSearch]] for enrichment,
  * sorting and the decision table.
  */
object SearchCommand {

  /** Scaladex /api/search page size (server-fixed). */
  val PageSize: Int = 20

  /** `pathEnv` (raw PATH, sys.env in production) reaches only the --rank branch
    * — CellarHint.detect for the footer/install notice; the thin branch ignores
    * it entirely.
    */
  def run(
      cfg: CliConfig,
      search: SearchConfig,
      client: ScaladexClient,
      pathEnv: Option[String] = None
  ): IO[Either[CliError, Out]] =
    if search.rank then RankSearch.run(client, cfg, search, pathEnv = pathEnv)
    else {
      val q = buildQuery(search.query, search.topics)
      fetch(client, q, search.target, cfg.limit, search.cli).map {
        case Left(err)  => Left(CliError.Api(err))
        case Right(all) =>
          val shown = all.take(math.max(cfg.limit, 0))
          if shown.isEmpty then Left(noResults(q, search.target))
          else if cfg.json then Right(
            Out(ThinRender.jsonPayload(q, search.target, shown))
          )
          else Right(Out(ThinRender.thin(shown, search.target)))
      }
    }

  /** Folds `--topic` values into the q syntax: `json --topic json --topic http`
    * → `json AND topics:json AND topics:http`. The raw query passes through
    * verbatim (`* AND topics:json`, `organization:x`, AND — Scaladex Lucene
    * syntax is the user's). Topic values likewise go in verbatim — a topic
    * containing spaces MAY need Lucene quoting (`topics:"my topic"`), which we
    * deliberately do not guess: pass-through keeps the mapping from flag to q
    * lossless and predictable.
    */
  def buildQuery(query: String, topics: List[String]): String =
    topics.foldLeft(query)((acc, topic) => acc + s" AND topics:$topic")

  /** Pages 1..`Paging.pagesNeeded(limit)` SEQUENTIALLY (relevance order must
    * survive — no parallel page fetches), stopping early when a page comes back
    * short of [[PageSize]] (the server is exhausted; asking again would waste a
    * call). ApiError short-circuits immediately: page 2 failing must not
    * discard page 1's Right values silently.
    *
    * RankSearch seeds its candidate pool through this exact fetch (same paging,
    * same early stop) so thin and rank see identical relevance order.
    */
  def fetch(
      client: ScaladexClient,
      q: String,
      target: Target,
      limit: Int,
      cli: Boolean
  ): IO[Either[ApiError, List[SearchResult]]] = {
    def loop(
        page: Int,
        pagesLeft: Int,
        acc: List[SearchResult]
    ): IO[Either[ApiError, List[SearchResult]]] =
      if pagesLeft <= 0 then IO.pure(Right(acc))
      else
        client.search(q, target, page = page, cli = cli).flatMap {
          case Left(err)          => IO.pure(Left(err))
          case Right(pageResults) =>
            val all = acc ++ pageResults
            if pageResults.length < PageSize then IO.pure(Right(all))
            else loop(page + 1, pagesLeft - 1, all)
        }
    loop(1, Paging.pagesNeeded(limit, PageSize), Nil)
  }

  /** Empty result set is a failure (exit 1, stdout empty). The rendered line
    * rides CliError.Missed's suggestions channel — those lines replace the
    * default wording VERBATIM on stderr (ExitCodes.message), so the exact text
    * `no results for '<q>' (target <label>)` reaches stderr without touching
    * ExitCodes; `codeOf` already maps it to 1.
    */
  private def noResults(query: String, target: Target): CliError =
    CliError.Missed(
      query,
      List(
        s"no results for '$query' (target ${ThinRender.targetLabel(target)})"
      )
    )
}
