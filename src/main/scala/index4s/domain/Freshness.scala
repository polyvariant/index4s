package index4s.domain

import cats.kernel.Eq
import java.time.{Duration, Instant}

enum Tier {
  case Active
  case Sleepy
  case Dead

  /** Rendering glyph used by the --rank decision table (✓ / 💤 / 💀). */
  def symbol: String = this match {
    case Tier.Active => "✓"
    case Tier.Sleepy => "💤"
    case Tier.Dead   => "💀"
  }
}

object Tier {
  given Eq[Tier] = Eq.fromUniversalEquals
}

object Freshness {

  /** Deterministic definition — NOT calendar-accurate months. A month := 30.44
    * days (365.25 / 12), so: 9 months = 9 × 30.44 = 273.96 → 274 days 18 months =
    * 18 × 30.44 = 547.92 → 548 days Boundaries: age < 274d → Active; 274 ≤ age
    * < 548 → Sleepy; age ≥ 548 → Dead. Future release dates (negative age)
    * count as Active.
    */
  val ActiveMaxAgeDays: Long = 274L
  val SleepyMaxAgeDays: Long = 548L

  def ageDays(releaseDate: Instant, now: Instant): Long =
    Duration.between(releaseDate, now).toDays

  /** Missing release date → None; otherwise Some(tier). Total. */
  def tier(releaseDate: Option[Instant], now: Instant): Option[Tier] =
    releaseDate.map(d => tierOf(ageDays(d, now)))

  def tierOf(age: Long): Tier =
    if age < ActiveMaxAgeDays then Tier.Active
    else if age < SleepyMaxAgeDays then Tier.Sleepy
    else Tier.Dead
}
