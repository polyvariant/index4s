---
name: index4s
description: >-
  Scala library discovery and dependency-coordinate lookup via the index4s CLI (backed by
  Scaladex, the Scala ecosystem index). Use whenever the task involves finding, comparing,
  or choosing Scala libraries ("which JSON library should I use", best option for a topic,
  freshest/most popular), adding or upgrading a Scala dependency in sbt/mill/scala-cli,
  resolving groupId:artifact:version coordinates, checking the latest version or release
  date, or Scala-version/platform compatibility (Scala 3 vs 2.13; JVM, Scala.js, Scala
  Native, sbt plugins) — even when the user never says "index4s" or "Scaladex", and
  especially before writing Scala coordinates into any build file: namespaces move
  (weaver: com.disneystreaming → org.typelevel) and memory-resolved coordinates 404.
  NOT for API signatures, members, or sources of an already-resolved dependency — that
  is cellar's job (index4s output hands off to cellar). NOT for Java/Maven-only or
  non-Scala dependencies.
---

# index4s — Scala library discovery & coordinates

`index4s` answers two questions: **"which Scala library?"** (`search`) and **"what are its exact current coordinates, versions, platforms, docs?"** (`get`). All data comes from the Scaladex public API; the only rankings are Scaladex relevance and the documented star/date sorts. Resolve coordinates here rather than from memory — training data is stalest exactly where it matters: moved namespaces. Samples below were captured live on 2026-09-01; numbers drift, shapes are stable.

## Prerequisites

Check the binary once per session:

```
$ index4s --version
index4s 0.1.0-SNAPSHOT
```

The version pins the behavior these pages describe. On an older binary expect flag/output drift — trust the shapes in [references/commands.md](references/commands.md), not exact bytes. If missing: install a prebuilt binary from https://github.com/polyvariant/index4s/releases (self-contained, no JVM or Scala toolchain needed), or build from source (README §Development — sbt plus s2n-tls provisioning). If neither is possible, fall back to the Scaladex web UI and say so: same index, but none of the guarantees below (no `--json`, no exit codes), and the stale-namespace traps in the discipline section apply there just the same.

## Output contract (read this once, it shapes everything)

- **stdout = payload only** (markdown, or JSON with `--json`). Safe to pipe. Exit-2 candidates are a payload too: the ranked table (or candidates JSON with `--json`) goes to **stdout**.
- **stderr = diagnostics**: resolution notes, degradation notices, not-found reasons, ambiguity one-line notice. **Read stderr even on exit 0** — a degradation notice there means retries already happened; don't re-run.
- **Exit codes**: `0` found · `1` not found, API error, or usage/parse error (stderr text distinguishes them) · `2` ambiguous — the identifier matched multiple projects. Ambiguity is not failure: pick the candidate that fits the user's intent and re-run (`get zio/zio-json`), ask the user when candidates genuinely compete, or — when none plausibly matches (fuzzy autocomplete returns junk for nonexistent names) — treat as not-found and `search` instead. Candidates print in Scaladex autocomplete order. Recovery details: [references/troubleshooting.md](references/troubleshooting.md).
- `--json` exists on every command for jq pipelines; JSON is full-fidelity (never truncated).
- **Global flags go after the subcommand**: `index4s get circe --json` works, `index4s --json get circe` fails with `Unexpected option` (the top level accepts only `--help`/`--version`).
- `—` cells in tables mean "signal unavailable" (Scaladex lacks it or a fetch degraded); a stderr notice explains. The row survives — missing data never hides a library.

## Flow 1 — Discover: `search`

You don't know which library yet (topic, category, or comparison question).

```
$ index4s search json --limit 8
8 projects (Scaladex relevance, target jvm · scala 3) — use --rank for stars/freshness comparison

  zio/zio-json                         artifacts: zio-json, zio-json-docs, zio-json-golden…
  playframework/play-json              artifacts: play-functional, play-json, play-json-joda
  tethys-json/tethys                   artifacts: tethys, tethys-cats, tethys-circe…
  json4s/json4s                        artifacts: json4s, json4s-ast, json4s-core…
  ...
```

Thin search is Scaladex relevance order only — no stars, no dates. For any **"which should I use / what's alive"** question, add `--rank` (enriched comparison table):

