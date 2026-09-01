package index4s.domain

import java.time.Instant
import weaver.FunSuite

object RankedSuite extends FunSuite {

  private def d(epochSec: Long) = Some(Instant.ofEpochSecond(epochSec))

  private val rows = List(
    Ranked(2542, "circe/circe", d(1_700_000_000L)),
    Ranked(2542, "typelevel/cats", d(1_600_000_000L)),
    Ranked(431, "zio/zio-json", None),
    Ranked(375, "playframework/play-json", d(1_760_000_000L)),
    Ranked(3000, "ghost/dead-project", None)
  )

  test("sortByStars — stars desc, org/repo asc tie-break") {
    expect.eql(
      Ranked.sortByStars(rows).map(r => (r.stars, r.orgRepo)),
      List(
        (3000, "ghost/dead-project"),
        (2542, "circe/circe"),
        (2542, "typelevel/cats"),
        (431, "zio/zio-json"),
        (375, "playframework/play-json")
      )
    )
  }

  test("sortByFresh — newest first, missing dates LAST") {
    expect.eql(
      Ranked.sortByFresh(rows).map(_.orgRepo),
      List(
        "playframework/play-json",
        "circe/circe",
        "typelevel/cats",
        "ghost/dead-project",
        "zio/zio-json"
      )
    )
  }

  test(
    "sortByFresh: equal dates fall through to stars desc, then org/repo asc"
  ) {
    val sameDate = List(
      Ranked(10, "b/b", d(1000)),
      Ranked(30, "c/c", d(1000)),
      Ranked(30, "a/a", d(1000)),
      Ranked(20, "d/d", None)
    )
    expect.eql(
      Ranked.sortByFresh(sameDate).map(_.orgRepo),
      List("a/a", "c/c", "b/b", "d/d")
    )
  }

  test("determinism — same input, same output regardless of input order") {
    expect.eql(Ranked.sortByStars(rows), Ranked.sortByStars(rows)) &&
    expect.eql(Ranked.sortByFresh(rows), Ranked.sortByFresh(rows)) &&
    expect.eql(Ranked.sortByFresh(rows.reverse), Ranked.sortByFresh(rows))
  }

  test("empty input") {
    expect.eql(Ranked.sortByStars(Nil), Nil) &&
    expect.eql(Ranked.sortByFresh(Nil), Nil)
  }
}
