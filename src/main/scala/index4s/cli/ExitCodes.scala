package index4s.cli

import cats.effect.{ExitCode, IO}
import cats.kernel.Eq
import cats.Show
import cats.syntax.all.*
import index4s.client.ApiError

/** Process exit codes — product surface, documented in the `--help` footer:
  *   - `0` — success (found; plain `--help`/`--version` also exit 0)
  *   - `1` — not found, invalid input, or API failure
  *   - `2` — ambiguous bare name (candidates listed)
  */
object ExitCodes {
  val Success: Int = 0
  val NotFound: Int = 1
  val Ambiguous: Int = 2

  def codeOf(err: CliError): Int = err match {
    case CliError.AmbiguousName(_, _, _) => Ambiguous
    case _                               => NotFound
  }

  def message(err: CliError): String = err match {
    case CliError.Api(api)          => apiMessage(api)
    case CliError.NotResolved(name) =>
      s"could not resolve '$name' — no matching project. Try: index4s search $name"
    // Ambiguous → candidates table on STDOUT, exit 2; stderr carries only
    // this one-line notice (humans), the payload carries the candidates (agents).
    case CliError.AmbiguousName(name, candidates, _) =>
      s"ambiguous '$name' — ${candidates.size} candidates on stdout (exit 2)"
    case CliError.Invalid(msg) => s"invalid: $msg"
    // Coordinate-miss suggestion lines (one per suggestion) REPLACE the plain
    // not-found wording on stderr.
    case CliError.Missed(name, Nil, _) =>
      s"'$name' not in index. Try: index4s search $name"
    case CliError.Missed(_, suggestions, _) => suggestions.mkString("\n")
  }

  private def apiMessage(api: ApiError): String = api match {
    case ApiError.NotFound(what)      => s"not found: $what"
    case ApiError.Server(status)      => s"Scaladex unavailable (HTTP $status)"
    case ApiError.RateLimited(source) => s"rate limited by $source"
    case ApiError.Decode(msg, _)      => s"unexpected response: $msg"
    case ApiError.Network(msg)        => s"network error: $msg"
  }

  /** The stdout/stderr discipline in one place: payload → stdout + exit 0;
    * notes → stderr BEFORE the payload (diagnostics side-channel for resolution
    * pins and degradation notices); diagnostics → stderr + mapped exit code.
    * Handlers build `IO[Either[CliError, Out]]` values and never print
    * directly. Error payloads on STDOUT: a `Missed` carrying a --json payload
    * (exit 1) and an `AmbiguousName` carrying its rendered candidates
    * table/JSON (exit 2).
    */
  def run(cmd: IO[Either[CliError, Out]]): IO[ExitCode] =
    cmd.flatMap {
      case Right(out) =>
        out.notes.traverse_(IO.consoleForIO.errorln) *>
          IO.println(out.payload).as(ExitCode(Success))
      case Left(err) =>
        stdoutPayload(err) *>
          IO.consoleForIO.errorln(message(err)).as(ExitCode(codeOf(err)))
    }

  private def stdoutPayload(err: CliError): IO[Unit] = err match {
    case CliError.Missed(_, _, Some(json))           => IO.println(json)
    case CliError.AmbiguousName(_, _, Some(payload)) => IO.println(payload)
    case _                                           => IO.unit
  }
}

/** A command's successful output: `payload` → stdout, `notes` → stderr (printed
  * before the payload).
  */
final case class Out(payload: String, notes: List[String] = Nil)

/** One ambiguous-bare-name candidate, as resolved by autocomplete (relevance
  * order). `description` comes straight from the autocomplete hit.
  */
final case class Ambiguity(
    organization: String,
    repository: String,
    description: String
)

/** Every command-level failure, as data (exhaustively matched; never thrown).
  * `Missed` is the not-found channel: `name` feeds the stderr line,
  * `suggestions` (coordinate-miss) replace it verbatim, `json` is the
  * machine-readable error payload for --json mode (stdout, exit code 1).
  * `AmbiguousName` carries the ranked candidates as data; `payload` is the
  * rendered table (plain) or candidates JSON (--json) printed on stdout with
  * exit 2 — filled by the get handler where the --json flag is known.
  */
enum CliError {
  case Api(apiError: ApiError)
  case NotResolved(name: String)
  case AmbiguousName(
      name: String,
      candidates: List[Ambiguity],
      payload: Option[String] = None
  )
  case Invalid(msg: String)
  case Missed(
      name: String,
      suggestions: List[String] = Nil,
      json: Option[String] = None
  )
}

object CliError {
  given Eq[CliError] = Eq.fromUniversalEquals
  given Show[CliError] = Show.fromToString
}
