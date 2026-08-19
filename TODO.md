# TODO — Jolt port of chatgpt-openai-api-adapter

Port the Go proxy (`../chatgpt-openai-api-adapter`) to Clojure-on-Chez (Jolt),
using `ring-chez-adapter` for the inbound HTTP/1.1 server and `jolt.http.net` +
`jolt.http.tls` (from `jolt-lang/http-client`) for the outbound TLS + WebSocket
client to `wss://chatgpt.com/backend-api/codex/responses`.

> **Read this whole file before writing code.** It is written for a smaller
> LLM that makes fewer mistakes when given exact signatures, copy-paste recipes,
> and an explicit list of what **not** to do. Each phase has: **Goal**,
> **Files**, **Sub-items** (small, testable steps), **Acceptance** (a `brepl`
> command that must pass), and **Common mistakes**.

Reuses existing dev credentials at
`~/.config/chatgpt-openai-api-adapter/auth.json` (keys `access_token`,
`refresh_token`, `expires_at` unix-ms, `account_id`).

---

## ⚠️ Platform rules that apply to EVERY phase (read once, refer back)

These are the things that will silently break code that looks correct to a JVM
Clojure programmer. Full details in `JOLT-GOTCHAS.md` / `CLOJURE-CONVERGENCE.md`.

### R1 — Two kinds of "map", two accessors. Do not mix them.

| Object | Type | Read with | Write with |
|---|---|---|---|
| Ring request/response maps (`ring-chez.adapter`) | **PMap** | `(:headers req)`, `(get-in req [:headers "x"])` | `assoc`, `assoc-in` |
| `jolt.http-client` response (`{:status :headers :body}`) | **PMap** | `(:status r)`, `(:body r)` | — |
| `clojure.data.json` parse result (with `:key-fn keyword`) | **PMap** | `(:access_token auth)` | — |
| `jolt.http.tls` stream (`tls-connect` result) | **tagged-table** | `(jolt.host/ref-get st :write)` | `(jolt.host/ref-put! st :k v)` |

```clojure
;; WRONG (returns nil silently, then crashes later as "nil cannot be cast to IFn"):
(:write st)              ;; st is a tagged-table, not a PMap
(jolt.host/ref-get req :headers)  ;; req is a PMap, ref-get returns nil on PMaps

;; RIGHT:
(jolt.host/ref-get st :write)     ;; tagged-table
(:headers req)                    ;; PMap
```

If you see "class nil cannot be cast to class clojure.lang.IFn", you almost
certainly used the wrong accessor on the wrong type. Check R1 first.

### R2 — `java.*` classes are used by **direct interop**, never `require`.

```clojure
;; RIGHT — no require, just use the class:
(java.util.Base64/getEncoder)
(java.security.MessageDigest/getInstance "SHA-1")
(java.security.SecureRandom.)

;; WRONG — fails with "Could not locate java/util/Base64.jolt":
(require '[java.util.Base64 :as b64])
```

Available & verified: `java.util.Base64` (`.getEncoder`/`.encodeToString`,
`.getDecoder`/`.decode`), `java.security.MessageDigest` (`getInstance "SHA-1"`,
`.update`, `.digest`), `java.security.SecureRandom` (`.nextBytes`),
`java.lang.String` (`String.`, `.getBytes`, `.substring`), `java.lang.StringBuilder`
(`StringBuilder.`, `.append`, `.toString`). `byte-array`, `alength`, `aget`,
`aset` work. **Strings are codepoint-indexed** (`(subs s 0 1)` on "😀" is fine,
but byte/char offsets are not the same — for byte work use `byte-array`).

**Base64 shim gap:** `java.util.Base64/getUrlDecoder` and `/getUrlEncoder` are
**NOT** in the shim (only `getEncoder`/`getDecoder`, standard base64). For
base64url (JWT payloads) convert first:
```clojure
(defn b64url->b64std [s]
  (let [pad (mod (- 4 (mod (count s) 4)) 4)]
    (-> (str s (apply str (repeat pad "=")))
        (.replace "-" "+") (.replace "_" "/"))))
(.decode (java.util.Base64/getDecoder) (.getBytes (b64url->b64std part) "UTF-8"))
```

### R3 — nREPL process: keep it alive, reload with `:reload`.

```bash
# Start once (stdin MUST come from /dev/null, or the tool call reaps it):
nohup jolt nrepl-server 7888 >/tmp/jolt-nrepl.log 2>&1 </dev/null &
# Verify:
ps -eo pid,args | grep 'jolt nrepl-server' | grep -v grep
ss -ltn | grep 7888 && cat .nrepl-port
```

- Kill **by PID**, never `pkill -f 'jolt nrepl-server'` (self-matches the shell
  and silently kills your tool call — see `JOLT-GOTCHAS.md` §2).
- Live-reload a namespace into the running proxy:
  `brepl '(require (quote codex.X) :reload)'`
