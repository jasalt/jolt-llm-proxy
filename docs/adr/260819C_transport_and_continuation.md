# 260819C — Unify transports behind an event-source contract

- **Date:** 2026-08-19
- **Status:** Accepted and implemented

## Context

The private Codex backend can be consumed over HTTP/SSE or WebSocket. WebSocket
sessions permit `previous_response_id` delta continuation and improved prompt
cache reuse, while SSE is required as a simple fallback. Endpoint handlers
should not implement transport parsing or connection ownership.

## Decision

Both transports expose the same small map interface:

```clojure
{:read     (fn [emit] ...)
 :finalize (fn [result-meta] ...)}
```

- `llm-proxy.transport.sse` owns outbound HTTP, one forced-refresh retry after a
  401, SSE parsing, and response-body cleanup.
- `llm-proxy.transport.ws` owns acquisition, request initialization, continuation
  selection, event adaptation, and release/finalization.
- `codex.ws` owns the RFC 6455 handshake, framing, message assembly, connection
  state, and bounded per-session pool.
- `codex.continuation` remains a data transformation: it normalizes prior
  output, checks conversation prefixes, and returns either a delta request or
  the unchanged full request.

A WebSocket setup failure releases its acquisition before the Ring layer may
fall back to SSE. A successful terminal result alone preserves the connection
and continuation state.

The pool is capped at 128 cached sessions, uses idle LRU eviction, a five-minute
idle TTL, and a 55-minute maximum connection age. Busy sessions are never
shared; capacity pressure uses an uncached one-off connection rather than
unbounded state growth.

WebSocket parsing enforces upgrade-header, frame, control-frame, fragmentation,
and 64 MiB aggregate message limits.

## Consequences

- Endpoint collection is transport-independent.
- Continuation behavior is independently testable and does not depend on socket
  objects.
- Resource release has a single explicit handoff point.
- The hand-written WebSocket implementation is justified by Jolt's available
  outbound TLS surface and the private upstream protocol; JVM WebSocket clients
  are not usable substitutes.
