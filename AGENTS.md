# AGENTS.md — index4s

Scala Native CLI for Scaladex-backed Scala library discovery + docs, shipped with an agentskills.io skill (`skills/index4s/`). Two commands: `search` (+`--rank`) and `get`. Full context lives in README.md.

## Authoritative spec
- `.sisyphus/notepads/index4s-design/CONTRACT-v1.md` — the product contract (commands, flags, output shapes, exit codes, test-plan case ownership). Code-vs-contract conflicts: contract wins, then fix code — unless the user says otherwise.
- `.sisyphus/notepads/index4s-design/decisions.md` + `learnings.md` — decision log + hard-won quirks. Read before touching transport, deps, or output shapes.

## Hard rules (user directives — violations were reverted before)
- **No git operations** (no commits/pushes) unless explicitly requested.
- **Any exception to the plan/contract requires the user's explicit approval BEFORE execution.** Do not treat contract escape hatches as pre-authorization (one unauthorized transport pivot cost a full revert).
- **Ember + s2n is the only HTTP transport. Permanently. No curl subprocess, no fallback.**
- Do not modify `scaladex/` — reference checkout of scalacenter/scaladex (gitignored).

## Build & verify (sbt 2.0.8)
- **`docs/linking.md` — canonical reference for the native link** (s2n interning, static idn2/z/unistring, glibc floor, verification recipe). Read it before touching linking options, s2n provisioning, or CI link steps.
- sbt 2: quote compound commands; sbt is client-by-default; **never `sbtn`**.
- `test` is incremental/cached — real execution is **`testFull`**:
  ```bash
  export S2N_LIBDIR=/home/majk/Code/personal/index4s/.native-deps/s2n-install/lib
  sbt --batch "testFull ; shutdown"
  ```
- **Env vars are captured when the sbt server boots.** After changing S2N_LIBDIR (or any env), kill the server (`; shutdown` or `pkill -f sbt-launch`) and start fresh — otherwise linking fails with `cannot find -ls2n`.
- `nativeLink` → `target/out/native0.5/scala-3.3.8/index4s/index4s` (plus `index4s-release-fast`). The binary runs WITHOUT env vars: s2n (with interned static libcrypto) and idn2/z/unistring are all statically linked; the only dynamic deps are glibc (floor 2.38).
- The s2n `-L` + static-archive flags (`--whole-archive -l:libidn2.a -l:libz.a --no-whole-archive` + selective `-l:libunistring.a`; NO `-lcrypto` — interning resolved it) live in build.sbt's env-guarded `nativeConfig` block. Scala Native's discovery reads S2N_LIBDIR for its own compile but does NOT add `-L` to the final app link — that's what the block is for. Local s2n provisioning lives in gitignored `.native-deps/` (built from the pinned v1.7.8 tag).

## Dependencies — verify coordinates, don't trust memory/docs
- weaver = **`org.typelevel` 0.13.0** (`_native0.5_3` published; the old `com.disneystreaming` groupId has NO native0.5 — this stale groupId caused a wrong "no native support" conclusion once).
- decline = **`com.monovore`** 2.6.2 (not org.typelevel).
- All versions pinned exact; sbt 2 `%%` is platform-aware (no `%%%`).
- `src/test/resources/fixtures/` are REAL captured API bytes. Changing codecs → re-capture via curl first (see `DecoderSuite` for endpoints); tests never hit the network (sttp `BackendStub` only).

## Testing quirks
- Tests run NATIVELY (weaver, `weaver.framework.CatsEffect`). Big failure diffs can crash the native RPC — assert on small slices; give case classes Eq/Show givens for `expect.eql`.
- Freshness/date logic uses an injected `Clock` — never `IO.realTime` in pure paths.
- **CI workflows are UNTESTED (partially executed)**: first push (2026-09-01) failed workflow VALIDATION before any job ran — `runner` context is not available in job-level `env:`; both workflows now export S2N_PREFIX/S2N_LIBDIR via GITHUB_ENV in an early step instead. Second push: linux link failed with `cannot find -lidn2` — Ubuntu runners have the libidn2 runtime lib but not the dev symlink, while Scala Native's discovery adds `-lidn2` on Linux regardless; both workflows now apt-install `libidn2-dev` (macOS discovery doesn't add it — no brew package needed). Jobs themselves have not fully run yet — treat the pipeline as unproven until the first green run.
- Local parity for CI's core job: `S2N_LIBDIR=… sbt --batch "testFull ; shutdown"` on linux.

