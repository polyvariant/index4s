package index4s.cli.get

import index4s.cli.ReadmeMode
import index4s.domain.{Fixture, Readme}
import weaver.FunSuite

/** ReadmeView: head-30 default, full, off, section extraction (real
  * readme-circe.md fixture, real headings — `## License` runs to EOF, 60-line
  * cap engaged), unavailable degradation, and fence escalation.
  */
object ReadmeViewSuite extends FunSuite {

  private val md = Fixture.text("readme-circe.md").replace("\r\n", "\n")
  private val lines = md.split("\n").toList

  test("head: first 30 lines fenced; content is the fixture's own head") {
    val rendered =
      ReadmeView.render(ReadmeMode.Head, None, Some(Readme.Available(md)))
    expect.eql(
      "readme:\n```\n" + lines.take(30).mkString("\n") + "\n```",
      rendered
    )
  }

  test(
    "full: entire markdown fenced (307-line fixture, head-30 boundary crossed)"
  ) {
    val rendered =
      ReadmeView.render(ReadmeMode.Full, None, Some(Readme.Available(md)))
    expect.eql("readme:\n```\n" + md + "\n```", rendered)
  }

  test("off: single line, no fetch ever needed") {
    expect.eql("readme: off", ReadmeView.render(ReadmeMode.Off, None, None))
    expect.eql(
      "readme: off",
      ReadmeView.render(ReadmeMode.Off, None, Some(Readme.Available(md)))
    )
  }

  test("unavailable: reason rendered in parens") {
    expect.eql(
      "readme: unavailable (no readme)",
      ReadmeView.render(
        ReadmeMode.Head,
        None,
        Some(Readme.Unavailable("no readme"))
      )
    )
  }

  test(
    "section: 'License' (h2, runs to EOF) — heading included, capped at 60 lines"
  ) {
    val rendered = ReadmeView.render(
      ReadmeMode.Head,
      Some("License"),
      Some(Readme.Available(md))
    )
    val extracted = lines.drop(195) // fixture line 196 is '## License'
    expect.all(
      rendered.startsWith("readme:\n```\n## License\n"),
      rendered.endsWith("\n```")
    ) && expect.eql(
      "readme:\n```\n" + extracted.take(60).mkString("\n") + "\n```",
      rendered
    )
  }

  test(
    "section: heading match is case-insensitive; stops at next same-level heading"
  ) {
    val rendered = ReadmeView.render(
      ReadmeMode.Head,
      Some("COMMUNITY"),
      Some(Readme.Available(md))
    )
    // '## Community' at line 13; next same-or-higher heading is
    // '## Contributors and participation' at line 182. The 169-line section
    // is capped at 60 lines by the section extractor.
    val expected =
      "readme:\n```\n" + lines.slice(12, 181).take(60).mkString("\n") + "\n```"
    expect.eql(expected, rendered)
  }

  test("section: unknown title → explicit not-found line, never a crash") {
    expect.eql(
      "readme: section 'Nope' not found",
      ReadmeView.render(
        ReadmeMode.Head,
        Some("Nope"),
        Some(Readme.Available(md))
      )
    )
  }

  test("fence escalates past embedded triple backticks") {
    val tricky = "text\n```\ncode\n```\nmore"
    val rendered =
      ReadmeView.render(ReadmeMode.Full, None, Some(Readme.Available(tricky)))
    expect.eql("readme:\n````\n" + tricky + "\n````", rendered)
  }
}
