# jolt-codex-sub-proxy

A Clojure-on-Chez (Jolt, non-JVM) port of the
[`chatgpt-openai-api-adapter`](https://github.com/jasalt/chatgpt-openai-api-adapter)
Go proxy. It authenticates to the ChatGPT Subscription (Codex) backend and
exposes an OpenAI-compatible HTTP API (`/v1/responses`, `/v1/chat/completions`)
for generic LLM clients, with multi-turn WebSocket delta continuation
(`previous_response_id`) so prompt-cache `cached` bytes grow with conversation
history.

> **Status:** inbound HTTP server + outbound wss handshake verified; `codex.*`
> namespaces and end-to-end multi-turn verification in progress — see
> [`TODO.md`](./TODO.md).

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
- **`codex.proxy`** — ring handler, routes, `prompt_cache_key`, SSE/WS event
  collectors. **Request maps are `jolt.host/tagged-table`s**: read fields with
  `jolt.host/ref-get`, not `(:headers req)` — see
  [`JOLT-GOTCHAS.md`](./JOLT-GOTCHAS.md) §1.
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
brepl eval -e '(require (quote codex.proxy) :reload)'

# 3. Evaluate a file:
brepl -f ws_handshake.clj
```

`ws_handshake.clj` is the proven end-to-end recipe for the outbound wss
handshake (`tls-connect` + `ref-get` + masked-frame-ready write). It returns
`HTTP/1.1 101 Switching Protocols` + a valid `sec-websocket-accept`.

> **Kill nREPL by PID, never `pkill -f 'jolt nrepl-server'`** — that pattern is
> a substring of the issuing shell command and self-matches, silently killing
> your shell. See [`JOLT-GOTCHAS.md`](./JOLT-GOTCHAS.md) §2.

## Platform notes (read before coding)

- [`JOLT-GOTCHAS.md`](./JOLT-GOTCHAS.md) — surprising non-JVM quirks
  (tagged-table access, pkill self-match, nREPL persistence, brepl stdout,
  opaque printing).
- [`JOLT-ISSUES.md`](./JOLT-ISSUES.md) — upstream gaps/bugs to forward
  (JI-1 brepl stdout discard, JI-2 http-client doc gap, JI-3 superseded
  misdiagnosis).
- [`CLOJURE-CONVERGENCE.md`](./CLOJURE-CONVERGENCE.md) — Clojure-language
  divergences confirmed against Babashka (`bb`).

The single most important gotcha: **`jolt.host/tagged-table` does not implement
`IFn`**, so `(:write st)` / `(:headers req)` return `nil` silently. Use
`jolt.host/ref-get`. This was the real cause of every earlier "FFI
contamination" / "BIO_ctrl nil" symptom.

## References

- Original Go implementation: [`../chatgpt-openai-api-adapter/`](../chatgpt-openai-api-adapter/)
  (`proxy.go`, `websocket.go`, `continuation.go`, `translate.go`, `auth.go`).
- Jolt: https://github.com/jolt-lang/jolt · `llms.txt` at its repo root.
- ring-chez-adapter: https://github.com/jolt-lang/ring-chez-adapter
- http-client (TLS/net): https://github.com/jolt-lang/http-client
