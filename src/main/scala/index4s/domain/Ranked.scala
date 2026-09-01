package index4s.domain

import cats.kernel.Eq
import cats.Show
import java.time.Instant

final case class Ranked(
    stars: Int,
    orgRepo: String,
    releaseDate: Option[Instant]
)

object Ranked {
  given Eq[Ranked] = Eq.fromUniversalEquals
  given Show[Ranked] = Show.fromToString

  /** stars desc → org/repo asc. */
  def sortByStars(rows: List[Ranked]): List[Ranked] =
    rows.sortBy(r => (-r.stars, r.orgRepo))

  /** freshest first (releaseDate desc); missing dates LAST, never crash; then
    * stars desc; then org/repo asc for determinism.
    */
  def sortByFresh(rows: List[Ranked]): List[Ranked] =
    rows.sortBy { r =>
      val dateKey = r.releaseDate.fold(0L)(d => -d.toEpochMilli)
      (r.releaseDate.isEmpty, dateKey, -r.stars, r.orgRepo)
    }
}
