# Upstream issue review

Reproducible upstream gaps encountered while porting
`chatgpt-openai-api-adapter` to Jolt. This file is a triage queue, not a claim
that every surprising behavior is a Jolt bug.

Reviewed 2026-08-19 against:

- Jolt **v0.7.16** (current release; original observations used v0.7.13)
- brepl **2.7.1**
- `jolt-lang/http-client` commit
  `6faba3570378df91c2c84a588a262b732f4f1858`
- Fedora 44 x86_64, glibc, OpenSSL 3

The commands below are independent of this application unless a dependency
checkout is explicitly required. File issues in the repository named in each
entry. Clojure semantic divergences belong in `CLOJURE-CONVERGENCE.md`; local
workflow and platform notes belong in `JOLT-GOTCHAS.md`.

## Report upstream

### JI-1: brepl loses successful stdout when the same eval later fails

**Upstream:** <https://github.com/licht1stein/brepl> (not Jolt core)

**Status:** Confirmed with brepl 2.7.1 and a Jolt v0.7.16 nREPL server. No
matching issue was found in the upstream tracker on 2026-08-19.

This is most clearly a brepl client bug: `--verbose` proves that the server sent
the output before sending the error, while normal mode does not print it.

Create a file in an otherwise empty directory:

```clojure
;; throwtest.clj
(println "BEFORE-THROW-OUTPUT")
(throw (ex-info "boom" {:k 1}))
```

Start any nREPL server and pass its port explicitly (Jolt shown here):

```console
$ jolt nrepl-server 7899
$ brepl --version
brepl 2.7.1
$ brepl -p 7899 -f throwtest.clj
boom
Exception: boom
```

`BEFORE-THROW-OUTPUT` is absent. The raw protocol mode shows that nREPL did
send it:

```console
$ brepl -p 7899 --verbose -f throwtest.clj
{"op" "eval", "code" "(load-file \"throwtest.clj\")", ...}
{"out" "BEFORE-THROW-OUTPUT\n", ...}
{"err" "boom\n", ...}
{"ex" "boom", "status" ["eval-error" "done"], ...}
```

**Expected:** Normal mode should print every `out` and `err` response received,
even when the final response has `eval-error`. A useful regression test is to
feed `process-eval-responses` the three response maps above and assert that the
CLI writes both `BEFORE-THROW-OUTPUT` and the exception.

**Likely location:** brepl 2.7.1 collects `:out` correctly and prints it before
calling `System/exit` on failure. The observed behavior is consistent with the
Babashka stdout buffer not being flushed before that exit; explicitly flushing
stdout before exiting should be investigated.

**Impact:** A failed `brepl -f` makes earlier diagnostic output disappear and
can falsely suggest that the file never ran.

**Separate concern:** The Jolt nREPL response only carries `"ex" "boom"`; it
does not expose the exception's `ex-data`. That is not evidence of brepl
silently dropping `ex-data`, and should not be bundled into the brepl report.
If richer nREPL errors are desired, first compare Jolt's response with the nREPL
protocol/middleware contract and file that independently against Jolt/nREPL.

### JI-4: `java.util.Base64` shim lacks URL-safe encoder and decoder

**Upstream:** <https://github.com/jolt-lang/jolt>

**Status:** Confirmed on Jolt v0.7.13 and v0.7.16. No matching Jolt issue was
found on 2026-08-19. This is a small shim-coverage enhancement, not a core
language defect.

Minimal probe:

```console
$ jolt -e '(println (System/getProperty "jolt.version")) \
           (java.util.Base64/getUrlDecoder)'
v0.7.16
Unhandled exception: No matching field or method: java.util.Base64/getUrlDecoder
```

The ordinary decoder exists but does not accept the URL alphabet:

```console
$ jolt -e '(let [d (java.util.Base64/getDecoder)] \
             (prn (try (vec (.decode d "_w")) \
                       (catch Throwable e [(ex-message e)]))))'
["Base64: illegal character"]
```

Reference behavior (Babashka/JVM):

```console
$ bb -e '(let [e (.withoutPadding (java.util.Base64/getUrlEncoder)) \
                   s (.encodeToString e (byte-array [(byte -1)])) \
                   d (java.util.Base64/getUrlDecoder)] \
           (prn [s (vec (.decode d s))]))'
["_w" [-1]]
```

