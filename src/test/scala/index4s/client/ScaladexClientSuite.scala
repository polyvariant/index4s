package index4s.client

import cats.effect.IO
import cats.effect.Ref
import cats.syntax.all.*
import index4s.TestStubs.*
import index4s.domain.*
import java.net.ConnectException
import sttp.client4.testing.ResponseStub
import sttp.model.StatusCode
import weaver.SimpleIOSuite

import scala.concurrent.duration.*

object ScaladexClientSuite extends SimpleIOSuite {

  test("search 'json' JVM — exactly one GET /api/search with expected params") {
    val (client, requests) =
      clientWith(
        _.whenAnyRequest.thenRespondAdjust(Fixture.text("search-json.json"))
      )
    client.search("json", Target.Jvm()).flatMap { result =>
      requests.map { reqs =>
        expect.all(
          reqs.size == 1,
          reqs.head.method.method == "GET",
          reqs.head.uri.params.get("q") == Some("json"),
          reqs.head.uri.params.get("target") == Some("JVM"),
          reqs.head.uri.params.get("scalaVersion") == Some("3"),
          reqs.head.uri.params.get("page") == Some("1"),
          reqs.head.uri.params.get("scalaJsVersion").isEmpty,
          reqs.head.uri.params.get("cli").isEmpty,
          reqs.head.headers
            .exists(h => h.name == "User-Agent" && h.value == "index4s/test"),
          result.isRight
        ) && expect.eql("/api/search", pathString(reqs.head))
      }
    }
  }

  test(
    "search Native carries scalaNativeVersion; JS carries scalaJsVersion; SBT carries both versions"
  ) {
    val (nativeC, nativeR) =
      clientWith(_.whenAnyRequest.thenRespondAdjust("[]"))
    val (jsC, jsR) = clientWith(_.whenAnyRequest.thenRespondAdjust("[]"))
    val (sbtC, sbtR) = clientWith(_.whenAnyRequest.thenRespondAdjust("[]"))
    val (cliC, cliR) = clientWith(_.whenAnyRequest.thenRespondAdjust("[]"))
    for {
      _ <- nativeC.search("json", Target.Native(scalaVersion = "2.13"))
      _ <- jsC.search("json", Target.Js(scalaJsVersion = "0.6"))
      _ <- sbtC.search("json", Target.Sbt())
      _ <- cliC.search("json", Target.Jvm(), cli = true)
      n <- nativeR
      j <- jsR
      s <- sbtR
      c <- cliR
    } yield {
      val np = n.head.uri.params
      val jp = j.head.uri.params
      val sp = s.head.uri.params
      val cp = c.head.uri.params
      expect.all(
        np.get("target") == Some("NATIVE"),
        np.get("scalaVersion") == Some("2.13"),
        np.get("scalaNativeVersion") == Some("0.5"),
        jp.get("target") == Some("JS"),
        jp.get("scalaJsVersion") == Some("0.6"),
        jp.get("scalaVersion") == Some("3"),
        sp.get("target") == Some("SBT"),
        sp.get("sbtVersion") == Some("1.0"),
        sp.get("scalaVersion") == Some("3"),
        cp.get("cli") == Some("true")
      )
    }
  }

  test("each method hits its exact path") {
    val (projC, projR) =
      clientWith(
        _.whenAnyRequest.thenRespondAdjust(Fixture.text("project-circe.json"))
      )
    val (latestC, latestR) =
      clientWith(_.whenAnyRequest.thenRespondAdjust("[]"))
    val (artC, artR) =
      clientWith(
        _.whenAnyRequest.thenRespondAdjust(
          Fixture.text("artifact-circe-core-latest.json")
        )
      )
    val (acC, acR) =
      clientWith(
        _.whenAnyRequest.thenRespondAdjust(
          Fixture.text("autocomplete-circe.json")
        )
      )
    for {
      proj <- projC.project("circe", "circe")
      _ <- latestC.versionsLatest("circe", "circe")
      art <- artC.artifactLatest("io.circe", "circe-core_3")
      _ <- acC.autocomplete("circe")
      pr <- projR
      lr <- latestR
      ar <- artR
      acr <- acR
    } yield {
      val all = pr ++ lr ++ ar ++ acr
      expect.all(
        acr.head.uri.params.get("q") == Some("circe"),
        all.forall(
          _.headers.exists(h =>
            h.name == "User-Agent" && h.value == "index4s/test"
          )
        ),
        proj.exists(_.repository == "circe"),
        art.exists(_.artifactId == "circe-core_3")
      ) && expect.eql(
        List(
          "/api/v1/projects/circe/circe",
          "/api/v1/projects/circe/circe/versions/latest",
          "/api/v1/artifacts/io.circe/circe-core_3/latest",
          "/api/autocomplete"
        ),
        paths(pr) ++ paths(lr) ++ paths(ar) ++ paths(acr)
      )
    }
  }

  test("artifact(g, a, v) hits the versioned artifacts endpoint exactly once") {
    val (client, requests) =
      clientWith(
        _.whenAnyRequest.thenRespondAdjust(
          Fixture.text("artifact-circe-core-latest.json")
        )
      )
    client.artifact("io.circe", "circe-core_3", "0.14.9").flatMap { result =>
      requests.map { reqs =>
        expect.all(
          reqs.size == 1,
          reqs.head.method.method == "GET",
          result.exists(r =>
            r.groupId == "io.circe" && r.artifactId == "circe-core_3"
          )
        ) && expect.eql(
          "/api/v1/artifacts/io.circe/circe-core_3/0.14.9",
          pathString(reqs.head)
        )
      }
    }
  }

