package index4s.domain

object Coordinates {

  /** Normalize `g::a[:v]` shorthand into fully-suffixed `g:a_<scala>[:v]`.
    *
    *   - `io.circe::circe-core` → `io.circe:circe-core_3`
    *   - `io.circe::circe-core:0.14.16` → `io.circe:circe-core_3:0.14.16`
    *   - `latest` version passes through untouched
    *   - an artifactId that already carries a scala suffix (`circe-core_3`,
    *     `circe-core_3.3.8`, `circe-core_2.13`) is respected as-is
    *   - single-colon `g:a[:v]` passes through verbatim
    *
    * Invalid input (empty segments, >1 `::`, more than 3 segments) → Left(msg).
    */
  def normalize(
      input: String,
      scalaBinaryVersion: String = "3"
  ): Either[String, String] =
    input.split(":", -1).toList match {
      case g :: "" :: a :: Nil =>
        build(g, a, None, scalaBinaryVersion, appendSuffix = true)
      case g :: "" :: a :: v :: Nil =>
        build(g, a, Some(v), scalaBinaryVersion, appendSuffix = true)
      case g :: a :: Nil =>
        build(g, a, None, scalaBinaryVersion, appendSuffix = false)
      case g :: a :: v :: Nil =>
        build(g, a, Some(v), scalaBinaryVersion, appendSuffix = false)
      case _ =>
        Left(s"Invalid coordinates '$input' — expected g:a[:v] or g::a[:v]")
    }

  private def build(
      groupId: String,
      artifactId: String,
      version: Option[String],
      scalaBinaryVersion: String,
      appendSuffix: Boolean
  ): Either[String, String] =
    if groupId.isEmpty then Left("Empty groupId")
    else if artifactId.isEmpty then Left("Empty artifactId")
    else if version.exists(_.isEmpty) then Left("Empty version")
    else {
      val suffixed =
        if !appendSuffix then artifactId
        else if ArtifactSuffix.parse(artifactId).scalaBinaryVersion.isDefined
        then artifactId
        else s"${artifactId}_$scalaBinaryVersion"
      Right(version.fold(s"$groupId:$suffixed")(v => s"$groupId:$suffixed:$v"))
    }
}
