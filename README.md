# jolt-codex-sub-proxy

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
> API-key guard, and the `login`/`logout`/`usage`/`info` CLI — see
> [`TODO.md`](./docs/TODO.md) Phase 7.

## Usage

```bash
# Log in (browser PKCE on localhost:1455, or headless device code):
jolt -m codex.core login

# Serve an OpenAI-compatible API (env: CHATGPT_ADAPTER_ADDR, CHATGPT_ADAPTER_API_KEY):
CHATGPT_ADAPTER_ADDR=127.0.0.1:8090 CHATGPT_ADAPTER_API_KEY=my-key jolt -m codex.core serve

curl -H 'Authorization: Bearer my-key' http://127.0.0.1:8090/v1/models
curl -H 'Authorization: Bearer my-key' -H 'Content-Type: application/json' \
     -H 'X-Session-Id: conv-1' \
     -d '{"model":"gpt-5.4-mini","input":"hello","stream":true}' \
     http://127.0.0.1:8090/v1/responses

# Account status:
jolt -m codex.core usage   # weekly Codex allowance
jolt -m codex.core info     # credentials + JWT claims
jolt -m codex.core logout
```

Requests carrying an `X-Session-Id` (or `X-Prompt-Cache-Key`) header are routed
over pooled WebSocket connections with multi-turn delta continuation; requests
without one fall back to plain SSE. Credentials live at
`~/.config/chatgpt-openai-api-adapter/auth.json` (override with
`CHATGPT_ADAPTER_AUTH_FILE`), shared with the Go original.

## Why Jolt

No JVM. Jolt runs Clojure on Chez Scheme; FFI to C (OpenSSL, POSIX sockets) is
via `jolt.ffi` (`defcfn`), not JNI. Inbound HTTP/1.1 (+ WebSocket) is served by
[`jolt-lang/ring-chez-adapter`](https://github.com/jolt-lang/ring-chez-adapter);
outbound TLS + the hand-rolled RFC 6455 WebSocket client use
[`jolt-lang/http-client`](https://github.com/jolt-lang/http-client)
(`jolt.http.tls` / `jolt.http.net`).

## Architecture

```
client ──HTTP/1.1──▶ ring-chez.adapter/run-server ──▶ codex.proxy/handler
                                                          │  translate (chat↔responses)
                                                          │  continuation (delta + prefix)
                                                          ▼
                                  codex.ws (RFC 6455 client, per-session pool)
                                                          │  wss over jolt.http.tls/tls-connect
                                                          ▼
                       wss://chatgpt.com/backend-api/codex/responses
```

- **`codex.auth`** — loads `~/.config/chatgpt-openai-api-adapter/auth.json`,
  refreshes the OAuth access token, exposes `[token account-id]`.
- **`codex.ws`** — TLS dial (`jolt.http.tls/tls-connect`), WS upgrade handshake,
  masked client frames, message defragmentation, event reader, per-session pool.
- **`codex.continuation`** — builds the delta request (`previous_response_id` +
  `store:false`), normalizes prior output into input items, lenient prefix match
  (assistant text matched on role only).
- **`codex.translate`** — `/v1/chat/completions` ↔ `/v1/responses` translation.
- **`codex.cli`** — `login` (browser PKCE + headless device code), `logout`,
  `usage` (weekly allowance), `info` (JWT claim dump); ports Go `auth.go`/
  `usage.go`/`info.go`.
- **`codex.proxy`** — ring handler, routes, `prompt_cache_key`, SSE/WS event
  collectors. **Inbound Ring request/response maps are plain Clojure PMaps** (ring-chez
  `request->ring` builds them with `{}`/`assoc`): use `(:headers req)`,
  `get-in`, `assoc` normally — do **not** use `jolt.host/ref-get` there (it
  returns `nil` on a PMap). The **outbound `jolt.http.tls` stream** is the
  opposite: a `jolt.host/tagged-table`, so read `:write`/`:read`/`:close` with
  `jolt.host/ref-get`, not `(:write st)`. See [`JOLT-GOTCHAS.md`](./docs/JOLT-GOTCHAS.md) §1.
- **`codex.core`** — `start!`/`stop!`, persistent state (token store, session
  pool). `run-server` called with `#'codex.proxy/handler` so live reload via
  `brepl eval -e '(require (quote codex.proxy) :reload)'` picks up redefs
  without restarting the HTTP server.

## Development workflow (REPL-driven)

```bash
# 1. Start a persistent nREPL server in this dir (stdin from /dev/null!)
nohup jolt nrepl-server 7888 >/tmp/jolt-nrepl.log 2>&1 </dev/null &
# verify
ps -eo pid,args | grep 'jolt nrepl-server' | grep -v grep
ss -ltn | grep 7888
cat .nrepl-port

# 2. Iterate. Live-reload a namespace into the running proxy:
brepl -p 7888 '(require (quote codex.proxy) :reload)'

# 3. Evaluate a file:
brepl -f ws_handshake.clj
```

`ws_handshake.clj` is the proven end-to-end recipe for the outbound wss
handshake (`tls-connect` + `ref-get` + masked-frame-ready write). It returns
`HTTP/1.1 101 Switching Protocols` + a valid `sec-websocket-accept`.

> **Kill nREPL by PID, never `pkill -f 'jolt nrepl-server'`** — that pattern is
> a substring of the issuing shell command and self-matches, silently killing
> your shell. See [`JOLT-GOTCHAS.md`](./docs/JOLT-GOTCHAS.md) §2.

## Platform notes (read before coding)

- [`JOLT-GOTCHAS.md`](./docs/JOLT-GOTCHAS.md) — surprising non-JVM quirks
  (tagged-table access, pkill self-match, nREPL persistence, brepl stdout,
  opaque printing).
- [`JOLT-ISSUES.md`](./docs/JOLT-ISSUES.md) — upstream gaps/bugs to forward
  (JI-1 brepl stdout discard, JI-2 http-client doc gap, JI-3 superseded
  misdiagnosis).
- [`CLOJURE-CONVERGENCE.md`](./docs/CLOJURE-CONVERGENCE.md) — Clojure-language
  divergences confirmed against Babashka (`bb`).

The two most dangerous gotchas: **`jolt.host/tagged-table` does not implement
`IFn`**, so `(:write st)` on the outbound TLS stream returns `nil` silently —
use `jolt.host/ref-get` (but never on Ring PMaps, where it is the wrong
accessor). And the **AOT cache + reader form-swallowing** trap: a top-level
form missing its closing paren silently absorbs the rest of the file, leaving
vars *interned but unbound*, while `brepl balance` fails with `Unable to fix`.
See [`JOLT-GOTCHAS.md`](./docs/JOLT-GOTCHAS.md) §7–§8.

## References

- Original Go implementation: [`../chatgpt-openai-api-adapter/`](../chatgpt-openai-api-adapter/)
  (`proxy.go`, `websocket.go`, `continuation.go`, `translate.go`, `auth.go`).
- Jolt: <https://github.com/jolt-lang/jolt> · `llms.txt` at its repo root.
- ring-chez-adapter: <https://github.com/jolt-lang/ring-chez-adapter>
- http-client (TLS/net): <https://github.com/jolt-lang/http-client>