```
$ index4s search json --rank --limit 6
6 candidates · target jvm · scala 3 · sorted by stars

  LIBRARY                       STARS  LATEST RELEASE     LICENSE     CATEGORY
  circe/circe                   2,542  0.14.16 · 2mo  ✓   Apache-2.0  json
  json4s/json4s                 1,485  4.0.7 · 2y8m  💀   Apache-2.0  json
  zio/zio-json                    431  0.9.2 · 4mo  ✓     Apache-2.0  json
  playframework/play-json         375  3.0.6 · 10mo  💤   Apache-2.0  json
  tethys-json/tethys              121  0.29.8 · 4mo  ✓    Apache-2.0  json
  scalapb-json/scalapb-circe       47  0.16.0 · 2y1m  💀  MIT         —

  ✓ <9mo · 💤 9–18mo · 💀 >18mo · — = signal unavailable
  inspect: index4s get <org/repo> · API: cellar deps <coordinate> (see get output)
```

Freshness glyphs are release-age tiers. `--rank` shows what relevance order hides — json4s has more stars than zio-json yet is 💀 stale. `--sort fresh` reorders by release age (**requires `--rank`**; silently ignored without it). Platform scoping changes the candidate set — the same query for a Native project is a different list:

```
$ index4s search weaver --target native --limit 5
4 projects (Scaladex relevance, target native · scala 3 · native 0.5) — …

  typelevel/weaver-test          artifacts: weaver-cats, weaver-cats-core, weaver-core…
  typelevel/toolkit              artifacts: toolkit, toolkit-test
  ...
```

Common flags: `--topic T` (repeatable, ANDed), `--target jvm|js|native|sbt`, `--scala 3|2.13|2.12`, `--cli`. Full reference: [references/commands.md](references/commands.md).

## Flow 2 — Inspect: `get`

You know the name (or the user does). `get` accepts three identifier forms:

1. `org/repo` — exact: `get circe/circe`
2. `group:artifact[:version]` — `get io.circe:circe-core_3:0.14.16`; `::` accepted and normalized; `latest` allowed
3. bare name — `get circe`; resolved via autocomplete. Confident → proceeds silently; ambiguous → exit 2, ranked candidates table on **stdout** (machine-readable with `--json`).

```
$ index4s get circe/circe
circe/circe | Apache-2.0 | category: json | ★ 2,542 | forks 546 | issues 128
Yet another JSON library for Scala — http://circe.io/circe/
topics: generic-derivation, json, scala
latest: 0.14.16 (2026-06) — scala 2.12, 2.13, 3 · sjs1 · native0.5   [108 versions]
default: io.circe:circe-core_3:0.14.16
  sbt       "io.circe" %% "circe-core" % "0.14.16"
  mill      ivy"io.circe::circe-core:0.14.16"
  scala-cli dep"io.circe::circe-core::0.14.16"
docs: none
scaladoc: https://www.javadoc.io/doc/io.circe/circe-core_3/0.14.16
readme:
…30-line README head…
---
Scala API inspection — via cellar:
  cellar deps io.circe:circe-core_3:0.14.16
  cellar get-external io.circe:circe-core_3:0.14.16 <symbol>
full data: index4s get circe/circe --json
```

**Field paths** (npm-view style) extract one value for scripting — much cheaper than parsing the card:

```
$ index4s get circe stars
2542
$ index4s get circe default
{"groupId":"io.circe","artifactId":"circe-core_3","version":"0.14.16","name":"circe-core"}
```

Common fields: `org repo stars forks issues license category latest default docLinks scaladoc readme` — an invalid name prints the full list (self-introspecting). Full table with JSON shapes: [references/commands.md](references/commands.md).

**The default-artifact caveat**: `default:` is one sensible entry point, not necessarily the one the user needs — multi-artifact projects ship several (test frameworks, plugins, multi-module: `get typelevel/weaver-test` defaults to `weaver-cats-core_3`, but `weaver-cats`, `weaver-framework`, … exist). When the choice matters, list artifacts (`--artifacts`, or `latestRefs` in `--json`) and pick deliberately. `search --rank --json` rows are weaker still: their `defaultCoordinate` falls back lexicographically when the project declares nothing (live: zio/zio-json → `zio-json-golden_3`, a *test tool*). Take build coordinates from `get` output, never from rank rows.

`--json` gives the full record for jq (never truncated). Other flags: `--readme head|full|off`, `--section <title>`, `--artifact-version V`, `--scala`, `--web`. Full reference: [references/commands.md](references/commands.md).

## Cellar handoff — API signatures are cellar's job

