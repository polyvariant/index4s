package index4s.cli

import cats.effect.{ExitCode, IO}
import cats.kernel.Eq
import cats.Show
import cats.syntax.all.*
import com.monovore.decline.{Argument, Command, Opts, Visibility}
import com.monovore.decline.effect.CommandIOApp
import index4s.cli.get.GetCommand
import index4s.cli.search.SearchCommand
import index4s.client.{ScaladexClient, Target}
import sttp.client4.UriContext
import sttp.model.Uri

/** `--sort` for `search --rank`. */
enum Sort {
  case Stars, Fresh
}

object Sort {
  given Argument[Sort] =
    Argument.fromMap("stars|fresh", Map("stars" -> Stars, "fresh" -> Fresh))

  given Eq[Sort] = Eq.fromUniversalEquals
  given Show[Sort] = Show.fromToString
}

enum ReadmeMode {
  case Head, Full, Off
}

object ReadmeMode {
  given Argument[ReadmeMode] =
    Argument.fromMap(
      "head|full|off",
      Map("head" -> Head, "full" -> Full, "off" -> Off)
    )

  given Eq[ReadmeMode] = Eq.fromUniversalEquals
  given Show[ReadmeMode] = Show.fromToString
}

/** The fully-resolved command surface of index4s, captured as data. */
enum CliCommand {
  case Search(search: SearchConfig)
  case Get(get: GetConfig)
  case ShowVersion
}

final case class SearchConfig(
    query: String,
    topics: List[String],
    target: Target,
    cli: Boolean = false,
    rank: Boolean = false,
    sort: Sort = Sort.Stars,
    ignoredVersionFlags: List[String] = Nil
)

final case class GetConfig(
    identifier: String,
    fields: List[String] = Nil,
    scalaBinary: String = "3",
    target: Target = Target.Jvm(),
    readme: ReadmeMode = ReadmeMode.Head,
    section: Option[String] = None,
    artifacts: Boolean = false,
    artifactVersion: Option[String] = None,
    web: Boolean = false
)

final case class CliConfig(
    command: CliCommand,
    json: Boolean = false,
    limit: Int = 20,
    noColor: Boolean = false,
    noHints: Boolean = false,
    timeoutSeconds: Int = 15,
    baseUrl: String = "https://index.scala-lang.org",
    verbose: Boolean = false
)

object CliConfig {
  given Eq[CliConfig] = Eq.fromUniversalEquals
  given Show[CliConfig] = Show.fromToString
}

/** ANSI color plumbing: renderers embed codes unconditionally and route output
  * through [[Colors.colorize]], which strips them when colors are disabled.
  */
object Colors {
  import scala.scalanative.posix.unistd.isatty

  private val Ansi = "\u001b\\[[0-9;]*m".r

  def colorize(enabled: Boolean)(s: String): String =
    if enabled then s else Ansi.replaceAllIn(s, "")

  /** stdout is a terminal? Scala Native does not implement `java.io.Console`,
    * so the JDK heuristic (`System.console() == null`) is unavailable —
    * `isatty(1)` is the native equivalent (posixlib is already on the
    * classpath; windowslib provides the Windows emulation).
    */
  def ttyStdout: Boolean = isatty(1) == 1

  /** NO_COLOR (any non-empty value), `--no-color`, and non-TTY (piped stdout)
    * each disable color.
    */
  def enabled(
      noColorFlag: Boolean,
      env: Map[String, String],
      tty: Boolean
  ): Boolean =
    !noColorFlag && !env.get("NO_COLOR").exists(_.nonEmpty) && tty
}

/** decline command tree: `search` + `get` subcommands, full global flag
  * surface, top-level `--version` short-circuit, exit-code plumbing via
  * [[ExitCodes.run]].
  */
object Cli {

  val Header: String =
    """Scaladex-backed Scala library discovery: search, get, resolve coordinates.
      |
      |stdout carries the payload (markdown or --json); stderr carries diagnostics.
      |Exit codes: 0 found · 1 not found or error · 2 ambiguous.""".stripMargin