## Code layout
- `domain/` — wire models (upickle, exact Scaladex field names) + pure logic (artifact-suffix parser, freshness tiers, default-artifact selection, deterministic sorts). No IO.
- `client/` — ScaladexClient: sttp+ember over an injected backend; methods return `IO[Either[ApiError, A]]` — errors are data, never thrown. Retry ×2 on 5xx/network only.
- `core/` — enrichment merge (`EnrichedProject`); degradation = failures recorded in `failures` list, rows never dropped.
- `cli/` — decline tree. `ExitCodes.Out(payload, notes)`: payload→stdout, notes→stderr, exit **0/1/2** (2 = ambiguous; candidates table goes to **stdout**, `--json` makes it machine-readable).

## Product invariants (easy to break silently)
- Output contract is product surface: stdout = markdown/json payload only; stderr = diagnostics; NO_COLOR honored; auto-plain when piped.
- Coordinates in ALL emitted output (snippets, cellar hints) are fully-suffixed with concrete versions (`io.circe:circe-core_3:0.14.16`) — never `::`, never `latest`.
- Deterministic: same input ⇒ same output; sorts have documented tie-breakers.
- `search --limit N --rank` slices Scaladex relevance order first, then ranks within the slice (README-documented — don't "fix" silently).
- Default-artifact precedence: `project.defaultArtifact` name → `repo_<scala>` match → platform-filtered select. `zio-json-golden_3` legitimately appears in rank `defaultCoordinate` (see skill troubleshooting.md before changing).
- `--version` is sbt-dynver-derived from git tags (user decision 2026-09-01, was an open decision): `v*` tag → bare version (`0.1.0`), after → `0.x.0+n.<sha>-SNAPSHOT`, fallback `0.0.0+n.<sha>` pre-first-tag. Never set `version` in build.sbt. CI checkouts use `fetch-depth: 0` or the version silently degrades. Verify version claims on a FRESH sbt server only (stale servers print stale builds' values).

## Publishing (coursier contrib model)
- `cs install --contrib index4s`: coursier resolves `latest.stable` from the Maven artifact `org.polyvariant:index4s_native0.5_3` (version anchor only, never executed) and downloads the raw native binary for the user's platform triple from the GitHub release. Asset names `index4s-<version>-<osname>-<arch>` are referenced verbatim by `coursier/apps` apps-contrib/resources/index4s.json — renaming them breaks installs.
- Release = `v*` tag → 4-platform release-fast builds (static s2n) → GH release (raw binaries + tar.gz + sha256) + Maven Central via sbt 2 native `publishSigned ; sonaRelease` (needs PGP_SECRET/PGP_PASSPHRASE/SONATYPE_USERNAME/SONATYPE_PASSWORD secrets, same set as polyvariant repos). PRs/main push → build + smoke + upload-artifact only, no publishing, no snapshots.
- sbt plugins for this are sbt2-only picks (all `_sbt2_3` on Central): sbt-dynver 5.1.1, sbt-pgp 2.3.2, sbt-scalafmt 2.6.2 (dynamic core honors .scalafmt.conf's 3.10.7). sbt-typelevel and sbt-sonatype are NOT sbt2-ready — don't reach for them.
- First-release sequence: create remote → verify CI green → tag v0.1.0 → confirm GH release + Central artifact → PR the app json to coursier/apps (after merge, `cs install --contrib index4s` works; later releases need no coursier/apps changes thanks to `${version}` templating).

## The skill (skills/index4s/)
- Follows skill-creator guidelines (progressive disclosure, pushy description, explain-why-not-MUSTs). Every command example was live-verified against the real binary AND real cellar — if CLI behavior changes, update the skill + `skills/index4s/evals/`.
- Cellar seam: index4s = coordinates/discovery/docs; cellar = symbols/APIs. Never blur; never shell out to cellar from the binary (hints only).

## Formatting
scalafmt IS wired in: sbt-scalafmt 2.6.2 (dynamic core resolves .scalafmt.conf's pinned 3.10.7). CI (ci.yml, linux-amd64 job) runs `scalafmtCheckAll ; scalafmtSbtCheck` — run `sbt scalafmtAll ; scalafmtSbt` before pushing. Compiler runs `-no-indent` (no `-rewrite` — it would rewrite sources on every compile); the scalafmt dialect matches (`allowSignificantIndentation = false`), so indentation-based multi-statement lambdas without braces are illegal — brace them.
