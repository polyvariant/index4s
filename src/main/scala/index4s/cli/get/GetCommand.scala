package index4s.cli.get

import cats.effect.IO
import cats.syntax.all.*
import index4s.cli.{
  CliConfig,
  CliError,
  GetConfig,
  Identifier,
  Out,
  ReadmeMode,
  Resolve
}
import index4s.client.{ApiError, ScaladexClient, Target}
import index4s.core.{EnrichedProject, Enrichment}
import index4s.domain.*

/** `get` command orchestration: Identifier → EnrichedProject → [[GetView]] →
  * Out payload. All printing stays in ExitCodes.run; this object only fetches,
  * degrades, and assembles.
  *
  * Flow per identifier form:
  *   - OrgRepo(o, r) → enrichOne(o, r, --scala) — project NotFound ⇒
  *     [[CliError.Missed]] (exit 1).
  *   - Coordinate(g,a,v) → artifact lookup (concrete v via the versioned
  *     endpoint, `latest`/absent via /latest) → its ProjectRef → enrichOne; the
  *     response becomes the card's `pinned` context (requested artifact +
  *     concrete version). NotFound ⇒ coordinate-miss suggestions.
  *   - Bare(name) → Resolve.resolveBare → OrgRepo flow; the non-confident-pin
  *     stderr note rides Out.notes.
  */
object GetCommand {

  final case class Resolution(
      identifier: String,
      resolvedTo: String,
      confident: Option[Boolean]
  )

  /** One coordinate-miss correction candidate. */
  final case class Suggestion(
      groupId: String,
      name: String,
      version: String,
      organization: String,
      repository: String
  )

  /** Everything the renderers need, post-fetch. `pinned` is the
    * coordinate/--artifact-version artifact context (Some ⇒ the card anchors on
    * it); `readme` is None iff --readme off; `notices` carry degradation / pin
    * diagnostics for stderr.
    */
  final case class GetView(
      enriched: EnrichedProject,
      readme: Option[Readme],
      pinned: Option[ArtifactResponse],
      resolution: Resolution,
      notices: List[String] = Nil
  )

  def run(
      cfg: CliConfig,
      get: GetConfig,
      client: ScaladexClient,
      pathEnv: Option[String]
  ): IO[Either[CliError, Out]] =
    Identifier.parse(get.identifier) match {
      case Left(msg)    => IO.pure(Left(CliError.Invalid(msg)))
      case Right(ident) =>
        val cellarDetected = CellarHint.detect(pathEnv)
        val asTyped = get.identifier.trim
        ident match {
          case Identifier.OrgRepo(org, repo) =>
            orgRepoFlow(
              cfg,
              client,
              get,
              cellarDetected,
              asTyped,
              org,
              repo,
              Nil,
              None
            )
          case Identifier.Bare(name) =>
            Resolve.resolveBare(name, client).flatMap {
              case Left(CliError.NotResolved(n)) =>
                IO.pure(Left(CliError.Missed(n)))
              // Ambiguous → candidates on STDOUT, exit 2. The payload is
              // rendered here (cfg.json decides table vs JSON) and printed by
              // ExitCodes alongside the one-line stderr notice.
              case Left(err @ CliError.AmbiguousName(_, _, None)) =>
                IO.pure(
                  Left(
                    err.copy(payload =
                      Some(
                        GetRender
                          .ambiguityPayload(err.name, err.candidates, cfg.json)
                      )
                    )
                  )
                )
              case Left(err)                              => IO.pure(Left(err))
              case Right(pinned: Resolve.Resolved.Pinned) =>
                orgRepoFlow(
                  cfg,
                  client,
                  get,
                  cellarDetected,
                  asTyped,
                  pinned.org,
                  pinned.repo,
                  Resolve.note(name, pinned).toList,
                  Some(pinned.confident)
                )
            }
          case Identifier.Coordinate(g, a, v) =>
            coordinateFlow(cfg, client, get, cellarDetected, asTyped, g, a, v)
        }
    }

