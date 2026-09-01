package index4s.core

import cats.effect.IO
import cats.kernel.Eq
import index4s.TestStubs.*
import index4s.client.ApiError
import index4s.domain.*
import sttp.client4.testing.BackendStub
import sttp.model.StatusCode
import upickle.default.*
import weaver.SimpleIOSuite

import java.time.Instant

/** Merge-semantics tests (stub backend only — zero network): full success, each
  * degradation level, the never-fail-after-project invariant, request
  * short-circuiting, enrichMany capping/ordering, and PlatformSummary purity.
  * Fixtures are the real captured Scaladex responses (frozen 2026-09-01).
  *
  * Route stubs are built as a NON-OVERLAPPING path→response map per test: sttp
  * BackendStub matchers are first-match-wins, so "override a routed path with a
  * 404" silently never fires — degraded paths are decided up front.
  */
object EnrichmentSuite extends SimpleIOSuite {

  // expect.eql needs Eq; Instant and ArtifactResponse have none in scope —
  // test-scoped, riding structural ==.
  private given Eq[Instant] = Eq.fromUniversalEquals
  private given Eq[ArtifactResponse] = Eq.fromUniversalEquals

  // --- stub plumbing -------------------------------------------------------

  private sealed trait Resp
  private object Resp {
    case class Body(text: String) extends Resp
    case object Empty404 extends Resp // Scaladex 404s have EMPTY bodies
  }

  // Hand-written ArtifactResponse for tethys-cats_3 (no captured fixture
  // exists; the versions/latest ref IS the captured fixture truth):
  // com.tethys-json:tethys-cats_3:0.29.8.
  private val tethysArtifactBody =
    """{"groupId":"com.tethys-lang","artifactId":"tethys-cats_3",""" +
      """"version":"0.29.8","name":"tethys-cats","binaryVersion":"_3",""" +
      """"language":"3","platform":"jvm","project":{"organization":"tethys-json",""" +
      """"repository":"tethys"},"releaseDate":"2026-01-15T10:00:00Z",""" +
      """"licenses":["Apache-2.0"]}"""

  private val allPaths: Map[String, Resp] = Map(
    "api/v1/projects/circe/circe" -> Resp.Body(
      Fixture.text("project-circe.json")
    ),
    "api/v1/projects/circe/circe/versions/latest" -> Resp.Body(
      Fixture.text("latest-circe.json")
    ),
    "api/v1/artifacts/io.circe/circe-core_3/latest" -> Resp.Body(
      Fixture.text("artifact-circe-core-latest.json")
    ),
    "api/v1/projects/tethys-json/tethys" -> Resp.Body(
      Fixture.text("project-tethys.json")
    ),
    "api/v1/projects/tethys-json/tethys/versions/latest" -> Resp.Body(
      Fixture.text("latest-tethys.json")
    ),
    "api/v1/artifacts/com.tethys-json/tethys-cats_3/latest" -> Resp.Body(
      tethysArtifactBody
    ),
    "api/v1/projects/zio/zio-json" -> Resp.Body(
      Fixture.text("project-zio-json.json")
    ),
    "api/v1/projects/zio/zio-json/versions/latest" -> Resp.Body(
      Fixture.text("latest-zio-json.json")
    ),
    "api/v1/projects/typelevel/weaver-test" -> Resp.Body(
      Fixture.text("project-weaver-test.json")
    )
  )

  private def routes(
      degraded: String*
  ): BackendStub[IO] => BackendStub[IO] = {
    val effective = allPaths ++ degraded.map(_ -> Resp.Empty404: (String, Resp))
    stub =>
      effective.foldLeft(stub) { (s, entry) =>
        val matcher = s.whenRequestMatches(_.uri.path.mkString("/") == entry._1)
        entry._2 match {
          case Resp.Body(text) => matcher.thenRespondAdjust(text)
          case Resp.Empty404 => matcher.thenRespondWithCode(StatusCode.NotFound)
        }
      }
  }

  private val circeCore3 = ArtifactRef("io.circe", "circe-core_3", "0.14.16")
  private val tethysCats3 =
    ArtifactRef("com.tethys-json", "tethys-cats_3", "0.29.8")

