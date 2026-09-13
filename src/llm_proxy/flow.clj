(ns llm-proxy.flow
  "Application-neutral Ebb lifecycle helpers. Codex protocol interpretation does
  not belong here; this namespace only starts, cancels, and classifies tasks."
  (:require [clojure.core.async :as async]
            [ebb.core :as m]))

(defn- downstream-closed []
  (ex-info "Ring response channel closed" {::cancelled true}))

(defn cancelled?
  "True when `failure` represents normal Ebb or downstream cancellation. Kept
  behind the project boundary so callers do not depend on either encoding."
  [failure]
  (or (m/cancelled? failure)
      (true? (::cancelled (ex-data failure)))))

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

(defn- put-task
  "Return a task that performs the genuinely blocking Ring-channel put on
  Ebb's blocking executor. A closed channel cancels the supervising task."
  [ch value cancel-ref]
  (m/sp
    (when-not (m/? (m/via m/blk (async/>!! ch value)))
      ((deref cancel-ref))
      (m/!)
      (throw (downstream-closed)))
    nil))

(defn- with-stream-error
  [task error-values ch cancel-ref]
  (m/sp
    (try
      (m/? task)
      (catch Throwable failure
        (when-not (cancelled? failure)
          (doseq [value (error-values failure)]
            (m/? (put-task ch value cancel-ref))))
        (throw failure)))))

(defn- bridge
  [task {:keys [channel error-values cleanup on-success on-failure on-cancel]
         :or {error-values (fn [_] [])
              cleanup (fn [] nil)
              on-success (fn [_] nil)
              on-failure (fn [_] nil)
              on-cancel (fn [] nil)}}]
  (let [ch (or channel (async/chan))
        finished? (atom false)
        task-cancel (atom (fn [] nil))
        finish! (fn []
                  (when (compare-and-set! finished? false true)
                    (try
                      (cleanup)
                      (finally (async/close! ch)))))
        cancel! (fn []
                  ((deref task-cancel))
                  (finish!))
        supervised (with-stream-error task error-values ch task-cancel)
        cancel-task! (start-task!
                       supervised
                       {:on-success (fn [value]
                                      (try (on-success value)
                                           (finally (finish!))))
                        :on-failure (fn [failure]
                                      (try (on-failure failure)
                                           (finally (finish!))))
                        :on-cancel (fn []
                                     (try (on-cancel)
                                          (finally (finish!))))})]
    (reset! task-cancel cancel-task!)
    {:body ch
     :cancel! cancel!}))

(defn task->channel
  "Start `task` and bridge its single non-nil result to a Ring channel body.

  Returns `{:body channel :cancel! fn}`. Options may provide an existing
  `:channel`, terminal callbacks, `:cleanup`, and `:error-values`, a function
  mapping a genuine failure to values written before the channel closes.
  Blocking puts run through `m/via m/blk`."
  ([task] (task->channel task {}))
  ([task options]
   (let [ch (or (:channel options) (async/chan))
         cancel-ref (atom (fn [] nil))
         producer (m/sp
                    (let [value (m/? task)]
                      (when (some? value)
                        (m/? (put-task ch value cancel-ref)))
                      value))
         result (bridge producer (assoc options :channel ch))]
     (reset! cancel-ref (:cancel! result))
     result)))

(defn flow->channel
  "Start discrete Ebb `source` and bridge its values, in order, to a Ring
  channel body. Returns `{:body channel :cancel! fn}` and accepts the same
  options as `task->channel`.

  Closing the downstream channel makes the next blocking put return false;
  that condition cancels the supervised Ebb task and therefore its source."
  ([source] (flow->channel source {}))
  ([source options]
   (let [ch (or (:channel options) (async/chan))
         cancel-ref (atom (fn [] nil))
         producer (m/reduce
                    (fn [_ _] nil)
                    nil
                    (m/ap
                      (let [value (m/?> source)]
                        (m/? (put-task ch value cancel-ref))
                        nil)))
         result (bridge producer (assoc options :channel ch))]
     (reset! cancel-ref (:cancel! result))
     result)))