  private def orgRepoFlow(
      cfg: CliConfig,
      client: ScaladexClient,
      get: GetConfig,
      cellarDetected: Boolean,
      asTyped: String,
      org: String,
      repo: String,
      preNotes: List[String],
      confident: Option[Boolean]
  ): IO[Either[CliError, Out]] =
    Enrichment.enrichOne(client, org, repo, get.scalaBinary).flatMap {
      case Left(ApiError.NotFound(_)) => IO.pure(Left(CliError.Missed(asTyped)))
      case Left(err)                  => IO.pure(Left(CliError.Api(err)))
      case Right(enriched0)           =>
        refineDefault(client, enriched0, get.scalaBinary, get.target).flatMap {
          enriched =>
            pinVersion(client, get.artifactVersion, enriched).flatMap {
              case Left(err)                 => IO.pure(Left(err))
              case Right((pinned, pinNotes)) =>
                finish(
                  cfg,
                  client,
                  get,
                  cellarDetected,
                  enriched,
                  pinned,
                  Resolution(asTyped, s"$org/$repo", confident),
                  preNotes ++ pinNotes
                )
            }
        }
    }

  private def coordinateFlow(
      cfg: CliConfig,
      client: ScaladexClient,
      get: GetConfig,
      cellarDetected: Boolean,
      asTyped: String,
      groupId: String,
      artifactId: String,
      version: Option[String]
  ): IO[Either[CliError, Out]] = {
    val lookup = version match {
      case None | Some("latest") => client.artifactLatest(groupId, artifactId)
      case Some(concrete) => client.artifact(groupId, artifactId, concrete)
    }
    lookup.flatMap {
      case Left(ApiError.NotFound(_)) =>
        coordinateMiss(cfg, client, groupId, artifactId)
      case Left(err)        => IO.pure(Left(CliError.Api(err)))
      case Right(requested) =>
        Enrichment
          .enrichOne(
            client,
            requested.project.organization,
            requested.project.repository,
            get.scalaBinary
          )
          .flatMap {
            case Left(ApiError.NotFound(_)) =>
              IO.pure(Left(CliError.Missed(asTyped)))
            case Left(err)        => IO.pure(Left(CliError.Api(err)))
            case Right(enriched0) =>
              refineDefault(client, enriched0, get.scalaBinary, get.target)
                .flatMap { enriched =>
                  rePin(client, get.artifactVersion, requested).flatMap {
                    case Left(err)                 => IO.pure(Left(err))
                    case Right((pinned, pinNotes)) =>
                      finish(
                        cfg,
                        client,
                        get,
                        cellarDetected,
                        enriched,
                        pinned,
                        Resolution(
                          asTyped,
                          s"${requested.project.organization}/${requested.project.repository}",
                          None
                        ),
                        pinNotes
                      )
                  }
                }
          }
    }
  }

  /** Coordinate-miss suggestions: search the suffix-stripped artifact name;
    * candidates are projects whose thin-search artifacts list carries that bare
    * name under a DIFFERENT organization; each is resolved to a concrete latest
    * version via versions/latest (skipped when unresolvable — a suggestion
    * without a concrete version would violate the determinism goal). Max 3. The
    * suggestion lines REPLACE the plain not-found stderr wording; the
    * machine-readable payload is attached only in --json mode (stderr plus
    * `--json suggestions[]`).
    */
  private def coordinateMiss(
      cfg: CliConfig,
      client: ScaladexClient,
      groupId: String,
      artifactId: String
  ): IO[Either[CliError, Out]] = {
    val bare = baseName(artifactId)
    client.search(bare, Target.Jvm()).flatMap {
      case Right(results) =>
        val candidates =
          results
            .filter(r =>
              r.organization != groupId && r.artifacts.contains(bare)
            )
            .take(3)
        candidates
          .traverse { candidate =>
            client
              .versionsLatest(candidate.organization, candidate.repository)
              .map {
                case Right(refs) =>
                  refs
                    .find(_.artifactId == artifactId)
                    .orElse(refs.find(r => baseName(r.artifactId) == bare))
                    .map(ref =>
                      Suggestion(
                        groupId = ref.groupId,
                        name = bare,
                        version = ref.version,
                        organization = candidate.organization,
                        repository = candidate.repository
                      )
                    )
                case Left(_) => None
              }
          }
          .map { suggestionOpts =>
            val suggestions = suggestionOpts.flatten
            if suggestions.isEmpty then Left(CliError.Missed(bare))
            else {
              val lines = suggestions.map(s =>
                s"$groupId:$artifactId not found — did you mean ${s.groupId}:${s.name}:${s.version}?" +
                  s" (project ${s.organization}/${s.repository})"
              )
              val payload = Option.when(cfg.json)(
                missedJson(groupId, artifactId, suggestions)
              )
              Left(CliError.Missed(bare, lines, payload))
            }
          }
      // The miss itself is the failure; a suggestion-search error must not
      // mask it (plain exit 1 with the bare name).
      case Left(_) => IO.pure(Left(CliError.Missed(bare)))
    }
  }

