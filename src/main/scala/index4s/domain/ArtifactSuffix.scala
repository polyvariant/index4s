package index4s.domain

import cats.kernel.Eq
import cats.Show

/** Platform of a Scala artifact, as encoded in its artifactId suffix. */
enum Platform {
  case Js(version: String)
  case Native(version: String)
  case SbtPlugin(version: String)
  case Jvm
}

object Platform {
  given Eq[Platform] = Eq.fromUniversalEquals
}

/** Result of parsing an artifactId suffix.
  *
  * Field semantics (deliberate):
  *   - scalaBinaryVersion = Some(token): the raw trailing scala token, e.g.
  *     "3", "2.13", or a FULL version like "3.3.8" (kept raw so parse→render
  *     round-trips; use [[scalaBinary]] for the normalized binary form).
  *   - platform = Some(Jvm): plain `_<scala>` suffix (a JVM artifact).
  *   - platform = Some(Js/Native/SbtPlugin): platform-prefixed suffix.
  *   - platform = None: a recognized-but-unsupported platform token was found
  *     (currently `_mill<v>_<scala>`) — definitely NOT a plain JVM artifact.
  *   - scalaBinaryVersion = None: no recognizable scala suffix at all (java or
  *     garbage artifactId) — fallback carries platform = Some(Jvm).
  */
final case class Suffix(
    scalaBinaryVersion: Option[String],
    platform: Option[Platform]
) {

  /** Normalized binary scala version: "3.3.8" → "3", "2.13.16" → "2.13". */
  def scalaBinary: Option[String] =
    scalaBinaryVersion.map { v =>
      v.split('.') match {
        case Array("3", _*)        => "3"
        case Array("2", minor, _*) => s"2.$minor"
        case _                     => v
      }
    }

  /** Canonical suffix text (without leading underscore, base name excluded).
    * sbt uses the bare `_scala_sbtVersion` form; unknown platforms (None)
    * render only the scala token — best-effort, not a full inverse for those.
    */
  def render: String = (platform, scalaBinaryVersion) match {
    case (Some(Platform.Js(v)), Some(sv))        => s"sjs${v}_$sv"
    case (Some(Platform.Native(v)), Some(sv))    => s"native${v}_$sv"
    case (Some(Platform.SbtPlugin(v)), Some(sv)) => s"${sv}_${v}"
    case (Some(Platform.Jvm), Some(sv))          => sv
    case (_, Some(sv))                           => sv
    case _                                       => ""
  }
}

object Suffix {
  given Eq[Suffix] = Eq.fromUniversalEquals
  given Show[Suffix] = Show.fromToString
}

object ArtifactSuffix {

  // Scala tokens: "3", "3.3.8", "2.12", "2.13.16" (2.10–2.13 only; there is no scala 2.0).
  private val Scala = raw"(?:3|2\.1[0-3])(?:\.\d+(?:\.\d+)?(?:-[\w.]*)?)?"

  // Loose semver-ish version for platform / bare-sbt tokens: "1", "0.5", "2.0.12".
  private val Ver = raw"\d+(?:\.\d+)*(?:-[\w.]*)?"

  private val SjsTail = raw".*_sjs($Ver)_($Scala)$$".r
  private val NativeTail = raw".*_native($Ver)_($Scala)$$".r
  private val SbtTail = raw".*_sbt($Ver)_($Scala)$$".r
  private val MillTail = raw".*_mill($Ver)_($Scala)$$".r
  private val BareSbtTail = raw".*_($Scala)_($Ver)$$".r
  private val ScalaTail = raw".*_($Scala)$$".r

  private val ScalaOnly = raw"^($Scala)$$".r

  private def isScalaToken(token: String): Boolean = ScalaOnly.matches(token)

  /** Total function: never throws. Unparseable ids fall back to Suffix(None,
    * Some(Platform.Jvm)) — "JVM artifact, no scala info".
    */
  def parse(artifactId: String): Suffix =
    artifactId match {
      case SjsTail(v, sv)    => Suffix(Some(sv), Some(Platform.Js(v)))
      case NativeTail(v, sv) => Suffix(Some(sv), Some(Platform.Native(v)))
      case SbtTail(v, sv)    => Suffix(Some(sv), Some(Platform.SbtPlugin(v)))
      case MillTail(_, sv)   => Suffix(Some(sv), None)
      case BareSbtTail(sv, v) if !isScalaToken(v) =>
        Suffix(Some(sv), Some(Platform.SbtPlugin(v)))
      case ScalaTail(sv) => Suffix(Some(sv), Some(Platform.Jvm))
      case _             => Suffix(None, Some(Platform.Jvm))
    }
}
