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
   Runtime maps retain `:now-ms`, `:http-post`, and, where required, HTTP GET
   equivalents. A WebSocket pool captures its own `:now-ms` with its sessions
   atom and lock. These values remain stable across idle-expiry futures and
   allow multiple application instances to use independent test dependencies.
2. **Dynamic utilities for synchronous leaf code only.**
   `llm-proxy.time/*now-ms*` and `llm-proxy.id/*random-bytes*` provide the
   production wall clock and `SecureRandom` bytes. Tests may override them
   using lexical `binding`; production leaf code calls `time/now-ms` and
   `id/random-hex`.

Do not use `with-redefs` as routine dependency injection. It remains suitable
only for narrowly scoped legacy tests where no asynchronous work escapes.

No log event may include tokens, prompts, authorization headers, raw session
identifiers, OAuth codes, or arbitrary upstream response bodies.

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