  private val jsonOpt =
    Opts.flag("json", "Machine-readable JSON output (full fidelity).").orFalse
  private val limitOpt =
    Opts
      .option[Int](
        "limit",
        "Maximum results; search pages internally.",
        metavar = "N"
      )
      .withDefault(20)
  private val noColorOpt =
    Opts
      .flag(
        "no-color",
        "Disable ANSI colors (implicit when piped or NO_COLOR)."
      )
      .orFalse
  private val noHintsOpt =
    Opts.flag("no-hints", "Suppress cellar hint block.").orFalse
  private val timeoutOpt =
    Opts
      .option[Int]("timeout", "HTTP timeout in seconds.", metavar = "seconds")
      .withDefault(15)
  private val baseUrlOpt =
    Opts
      .option[String]("base-url", "Scaladex base URL.", metavar = "uri")
      .orElse(
        Opts.env[String](
          "INDEX4S_BASE_URL",
          "Scaladex base URL (env).",
          metavar = "uri"
        )
      )
      .withDefault("https://index.scala-lang.org")
  private val verboseOpt =
    Opts.flag("verbose", "Diagnostics on stderr.").orFalse

  private final case class Globals(
      json: Boolean,
      limit: Int,
      noColor: Boolean,
      noHints: Boolean,
      timeoutSeconds: Int,
      baseUrl: String,
      verbose: Boolean
  ) {
    def config(command: CliCommand): CliConfig =
      CliConfig(
        command,
        json,
        limit,
        noColor,
        noHints,
        timeoutSeconds,
        baseUrl,
        verbose
      )
  }

  private val globalOpts: Opts[Globals] =
    (
      jsonOpt,
      limitOpt,
      noColorOpt,
      noHintsOpt,
      timeoutOpt,
      baseUrlOpt,
      verboseOpt
    )
      .mapN(Globals.apply)

  private val queryArg = Opts.argument[String]("query")
  private val topicOpt =
    Opts
      .options[String](
        "topic",
        "Filter by topic (repeatable; ANDed).",
        metavar = "topic"
      )
      .orEmpty
  private val scalaOpt =
    Opts
      .option[String]("scala", "Scala binary version.", metavar = "version")
      .withDefault("3")
  private val sjsOpt =
    Opts
      .option[String](
        "sjs",
        "Scala.js version (with --target js).",
        metavar = "version"
      )
      .orNone
  private val nativeOpt =
    Opts
      .option[String](
        "native",
        "Scala Native version (with --target native).",
        metavar = "version"
      )
      .orNone
  private val sbtOpt =
    Opts
      .option[String](
        "sbt",
        "sbt binary version (with --target sbt).",
        metavar = "version"
      )
      .orNone
  private val cliOpt =
    Opts.flag("cli", "Only Scala CLI-friendly artifacts.").orFalse
  private val rankOpt =
    Opts.flag("rank", "Enriched ranking table (fan-out).").orFalse
  private val sortOpt =
    Opts.option[Sort]("sort", "Sort order for --rank.").withDefault(Sort.Stars)

  /** CLI-surface value of --target; mapped onto the wire-level client.Target
    * (which owns the actual default versions) by buildTarget below.
    */
  enum TargetArg {
    case Jvm, Js, Native, Sbt
  }

  object TargetArg {
    given Argument[TargetArg] =
      Argument.fromMap(
        "jvm|js|native|sbt",
        Map("jvm" -> Jvm, "js" -> Js, "native" -> Native, "sbt" -> Sbt)
      )
  }

  private final case class TargetSpec(
      kind: TargetArg,
      scalaVersion: String,
      sjsVersion: Option[String],
      nativeVersion: Option[String],
      sbtVersion: Option[String]
  )

