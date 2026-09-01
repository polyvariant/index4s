package index4s.client

import cats.kernel.Eq

/** Every failure mode of the HTTP layer, as data: commands degrade gracefully
  * on these instead of crashing. No client method ever throws; all failures
  * surface through `Either[ApiError, A]` (except `readme`, whose failures are
  * already data via `Readme.Unavailable`).
  */
enum ApiError {

  /** 404, or a 2xx whose body is empty/blank (Scaladex 404 bodies are 0 bytes).
    */
  case NotFound(what: String)

  /** Server-side failure. `status >= 500` variants are retried (×2, exponential
    * backoff) before this value is returned; any other unexpected non-2xx (e.g.
    * a 400 from malformed params) is also reported here, unretried.
    */
  case Server(status: Int)

  /** 403/429 — GitHub or Scaladex refusing, usually quota/UA related. */
  case RateLimited(source: String)

  /** upickle decode failure; `body` keeps a diagnostic snippet (first 200
    * chars).
    */
  case Decode(msg: String, body: String)

  /** Connection, DNS or timeout failures (transport-level exceptions). */
  case Network(msg: String)
}

object ApiError {
  given Eq[ApiError] = Eq.fromUniversalEquals
}
