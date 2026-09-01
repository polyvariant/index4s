package index4s.cli.get

import cats.kernel.Eq
import index4s.cli.ReadmeMode
import index4s.core.EnrichedProject
import index4s.domain.*
import upickle.default.*

/** The `get` output renderer: the markdown card, the --json view ([[GetJson]]),
  * and npm-view-style positional field paths over that json. All rendering is
  * PURE — GetCommand assembles a [[GetCommand.GetView]], this object turns it
  * into strings.
  */
object GetRender {

  /** Card-affecting flags, resolved once by the handler. */
  final case class RenderOpts(
      readmeMode: ReadmeMode,
      section: Option[String],
      artifacts: Boolean,
      noHints: Boolean,
      cellarDetected: Boolean
  )

  /** The artifact the card anchors on: the pinned/requested one (coordinate
    * identifier or --artifact-version) or the project's default ref. `name` is
    * suffix-stripped (sbt `%%` form, `[name]` placeholder).
    */
  final case class Selected(
      groupId: String,
      artifactId: String,
      version: String,
      name: String
  )

  def selected(view: GetCommand.GetView): Option[Selected] =
    view.pinned
      .map(p => Selected(p.groupId, p.artifactId, p.version, p.name))
      .orElse(view.enriched.defaultArtifact.map { ref =>
        Selected(
          ref.groupId,
          ref.artifactId,
          ref.version,
          GetCommand.baseName(ref.artifactId)
        )
      })

  def coordinate(sel: Selected): String =
    s"${sel.groupId}:${sel.artifactId}:${sel.version}"

  def card(view: GetCommand.GetView, opts: RenderOpts): String = {
    val e = view.enriched
    val p = e.project
    val lines = List.newBuilder[String]

    lines += s"${e.org}/${e.repo} | ${cell(p.flatMap(_.license))} | category: ${cell(p.flatMap(_.category))}" +
      s" | ★ ${fmt(p.flatMap(_.stars))} | forks ${fmt(p.flatMap(_.forks))} | issues ${fmt(p.flatMap(_.issues))}"

    val homepage = p.flatMap(_.homepage).filter(_.nonEmpty)
    lines += cell(p.flatMap(_.description)) + homepage.fold("")(" — " + _)

    lines += (e.project.map(_.topics) match {
      case Some(topics) if topics.nonEmpty =>
        if topics.size > 5
        then s"topics: ${topics.take(5).mkString(", ")}, … (+${topics.size - 5} more.)"
        else s"topics: ${topics.mkString(", ")}"
      case _ => "topics: —"
    })

    val latestVersion =
      e.artifactDetails.map(_.version).orElse(e.defaultArtifact.map(_.version))
    val releaseDate = e.releaseDate.map(_.toString.take(7))
    lines += s"latest: ${cell(latestVersion)} (${cell(releaseDate)}) — ${cell(e.platformSummary.map(_.render))}" +
      s"   [${cell(e.latestRefs.map(_.size).map(_.toString))} versions]"

    selected(view) match {
      case Some(sel) =>
        lines += s"default: ${coordinate(sel)}"
        // `%%`/`::` encode ONLY the scala suffix — a platform-suffixed
        // artifact (js/native/sbt) must stay fully-qualified in all three
        // snippets: single-colon with the complete artifactId.
        val platformQualified =
          ArtifactSuffix.parse(sel.artifactId).platform.exists {
            case Platform.Jvm => false
            case _            => true
          }
        if platformQualified then {
          lines += s"""  sbt       "${sel.groupId}" % "${sel.artifactId}" % "${sel.version}""""
          lines += s"""  mill      ivy"${sel.groupId}:${sel.artifactId}:${sel.version}""""
          lines += s"""  scala-cli dep"${sel.groupId}:${sel.artifactId}:${sel.version}""""
        } else {
          lines += s"""  sbt       "${sel.groupId}" %% "${sel.name}" % "${sel.version}""""
          lines += s"""  mill      ivy"${sel.groupId}::${sel.name}:${sel.version}""""
          lines += s"""  scala-cli dep"${sel.groupId}::${sel.name}::${sel.version}""""
        }
      case None =>
        lines += "default: —"
    }

    val ctx = selected(view).map(s =>
      DocLinks.Context(s.groupId, s.artifactId, s.version, s.name)
    )
    val evaluated = for {
      project <- e.project
      c <- ctx
    } yield DocLinks.docs(project, c)

    lines += (evaluated match {
      case Some(links) if links.nonEmpty =>
        s"docs: ${links.map(l => s"${l.label}: ${l.url}").mkString(", ")}"
      case _ => "docs: none"
    })

    lines += (for {
      project <- e.project
      c <- ctx
    } yield s"scaladoc: ${DocLinks.scaladoc(project, c)}")
      .getOrElse("scaladoc: —")

    lines += ReadmeView.render(opts.readmeMode, opts.section, view.readme)

    if !opts.noHints then selected(view).foreach { sel =>
      CellarHint.block(coordinate(sel), opts.cellarDetected).foreach(lines += _)
    }

    lines += s"full data: index4s get ${e.org}/${e.repo} --json"

    if opts.artifacts then e.latestRefs.foreach { refs =>
      lines += s"artifacts (${refs.size}):"
      refs.foreach(r => lines += s"  ${r.groupId}:${r.artifactId}:${r.version}")
    }

    lines.result().mkString("\n")
  }

