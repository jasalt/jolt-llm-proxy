# JOLT-ISSUES.md

Reproducible upstream gaps/bugs in the Jolt language, its libraries, or its
documentation, encountered while porting `chatgpt-openai-api-adapter` to Jolt.
Per `AGENTS.md` these are intended to be forwarded to maintainers. Clojure
*language* divergences confirmed against Babashka are filed separately in
`CLOJURE-CONVERGENCE.md`; surprising non-JVM quirks in `JOLT-GOTCHAS.md`.

Environment: Jolt v0.7.13, brepl 2.7.1, Fedora 44, OpenSSL 3.x
(`/lib64/libssl.so.3`), glibc.

---

## JI-1: `brepl -f <file>` discards buffered `println`/stdout on eval error

**Tool / version:** `brepl` 2.7.1 (companion to Jolt v0.7.13).

**Repro.** File `throwtest.clj`:

```clojure
(println "BEFORE-THROW-OUTPUT")
(throw (ex-info "boom" {:k 1}))
```

```
$ brepl -f throwtest.clj
boom
Exception: boom
```

The `println` line produces **no output**. The exception message is reported,
but `ex-data` (`{:k 1}`) is not shown, and all stdout emitted before the throw
is lost.

**Expected.** Buffered stdout emitted before the throw should be flushed, and
`ex-data` should be shown (as `jolt -e '(load-file "throwtest.clj")'` does — it
prints `BEFORE-THROW-OUTPUT`, the message `boom`, and `ex-data: {:k 1}`).

**Reference.**

```
$ jolt -e '(load-file "throwtest.clj")'
BEFORE-THROW-OUTPUT
Unhandled exception: boom
  ex-data: {:k 1}
  at throwtest.clj:2:1
```

**Impact.** Debugging-by-println during REPL-driven dev (`brepl -f`) is
unreliable: a thrown exception erases all prior `println` evidence, so a
failure looks like nothing ran. Workaround in `JOLT-GOTCHAS.md` §4 — wrap the
top-level form in `try`/`catch` returning a data map, or iterate with
`jolt -e '(load-file …)'` first.

---

## JI-2: `jolt-lang/http-client` TLS stream access is undocumented — host tagged-table, not a map

**Library / version:** `jolt-lang/http-client` SHA
`6faba3570378df91c2c84a588a262b732f4f1858` (`jolt.http.tls`).

**Repro.** `(jolt.http.tls/tls-connect host port insecure cto rto)` returns a
host tagged-table (`:jolt/tls-stream`) carrying `:write`, `:read`, `:close`
closures. The namespace docstring says "A TLS stream is a host tagged-table
carrying :write / :read / :close closures" but does **not** state that the only
correct accessor is `jolt.host/ref-get`, nor that keyword-as-function
(`(:write st)`) returns `nil` because tagged-tables are not `IFn`
(see `CLOJURE-CONVERGENCE.md` CONV-1).

```clojure
(require '[jolt.http.tls :as tls])
(def st (tls/tls-connect "chatgpt.com" 443 false 15000 15000))
(:write st)                 ;; => nil   (JVM muscle memory; silently nil)
(jolt.host/ref-get st :write) ;; => <closure>
```

The internal `jolt.http.platform` namespace uses `ref-get` consistently
(`s-write`, `s-read`, `s-close`), but a downstream consumer building a raw
WebSocket client on top of `tls-connect` (our use case) gets no hint from the
docstring and hits "class nil cannot be cast to class clojure.lang.IFn" on the
first write.

**Expected.** The `jolt.http.tls` docstring should state:
> The returned stream is a `jolt.host/tagged-table`, not a Clojure map. Read
> its `:write`/`:read`/`:close` fields with `jolt.host/ref-get`; `(:write st)`
> returns `nil` because tagged-tables do not implement `IFn`.

**Impact.** Blocks any direct (non-`jolt.http.platform`) use of the TLS stream.
Workaround in `JOLT-GOTCHAS.md` §1.

---

## JI-3 (superseded): `BIO_ctrl` binds to nil on OpenSSL 3 — NOT confirmed

An earlier hypothesis (logged during misdiagnosis) claimed `jolt.http.tls`'s
`defcfn c-BIO-ctrl "BIO_ctrl"` binds to `nil` on OpenSSL 3 because `BIO_ctrl`
is a macro. **This turned out to be false on this system:**

```
$ jolt -e '(require (quote [jolt.http.tls :as tls])) (println (nil? tls/c-BIO-ctrl))'
false
```

`c-BIO-ctrl` binds fine. The real cause of the "nil cannot be cast to IFn"
crash was CONV-1 / JI-2 (`(:write st)` returning nil on a tagged-table).
Kept here to prevent re-litigating the same wrong theory. The vendored
`codex/tls.clj` that "fixed" `bio-pending` was based on this misdiagnosis and
has been removed; upstream `jolt.http.tls` is used unmodified and a full
`wss://chatgpt.com/backend-api/codex/responses` WebSocket handshake
(`101 Switching Protocols` + valid `sec-websocket-accept`) completes with it.
