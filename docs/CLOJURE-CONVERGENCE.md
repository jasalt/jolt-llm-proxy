# CLOJURE-CONVERGENCE.md

Under-documented behavioral divergences between Jolt (Clojure-on-Chez, non-JVM)
and reference Clojure, confirmed by comparing the Jolt REPL against Babashka
(`bb`, JVM-adjacent Clojure dialect) unless noted. Per `AGENTS.md`, these are
filed separately from `JOLT-ISSUES.md` (upstream Jolt/lib bugs) and
`JOLT-GOTCHAS.md` (surprising non-JVM quirks).

Each entry: the form, the reference (`bb`) result, the Jolt result, and whether
it is tracked in Jolt's `test/conformance/known-divergences.edn`.

---

## CONV-1: host tagged-tables are host wrappers, not Clojure maps

### Form

```clojure
(def tt (jolt.host/tagged-table :jolt/x))
(jolt.host/ref-put! tt :a 1)
(:a tt)        ;; keyword as function
(get tt :a)    ;; clojure.core/get
(ifn? tt)
```

### Reference (Babashka, on a defrecord / IPersistentMap-like object)

```console
bb -e '(defrecord R [a]) (def r (->R 5)) (println (:a r))'
;; => 5
```

On JVM Clojure a `defrecord` implements `IFn` (`r` is invoked as `(.valAt r :a)`),
and `clojure.lang.IPersistentMap` likewise, so `(:a m)` returns the value.

### Jolt

```console
jolt -e '(def tt (jolt.host/tagged-table :jolt/x)) (jolt.host/ref-put! tt :a 1)
          (println "ifn?" (ifn? tt) "get" (get tt :a) "kw" (:a tt))'
;; => ifn? false get nil kw nil
```

**Verdict.** Not a Clojure convergence issue. A tagged-table is a Jolt-specific
mutable host wrapper, not an implementation of `IPersistentMap` or `ILookup`.
There is no equivalent value to construct in Babashka, and comparing it to a
`defrecord` changes the tested type and its contracts. `(:a x)` returning nil
for an object that does not implement keyed lookup is therefore not evidence
that keyword invocation semantics diverge.

The current Jolt Host Interop documentation explicitly builds tagged-table
state with `jolt.host/ref-put!` and reads it with `jolt.host/ref-get`. The useful
remaining action is the low-priority `jolt-lang/http-client` documentation
clarification in `JOLT-ISSUES.md` JI-2, because that library exposes a raw TLS
stream using this representation.

**Tracked in `known-divergences.edn`?** Appropriately no: this is host API shape,
not a JVM/Jolt result difference for the same portable Clojure form and value.

**Confirmed by `bb`?** No. Babashka has no `jolt.host/tagged-table`; a defrecord
is not a valid control because it deliberately implements map lookup.

---

## CONV-2: `(ex-data e)` concern — not reproduced

The earlier note mixed ordinary host-thrown exceptions (where `ex-data` should
be nil) with `ex-info`. The direct same-form check succeeds on current Jolt:

```console
$ jolt -e '(try (throw (ex-info "boom" {:k 1})) \
             (catch Throwable e (prn [(ex-message e) (ex-data e)])))'
["boom" {:k 1}]
```

Babashka/JVM returns the same pair. Arithmetic exceptions return nil from
`ex-data` on both, as expected.

**Verdict.** Invalid as currently stated; do not file. A future claim about a
library-created tagged throwable must include the exact public constructor,
the expected contract for that distinct host type, and a standalone reproducer.
