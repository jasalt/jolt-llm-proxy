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
codex.core
  -> codex.proxy
       -> codex.schema / codex.translate
       -> codex.transport.sse / codex.transport.ws
       -> codex.collect
```

Responsibilities are:

- `codex.proxy`: exact Ring routes, API-key guard, session normalization,
  prompt-cache-key application, transport selection, and HTTP response maps;
- `codex.translate`: Chat-to-Responses transformation, Responses normalization,
  defaults, recognized-field handling, and continuation-compatible input shape;
- `codex.collect`: reduce common upstream events into Chat or Responses output,
  for streaming and nonstreaming clients;
- `codex.transport.*`: produce events and own transport cleanup.

Retain the simple `cond` router while the API has four exact routes. Do not add
Reitit solely to replace these comparisons. Retain maps and functions as the
internal event-source interface rather than introducing a protocol with no
multiple implementation requirement beyond the two transport maps.

## Consequences

- Collectors can be replay-tested from event vectors without network access.
- SSE and WS transports feed identical event maps to endpoint logic.
- Unknown Chat fields can be deliberately dropped while Responses compatibility
  policy remains localized in translation.
- Reitit and a broader middleware stack remain appropriate if the API grows to
  parameterized routes, route data, versioned endpoints, or many route-specific
  policies.

## Evidence

The extraction reduced `codex.proxy` from roughly 544 lines to about 230 lines.
Replay tests live in `test/codex/collect_test.clj`; transport parser tests live
in `test/codex/transport_sse_test.clj`.
