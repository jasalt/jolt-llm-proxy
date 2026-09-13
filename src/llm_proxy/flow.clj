(ns llm-proxy.flow
  "Application-neutral Ebb lifecycle helpers. Codex protocol interpretation does
  not belong here; this namespace only starts, cancels, and classifies tasks."
  (:require [ebb.core :as m]))

(defn cancelled?
  "True when `failure` represents normal Ebb cancellation. Kept behind the
  project boundary so callers do not depend on Ebb's cancellation encoding."
  [failure]
  (m/cancelled? failure))

(defn start-task!
  "Start an Ebb `task` and return an idempotent cancellation function.

  Options are callbacks `:on-success` (one argument), `:on-failure` (one
  argument), and `:on-cancel` (no arguments). Cancellation requested through
  the returned function, and Ebb cancellation raised by the task itself, are
  reported through `:on-cancel`, never `:on-failure`. Exactly one terminal
  callback runs, even if a malformed task invokes more than one callback."
  ([task] (start-task! task {}))
  ([task {:keys [on-success on-failure on-cancel]
          :or {on-success (fn [_] nil)
               on-failure (fn [_] nil)
               on-cancel (fn [] nil)}}]
   (let [settled? (atom false)
         cancel-requested? (atom false)
         task-cancel (atom (fn [] nil))
         settle! (fn [status value]
                   (when (compare-and-set! settled? false true)
                     (if (or @cancel-requested?
                             (and (= status :failure) (cancelled? value)))
                       (on-cancel)
                       (if (= status :success)
                         (on-success value)
                         (on-failure value)))))
         cancel (task #(settle! :success %)
                      #(settle! :failure %))]
     (reset! task-cancel cancel)
     (fn []
       (when (and (compare-and-set! cancel-requested? false true)
                  (not @settled?))
         (@task-cancel))
       nil))))
