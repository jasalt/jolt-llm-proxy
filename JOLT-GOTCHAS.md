# JOLT-GOTCHAS.md

Surprising, under-documented differences in Jolt (Clojure-on-Chez, non-JVM)
platform behavior encountered while building this proxy. Each entry has a
repro and the workaround we adopted. Forward-worthy items are also mirrored
in `JOLT-ISSUES.md` when they look like upstream gaps rather than intended
divergence.

---

## 1. Host tagged-tables are NOT `IFn` — keyword-as-function and `get` return `nil`

**Surprise.** On JVM Clojure a persistent map (`clojure.lang.IPersistentMap`)
implements `IFn`, so `(:a m)` calls `(.valAt m :a)`. A Jolt *host tagged-table*
(`jolt.host/tagged-table`) is **not** `IFn`:

```clojure
(def tt (jolt.host/tagged-table :jolt/x))
(jolt.host/ref-put! tt :a 1)
(ifn? tt)   ;; => false
(get tt :a) ;; => nil   (NOT 1)
(:a tt)     ;; => nil   (NOT 1)
```

The table's fields are readable **only** through `jolt.host/ref-get`:

```clojure
(jolt.host/ref-get tt :a) ;; => 1
```

**Impact.** This is silent: no exception, just `nil`. Any code copied from JVM
Clojure that destructures or keyword-calls a host object (`(:headers req)`,
`(:write stream)`, `(:body resp)`, …) will quietly read `nil` and then fail
much later as "nil cannot be cast to class clojure.lang.IFn" when the `nil` is
eventually invoked.

**Where it bit us.** The `jolt-lang/http-client` TLS stream is a host
tagged-table (`:jolt/tls-stream`) carrying `:write`/`:read`/`:close` closures.
Writing `(:write st)` returns `nil`; the upstream `jolt.http.platform` namespace
deliberately wraps every access in `(jolt.host/ref-get stream :write)`. Copying
JVM-style `(let [w (:write st)] (w st data))` reproduces the crash in both
`jolt -e` and `nrepl-server`.

**Workaround.** Always use `jolt.host/ref-get` / `ref-put!` for host tagged-tables.
Wrap with a small helper if ergonomics matter:

```clojure
(defn tget [t k] (jolt.host/ref-get t k))
(defn tput! [t k v] (jolt.host/ref-put! t k v))
```

**Caveat for Ring handlers.** `ring-chez.adapter` delivers request maps as
**plain Clojure PMaps** (built with `{}`/`assoc` in `http/request->ring`), so
`(:headers req)` and `(get-in req [:headers "upgrade"])` work normally there.
`jolt.host/ref-get` is the **wrong** accessor on a PMap (it returns `nil`).
The `ref-get` rule is for the **outbound `jolt.http.tls` stream** and other
`jolt.host/tagged-table` objects, not for Ring maps. Don't mix the two:

```clojure
;; inbound ring request  — PMap, normal Clojure
(:headers req)            ;; => {"authorization" "Bearer …", …}
(get-in req [:headers "x-session-id"])

;; outbound tls stream    — tagged-table, host accessors
(jolt.host/ref-get st :write)  ;; => closure
(:write st)                    ;; => nil  (WRONG)
```

---

## 2. `pkill -f 'jolt nrepl-server'` kills your own shell

Not a Jolt bug, but it cost hours. `pkill -f` matches the **full command line**
of every process, including the bash invocation that is running your
`pkill ... && nohup jolt nrepl-server ...` command (that line literally contains
the string `jolt nrepl-server`). The shell suicides and the tool call returns
`(no output)` with no error.

**Workaround.** Kill by PID:

```bash
PID=$(ps -eo pid,args | grep 'jolt nrepl-server' | grep -v grep | awk '{print $1}')
[ -n "$PID" ] && kill "$PID"
```

Never `pkill -f <pattern>` where `<pattern>` is a substring of the command
issuing the pkill.

---

## 3. nREPL background-process persistence

`jolt nrepl-server 7888` started with `nohup ... & </dev/null` from a tool shell
survives across tool calls only if stdin is redirected from `/dev/null` and the
process is detached from the shell's process group. A bare `&` with the shell's
stdin still attached can die when the tool call's process group is reaped.

**Workaround.**

```bash
nohup jolt nrepl-server 7888 >/tmp/jolt-nrepl.log 2>&1 </dev/null &
```

Verify with `ps -eo pid,args | grep 'jolt nrepl-server'` and `ss -ltn | grep 7888`
before trusting `.nrepl-port`.

---

## 4. `brepl -f <file>` discards buffered `println` stdout on eval error

When a file evaluated via `brepl -f` throws, brepl reports the exception
message (and nothing else) and any `println`/stdout emitted **before** the
throw is lost. `ex-data` is not shown. There is no stack trace.

```
$ brepl -f throwtest.clj      # (println "X") then (throw (ex-info "boom" {:k 1}))
boom
Exception: boom
```

`jolt -e '(load-file "throwtest.clj")'` on the same file prints the `println`
output, the message, **and** `ex-data: {:k 1}` with a trace — so it is the
reliable debugging path.

**Workaround.** Develop the risky form with `jolt -e '(load-file "x.clj")'`
first (real traces + flushed stdout), then move to `brepl -f` once it loads
clean. For `brepl -f` specifically, wrap the top-level form in
`(try ... (catch Throwable e ...))` returning a data map
(`{:err (ex-message e) :data (ex-data e)}`) so the result survives.

