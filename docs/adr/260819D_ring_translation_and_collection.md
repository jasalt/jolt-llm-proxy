# 260819D — Separate the Ring boundary, translation, and event collection

- **Date:** 2026-08-19
- **Status:** Accepted and implemented

## Context

The initial proxy namespace combined routing, authentication, upstream HTTP,
SSE parsing, WebSocket lifecycle, request translation, and streaming/nonstreaming
response collection. This made transport cleanup and endpoint behavior hard to
test independently.

## Decision

Keep a one-directional boundary:

```text
llm-proxy.core
  -> llm-proxy.proxy
       -> llm-proxy.schema / codex.translate
       -> llm-proxy.transport.sse / llm-proxy.transport.ws
       -> codex.collect
```

Responsibilities are:

- `llm-proxy.proxy`: exact Ring routes, API-key guard, session normalization,
  prompt-cache-key application, transport selection, and HTTP response maps;
- `codex.translate`: Chat-to-Responses transformation, Responses normalization,
  defaults, recognized-field handling, and continuation-compatible input shape;
- `codex.collect`: reduce common upstream events into Chat or Responses output,
  for streaming and nonstreaming clients;
- `llm-proxy.transport.*`: produce events and own transport cleanup.

Use `askonomm/ruuter` for the Ring route table. Routes remain plain data and
Ruuter's best-match semantics leave room for future path parameters without
reintroducing routing conditionals into `llm-proxy.proxy`. Keep API-key
protection in the route response functions so routing and authorization remain
separate concerns. Retain maps and functions as the internal event-source
interface rather than introducing a protocol with no multiple implementation
requirement beyond the two transport maps.

## Consequences

- Collectors can be replay-tested from event vectors without network access.
- SSE and WS transports feed identical event maps to endpoint logic.
- Unknown Chat fields can be deliberately dropped while Responses compatibility
  policy remains localized in translation.
- Ruuter is intentionally a small routing dependency; a broader middleware
  stack remains appropriate only if the API grows to many route-specific
  policies. Reitit remains an alternative for a substantially larger route
  graph, but is not needed alongside Ruuter for this service.

## Evidence

The extraction reduced `llm-proxy.proxy` from roughly 544 lines to about 230 lines.
Replay tests live in `test/codex/collect_test.clj`; transport parser tests live
in `test/codex/transport_sse_test.clj`.
