package index4s.cli

import com.monovore.decline.Command
import index4s.client.Target
import weaver.FunSuite

/** The full flag surface parses into the expected resolved CliConfig; invalid
  * enum-ish values fail with decline errors; --version short-circuits top-level
  * and never collides with get's --artifact-version.
  */
object CliParsingSuite extends FunSuite {

  private val cmd: Command[CliConfig] = Cli.command

  private def parseOrDie(args: String*): CliConfig =
    cmd.parse(args.toList) match {
      case Right(cfg) => cfg
      case Left(help) =>
        throw new RuntimeException(s"unexpected parse failure: $help")
    }

  private def searchCfg(cfg: SearchConfig): CliConfig =
    CliConfig(CliCommand.Search(cfg))

  private def getCfg(cfg: GetConfig): CliConfig =
    CliConfig(CliCommand.Get(cfg))

  test("search: full flag matrix") {
    val cfg = parseOrDie(
      "search",
      "json parsing",
      "--topic",
      "json",
      "--topic",
      "parser",
      "--target",
      "native",
      "--native",
      "0.4",
      "--scala",
      "2.13",
      "--json",
      "--limit",
      "50",
      "--no-color",
      "--no-hints",
      "--timeout",
      "30",
      "--base-url",
      "http://localhost:9090",
      "--verbose",
      "--cli",
      "--rank",
      "--sort",
      "fresh"
    )
    expect.eql(
      CliConfig(
        command = CliCommand.Search(
          SearchConfig(
            query = "json parsing",
            topics = List("json", "parser"),
            target = Target.Native("2.13", "0.4"),
            cli = true,
            rank = true,
            sort = Sort.Fresh
          )
        ),
        json = true,
        limit = 50,
        noColor = true,
        noHints = true,
        timeoutSeconds = 30,
        baseUrl = "http://localhost:9090",
        verbose = true
      ),
      cfg
    )
  }

  test(
    "search: defaults — jvm/3, no topics, stars sort, limit 20, timeout 15"
  ) {
    expect.eql(
      searchCfg(SearchConfig("json", Nil, Target.Jvm("3"))),
      parseOrDie("search", "json")
    )
  }

  test(
    "search: platform version flags apply per-target (js/sjs, sbt default 1.0)"
  ) {
    val js = parseOrDie("search", "x", "--target", "js", "--sjs", "0.6")
    val sbt = parseOrDie("search", "x", "--target", "sbt", "--scala", "2.12")
    expect.all(
      js.command == CliCommand.Search(
        SearchConfig("x", Nil, Target.Js("3", "0.6"))
      ),
      sbt.command == CliCommand.Search(
        SearchConfig("x", Nil, Target.Sbt("2.12", "1.0"))
      )
    )
  }

  test(
    "search: mismatched platform flag is ignored with a warning (verbose-only stderr)"
  ) {
    val cfg = parseOrDie("search", "x", "--sjs", "0.6")
    expect.eql(
      searchCfg(
        SearchConfig(
          "x",
          Nil,
          Target.Jvm("3"),
          ignoredVersionFlags =
            List("warning: --sjs 0.6 ignored (only used with --target js)")
        )
      ),
      cfg
    )
  }

  test("get: identifier + field paths + full get flags") {
    val cfg = parseOrDie(
      "get",
      "circe/circe",
      "stars",
      "topics",
      "--readme",
      "full",
      "--section",
      "Usage",
      "--artifacts",
      "--artifact-version",
      "0.14.10",
      "--web",
      "--json",
      "--verbose"
    )
    expect.eql(
      CliConfig(
        command = CliCommand.Get(
          GetConfig(
            identifier = "circe/circe",
            fields = List("stars", "topics"),
            readme = ReadmeMode.Full,
            section = Some("Usage"),
            artifacts = true,
            artifactVersion = Some("0.14.10"),
            web = true
          )
        ),
        json = true,
        verbose = true
      ),
      cfg
    )
  }

  test("get: bare identifier with no field paths") {
    expect.eql(
      getCfg(GetConfig("circe")),
      parseOrDie("get", "circe")
    )
  }

