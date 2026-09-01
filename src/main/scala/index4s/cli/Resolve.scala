package index4s.cli

import cats.effect.IO
import cats.kernel.Eq
import cats.Show
import cats.syntax.all.*
import index4s.client.{ApiError, ScaladexClient, Target}
import index4s.domain.Coordinates

/** The three identifier forms `index4s get` accepts:
  *   1. `org/repo` — exact
  *   2. `group:artifact[:version]` (incl. `::` — normalized via Coordinates)
  *   3. bare name — resolved via [[Resolve.resolveBare]]
  */
enum Identifier {
  case OrgRepo(org: String, repo: String)
  case Coordinate(groupId: String, artifactId: String, version: Option[String])
  case Bare(name: String)
}

object Identifier {
  given Eq[Identifier] = Eq.fromUniversalEquals
  given Show[Identifier] = Show.fromToString

  /** Deterministic classification (first match wins):
    *   - contains `/` → OrgRepo (exactly one slash, both segments non-empty)
    *   - contains `:` → Coordinates.normalize (so `g::a` gains the scala
    *     suffix, `g:a` passes through verbatim) → Coordinate
    *   - otherwise → Bare
    *
    * Blank input, malformed org/repo, or coordinates that fail normalization →
    * Left(msg) (the get handler maps this to CliError.Invalid).
    */
  def parse(input: String): Either[String, Identifier] = {
    val trimmed = input.trim
    if trimmed.isEmpty then Left("empty identifier")
    else if trimmed.contains('/') then parseOrgRepo(trimmed)
    else if trimmed.contains(':') then parseCoordinate(trimmed)
    else Right(Identifier.Bare(trimmed))
  }

  private def parseOrgRepo(input: String): Either[String, Identifier] =
    input.split("/", -1).toList match {
      case organization :: repository :: Nil
          if organization.nonEmpty && repository.nonEmpty =>
        Right(Identifier.OrgRepo(organization, repository))
      case _ =>
        Left(s"invalid org/repo '$input' — expected exactly <org>/<repo>")
    }

  /** Normalizes first (suffix rules, validation) and then decomposes — the
    * normalized form is always `g:a` or `g:a:v` (groupIds and artifactIds never
    * contain colons), so the split is total.
    */
  private def parseCoordinate(input: String): Either[String, Identifier] =
    Coordinates.normalize(input).map { normalized =>
      val parts = normalized.split(":", -1)
      Identifier.Coordinate(
        groupId = parts(0),
        artifactId = parts(1),
        version = if parts.length == 3 then Some(parts(2)) else None
      )
    }
}

/** Bare-name resolution: confident → proceed + stderr note; ambiguous →
  * candidates, exit 2; NEVER a silent guess.
  */
object Resolve {

  enum Resolved {

    /** A single pinned project.
      *   - `confident = true` — an autocomplete hit whose repository equals the
      *     queried name (case-insensitive): the user's name IS this repo.
      *   - `confident = false` — pinned by a weaker rule (the single
      *     autocomplete hit, or the top search result on zero hits). Callers
      *     print [[Resolve.note]] on stderr so the pin is never silent.
      */
    case Pinned(org: String, repo: String, confident: Boolean)
  }

  object Resolved {
    given Eq[Resolved] = Eq.fromUniversalEquals
    given Show[Resolved] = Show.fromToString
  }

  /** Resolution algorithm (deterministic, Scaladex-only):
    *   1. `autocomplete(name)` → hits (any ApiError → Left(Api))
    *   2. a hit whose `repository` equals `name` case-insensitively →
    *      Pinned(confident = true); otherwise exactly one hit →
    *      Pinned(confident = false)
    *   3. zero hits → fall back to `search(name, Jvm)`; the head of the results
    *      (if any) → Pinned(confident = false)
    *   4. multiple non-exact hits → Left(AmbiguousName(name, first 5 candidates
    *      as [[Ambiguity]])) — the CLI boundary renders them onto stdout with
    *      exit 2
    *   5. zero hits everywhere → Left(NotResolved(name))
    */
  def resolveBare(
      name: String,
      client: ScaladexClient
  ): IO[Either[CliError, Resolved]] =
    client.autocomplete(name).flatMap {
      case Left(err)   => IO.pure(Left(CliError.Api(err)))
      case Right(hits) =>
        hits.find(_.repository.equalsIgnoreCase(name)) match {
          case Some(exact) =>
            IO.pure(
              Right(
                Resolved.Pinned(
                  exact.organization,
                  exact.repository,
                  confident = true
                )
              )
            )
          case None =>
            hits match {
              case only :: Nil =>
                IO.pure(
                  Right(
                    Resolved.Pinned(
                      only.organization,
                      only.repository,
                      confident = false
                    )
                  )
                )
              case Nil =>
                fallbackToSearch(name, client)
              case _ =>
                IO.pure(
                  Left(
                    CliError.AmbiguousName(
                      name,
                      hits
                        .take(5)
                        .map(h =>
                          Ambiguity(h.organization, h.repository, h.description)
                        )
                    )
                  )
                )
            }
        }
    }

  private def fallbackToSearch(
      name: String,
      client: ScaladexClient
  ): IO[Either[CliError, Resolved]] =
    client.search(name, Target.Jvm()).flatMap {
      case Left(err)      => IO.pure(Left(CliError.Api(err)))
      case Right(results) =>
        results.headOption match {
          case Some(top) =>
            IO.pure(
              Right(
                Resolved
                  .Pinned(top.organization, top.repository, confident = false)
              )
            )
          case None => IO.pure(Left(CliError.NotResolved(name)))
        }
    }

  /** stderr note for a non-exact pin:
    * `resolved: <name> → <org>/<repo> (pin with <org>/<repo>)`. Exact
    * (confident) pins resolve silently — None.
    */
  def note(name: String, pinned: Resolved.Pinned): Option[String] =
    if pinned.confident then None
    else
      Some(
        s"resolved: $name → ${pinned.org}/${pinned.repo} (pin with ${pinned.org}/${pinned.repo})"
      )
}