  test(
    "full success — circe: every field populated, zero failures, 3 requests"
  ) {
    val (client, requests) = clientWith(routes())
    Enrichment.enrichOne(client, "circe", "circe").flatMap { result =>
      requests.map { reqs =>
        result.fold(
          err => expect.eql("Right(enriched)", s"Left($err)"),
          enriched =>
            expect.all(
              enriched.project.exists(_.repository == "circe"),
              enriched.stars == Some(2542),
              enriched.failures == Nil
            ) &&
              expect.eql("circe/circe", s"${enriched.org}/${enriched.repo}") &&
              expect.eql(Some(108), enriched.latestRefs.map(_.size)) &&
              expect.eql(Some(circeCore3), enriched.defaultArtifact) &&
              expect.eql(
                Some(Instant.parse("2026-06-24T16:34:49Z")),
                enriched.releaseDate
              ) &&
              expect.eql(
                Some("scala 2.12, 2.13, 3 · sjs1 · native0.5"),
                enriched.platformSummary.map(_.render)
              ) &&
              expect.eql(
                List(
                  "/api/v1/projects/circe/circe",
                  "/api/v1/projects/circe/circe/versions/latest",
                  "/api/v1/artifacts/io.circe/circe-core_3/latest"
                ),
                paths(reqs)
              )
        )
      }
    }
  }

  test(
    "versions/latest 404 — refs/default/details None, exactly 1 failure, row alive"
  ) {
    val (client, requests) =
      clientWith(routes("api/v1/projects/circe/circe/versions/latest"))
    Enrichment.enrichOne(client, "circe", "circe").flatMap { result =>
      requests.map { reqs =>
        result.fold(
          err => expect.eql("Right(enriched)", s"Left($err)"),
          enriched =>
            expect.all(
              enriched.project.exists(_.repository == "circe"),
              enriched.stars == Some(2542),
              enriched.latestRefs.isEmpty,
              enriched.defaultArtifact.isEmpty,
              enriched.artifactDetails.isEmpty
            ) &&
              expect.eql(
                List(ApiError.NotFound("circe/circe latest versions")),
                enriched.failures
              ) &&
              expect.eql(
                List(
                  "/api/v1/projects/circe/circe",
                  "/api/v1/projects/circe/circe/versions/latest"
                ),
                paths(reqs)
              )
        )
      }
    }
  }

  test(
    "artifactLatest 404 — default kept from refs, details None, 1 failure, tier None"
  ) {
    val (client, requests) =
      clientWith(routes("api/v1/artifacts/io.circe/circe-core_3/latest"))
    val now = Instant.parse("2026-09-01T00:00:00Z")
    Enrichment.enrichOne(client, "circe", "circe").flatMap { result =>
      requests.map { reqs =>
        result.fold(
          err => expect.eql("Right(enriched)", s"Left($err)"),
          enriched =>
            expect.all(
              enriched.latestRefs.isDefined,
              enriched.artifactDetails.isEmpty,
              enriched.releaseDate.isEmpty,
              enriched.tier(now).isEmpty
            ) &&
              expect.eql(Some(circeCore3), enriched.defaultArtifact) &&
              expect.eql(
                List(ApiError.NotFound("io.circe:circe-core_3")),
                enriched.failures
              ) &&
              expect.eql(
                List(
                  "/api/v1/projects/circe/circe",
                  "/api/v1/projects/circe/circe/versions/latest",
                  "/api/v1/artifacts/io.circe/circe-core_3/latest"
                ),
                paths(reqs)
              )
        )
      }
    }
  }

  test(
    "project 404 — Left(NotFound) and NO further requests (exactly 1 issued)"
  ) {
    val (client, requests) = clientWith(routes("api/v1/projects/circe/circe"))
    Enrichment.enrichOne(client, "circe", "circe").flatMap { result =>
      requests.map { reqs =>
        expect.all(
          result.isLeft,
          reqs.size == 1
        ) && expect.eql(
          Left(ApiError.NotFound("circe/circe")): Either[
            ApiError,
            EnrichedProject
          ],
          result
        )
      }
    }
  }

  private val candidates = List(
    SearchResult("circe", "circe", artifacts = List("circe-core")),
    SearchResult("tethys-json", "tethys", artifacts = List("tethys")),
    SearchResult("zio", "zio-json", artifacts = List("zio-json")),
    SearchResult("typelevel", "weaver-test", artifacts = List("weaver-cats"))
  )

  private val mixedDegraded = List(
    "api/v1/projects/zio/zio-json/versions/latest",
    "api/v1/projects/typelevel/weaver-test"
  )