  private final case class MissedJson(
      error: String,
      suggestions: List[GetRender.SuggestionJson]
  ) derives upickle.default.ReadWriter

  private def missedJson(
      groupId: String,
      artifactId: String,
      suggestions: List[Suggestion]
  ): String =
    upickle.default.write(
      MissedJson(
        error = s"$groupId:$artifactId not found",
        suggestions = suggestions.map(s =>
          GetRender.SuggestionJson(
            s.groupId,
            s.name,
            s.version,
            s"${s.organization}/${s.repository}"
          )
        )
      )
    )

  /** Refines the enrichment pipeline's default-artifact choice. Deterministic
    * precedence, platform-aware via `--target` (default jvm):
    *   1. `project.defaultArtifact` name — pre-filter latest refs to that base
    *      name AND the target platform, then DefaultArtifact.select;
    *   2. the repo-named ref on the target platform — base name == repo,
    *      target-suffixed (`zio/zio-json --target jvm` → `zio-json_3`);
    *   3. DefaultArtifact.select over the target-platform refs; if the target
    *      has NO refs at all (tethys has no js/native), the unfiltered
    *      selection survives as-is (tethys-cats_3 under any target).
    * Scala-binary matching accepts raw OR normalized forms (`3` / `3.3.8`). A
    * CHANGED selection re-fetches artifacts/{g}/{a}/latest (the enrichment
    * details belong to the old ref); that fetch failing degrades (details
    * None).
    */
  def refineDefault(
      client: ScaladexClient,
      enriched: EnrichedProject,
      scalaBinary: String,
      target: Target = Target.Jvm()
  ): IO[EnrichedProject] =
    desiredDefault(enriched, scalaBinary, target) match {
      case Some(ref) if !enriched.defaultArtifact.contains(ref) =>
        client.artifactLatest(ref.groupId, ref.artifactId).map {
          case Right(details) =>
            enriched.copy(
              defaultArtifact = Some(ref),
              artifactDetails = Some(details)
            )
          case Left(err) =>
            enriched.copy(
              defaultArtifact = Some(ref),
              artifactDetails = None,
              failures = enriched.failures :+ err
            )
        }
      case _ => IO.pure(enriched)
    }

  private def desiredDefault(
      enriched: EnrichedProject,
      scalaBinary: String,
      target: Target
  ): Option[ArtifactRef] =
    enriched.latestRefs.flatMap { refs =>
      val onTarget = refs.filter { r =>
        val s = ArtifactSuffix.parse(r.artifactId)
        platformMatches(s, target) && scalaMatches(s, scalaBinary)
      }
      val declared =
        enriched.project.flatMap(_.defaultArtifact).flatMap { name =>
          DefaultArtifact.select(
            onTarget.filter(r => baseName(r.artifactId) == name),
            Some(scalaBinary)
          )
        }
      val repoNamed = DefaultArtifact.select(
        onTarget.filter(r => baseName(r.artifactId) == enriched.repo),
        Some(scalaBinary)
      )
      val platformSelect = DefaultArtifact.select(onTarget, Some(scalaBinary))
      declared
        .orElse(repoNamed)
        .orElse(platformSelect)
        .orElse(enriched.defaultArtifact)
    }

  private def platformMatches(s: Suffix, target: Target): Boolean =
    (s.platform, target) match {
      case (Some(Platform.Js(_)), Target.Js(_, _))         => true
      case (Some(Platform.Native(_)), Target.Native(_, _)) => true
      case (Some(Platform.SbtPlugin(_)), Target.Sbt(_, _)) => true
      case (Some(Platform.Jvm), Target.Jvm(_))             => true
      case _                                               => false
    }

  private def scalaMatches(s: Suffix, wanted: String): Boolean =
    s.scalaBinary.contains(wanted) || s.scalaBinaryVersion.contains(wanted)

