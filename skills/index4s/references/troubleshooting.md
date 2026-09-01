# index4s troubleshooting — coordinate discipline, ambiguity, degradation

The theme of most index4s trouble is the same: **Scala coordinates are less stable than your memory of them.** Namespaces move, platforms lag, indexes keep stale ghosts. This file expands the SKILL.md discipline section into concrete recovery procedures, with real transcripts (numbers drift; procedures don't).

## TOC

- [The weaver incident, in full](#the-weaver-incident-in-full)
- [Exit 2: ambiguity recovery](#exit-2-ambiguity-recovery)
- [Exit 1: not-found and suggestions](#exit-1-not-found-and-suggestions)
- [Stale namespaces: exit 0 is not freshness](#stale-namespaces-exit-0-is-not-freshness)
- [The default-artifact caveat](#the-default-artifact-caveat)
- [Degradation semantics (— cells)](#degradation-semantics--cells)
- [Network & timeouts](#network--timeouts)

## The weaver incident, in full

An agent needed the weaver test framework on Scala Native. Every cached instinct said `com.disneystreaming:weaver-cats` — correct for years of training data. Reality: weaver moved to `org.typelevel`, and **the native0.5 line was never published under the old namespace**. Result: "not found" for a library that absolutely existed, an agent confidently reporting weaver-incompatible-with-Native (false), and a broken build either way.

Three general rules fall out:

1. **Resolve coordinates through index4s, always, even when you're sure.** `get typelevel/weaver-test` shows the live truth — `latest: 0.13.0 (2026-06) — scala 2.12, 2.13, 3 · sjs1 · native0.5` — in one call.
2. **A 404 is a claim about coordinates, not existence.** Before concluding "unavailable", suspect, in order: (a) moved groupId/renamed org, (b) platform-suffix mismatch — `_3` published but not `_native0.5_3`, or the platform line lives under a *different* groupId, (c) version skipped for that platform/binary combo.
3. **User claims are primary sources.** If the user says "weaver supports Native" or links `index.scala-lang.org/typelevel/weaver-test`, treat that as ground truth about *existence* and use index4s to find the *coordinates*. Never report absence when the user asserts presence — re-resolve instead.

## Exit 2: ambiguity recovery

A bare name matched multiple projects. The ranked candidates table prints to **stdout** (pipeline-friendly; machine-readable with `--json`); stderr carries a one-line notice:

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

With `--json` the stdout payload is machine-readable candidates: `{"error":"ambiguous","query":"json","candidates":[{"organization":...,"repository":...,"description":...}]}`.

Recovery procedure:

1. If the user's context disambiguates (ZIO stack → `zio/zio-json`, Play app → `playframework/play-json`), re-run `get <org/repo>` directly.
2. If candidates are genuinely competing, either ask the user or compare with `search <name> --rank`.
3. Pin with `org/repo` (or explicit `g:a:v`) from then on — bare names are only for interactive convenience.

**Fuzzy-miss trap**: a *nonexistent* bare name can also exit 2 — Scaladex autocomplete is fuzzy and may return junk candidates for garbage input:

```
$ index4s get not-a-real-project-xyz
# exit: 2 — candidates: lightbend/paradox, scala-garden/lightbend-emoji, … (all wrong)
```

If no candidate plausibly matches the name, treat it as not-found: fall back to `search <name>`, and if that exits 1 too, the library doesn't exist under any name you can find.

## Exit 1: not-found and suggestions

Explicit `g:a[:v]` misses trigger automatic re-resolution — index4s searches the artifactId and, if it lives under a different namespace, says so on stderr:

```
$ index4s get com.example:weaver-cats_3
# exit: 1
# stderr: com.example:weaver-cats_3 not found — did you mean org.typelevel:weaver-cats:0.13.0? (project typelevel/weaver-test)
```

(In `--json`, the same data is in `suggestions[]`.) Take the suggestion: `get typelevel/weaver-test`, confirm the artifact/platform line, use its snippets. A miss with **no** suggestion = the artifactId genuinely isn't in the index — check spelling, then `search`.

Plain `search` misses are quieter:

```
$ index4s search xyzzynotarealibrary-qq
# stdout: (empty)   exit: 1
# stderr: no results for 'xyzzynotarealibrary-qq' (target jvm · scala 3)
```

Note the target in the message — a JVM-target miss for a Native-only library is not absence. Retry with `--target native` before concluding anything.

## Stale namespaces: exit 0 is not freshness

The nastiest failure mode is the successful one. Scaladex keeps **old namespaces indexed forever**, so a moved project resolves under both namespaces — and the ghost is stale:

```
$ index4s get com.disneystreaming:weaver-cats_3
disneystreaming/weaver-test | — | category: testing | ★ 443 | forks 46 | issues 50
A test framework that runs everything in parallel.  — https://disneystreaming.github.io/weaver-test/
topics: —
latest: 0.8.4 (2024-01) — scala 2.12, 2.13, 3 · sjs1 · native0.4   [56 versions]
default: com.disneystreaming:weaver-cats_3:0.8.4
…
```

Exit 0 — resolves fine, but to the 2024 line: native0.4 only, no native0.5, stale snippets. Note the ghost even looks *healthier* than the canonical repo: ★ 443 vs ★ 85 on `typelevel/weaver-test`, because stars froze at move-time while GitHub's live count migrated. Compare the canonical home — `typelevel/weaver-test`, `latest: 0.13.0 (2026-06) — scala 2.12, 2.13, 3 · sjs1 · native0.5`. Defenses:

- Check the identity line's `org/repo` against the project's real home (github URL, homepage, or the org the user mentioned). `disneystreaming/weaver-test` vs typelevel's docs site is the tell.
- A card whose `latest:` date is years old while the project is known-active is a ghost until proven otherwise — verify with `search <name> --rank` (the ghost and the real repo both appear; compare latest versions).
- When the user names an org (typelevel, zio, akka/apache pekko…), prefer `get <their-org>/<repo>` first.

## The default-artifact caveat

`default:` on the card is a sensible entry point, not a promise it's the artifact you need:

- Multi-artifact projects have several valid entry points: `get typelevel/weaver-test` defaults to `weaver-cats-core_3`, but `weaver-cats`, `weaver-framework`, `weaver-scalacheck` exist for different setups.
- `get` handles this well — it picks the project's declared default, else the repo-named artifact (`get zio/zio-json default` → `zio-json_3`, correct). The weak surface is `search --rank --json`: a row's `defaultCoordinate` can fall back to a lexicographic sibling when the project declares no default and no repo-named artifact matches (live: zio/zio-json → `dev.zio:zio-json-golden_3:0.9.2` — golden is a *test* tool, not the JSON codec). Take build coordinates from `get` output, not from rank rows.

When the wrong artifact would materially change the build (test frameworks, plugins, multi-module ecosystems), enumerate and choose deliberately:

```
index4s get zio/zio-json --json | jq -r '[.latestRefs[].artifact] | unique | .[]'
```

or `get <org/repo> --artifacts` for the full ref table.

## Degradation semantics (— cells)

A `—` cell (table) or `—` field (card) means *signal unavailable* — Scaladex lacks the value or an enrichment fetch failed after retries. The project itself is real; the row/card survives with what's known. A stderr notice names what degraded. In `--json` the field is `null` and `--rank` rows carry a `degraded: []` array listing failed fields.

Do not retry degraded fetches immediately — the notice means retries already happened (2× backoff on 5xx). `null` stars in rank JSON sort as 0 for the table but stay `null` in the data (faithful to the source).

## Network & timeouts

- Default HTTP timeout 15s (`--timeout` to raise for slow links); Scaladex overload (503) is retried twice with backoff before degrading.
- `--base-url` / `INDEX4S_BASE_URL` redirect all calls (useful for proxies/fixtures, and for checking what the tool does against a pinned snapshot).
- If everything exits 1 with network-flavored stderr, check connectivity before concluding libraries vanished — the messages distinguish transport errors from not-found.
