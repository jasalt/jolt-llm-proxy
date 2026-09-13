(ns llm-proxy.jolt-shims
  "Application-owned additions to Jolt's partial Java compatibility surface."
  (:require [jolt.host :as host]))

;; ArrayDeque.poll and pollFirst have identical head-removal semantics, including
;; returning nil for an empty deque. This lets Chassis keep its upstream traversal.
;; The non-override extension becomes a no-op if Jolt supplies pollFirst itself.
(host/extend-class! "java.util.ArrayDeque"
  {:methods {"pollFirst" (fn [deque] (.poll deque))}})
