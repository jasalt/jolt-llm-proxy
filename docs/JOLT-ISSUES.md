# Upstream issue review

Reproducible upstream gaps encountered while porting
`chatgpt-openai-api-adapter` to Jolt. This file is a triage queue, not a claim
that every surprising behavior is a Jolt bug.

Reviewed 2026-08-21 against:

- Jolt **v0.7.20** (current release; original observations used v0.7.13)
- brepl **2.7.1**
- `jolt-lang/http-client` commit
  `6faba3570378df91c2c84a588a262b732f4f1858`
- Fedora 44 x86_64, glibc, OpenSSL 3

The commands below are independent of this application unless a dependency
checkout is explicitly required. File issues in the repository named in each
entry. Clojure semantic divergences belong in `CLOJURE-CONVERGENCE.md`; local
workflow and platform notes belong in `JOLT-GOTCHAS.md`.

## Issue outbox

### JI-1: brepl loses successful stdout when the same eval later fails

**Upstream:** <https://github.com/licht1stein/brepl> (not Jolt core)

**Status:** Confirmed with brepl 2.7.1 and a Jolt v0.7.20 nREPL server. No
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

## JI-4: Jolt's ArrayDeque shim omits `pollFirst`

**Upstream:** <https://github.com/jolt-lang/jolt>

**Status:** Confirmed on Jolt `d782ba6` (v0.8.7 baseline) with Chassis
v1.0.365. Jolt main at `be356e5` still provides `.poll` but not `.pollFirst`.

**Reproduction:**

```clojure
(require '[dev.onionpancakes.chassis.core :as chassis])
(chassis/html [:div "hello"])
```

**Observed error:**

```text
IllegalArgumentException: No matching field found: pollFirst for class java.util.ArrayDeque
```

Chassis' depth-first serializer calls the standard `Deque.pollFirst` method.
Jolt's `java.util.ArrayDeque` shim supports `.poll`, which has the same
head-removal semantics (including returning nil when empty), but does not expose
`.pollFirst`. This is a Jolt compatibility gap rather than a Chassis defect.

**Workaround in this project:** `llm-proxy.jolt-shims` uses the documented
`jolt.host/extend-class!` seam to add `pollFirst` as a delegation to `.poll`.
The dashboard can consequently call `chassis/html` unchanged instead of
maintaining a copy of Chassis' traversal. The extension does not use
`:override`, so a future built-in Jolt implementation takes precedence.


## JI-5: pinned Ebb sleep never completes on Jolt v0.8.21

**Upstream:** <https://github.com/jlt-commons/ebb> (dependency compatibility;
not a Clojure value-semantics divergence)

**Status:** Confirmed on Jolt v0.8.21
`5b46858a171f30949f5b09eb484bc6c5ff93c6ea`, Ebb
`cd3b36ddd07be14f81c3690d084a44adcb08a5c2`, Fedora 44 x86_64. Inspection of
Ebb main `32756cfa6d996f5a221065660cfbdc063794c258` still finds the same
wall-clock deadline in `ebb.impl.util/schedule!`.

