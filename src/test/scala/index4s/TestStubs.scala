package index4s

import cats.effect.IO
import index4s.client.ScaladexClient
import sttp.client4.*
import sttp.client4.impl.cats.CatsMonadError
import sttp.client4.testing.{BackendStub, RecordingBackend}

import scala.concurrent.duration.*

/** Shared stub-backend plumbing for client/enrichment suites: a BackendStub
  * wrapped in a RecordingBackend, plus exact request-path helpers for
  * request-set assertions ("no more, no fewer"). Zero network.
  */
object TestStubs {

  /** Retry backoff compressed to milliseconds — tests stay fast while attempt
    * counts (1 + 2 retries) remain observable in the recorded requests.
    */
  val tinyDelays: List[FiniteDuration] = List(2.millis, 2.millis)

  def clientWith(
      stubRules: BackendStub[IO] => BackendStub[IO],
      githubToken: Option[String] = None
  ): (ScaladexClient, IO[List[GenericRequest[_, _]]]) = {
    val stub = stubRules(BackendStub[IO](CatsMonadError[IO]()))
    val recording = RecordingBackend(stub)
    val client = ScaladexClient(
      backend = recording,
      version = "test",
      githubToken = githubToken,
      retryDelays = tinyDelays
    )
    (client, IO(recording.allInteractions.map(_._1)))
  }

  /** sttp `Uri.path` is a Seq[String] of decoded segments (no leading slash, no
    * custom toString) — render canonically for matching/assertions.
    */
  def pathString(req: GenericRequest[_, _]): String =
    "/" + req.uri.path.mkString("/")

  def paths(reqs: List[GenericRequest[_, _]]): List[String] =
    reqs.map(pathString)
}
