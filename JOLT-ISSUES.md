# JOLT-ISSUES.md

## JI-2 — `java.util.ArrayDeque.pollFirst` is unavailable in Jolt

**Observed on:** Jolt `d782ba6` (v0.8.7 baseline)

**Reproduction:**

```clojure
(require '[dev.onionpancakes.chassis.core :as chassis])
(chassis/html [:div "hello"])
```

**Observed error:**

```text
IllegalArgumentException: No matching field found: pollFirst for class java.util.ArrayDeque
```

Chassis v1.0.365 uses `.pollFirst` in its depth-first serializer. Jolt's
`java.util.ArrayDeque` shim supports the equivalent `.removeFirst` operation
but does not expose `.pollFirst`, so the otherwise portable library fails at
runtime.

**Workaround in this project:** use Chassis for the Hiccup tree, token
serialization, and escaping, while dynamically traversing Chassis nodes with an
equivalent local traversal using `.removeFirst`. This keeps the workaround at
the integration boundary and does not modify the dependency.
