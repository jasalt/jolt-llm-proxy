# 260819J — Propose an optional Datastar SSE operator dashboard

- **Date:** 2026-08-19
- **Status:** Implemented (Glimmer + `glimmer-datastar` dashboard)

## Context

The pinned `jolt-lang/glimmer-datastar` integration provides a server-driven
browser UI:

- the browser-side vendored Datastar bundle patches a selected HTML fragment
  without a page reload;
- one Glimmer ratom contains the latest bounded `llm-proxy.inspect/snapshot`;
- an Ebb sampler refreshes that presentation value once per second;
- `jolt.datastar.core/wrap-datastar` tracks the ratom during rendering, emits
  changed `datastar-patch-elements` output, and suppresses unchanged HTML.

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

## Decision

Add an explicitly enabled, **read-only operator dashboard** using Datastar SSE
and the redacted inspection model now implemented by `llm-proxy.inspect`.

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
- Keep dashboard code in `llm-proxy.dashboard`, distinct from the outbound
  `llm-proxy.transport.sse` namespace.
- Wrap only the dashboard sub-handler with `jolt.datastar.core/wrap-datastar`;
  the OpenAI API and its SSE responses never pass through Datastar middleware.
- Serve the page and its SSE subscription from the same `/_llm-proxy/` route,
  distinguished by Datastar's query convention. The old explicit
  `/_llm-proxy/events` endpoint is intentionally removed.
- Keep the vendored browser asset embedded with `resources/`; normal `serve`
  does not install dashboard routes and no CDN is contacted at runtime.
- Vendor a fixed, reviewed Datastar browser bundle in project resources and
  embed it only in a dashboard-enabled standalone build. Do not load a CDN
  script at operator-page runtime.

### State and update model

- The proxy lifecycle/runtime remains the authoritative owner of state.
  Do not replace its system map or WebSocket pool with UI-owned ratoms.
- The initial runtime-owned statistics are request count, current pool summary,
  process start time, listener configuration, and feature/authentication state.
  Error totals, SSE fallback count, token-refresh outcomes, and latency metrics
  remain future additions.
- Project those statistics through the pure redaction function into one
  immutable snapshot held by a Glimmer ratom. Render code observes only this
  presentation cell, never the runtime, token store, or WebSocket pool.
- A core-owned Ebb task samples the safe snapshot once per second. Its explicit
  cancellation function is run during normal shutdown and startup rollback.
- `glimmer-datastar` owns per-tab SSE channels, reactive subscriptions,
  re-rendering, unchanged-HTML suppression, and disconnect cleanup.
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
  the vendored module script). Datastar v1.0.3 compiles its declarative
  attributes with `Function` when the CSP nonce path is not active, so the local
  dashboard CSP includes the narrow `script-src 'unsafe-eval'` exception; it does
  not permit inline scripts or remote script origins.
- Dashboard opening must not make a failed/unauthenticated Codex backend appear
  healthy: distinguish proxy listener health from backend authentication and
  latest-upstream-result state.

## Dependency adoption

The migration pins `jolt-lang/glimmer-datastar` at an exact commit. Its
transitive Glimmer revision and v1.0 wire protocol are compatible with the
vendored official Datastar v1.0.3 browser bundle:

```text
resources/public/js/datastar.js
SHA-256: 5d6b7794a50a83d82da962aec5e382f5ae83ac7afbc751f903f7a9c6bd433c65
```

The bundle is loaded only by the explicit dashboard route and is embedded in
standalone builds through `:jolt/build`; no CDN request is made at runtime.
Middleware behavior, wire formatting, unchanged-render suppression, disconnect
cleanup, and resource loading are covered by deterministic tests on the pinned
Jolt release.

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
3. Tests prove a dashboard SSE subscription receives a redacted initial
   fragment and route traffic is excluded from the request metric. The
   Glimmer/Datastar subscription unregisters after the response channel closes.
4. Tests prove no snapshot, rendered HTML, event, or error response contains
   tokens, API keys, raw session IDs, prompts, or upstream body data.
5. The dashboard’s optional wrapper does not change existing Chat/Responses SSE
   headers, event payloads, streaming behavior, or API-key guard behavior.
6. The ordinary deterministic suite remains credential-free, network-free, and
   browser-free.
