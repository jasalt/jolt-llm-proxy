# Project conventions

Use REPL driven development to work on a live process via `brepl '<form>'` writing idiomatic Clojure patterns on Jolt (non-JVM) platform.

Use `brepl balance <file>` after write/edit on `.clj` files.

Read [./AGENTS.local.md](./AGENTS.local.md) for local environment info.

# Jolt language instructions

Main documentation: https://github.com/jolt-lang/jolt

Read jolt llms.txt [../../jolt/jolt/llms.txt](../../jolt/jolt/llms.txt) or online https://github.com/jolt-lang/jolt/blob/main/llms.txt

Before making changes, read Jolt's repository guidance https://raw.githubusercontent.com/jolt-lang/jolt/main/llms.txt.

For Jolt language, API, tooling, interop, library, specification, and design
documentation, consult:
- [./jolt-lang.github.io/llms.txt](./jolt-lang.github.io/llms.txt) or online at https://jolt-lang.net/llms.txt
- https://jolt-lang.net/docs/

Follow specification and RFC links from the documentation when relevant; the
top-level indexes may not enumerate every detailed page directly.

Before implementing common functionality, check Jolt's existing libraries and examples:
- https://jolt-lang.net/docs/libraries.html
- https://github.com/jolt-lang/examples/

When documentation and implementation disagree, prefer current Jolt source,
tests, and known-divergences.edn for implemented behaviour. Treat RFCs primarily
as design documentation unless current implementation confirms them.

Also consider searching Jolt project chat archive for recent info, example search query "jvm performance" (space becomes `.20`):
https://clojurians.zulipchat.com/#narrow/channel/180378-slack-archive/topic/jolt/search/jvm.20performance

Do not assume JVM implementation semantics merely because Jolt follows Clojure
language semantics.

## Reporting errors

If Jolt language upstream project, or it's libraries or documentation include gaps or bugs that need diagnosing or workarounds during process, file them in JOLT-ISSUES.md as reproducible tickets that can be forwarded to maintainers.

Jolt sources are available at [~/dev/jail/jolt/jolt](~/dev/jail/jolt/jolt), see [~/dev/jail/jolt/AGENTS.md](~/dev/jail/jolt/AGENTS.md).

### Clojure Covergence issues

If un-documented issues come up that can be seen as covergence of Clojure, file them separately convirming their validity using `bb` Babashka Clojure dialect REPL comparisons with Jolt REPL.

### Non JVM Quirks

Suprising differences in platform behavior that are not documented well should be also documented in JOLT-GOTCHAS.md