  /** A concrete --artifact-version pins the card's version context via the
    * versioned artifact endpoint. NotFound on the pin is a hard error (the user
    * explicitly asked for that version); other failures degrade to the latest
    * with a note. On the coordinate flow the requested response is already
    * pinned — re-pin only when the flag names a DIFFERENT version.
    */
  private def pinVersion(
      client: ScaladexClient,
      flag: Option[String],
      enriched: EnrichedProject
  ): IO[Either[CliError, (Option[ArtifactResponse], List[String])]] =
    flag match {
      case None | Some("latest") => IO.pure(Right((None, Nil)))
      case Some(version)         =>
        enriched.defaultArtifact match {
          case None =>
            IO.pure(
              Right(
                (
                  None,
                  List(
                    "notice: --artifact-version ignored (no default artifact)"
                  )
                )
              )
            )
          case Some(ref) =>
            client.artifact(ref.groupId, ref.artifactId, version).map {
              case Right(resp)                => Right((Some(resp), Nil))
              case Left(ApiError.NotFound(_)) =>
                Left(
                  CliError.Api(
                    ApiError
                      .NotFound(s"${ref.groupId}:${ref.artifactId}:$version")
                  )
                )
              case Left(err) =>
                Right(
                  (
                    None,
                    List(
                      s"notice: --artifact-version $version unavailable (${describe(err)})"
                    )
                  )
                )
            }
        }
    }

  private def rePin(
      client: ScaladexClient,
      flag: Option[String],
      requested: ArtifactResponse
  ): IO[Either[CliError, (Option[ArtifactResponse], List[String])]] =
    flag match {
      case Some(version)
          if version != "latest" && version != requested.version =>
        client.artifact(requested.groupId, requested.artifactId, version).map {
          case Right(resp)                => Right((Some(resp), Nil))
          case Left(ApiError.NotFound(_)) =>
            Left(
              CliError.Api(
                ApiError.NotFound(
                  s"${requested.groupId}:${requested.artifactId}:$version"
                )
              )
            )
          case Left(err) =>
            Right(
              (
                Some(requested),
                List(
                  s"notice: --artifact-version $version unavailable (${describe(err)})"
                )
              )
            )
        }
      case _ => IO.pure(Right((Some(requested), Nil)))
    }

  private def finish(
      cfg: CliConfig,
      client: ScaladexClient,
      get: GetConfig,
      cellarDetected: Boolean,
      enriched: EnrichedProject,
      pinned: Option[ArtifactResponse],
      resolution: Resolution,
      preNotes: List[String]
  ): IO[Either[CliError, Out]] =
    // --web short-circuits: URL only, no readme fetch (Scala Native does not
    // spawn browsers).
    if get.web then IO.pure(
      Right(
        Out(s"https://index.scala-lang.org/${enriched.org}/${enriched.repo}")
      )
    )
    else {
      val fetchReadme =
        if get.readme == ReadmeMode.Off then IO.pure(None)
        else client.readme(enriched.org, enriched.repo).map(Some(_))
      fetchReadme.map { readme =>
        val view = GetView(
          enriched = enriched,
          readme = readme,
          pinned = pinned,
          resolution = resolution,
          notices =
            preNotes ++ enriched.failures.map(e => s"notice: ${describe(e)}")
        )
        val opts = GetRender.RenderOpts(
          readmeMode = get.readme,
          section = get.section,
          artifacts = get.artifacts,
          noHints = cfg.noHints,
          cellarDetected = cellarDetected
        )
        if get.fields.nonEmpty then get.fields.traverse(
          GetRender.field(view, cellarDetected, _)
        ) match {
          case Right(values) => Right(Out(values.mkString("\n"), view.notices))
          case Left(msg)     => Left(CliError.Invalid(msg))
        }
        else if cfg.json then Right(
          Out(GetRender.writeJson(view, cellarDetected), view.notices)
        )
        else Right(Out(GetRender.card(view, opts), view.notices))
      }
    }

  def describe(err: ApiError): String = err match {
    case ApiError.NotFound(what)      => s"$what not found"
    case ApiError.Server(status)      => s"HTTP $status"
    case ApiError.RateLimited(source) => s"rate limited by $source"
    case ApiError.Decode(msg, _)      => msg
    case ApiError.Network(msg)        => msg
  }

  /** The suffix-stripped base name of a fully-suffixed artifactId
    * (`circe-core_native0.5_3` → `circe-core`). Total: unrecognized shapes
    * return the id unchanged.
    */
  def baseName(artifactId: String): String = {
    val suffix = ArtifactSuffix.parse(artifactId).render
    if suffix.nonEmpty && artifactId.endsWith("_" + suffix) then artifactId
      .dropRight(suffix.length + 1)
    else artifactId
  }
}
