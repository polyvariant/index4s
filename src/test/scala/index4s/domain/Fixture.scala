package index4s.domain

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}

/** Loads captured wire bytes from src/test/resources/fixtures. Tries the
  * classpath first (nativeLink embeds resources), falls back to the filesystem
  * relative to the sbt working directory.
  */
object Fixture {
  def text(name: String): String = {
    val in = getClass.getClassLoader.getResourceAsStream(s"fixtures/$name")
    if in != null then {
      val bytes = in.readAllBytes()
      in.close()
      new String(bytes, StandardCharsets.UTF_8)
    } else {
      val path = Paths.get("src/test/resources/fixtures", name)
      new String(Files.readAllBytes(path), StandardCharsets.UTF_8)
    }
  }

  def lines(name: String): List[String] = text(name).split("\n", -1).toList
}
