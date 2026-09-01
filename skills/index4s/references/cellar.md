# cellar — command menu & division of labor

index4s and [cellar](https://github.com/VirtusLab/cellar) (VirtusLab) are complementary halves of Scala dependency work. index4s answers *"which library, which coordinates"*; cellar answers *"what's inside those coordinates"*. When cellar is installed, route all symbol/API inspection there — index4s has no commands for it, and cellar's output is strictly better at that job (it reads compiled classpath/tasty data, not indexes).

If cellar is absent: `cs install --contrib cellar` (coursier). index4s never fails on a missing cellar — the hint block degrades to commands plus an install note on stderr.

## TOC

- [Division of labor](#division-of-labor)
- [cellar commands](#cellar-commands)
- [Coordinate rules](#coordinate-rules)
- [The natural flow](#the-natural-flow)

## Division of labor

| Task | Tool | Why |
|---|---|---|
| Discover libraries by topic/keyword | `index4s search` | Scaladex index; cellar has no Scaladex search |
| Compare candidates (stars/freshness/platforms) | `index4s search --rank` | Enriched table from live Scaladex |
| Resolve current coordinates & latest version | `index4s get` | Scaladex is the source of truth for namespaces |
| Build snippets (sbt/mill/scala-cli) | `index4s get` | Rendered from resolved coordinates |
| Docs/scaladoc/readme links | `index4s get` | Project metadata |
| Method/function **signatures** | `cellar get-external` | Reads compiled artifacts |
| Package/module **members** | `cellar list-external` | — |
| **Symbol search** inside an artifact | `cellar search-external` | — |
| **Source code** of a symbol | `cellar get-source` | — |
| **Dependency tree** of a coordinate | `cellar deps` | Resolves the actual POM tree |

Anti-rules, both directions:

- **Don't** use index4s for symbol lookups — "what's the signature of `cats.Monad.flatten`" has no index4s answer. You'd flail over markdown cards.
- **Don't** use cellar for discovery — `cellar search` searches *symbols within a coordinate*, not the Scala ecosystem. "Which JSON library" is not a cellar question.
- **Don't** re-derive coordinates for cellar from memory — take them from `index4s get` output, which is already fully-suffixed (cellar requires that; see below).

## cellar commands

All external commands take an explicit coordinate; `latest` is allowed as the version:

```
cellar get-external <g:a:v> <fqn>        # signature of one symbol
                                         #   cellar get-external io.circe:circe-core_3:0.14.16 io.circe.Parser
cellar list-external <g:a:v> <package>   # members of a package
                                         #   cellar list-external org.typelevel:cats-core_3:2.13.0 cats.syntax
cellar search-external <g:a:v> <query>   # symbol search inside one artifact
                                         #   cellar search-external io.circe:circe-core_3:0.14.16 Decoder
cellar get-source <g:a:v> <fqn>          # source of one symbol
                                         #   cellar get-source io.circe:circe-core_3:0.14.16 io.circe.Parser
cellar deps <g:a:v>                      # dependency tree (run as-is, no symbol needed)
                                         #   cellar deps io.circe:circe-core_3:0.14.16
```

Flags (verified against cellar 0.1.0-M12 — scope matters, cellar rejects misplaced flags with "Unexpected option"):

- `-l/--limit N` — list/search commands; default 50. When results clip, cellar prints `Note: results truncated at 50. Use --limit to increase.` on stderr — read that as "pass `-l`", not as an error.
- `--hide-inherited` / `--group-inherited` — **get/get-external ONLY**; list-external/search-external reject them (exit 1).
- `-r` (extra repository) — resolution, applies everywhere.

cellar's output contract mirrors index4s: stdout = markdown payload, stderr = diagnostics.

Some artifacts' sources jars don't match cellar's expected internal paths, so `get-source` fails with `Source file not found in JAR` even though the artifact is fine — weaver 0.13.0 is such a case. Fall back to `get-external` for the signature and the project's GitHub for full source.

For **project-local** code (the workspace you're standing in), cellar's project-aware commands (`cellar list`, `cellar get`, … without `-external`) auto-detect the build and use its classpath — prefer those when inspecting the user's own dependency tree.

## Coordinate rules

cellar external commands are strict about coordinates, and agents reliably get these wrong — this is why index4s emits coordinates in exactly cellar's dialect:

1. **Explicit `group:artifact:version`** — no `%%`, no `::` shorthand. `io.circe:circe-core_3:0.14.16`, not `io.circe::circe-core`.
2. **Platform suffix included in the artifactId**: `_3` (JVM/Scala 3), `_sjs1_3`, `_native0.5_3`. The binary-version suffix is part of the name on Maven Central.
3. **sbt plugins** carry the sbt binary suffix: `org.scala-native:sbt-scala-native_2.12_1.0:0.5.12`.
4. **`latest`** is accepted as version (cellar resolves it) — but prefer the concrete version index4s already resolved, so both tools operate on the same bytes.
5. Compiler-plugin artifacts may need the *full* Scala version suffix (`_3.3.8`) — pass through the exact artifactId when the user provides one.

`index4s get` output (card `default:` line, `--json` `default` field, and the cellar hint block) is already in this form — copy it verbatim.

## The natural flow

```
index4s search json --rank              # 1. compare candidates
index4s get circe/circe                 # 2. resolve coordinates + snippets + docs
cellar deps io.circe:circe-core_3:0.14.16               # 3. inspect with cellar,
cellar get-external io.circe:circe-core_3:0.14.16 <sym> #    coordinate from step 2
```

Steps 1–2 are index4s; step 3 is cellar; the seam is the fully-suffixed coordinate printed by step 2. Every `get` card ends with ready-to-run cellar lines (`deps` runs as-is; `get-external` has a `<symbol>` placeholder to fill).
