# index4s command reference

Full flag and output reference for `index4s 0.1.0-SNAPSHOT`, transcribed from `--help` and live runs (2026-09-01). Numbers in samples drift — Scaladex data moves; treat the *shapes* as stable.

## TOC

- [Global options](#global-options)
- [search](#search)
- [get](#get)
- [Field paths](#field-paths)
- [--json shapes](#json-shapes)
- [Environment](#environment)

## Global options

Present on **both subcommands — and only there**. The top-level command accepts just `--help`/`--version`; a global flag before the subcommand is a hard parse error:

```
$ index4s --json get circe
Unexpected option: --json          # exit 1
```

**Rule: global flags go AFTER the subcommand.** Write `index4s get circe --json`, never `index4s --json get circe`.

| Flag | Meaning |
|---|---|
| `--json` | Machine-readable JSON output (full fidelity) |
| `--limit <N>` | Maximum results; search pages internally (default 20) |
| `--no-color` | Disable ANSI colors (implicit when piped or `NO_COLOR` set) |
| `--no-hints` | Suppress the cellar hint block (and install note) |
| `--timeout <seconds>` | HTTP timeout (default 15) |
| `--base-url <uri>` | Scaladex base URL |
| `--verbose` | Diagnostics on stderr |
| `--version` / `--help` | Version / help |

## search

```
index4s search [--topic <topic>]... [--target <jvm|js|native|sbt>] [--scala <version>]
               [--sjs <version>] [--native <version>] [--sbt <version>] [--cli]
               [--rank] [--sort <stars|fresh>] <query>
```

`<query>` passes Scaladex query syntax through (`* AND topics:json`, `organization:x`, AND-composition). Flags:

| Flag | Meaning |
|---|---|
| `--topic <topic>` | Filter by topic; repeatable, ANDed (`--topic json --topic http`) |
| `--target` | `jvm` (default) · `js` · `native` · `sbt` — changes candidate set + required version params |
| `--scala <version>` | Scala binary version: `3` (default), `2.13`, `2.12` |
| `--sjs / --native / --sbt <version>` | Platform versions for the respective `--target` (defaulted) |
| `--cli` | Only Scala CLI-friendly artifacts |
| `--rank` | Enriched ranking table — fan-out per candidate for stars/license/category/release date |
| `--sort stars\|fresh` | `--rank` sort order (default `stars`); fresh sorts by release age, missing dates last. No effect without `--rank` — silently ignored |

**Thin output** (default) — Scaladex relevance order, org/repo + artifact names:

```
$ index4s search json --limit 8
8 projects (Scaladex relevance, target jvm · scala 3) — use --rank for stars/freshness comparison

  zio/zio-json                         artifacts: zio-json, zio-json-docs, zio-json-golden…
  playframework/play-json              artifacts: play-functional, play-json, play-json-joda
  tethys-json/tethys                   artifacts: tethys, tethys-cats, tethys-circe…
  json4s/json4s                        artifacts: json4s, json4s-ast, json4s-core…
  scalapb-json/scalapb-circe           artifacts: scalapb-circe, scalapb-circe-macros
  circe/circe                          artifacts: circe-core, circe-extras, circe-generic…
  stephennancekivell/scalatest-json    artifacts: scalatest-argonaut, scalatest-circe, …
  scalapb-json/scalapb-playjson        artifacts: scalapb-playjson, scalapb-playjson-macros
```

Miss → exit 1, stderr: `no results for 'x' (target jvm · scala 3)`.

**`--rank` output** — comparison table + footer (freshness legend, next-step hints):

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

`—` cells = signal unavailable (missing data or degraded fetch; stderr notice explains; row survives). `--rank` on an empty seed = empty table, exit 0. Enrichment retries 503s twice; persistent failure degrades to `—` rather than dropping rows or failing.

**Platform scoping** — same query, different target:

```
$ index4s search weaver --target native --limit 5
4 projects (Scaladex relevance, target native · scala 3 · native 0.5) — …

  typelevel/weaver-test          artifacts: weaver-cats, weaver-cats-core, weaver-core…
  typelevel/toolkit              artifacts: toolkit, toolkit-test
  typelevel/otel4s               artifacts: otel4s-core, otel4s-core-common, otel4s-core-logs…
  typelevel/scalacheck-effect    artifacts: scalacheck-effect, scalacheck-effect-munit
```

## get

```
index4s get [--readme <head|full|off>] [--section <title>] [--artifacts]
            [--artifact-version <version>] [--web] [--scala <version>]
            [--target <jvm|js|native|sbt>] <identifier> [<field>...]
```

**Identifier forms** (tried in this order of specificity):

1. `org/repo` — exact project: `get typelevel/weaver-test`
2. `group:artifact[:version]` — `get io.circe:circe-core_3:0.14.16`; `::` accepted and normalized (`io.circe::circe-core`); `latest` allowed as version
3. bare name — autocomplete resolve: confident → proceeds (silent); ambiguous → exit 2

**Card output** (readme elided here; default = first 30 lines in a fenced block):

```
$ index4s get typelevel/weaver-test
typelevel/weaver-test | — | category: — | ★ 85 | forks 15 | issues 37
A test framework that runs everything in parallel.  — https://typelevel.org/weaver-test/
topics: —
latest: 0.13.0 (2026-06) — scala 2.12, 2.13, 3 · sjs1 · native0.5   [54 versions]
default: org.typelevel:weaver-cats-core_3:0.13.0
  sbt       "org.typelevel" %% "weaver-cats-core" % "0.13.0"
  mill      ivy"org.typelevel::weaver-cats-core:0.13.0"
  scala-cli dep"org.typelevel::weaver-cats-core::0.13.0"
docs: none
scaladoc: https://www.javadoc.io/doc/org.typelevel/weaver-cats-core_3/0.13.0
readme:
…
---
Scala API inspection — via cellar:
  cellar deps org.typelevel:weaver-cats-core_3:0.13.0
  cellar get-external org.typelevel:weaver-cats-core_3:0.13.0 <symbol>
full data: index4s get typelevel/weaver-test --json
```

Read the identity line as `{org/repo} | {license | —} | category: {c | —} | ★ {stars} | forks {n} | issues {n}`. `—` = Scaladex has no value. The `docs:` line lists the project's `documentationLinks` (frequently none — not an error); `scaladoc:` is the resolved scaladoc URL (custom pattern → doc link → javadoc.io fallback). The `latest:` line lists every platform the newest release supports — check it before assuming Scala.js/Native support.

Flags beyond the globals:

| Flag | Meaning |
|---|---|
| `--readme head\|full\|off` | README rendering (default `head` = 30 lines) |
| `--section <title>` | Print a single README section by title |
| `--artifacts` | Include the full artifact ref list (large — circe has ~3455 refs; opt-in only) |
| `--artifact-version <version>` | Pin the card to a specific artifact version |
| `--web` | Print the Scaladex page URL (no browser is spawned) |
| `--scala <version>` | Influence default-artifact selection |
| `--target <jvm\|js\|native\|sbt>` | Platform for default-artifact selection (default jvm). Platform-suffixed picks render single-colon fully-qualified snippets (`"io.circe" % "circe-core_native0.5_3" % "v"`) — `%%`/`::` cannot encode platforms |
| `[<field>...]` | Positional field paths — see below |

**Ambiguity (exit 2)** — ranked candidates table on **stdout**, one-line notice on stderr:

```
$ index4s get json
# stdout (exit: 2):
'json' is ambiguous — 5 candidates (pin with org/repo):

| # | project | description |
|---|---------|-------------|
| 1 | spray/spray-json | A lightweight, clean and simple JSON implementation in Scala |
| 2 | zio/zio-json | Fast, secure JSON library with tight ZIO integration. |
| 3 | playframework/play-json | The Play JSON library |
| 4 | jrudolph/json-lenses | A library to query and update JSON data in Scala. |
| 5 | json4s/json4s | JSON library |
# stderr: ambiguous 'json' — 5 candidates on stdout (exit 2)
```

With `--json`: `{"error":"ambiguous","query":"json","candidates":[{"organization":...,"repository":...,"description":...}]}` — parse and pick programmatically.

Recover: pick the candidate matching user intent, re-run `get <org/repo>`. Beware: a genuinely-absent bare name can also produce exit 2 with *fuzzy* autocomplete candidates — if none look right, treat as not-found and `search` instead. See [troubleshooting.md](troubleshooting.md).

**Coordinate miss with suggestion (exit 1)**:

```
$ index4s get com.example:weaver-cats_3
# stdout: (empty)   exit: 1
# stderr:
com.example:weaver-cats_3 not found — did you mean org.typelevel:weaver-cats:0.13.0? (project typelevel/weaver-test)
```

The tool re-resolves the artifactId and suggests the live namespace. Take the suggestion; verify with `get typelevel/weaver-test`.

## Field paths

npm-view-style positional fields print one bare value (scalars plain, structures as JSON):

```
$ index4s get circe stars
2542
$ index4s get circe latest
{"version":"0.14.16","releaseDate":"2026-06","platforms":"scala 2.12, 2.13, 3 · sjs1 · native0.5","totalVersions":108}
$ index4s get circe default
{"groupId":"io.circe","artifactId":"circe-core_3","version":"0.14.16","name":"circe-core"}
$ index4s get circe topics
["generic-derivation","json","scala"]
$ index4s get circe cellar
{"detected":true,"deps":"cellar deps io.circe:circe-core_3:0.14.16","getExternal":"cellar get-external io.circe:circe-core_3:0.14.16 <symbol>"}
```

Valid fields: `org`, `repo`, `stars`, `forks`, `issues`, `license`, `category`, `description`, `homepage`, `topics`, `latest`, `default`, `docLinks`, `scaladoc`, `readme`, `project`, `latestRefs`, `artifactDetails`, `pinned`, `cellar`, `resolution`, `suggestions`. An unknown field exits 1 and prints exactly this list — self-introspecting, like `gh --json`.

Scalar fields to know: `stars`/`forks`/`issues` (Int), `latest.version` lives in `latest` (JSON). `default` is the resolved default artifact — see the multi-artifact caveat in SKILL.md before trusting it blindly.

## --json shapes

**`get --json`** — top-level keys:

```
org, repo, stars, forks, issues, license, category, description, homepage, topics,
latest, default, docLinks, scaladoc, readme, project, latestRefs, artifactDetails,
pinned, cellar, resolution, suggestions
```

Sample (trimmed; `readme` is the FULL text, `latestRefs` the full platform/version ref array):

```json
{
  "org": "circe", "repo": "circe", "stars": 2542, "forks": 546, "issues": 128,
  "license": "Apache-2.0", "category": "json",
  "description": "Yet another JSON library for Scala",
  "homepage": "http://circe.io/circe/",
  "topics": ["generic-derivation", "json", "scala"],
  "latest": {"version": "0.14.16", "releaseDate": "2026-06",
             "platforms": "scala 2.12, 2.13, 3 · sjs1 · native0.5", "totalVersions": 108},
  "default": {"groupId": "io.circe", "artifactId": "circe-core_3", "version": "0.14.16", "name": "circe-core"},
  "scaladoc": "https://www.javadoc.io/doc/io.circe/circe-core_3/0.14.16",
  "readme": "…full text…"
}
```

jq recipes:

```
index4s get circe --json | jq '.default | "\(.groupId):\(.artifactId):\(.version)"'
index4s get circe --json | jq '[.latestRefs[].artifact]'          # all published artifactIds
index4s search json --rank --json | jq -r '.results[] | "\(.id) \(.tier)"'
```

**`search --rank --json`** — envelope + enriched rows:

```json
{
  "meta": {"query": "json", "target": "jvm", "sort": "stars", "count": 3},
  "results": [
    {"id": "zio/zio-json", "stars": 431, "latestVersion": "0.9.2",
     "releaseDate": "2026-04-22T12:18:34Z", "tier": "active",
     "license": "Apache-2.0", "category": "json",
     "platformSummary": "scala 2.12, 2.13, 3 · sjs1 · native0.5",
     "defaultCoordinate": "dev.zio:zio-json-golden_3:0.9.2",
     "degraded": []}
  ]
}
```

`tier` ∈ `active` (✓) · `sleepy` (💤) · `dead` (💀) · `null` (unknown — no release date). `degraded` lists fields that failed enrichment. Dates differ by surface: `get --json`'s `latest.releaseDate` is `YYYY-MM`; rank rows carry full ISO-8601. `defaultCoordinate` prefers the project's declared default, then the repo-named artifact, then a deterministic fallback — multi-artifact caveat still applies, confirm via `get` before writing it into a build.

## Environment

- `NO_COLOR` — disable ANSI (also implicit when stdout is piped)
- `INDEX4S_BASE_URL` — Scaladex base URL (same as `--base-url`)