  test("get: --scala steers default-artifact selection") {
    // --scala also rides into target.scalaVersion (get's target defaults to jvm)
    expect.eql(
      getCfg(
        GetConfig(
          "circe/circe",
          scalaBinary = "2.13",
          target = index4s.client.Target.Jvm("2.13")
        )
      ),
      parseOrDie("get", "circe/circe", "--scala", "2.13")
    )
  }

  test(
    "get: --target selects the platform (default versions applied; default jvm)"
  ) {
    expect.all(
      parseOrDie("get", "circe/circe", "--target", "native").command ==
        CliCommand.Get(
          GetConfig(
            "circe/circe",
            target = index4s.client.Target.Native("3", "0.5")
          )
        ),
      parseOrDie(
        "get",
        "circe/circe",
        "--target",
        "js",
        "--scala",
        "2.13"
      ).command ==
        CliCommand.Get(
          GetConfig(
            "circe/circe",
            scalaBinary = "2.13",
            target = index4s.client.Target.Js("2.13", "1")
          )
        ),
      parseOrDie("get", "circe/circe", "--target", "sbt").command ==
        CliCommand.Get(
          GetConfig(
            "circe/circe",
            target = index4s.client.Target.Sbt("3", "1.0")
          )
        ),
      getCfg(GetConfig("circe/circe")).command ==
        parseOrDie("get", "circe/circe").command
    )
  }

  test("--version short-circuits top-level with default globals") {
    expect.eql(CliConfig(CliCommand.ShowVersion), parseOrDie("--version"))
  }

  test("--artifact-version does not collide with --version") {
    val withVersion =
      parseOrDie("get", "io.circe::circe-core", "--artifact-version", "0.14.10")
    val versionOnGet = cmd.parse(List("get", "circe", "--version"))
    expect.all(
      withVersion.command == CliCommand.Get(
        GetConfig("io.circe::circe-core", artifactVersion = Some("0.14.10"))
      ),
      versionOnGet.isLeft
    )
  }

  test(
    "invalid enum values fail with decline errors (--sort, --target, --readme, --limit)"
  ) {
    expect.all(
      cmd.parse(List("search", "x", "--sort", "bogus")).isLeft,
      cmd.parse(List("search", "x", "--target", "java")).isLeft,
      cmd.parse(List("get", "x", "--readme", "some")).isLeft,
      cmd.parse(List("search", "x", "--limit", "lots")).isLeft
    )
  }

  test("no args → decline help error (the trivial fallback)") {
    expect(cmd.parse(Nil).isLeft)
  }

  test("--base-url falls back to INDEX4S_BASE_URL env") {
    cmd.parse(
      List("search", "x"),
      Map("INDEX4S_BASE_URL" -> "http://env-host:1")
    ) match {
      case Right(cfg) => expect.eql("http://env-host:1", cfg.baseUrl)
      case Left(help) => failure(s"unexpected parse failure: $help")
    }
  }

  test("--help renders both subcommands and the exit-code footer") {
    cmd.parse(List("--help")) match {
      case Left(help) =>
        val text = help.toString
        expect.all(
          text.contains("search"),
          text.contains("get"),
          text.contains("Exit codes")
        )
      case Right(_) => failure("--help must produce Help, not a config")
    }
  }

  test(
    "Colors: NO_COLOR (non-empty), --no-color, and piped stdout each disable color"
  ) {
    expect.all(
      !Colors
        .enabled(noColorFlag = false, env = Map("NO_COLOR" -> "1"), tty = true),
      !Colors.enabled(noColorFlag = true, env = Map.empty, tty = true),
      !Colors.enabled(noColorFlag = false, env = Map.empty, tty = false),
      Colors
        .enabled(noColorFlag = false, env = Map("NO_COLOR" -> ""), tty = true),
      Colors.enabled(noColorFlag = false, env = Map.empty, tty = true)
    )
  }

  test(
    "Colors: colorize passes ANSI through when enabled, strips when disabled"
  ) {
    val bold = "\u001b[1mbold\u001b[0m"
    expect.all(
      Colors.colorize(enabled = true)(bold) == bold,
      Colors.colorize(enabled = false)(bold) == "bold"
    )
  }
}