**Reproduction:** In a temporary project with only the pinned Ebb dependency
(and without this application's shims), run:

```clojure
(require '[ebb.core :as m])
(let [fired (atom false)
      cancel ((m/sleep 10) (fn [_] (reset! fired true)) (fn [_] nil))]
  (Thread/sleep 100)
  (prn @fired)
  (cancel))
;; false; expected true (also false after longer waits).
```

Jolt v0.8.21 intentionally changed runtime deadlines to monotonic
milliseconds. Ebb's private `timer-at!` binding calls the Scheme
`jolt-timer-at!` seam, but `schedule!` supplies
`(+ (System/currentTimeMillis) ms)`. This epoch timestamp is far in the future
on the monotonic timeline, so the timer does not fire. The cancellation
callback still works. The dashboard consequently renders its initial snapshot
but its sampler never publishes subsequent request counts.

**Suggested upstream fix:** construct the deadline on Jolt's monotonic clock
(e.g. the documented `jolt.io-poller/deadline-ms` seam) rather than passing a
wall timestamp into a private runtime procedure. Test completion, cancellation,
and concurrent sleeps on v0.8.21 or newer.

**Project workaround:** `llm-proxy.jolt-shims` adapts only the pinned Ebb
`timer-at!` var from wall to monotonic milliseconds, installed once. It does
not change the runtime timer used by other libraries. Remove the adapter when
updating Ebb to an upstream fix; it assumes this exact pinned implementation.
The dashboard sampler test passes, and standalone default/optimized/dev/
shake-requested builds all publish an updated count within 1.2 seconds with
the default one-second sampler interval.

## JI-6: embedded text resources cost approximately 5× their payload in default boots

**Upstream:** <https://github.com/jolt-lang/jolt>

**Status:** Reproduced on Jolt v0.8.21 `5b46858a` with both stock and
Jolt-patched Chez 10.4.1. Memory/code-generation improvement request, not a
Clojure semantic divergence. Automatic dependency embedding itself is
intentional (v0.8.17, #1232), and v0.8.18 added `:exclude-resources` (#1247).

**Minimal reproduction:** See the complete resource-only project and commands
in [MEMORY-REGRESSION.md](MEMORY-REGRESSION.md#minimal-reproduction-no-proxy-or-external-libraries).
It uses no external libraries: an empty `:local/root` dependency has only a
4 MiB ASCII `unused.ext.js`; the application never reads the resource and
prints static-generation bytes after `(System/gc)`.

| Mode | With 4 MiB resource | Excluded | Added static heap |
|------|--------------------:|---------:|------------------:|
| Default vfasl | 81,423,584 bytes | 60,451,184 bytes | 20,972,400 bytes (~5×) |
| `--boot plain` | 45,105,200 bytes | 40,910,784 bytes | 4,194,416 bytes (~1×) |

**Cause:** `bld-bytes-lit` in `host/chez/build.ss` / `ei-bytes-lit` emits UTF-8
text as `(string->utf8 "...")`. Vfasl loads the compiled constructor's Chez
UTF-32 string into the static generation, which ordinary full collection does
not reclaim. The embedded registry additionally retains the UTF-8 bytevector.
For this proxy, the transitive 11,743,241-byte js-joda extern adds 58,716,752
static bytes and approximately 67.8 MiB process RSS. A filename-based exclusion
removes it without removing the server's Datastar bundle.

**Suggested improvement:** represent embedded UTF-8 resources without a
permanent UTF-32 constructor literal in the static boot (e.g. byte-oriented
storage), with a regression gate comparing resource bytes against added static
heap. Consider reporting large embedded resource names/sizes during build.
Do not universally exclude `.js` files: applications can intentionally serve
such assets, and the current explicit-exclusion design is valid.

**Project workaround tested, not yet set in production deps:**
`:jolt/build {:embed ["resources"] :exclude-resources ["**/*.ext.js"]}`.
HTTP, licenses, dashboard sampling, Datastar resources, nREPL, and Orchard's
docs resource passed in standalone filtered builds. Do not blanket-exclude all
dependency files: runtime-loading/editor features can need them.

## JI-7: upstream Nix package does not apply Jolt's Chez kernel patches

**Upstream:** <https://github.com/jolt-lang/jolt> (`flake.nix` packaging)

**Status:** Confirmed on v0.8.21. Its flake builds Jolt with `pkgs.chez` directly;
Nix derivation `5l1warrz3hpgbqrp27n6bmvdpjqdl62d-jolt-5b46858.drv` uses stock
Chez 10.4.1, with no `host/chez/chez-patches` additions. The built kernel reports
`(foreign-entry? "(cs)lockfree_activation") => #f`, whereas applying all four
patches via an experimental Chez `overrideAttrs` yields `#t`.

The release workflows use `ci/apply-chez-patches.sh`; the v0.8.21 release notes
attribute some runtime/FFI/memory improvements to those patches. The Nix
package therefore does not reproduce that patched-kernel release configuration.
This is a packaging parity gap, not proof of a crash or value-semantics bug.

**Controlled measurement:** Same project source, same nixpkgs, same Jolt commit,
`--opt --direct-link`, default boot/resources: stock kernel 232.4 MiB HTTP-only
idle RSS, patched kernel 218.7 MiB. Full GC reduces the patched diagnostic to
210.4 MiB but leaves the resource's large static constants. Missing patches
are thus a secondary contributor, **not the main RSS regression** (JI-6).

**Suggested fix:** have Jolt's Nix package override `pkgs.chez` with the four
versioned upstream patches, rebuilding both the kernel and boot files together.
Gate it with the kernel marker and footprint/FFI tests. The experiment remains
under ignored `target/memory-diagnosis/`; this project's production flake was
not changed by the diagnosis.

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
`JOLT-GOTCHAS.md` §7.

### AOT-cache observations

The stale/mixed AOT-cache symptoms in `JOLT-GOTCHAS.md` §6 are operationally
important but currently lack a minimal sequence that starts from an empty temp
project/cache and deterministically produces the wrong result on current Jolt.
Do not file the narrative as an upstream bug. First produce a script that owns
its temporary `HOME`, edits one namespace, runs a fixed sequence of fresh Jolt
processes, and asserts stale output; then retest v0.7.20 and main.