  private val targetSpec: Opts[TargetSpec] =
    (
      Opts
        .option[TargetArg]("target", "Compile target.")
        .withDefault(TargetArg.Jvm),
      scalaOpt,
      sjsOpt,
      nativeOpt,
      sbtOpt
    ).mapN(TargetSpec.apply)

  /** Applies the per-target version defaults and collects warnings for explicit
    * platform versions whose target was not selected (surfaced on stderr in
    * verbose mode only — never fatal).
    */
  private def buildTarget(spec: TargetSpec): (Target, List[String]) = {
    def ignored(flag: String, value: Option[String]): List[String] = {
      val owner = Map("sjs" -> "js", "native" -> "native", "sbt" -> "sbt")
      value.toList.map(v =>
        s"warning: --$flag $v ignored (only used with --target ${owner(flag)})"
      )
    }
    spec.kind match {
      case TargetArg.Jvm =>
        (
          Target.Jvm(spec.scalaVersion),
          ignored("sjs", spec.sjsVersion) ++ ignored(
            "native",
            spec.nativeVersion
          ) ++
            ignored("sbt", spec.sbtVersion)
        )
      case TargetArg.Js =>
        (
          Target.Js(spec.scalaVersion, spec.sjsVersion.getOrElse("1")),
          ignored("native", spec.nativeVersion) ++ ignored(
            "sbt",
            spec.sbtVersion
          )
        )
      case TargetArg.Native =>
        (
          Target.Native(spec.scalaVersion, spec.nativeVersion.getOrElse("0.5")),
          ignored("sjs", spec.sjsVersion) ++ ignored("sbt", spec.sbtVersion)
        )
      case TargetArg.Sbt =>
        (
          Target.Sbt(spec.scalaVersion, spec.sbtVersion.getOrElse("1.0")),
          ignored("sjs", spec.sjsVersion) ++ ignored(
            "native",
            spec.nativeVersion
          )
        )
    }
  }

  private val searchOpts: Opts[CliConfig] =
    (globalOpts, queryArg, topicOpt, targetSpec, cliOpt, rankOpt, sortOpt)
      .mapN { (g, query, topics, spec, cli, rank, sort) =>
        val (target, ignored) = buildTarget(spec)
        g.config(
          CliCommand.Search(
            SearchConfig(
              query,
              topics,
              target,
              cli = cli,
              rank = rank,
              sort = sort,
              ignoredVersionFlags = ignored
            )
          )
        )
      }

  private val identifierArg = Opts.argument[String]("identifier")
  private val fieldsArg = Opts.arguments[String]("field").orEmpty
  private val readmeOpt = Opts
    .option[ReadmeMode]("readme", "README rendering.")
    .withDefault(ReadmeMode.Head)
  private val sectionOpt =
    Opts
      .option[String](
        "section",
        "Print a single README section.",
        metavar = "title"
      )
      .orNone
  private val artifactsOpt =
    Opts.flag("artifacts", "Include the full artifact ref list.").orFalse
  private val artifactVersionOpt =
    Opts
      .option[String](
        "artifact-version",
        "Pin an artifact version.",
        metavar = "version"
      )
      .orNone
  private val webOpt =
    Opts.flag("web", "Open the Scaladex page in a browser.").orFalse
  private val getTargetOpt =
    Opts
      .option[TargetArg](
        "target",
        "Compile target for default-artifact selection."
      )
      .withDefault(TargetArg.Jvm)

  private val getOpts: Opts[CliConfig] =
    (
      globalOpts,
      identifierArg,
      fieldsArg,
      scalaOpt,
      getTargetOpt,
      readmeOpt,
      sectionOpt,
      artifactsOpt,
      artifactVersionOpt,
      webOpt
    ).mapN {
      (
          g,
          identifier,
          fields,
          scalaBinary,
          targetArg,
          readme,
          section,
          artifacts,
          artifactVersion,
          web
      ) =>
        val (target, _) =
          buildTarget(TargetSpec(targetArg, scalaBinary, None, None, None))
        g.config(
          CliCommand.Get(
            GetConfig(
              identifier,
              fields,
              scalaBinary = scalaBinary,
              target = target,
              readme = readme,
              section = section,
              artifacts = artifacts,
              artifactVersion = artifactVersion,
              web = web
            )
          )
        )
    }

