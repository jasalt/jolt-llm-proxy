# 260819J — Propose an optional Datastar SSE operator dashboard

- **Date:** 2026-08-19
- **Status:** Implemented (initial local-only dashboard)

## Context

The Jolt Glimmer/Datastar example demonstrates a server-driven browser UI:

- a server-side Glimmer reactive atom is the rendered-state source;
- `jolt.datastar.core/wrap-datastar` detects a Datastar SSE request, subscribes
  it to state changes, and emits `datastar-patch-elements` SSE events;
- a browser-side vendored Datastar bundle patches a selected HTML fragment
  without a page reload;
- ordinary action requests can patch browser signals, while SSE carries
  subsequent rendered-fragment updates.

The proxy already uses Ring Chez and SSE, but its existing SSE is an **outbound
Codex transport** and its public inbound endpoints are OpenAI-compatible API
routes. An operator dashboard is a separate browser-facing administrative
surface, not a reuse of `llm-proxy.transport.sse`.

The runtime has potentially useful operator information, but `@llm-proxy.core/system`
contains credentials, raw pool/session state, TLS streams, closures, and other
non-serializable internals. ADR 260819I separately proposes a redacted,
immutable inspection snapshot for an on-demand nREPL TUI.

## Problem

A browser dashboard could offer a more accessible local or reverse-proxy-fronted
operator view than a terminal TUI, with automatic refresh over SSE. It must not
turn the OpenAI API proxy into a general browser application, expose secrets,
or weaken the existing local-only threat model.

## Proposed decision

If implemented, add an explicitly enabled, **read-only operator dashboard**
using Datastar SSE and the same redacted inspection model proposed by ADR
260819I.

```text
proxy runtime/statistics atom
            │
            ▼
redacted llm-proxy.inspect/dashboard-snapshot
            │
            ▼
Glimmer/Datastar rendering state ── SSE patches ──> browser fragment
```

The dashboard is not an nREPL client and does not need nREPL to be enabled.
It is a second optional operator interface over HTTP/SSE.

### Routing and packaging

- Install dashboard routes only through an explicit startup option/flag, for
  example `serve --dashboard`; ordinary `serve` remains API-only.
- Reserve a non-OpenAI path prefix such as `/_llm-proxy/` for the page, static
  Datastar asset, and its SSE subscription. Do not place an administrative UI
  at `/v1/*`.
- Keep dashboard code in a separate namespace such as
  `llm-proxy.dashboard.datastar`, distinct from the outbound
  `llm-proxy.transport.sse` namespace.
- Use the existing Ruuter route table to mount the optional routes; the
  Datastar wrapper should wrap only the dashboard branch or be demonstrated not
  to alter OpenAI/SSE streaming route semantics.
- Make Glimmer, Datastar, Hiccup, resources, and ncurses-free browser assets
  optional dependencies/source roots or a dedicated build profile. A normal
  proxy deployment and normal `serve` must not require them.
- Vendor a fixed, reviewed Datastar browser bundle in project resources and
  embed it only in a dashboard-enabled standalone build. Do not load a CDN
  script at operator-page runtime.

### State and update model

- The proxy lifecycle/runtime remains the authoritative owner of state.
  Do not replace its system map or WebSocket pool with UI-owned ratoms.
- Add explicit runtime-owned, bounded statistics only when needed: request and
  error totals, SSE fallback count, token-refresh outcomes, current pool
  summary, and process start time are suitable examples.
- Project those statistics through a pure redaction function into an immutable
  snapshot. A Glimmer ratom may hold that projection solely to trigger UI
  renders.
- Coalesce updates and rate-limit rendering (the example uses a 15 ms wrapper
  rate limit; an operator dashboard should use a deliberately modest interval,
  such as 250 ms to one second). Per-tab SSE channels must be bounded and
  subscription/watch cleanup must occur when the browser disconnects.
- Render a compact overview first: process uptime, listener state,
  authenticated/not-authenticated state, API-key-enabled boolean, request/error
  counters, and pooled/busy/idle WebSocket counts. Avoid unbounded tables;
  use capped recent/error summaries or pagination when needed.

