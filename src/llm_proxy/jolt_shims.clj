(ns llm-proxy.jolt-shims
  "Application-owned compatibility shims for the pinned libraries on Jolt."
  (:require [jolt.host :as host]
            [ebb.impl.util]))

;; ArrayDeque.poll and pollFirst have identical head-removal semantics, including
;; returning nil for an empty deque. This lets Chassis keep its upstream traversal.
;; The non-override extension becomes a no-op if Jolt supplies pollFirst itself.
(host/extend-class! "java.util.ArrayDeque"
  {:methods {"pollFirst" (fn [deque] (.poll deque))}})

;; Pinned Ebb passes Unix epoch milliseconds to Jolt's private timer seam.
;; Jolt 0.8.21 changed that seam to monotonic milliseconds. Adapt only Ebb's
;; binding, not the runtime timer (which other libraries already use correctly).
;; Keep this until Ebb's schedule! uses monotonic deadlines upstream (JI-5).
(defonce ^:private ebb-timer-adapter
  (do
    (alter-var-root (ns-resolve 'ebb.impl.util 'timer-at!)
      (fn [timer]
        (fn [wall-deadline callback]
          (let [remaining-ms (- wall-deadline (System/currentTimeMillis))
                monotonic-ms (quot (System/nanoTime) 1000000)]
            (timer (+ monotonic-ms remaining-ms) callback)))))
    true))
