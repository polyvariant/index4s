package index4s.cli

import cats.effect.IO
import cats.syntax.all.*
import index4s.client.{ApiError, ScaladexClient}
import sttp.client4.*
import sttp.client4.impl.cats.CatsMonadError
import sttp.client4.testing.BackendStub
import sttp.model.StatusCode
import weaver.SimpleIOSuite

import scala.concurrent.duration.*

/** Identifier classification table + bare-name resolution against a BackendStub
  * (zero network), incl. the exit-code mapping of CliError.
  */
object ResolveSuite extends SimpleIOSuite {

  private def stub(
      autocomplete: String = "[]",
      search: String = "[]"
  ): ScaladexClient = {
    val backend =
      BackendStub[IO](CatsMonadError[IO]())
        .whenRequestMatches(_.uri.path.mkString("/") == "api/autocomplete")
        .thenRespondAdjust(autocomplete)
        .whenRequestMatches(_.uri.path.mkString("/") == "api/search")
        .thenRespondAdjust(search)
    ScaladexClient(
      backend = backend,
      version = "test",
      retryDelays = List(1.milli, 1.milli)
    )
  }

  private def autocompleteBody(repos: (String, String)*): String =
    repos
      .map { case (o, r) =>
        s"""{"organization":"$o","repository":"$r","description":"d"}"""
      }
      .mkString("[", ",", "]")

  private def searchBody(repos: (String, String)*): String =
    repos
      .map { case (o, r) =>
        s"""{"organization":"$o","repository":"$r"}"""
      }
      .mkString("[", ",", "]")

  test("classifier: org/repo (exactly one slash, non-empty segments)") {
    IO.pure(
      expect.eql(
        Right(Identifier.OrgRepo("typelevel", "weaver-test")),
        Identifier.parse("typelevel/weaver-test")
      )
    )
  }

  test("classifier: g:a and g:a:v pass through verbatim") {
    IO.pure(
      expect.all(
        Identifier.parse("io.circe:circe-core") ==
          Right(Identifier.Coordinate("io.circe", "circe-core", None)),
        Identifier.parse("io.circe:circe-core:0.14.10") ==
          Right(
            Identifier.Coordinate("io.circe", "circe-core", Some("0.14.10"))
          )
      )
    )
  }

  test("classifier: g::a normalizes to a suffixed Coordinate") {
    IO.pure(
      expect.eql(
        Right(Identifier.Coordinate("io.circe", "circe-core_3", None)),
        Identifier.parse("io.circe::circe-core")
      )
    )
  }

  test("classifier: bare name") {
    IO.pure(
      expect.eql(Right(Identifier.Bare("circe")), Identifier.parse("circe"))
    )
  }

  test("classifier: rejects malformed identifiers") {
    IO.pure(
      expect.all(
        Identifier.parse("a/b/c").isLeft,
        Identifier.parse("org/").isLeft,
        Identifier.parse("/repo").isLeft,
        Identifier.parse("  ").isLeft,
        Identifier.parse("io.circe:::broken").isLeft
      )
    )
  }

  test("resolveBare: exact repo hit pins confidently, case-insensitively") {
    val client = stub(
      autocomplete =
        autocompleteBody("io.circe" -> "circe-jawn", "typelevel" -> "Circe"),
      search = searchBody()
    )
    Resolve.resolveBare("circe", client).map { result =>
      expect.eql(
        Right(Resolve.Resolved.Pinned("typelevel", "Circe", confident = true)),
        result
      )
    }
  }

  test(
    "resolveBare: single non-exact hit pins non-confidently (with stderr note)"
  ) {
    val client =
      stub(autocomplete = autocompleteBody("typelevel" -> "weaver-test"))
    Resolve.resolveBare("weaver", client).map {
      case Right(pinned @ Resolve.Resolved.Pinned(_, _, _)) =>
        expect.eql(
          Some(
            "resolved: weaver → typelevel/weaver-test (pin with typelevel/weaver-test)"
          ),
          Resolve.note("weaver", pinned)
        )
      case other => failure(s"expected a pin, got $other")
    }
  }

  test("resolveBare: note is silent for confident pins") {
    val pinned: Resolve.Resolved.Pinned =
      Resolve.Resolved.Pinned("typelevel", "circe", confident = true)
    IO.pure(expect.eql(None, Resolve.note("circe", pinned)))
  }

  test(
    "resolveBare: multiple non-exact hits → AmbiguousName data (5 ranked candidates), exit 2"
  ) {
    val hits = (1 to 6).map(i => (s"org$i", s"repo$i"))
    val client = stub(autocomplete = autocompleteBody(hits*))
    Resolve.resolveBare("nope", client).map {
      case Left(err @ CliError.AmbiguousName("nope", candidates, None)) =>
        expect.all(
          candidates == (1 to 5)
            .map(i => Ambiguity(s"org$i", s"repo$i", "d"))
            .toList,
          ExitCodes.codeOf(err) == ExitCodes.Ambiguous,
          ExitCodes.message(
            err
          ) == "ambiguous 'nope' — 5 candidates on stdout (exit 2)",
          err.payload.isEmpty
        )
      case other => failure(s"expected AmbiguousName, got $other")
    }
  }

  test("resolveBare: zero autocomplete hits fall back to search top-1") {
    val client = stub(search = searchBody("zio" -> "zio-json"))
    Resolve.resolveBare("jsonlib", client).map { result =>
      expect.eql(
        Right(Resolve.Resolved.Pinned("zio", "zio-json", confident = false)),
        result
      )
    }
  }

  test("resolveBare: zero hits everywhere → NotResolved, exit 1") {
    val client = stub()
    Resolve.resolveBare("ghost", client).map {
      case Left(err @ CliError.NotResolved("ghost")) =>
        expect.eql(ExitCodes.NotFound, ExitCodes.codeOf(err))
      case other => failure(s"expected NotResolved, got $other")
    }
  }

  test("resolveBare: autocomplete API failure → CliError.Api, exit 1") {
    val backend =
      BackendStub[IO](CatsMonadError[IO]())
        .whenRequestMatches(_.uri.path.mkString("/") == "api/autocomplete")
        .thenRespondWithCode(StatusCode.NotFound)
    val client =
      ScaladexClient(backend = backend, retryDelays = List(1.milli, 1.milli))
    Resolve.resolveBare("x", client).map { result =>
      expect.eql(
        Left(CliError.Api(ApiError.NotFound("autocomplete 'x'"))): Either[
          CliError,
          Resolve.Resolved
        ],
        result
      )
    }
  }
}