**Expected:** Implement at least `Base64/getUrlEncoder` and
`Base64/getUrlDecoder`, using RFC 4648's `-`/`_` alphabet. JVM parity also
suggests supporting `.withoutPadding` on the encoder. Tests should cover bytes
whose output actually differs from standard Base64 (for example `[-1]`), both
padded and unpadded input, rather than ASCII text such as `hello` whose encoding
does not distinguish the alphabets.

**Implementation context:** In v0.7.16 the Base64 implementation is in
`host/chez/java/host-static-classes.ss`; only `getEncoder` and `getDecoder` are
registered. Jolt's Host Interop page lists `java.util.Base64` but does not state
this narrower surface.

**Impact:** JWT/JWS and other base64url consumers must translate `-`→`+`,
`_`→`/`, and restore padding before calling the ordinary decoder. See
`JOLT-GOTCHAS.md` §6.

## Valid but low-priority documentation contribution

### JI-2: make the raw TLS stream accessor explicit in `http-client`

**Upstream:** <https://github.com/jolt-lang/http-client>

**Verdict:** Valid documentation improvement, but not a runtime bug and not a
Jolt language-convergence issue.

`jolt.http.tls/tls-connect` returns a Jolt host tagged-table. Such values are
stateful host wrappers, not persistent Clojure maps, and their fields are read
with `jolt.host/ref-get`:

```clojure
(require '[jolt.http.tls :as tls]
         '[jolt.host :as host])

(let [stream (tls/tls-connect "example.com" 443 false 15000 15000)]
  (try
    (prn [(host/table? stream)
          (ifn? stream)
          (:write stream)
          (boolean (host/ref-get stream :write))])
    (finally
      ((host/ref-get stream :close)))))
;; => [true false nil true]
```

The network makes that example less suitable for an automated Jolt-core report;
the underlying behavior can be demonstrated without I/O:

```console
$ jolt -e '(let [t (jolt.host/tagged-table :demo)] \
             (jolt.host/ref-put! t :a 1) \
             (prn [(ifn? t) (get t :a) (:a t) \
                   (jolt.host/ref-get t :a)]))'
[false nil nil 1]
```

The current Jolt Host Interop documentation already teaches tagged-table fields
through `ref-put!`/`ref-get`. The `jolt.http.tls` namespace docstring says that
the stream is a tagged-table carrying `:write`, `:read`, and `:close`, but does
not explicitly connect those two facts. A focused http-client doc change would
help direct users:

> The return value is a `jolt.host/tagged-table`, not a persistent map. Obtain
> the operations with `(jolt.host/ref-get stream :write)` (and `:read` /
> `:close`); keyword lookup and `get` do not read host fields.

This matters only to consumers deliberately using the low-level TLS namespace;
normal `jolt.http.platform` users never access these fields directly. Therefore
it is less important than a failing public API and should be offered as a small
documentation PR, not reported as a high-severity blocker.

## Do not report upstream

### JI-3: `BIO_ctrl` / OpenSSL 3 hypothesis — disproved

The previous claim that `BIO_ctrl` bound to nil was false. `c-BIO-ctrl` binds on
the tested OpenSSL 3 system, and the full WebSocket handshake works with the
upstream TLS implementation. The actual nil was obtained by treating a tagged
host value as a map (JI-2). Retained here only to prevent reopening the same
misdiagnosis.

### Local shell/process notes

`pkill -f` self-matching and the need to detach a background nREPL process are
shell/process-management behavior, not Jolt defects. They remain local notes in
`JOLT-GOTCHAS.md` §§2–3.

### Delimiter swallowing

A misplaced delimiter can make later `def` forms part of an earlier `defn`
while leaving the whole file syntactically balanced. That is ordinary Lisp
structure, not a reader bug. `brepl balance` checks delimiter balance; it cannot
infer the programmer's intended top-level form boundaries. See
`JOLT-GOTCHAS.md` §8.

### AOT-cache observations

The stale/mixed AOT-cache symptoms in `JOLT-GOTCHAS.md` §7 are operationally
important but currently lack a minimal sequence that starts from an empty temp
project/cache and deterministically produces the wrong result on current Jolt.
Do not file the narrative as an upstream bug. First produce a script that owns
its temporary `HOME`, edits one namespace, runs a fixed sequence of fresh Jolt
processes, and asserts stale output; then retest v0.7.16 and main.