See `JOLT-ISSUES.md` JI-1.

---

## 5. Host tagged-tables print opaquely as `#object[...]` / type `:object`

`(type tt)` on a tagged-table returns `:object`, not a class symbol, and the
table's keys/values are not shown by `pr`. This makes debugging request/stream
shapes by eye impossible without `(jolt.host/ref-get tt k)` probes.

**Workaround.** A small dumper:

```clojure
(defn dump-tt [t] (into {} (map (fn [k] [k (jolt.host/ref-get t k)])) [:headers :body :write :read :close :sock]))
```

---

## 6. `java.util.Base64` shim lacks the URL variants

The Jolt `java.util.Base64` shim implements `getEncoder`/`getDecoder` (standard
base64) but **not** `getUrlEncoder`/`getUrlDecoder`. Calling
`java.util.Base64/getUrlDecoder` throws
`No matching field or method: java.util.Base64/getUrlDecoder`.

This bites JWT decoding (JWT payloads are base64url). Workaround: translate
base64url to standard base64 before decoding:

```clojure
(defn b64url->b64std [s]
  (let [pad (mod (- 4 (mod (count s) 4)) 4)]
    (-> (str s (apply str (repeat pad "=")))
        (.replace "-" "+") (.replace "_" "/"))))
(.decode (java.util.Base64/getDecoder) (.getBytes (b64url->b64std part) "UTF-8"))
```

Verified against a live OpenAI access token: the decoded payload's
`:https://api.openai.com/auth` → `:chatgpt_account_id` matches `auth.json`.
See `JOLT-ISSUES.md` JI-4.

---

## 7. The AOT cache (`~/.jolt/aot-cache`) makes fresh processes diverge from the live nREPL

**Surprise.** `require` goes through `aot-compile-and-cache` (visible in
stack traces): each compiled namespace is written to `~/.jolt/aot-cache`
(`.scm`/`.so`/`.deps` artifacts) and **read back by every new `jolt` process**
(`jolt -e`, `jolt file.clj`, CLI runs). A persistent nREPL session using
`require ... :reload` sees your edits; a fresh process may load a stale or
mixed cache state instead. Symptoms seen in this project:

- New defs missing entirely (`No such var: cli/info!`, `Unknown class cli`).
- Vars present but never bound (`Attempting to call unbound fn: ...`) after a
  cold compile.
- `jolt -e '(load-file "src/x.clj")'` succeeding while `jolt -e '(require 'x)'`
  fails for the same file — different code paths (interpreter vs AOT).
- A warm cache **masking forward references**: `codex.ws` loaded fine while
  `close-conn` called `write-close` defined later in the file; the cold compile
  failed with `Unable to resolve symbol`. The cache served old artifacts that
  never re-resolved the symbol. (Reordering the defs was the real fix; the
  error only ever appeared on a cold compile.)

**Workarounds.**

```bash
# After structural edits, before trusting cold-load behavior:
rm -rf ~/.jolt/aot-cache
# Then verify in a fresh process, checking BINDING not just loading:
jolt -e "(do (require 'codex.cli)
             (println (bound? (ns-resolve (find-ns 'codex.cli) 'info!))))"
```

- `:reload` / `:reload-all` in the nREPL refreshes the cache for later
  processes; a plain `require` in a new process does not recompile.
- Order top-level defs callee-before-caller on a cold compile, or use
  `(declare later-fn)` — verified working in Jolt.
- **Do not trust "the namespace loaded" as success.** `require` returning
  without error only means the reader+compiler ran; it says nothing about
  which defs actually got bound (see §8).

---

## 8. A missing paren silently swallows the rest of the file — vars become "unbound", not "missing"

**Surprise.** If a top-level form is missing its final closing delimiter, the
reader does not fail — it keeps reading, and **every following top-level form
becomes part of the broken form's body**. If that form is a `defn`, the
swallowed `(def ...)`/`(defn ...)` forms are compiled as *body statements*:
their vars get interned at compile time but stay **unbound** until the outer
function is actually called. This bit `codex.cli` for a full session:

- `(cli/info!)` → `Attempting to call unbound fn: #'codex.cli/info!` cold,
  but worked in processes where `usage!` had been called first.
- `(cli/usage!)` **returned `#'codex.cli/info!`** — the last swallowed form
  was `(defn info! ...)`, and `defn` evaluates to the var.
- `ns-publics` listed all 21 vars (interned), hiding that the last six were
  unbound. `brepl balance` reported `Unable to fix` — the real warning sign.

**Detection.** Whole-file paren depth is useless here (our file netted to 0
because a stray `)` sat at EOF). Check **per-form** balance:

```python
# every top-level form must return to depth 0 before the next starts
```

and probe binding, not existence:

```clojure
(doseq [[s v] (ns-publics (find-ns 'codex.cli))]
  (when-not (bound? v) (println "UNBOUND:" s)))
```

**Rules adopted.**

- `brepl balance` failing with `Unable to fix` = stop and hand-inspect; it
  means delimiters are irreconcilable, usually a misplaced close, not just a
  missing one at EOF.
- Never "fix" a paren imbalance by appending `)` at EOF — it balances the
  count while corrupting the structure (that is exactly what created this bug).
- A function returning a `#'var` (or any value it has no business returning)
  is the signature of swallowed defs in its body.
