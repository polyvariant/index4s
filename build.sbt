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
    // ember's native TLS: link with S2N_LIBDIR=<s2n-tls-prefix>/lib (holds libs2n.a).
    // Scala Native's discovery reads the env var and adds -ls2n, but NOT the
    // -L search path — supply it via nativeConfig (sbt 2 plugin has no bare
    // nativeLinkingOptions key). Only libs2n.a exists there, so s2n is linked
    // STATICALLY; its undefined OpenSSL symbols are resolved by -lcrypto
    // (DYNAMIC on Linux, from system OpenSSL — libcrypto.so.3 becomes a
    // runtime dep).
    // Scala Native places custom linkingOptions BEFORE its discovered -l
    // flags and prepends -Wl,--as-needed, so a bare -lcrypto here would be
    // dropped (nothing references it yet) before -ls2n pulls OpenSSL refs —
    // wrap it in --no-as-needed to keep it live for the whole link.
    // Platform gating (flags differ by linker):
    //   Linux (GNU ld)      -L + --no-as-needed/-lcrypto/--as-needed, as above.
    //   macOS (ld64)        --no-as-needed/--as-needed DO NOT EXIST on ld64.
    //                        Release CI sets INDEX4S_LIBCRYPTO_A to brew
    //                        openssl@3's libcrypto.a, which is -force_load-ed:
    //                        position-independent (our flags precede -ls2n, so
    //                        a plain archive listed earlier would contribute
    //                        nothing) and makes the binary self-contained
    //                        (dynamic brew libcrypto would bake its absolute
    //                        path into the binary and break on stock macOS).
    //                        Without the env var: plain -lcrypto (dev fallback).
    //   Windows             Nil — windows releases are disabled until an
    //                        s2n-tls provisioning path exists (see
    //                        .github/workflows/release.yml). GNU-style -Wl
    //                        flags are rejected by MSVC link anyway.
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
            Seq(s"-L$dir", "-Wl,--no-as-needed", "-lcrypto", "-Wl,--as-needed")
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