### Read-only policy

The first version has no browser actions that mutate the proxy. It may use
Datastar's initial signals and SSE element patches, but it must not accept
client-provided signals as authority for server state. Restart, logout, token
refresh, pool eviction, or any other mutation require separate future
administrative commands with their own authorization review.

## Security and exposure constraints

- The pinned Ring adapter is loopback-only today, so the initial dashboard is
  local-only. This is a product/security boundary, not an incomplete remote UI.
- A browser `EventSource` cannot attach the proxy's existing bearer API-key
  header. Do **not** work around this by placing the API key or a reusable
  dashboard token in a URL/query parameter, HTML, JavaScript, browser storage,
  logs, or SSE event data.
- If external access is later needed, terminate TLS and perform browser-suitable
  authentication at a dedicated reverse proxy or introduce a separately
  reviewed cookie/session design. Reuse of the OpenAI bearer-key guard is not
  assumed sufficient for browser SSE.
- The dashboard must expose no access/refresh tokens, API keys, raw session
  identifiers, prompts, model inputs/outputs, authorization headers, raw
  upstream errors, connection objects, or credential claims. Session
  correlation, if required, uses a short hash only.
- Render dynamic values through escaping Hiccup/data APIs, never raw generated
  HTML from upstream/client data. Send appropriate restrictive response headers
  (at minimum content type, frame embedding policy, and a CSP compatible with
  the vendored module script). Datastar v1.0.2 compiles its declarative
  attributes with `Function`, so the local dashboard CSP must include the
  narrow `script-src 'unsafe-eval'` exception; it does not permit inline
  scripts or remote script origins.
- Dashboard opening must not make a failed/unauthenticated Codex backend appear
  healthy: distinguish proxy listener health from backend authentication and
  latest-upstream-result state.

## Adoption gate

The example consumes `jolt-lang/datastar` as a workspace-local dependency and
therefore does not provide a suitable pinned Jolt runtime dependency for this
project. The initial implementation resolves that adoption gate by using the
existing Ring SSE primitives and vendoring the official Datastar v1.0.2 browser
bundle from jsDelivr's GitHub source:

```text
resources/public/js/datastar.js
SHA-256: 2837d87acf6ee0ba8e4e63765926c25a98d63883b02f88be194a86b81d3fd24a
```

The bundle is loaded only by the explicit dashboard route and is embedded in
standalone builds through `:jolt/build`; no CDN request is made at runtime.
The wrapper behavior, SSE formatting, disconnect behavior, and resource loading
are covered by the deterministic tests on the target Jolt release. A future
adoption of `jolt-lang/datastar` still requires a release/tag and pinned commit
SHA.

## Consequences

### Benefits

- A local browser gains live operational visibility without polling/reloading a
  full page.
- The inspection projection can be shared with the proposed TUI and future CLI
  diagnostics.
- Datastar avoids a separate SPA build pipeline and keeps UI rendering as
  Clojure data.
- The dashboard remains optional and separate from API transport code.

### Costs and risks

- It creates a browser-facing administrative surface and requires stronger
  HTML/SSE security discipline than the JSON-only proxy.
- Per-browser SSE subscriptions need explicit resource and backpressure tests.
- Browser authentication is different from the existing API-key model.
- The current upstream Datastar distribution model blocks a normal pinned
  dependency adoption.

## Implementation acceptance criteria

1. Base `serve`, proxy tests, and standalone API builds work without dashboard
   dependencies or resources.
2. Dashboard routes are absent unless explicitly enabled and cannot shadow
   `/v1/*` API routes.
3. Tests prove a dashboard SSE subscription receives a redacted fragment update
   after a statistics change and is released after client disconnect.
4. Tests prove no snapshot, rendered HTML, event, or error response contains
   tokens, API keys, raw session IDs, prompts, or upstream body data.
5. The dashboard’s optional wrapper does not change existing Chat/Responses SSE
   headers, event payloads, streaming behavior, or API-key guard behavior.
6. The ordinary deterministic suite remains credential-free, network-free, and
   browser-free.
