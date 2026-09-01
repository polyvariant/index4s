# index4s skill evals — T9b results (2026-09-01)

## Outcome: 4/4 with-skill PASS; baseline comparison on eval 2

| Eval | Prompt essence | Expected behavior | Result |
|---|---|---|---|
| 1 | pick JSON lib, actively maintained, add to build.sbt | --rank (not thin/web), get card, snippet from card not memory | ✅ circe via --rank (fresh ✓ vs json4s 2y8m 💀), exact snippet written, coordinates from index4s |
| 2 | fix com.disneystreaming:weaver-cats_3 not-found (user insists native works) | recognize moved namespace, user claim primary, resolve via index4s | ✅ org.typelevel:0.13.0 native0.5 line found via index4s, build fixed (%%), upstream README cross-checked |
| 2-baseline | same, NO skill | — | Outcome also achieved (Maven Central spelunking + sbt verify) but via manual multi-source research; skill run got it via one command + teaches the reusable discipline |
| 3 | cats.Monad.flatten signature + siblings | index4s for coordinates ONLY, cellar for symbols | ✅ get cats → org.typelevel:cats-core_3:2.13.0 → cellar get-external (flatten def) + list-external (type class list) — division of labor exact |
| 4 | script stars+latest for 4 libs, freshness verdict | field paths / --rank --json machine surface, tier reading | ✅ get <name> stars/latest per project + tier cross-validation vs --rank; json4s dead, tethys active — correct |

## Findings (→ F4 follow-ups)
1. commands.md should document: global flags must come AFTER the subcommand (`get x --no-color` works, `--no-color get x` doesn't)
2. cellar.md staleness found live by eval 3: cellar 0.1.0-M12 rejects `--hide-inherited` on `list-external`; truncates at 50 without `--limit` — update reference
3. Eval agents on this machine: sandbox denied writes outside workspace — transcripts staged in workspace eval dirs + tool-output; artifacts preserved

## Deferred
- Full description-optimization loop (skill-creator run_loop, needs claude CLI trigger testing at scale) → runs before final packaging (post-F4, user sign-off)
- Trigger evals (6 queries) authored in evals/evals.json — to be executed in the optimization loop

Transcripts (verified locations 2026-09-01, post-F4): workspace `eval1/` + `eval2b/` at repo root; `eval2b/` + `eval4/` under `/home/majk/.local/share/opencode/tool-output/`. **eval2/ and eval3/ transcripts were not preserved** — those runs' outputs live only in the eval-session reports, and the 4/4 PASS claim for those two rows rests on the session reports rather than staged artifacts (findings are recorded in the table above).
