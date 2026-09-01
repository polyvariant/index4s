package index4s.cli.get

import java.nio.file.{Files, Path, Paths}
import scala.scalanative.posix.sys.stat.chmod
import scala.scalanative.unsafe.{toCString, Zone}
import scala.scalanative.unsigned.*
import weaver.FunSuite

/** CellarHint: pure-fs PATH detection (no process spawn) and the card hint
  * block. Detection tests use REAL temp directories — an executable `cellar`
  * (mode 0o755 via posix chmod, native-safe) vs an empty dir vs a
  * non-executable file — so the java.nio predicate itself is exercised, not a
  * mock.
  */
object CellarHintSuite extends FunSuite {

  private def tempDir(name: String): Path =
    Files.createTempDirectory(s"index4s-$name")

  private def chmod755(path: Path): Unit =
    Zone.acquire { zone =>
      chmod(toCString(path.toString)(zone), 0x755.toUInt)
      ()
    }

  test("detect: None PATH / empty PATH → not detected") {
    expect.all(
      !CellarHint.detect(None),
      !CellarHint.detect(Some("")),
      !CellarHint.detect(Some(":::"))
    )
  }

  test("detect: empty temp dir on PATH → not detected") {
    val dir = tempDir("empty")
    expect(!CellarHint.detect(Some(dir.toString))) &&
    expect(!CellarHint.detect(Some(s"/nonexistent-dir:${dir.toString}")))
  }

  test("detect: executable `cellar` in a PATH dir → detected") {
    val dir = tempDir("has-cellar")
    val cellar = Files.write(Paths.get(dir.toString, "cellar"), "fake".getBytes)
    chmod755(cellar)
    expect(CellarHint.detect(Some(dir.toString))) &&
    expect(CellarHint.detect(Some(s"/nonexistent:${dir.toString}")))
  }

  test(
    "detect: non-executable `cellar` → not detected (executable bit required)"
  ) {
    val dir = tempDir("plain-file")
    // Files.write default perms: 0o644 — regular but NOT executable.
    Files.write(Paths.get(dir.toString, "cellar"), "fake".getBytes)
    expect(!CellarHint.detect(Some(dir.toString)))
  }

  test("block: fully-suffixed coordinate; install hint iff NOT detected") {
    val absent =
      CellarHint.block("io.circe:circe-core_3:0.14.16", detected = false)
    val present =
      CellarHint.block("io.circe:circe-core_3:0.14.16", detected = true)
    expect.all(
      absent.size == 5,
      present.size == 4
    ) && expect.eql(
      List(
        "---",
        "Scala API inspection — via cellar:",
        "  cellar deps io.circe:circe-core_3:0.14.16",
        "  cellar get-external io.circe:circe-core_3:0.14.16 <symbol>",
        "Install: cs install --contrib cellar"
      ),
      absent
    ) && expect.eql(
      List(
        "---",
        "Scala API inspection — via cellar:",
        "  cellar deps io.circe:circe-core_3:0.14.16",
        "  cellar get-external io.circe:circe-core_3:0.14.16 <symbol>"
      ),
      present
    )
  }
}
