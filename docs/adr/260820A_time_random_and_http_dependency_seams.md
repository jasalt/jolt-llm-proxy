# 260820A — Use explicit runtime seams and dynamic leaf utilities

- **Date:** 2026-08-20
- **Status:** Accepted and implemented

## Context

The proxy needs deterministic tests for clocks, random identifiers, and HTTP
calls. Passing every leaf utility through the complete application runtime
would add noise. Conversely, `with-redefs` changes root Vars and is unsafe as
a primary strategy when server threads, `future`, or `async/thread` work can
outlive a test scope.

## Decision

Use two deliberately different seams:

1. **Explicit dependencies for owned, asynchronous, or external resources.**
   Runtime maps retain `:now-ms` and `:http-post`. A WebSocket pool captures
   its own `:now-ms` with its sessions atom and lock. These values remain
   stable across idle-expiry futures and allow multiple application instances
   to use independent test dependencies. No HTTP GET seam is currently
   required; the CLI usage fetch calls `http/get` directly.
2. **Dynamic utilities for synchronous leaf code only.**
   `llm-proxy.time/*now-ms*` and `llm-proxy.id/*random-bytes*` provide the
   production wall clock and `SecureRandom` bytes. Tests may override them
   using lexical `binding`; production leaf code calls `time/now-ms` and
   `id/random-hex`.

Do not use `with-redefs` as routine dependency injection. It remains suitable
only for narrowly scoped, fully synchronous tests where no asynchronous work
escapes and no concurrent test can observe the root-Var redefinition. Document
the case-specific reason, synchronous boundary, and condition requiring a
replacement seam in a comment immediately above every permitted use. Prefer an
explicit dependency or lexical `binding` when either is practical.

## Consequences

- Synchronous helpers are concise and deterministic under `binding`.
- Runtime, pool, and HTTP behavior remains explicit and robust across thread
  boundaries.
- Tests can prevent live network access by injecting HTTP functions.
- Dynamic bindings must not be relied on by spawned futures or request worker
  threads; capture an explicit function in the owning resource instead.

## Evidence

`test/codex/auth_test.clj` injects a clock and HTTP POST for token refresh, and
`test/llm_proxy/proxy_test.clj` injects the runtime clock for model timestamps.
`test/codex/ws_test.clj` binds the dynamic clock and random-byte utilities for
deterministic synchronous leaf-code tests.