- Evaluate a file: `brepl -f path/to/file.clj` (searches `.nrepl-port` upward
  from the file's dir — so run from this project dir).
- `brepl -f` **discards buffered `println` stdout on error** but still prints
  the exception message. To see full traces + flushed stdout while iterating,
  use `jolt -e '(load-file "x.clj")'` first (`JOLT-GOTCHAS.md` §4).
- Wrap risky top-level forms in `(try … (catch Throwable e {:err (ex-message e) :data (ex-data e)}))`
  so `brepl -f` returns a data map instead of `nil`.

### R4 — Outbound TLS + WebSocket: use the proven recipe.

`jolt.http.tls/tls-connect` works **unmodified**. The full
`wss://chatgpt.com/backend-api/codex/responses` handshake
(`101 Switching Protocols` + valid `sec-websocket-accept`) is proven in
`ws_handshake.clj` — read it as the reference. Key points:
- `(def st (tls/tls-connect "chatgpt.com" 443 false 20000 20000))`
- `(def wfn (jolt.host/ref-get st :write))` / `(def rfn (jolt.host/ref-get st :read))`
- `(wfn st (.getBytes req "ISO-8859-1"))` — `wfn` is `(fn [self data] …)`,
  **pass `st` again as the first arg** (it's the `self` of the closure).
- `(rfn st nil)` returns a `byte[]` of decrypted plaintext or `nil` on idle/EOF.
- Close with `((jolt.host/ref-get st :close))`.
- `ring-chez.adapter` and `jolt.http.tls` **coexist fine** in one process
  (verified, both load orders). There is **no FFI load-order contamination** —
  that was a misdiagnosis (see `JOLT-ISSUES.md` JI-3).

### R5 — JSON.

`(require '[clojure.data.json :as json])`. `(json/read-str s :key-fn keyword)`
→ PMap with keyword keys. `(json/write-str m)` → string. For parsing arrays
of objects, `(json/read-str s :key-fn keyword)` returns a Clojure vector of
PMaps. `json/read-str` keys are strings by default — **always pass
`:key-fn keyword`** unless you want string keys.

### R6 — `previous_response_id` / `store:false` are required on the WS transport.

The Codex WebSocket endpoint **rejects `store:true`**. Every WS request body
must set `"store" false` and `"stream" true`. `previous_response_id` is added
by the continuation logic (Phase 3). A client-supplied `previous_response_id`
is passed through unchanged and the proxy's delta logic defers to it.

### R7 — Source roots & deps.

`deps.edn` has `:paths ["src"]`. Namespaces live under `src/codex/*.clj` as
`codex.auth`, `codex.ws`, `codex.continuation`, `codex.translate`,
`codex.proxy`, `codex.core`. After editing a file, run
`brepl balance <file>` (per `AGENTS.md`) then `brepl '(require (quote codex.X) :reload)'`.

---

## Progress

- [x] Phase 0 — Foundation (done)
- [x] Phase 1 — `codex.auth` (done)
- [x] Phase 2 — `codex.ws` (done; dial, masked frames, defrag, pool reuse verified)
- [ ] Phase 3 — `codex.continuation` (in progress)
- [ ] Phase 4 — `codex.translate`
- [ ] Phase 5 — `codex.proxy`
- [ ] Phase 6 — `codex.core`
- [ ] Phase 7 — End-to-end verification
- [ ] Phase 8 — Docs, issues, polish

---

## Phase 0 — Foundation (DONE ✓)

- [x] `deps.edn` (ring-chez-adapter + http-client + data.json + time)
- [x] nREPL + `brepl '<form>'` workflow verified (port 7888, this dir)
- [x] Outbound TLS + WebSocket handshake verified end-to-end (`ws_handshake.clj` → 101)
- [x] Diagnosed real blocker (R1: tagged-table `ref-get`), removed misdiagnosed vendor
- [x] Filed `JOLT-ISSUES.md`, `CLOJURE-CONVERGENCE.md`, `JOLT-GOTCHAS.md`

**Do not redo.** Upstream `jolt.http.tls` is used unmodified (no vendored copy).

---

## Phase 1 — `codex.auth` (token store + refresh) — IN PROGRESS

**Goal:** load `auth.json`, refresh the OAuth access token when expired, expose
`[token account-id]` to the rest of the proxy. Mirror Go `auth.go`
(`tokenStore`, `exchangeToken`, `accountIDFromJWT`, `jwtExpiry`).

**Files:** `src/codex/auth.clj`.

**Sub-items** (do in order, each is a single `defn` you can test in isolation):

1. **Constants** (top of file):
   ```clojure
   (def client-id "app_EMoamEEZ73f0CkXaXp7hrann")
   (def auth-base-url "https://auth.openai.com")
   (def refresh-margin-ms (* 5 60 1000))   ; refresh if <5min left
   (def default-auth-path
     (str (or (System/getenv "HOME") ".") "/.config/chatgpt-openai-api-adapter/auth.json"))
   ```
   Read env override: `(or (System/getenv "CHATGPT_ADAPTER_AUTH_FILE") default-auth-path)`.

2. **`load-cred [path]`** → PMap or `nil` if file missing.
   Use `(clojure.core/slurp path)` then `(json/read-str s :key-fn keyword)`.
   Return `nil` (not throw) when `(java.io.File. path)` `.exists` is false.
   Keys after `:key-fn keyword`: `:access_token`, `:refresh_token`,
   `:expires_at`, `:account_id`.

3. **`save-cred! [path cred]`** → write `auth.json` atomically.
   Use `json/write-str` with `:indent true` if supported, else plain.
   Write to `path` (Mkdirs `(java.io.File. (.getParentPath (java.io.File. path)))`
   first — or just `(.mkdirs (java.io.File. (str path ".tmp"))`'s parent).
   Simplest reliable approach: `(spit path (str (json/write-str cred) "\n"))`.
   (Atomic rename is nice-to-have, not required for the port.)

4. **`decode-jwt-payload [token]`** → PMap.
   Split on `"."` (`clojure.string/split token #"\."`), take part index 1,
   base64url-decode (the Base64 shim **lacks** `getUrlDecoder` — use the
   `b64url->b64std` helper from R2 + `java.util.Base64/getDecoder`), then
   `json/read-str :key-fn keyword`.
   Verified: `:key-fn keyword` **does** preserve the URL-with-slashes key as
   `:https://api.openai.com/auth`.

5. **`account-id-from-jwt [token]`** → string.
   `(get-in (decode-jwt-payload token) [:https://api.openai.com/auth
   :chatgpt_account_id])`. Verified: this matches the `account_id` written in
   `auth.json` (they come from the same JWT). If a future JWT shape breaks the
   keyword key, fall back to string keys: `(json/read-str payload-str)` then
   `(get-in p ["https://api.openai.com/auth" "chatgpt_account_id"])`.

6. **`jwt-expiry-ms [token]`** → unix-ms.
   `(get-in (decode-jwt-payload token) [:exp])` → `(* exp 1000)` (exp is unix
   seconds). Fallback `(+ (System/currentTimeMillis) (* 60 60 1000))`.

7. **`refresh-token! [cred]`** → new cred PMap.
   ```clojure
   (defn refresh-token! [cred]
     (let [resp (http/post (str auth-base-url "/oauth/token")
                  {:form-params {"grant_type" "refresh_token"
                                 "client_id" client-id
                                 "refresh_token" (:refresh_token cred)}
                   :throw-exceptions false})]
       (when-not (= 200 (:status resp))
         (throw (ex-info "token refresh failed" {:status (:status resp) :body (:body resp)})))
       (let [body (json/read-str (:body resp) :key-fn keyword)
             access (:access_token body)
             refresh (or (:refresh_token body) (:refresh_token cred)) ; rotate or keep
             expires-in (:expires_in body)
             expires-at (if (and expires-in (pos? expires-in))
                          (+ (System/currentTimeMillis) (* expires-in 1000))
                          (jwt-expiry-ms access))
             account (account-id-from-jwt access)]
         {:access_token access :refresh_token refresh
          :expires_at expires-at :account_id account})))
   ```
   Requires `(require '[jolt.http-client :as http])` at top — and per R4 the
   `jolt.http.tls`/crypto libs must be loaded for HTTPS; requiring
   `jolt.http-client` pulls them. Verified working: a real refresh returns 200.

8. **Stateful `token-store` record/atom.** Use a simple `(def state (atom {}))`
   holding `{:path :cred}`. Provide:
   - `(start! [path])` — load cred into atom, return the store.
   - `(authenticated? [store])` — `(:access_token cred)` truthy.
   - `(token [store & {:keys [force]}])` → `[access-token account-id]`,
     refreshing when `(>= (+ (System/currentTimeMillis) refresh-margin-ms) (:expires_at cred))`
     or `force`, saving the new cred. Mutex: the Go uses `sync.Mutex`; in Jolt
     single-threaded proxy workers may call concurrently — wrap the refresh in
     a `clojure.core/locking` on the store, or serialize via an agent. Simplest
     correct: `(locking store …)`.
   - `(logout! [store])` — clear atom, delete file.

**Acceptance:**
```
brepl -f src/codex/auth.clj
brepl '(in-ns (quote codex.auth)) (def s (start! default-auth-path)) (println "acct:" (:account_id @s)) (println "token-len:" (count (:access_token @s))) (println "forced refresh:" (count (first (token s :force true))))'
```
Must print a non-empty `account_id`, a token length ~1700+, and a refreshed
token length ~1700+. `account_id` must match the `account_id` in `auth.json`
(they come from the same JWT).

**Common mistakes:**
- Using `(:write …)` on the http-client response — it's a PMap, that's correct
  here; the trap is the *opposite* (using `ref-get` on a PMap).
- Forgetting `:throw-exceptions false` on the POST (default throws on 4xx/5xx).
- Getting the JWT `https://api.openai.com/auth` key wrong — test it explicitly.
- Not `:reload`-ing after edits (R3).

---

## Phase 2 — `codex.ws` (RFC 6455 client + per-session pool) — DONE ✓

**Goal:** a minimal WebSocket client over the `jolt.http.tls` stream: dial,
handshake, send masked text frames, read+defragment messages, handle
ping/pong/close, and a per-session connection pool with idle TTL. Mirror Go
`websocket.go` (`wsConn`, `wsDial`, `readFrame`/`writeFrame`/`ReadMessage`,
`sessionPool`, `acquire`/`release`).

**Files:** `src/codex/ws.clj`.

**Sub-items:**

### 2a — Constants & handshake key
```clojure
(def ws-beta-header "responses_websockets=2026-02-06")
(def ws-url "wss://chatgpt.com/backend-api/codex/responses")
(def ws-guid "258EAFA5-E914-47DA-95CA-C5AB0DC85B11")
(def ws-max-message 67108864) ; 64 MiB
(def ws-session-max-age-ms (* 55 60 1000))
(def ws-idle-ttl-ms (* 5 60 1000))
```
- `handshake-key []` → 16 random bytes base64-std-encoded:
  `(let [b (byte-array 16)] (.nextBytes (java.security.SecureRandom.) b)
   (.encodeToString (java.util.Base64/getEncoder) b))`
- `accept-key [key]` → SHA-1 of `(str key ws-guid)`, base64-std:
  `(let [d (java.security.MessageDigest/getInstance "SHA-1")] (.update d (.getBytes (str key ws-guid) "UTF-8")) (.encodeToString (java.util.Base64/getEncoder) (.digest d)))`

### 2b — `dial [token account-id session-id]` → `{:stream :handshake-data}`
Build the upgrade request exactly like `ws_handshake.clj` (copy that string),
with headers:
`Authorization: Bearer <token>`, `ChatGPT-Account-Id: <account-id>`,
`OpenAI-Beta: responses_websockets=2026-02-06`, `originator: pi`,
`User-Agent: chatgpt-openai-api-adapter/1`, `session-id: <session-id>`,
`x-client-request-id: <session-id>`, plus `Upgrade/Connection/Sec-WebSocket-Key/Sec-WebSocket-Version`.
- `(def st (tls/tls-connect "chatgpt.com" 443 false 20000 20000))`
- `(wfn st (.getBytes req "ISO-8859-1"))`
- Read the HTTP/1.1 101 response by looping `(rfn st nil)` until the accumulated
  bytes contain `"\r\n\r\n"`. Parse the status line + `sec-websocket-accept`
  header from the bytes before the first `\r\n\r\n`.
- **Verify** `accept-key` matches, else close + throw.
- After the blank line, any leftover bytes are the **start of the WS frame
  stream** — keep them in a per-connection byte buffer (see 2d).
- Return `{:stream st :session-id session-id :created-at (System/currentTimeMillis) :leftover <byte-array-or-nil>}`.

### 2c — `write-frame [conn opcode payload]` (client→server, MUST mask)
`conn` carries `:stream` and a `:write-lock` (Object to `locking` on — frames
must not interleave). Build the header per RFC 6455 §5.2/§5.3:
- byte0: `(bit-or 0x80 opcode)` (FIN=1).
- byte1: length 7/16/64-bit with the high mask bit `0x80` set (client masking).
  - `n < 126`: byte1 = `(bit-or 0x80 n)`, no extra length bytes.
  - `n < 65536`: byte1 = `(bit-or 0x80 126)`, then 2-byte big-endian length.
  - else: byte1 = `(bit-or 0x80 127)`, then 8-byte big-endian length.
- 4-byte random mask (SecureRandom). Append mask, then `masked[i] = payload[i] ^ mask[i&3]`.
- Write header bytes then masked payload via `wfn`. For big-endian shorts/longs
  use bit ops (`bit-shift-left`, `bit-and 0xff`) into a `byte-array`; there is no
  `DataOutputStream` shim guaranteed — build bytes manually.
- `write-text [conn json-string]` → `write-frame` opcode `0x1`.
- `write-close [conn]` → `write-frame` opcode `0x8` empty payload.

### 2d — `read-message [conn]` (server→client; defragment; handle control frames)
Maintain a `:leftover` byte buffer on `conn` (from handshake + prior reads).
`read-frame` reads ≥2 bytes; parse FIN, opcode, masked, length (7/16/64), mask,
payload. Server frames are typically **unmasked** but handle mask if present.
- opcode `0x8` close → return `{:type :close}`.
- opcode `0x9` ping → `write-frame` opcode `0xA` with the ping payload, recur.
- opcode `0xA` pong → ignore, recur.
- opcode `0x1`/`0x2` (text/binary): if FIN return `{:type :text-or-binary :data payload}`,
  else start accumulator.
- opcode `0x0` continuation: append; if FIN return accumulated.
- Because `(rfn st nil)` returns a chunk of bytes that may span multiple frames
  or partial frames, implement a **byte buffer** (a `java.io.ByteArrayOutputStream`
  or a mutable `byte-array` + position) that you refill from `(rfn st nil)` when
  it doesn't have enough bytes. This is the trickiest part — **port Go's
  `bufio.Reader`-backed `readFrame`** but with an explicit buffer since jolt
  has no `bufio`. Use `java.io.ByteArrayOutputStream` (`.write`, `.toByteArray`,
  `.reset`) for accumulation.
- `read-event [conn]` → parse one `read-message` text payload as JSON
  (`json/read-str :key-fn keyword`), return `{:type (:type obj) :data obj}`.
  Empty/non-JSON messages are skipped (recur) per Go `readWebSocket`.

### 2e — `read-until-terminal [conn emit]`
Loop `read-event`, call `(emit event)` for each, stop on terminal types:
`"response.completed"`, `"response.done"`, `"response.incomplete"`,
`"response.failed"`, `"error"`. Returns when terminal (so the socket can be
released to the pool). Mirror Go `readWebSocket`.

### 2f — Per-session pool
`(def pool (atom {}))` keyed by session-id → conn map. Mirror Go `sessionPool`:
- `acquire [pool session-id header-builder]` → `{:conn :reused :release}`:
  - If `session-id` empty → one-off: dial, `release` = close.
  - If cached & not busy & not too old (`< ws-session-max-age-ms`) → mark busy,
    reuse, `release` = releaser.
  - If cached & busy → one-off (don't share between concurrent requests).
  - If cached & too old → close+drop, create fresh cached.
  - If absent → create fresh cached.
- `releaser`: `(fn [keep] …)` — if not keep, close+drop; if keep, mark not-busy
  and schedule an idle timer (`ws-idle-ttl-ms`) that closes+drops if still idle.
  Jolt has no `time.AfterFunc`; use a `(future (Thread/sleep ttl) (cleanup))` or
  an agent. Simplest: `(def idle-future (future (Thread/sleep ws-idle-ttl-ms) (maybe-close …)))`.
  Store the future on the conn so a later acquire can cancel it
  `(future-cancel idle-future)`.
- `busy` flag + `continuation` slot live on the conn map (Phase 3 fills
  `:continuation`).

**Acceptance:**
```
brepl -f src/codex/ws.clj
brepl '(in-ns (quote codex.ws)) (require (quote [clojure.data.json :as json])) (def auth (json/read-str (slurp "/home/user/.config/chatgpt-openai-api-adapter/auth.json") :key-fn keyword)) (def conn (dial (:access_token auth) (:account_id auth) "wstest02")) (println "dial ok:" (some? (:stream conn))) (println "accept ok:" (:accept-ok conn)) (write-text conn (json/write-str {:type "response.create" :model "gpt-5.4" :input [{:role "user" :content "say hi in one word"}] :stream true :store false})) (def events (atom [])) (read-until-terminal conn (fn [e] (swap! events conj (:type e)))) (println "event types:" (take 8 @events)) (write-close conn)'
```
Must print `dial ok: true`, `accept ok: true`, and an event-types list
containing `"response.completed"` (or `response.done`). The model name must be
one a current Codex subscription accepts (check `modelIDs` in Go `proxy.go`;
`gpt-5.4` is in the list).

**Common mistakes:**
- Forgetting the client MUST mask outbound frames (server will close the
  connection otherwise).
- Losing leftover bytes after the HTTP 101 — those are WS frames.
- Not `locking` the write path → interleaved frames corrupt the stream.
- Using `(:write st)` instead of `(jolt.host/ref-get st :write)` (R1).
- Building big-endian lengths wrong — test with a payload >125 bytes (forces
  16-bit length) and one >65535 (forces 64-bit) if feasible.

---

## Phase 3 — `codex.continuation` (delta request + prefix match) — IN PROGRESS

**Goal:** given the current request body and the prior turn's
`continuationState`, produce a delta body (`previous_response_id` + only the
new input items) when the current input extends the prior conversation, else
the full body. Mirror Go `continuation.go` exactly — the lenient-assistant
prefix match is load-bearing.

**Files:** `src/codex/continuation.clj`.

**Sub-items:**

1. **`continuation-state` shape** (a PMap, not a record — keep it data):
   `{:last-request-body :last-response-id :last-response-items}`.

2. **`message-item-text [item]`** → string. Port Go `messageItemText`: if
   `:content` is a string return it; if it's a vector, concatenate the `:text`
   of parts whose `:type` is `"output_text"`,`"text"`, or `"input_text"`.

3. **`content-parts-text [parts]`** → string (same concatenation).

4. **`normalize-input-item [item]`** → canonical PMap. Port Go
   `normalizeInputItem`:
   - If `:role` is `assistant`/`user`/`system`/`developer`: flatten `:content`
     to a plain string → `{:role role :content text}` (or `""` if no content).
   - Else (function_call, function_call_output, reasoning): copy all keys
     except `:status`. Return that map.

5. **`normalize-input-list [items]`** → vector of normalized items (same length).

6. **`response-output-to-input-items [output for-chat]`** → vector. Port Go
   `responseOutputToInputItems`:
   - `:type "message"` → `{:role "assistant" :content (message-item-text item)}` normalized.
   - `:type "function_call"` → normalized item.
   - `:type "reasoning"`/`"reasoning_summary"` → keep only when `(not for-chat)`.
   - Other types → keep only when `(not for-chat)`.

7. **`deep-equal-json [a b]`** → boolean. Jolt has `clojure.core/=` which is
   value-equality on nested PMaps/vectors — use `=`. (Go used `reflect.DeepEqual`
   because Go `==` isn't deep on maps.) **Verify** `(= {:a [1 2]} {:a [1 2]})`
   is true in jolt (it is, standard Clojure).

8. **`bodies-match-except-input [a b]`** → boolean. Port Go
   `bodiesMatchExceptInput`: same keys (ignoring `"input"` and
   `"previous_response_id"`), and `=` on each shared value.

9. **`prefix-matches-continuation [current baseline]`** → boolean. Port Go
   `prefixMatchesContinuation` **exactly**, including the lenient assistant rule:
   - same length, else false.
   - for each pair: if baseline item `:role` is `"assistant"`, only require
     current item `:role` == `"assistant"` (server replays its own stored text,
     so client-resent assistant text is ignored). Otherwise `=` (deep).

10. **`build-delta-request [body cont]`** → `[delta-body ok?]`. Port Go
    `buildDeltaRequest`:
    - `cont` nil or `:last-response-id` empty → `[body false]`.
    - `body` already has `:previous_response_id` → `[body false]` (client manages it).
    - `bodies-match-except-input` false → `[body false]`.
    - `baseline = (concat (normalize-input-list last-input) last-response-items)`.
    - `current-norm = (normalize-input-list current-input)`.
    - if `(> (count baseline) (count current-norm))` → `[body false]`.
    - if `(prefix-matches-continuation (take (count baseline) current-norm) baseline)` false → `[body false]`.
    - else: `delta-start = (- (count current-input) (- (count current-norm) (count baseline)))`,
      `delta = (subvec current-input delta-start)`,
      `delta-body = (assoc body :previous_response-id (:last-response-id cont) :input delta)`,
      `[delta-body true]`.

**Acceptance:** write `src/codex/continuation.clj`, then a small test file
`test_continuation.clj` that requires it and asserts:
- A 2-turn chat: turn-1 body has `input [{:role user :content "hi"}]`; turn-2
  has `input [{:role user :content "hi"} {:role assistant :content "hello"} {:role user :content "how are you"}]`;
  `cont` = `{:last-request-body turn1-body :last-response-id "resp_1"
  :last-response-items [{:role assistant :content "hello"}]}`.
  `build-delta-request` returns `ok? true` and `:input` = the last user item
  only, with `:previous_response_id "resp_1"`.
- An unrelated turn-2 (different `:instructions`) → `ok? false`.
```
brepl -f test_continuation.clj
```
Must print `PASS` for both cases.

**Common mistakes:**
- Making the assistant prefix match strict (will fail continuation whenever the
  client's streamed text differs from the server's stored text — which it
  often does due to truncation/whitespace).
- Using `clojure.core/=` where it's correct but then second-guessing and writing
  a custom deep-equal — don't, `=` is deep on Clojure data.
- Forgetting `:store false` is set by Phase 4 translate, not here — continuation
  only adds `:previous_response_id` and rewrites `:input`.

---

## Phase 4 — `codex.translate` (chat ↔ responses)

**Goal:** convert `/v1/chat/completions` bodies to `/v1/responses` bodies, and
normalize `/v1/responses` bodies for the upstream. Mirror Go `translate.go`
(`chatToResponses`, `prepareResponses`, `flattenTools`, `flattenToolChoice`,
`flattenResponseFormat`, `contentText`, `responseContent`).

**Files:** `src/codex/translate.clj`.

**Sub-items:**

1. **`chat-to-responses [raw-bytes-or-string]`** → `[request model stream]`.
   Parse with `json/read-str :key-fn keyword`. Require `:model` (string) and
   `:messages` (vector, non-empty). Build `:instructions` from
   `system`/`developer` messages (join with `"\n\n"`). Build `:input` per
   message role:
   - `system`/`developer` → fold into instructions.
   - `assistant` → `{:role "assistant" :content text}`; if `:tool_calls`,
     emit `{:type "function_call" :call_id … :name … :arguments …}` each.
   - `tool` → `{:type "function_call_output" :call_id :output}`.
   - `user` → `{:role "user" :content (response-content content)}`.
   Default instructions `"You are a helpful assistant."`, default input a
   single empty user message. Reject `n != 1`. Set `:stream true`, `:store false`.
   Pass through `:service_tier`, `:parallel_tool_calls`, `:temperature`,
   `:reasoning_effort` (→ `:reasoning {:effort :summary "auto"}`), `:tools`
   (flatten), `:tool_choice` (flatten), `:response_format` (→ `:text :format`).

2. **`prepare-responses [raw]`** → `[request model stream]`. Parse; require
   `:model` and `:input`; if `:input` is a string wrap to
   `[{:role "user" :content input}]`. Force `:stream true`, `:store false`.
   Default instructions. Delete `:max_output_tokens`/`:max_tokens`.
   Map `service_tier "fast"` → `"priority"`. **Pass `:previous_response_id`
   through unchanged** (R6).

3. **`flatten-tools`/`flatten-tool-choice`/`flatten-response-format`/`content-text`/`response-content`**
   — direct ports. `content-text` joins `:text` parts with `"\n"`.

**Acceptance:**
```
brepl -f src/codex/translate.clj
brepl '(in-ns (quote codex.translate)) (def [req model stream] (chat-to-responses (json/write-str {:model "gpt-5.4" :messages [{:role "system" :content "be brief"} {:role "user" :content "hi"}] :stream true}))) (println "model:" model "stream:" stream "store:" (:store req) "instructions:" (:instructions req) "input:" (:input req))'
```
Must print `model: gpt-5.4 stream: true store: false instructions: be brief
input: [{:role user :content "hi"}]`.

**Common mistakes:**
- Setting `:store true` (must be false — R6).
- Dropping `:previous_response_id` in `prepare-responses` (must pass through).
- Forgetting `:key-fn keyword` on `json/read-str` (then keys are strings and
  `(:model chat)` returns nil → "model is required" error).

---

## Phase 5 — `codex.proxy` (ring handler + collectors)

**Goal:** the Ring handler with routes `/health`, `/v1/models`,
`/v1/chat/completions`, `/v1/responses`; API-key guard; prompt-cache key
derivation; transport selection (WS when a per-session key is present, else
SSE fallback); and the SSE/WS event collectors that stream OpenAI-shaped
output. Mirror Go `proxy.go` (`proxyServer.routes`, `chat`, `responses`,
`openEventSource`, `wsSource`, `sseSource`, `streamChat`, `collectChat`,
`streamResponses`, `collectResponse`, `readSSE`, `chatCollector`,
`openAIUsage`).

**Files:** `src/codex/proxy.clj`.

**Sub-items:**

### 5a — Handler var + routes
Define `(defonce handler (fn [req] ...))` and **redefine it via a function**
so `#'handler` passed to `run-server` picks up redefs. Pattern:
```clojure
(defn app [req] (routes req))   ;; the real logic
(def handler #'app)             ;; var, so redef of `app` is live
```
Actually simpler: pass `#'handler` where `handler` is a `defn` you redef.
Route on `(:request-method req)` and `(:uri req)` (R1: PMap, keyword access ok).
Return Ring response PMaps: `{:status 200 :headers {"Content-Type" "application/json"} :body (json/write-str …)}`.

### 5b — API-key guard
Read `CHATGPT_ADAPTER_API_KEY` env. If set, compare
`(get-in req [:headers "authorization"])` (header names are **lower-cased**
by ring-chez — verify with a probe) after stripping `"Bearer "`. Constant-time
compare is nice but not required for the port; `=` is fine.

### 5c — Prompt-cache key
`resolve-session-id [req default]`: check headers `"x-session-id"` then
`"x-prompt-cache-key"` (lower-cased), return clamped to 64 codepoints
(`(subs key 0 (min 64 (count key)))` — `count` is codepoints on jolt, which is
correct here). `apply-prompt-cache-key [request key]`: assoc `:prompt_cache_key`
if not already present.

### 5d — SSE transport (`sse-source`)
POST to `https://chatgpt.com/backend-api/codex/responses` via
`jolt.http-client/request` (NOT `http/post` — you need `:as :stream` or raw
body access). **Check clj-http-lite streaming support**: if `:as :stream`
isn't supported, fall back to `:as :text` and feed the string to `read-sse`.
Retry once on 401 with `force` token refresh (mirror Go `upstreamSSE`).
Headers: `Authorization`, `ChatGPT-Account-Id`, `Content-Type application/json`,
`Accept text/event-stream`, `OpenAI-Beta responses=experimental`, `originator pi`,
`User-Agent`, `session-id`, `x-client-request-id`.

### 5e — `read-sse [src emit]`
Parse `event:`/`data:` lines into `{:name :data}` and call `(emit event)`.
Mirror Go `readSSE`. `[DONE]` ends. If working from a full string (not a
stream), `clojure.string/split` on `"\n"`.

### 5f — WS transport (`ws-source [ctx request session-id for-chat]`)
- `acquire` from `pool` (Phase 2) with a header-builder that calls
  `(codex.auth/token store)` for `[token account-id]`.
- Read `:continuation` off the conn; if present, `build-delta-request` (Phase 3);
  else send full body.
- `frame = (assoc request :type "response.create")`; `write-text` the JSON.
- Return `{:read (fn [emit] (codex.ws/read-until-terminal conn emit))
  :finalize (fn [meta] …)}`. `finalize`: if `meta.completed` and response-id
  non-empty, store `:continuation` =
  `{:last-request-body full-body :last-response-id (:response-id meta)
   :last-response-items (response-output-to-input-items (:items meta) for-chat)}`
  and `release true`; else clear `:continuation` and `release false`.

### 5g — `open-event-source [ctx request session-id for-chat]`
WS only when `session-id` non-empty and `!=` proxy default (per Go
`openEventSource`); else SSE. On WS dial failure, log + SSE fallback.

### 5h — Collectors
Port `chatCollector` as an atom or PMap accumulator:
- `consume-chat [acc event emit]` handles `response.output_text.delta`,
  `response.reasoning_summary_text.delta`/`response.reasoning_text.delta`,
  `response.output_item.added`/`.done`, `response.function_call_arguments.delta`/`.done`,
  `response.completed`/`.done`/`.incomplete`, `response.failed`/`error`.
- `stream-chat [write-chunk stream model]` and `collect-chat [stream model]`.
- `stream-responses [write-chunk stream]` and `collect-response [stream]`.
- `openai-usage [usage]` → `{:prompt_tokens :completion_tokens :total_tokens …}`.
- `response-meta [acc]` → `{:response-id :items :completed :incomplete}`.

### 5i — Streaming responses to the client
Use `ring-chez.sse/event-response` + a `clojure.core.async` channel:
the handler returns `{:status 200 :headers {"Content-Type" "text/event-stream" "Cache-Control" "no-cache" "X-Accel-Buffering" "no"} :body ch}`,
and a spawned thread `(async/thread …)` runs the collector, calling
`(ring-chez.sse/send! ch {:event name :data (json/write-str data)})` per event
and `(async/close! ch)` at the end. For non-stream, collect fully then return
a JSON PMap body.

**Acceptance:**
```
brepl -f src/codex/proxy.clj
# Then via curl against the running server (Phase 6 starts it):
curl -sN http://127.0.0.1:8080/health
curl -sN http://127.0.0.1:8080/v1/models
curl -sN -H 'Content-Type: application/json' -d '{"model":"gpt-5.4","messages":[{"role":"user","content":"say hi in one word"}],"stream":true}' http://127.0.0.1:8080/v1/chat/completions
```
`/health` → `{"status":"ok"}`. `/v1/models` → a list with `gpt-5.4`. The chat
curl must stream `data: {...}` chunks ending in `data: [DONE]`.

**Common mistakes:**
- Using `ref-get` on the Ring request map (R1 — it's a PMap).
- Header names: ring-chez lower-cases them; `(get-in req [:headers "X-Session-Id"])`
  returns nil — use `"x-session-id"`.
- Not closing the SSE channel → client hangs forever.
- Forgetting `:store false` in the upstream body (translate sets it; double-check).
- Calling `(emit event)` inside `consume-chat` without `try` — one bad event
  kills the stream. Wrap the per-event body.

---

## Phase 6 — `codex.core` (server + state)

**Goal:** wire it all together; `start!`/`stop!`; persistent state.

**Files:** `src/codex/core.clj`.

**Sub-items:**

1. **`(defonce system (atom nil))`** holding `{:store :pool :server :session-id :api-key}`.

2. **`start! [& {:keys [port api-key auth-path]}]`**:
   - `(def store (codex.auth/start! auth-path))`; if not authenticated, error
     (login is a separate CLI concern — see Phase 8 note).
   - `(def pool (atom {}))` (or reuse `codex.ws/pool`).
   - default session-id = `(or (System/getenv "CHATGPT_ADAPTER_SESSION_ID") (random-id))`
     clamped to 64.
   - `(def server (adapter/run-server #'codex.proxy/handler {:port port :worker-threads 8}))`
     — **pass the var `#'codex.proxy/handler`** so redefs are live (R3).
   - reset! `system` with the map. Return it.

3. **`stop! []`**: `(adapter/stop-server (:server @system))`, close all pooled
   WS connections, reset! `system` nil.

4. **`random-id []`**: 16 random bytes hex-encoded
   `(let [b (byte-array 16)] (.nextBytes (java.security.SecureRandom.) b)
    (format "%x" (BigInteger. 1 b)))` or byte-by-byte hex.

5. CLI dispatch (`-main [& args]`): `serve` / `login` / `logout` / `usage` /
   `info`. For `serve`, read `CHATGPT_ADAPTER_ADDR` (default `127.0.0.1:8080`)
   and `CHATGPT_ADAPTER_API_KEY`; call `start!`. (Login/logout/usage/info can
   be stubbed initially — mark in TODO — the Go versions exist for reference.)

**Acceptance:**
```
brepl -f src/codex/core.clj
brepl '(require (quote codex.core)) (codex.core/start! :port 8080 :auth-path codex.auth/default-auth-path)'
curl -sN http://127.0.0.1:8080/health
brepl '(codex.core/stop!)'
```
`/health` returns `{"status":"ok"}`. Live reload: edit `codex/proxy.clj`, run
`brepl '(require (quote codex.proxy) :reload)'`, hit `/health` again —
new code is live without restarting the server.

**Common mistakes:**
- Passing `codex.proxy/handler` (the value) instead of `#'codex.proxy/handler`
  (the var) → live reload silently broken.
- Letting the nREPL process die (R3) → `start!` server dies with it.
- Listening on a non-loopback address without an API key (Go refuses; mirror
  `safeListenAddress`).

---

## Phase 7 — End-to-end verification

Mirror the Go `main_test.go` scenarios. Write `test_e2e.clj` (run via `brepl -f`
against the running proxy) and `curl` checks.

- [ ] **7.1** `/v1/responses` single turn (non-stream) returns a completed
  response with `:id`, `:output`, `:usage`.
- [ ] **7.2** `/v1/responses` streaming emits `response.created` →
  `response.output_text.delta`* → `response.completed`, then SSE closes.
- [ ] **7.3** **Multi-turn delta continuation**: two turns on the same
  `X-Session-Id`. Inspect upstream `usage.input_tokens_details.cached_tokens`
  (or `prompt_cache_key` hit) — the second turn's `cached` must be **>=** the
  first turn's and grow with history (this is the whole point of the WS path).
  Confirm the second turn used `previous_response_id` (add a debug log in
  `ws-source` printing `usedDelta?`).
- [ ] **7.4** `/v1/chat/completions` streaming + non-streaming both return
  OpenAI-shaped `chat.completion.chunk` / `chat.completion` with `choices`,
  `usage`, correct `finish_reason` (`stop`/`tool_calls`/`length`).
- [ ] **7.5** **Parallel-session isolation**: two concurrent requests with
  different `X-Session-Id` on separate WS connections; turn-2 of session A
  must recall session A's turn-1, not session B's. (Spin two `future` calls
  in the test.)
- [ ] **7.6** **SSE fallback**: a request with no `X-Session-Id` uses SSE
  (no WS dial) and still returns a correct single-turn answer.
- [ ] **7.7** **Token refresh**: set `expires_at` in the atom to the past,
  force a request — proxy must transparently refresh and succeed.
- [ ] **7.8** **API-key guard**: with `CHATGPT_ADAPTER_API_KEY` set, a request
  without/with-wrong `Authorization` returns 401; correct key works.

Each sub-item: write the exact `curl`/`brepl` command and the assertion in
`test_e2e.clj`. Print `PASS`/`FAIL` per case.

**Common mistakes:**
- Trusting "it returned 200" without checking `cached` grew (7.3) — that's the
  feature under test.
- Running parallel sessions on the **same** `X-Session-Id` (they share one WS
  connection and serialize; 7.5 needs different ids).
- Not `:reload`-ing between fixes (R3).

---

## Phase 8 — Docs, issues, polish

- [ ] Keep `README.md` current with the real architecture + usage (commands,
  env vars, the `ref-get` vs PMap rule R1).
- [ ] Confirm `CLOJURE-CONVERGENCE.md` CONV-2 (`ex-data` on host ex-info) with
  a focused `bb` comparison; file upstream if confirmed.
- [ ] Review `JOLT-ISSUES.md` JI-1/JI-2 for forwarding (brepl stdout discard;
  http-client TLS stream doc gap).
- [ ] Implement `login`/`logout`/`usage`/`info` CLI commands (port Go
  `interactiveLogin`, `deviceLogin`, `codexUsage`, `codexInfo`) — these are
  secondary to `serve` and can land last.
- [ ] Atomic commits per phase (the `.git` is in this dir).

---

## Quick reference: where things live

- Go reference: `../chatgpt-openai-api-adapter/*.go`
  (`auth.go`, `websocket.go`, `continuation.go`, `translate.go`, `proxy.go`,
  `usage.go`, `info.go`, `main.go`).
- Proven WS handshake recipe: `ws_handshake.clj` (this dir).
- Jolt libs (read-only, in `~/.jolt/gitlibs`):
  `jolt-lang/ring-chez-adapter` (`ring-chez.adapter`, `ring-chez.sse`,
  `ring-chez.http`, `ring-chez.socket`), `jolt-lang/http-client`
  (`jolt.http.tls`, `jolt.http.net`, `jolt.http-client`, `jolt.http.platform`).
- Auth: `~/.config/chatgpt-openai-api-adapter/auth.json`.
- Issue/gotcha/convergence docs: `JOLT-ISSUES.md`, `JOLT-GOTCHAS.md`,
  `CLOJURE-CONVERGENCE.md`.
