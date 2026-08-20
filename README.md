# jolt-llm-proxy

A generic Jolt-based LLM proxy architecture with the current Codex/ChatGPT
Subscription backend as its supported backend.

> **Unofficial/private API:** this project talks to ChatGPT Subscription backend
> endpoints that are not a stable public OpenAI API. They may change without
> notice. Review applicable service terms and do not expose this proxy as a
> public multi-user service.

A Clojure-on-Chez (Jolt, non-JVM) port of the
[`chatgpt-openai-api-adapter`](https://github.com/jasalt/chatgpt-openai-api-adapter)
Go proxy. It authenticates to the ChatGPT Subscription (Codex) backend and
exposes an OpenAI-compatible HTTP API (`/v1/responses`, `/v1/chat/completions`)
for generic LLM clients, with multi-turn WebSocket delta continuation
(`previous_response_id`) so prompt-cache `cached` bytes grow with conversation
history.

> **Status:** feature-complete and verified end-to-end against the live ChatGPT
> subscription backend: `/v1/responses` + `/v1/chat/completions` (streaming and
> non-streaming), WebSocket delta continuation, SSE fallback, token refresh,
> API-key guard, and the `login`/`logout`/`usage`/`info` CLI. Implemented
> architectural decisions are recorded under [`docs/adr/`](./docs/adr/).

## Prerequisites

- Jolt v0.7.16 (the tested version)
- Linux x86_64 with glibc and system OpenSSL 3
- Git/network access on first run so Jolt can resolve the exact dependency SHAs
  pinned in `deps.edn`

Verify the source and deterministic test suite before using live credentials:

```bash
jolt -e '(require (quote llm-proxy.core))'
jolt -M:test -m llm-proxy.test-runner
```

## Usage

The commands below work identically through Jolt or the standalone binary;
substitute `./target/jolt-llm-proxy` for `jolt -m llm-proxy.core` after building.

```bash
# Log in (browser PKCE on localhost:1455, or headless device code):
jolt -m llm-proxy.core login
# Or after building: ./target/jolt-llm-proxy login

# Serve an OpenAI-compatible API (env: JOLT_LLM_PROXY_ADDR, JOLT_LLM_PROXY_API_KEY):
JOLT_LLM_PROXY_ADDR=127.0.0.1:8090 JOLT_LLM_PROXY_API_KEY=my-key jolt -m llm-proxy.core serve
# Or: JOLT_LLM_PROXY_ADDR=127.0.0.1:8090 JOLT_LLM_PROXY_API_KEY=my-key ./target/jolt-llm-proxy serve

# Development/debug only: start the proxy and a loopback nREPL for CIDER,
# Calva, or Cursive. Defaults to 127.0.0.1:7888; set JOLT_NREPL_PORT to change it.
jolt -m llm-proxy.core serve --nrepl

# Optional local read-only browser dashboard with live Datastar SSE updates.
# It is disabled by default and is available at /_llm-proxy/.
jolt -m llm-proxy.core serve --dashboard

curl -H 'Authorization: Bearer my-key' http://127.0.0.1:8090/v1/models
curl -H 'Authorization: Bearer my-key' -H 'Content-Type: application/json' \
     -H 'X-Session-Id: conv-1' \
     -d '{"model":"gpt-5.4-mini","input":"hello","stream":true}' \
     http://127.0.0.1:8090/v1/responses

# Account status:
jolt -m llm-proxy.core usage   # weekly Codex allowance
jolt -m llm-proxy.core info     # credentials + JWT claims
jolt -m llm-proxy.core logout
jolt -m llm-proxy.core licenses # third-party license notice
```

The dashboard is loopback-only, read-only, and must not be exposed through a
reverse proxy without a separately reviewed browser authentication design. It
vendors Datastar v1.0.2 under `resources/public/js/datastar.js`; normal proxy
startup does not enable the dashboard.

`JOLT_LLM_PROXY_ADDR` accepts only `127.0.0.1:<port>` or
`localhost:<port>` because the current Ring adapter is loopback-only. With no
`JOLT_LLM_PROXY_API_KEY`, every local process can spend the logged-in account's
allowance. Set a strong key on shared machines. Never put the proxy directly on
an untrusted network; use an authenticating TLS reverse proxy if remote access
is unavoidable.

Requests carrying an `X-Session-Id` (or `X-Prompt-Cache-Key`) header are routed
over pooled WebSocket connections with multi-turn delta continuation; requests
without one fall back to plain SSE. Credentials live at
`~/.config/jolt-llm-proxy/auth.json` (override with
`JOLT_LLM_PROXY_AUTH_FILE`), shared with the Go original. The directory and
file are maintained with modes `0700` and `0600`; startup tightens an existing
credential file before reading it.

## Build a standalone binary

Jolt resolves the pinned dependencies, compiles the `llm-proxy.core` entry
point, and embeds `resources/` into a self-contained native binary. Build on
the target Linux/Chez platform (or use Jolt's documented `--target` /
`--target-pack` cross-compilation options):

```bash
# Produces an optimized native executable at target/jolt-llm-proxy taking ~3min.
# `--direct-link` reduces runtime var indirection for this closed-world binary.
mkdir -p target
jolt build -m llm-proxy.core -o target/jolt-llm-proxy --opt --direct-link

# Faster uniptimized development build (takes ~30 sec)
jolt build -m llm-proxy.core -o target/jolt-llm-proxy --dev

# The executable contains the application and resources; Jolt is not needed
# to run it after the build.
./target/jolt-llm-proxy --help
```

The build command above was verified with Jolt's standalone build mode. The
result is platform-specific; rebuild it for each target OS/architecture and
ship it with the system OpenSSL/glibc requirements described in Prerequisites.
Credentials are deliberately not embedded: the binary reads the normal
`~/.config/jolt-llm-proxy/auth.json` path (or `JOLT_LLM_PROXY_AUTH_FILE`).

## Why Jolt

No JVM. Jolt runs Clojure on Chez Scheme; FFI to C (OpenSSL, POSIX sockets) is
via `jolt.ffi` (`defcfn`), not JNI. Inbound HTTP/1.1 (+ WebSocket) is served by
[`jolt-lang/ring-chez-adapter`](https://github.com/jolt-lang/ring-chez-adapter);
outbound TLS + the hand-rolled RFC 6455 WebSocket client use
[`jolt-lang/http-client`](https://github.com/jolt-lang/http-client)
(`jolt.http.tls` / `jolt.http.net`).

## Architecture

```
client ──HTTP/1.1──▶ ring-chez.adapter/run-server ──▶ llm-proxy.proxy/make-handler
                                                          │  translate (chat↔responses)
                                                          │  continuation (delta + prefix)
                                                          ▼
                                  codex.ws (RFC 6455 client, per-session pool)
                                                          │  wss over jolt.http.tls/tls-connect
                                                          ▼
                       wss://chatgpt.com/backend-api/codex/responses
```

- **`codex.auth`** — loads `~/.config/jolt-llm-proxy/auth.json`,
  refreshes the OAuth access token, exposes `[token account-id]`.
- **`codex.ws`** — TLS dial (`jolt.http.tls/tls-connect`), WS upgrade handshake,
  masked client frames, message defragmentation, event reader, per-session pool.
- **`codex.continuation`** — builds the delta request (`previous_response_id` +
  `store:false`), normalizes prior output into input items, lenient prefix match
  (assistant text matched on role only).
- **`llm-proxy.schema`** — open, string-keyed Malli schemas that validate untrusted
  request structure and collection bounds before normalization.
- **`codex.translate`** — `/v1/chat/completions` ↔ `/v1/responses` translation.
  Parses client JSON with string keys, delegates structural checks to
  `llm-proxy.schema`, and then performs policy-aware normalization; unknown
  top-level `/v1/responses` keys pass through upstream unchanged.
- **`llm-proxy.transport.sse`** / **`llm-proxy.transport.ws`** — generic
  transport adapters that expose a common event-source contract while keeping
  the Codex WebSocket protocol implementation in `codex.ws`.
- **`codex.cli`** — `login` (browser PKCE + headless device code), `logout`,
  `usage` (weekly allowance), `info` (JWT claim dump); ports Go `auth.go`/
  `usage.go`/`info.go`.
- **`codex.collect`** — event collectors that reduce upstream events into
  OpenAI-shaped output (chat + responses, streaming and non-streaming).
- **`llm-proxy.proxy`** — Ring boundary: routes, API-key guard, `prompt_cache_key`,
  transport selection, endpoint handlers.
- **`llm-proxy.error`** — classified, redacted public error responses and
  structured logging; only WebSocket setup/initial-write failures use SSE fallback.
  **Inbound Ring request/response maps are plain Clojure PMaps** (ring-chez
  `request->ring` builds them with `{}`/`assoc`): use `(:headers req)`,
  `get-in`, `assoc` normally — do **not** use `jolt.host/ref-get` there (it
  returns `nil` on a PMap). The **outbound `jolt.http.tls` stream** is the
  opposite: a `jolt.host/tagged-table`, so read `:write`/`:read`/`:close` with
  `jolt.host/ref-get`, not `(:write st)`. See [`JOLT-GOTCHAS.md`](./docs/JOLT-GOTCHAS.md) §1.
- **`llm-proxy.core`** — sole lifecycle owner for `start!`/`stop!`. Each start
  creates an isolated token store, session pool, and handler from
  `llm-proxy.proxy/make-handler`; startup cleanup is transactional and stop is
  idempotent.

## Development workflow (REPL-driven)

```bash
# 1. Start a persistent nREPL server in this dir (stdin from /dev/null!)
nohup jolt nrepl-server 7888 >/tmp/jolt-nrepl.log 2>&1 </dev/null &
# verify
ps -eo pid,args | grep 'jolt nrepl-server' | grep -v grep
ss -ltn | grep 7888
cat .nrepl-port

# 2. Iterate. Live-reload a namespace into the running proxy:
brepl -p 7888 '(require (quote llm-proxy.proxy) :reload)'

# 3. Evaluate a file:
brepl -f examples/ws_handshake.clj
```

For a live proxy process, prefer the explicit `serve --nrepl` flag above: it
starts an nREPL server with session, interruptible-eval, completion, and lookup
middleware, then stops it with the proxy. It is loopback-only; do not expose it
through a reverse proxy or untrusted network.

`examples/ws_handshake.clj` is the proven end-to-end recipe for the outbound wss
handshake (`tls-connect` + `ref-get` + masked-frame-ready write). It returns
`HTTP/1.1 101 Switching Protocols` + a valid `sec-websocket-accept`.

> **Kill nREPL by PID, never `pkill -f 'jolt nrepl-server'`** — that pattern is
> a substring of the issuing shell command and self-matches, silently killing
> your shell. See [`JOLT-GOTCHAS.md`](./docs/JOLT-GOTCHAS.md) §2.

## Platform notes (read before coding)

- [`JOLT-GOTCHAS.md`](./docs/JOLT-GOTCHAS.md) — surprising non-JVM quirks
  (tagged-table access, pkill self-match, nREPL persistence, brepl stdout,
  opaque printing).
- [`JOLT-ISSUES.md`](./docs/JOLT-ISSUES.md) — reviewed upstream reports,
  including brepl output loss and Jolt's missing base64url shim.
- [`CLOJURE-CONVERGENCE.md`](./docs/CLOJURE-CONVERGENCE.md) — reviewed
  Clojure/Jolt convergence observations.
- [`docs/adr/`](./docs/adr/) — accepted architectural decisions and completed
  implementation progress.

The two most dangerous gotchas: **`jolt.host/tagged-table` does not implement
`IFn`**, so `(:write st)` on the outbound TLS stream returns `nil` silently —
use `jolt.host/ref-get` (but never on Ring PMaps, where it is the wrong
accessor). And the **AOT cache + reader form-swallowing** trap: a top-level
form missing its closing paren silently absorbs the rest of the file, leaving
vars *interned but unbound*, while `brepl balance` fails with `Unable to fix`.
See [`JOLT-GOTCHAS.md`](./docs/JOLT-GOTCHAS.md) §7–§8.

## References

- Original Go implementation: https://github.com/jasalt/chatgpt-openai-api-adapter
  (`proxy.go`, `websocket.go`, `continuation.go`, `translate.go`, `auth.go`).
- Jolt: <https://github.com/jolt-lang/jolt> · `llms.txt` at its repo root.
- ring-chez-adapter: <https://github.com/jolt-lang/ring-chez-adapter>
- http-client (TLS/net): <https://github.com/jolt-lang/http-client>
