# TODO — Jolt port of chatgpt-openai-api-adapter

Port the Go proxy (`../chatgpt-openai-api-adapter`) to Clojure-on-Chez (Jolt),
using `ring-chez-adapter` for the inbound HTTP/1.1 server and `jolt.http.net` +
`jolt.http.tls` (from `jolt-lang/http-client`) for the outbound TLS + WebSocket
client to `wss://chatgpt.com/backend-api/codex/responses`.

REPL-driven dev: a persistent `jolt nrepl-server 7888` process runs in this dir;
`brepl eval -e '(require (quote codex.x) :reload)'` reloads live. Inbound HTTP
served by `ring-chez.adapter/run-server`; outbound `wss://` hand-rolled
(RFC 6455 client over the TLS stream from `jolt.http.tls/tls-connect`).

Reuses existing dev credentials at `~/.config/chatgpt-openai-api-adapter/auth.json`.

## Key platform facts (confirmed)

- **Upstream `jolt.http.tls` works unmodified.** A full
  `wss://chatgpt.com/backend-api/codex/responses` WebSocket handshake
  (`101 Switching Protocols` + valid `sec-websocket-accept`) completes via
  `tls-connect` + manual write, in both `jolt -e` and `nrepl-server`/`brepl`.
  The earlier vendored `codex/tls.clj` "BIO_ctrl fix" was a misdiagnosis and
  was removed. See `JOLT-ISSUES.md` JI-3 (superseded).
- **The TLS stream is a `jolt.host/tagged-table`, NOT a Clojure map.** Read
  `:write`/`:read`/`:close` with `jolt.host/ref-get`, NOT `(:write st)`.
  `(:write st)` returns `nil` (tagged-tables do not implement `IFn`) and then
  fails as "nil cannot be cast to class clojure.lang.IFn". This was the *real*
  blocker behind every earlier "FFI contamination" symptom. See
  `CLOJURE-CONVERGENCE.md` CONV-1 and `JOLT-GOTCHAS.md` §1.
- **`ring-chez.adapter` and `jolt.http.tls` coexist fine** in one process
  (adapter-first or tls-first load order both verified: connect + write + read
  1369 bytes round-trip). No FFI load-order contamination.
- nREPL must be started with stdin redirected from `/dev/null` and killed by
  PID (never `pkill -f 'jolt nrepl-server'` — it self-matches the shell).
  See `JOLT-GOTCHAS.md` §2–3.

## Tasks

- [x] Project skeleton: `deps.edn` (ring-chez-adapter + http-client + data.json + time)
- [x] nREPL + `brepl eval` workflow verified (`.nrepl-port` on 7888, this dir)
- [x] **Outbound TLS + WebSocket handshake verified** end-to-end (101 + accept)
- [x] Diagnosed & documented real blocker (tagged-table `ref-get`), removed misdiagnosed vendor
- [ ] `codex.auth` — load auth.json, refresh token, expose `[token account-id]`
- [ ] `codex.ws` — RFC 6455 client: masked frames, defragment, event reader, per-session pool
- [ ] `codex.continuation` — delta build + normalize input + prefix match (lenient assistant)
- [ ] `codex.translate` — chat↔responses translation (+ allow `previous_response_id`)
- [ ] `codex.proxy` — ring handler, routes, prompt-cache key, SSE/WS event collectors
      (use `jolt.host/ref-get` for all request-map field access — see CONV-1)
- [ ] `codex.core` — server `start!`/`stop!`, persistent state (token store, session pool)
- [ ] End-to-end verify: `/v1/responses` multi-turn delta continuation (server recalls prior turns)
- [ ] End-to-end verify: `/v1/chat/completions` multi-turn continuation
- [ ] End-to-end verify: parallel sessions isolated on separate WS connections
- [ ] Confirm static-prefix `prompt_cache_key` caching still works
- [ ] Keep README.md updated (architecture + usage)
- [ ] File `JOLT-ISSUES.md` / `CLOJURE-CONVERGENCE.md` / `JOLT-GOTCHAS.md` (drafted; confirm CONV-2 with `bb`)

## Done
- Confirmed `jolt nrepl-server` writes `.nrepl-port`; `brepl eval`/`brepl -f` connect.
- Confirmed `ring-chez.adapter`, `jolt.http.tls`, `jolt.http.net`, `jolt.http-client`, `clojure.data.json` all load.
- Read Go source: proxy.go / websocket.go / continuation.go / translate.go / auth.go.
- Verified upstream `jolt.http.tls/tls-connect` + `ref-get` accessors do a real wss handshake.
- Filed `JOLT-ISSUES.md` (JI-1 brepl stdout, JI-2 http-client doc gap, JI-3 superseded misdiagnosis),
  `CLOJURE-CONVERGENCE.md` (CONV-1 tagged-table IFn, CONV-2 ex-data tentative),
  `JOLT-GOTCHAS.md` (tagged-table access, pkill self-match, nrepl persistence, brepl stdout, opaque print).
