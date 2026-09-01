package index4s.domain

import java.time.{Duration, Instant}
import org.scalacheck.Gen
import Platform.*
import weaver.SimpleIOSuite
import weaver.scalacheck.Checkers

object DomainPropertiesSuite extends SimpleIOSuite with Checkers {

  private val now: Instant = Instant.parse("2026-09-01T00:00:00Z")

  test("tier ⇔ age consistency") {
    val genAge = Gen.chooseNum(-400L, 1500L)
    forall(genAge) { age =>
      val release = now.plusSeconds(-(age * 86400L + 3600L))
      val days = Duration.between(release, now).toDays
      val expected =
        if days < Freshness.ActiveMaxAgeDays then Some(Tier.Active)
        else if days < Freshness.SleepyMaxAgeDays then Some(Tier.Sleepy)
        else Some(Tier.Dead)
      expect.eql(Freshness.tier(Some(release), now), expected)
    }
  }

  private val genBase: Gen[String] =
    for {
      n <- Gen.chooseNum(1, 10)
      cs <- Gen.listOfN(
        n,
        Gen.frequency((8, Gen.alphaChar), (2, Gen.const('-')))
      )
    } yield cs.mkString

  private val genScalaToken =
    Gen.oneOf("3", "2.12", "2.13", "2.10", "2.11", "3.3.8", "2.13.16")

  private val genKnownPlatform: Gen[Option[Platform]] =
    Gen.oneOf(
      Gen.const(Some(Jvm: Platform)),
      Gen.oneOf("1", "0.6", "1.0.0-M3").map(v => Some(Js(v): Platform)),
      Gen.oneOf("0.5", "0.4", "0.5.6").map(v => Some(Native(v): Platform)),
      Gen.oneOf("1.0", "0.13", "2.0.12").map(v => Some(SbtPlugin(v): Platform))
    )

  test("suffix parse → render round-trip") {
    val genPair = for {
      platform <- genKnownPlatform
      sv <- genScalaToken
      b <- genBase
    } yield (b, Suffix(Some(sv), platform))
    forall(genPair) { case (base, suffix) =>
      val id = base + "_" + suffix.render
      expect.eql(ArtifactSuffix.parse(id), suffix)
    }
  }

  test("parse is total — arbitrary junk never throws") {
    val genJunk = Gen.stringOf(
      Gen.oneOf(Gen.alphaNumChar, Gen.oneOf('_', '.', '-', '!', ':', '@'))
    )
    forall(genJunk) { id => expect(ArtifactSuffix.parse(id) != null) }
  }

  private val genRow: Gen[Ranked] =
    for {
      repo <- Gen.nonEmptyListOf(Gen.alphaLowerChar).map(_.mkString.take(8))
      stars <- Gen.chooseNum(0, 5000)
      date <- Gen.option(
        Gen.chooseNum(0L, 2_000_000_000L).map(Instant.ofEpochSecond)
      )
    } yield Ranked(stars, repo, date)

  private def genRows: Gen[List[Ranked]] =
    Gen.sized { size =>
      for n <- Gen.chooseNum(0, size)
      rows <- Gen.listOfN(n, genRow)
      yield rows.distinctBy(_.orgRepo)
    }

  private def starsPredicate(sorted: List[Ranked]): Boolean =
    sorted.zip(sorted.drop(1)).forall { case (a, b) =>
      a.stars > b.stars || (a.stars == b.stars && a.orgRepo <= b.orgRepo)
    }

  private def freshPredicate(sorted: List[Ranked]): Boolean =
    sorted.zip(sorted.drop(1)).forall { case (a, b) =>
      (a.releaseDate, b.releaseDate) match {
        case (Some(x), Some(y)) =>
          x.toEpochMilli > y.toEpochMilli ||
          (x.toEpochMilli == y.toEpochMilli && (a.stars > b.stars ||
            (a.stars == b.stars && a.orgRepo <= b.orgRepo)))
        case (Some(_), None) => true
        case (None, None)    =>
          a.stars > b.stars || (a.stars == b.stars && a.orgRepo <= b.orgRepo)
        case (None, Some(_)) => false
      }
    }

  test("sorts are permutations satisfying their ordering predicate") {
    forall(genRows) { rows =>
      val byStars = Ranked.sortByStars(rows)
      val byFresh = Ranked.sortByFresh(rows)
      expect.all(
        byStars.map(_.orgRepo).sorted == rows.map(_.orgRepo).sorted,
        starsPredicate(byStars),
        byFresh.map(_.orgRepo).sorted == rows.map(_.orgRepo).sorted,
        freshPredicate(byFresh)
      )
    }
  }

  test("sorting is input-order independent") {
    forall(genRows) { rows =>
      expect.all(
        Ranked
          .sortByStars(rows.reverse)
          .map(_.orgRepo) == Ranked.sortByStars(rows).map(_.orgRepo),
        Ranked.sortByFresh(rows.reverse).map(_.orgRepo) == Ranked
          .sortByFresh(rows)
          .map(_.orgRepo)
      )
    }
  }

  private def genPlainRef: Gen[ArtifactRef] =
    for {
      b <- genBase
      v <- Gen.oneOf("1.0.0", "0.14.16", "2.1.0")
    } yield ArtifactRef("org.example", b + "_3", v)

  private def genAnyRef: Gen[ArtifactRef] =
    for {
      b <- genBase
      suffix <- Gen.oneOf(
        "_3",
        "_2.13",
        "_sjs1_3",
        "_sjs1_2.13",
        "_native0.5_3",
        "_native0.4_2.12",
        "_2.12_1.0",
        ""
      )
      v <- Gen.oneOf("1.0.0", "0.9.0-RC1", "3.0.0-SNAPSHOT")
    } yield ArtifactRef("org.example", b + suffix, v)

  test(
    "selection never picks a platform-suffixed ref while a plain _3 exists"
  ) {
    val genRefs = for {
      plainCount <- Gen.chooseNum(1, 3)
      plains <- Gen.listOfN(plainCount, genPlainRef)
      othersCount <- Gen.chooseNum(0, 10)
      others <- Gen.listOfN(othersCount, genAnyRef)
    } yield plains ++ others
    forall(genRefs) { refs =>
      expect(
        DefaultArtifact.select(refs, Some("3")).exists { selected =>
          val s = ArtifactSuffix.parse(selected.artifactId)
          s.platform.contains(Jvm) &&
          s.scalaBinary.contains("3") &&
          refs.exists(_.artifactId == selected.artifactId)
        }
      )
    }
  }
}
