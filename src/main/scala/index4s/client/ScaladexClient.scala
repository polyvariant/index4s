package index4s.client

import cats.effect.IO
import cats.effect.Resource
import cats.syntax.all.*
import index4s.BuildInfo
import index4s.domain.*
import org.http4s.ember.client.EmberClientBuilder
import sttp.client4.*
import sttp.client4.http4s.Http4sBackend
import sttp.model.{StatusCode, Uri}

import scala.concurrent.duration.*
import scala.util.Try
import upickle.default.*

/** Compile target + platform parameters for GET /api/search.
  *
  * Wire facts verified against the Scaladex server source (OldSearchApi +
  * Platform.stableVersions) and the live API:
  *   - `target` is UPPERCASE: JVM | JS | NATIVE | SBT.
  *   - JS requires `scalaVersion` + `scalaJsVersion`; the ONLY valid sjs values
  *     are "1" and "0.6" — `1.x` is REJECTED with 400 (the server's semantic
  *     parser accepts numbers only; ScalaJs.stableVersions = {0.6, 1}).
  *   - NATIVE requires `scalaVersion` + `scalaNativeVersion` ("0.5", "0.4",
  *     "0.3").
  *   - SBT requires `scalaVersion` AND `sbtVersion` ("1.0", "0.13", "2") —
  *     omitting scalaVersion yields 400.
  */
enum Target {
  case Jvm(scalaVersion: String = "3")
  case Js(scalaVersion: String = "3", scalaJsVersion: String = "1")
  case Native(scalaVersion: String = "3", nativeVersion: String = "0.5")
  case Sbt(scalaVersion: String = "3", sbtVersion: String = "1.0")
}

/** The ONLY HTTP layer of index4s: Scaladex (search/autocomplete/project/
  * versions/artifacts) plus the single non-Scaladex call (GitHub raw readme).
  *
  * All methods return `IO[Either[ApiError, A]]` and NEVER throw; `readme` is
  * the deliberate exception — its failure modes are already data
  * (`Readme.Unavailable`), so it returns `IO[Readme]`.
  *
  * Cross-cutting behavior:
  *   - `User-Agent: index4s/<version>` on every request (Scaladex 403s
  *     default-language UAs).
  *   - Retries ×2 with exponential backoff on 5xx and network errors — never on
  *     404/403/429/decode failures. Delays are injectable (`retryDelays`) so
  *     tests run in milliseconds.
  *   - A single decode helper: body-as-string → upickle read, keeping a
  *     200-char body snippet on failure; a blank body decodes to NotFound
  *     (Scaladex 404 bodies are empty).
  */
