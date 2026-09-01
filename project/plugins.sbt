addSbtPlugin("org.scala-native" % "sbt-scala-native" % "0.5.12")
addSbtPlugin("com.eed3si9n" % "sbt-buildinfo" % "0.13.1")
// Publishing: sbt 2 native Central Portal flow (publishSigned + sonaRelease).
// All three have published sbt2 (``_sbt2_3``) variants — verified on Central:
// https://repo1.maven.org/maven2/com/github/sbt/sbt-dynver_sbt2_3/ etc.
// (sbt-typelevel / sbt-sonatype are NOT sbt2-ready; sbt-ci-release would also
// work but the native sbt 2 flow needs nothing beyond dynver + pgp.)
addSbtPlugin("com.github.sbt" % "sbt-dynver" % "5.1.1")
addSbtPlugin("com.github.sbt" % "sbt-pgp" % "2.3.2")
// scalafmt-dynamic resolves the version pinned in .scalafmt.conf (3.10.7).
addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.6.2")
