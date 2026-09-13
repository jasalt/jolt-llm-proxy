# 260913A — Use Ebb for request-stream lifecycles

- **Status:** Implemented
- **Date:** 2026-09-13

## Decision

Use Ebb to own each upstream SSE or pooled-WebSocket request lifetime. Transport
acquisition returns an Ebb task yielding a resource with a rendezvous-backed
producer, `abort!`, and `release!`. `llm-proxy.flow/with-event-source` owns
acquisition, consumption, cancellation, and exactly-once release.

The rendezvous is zero-capacity: every upstream event waits for the consuming
request task. This preserves event ordering and losslessness without applying
latest-value or lossy backpressure operators to model output. Blocking HTTP,
SSE line reads, and WebSocket frame reads run with `m/via m/blk`.

The Ring Chez response remains a `core.async` channel at the sole adapter
boundary. `flow/task->channel` bridges an Ebb task to that channel; a failed
write caused by browser disconnect cancels the Ebb task, which aborts and
releases the owned upstream resource. `proxy.clj` no longer starts request
`async/thread` workers.

Codex translation, validation, and collector reducers remain ordinary Clojure.
The proxy uses a small local pull-to-callback adapter while those deterministic
collectors consume events; it does not recreate a general callback source
contract.

For pooled WebSockets, release commits continuation and returns `keep=true`
only after the consumer reports a completed response with valid response
metadata. Setup failures, parse/stream failures, incomplete responses, and
client cancellation release `keep=false`.

The dashboard is separate: Glimmer/Datastar observes only the bounded,
redacted `inspect/snapshot` ratom. Its core-owned Ebb sampler is telemetry;
it is not an application-state graph and never carries credentials, prompts,
raw session IDs, or upstream payloads.

## Consequences

- Client disconnects have one explicit path to upstream cancellation.
- Transport resource cleanup no longer depends on proxy handlers remembering a
  separate finalizer.
- `core.async` remains only where Ring channel bodies require it.
- Real credentialed SSE and WebSocket continuation verification remains an
  external manual check; sandbox tests use deterministic fakes and preserve
  error redaction boundaries.