  private def cell(v: Option[String]): String = v.getOrElse("—")

  /** Thousands-grouped decimal ("2542" → "2,542"); hand-rolled so rendering
    * never depends on native Formatter coverage.
    */
  private def fmt(n: Option[Int]): String =
    n.fold("—") { v =>
      v.toString.reverse.grouped(3).mkString(",").reverse
    }

  final case class LatestJson(
      version: Option[String],
      releaseDate: Option[String],
      platforms: Option[String],
      totalVersions: Option[Int]
  ) derives ReadWriter

  final case class DefaultJson(
      groupId: String,
      artifactId: String,
      version: String,
      name: String
  ) derives ReadWriter

  final case class DocLinkJson(label: String, url: String) derives ReadWriter

  final case class CellarJson(
      detected: Boolean,
      deps: Option[String],
      getExternal: Option[String]
  ) derives ReadWriter

  final case class ResolutionJson(
      identifier: String,
      resolvedTo: String,
      confident: Option[Boolean]
  ) derives ReadWriter

  /** One coordinate-miss correction. Also reused by the error-path --json
    * payload (CliError.Missed.json).
    */
  final case class SuggestionJson(
      groupId: String,
      artifactId: String,
      version: String,
      project: String
  ) derives ReadWriter

  /** The --json view — full fidelity, never truncated. The leading scalars
    * mirror the card so `get x stars` works; the trailing blocks carry the raw
    * fetched data. `suggestions` is only ever populated on the error path (a
    * miss exits 1) — the field exists so the success shape and the Missed error
    * payload share one suggestion schema. `readme` is the FULL markdown when
    * fetched (flag-dependent: "" with --readme off or when unavailable).
    */
  final case class GetJson(
      org: String,
      repo: String,
      stars: Option[Int],
      forks: Option[Int],
      issues: Option[Int],
      license: Option[String],
      category: Option[String],
      description: Option[String],
      homepage: Option[String],
      topics: List[String],
      latest: LatestJson,
      default: Option[DefaultJson],
      docLinks: List[DocLinkJson],
      scaladoc: Option[String],
      readme: String,
      project: Option[ProjectResponse],
      latestRefs: Option[List[ArtifactRef]],
      artifactDetails: Option[ArtifactResponse],
      pinned: Option[ArtifactResponse],
      cellar: CellarJson,
      resolution: ResolutionJson,
      suggestions: List[SuggestionJson]
  ) derives ReadWriter

  object GetJson {
    given Eq[GetJson] = Eq.fromUniversalEquals
  }