final case class ScaladexClient(
    backend: Backend[IO],
    baseUrl: Uri = uri"https://index.scala-lang.org",
    version: String = BuildInfo.version,
    githubToken: Option[String] = None,
    timeoutSeconds: Int = 15,
    retryDelays: List[FiniteDuration] = List(200.millis, 400.millis)
) {

  private val RequestTimeout = timeoutSeconds.seconds

  def search(
      q: String,
      target: Target,
      page: Int = 1,
      cli: Boolean = false
  ): IO[Either[ApiError, List[SearchResult]]] =
    getJson[List[SearchResult]](
      baseUrl
        .addPath("api", "search")
        .addParams(searchParams(q, target, page, cli)*),
      notFound = s"search '$q'"
    )

  def autocomplete(q: String): IO[Either[ApiError, List[AutocompleteResult]]] =
    getJson[List[AutocompleteResult]](
      baseUrl.addPath("api", "autocomplete").addParam("q", q),
      notFound = s"autocomplete '$q'"
    )

  def project(
      org: String,
      repo: String
  ): IO[Either[ApiError, ProjectResponse]] =
    getJson[ProjectResponse](projectsUri(org, repo), notFound = s"$org/$repo")

  def versionsLatest(
      org: String,
      repo: String
  ): IO[Either[ApiError, List[ArtifactRef]]] =
    getJson[List[ArtifactRef]](
      projectsUri(org, repo).addPath("versions", "latest"),
      notFound = s"$org/$repo latest versions"
    )

  def artifactLatest(
      groupId: String,
      artifactId: String
  ): IO[Either[ApiError, ArtifactResponse]] =
    getJson[ArtifactResponse](
      baseUrl.addPath("api", "v1", "artifacts", groupId, artifactId, "latest"),
      notFound = s"$groupId:$artifactId"
    )

  /** GET /api/v1/artifacts/{g}/{a}/{v} — a CONCRETE version lookup;
    * `latest`/absent versions go through [[artifactLatest]].
    */
  def artifact(
      groupId: String,
      artifactId: String,
      version: String
  ): IO[Either[ApiError, ArtifactResponse]] =
    getJson[ArtifactResponse](
      baseUrl.addPath("api", "v1", "artifacts", groupId, artifactId, version),
      notFound = s"$groupId:$artifactId:$version"
    )

  /** GitHub raw README. Every failure mode degrades to `Readme.Unavailable`:
    *   - 403/429 → "GitHub rate limit — set INDEX4S_GITHUB_TOKEN"
    *   - 404 → "no readme"
    *   - network / 5xx after retries → the error message
    */
  def readme(org: String, repo: String): IO[Readme] = {
    val request = baseGithubRequest(org, repo)
    sendWithRetry(request, classifyReadme).map {
      case Right(markdown)               => Readme.Available(markdown)
      case Left(ApiError.RateLimited(_)) =>
        Readme.Unavailable("GitHub rate limit — set INDEX4S_GITHUB_TOKEN")
      case Left(ApiError.NotFound(_))    => Readme.Unavailable("no readme")
      case Left(ApiError.Network(msg))   => Readme.Unavailable(msg)
      case Left(ApiError.Server(status)) =>
        Readme.Unavailable(s"GitHub unavailable (HTTP $status)")
      case Left(ApiError.Decode(msg, _)) => Readme.Unavailable(msg)
    }
  }

  private def projectsUri(org: String, repo: String): Uri =
    baseUrl.addPath("api", "v1", "projects", org, repo)

  private[client] def searchParams(
      q: String,
      target: Target,
      page: Int,
      cli: Boolean
  ): List[(String, String)] = {
    val common =
      ("q" -> q) :: ("page" -> page.toString) ::
        (if cli then List("cli" -> "true") else Nil)
    target match {
      case Target.Jvm(sv) =>
        ("target" -> "JVM") :: ("scalaVersion" -> sv) :: common
      case Target.Js(sv, sjs) =>
        ("target" -> "JS") :: ("scalaVersion" -> sv) :: ("scalaJsVersion" -> sjs) :: common
      case Target.Native(sv, nv) =>
        ("target" -> "NATIVE") :: ("scalaVersion" -> sv) :: ("scalaNativeVersion" -> nv) :: common
      case Target.Sbt(sv, sbtv) =>
        ("target" -> "SBT") :: ("scalaVersion" -> sv) :: ("sbtVersion" -> sbtv) :: common
    }
  }

  private def scaladexRequest(uri: Uri): Request[Either[String, String]] =
    basicRequest
      .get(uri)
      .header("User-Agent", s"index4s/$version")
      .readTimeout(RequestTimeout)

  private def baseGithubRequest(
      org: String,
      repo: String
  ): Request[Either[String, String]] = {
    val base = basicRequest
      .get(uri"https://api.github.com/repos/$org/$repo/readme")
      .header("User-Agent", s"index4s/$version")
      .header("Accept", "application/vnd.github.raw")
      .readTimeout(RequestTimeout)
    githubToken.fold(base)(token => base.auth.bearer(token))
  }

  private def getJson[A: ReadWriter](
      uri: Uri,
      notFound: String
  ): IO[Either[ApiError, A]] = {
    val request = scaladexRequest(uri)
    sendWithRetry(request, classifyScaladex(request, notFound))
      .map(_.flatMap(body => decode[A](body, notFound)))
  }

  /** Sends, classifies, and retries (5xx/network) while delays remain. */
  private def sendWithRetry(
      request: Request[Either[String, String]],
      classify: Response[Either[String, String]] => Either[ApiError, String],
      delays: List[FiniteDuration] = retryDelays
  ): IO[Either[ApiError, String]] =
    request
      .send(backend)
      .map(classify)
      .handleError(e => Left(ApiError.Network(errorMessage(e))))
      .flatMap {
        case Left(err) if delays.nonEmpty && retryable(err) =>
          IO.sleep(delays.head) *> sendWithRetry(request, classify, delays.tail)
        case result => IO.pure(result)
      }

  private def retryable(err: ApiError): Boolean = err match {
    case ApiError.Network(_)     => true
    case ApiError.Server(status) => status >= 500
    case _                       => false
  }

  private def classifyScaladex(
      request: Request[Either[String, String]],
      notFound: String
  )(response: Response[Either[String, String]]): Either[ApiError, String] =
    if response.code.isSuccess then response.body.left.map(body =>
      ApiError.Decode("unexpected error body on success", snippet(body))
    )
    else
      response.code.code match {
        case 404       => Left(ApiError.NotFound(notFound))
        case 403 | 429 =>
          Left(ApiError.RateLimited(request.uri.host.getOrElse("unknown")))
        case code => Left(ApiError.Server(code))
      }

  private def classifyReadme(
      response: Response[Either[String, String]]
  ): Either[ApiError, String] =
    if response.code.isSuccess then response.body.left.map(body =>
      ApiError.Network(body)
    )
    else
      response.code.code match {
        case 403 | 429 => Left(ApiError.RateLimited("GitHub"))
        case 404       => Left(ApiError.NotFound("readme"))
        case code      => Left(ApiError.Server(code))
      }

  private def decode[A: ReadWriter](
      body: String,
      notFound: String
  ): Either[ApiError, A] =
    Try(read[A](body)).toEither.left.map { e =>
      if body.trim.isEmpty then ApiError.NotFound(notFound)
      else ApiError.Decode(errorMessage(e), snippet(body))
    }

  private def snippet(body: String): String = body.take(200)

  private def errorMessage(e: Throwable): String =
    Option(e.getMessage).getOrElse(e.getClass.getSimpleName).take(200)
}

object ScaladexClient {

  /** Production wiring: ember-backed sttp http4s backend.
    *
    * The sttp http4s backend IGNORES per-request sttp timeouts, so the timeout
    * is configured on the EmberClientBuilder itself. NOTE: http4s 0.23.x has no
    * withConnectTimeout/withRequestTimeout (those are 1.x APIs) — the one
    * available knob is `withTimeout` (header-receive timeout on connections,
    * reset on each read/write), set to 15s.
    *
    * Linking: ember on Scala Native needs s2n-tls; link with
    * `S2N_LIBDIR=<prefix>/lib sbt nativeLink` where that dir holds libs2n.a
    * (static — the binary then has NO runtime s2n dependency).
    */
  def resource(
      baseUrl: Uri = uri"https://index.scala-lang.org",
      version: String = BuildInfo.version,
      githubToken: Option[String] = None,
      timeoutSeconds: Int = 15
  ): Resource[IO, ScaladexClient] = {
    val ember = EmberClientBuilder
      .default[IO]
      .withTimeout(timeoutSeconds.seconds)
    Http4sBackend
      .usingEmberClientBuilder[IO](ember)
      .map(backend =>
        ScaladexClient(backend, baseUrl, version, githubToken, timeoutSeconds)
      )
  }
}
