# CLOJURE-CONVERGENCE.md

Under-documented behavioral divergences between Jolt (Clojure-on-Chez, non-JVM)
and reference Clojure, confirmed by comparing the Jolt REPL against Babashka
(`bb`, JVM-adjacent Clojure dialect) unless noted. Per `AGENTS.md`, these are
filed separately from `JOLT-ISSUES.md` (upstream Jolt/lib bugs) and
`JOLT-GOTCHAS.md` (surprising non-JVM quirks).

Each entry: the form, the reference (`bb`) result, the Jolt result, and whether
it is tracked in Jolt's `test/conformance/known-divergences.edn`.

---

## CONV-1: `IFn` not implemented on host tagged-tables — keyword-as-fn / `get` return `nil`

**Form**

```clojure
(def tt (jolt.host/tagged-table :jolt/x))
(jolt.host/ref-put! tt :a 1)
(:a tt)        ;; keyword as function
(get tt :a)    ;; clojure.core/get
(ifn? tt)
```

**Reference (Babashka, on a defrecord / IPersistentMap-like object)**

```
bb -e '(defrecord R [a]) (def r (->R 5)) (println (:a r))'
;; => 5
```
On JVM Clojure a `defrecord` implements `IFn` (`r` is invoked as `(.valAt r :a)`),
and `clojure.lang.IPersistentMap` likewise, so `(:a m)` returns the value.

**Jolt**

```
jolt -e '(def tt (jolt.host/tagged-table :jolt/x)) (jolt.host/ref-put! tt :a 1)
          (println "ifn?" (ifn? tt) "get" (get tt :a) "kw" (:a tt))'
;; => ifn? false get nil kw nil
```

**Verdict.** Genuine divergence. Host tagged-tables (`jolt.host/tagged-table`)
do not implement `IFn`, so `(:a tt)` and `(get tt :a)` silently return `nil`
instead of the stored value. The only accessor is `jolt.host/ref-get`.

**Tracked in `known-divergences.edn`?** No (not found; `grep keyword|tagged-table|IFn|ref-get`
returned no matching entry in the registry bundled with Jolt v0.7.13).

**Severity.** High for portability. Any JVM-Clojure code that keyword-calls a
host object (request maps, response maps, stream handles) reads `nil` silently
and then fails far from the cause with "class nil cannot be cast to class
clojure.lang.IFn". The `jolt-lang/http-client` library works around this
internally (`jolt.http.platform` uses `jolt.host/ref-get` everywhere) but does
not document the constraint for downstream consumers.

**Confirmed by `bb`?** Yes — keyword-as-fn returns the value under `bb`/JVM;
returns `nil` under Jolt. (The `jolt.host/tagged-table` constructor itself is
Jolt-specific, so the comparison is on the *language semantics* of calling a
keyword on an object that stores keyed values, not on the constructor.)

---

## CONV-2: `(ex-data e)` returns `nil` for host-thrown exceptions

**Form**

```clojure
(try (/ 1 0) (catch Throwable e (ex-data e)))
```

**Reference (Babashka/JVM)** — returns `{}` or the ex-info map for `ex-info`,
and `nil` for non-ex-info; `ex-message` returns the message string.

**Jolt** — `(ex-data e)` returns `nil` for arithmetic exceptions (as on JVM),
but for `jolt.host/tagged-table` exceptions built with `:jolt/ex-info`,
`(ex-data e)` returns `nil` unless the table is wrapped, while `(ex-message e)`
returns the `:message` field. (Observed during `diag2.clj` runs.)

**Verdict.** Apparent divergence for host-tagged ex-info objects; needs a
dedicated `bb` comparison to confirm against `ex-info` semantics before filing
upstream. Tentative; see `JOLT-ISSUES.md` JI-? if confirmed.

**Confirmed by `bb`?** Not yet — pending a focused `ex-info` comparison.
