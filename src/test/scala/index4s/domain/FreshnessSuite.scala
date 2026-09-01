package index4s.domain

import java.time.Instant
import weaver.FunSuite

object FreshnessSuite extends FunSuite {

  val now: Instant = Instant.parse("2026-09-01T00:00:00Z")

  private def released(daysAgo: Long): Instant =
    now.minusSeconds(daysAgo * 86400L)

  test("freshness boundary matrix") {
    expect.all(
      Freshness.tier(Some(released(0)), now) == Some(Tier.Active),
      Freshness.tier(Some(released(100)), now) == Some(Tier.Active),
      Freshness.tier(Some(released(273)), now) == Some(Tier.Active),
      Freshness.tier(Some(released(274)), now) == Some(Tier.Sleepy),
      Freshness.tier(Some(released(547)), now) == Some(Tier.Sleepy),
      Freshness.tier(Some(released(548)), now) == Some(Tier.Dead),
      Freshness.tier(Some(released(4000)), now) == Some(Tier.Dead)
    )
  }

  test("missing releaseDate is None (renders —)") {
    expect(Freshness.tier(None, now) == None)
  }

  test("future release dates count as Active") {
    expect(
      Freshness.tier(Some(now.plusSeconds(86400 * 10)), now) == Some(
        Tier.Active
      )
    )
  }

  test("ageDays is floor of the elapsed duration") {
    expect(Freshness.ageDays(released(3).plusSeconds(3600), now) == 2L) &&
    expect(Freshness.ageDays(released(3), now) == 3L)
  }
}
