# index4s

CLI for Scala library discovery, backed by the [Scaladex](https://index.scala-lang.org) index: current versions, exact coordinates, stars and freshness.

Provides two tools:

- **`search`** - *which library?* Relevance-ordered listings, or `--rank` for a stars/freshness decision table.
- **`get`** - *what are its exact coordinates?* A project card with fully-suffixed build coordinates for sbt / mill / scala-cli, docs links, and a README head.

All data comes from the Scaladex public API - no scores or opinions beyond the documented star/date sorts. Useful precisely when memory is stale: namespaces move (weaver moved `com.disneystreaming` → `org.typelevel`) and memory-resolved coordinates 404.

Static-ish binary (Scala Native), one runtime dependency on Linux (`libcrypto.so.3`), fully self-contained on macOS.

## Install

With [coursier](https://get-coursier.io) (recommended - picks the statically linked native binary for your arch):

```console
$ cs install --contrib index4s
$ index4s --version
index4s 0.1.0
```


<details>
<summary>Github releases install</summary>

Or download from the [releases page](https://github.com/polyvariant/index4s/releases), verify, put on PATH:

```console
$ curl -LO https://github.com/polyvariant/index4s/releases/download/v0.1.0/index4s-0.1.0-linux-amd64.tar.gz
$ tar -xzf index4s-0.1.0-linux-amd64.tar.gz && chmod +x index4s
$ ./index4s --version
index4s 0.1.0
```

Assets: `index4s-{version}-{linux,macos}-{amd64,arm64,x86_64}` raw native binaries (what coursier serves) and the same binaries as `.tar.gz` (+ `.sha256`) archives.
Windows builds are blocked pending an s2n-tls provisioning path - see the release workflow for details.

Verify a download:

```console
$ sha256sum --check index4s-0.1.0-linux-amd64.tar.gz.sha256
index4s-0.1.0-linux-amd64.tar.gz: OK
```
</details>

## Quick tour

Compare JSON libraries by stars and freshness:

```console
$ index4s search json --rank --limit 5
5 candidates · target jvm · scala 3 · sorted by stars

  LIBRARY                              STARS  LATEST RELEASE     LICENSE     CATEGORY
  circe/circe                          2,542  0.14.16 · 2mo  ✓   Apache-2.0  json
  json4s/json4s                        1,485  4.0.7 · 2y8m  💀   Apache-2.0  json
  zio/zio-json                           431  0.9.2 · 4mo  ✓     Apache-2.0  json
  playframework/play-json                375  3.0.6 · 10mo  💤   Apache-2.0  json
  tethys-json/tethys                     121  0.29.8 · 4mo  ✓    Apache-2.0  json

  ✓ <9mo · 💤 9–18mo · 💀 >18mo · - = signal unavailable
```

Get a project card (accepts `org/repo`, `group::artifact`, `group:artifact[:version]`, or a bare name):

```console
$ index4s get circe/circe
circe/circe | Apache-2.0 | category: json | ★ 2,542 | forks 546 | issues 128
Yet another JSON library for Scala - http://circe.io/circe/
topics: generic-derivation, json, scala
latest: 0.14.16 (2026-06) - scala 2.12, 2.13, 3 · sjs1 · native0.5   [108 versions]
default: io.circe:circe-core_3:0.14.16
  sbt       "io.circe" %% "circe-core" % "0.14.16"
  mill      ivy"io.circe::circe-core:0.14.16"
  scala-cli dep"io.circe::circe-core::0.14.16"
docs: none
scaladoc: https://www.javadoc.io/doc/io.circe/circe-core_3/0.14.16
readme:
...
```

Pull a single field (npm-view style positional paths):

```console
$ index4s get circe/circe stars
2542
```

Everything is available as JSON for `jq`:

```console
$ index4s get circe/circe --json | jq -r '.default | "\(.groupId):\(.artifactId):\(.version)"'
io.circe:circe-core_3:0.14.16
$ index4s search json --rank --json | jq -r '.results[] | [.id, .stars] | @tsv' | head -3
circe/circe	2542
json4s/json4s	1485
plokhotnyuk/jsoniter-scala	819
```

Useful flags: `search --topic <t>` (repeatable), `--rank --sort stars|fresh`, `--limit N`; `get --artifact-version <v>`, `--readme head|full|off`, `--section <title>`, `--artifacts`, `--web`. Global: `--json`, `--no-color`, `--timeout`, `--base-url`. `--help` on each command for the full list.

## Exit codes

| Code | Meaning | Streams |
|------|---------|---------|
| 0 | found / success | payload on stdout |
| 1 | not found (`'x' not in index. Try: index4s search x` + did-you-mean suggestions) | diagnostics on stderr |
| 2 | ambiguous bare name | candidate table on stdout, one-line notice on stderr |

stdout is always the markdown/JSON payload; stderr is diagnostics. Color is stripped automatically when piped or when `NO_COLOR` is set.

## The agent skill

index4s ships as an [agentskills.io](https://agentskills.io) skill, living in [`skills/index4s/`](skills/index4s/) in this repo:

```console
$ npx skills add polyvariant/index4s
```

## Pairing with cellar

[cellar](https://github.com/VirtusLab/cellar) reads compiled artifacts (signatures, members, symbol search, dependency trees); index4s finds the library and emits coordinates in exactly cellar's fully-suffixed dialect:

```console
$ index4s get circe/circe --json | jq -r '.default | "\(.groupId):\(.artifactId):\(.version)"' \
    | xargs -I{} cellar get-external {} io.circe.Parser
```

## Development

Scala 3.3.8 · Scala Native 0.5.12 · sbt 2.0.8 (sbt must be started with `S2N_LIBDIR` set - the server captures env at boot) · cats-effect / fs2 / sttp+ember / upickle / decline · weaver tests, running natively.

TLS: HTTP goes through http4s-ember-client with AWS **s2n-tls** linked statically. There is no alternative transport. You provision s2n-tls once:

```console
$ git clone --depth 1 --branch v1.7.8 https://github.com/aws/s2n-tls.git /tmp/s2n-tls
$ cmake -S /tmp/s2n-tls -B /tmp/s2n-tls/build -DBUILD_SHARED_LIBS=OFF -DBUILD_TESTING=OFF \
    -DCMAKE_BUILD_TYPE=Release -DCMAKE_INSTALL_PREFIX="$HOME/s2n-install"
$ cmake --build /tmp/s2n-tls/build --parallel --target install
```

(needs OpenSSL headers: `sudo apt install libssl-dev clang cmake` on Ubuntu, `brew install openssl@3` on macOS)

Then, in a **fresh** shell:

```console
$ S2N_LIBDIR=$HOME/s2n-install/lib sbt --batch "testFull ; nativeLinkReleaseFast ; shutdown"
```

- `testFull` - all weaver tests, linked and run natively (~2 min; `test` alone is incremental/cached).
- `nativeLink` - debug binary for local use; `nativeLinkReleaseFast` - optimized binary → `target/out/native0.5/scala-3.3.8/index4s/index4s-release-fast`.
- Release builds for all platforms happen in CI (`.github/workflows/release.yml`) - each runner provisions the same pinned s2n-tls and smoke-tests the packaged binary before it ships. On `v*` tags the release also publishes `org.polyvariant:index4s_native0.5_3` to Maven Central (sbt 2 native `publishSigned` + `sonaRelease`) and creates the GitHub release whose raw binary assets coursier serves for `cs install --contrib index4s`. CI (`.github/workflows/ci.yml`) runs scalafmt check, compile, and the full suite on the same 4-platform matrix on every PR/push.
- Versions are derived by sbt-dynver from git tags: a `v*` tag yields the bare version (`0.1.0`), anything after yields `0.x.0+n.<sha>-SNAPSHOT` (fallback `0.0.0+n.<sha>` before the first tag). Don't set `version` in build.sbt.

On macOS, also export `INDEX4S_LIBCRYPTO_A=$(brew --prefix openssl@3)/lib/libcrypto.a` for sbt: build.sbt force-loads a static libcrypto so the binary is self-contained.

