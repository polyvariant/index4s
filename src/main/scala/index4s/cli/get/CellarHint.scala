package index4s.cli.get

import java.io.File
import java.nio.file.{Files, Path, Paths}

/** Cellar hint block: the natural-flow pointer from a get card to cellar for
  * Scala API (symbol) inspection.
  *
  * Detection is PURE fs — scan the PATH environment's directories for an
  * executable regular file named `cellar` (java.nio Files.isExecutable,
  * posixlib-backed on native; no process spawn, never fails). Callers detect
  * ONCE per run and thread the result through.
  *
  * The hint coordinate is ALWAYS fully-suffixed with a CONCRETE version
  * (`g:a_3:v`, never `::`, never `latest`).
  */
object CellarHint {

  /** `pathEnv` = raw PATH value (e.g. sys.env.get("PATH")); None / empty → not
    * detected. Missing paths and permission oddities simply do not match.
    */
  def detect(pathEnv: Option[String]): Boolean =
    pathEnv.exists { path =>
      path
        .split(File.pathSeparator)
        .iterator
        .filter(_.nonEmpty)
        .exists(dir => isExecutableCellar(Paths.get(dir, "cellar")))
    }

  private def isExecutableCellar(path: Path): Boolean =
    Files.isRegularFile(path) && Files.isExecutable(path)

  /** The card block — `coord` is the fully-suffixed `g:a:v` string. The install
    * hint line appears exactly when cellar was NOT detected.
    */
  def block(coord: String, detected: Boolean): List[String] =
    ("---" ::
      "Scala API inspection — via cellar:" ::
      s"  cellar deps $coord" ::
      s"  cellar get-external $coord <symbol>" :: Nil) ++
      (if detected then Nil else List("Install: cs install --contrib cellar"))
}
