package index4s.cli.get

import index4s.cli.ReadmeMode
import index4s.domain.Readme

/** README card block rendering: `readme: off`, `readme: unavailable (reason)`,
  * or a fenced markdown block — head (first 30 lines, the default), full
  * (`--readme full`), or one extracted section (`--section <title>`, capped at
  * 60 lines).
  *
  * Section extraction: the first heading (ATX `#`–`######`) whose text equals
  * the title case-insensitively; content runs to the next heading of the same
  * or higher level (or EOF). The block INCLUDES the matched heading.
  */
object ReadmeView {

  val HeadLines: Int = 30
  val SectionCap: Int = 60

  def render(
      mode: ReadmeMode,
      section: Option[String],
      readme: Option[Readme]
  ): String =
    mode match {
      case ReadmeMode.Off => "readme: off"
      case _              =>
        readme match {
          case None                             => "readme: off"
          case Some(Readme.Unavailable(reason)) =>
            s"readme: unavailable ($reason)"
          case Some(Readme.Available(md)) if md.isBlank =>
            "readme: unavailable (empty readme)"
          case Some(Readme.Available(md)) =>
            section match {
              case Some(title) => renderSection(md, title)
              case None        =>
                val content =
                  if mode == ReadmeMode.Full then md
                  else md.split("\n").take(HeadLines).mkString("\n")
                fence(content)
            }
        }
    }

  def renderSection(md: String, title: String): String =
    extractSection(md, title) match {
      case None        => s"readme: section '$title' not found"
      case Some(lines) => fence(lines.take(SectionCap).mkString("\n"))
    }

  private val Heading = """^(#{1,6})\s+(.*?)\s*$""".r

  private def headingLevel(line: String): Option[Int] = line match {
    case Heading(hashes, _) => Some(hashes.length)
    case _                  => None
  }

  def extractSection(md: String, title: String): Option[List[String]] = {
    val lines = md.split("\n").toList
    val start = lines.indexWhere {
      case Heading(_, text) => text.equalsIgnoreCase(title.trim)
      case _                => false
    }
    if start == -1 then None
    else {
      val level = headingLevel(lines(start)).get
      val following = lines.drop(start + 1)
      val stop = following.indexWhere(l => headingLevel(l).exists(_ <= level))
      Some(
        lines.slice(
          start,
          start + 1 + (if stop == -1 then following.length else stop)
        )
      )
    }
  }

  /** Fence length escalates past the longest backtick run inside the content so
    * embedded code fences cannot break the block.
    */
  private def fence(content: String): String = {
    val longestRun = content
      .split("\n")
      .iterator
      .map(line => line.takeWhile(_ == '`').length)
      .foldLeft(0)(math.max)
    val marker = "`" * math.max(3, longestRun + 1)
    s"readme:\n$marker\n$content\n$marker"
  }
}
