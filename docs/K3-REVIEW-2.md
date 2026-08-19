# Architecture review — jolt-codex-sub-proxy

## Overall state

Feature-complete Clojure-on-Chez (Jolt) port of the Go proxy, **verified end-to-end against the live ChatGPT subscription backend**. ~2,274 LOC across 10 namespaces, with a 6-namespace deterministic test suite (≈62 assertions), CI on Jolt v0.7.16, and an actively-maintained review doc (`docs/SOL-REVIEW.md`) tracking remediation. Clean git tree, 19 commits of incremental hardening.

## Layering (clean, one-directional)

```
codex.core      — sole lifecycle owner: system atom, transactional start!/idempotent stop!, CLI dispatch
 ├─ codex.cli   — login (browser PKCE + device code), logout, usage, info
 └─ codex.proxy — ring handler, routing, API-key guard, session normalization, SSE/chat collectors (544 LOC, largest)
     ├─ codex.transport.sse  — outbound HTTP/SSE, one 401-retry with forced refresh; {:read :finalize} interface
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
- **P1: 5 of 7 done** (tests+CI, session bounding, OAuth callback, runtime ownership, WS frame hardening incl. 64 MiB aggregate cap).
- **2 in progress:**
  1. **Split `codex.proxy`** — transport namespaces extracted; collectors (`consume-chat`, `stream-chat`, `collect-response`, ~400 LOC) still live in proxy.clj.
  2. **Request validation** — top-level/model/messages validated; string-key parsing and deeper per-field limits remain.
- **P2 partial:** item 12 (stop printing identifiers — `start!` still prints the raw session-id; `info!` needs a redacted default); items 14–16 (error taxonomy, duplication, TODO.md reconciliation) pending.

## Loose ends worth addressing

1. **Repo-root scratch files**: `throwtest.clj` (4-line scratch), `test_continuation.clj`, `ws_handshake.clj`. README references `ws_handshake.clj` as the proven handshake recipe — move it to `examples/` or `dev/` and delete the other two.
2. **TODO.md duality** — flagged in the review: it's simultaneously a stale implementation plan (Phase 1 "IN PROGRESS" vs. table "done") and a backlog. Convert to historical doc or prune.
3. **`codex.core` prints raw session-id** at startup (SOL-REVIEW #12) — trivial fix.
4. **Remaining proxy split + validation depth** — the two in-progress P1 items; both have clear acceptance criteria in SOL-REVIEW.

**Bottom line:** the architecture has converged well — single lifecycle owner, injected dependencies, clean transport abstraction, hardened security posture. What remains is finishing the proxy decomposition, deepening input validation, a redaction/logging pass, and doc hygiene. No structural rework is warranted.