When you need **members, signatures, or source of a resolved coordinate** (`cats.Monad`'s methods, "what does fs2's Stream.compile return"), that is symbol inspection — cellar's job, and index4s has no command for it:

```
cellar get-external io.circe:circe-core_3:0.14.16 io.circe.Parser
cellar deps org.typelevel:weaver-cats-core_3:0.13.0
```

| Need | Tool |
|---|---|
| Discover / compare libraries, topics, freshness | `index4s search [--rank]` |
| Coordinates, versions, platforms, build snippets, docs links, readme | `index4s get` |
| Signatures, members, symbol search, source, dependency tree of a resolved coordinate | `cellar` |

Don't reverse the roles: no index4s command does symbol lookup (you'd flail over markdown cards), and `cellar search` searches symbols within one coordinate, not the ecosystem. The seam is the coordinate: cellar needs explicit `g:a:v` (no `::`, platform-suffixed like `circe-core_3`, sbt plugins `_2.12_1.0`). The card's `default:` line and cellar hint block are already in that form — copy verbatim. One exception: the did-you-mean stderr line on a coordinate miss is a *pointer*, not a coordinate (it can lack the platform suffix) — re-run `get <org/repo>` and take coordinates from the new card. Full menu and rules: [references/cellar.md](references/cellar.md).

## Coordinate verification discipline (the weaver incident)

Real incident: an agent needed `weaver-cats` for Scala Native. Training-data truth was `com.disneystreaming:weaver-cats` — correct for years. But weaver moved to `org.typelevel`, and the **native0.5 line was never published under the old namespace**. The dependency "existed", the memory was "right", and the build still failed. The rules that fall out:

1. **Never resolve Scala coordinates from memory, cached snippets, or another tool's docs.** Resolve through `get`/`search` — Scaladex reflects the current namespace and platform availability. Do this even (especially) when you're "sure".
2. **A 404 means "not under THESE coordinates", not "does not exist".** Before reporting a library unavailable, suspect: moved groupId, platform-suffix mismatch (`_3` published but not `_native0.5_3`), or version never published for that platform.
3. **The user's claim is a primary source.** When they say "weaver supports Native", re-resolve coordinates instead of reporting absence. A user link like `index.scala-lang.org/{org}/{repo}` encodes the canonical project — `get {org}/{repo}` lists what's actually published.
4. `get` performs this recovery itself when a coordinate genuinely misses:

```
$ index4s get com.example:weaver-cats_3
# stderr: com.example:weaver-cats_3 not found — did you mean org.typelevel:weaver-cats:0.13.0? (project typelevel/weaver-test)
```

Take the hint and re-resolve — the suggestion is a pointer, not a cellar-usable coordinate.

5. **Exit 0 is not proof of freshness.** Scaladex keeps old namespaces indexed forever — `get com.disneystreaming:weaver-cats_3` still resolves, to a stale 2024 mirror: native0.4 only, stars frozen at move-time. The ghost announces itself — its README's first line says "THIS PROJECT HAS BEEN MOVED". Cross-check whenever `latest:` is years old or the org differs from the one the user named. Expanded walkthrough: [references/troubleshooting.md](references/troubleshooting.md).

## Decision tree

```
Need a Scala library (or which/whether/best)?
├─ Don't know which ───────────────→ index4s search <topic> --rank [--target …] [--sort fresh]
├─ Know org/repo or coordinate ────→ index4s get <identifier>          # snippets, platforms, docs
├─ Need one value in a script ─────→ index4s get <id> <field>          # e.g. `get circe stars`
├─ Need API of that dependency ────→ cellar (coordinate from get output) — see references/cellar.md
└─ Coordinate failed / user says
   "but it exists!" ──────────────→ index4s get <name> / get <org/repo>; re-resolve, never report
                                      absence from memory — see references/troubleshooting.md
```

Non-Scala (Java/Maven) dependencies: index4s doesn't index them — resolve via Maven Central directly.

## References (read on demand)

- [references/commands.md](references/commands.md) — every flag for `search`/`get` from `--help`, field-path table, `--json` shapes with real samples. Read when composing a specific command.
- [references/cellar.md](references/cellar.md) — cellar command menu, coordinate rules, division-of-labor deep dive. Read before any cellar delegation.
- [references/troubleshooting.md](references/troubleshooting.md) — coordinate-miss recovery walkthrough, exit-2 ambiguity flow, degradation semantics, stale-namespace traps. Read when something fails or looks stale.