  def toJson(view: GetCommand.GetView, cellarDetected: Boolean): GetJson = {
    val e = view.enriched
    val sel = selected(view)
    val ctx =
      sel.map(s => DocLinks.Context(s.groupId, s.artifactId, s.version, s.name))
    val evaluated = for {
      project <- e.project
      c <- ctx
    } yield DocLinks.docs(project, c)
    GetJson(
      org = e.org,
      repo = e.repo,
      stars = e.project.flatMap(_.stars),
      forks = e.project.flatMap(_.forks),
      issues = e.project.flatMap(_.issues),
      license = e.project.flatMap(_.license),
      category = e.project.flatMap(_.category),
      description = e.project.flatMap(_.description),
      homepage = e.project.flatMap(_.homepage),
      topics = e.project.map(_.topics).getOrElse(Nil),
      latest = LatestJson(
        version = e.artifactDetails
          .map(_.version)
          .orElse(e.defaultArtifact.map(_.version)),
        releaseDate = e.releaseDate.map(_.toString.take(7)),
        platforms = e.platformSummary.map(_.render),
        totalVersions = e.latestRefs.map(_.size)
      ),
      default =
        sel.map(s => DefaultJson(s.groupId, s.artifactId, s.version, s.name)),
      docLinks = evaluated.getOrElse(Nil).map(l => DocLinkJson(l.label, l.url)),
      scaladoc = (for {
        project <- e.project
        c <- ctx
      } yield DocLinks.scaladoc(project, c)),
      readme =
        view.readme.collect { case Readme.Available(md) => md }.getOrElse(""),
      project = e.project,
      latestRefs = e.latestRefs,
      artifactDetails = e.artifactDetails,
      pinned = view.pinned,
      cellar = CellarJson(
        detected = cellarDetected,
        deps = sel.map(s => s"cellar deps ${coordinate(s)}"),
        getExternal =
          sel.map(s => s"cellar get-external ${coordinate(s)} <symbol>")
      ),
      resolution = ResolutionJson(
        view.resolution.identifier,
        view.resolution.resolvedTo,
        view.resolution.confident
      ),
      suggestions = Nil
    )
  }

  def writeJson(view: GetCommand.GetView, cellarDetected: Boolean): String =
    write(toJson(view, cellarDetected))

  /** Navigate a dotted path over the rendered --json view. Segments index
    * objects by key and arrays by integer. Scalars print bare (pipe friendly);
    * structures print as compact JSON. Unknown keys → Left with the available
    * keys listed.
    */
  def field(
      view: GetCommand.GetView,
      cellarDetected: Boolean,
      path: String
  ): Either[String, String] = {
    val root = ujson.read(writeJson(view, cellarDetected))
    path
      .split('.')
      .toList
      .foldLeft[Either[String, ujson.Value]](Right(root)) { (acc, seg) =>
        acc.flatMap {
          case obj: ujson.Obj =>
            obj.value
              .get(seg)
              .toRight(
                s"unknown field '$path' — available: ${obj.value.keys.mkString(", ")}"
              )
          case arr: ujson.Arr =>
            seg.toIntOption
              .flatMap(i =>
                Option.when(i >= 0 && i < arr.value.size)(arr.value(i))
              )
              .toRight(
                s"invalid index '$seg' into array (size ${arr.value.size}) for path '$path'"
              )
          case other =>
            Left(s"cannot descend into scalar at '$seg' for path '$path'")
        }
      }
      .map(renderValue)
  }

  private def renderValue(v: ujson.Value): String = v match {
    case ujson.Str(s)  => s
    case ujson.Bool(b) => b.toString
    case ujson.Null    => "null"
    case ujson.Num(n)  =>
      // ujson stores every number as Double; render integral values without
      // a trailing ".0" so `stars` prints 2542, not 2542.0.
      if n.isWhole && n.abs < 9.007199254740992e15 then n.toLong.toString
      else n.toString
    case _ => ujson.write(v)
  }

  final case class AmbiguityJson(
      organization: String,
      repository: String,
      description: String
  ) derives ReadWriter

  final case class AmbiguousPayloadJson(
      error: String,
      query: String,
      candidates: List[AmbiguityJson]
  ) derives ReadWriter

  /** The exit-2 stdout payload: a ranked markdown candidates table (plain mode)
    * or the machine-readable candidates JSON (--json).
    */
  def ambiguityPayload(
      name: String,
      candidates: List[index4s.cli.Ambiguity],
      json: Boolean
  ): String =
    if json then write(
      AmbiguousPayloadJson(
        error = "ambiguous",
        query = name,
        candidates = candidates.map(a =>
          AmbiguityJson(a.organization, a.repository, a.description)
        )
      )
    )
    else ambiguityTable(name, candidates)

  /** Ranked table in Scaladex autocomplete (relevance) order; `|` in
    * descriptions is escaped so the markdown stays well-formed.
    */
  def ambiguityTable(
      name: String,
      candidates: List[index4s.cli.Ambiguity]
  ): String = {
    val rows = candidates.zipWithIndex.map { case (c, i) =>
      s"| ${i + 1} | ${c.organization}/${c.repository} | ${c.description.replace("|", """\|""")} |"
    }
    (s"'$name' is ambiguous — ${candidates.size} candidates (pin with org/repo):" ::
      "" ::
      "| # | project | description |" ::
      "|---|---------|-------------|" ::
      rows).mkString("\n")
  }
}
