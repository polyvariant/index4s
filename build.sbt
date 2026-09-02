organization := "org.polyvariant"
// version is derived by sbt-dynver from git tags: a `v*` tag yields the bare
// version (e.g. 0.1.0), everything after yields `<next>.0+n.<sha>-SNAPSHOT`
// (fallback `0.0.0+n.<sha>` until the first tag exists; dirty trees append a
// timestamp). CI checkouts therefore use fetch-depth: 0. NOTE: the sbt server
// caches the loaded build — verify version claims only on a fresh boot.
scalaVersion := "3.3.8"

lazy val index4s = project
  .in(file("."))
  .enablePlugins(ScalaNativePlugin, BuildInfoPlugin)
  .settings(
    name := "index4s",
    buildInfoPackage := "index4s",
    buildInfoKeys := Seq[BuildInfoKey](name, version),
    Compile / mainClass := Some("index4s.Main"),
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-effect" % "3.7.1",
      "co.fs2" %% "fs2-io" % "3.13.0",
      "com.softwaremill.sttp.client4" %% "http4s-backend" % "4.0.26",
      "org.http4s" %% "http4s-ember-client" % "0.23.36",
      "com.lihaoyi" %% "upickle" % "4.4.3",
      "com.monovore" %% "decline-effect" % "2.6.2",
      "org.typelevel" %% "weaver-cats" % "0.13.0" % Test,
      "org.typelevel" %% "weaver-scalacheck" % "0.13.0" % Test
    ),
    // sbt 2 makes eviction warnings fatal; scalacheck 1.19.0 declares test-interface 0.5.8
    // (resolves to 0.5.12 — same winner as under sbt 1; Scala Native test-interface is
    // backward-compatible across 0.5.x, this silences the false-positive strict conflict)
    libraryDependencySchemes += "org.scala-native" % "test-interface_native0.5_3" % VersionScheme.Always,
    // ember's native TLS: link with S2N_LIBDIR=<s2n-tls-prefix>/lib — libs2n.a
    // is built with a STATIC libcrypto interned in (-DS2N_INTERN_LIBCRYPTO=ON,
    // s2n$-prefixed symbols). Linux flags additionally force-include STATIC
    // idn2/z so the binary's only dynamic deps are glibc. WHY: option
    // ordering, the macOS -force_load branch, Windows blockers, provisioning
    // and verification recipes → docs/linking.md.
    // NOTE: the sbt 2 server captures env at startup — REBOOT the server
    // (shutdown + fresh invocation) after changing S2N_LIBDIR.
    nativeConfig := nativeConfig.value.withLinkingOptions(
      nativeConfig.value.linkingOptions ++ {
        val osName = sys.props("os.name").toLowerCase(java.util.Locale.ROOT)
        val isMac = osName.contains("mac")
        val isWin = osName.contains("windows")
        def s2nFlags(dir: String): Seq[String] =
          if (isWin) Seq.empty
          else if (isMac)
            sys.env.get("INDEX4S_LIBCRYPTO_A").filter(_.nonEmpty) match {
              case Some(archive) => Seq(s"-L$dir", s"-Wl,-force_load,$archive")
              case None          => Seq(s"-L$dir", "-lcrypto")
            }
          else
            Seq(
              s"-L$dir",
              "-Wl,--whole-archive",
              "-l:libidn2.a",
              "-l:libz.a",
              "-Wl,--no-whole-archive",
              // SELECTIVE (outside the whole-archive group): Ubuntu's libidn2.a
              // bundles only ~12 unistring objects; its remaining uc_* refs are
              // completed from here. Already-defined bundled symbols prevent
              // extraction of the duplicate members — see docs/linking.md.
              "-l:libunistring.a"
            )
        sys.env.get("S2N_LIBDIR").map(s2nFlags).getOrElse(Seq.empty)
      }
    ),
    scalacOptions ++= Seq(
      "-no-indent"
    )
  )

// Publishing — sbt 2 native Central Portal flow (no sbt-sonatype / sbt-ci-release
// needed on sbt 2): `publishSigned` stages a release into the localStaging bundle
// and `sonaRelease` uploads + publishes it. sbt 2 maps SONATYPE_USERNAME /
// SONATYPE_PASSWORD env vars to a central.sonatype.com credential out of the box.
// SNAPSHOT versions publish straight to the central snapshots repo via `publish`.
// https://www.scala-sbt.org/2.x/docs/en/recipes/central.html
description := "Single-binary CLI for Scala library discovery on the Scaladex index"
homepage := Some(uri("https://github.com/polyvariant/index4s"))
scmInfo := Some(
  ScmInfo(
    uri("https://github.com/polyvariant/index4s"),
    "scm:git@github.com:polyvariant/index4s.git"
  )
)
licenses := List(
  "Apache-2.0" -> uri("https://www.apache.org/licenses/LICENSE-2.0.txt")
)
developers := List(
  Developer(
    "majk-p",
    "Michał Pawlik",
    "admin@michalp.net",
    uri("https://michal.pawlik.dev")
  )
)
versionScheme := Some("early-semver")
publishMavenStyle := true
pomIncludeRepository := { _ => false }
publishTo := {
  val centralSnapshots =
    "https://central.sonatype.com/repository/maven-snapshots/"
  if (version.value.endsWith("-SNAPSHOT"))
    Some("central-snapshots" at centralSnapshots)
  else localStaging.value
}
