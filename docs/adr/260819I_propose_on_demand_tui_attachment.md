# 260819I — Propose an on-demand operator TUI attachment model

- **Date:** 2026-08-19
- **Status:** Proposed — inspection boundary implemented; no TUI client yet

## Context

`jolt-lang/glimmer-tui` provides a reactive ncurses terminal backend. Its
example demonstrates that a UI can derive its display from reactive state and
safely receive background-thread updates: Glimmer schedules rendering on the
terminal-owning thread.

`llm-proxy.core` is a long-running proxy process and can already opt in to a
loopback nREPL server through:

```console
jolt -m llm-proxy.core serve --nrepl
```

The runtime owns useful operational state: server configuration, authenticated
status, the bounded WebSocket pool, connection age/activity, and continuation
presence. It deliberately also owns sensitive and non-serializable values:
credential atoms, tokens, API-key configuration, raw session IDs, TLS streams,
handler closures, and stop functions.

## Problem

An operator needs an occasional terminal view of safe proxy health and pool
statistics without turning the proxy into a browser application or permanently
adding an ncurses dependency to server deployments.

Running a Glimmer TUI *inside* the proxy process is not an attachment solution.
`ui/run` owns and blocks that process's controlling terminal, which is normally
absent or unrelated to the operator's terminal for a daemon. Evaluating it
through nREPL would draw at the server, not at the attached client.

## Proposed TUI decision

If implemented, provide an **external, short-lived TUI client** that owns the
operator's terminal and reads a deliberately narrow inspection API over the
already opt-in loopback nREPL transport:

```text
proxy:    jolt -m llm-proxy.core serve --nrepl
                              │
                              │ loopback nREPL
                              ▼
operator: jolt -M:tui -m llm-proxy.tui attach
                              │
                              └─ Glimmer TUI owns this terminal
```

### Inspection boundary

The implemented public function:

```clojure
(llm-proxy.inspect/snapshot runtime)
```

produces a small immutable, serialization-safe snapshot rather than exposing
`@llm-proxy.core/system` directly. Its current shape includes:

```clojure
{:started-at ...
 :uptime-seconds ...
 :proxy-port 8080
 :api-key-auth-enabled true
 :nrepl-enabled true
 :codex {:authenticated true
         :token-expiry-ms ...
         :ws-pool {:entries 4
                   :busy 1
                   :idle 3
                   :with-continuation 2}}}
```

It must not return access tokens, refresh tokens, API keys, raw session IDs,
prompts, request/response bodies, authorization headers, TLS streams, handler
closures, or stop functions. If a session must be identified diagnostically,
use the existing short hash rather than its raw value.

### TUI packaging and behavior

- Put TUI source on a separate `tui/` source root and add Glimmer TUI through a
  `:tui` alias, rather than adding ncurses/Glimmer to ordinary server startup or
  standalone proxy builds.
- Use `jolt-lang/glimmer-tui` pinned to a tested commit when implementation
  begins.
- Connect through the nREPL client supplied by `jolt-lang/nrepl`; query only
  the snapshot function.
- Poll at a modest interval (for example, one second) and update a Glimmer
  reactive atom. Do not add another streaming/control protocol initially.
- Start with compact pages or tabs: overview, pool/session summary, and help.
  Glimmer TUI currently has no scrolling viewport, so unbounded session tables
  are unsuitable.
- Keep the client read-only. Operational mutations belong in explicit future
  administrative commands with separately reviewed authorization semantics.

### Metrics scope

The implemented snapshot derives current pool state from the runtime-owned pool
atom and exposes the cumulative proxy request count. SSE-fallback counts,
token-refresh counts, upstream failure counts, and latency distributions are
not currently available and should be added only as explicit runtime-owned
counters if a future view requires them. Do not infer history from
socket/connection objects.

## Security and operational constraints

- `serve --nrepl` remains the explicit prerequisite; normal `serve` must not
  open nREPL.
- Jolt nREPL's default middleware permits arbitrary evaluation. It is therefore
  development/debug access only, loopback-only, and must never be exposed via a
  reverse proxy or untrusted network.
- Do not implement remote activation of nREPL for an already-running proxy.
  That would require a second privileged control path and increase attack
  surface without improving the local operator workflow.
- The TUI must display connection failure clearly and degrade to no data rather
  than caching potentially stale sensitive information.

## Consequences

### Benefits

- Operators receive an on-demand local terminal dashboard without changing
  normal proxy serving or terminal ownership.
- Glimmer remains outside the core runtime and optional for deployment.
- The safe inspection API becomes reusable by future CLI diagnostics, tests, or
  another local UI.
- Existing nREPL support supplies a tested local attach transport.

### Costs and risks

- nREPL is powerful and remains unsuitable for shared or remote environments.
- The inspection boundary must be reviewed as carefully as logging/redaction.
- A separate process introduces attach/reconnect/polling behavior to test.
- Glimmer TUI has current layout limitations, requiring a deliberately compact
  interface.

## Acceptance criteria for a future implementation

1. The base proxy dependency graph and `serve` command do not require Glimmer
   TUI or ncurses.
2. `serve --nrepl` plus the TUI alias can attach locally and display a snapshot.
3. The snapshot contains no secrets or raw session identifiers; tests assert
   this explicitly.
4. TUI connection loss produces a visible local error and does not affect proxy
   serving.
5. Proxy shutdown and nREPL shutdown do not leave the TUI process or terminal
   in an unusable state.
6. The ordinary deterministic test suite remains credential-free and does not
   require an interactive terminal.
