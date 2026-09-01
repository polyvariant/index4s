package index4s.domain

object Paging {

  /** Pages of `pageSize` needed to fetch `total` items (Scaladex /api/search
    * pages by 20). Non-positive totals need no pages; non-positive page sizes
    * are clamped to 1 — this function is total.
    */
  def pagesNeeded(total: Int, pageSize: Int = 20): Int = {
    val ps = math.max(pageSize, 1)
    if total <= 0 then 0 else (total + ps - 1) / ps
  }

  /** For `--rank` fan-out display: never show more than `limit` rows, and never
    * more than were actually fetched. min(limit, fetched), floored at 0.
    */
  def cap(limit: Int, fetched: Int): Int =
    math.max(0, math.min(limit, fetched))
}
