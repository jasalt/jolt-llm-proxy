# Architecture review — jolt-codex-sub-proxy

## Overall state

Feature-complete Clojure-on-Chez (Jolt) port of the Go proxy, **verified end-to-end against the live ChatGPT subscription backend**. ~2,274 LOC across 10 namespaces, with a 6-namespace deterministic test suite (≈62 assertions), CI on Jolt v0.7.16, and an actively-maintained review doc (`docs/SOL-REVIEW.md`) tracking remediation. Clean git tree, 19 commits of incremental hardening.

## Layering (clean, one-directional)

```
codex.core      — sole lifecycle owner: system atom, transactional start!/idempotent stop!, CLI dispatch
 ├─ codex.cli   — login (browser PKCE + device code), logout, usage, info
 └─ codex.proxy — Ring boundary: routes, API-key guard, session normalization, transport selection, handlers (~230 LOC)
     ├─ codex.collect           — chat + responses event collectors (streaming/non-streaming), usage/error formatting
     ├─ codex.transport.sse     — outbound HTTP/SSE, one 401-retry with forced refresh; {:read :finalize} interface
     ├─ codex.transport.ws   — pooled WS event-source lifecycle, delta continuation acquire
     │    └─ codex.ws        — hand-rolled RFC 6455 client over jolt.http.tls; per-session pool
     │                        (128 cap, LRU eviction, 5m idle TTL, 55m max age, serialized acquire/release)
     ├─ codex.continuation   — previous_response_id delta requests, lenient prefix match
     ├─ codex.translate      — /v1/chat/completions ↔ /v1/responses
     └─ codex.auth           — token store atom, refresh, atomic 0600 cred-file writes
```

Notable design decisions that are in good shape:
- **Explicit runtime injection** (`make-handler` closes over `{:store :pool :session-id :api-key}`) — no namespace owns app state; tests inject fakes at clear seams (`run-server`, `stop-server`, `store`).
- **Uniform event-source interface** (`{:read :finalize}`) across SSE and WS transports, with guaranteed release-on-failure.
- **Platform hazards documented and encoded**: PMap vs tagged-table accessor split (R1), base64url shim gap, nREPL/pkill traps — with the gotchas even repeated in ns docstrings where they bite.

## SOL-REVIEW remediation status

- **P0 (security/correctness): all 4 done** — credential file protection, pool atomicity, release-on-failure, stream-failure reporting.
- **P1: all 7 done** (tests+CI, session bounding, OAuth callback, runtime ownership, WS frame hardening incl. 64 MiB aggregate cap, **proxy split**, **request validation** — the last two were completed 2026-08-19, see below).
- **P2:** item 13 done; item 12 done for startup output (startup prints a hashed session id; `info!` redaction + structured logging remain); items 14–15 partially done; item 16 done.

## Loose ends worth addressing

All four items below were addressed on 2026-08-19 (commits `b89cea9`…):

1. **Repo-root scratch files** — ✅ `ws_handshake.clj` moved to `examples/`;
   `throwtest.clj` and `test_continuation.clj` deleted (superseded by
   `test/codex/continuation_test.clj`); README/TODO references updated.
2. **TODO.md duality** — ✅ converted to a historical implementation plan with
   an explicit header pointing at `SOL-REVIEW.md`/`K3-REVIEW-2.md` as the
   current backlog; stale status markers (Phase 8 "README update pending",
   the never-checked-in `test_e2e.clj`) reconciled.
3. **`codex.core` prints raw session-id** — ✅ startup now prints an 8-char
   SHA-256 prefix (`codex.id/short-hash`) instead of the raw id.
4. **Remaining proxy split + validation depth** — ✅ both completed:
   - **Split:** collectors extracted to `codex.collect` (chat + responses,
     streaming and non-streaming) with replay-based tests; `codex.proxy`
     (544 → ~230 LOC) is now the pure Ring boundary (routes, guard, transport
     selection, handlers).
   - **Validation depth:** both translate entry points parse with string keys
     and validate before any keyword conversion (boolean `stream`, bounded
     vector-of-map `tools`/`functions` ≤128 with validated `function`
     sub-objects, content-part shapes, numeric `temperature`, 32-level nesting
     cap). `chat-to-responses` drops unknown top-level fields;
     `prepare-responses` keywordizes a known key set and passes unknown keys
     through upstream with string keys; input items stay keywordized
     (regression-tested) so delta continuation keeps working.

Test suite grew from 16 tests / 40 assertions to 25 tests / 78 assertions;
CI namespace load list updated for `codex.collect`.

**Bottom line:** the architecture has converged well — single lifecycle owner, injected dependencies, clean transport abstraction, hardened security posture. The review's loose ends are all closed; what remains is the P2 polish tail: `info!` redaction with a structured logger (SOL-REVIEW #12), the error-taxonomy/diagnostics pass (#14), and remaining minor style cleanup (#15). No structural rework is warranted.