  test(
    "enrichMany — 4 candidates (2 full, 1 versions-404, 1 project-404): ordered rows, 9 requests"
  ) {
    val (client, requests) = clientWith(routes(mixedDegraded*))
    Enrichment.enrichMany(client, candidates, limit = 10).flatMap { rows =>
      requests.map { reqs =>
        val weaver = rows(3)
        val zioJson = rows(2)
        expect.all(
          rows.size == 4,
          weaver.project.isEmpty,
          weaver.stars.isEmpty,
          weaver.platformSummary.isEmpty,
          zioJson.project.exists(_.repository == "zio-json"),
          zioJson.stars == Some(431),
          zioJson.latestRefs.isEmpty
        ) &&
        expect.eql(
          List(
            "circe/circe",
            "tethys-json/tethys",
            "zio/zio-json",
            "typelevel/weaver-test"
          ),
          rows.map(r => s"${r.org}/${r.repo}")
        ) &&
        expect.eql(List("weaver-cats"), weaver.artifactNames) &&
        expect.eql(
          List(ApiError.NotFound("typelevel/weaver-test")),
          weaver.failures
        ) &&
        expect.eql(
          List(ApiError.NotFound("zio/zio-json latest versions")),
          zioJson.failures
        ) &&
        expect.eql(Some(tethysCats3), rows(1).defaultArtifact) &&
        expect.eql(Nil: List[ApiError], rows(1).failures) &&
        expect.eql(Some(circeCore3), rows(0).defaultArtifact) &&
        expect.eql(9, reqs.size) &&
        expect.eql(
          List(
            "/api/v1/projects/circe/circe",
            "/api/v1/projects/circe/circe/versions/latest",
            "/api/v1/artifacts/io.circe/circe-core_3/latest",
            "/api/v1/projects/tethys-json/tethys",
            "/api/v1/projects/tethys-json/tethys/versions/latest",
            "/api/v1/artifacts/com.tethys-json/tethys-cats_3/latest",
            "/api/v1/projects/typelevel/weaver-test",
            "/api/v1/projects/zio/zio-json",
            "/api/v1/projects/zio/zio-json/versions/latest"
          ).sorted,
          paths(reqs).sorted
        )
      }
    }
  }

  test(
    "enrichMany limit 2 over 4 candidates — 2 rows enriched, 6 requests (cap pre-fan-out)"
  ) {
    val (client, requests) = clientWith(routes(mixedDegraded*))
    Enrichment.enrichMany(client, candidates, limit = 2).flatMap { rows =>
      requests.map { reqs =>
        expect.all(
          reqs.size == 6
        ) &&
        expect.eql(
          List("circe/circe", "tethys-json/tethys"),
          rows.map(r => s"${r.org}/${r.repo}")
        )
      }
    }
  }

  test("PlatformSummary: empty refs — empty summary, empty render") {
    IO.pure {
      val summary = PlatformSummary.from(Nil)
      expect.eql(PlatformSummary(Nil, Nil, Nil, false), summary) &&
      expect.eql("", summary.render)
    }
  }

  test(
    "PlatformSummary: tethys (latest fixture) — JVM-only: no sjs/native segments"
  ) {
    IO.pure {
      val refs = read[List[ArtifactRef]](Fixture.text("latest-tethys.json"))
      val summary = PlatformSummary.from(refs)
      expect.all(
        summary.js.isEmpty,
        summary.native.isEmpty,
        !summary.sbtPlugins
      ) &&
      expect.eql(List("2.12", "2.13", "3"), summary.scalaVersions) &&
      expect.eql("scala 2.12, 2.13, 3", summary.render)
    }
  }

  test(
    "PlatformSummary: weaver fixture — sjs1 + native0.5 mix renders the expected format"
  ) {
    IO.pure {
      val refs = read[List[ArtifactRef]](Fixture.text("latest-weaver.json"))
      val summary = PlatformSummary.from(refs)
      expect.eql(List("sjs1"), summary.js) &&
      expect.eql(List("native0.5"), summary.native) &&
      expect.eql("scala 2.12, 2.13, 3 · sjs1 · native0.5", summary.render)
    }
  }

  test(
    "PlatformSummary: synthetic — sbt-plugin segment; full scala version normalizes"
  ) {
    IO.pure {
      val refs = List(
        ArtifactRef("x", "lib_3.3.8", "1.0.0"),
        ArtifactRef("x", "sbt-thing_2.12_1.0", "1.0.0")
      )
      val summary = PlatformSummary.from(refs)
      expect(summary.sbtPlugins) &&
      expect.eql(List("2.12", "3"), summary.scalaVersions) &&
      expect.eql("scala 2.12, 3 · sbt-plugin", summary.render)
    }
  }

  test(
    "tier propagation — zio-json releaseDate (2026-04 fixture) at fixed now → Some(Active)"
  ) {
    IO.pure {
      val details =
        read[ArtifactResponse](Fixture.text("artifact-zio-json-latest.json"))
      val enriched =
        EnrichedProject("zio", "zio-json", None, None, None, Some(details))
      expect.eql(
        Some(Instant.parse("2026-04-22T12:18:34Z")),
        enriched.releaseDate
      ) &&
      expect.eql(
        Some(Tier.Active),
        enriched.tier(Instant.parse("2026-09-01T00:00:00Z"))
      )
    }
  }
}
