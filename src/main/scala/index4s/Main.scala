package index4s

import cats.effect.{ExitCode, IO, IOApp}
import index4s.cli.Cli

object Main extends IOApp {

  override def run(args: List[String]): IO[ExitCode] =
    Cli.run(args, version = BuildInfo.version)
}
