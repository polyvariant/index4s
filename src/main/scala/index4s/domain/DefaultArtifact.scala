package index4s.domain

object DefaultArtifact {

  private val PreRelease =
    raw".*(?:-SNAPSHOT|-RC[\w.]*|-M\d+[\w.]*|-milestone[\w.]*|-alpha[\w.]*|-beta[\w.]*)$$".r

  def isStable(version: String): Boolean = !PreRelease.matches(version)

  private def isPlain(
      ref: ArtifactRef,
      wantedScala: Option[String]
  ): Boolean = {
    val s = ArtifactSuffix.parse(ref.artifactId)
    s.platform.contains(Platform.Jvm) &&
    s.scalaBinaryVersion.isDefined &&
    wantedScala.forall(w => s.scalaBinary.contains(w))
  }

  /** Deterministic default-artifact selection over versions/latest refs.
    *
    * Preference order:
    *   1. plain JVM `_<scala>` refs matching `scalaBinaryVersion` when given
    *      (never a platform-suffixed ref while a plain one exists);
    *   2. any stable (non-pre-release) ref;
    *   3. any ref.
    * Ties inside a tier break by lexicographically smallest artifactId.
    * Live-proven case: tethys has NO tethys_3 — its default is tethys-cats_3.
    */
  def select(
      refs: List[ArtifactRef],
      scalaBinaryVersion: Option[String] = Some("3")
  ): Option[ArtifactRef] =
    if refs.isEmpty then None
    else {
      def byId(rs: List[ArtifactRef]) = rs.sortBy(_.artifactId)
      val plains = byId(refs.filter(isPlain(_, scalaBinaryVersion)))
      plains.headOption
        .orElse(byId(refs.filter(r => isStable(r.version))).headOption)
        .orElse(byId(refs).headOption)
    }
}
