Use `brepl balance <file>` after write/edit on `.clj` files.

Use REPL driven development to work on a live process via `brepl eval` writing idiomatic Clojure patterns on Jolt (non-JVM) platform.

Main documentation: https://github.com/jolt-lang/jolt

Read jolt llms.txt https://github.com/jolt-lang/jolt/blob/main/llms.txt

Original Golang repository this rewrite is based on: https://github.com/jasalt/chatgpt-openai-api-adapter


# Reporting errors

If Jolt language upstream project, or it's libraries or documentation include gaps or bugs that need diagnosing or workarounds during process, file them in JOLT-ISSUES.md as reproducible tickets that can be forwarded to maintainers.

## Clojure Covergence issues

If un-documented issues come up that can be seen as covergence of Clojure, file them separately convirming their validity using `bb` Babashka Clojure dialect REPL comparisons with Jolt REPL.

## Non JVM Quirks

Suprising differences in platform behavior that are not documented well should be also documented in JOLT-GOTCHAS.md
