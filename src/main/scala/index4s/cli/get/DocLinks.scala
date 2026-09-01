package index4s.cli.get

import index4s.domain.ProjectResponse

/** Doc-link pattern evaluation.
  *
  * Scaladex patterns carry placeholders replaced against the SELECTED (default
  * or pinned) artifact:
  *   - `[groupId]`, `[artifactId]` (fully-suffixed), `[version]` (concrete,
  *     never `latest`), `[name]` (suffix-stripped)
  *   - `[major]` = version up to the 2nd dot ("0.14.16" → "0.14")
  *   - `[minor]` is NOT a distinct token — it resolves to the FULL version
  *     (deliberate: we never guess a minor-only rendering)
  *
  * `scaladoc` resolution order: project.customScalaDoc pattern → first
  * documentationLinks pattern → javadoc.io/doc/{g}/{a}/{v} fallback.
  */
object DocLinks {

  final case class Context(
      groupId: String,
      artifactId: String,
      version: String,
      name: String
  )

  final case class Evaluated(label: String, url: String)

  def evaluate(pattern: String, ctx: Context): String =
    pattern
      .replace("[groupId]", ctx.groupId)
      .replace("[artifactId]", ctx.artifactId)
      .replace("[version]", ctx.version)
      .replace("[major]", major(ctx.version))
      .replace("[minor]", ctx.version)
      .replace("[name]", ctx.name)

  def major(version: String): String =
    version.split('.') match {
      case Array(single)   => single
      case Array(a, b, _*) => s"$a.$b"
      case _               => version
    }

  def docs(project: ProjectResponse, ctx: Context): List[Evaluated] =
    project.documentationLinks.map(l =>
      Evaluated(l.label, evaluate(l.pattern, ctx))
    )

  def scaladoc(project: ProjectResponse, ctx: Context): String =
    project.customScalaDoc
      .map(evaluate(_, ctx))
      .orElse(
        project.documentationLinks.headOption.map(l => evaluate(l.pattern, ctx))
      )
      .getOrElse(
        s"https://www.javadoc.io/doc/${ctx.groupId}/${ctx.artifactId}/${ctx.version}"
      )
}
