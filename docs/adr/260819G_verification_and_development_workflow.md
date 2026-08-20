# 260819G — Use deterministic tests, CI, and Jolt REPL verification

- **Date:** 2026-08-19
- **Status:** Accepted and implemented

## Context

The original implementation relied on scratch scripts and printed acceptance
messages. Protocol, credential, translation, and lifecycle regressions require a
command that fails reliably without live credentials or network access. Jolt
also has reader, cache, and host-behavior differences that ordinary JVM tooling
does not verify.

## Decision

Maintain tests under `test/codex/` and run them through a small explicit
`clojure.test` runner:

```console
jolt -M:test -m llm-proxy.test-runner
```

The runner throws when any test fails. CI installs the pinned tested Jolt
release, loads production namespaces, and runs the deterministic suite. Live
subscription verification remains separate from the default suite.

Use a persistent Jolt nREPL for development and reload namespaces explicitly.
For an already-running proxy, `jolt -m llm-proxy.core serve --nrepl` starts the
same loopback nREPL middleware with the application and binds its stop function
to `llm-proxy.core/stop!`; `JOLT_NREPL_PORT` overrides its default port 7888.
After every `.clj` edit, run `brepl balance <file>`. Keep Jolt-specific
reproductions in the dedicated issues/gotchas/convergence documents rather than
embedding workarounds silently in architecture prose.

Tests use injected server, store, HTTP, source, and lifecycle seams where
available. Collector tests replay events; continuation and translation tests
operate on data.

## Consequences

- The default test command needs neither credentials nor upstream availability.
- Scratch acceptance files are unnecessary; the proven manual WebSocket
  handshake remains under `examples/` as an integration recipe.
- JVM-only runners, coverage agents, and static-analysis assumptions are not
  treated as authoritative for Jolt.
- The suite currently has 59 tests and 239 assertions, including deterministic
  WebSocket frame/handshake, classified-error/redaction, and dashboard coverage.