  test("503-then-200 on project — succeeds after retry, 2 attempts recorded") {
    val (client, requests) = clientWith(
      _.whenRequestMatches(pathString(_) == "/api/v1/projects/circe/circe")
        .thenRespondCyclic(
          ResponseStub.adjust("", StatusCode.ServiceUnavailable),
          ResponseStub.adjust(Fixture.text("project-circe.json"))
        )
    )
    client.project("circe", "circe").flatMap { result =>
      requests.map { reqs =>
        expect.all(
          result.exists(_.repository == "circe"),
          reqs.size == 2
        )
      }
    }
  }

  test("3×503 — Left(Server(503)) after exactly 3 attempts (1 + 2 retries)") {
    val (client, requests) =
      clientWith(
        _.whenAnyRequest.thenRespondWithCode(StatusCode.ServiceUnavailable)
      )
    client.project("circe", "circe").flatMap { result =>
      requests.map { reqs =>
        expect.all(
          result == Left(ApiError.Server(503)),
          reqs.size == 3
        )
      }
    }
  }

  test("network errors retried, then Left(Network) — 3 attempts total") {
    val (client, requests) =
      clientWith(
        _.whenAnyRequest.thenThrow(new ConnectException("connection refused"))
      )
    client.autocomplete("circe").flatMap { result =>
      requests.map { reqs =>
        expect.all(
          result.swap.exists {
            case ApiError.Network(_) => true
            case _                   => false
          },
          reqs.size == 3
        )
      }
    }
  }

  test("artifactLatest 404 (jawn case) — Left(NotFound), no retry, no throw") {
    val (client, requests) =
      clientWith(_.whenAnyRequest.thenRespondWithCode(StatusCode.NotFound))
    client.artifactLatest("org.typelevel", "jawn_3").flatMap { result =>
      requests.map { reqs =>
        expect.all(
          result == Left(ApiError.NotFound("org.typelevel:jawn_3")),
          reqs.size == 1
        )
      }
    }
  }

  test(
    "search returning [] — Right(Nil), exactly 1 request (no retries on 200)"
  ) {
    val (client, requests) =
      clientWith(_.whenAnyRequest.thenRespondAdjust("[]"))
    client.search("json", Target.Jvm()).flatMap { result =>
      requests.map { reqs =>
        expect.all(
          result == Right(Nil),
          reqs.size == 1
        )
      }
    }
  }

  test("parTraverseBounded(2) keeps in-flight ≤ 2, all 6 tasks complete") {
    for {
      inFlight <- Ref.of[IO, Int](0)
      maxSeen <- Ref.of[IO, Int](0)
      results <- Bounded.parTraverseBounded(2)((1 to 6).toList) { i =>
        inFlight
          .modify { n =>
            val m = n + 1; (m, m)
          }
          .flatMap(cur => maxSeen.update(_ max cur))
          *> IO.sleep(50.millis)
          *> inFlight
            .update(_ - 1)
            .as(i * 10)
      }
      max <- maxSeen.get
    } yield expect.all(
      max <= 2,
      results == List(10, 20, 30, 40, 50, 60)
    )
  }

  test("readme 403 without token — Unavailable(GitHub rate limit hint)") {
    val (client, _) = clientWith(
      _.whenAnyRequest.thenRespondWithCode(StatusCode.Forbidden),
      githubToken = None
    )
    client.readme("circe", "circe").map { readme =>
      expect(
        readme == Readme
          .Unavailable("GitHub rate limit — set INDEX4S_GITHUB_TOKEN")
      )
    }
  }

  test("readme 404 — Unavailable(no readme)") {
    val (client, _) =
      clientWith(_.whenAnyRequest.thenRespondWithCode(StatusCode.NotFound))
    client
      .readme("circe", "circe")
      .map(r => expect(r == Readme.Unavailable("no readme")))
  }

  test("readme 200 — Available(raw md); token and Accept headers sent") {
    val markdown = "# circe\nA JSON library"
    val (client, requests) = clientWith(
      _.whenAnyRequest.thenRespondAdjust(markdown),
      githubToken = Some("ghp-test-token")
    )
    client.readme("circe", "circe").flatMap { readme =>
      requests.map { reqs =>
        expect.all(
          readme == Readme.Available(markdown),
          reqs.size == 1,
          reqs.head.uri.toString == "https://api.github.com/repos/circe/circe/readme",
          reqs.head.headers.exists(h =>
            h.name == "Accept" && h.value == "application/vnd.github.raw"
          ),
          reqs.head.headers.exists(h =>
            h.name == "Authorization" && h.value == "Bearer ghp-test-token"
          ),
          reqs.head.headers
            .exists(h => h.name == "User-Agent" && h.value == "index4s/test")
        )
      }
    }
  }

  test("garbage JSON — Left(Decode) carrying the body snippet") {
    val garbage = """{"organization": "circe", "repository": """
    val (client, _) = clientWith(_.whenAnyRequest.thenRespondAdjust(garbage))
    client.project("circe", "circe").map { result =>
      expect(
        result.swap.exists {
          case ApiError.Decode(_, body) => body == garbage
          case _                        => false
        }
      )
    }
  }

  test("Scaladex 403 — Left(RateLimited) with host as source") {
    val (client, _) =
      clientWith(_.whenAnyRequest.thenRespondWithCode(StatusCode.Forbidden))
    client.search("json", Target.Jvm()).map { result =>
      expect(result == Left(ApiError.RateLimited("index.scala-lang.org")))
    }
  }
}
