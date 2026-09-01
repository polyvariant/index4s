package index4s.domain

import cats.kernel.Eq
import cats.Show
import java.time.Instant
import upickle.default.*

/** Wire models mirroring the Scaladex API byte-for-byte (field names,
  * optionality). Shapes verified against live captures under
  * src/test/resources/fixtures/.
  *
  * Notable wire facts:
  *   - Optional fields are OMITTED entirely when None (never
  *     `"license": null`), hence every optional field carries a `= None` /
  *     `= Nil` default so upickle tolerates missing keys.
  *   - `"homepage": ""` (empty string, Some("")) does occur — play-json.
  *   - ProjectResponse has NO defaultVersion / creationDate / contributorCount.
  *   - versions/latest artifactIds are FULLY-SUFFIXED (e.g. circe_native0.5_3).
  *   - ArtifactResponse fields are all required; releaseDate is ISO-8601 (e.g.
  *     "2026-06-24T16:34:49Z"); platform/language are bare wire strings ("jvm"
  *     / "3"); there is additionally a `binaryVersion` field ("_3").
  */
given ReadWriter[Instant] =
  readwriter[String].bimap[Instant](_.toString, s => Instant.parse(s))

/** GET /api/search — thin search result row (artifact *names*, not ids). */
final case class SearchResult(
    organization: String,
    repository: String,
    logo: Option[String] = None,
    artifacts: List[String] = Nil,
    deprecatedArtifacts: List[String] = Nil
) derives ReadWriter

/** GET /api/autocomplete — `description` is required on the wire (may be
  * empty).
  */
final case class AutocompleteResult(
    organization: String,
    repository: String,
    description: String
) derives ReadWriter

/** GET /api/v1/projects/{organization}/{repository} */
final case class ProjectResponse(
    organization: String,
    repository: String,
    homepage: Option[String] = None,
    description: Option[String] = None,
    logo: Option[String] = None,
    stars: Option[Int] = None,
    forks: Option[Int] = None,
    issues: Option[Int] = None,
    topics: List[String] = Nil,
    contributingGuide: Option[String] = None,
    codeOfConduct: Option[String] = None,
    license: Option[String] = None,
    defaultArtifact: Option[String] = None,
    customScalaDoc: Option[String] = None,
    documentationLinks: List[DocumentationLink] = Nil,
    contributorsWanted: Boolean = false,
    cliArtifacts: List[String] = Nil,
    category: Option[String] = None,
    chatroom: Option[String] = None
) derives ReadWriter

/** Wire shape exactly `{"label": ..., "pattern": ...}` (lowercase, verified
  * live).
  */
final case class DocumentationLink(label: String, pattern: String)
    derives ReadWriter
object DocumentationLink {
  given Eq[DocumentationLink] = Eq.fromUniversalEquals
}

final case class ProjectRef(organization: String, repository: String)
    derives ReadWriter
object ProjectRef {
  given Eq[ProjectRef] = Eq.fromUniversalEquals
}

/** One entry of GET /api/v1/projects/{o}/{r}/versions/latest — an array of
  * these.
  */
final case class ArtifactRef(
    groupId: String,
    artifactId: String,
    version: String
) derives ReadWriter
object ArtifactRef {
  given Eq[ArtifactRef] = Eq.fromUniversalEquals
  given Show[ArtifactRef] = Show.fromToString
}

/** GET /api/v1/artifacts/{groupId}/{artifactId}/latest */
final case class ArtifactResponse(
    groupId: String,
    artifactId: String,
    version: String,
    name: String,
    binaryVersion: String,
    language: String,
    platform: String,
    project: ProjectRef,
    releaseDate: Instant,
    licenses: List[String]
) derives ReadWriter

/** Result of fetching a GitHub raw README — failure is data, not an exception.
  */
enum Readme {
  case Available(markdown: String)
  case Unavailable(reason: String)
}