  /** Top-level --version short-circuit (Partial visibility: shown in help,
    * matched before subcommands, so `get`'s --artifact-version never collides
    * with it).
    */
  private val versionFlag: Opts[CliConfig] =
    Opts
      .flag(
        "version",
        "Print the version and exit.",
        visibility = Visibility.Partial
      )
      .as(CliConfig(CliCommand.ShowVersion))

  private val searchCmd =
    Opts.subcommand("search", "Search Scaladex for Scala libraries.")(
      searchOpts
    )
  private val getCmd =
    Opts.subcommand(
      "get",
      "Show a project card for <identifier> (org/repo, g:a[:v], or bare name)."
    )(getOpts)

  /** The pure parse tree — `Command[CliConfig]` so tests assert parsed configs
    * without any IO.
    */
  def command: Command[CliConfig] =
    Command("index4s", Header)(versionFlag orElse (searchCmd orElse getCmd))

  /** Production entrypoint: parse with sys.env (INDEX4S_BASE_URL), render Help
    * on stderr (exit 1 on parse errors, 0 on --help), dispatch otherwise.
    */
  def run(args: List[String], version: String): IO[ExitCode] =
    CommandIOApp.run[IO](command.map(dispatch(version)), args)

  private def dispatch(version: String)(cfg: CliConfig): IO[ExitCode] =
    cfg.command match {
      case CliCommand.ShowVersion =>
        IO.println(s"index4s $version").as(ExitCode(ExitCodes.Success))
      case CliCommand.Search(srch) => searchHandler(version, cfg, srch)
      case CliCommand.Get(get)     => getHandler(version, cfg, get)
    }

  /** The `search` handler: one client resource for the command's lifetime;
    * SearchCommand routes thin vs --rank (--rank needs PATH for the cellar
    * footer). Verbose-mode stderr warnings for ignored platform-version flags
    * stay here, ahead of any payload.
    */
  private def searchHandler(
      version: String,
      cfg: CliConfig,
      search: SearchConfig
  ): IO[ExitCode] = {
    val colors = Colors.enabled(cfg.noColor, sys.env, Colors.ttyStdout)
    IO.whenA(cfg.verbose && search.ignoredVersionFlags.nonEmpty)(
      IO.consoleForIO.errorln(
        Colors.colorize(colors)(search.ignoredVersionFlags.mkString("\n"))
      )
    ) *>
      {
        val base =
          Uri.parse(cfg.baseUrl).getOrElse(uri"https://index.scala-lang.org")
        ExitCodes.run(
          ScaladexClient
            .resource(
              baseUrl = base,
              version = version,
              timeoutSeconds = cfg.timeoutSeconds
            )
            .use(client =>
              SearchCommand.run(cfg, search, client, sys.env.get("PATH"))
            )
        )
      }
  }

  /** The `get` handler: one client resource for the command's lifetime,
    * GetCommand assembles the payload, ExitCodes enforces the output
    * discipline. `--base-url`, `--timeout`, and INDEX4S_GITHUB_TOKEN are
    * honored here.
    */
  private def getHandler(
      version: String,
      cfg: CliConfig,
      get: GetConfig
  ): IO[ExitCode] = {
    val base =
      Uri.parse(cfg.baseUrl).getOrElse(uri"https://index.scala-lang.org")
    ExitCodes.run(
      ScaladexClient
        .resource(
          baseUrl = base,
          version = version,
          githubToken = sys.env.get("INDEX4S_GITHUB_TOKEN"),
          timeoutSeconds = cfg.timeoutSeconds
        )
        .use(client => GetCommand.run(cfg, get, client, sys.env.get("PATH")))
    )
  }
}
