(ns llm-proxy.flow-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.core.async :as async]
            [ebb.core :as m]
            [llm-proxy.flow :as flow]))

(defn- await!
  [pred]
  (loop [remaining 100]
    (cond
      (pred) true
      (zero? remaining) false
      :else (do (Thread/sleep 10)
                (recur (dec remaining))))))

(defn- observe-cancel
  [source cancellations]
  (fn [notify terminate]
    (let [iterator (source notify terminate)]
      (reify clojure.lang.IFn
        (invoke [_]
          (swap! cancellations inc)
          (iterator))
        clojure.lang.IDeref
        (deref [_] (deref iterator))))))

(deftest task-runner-reports-success-and-failure
  (testing "successful tasks"
    (let [seen (atom [])]
      (flow/start-task! (m/sp :done)
                        {:on-success #(swap! seen conj [:success %])
                         :on-failure #(swap! seen conj [:failure %])
                         :on-cancel #(swap! seen conj [:cancel])})
      (is (= [[:success :done]] @seen))))
  (testing "failed tasks"
    (let [failure (ex-info "boom" {:type :test})
          seen (atom [])]
      (flow/start-task! (m/sp (throw failure))
                        {:on-success #(swap! seen conj [:success %])
                         :on-failure #(swap! seen conj [:failure %])
                         :on-cancel #(swap! seen conj [:cancel])})
      (is (= [[:failure failure]] @seen)))))

(deftest task-runner-classifies-cancellation-separately
  (let [seen (atom [])
        underlying-cancels (atom 0)
        task (fn [_success failure]
               (fn []
                 (swap! underlying-cancels inc)
                 (failure (m/cancelled "test cancellation"))))
        cancel! (flow/start-task! task
                  {:on-success #(swap! seen conj [:success %])
                   :on-failure #(swap! seen conj [:failure %])
                   :on-cancel #(swap! seen conj [:cancel])})]
    (cancel!)
    (cancel!)
    (is (= 1 @underlying-cancels))
    (is (= [[:cancel]] @seen))))

(deftest task-runner-settles-only-once
  (let [seen (atom [])
        failure (ex-info "late failure" {})
        malformed-task (fn [success fail]
                         (success :first)
                         (fail failure)
                         (fn [] nil))]
    (flow/start-task! malformed-task
                      {:on-success #(swap! seen conj [:success %])
                       :on-failure #(swap! seen conj [:failure %])})
    (is (= [[:success :first]] @seen))))

(deftest task-bridge-emits-result-and-closes-once
  (let [cleanups (atom 0)
        closes (atom 0)
        close! async/close!]
    (with-redefs [async/close! (fn [ch]
                                (swap! closes inc)
                                (close! ch))]
      (let [{:keys [body cancel!]} (flow/task->channel
                                    (m/sp "result")
                                    {:cleanup #(swap! cleanups inc)})]
        (is (= "result" (async/<!! body)))
        (is (nil? (async/<!! body)))
        (cancel!)
        (cancel!)
        (is (= 1 @cleanups))
        (is (= 1 @closes))))))

(deftest flow-bridge-preserves-order-and-closes
  (let [{:keys [body]} (flow/flow->channel (m/seed [1 2 3]))]
    (is (= [1 2 3 nil]
           [(async/<!! body) (async/<!! body)
            (async/<!! body) (async/<!! body)]))))

(deftest flow-bridge-converts-failure-to-stream-values
  (let [failure (ex-info "upstream secret" {:type :test})
        failures (atom [])
        source (m/ap (throw failure))
        {:keys [body]} (flow/flow->channel
                         source
                         {:error-values (fn [_] ["safe streaming error"])
                          :on-failure #(swap! failures conj %)})]
    (is (= "safe streaming error" (async/<!! body)))
    (is (nil? (async/<!! body)))
    (is (= [failure] @failures))))

(deftest downstream-close-cancels-source-without-failure
  (let [ch (async/chan)
        source-cancels (atom 0)
        cleanups (atom 0)
        cancellations (atom 0)
        failures (atom [])]
    ;; This is the behavior ring-chez-adapter uses when its HTTP write fails.
    (async/close! ch)
    (let [{:keys [body cancel!]}
          (flow/flow->channel
            (observe-cancel (m/seed [:next]) source-cancels)
            {:channel ch
             :cleanup #(swap! cleanups inc)
             :on-cancel #(swap! cancellations inc)
             :on-failure #(swap! failures conj %)})]
      (is (await! #(= 1 @cleanups)))
      (is (identical? ch body))
      (is (= 1 @source-cancels))
      (is (= 1 @cancellations))
      (is (= [] @failures))
      (cancel!)
      (cancel!)
      (is (= 1 @source-cancels))
      (is (= 1 @cleanups)))))

(defn- settle-task
  [task]
  (let [result (promise)
        cancel! (flow/start-task! task
                                  {:on-success #(deliver result [:success %])
                                   :on-failure #(deliver result [:failure %])
                                   :on-cancel #(deliver result [:cancel])})]
    [result cancel!]))

(defn- await-result
  [result]
  (when (await! #(realized? result))
    @result))

(defn- fake-event-source
  [produce]
  (let [aborts (atom 0)
        releases (atom [])]
    {:acquire (m/sp {:produce produce
                     :abort! #(swap! aborts inc)
                     :release! #(swap! releases conj %)})
     :aborts aborts
     :releases releases}))

(defn- collect-events
  [next-event]
  (m/sp
    (loop [events []]
      (let [{:keys [status value terminal]} (m/? (next-event))]
        (if (= :event status)
          (recur (conj events value))
          {:events events :terminal terminal})))))

(deftest rendezvous-event-source-is-ordered-lossless-and-terminates
  (let [{:keys [acquire aborts releases]}
        (fake-event-source
          (fn [emit!]
            (m/sp
              (doseq [event [1 2 3]]
                (m/? (emit! event)))
              :eof)))
        [result _] (settle-task (flow/with-event-source acquire collect-events))]
    (is (= [:success {:events [1 2 3] :terminal :eof}]
           (await-result result)))
    (is (= 0 @aborts))
    (is (= [{:status :completed
             :terminal :eof
             :result {:events [1 2 3] :terminal :eof}}]
           @releases))))

(deftest rendezvous-event-source-backpressures-producer
  (let [first-event-seen (promise)
        continue (m/rdv)
        emitted (atom 0)
        {:keys [acquire releases]}
        (fake-event-source
          (fn [emit!]
            (m/sp
              (m/? (emit! :first))
              (swap! emitted inc)
              (m/? (emit! :second))
              (swap! emitted inc)
              :eof)))
        consume (fn [next-event]
                  (m/sp
                    (let [first (m/? (next-event))]
                      (deliver first-event-seen first)
                      (m/? continue)
                      [(m/? (next-event)) (m/? (next-event))])))
        [result _] (settle-task (flow/with-event-source acquire consume))]
    (is (= {:status :event :value :first}
           (await-result first-event-seen)))
    ;; The producer is parked at the second emit until the consumer pulls it.
    (is (= 1 @emitted))
    (m/? (continue :go))
    (is (= [:success [{:status :event :value :second}
                      {:status :done :terminal :eof}]]
           (await-result result)))
    (is (= 1 (count @releases)))))

(deftest rendezvous-event-source-propagates-failure-and-cancellation
  (testing "an asynchronous producer failure reaches the consumer"
    (let [failure (ex-info "upstream failure" {:type :test})
          {:keys [acquire aborts releases]}
          (fake-event-source
            (fn [emit!]
              (m/sp
                (m/? (emit! :first))
                (throw failure))))
          [result _] (settle-task (flow/with-event-source acquire collect-events))]
      (is (= [:failure failure] (await-result result)))
      (is (= 1 @aborts))
      (is (= :failed (:status (first @releases))))))
  (testing "cancelling a blocked consumer cancels the producer and releases once"
    (let [first-event-seen (promise)
          producer-cancelled (atom 0)
          {:keys [acquire aborts releases]}
          (fake-event-source
            (fn [emit!]
              (m/sp
                (try
                  (m/? (emit! :first))
                  (m/? (emit! :second))
                  :unreachable
                  (catch Throwable failure
                    (when (m/cancelled? failure)
                      (swap! producer-cancelled inc))
                    (throw failure))))))
          consume (fn [next-event]
                    (m/sp
                      (deliver first-event-seen (m/? (next-event)))
                      (m/? m/never)))
          [result cancel!] (settle-task (flow/with-event-source acquire consume))]
      (is (= {:status :event :value :first}
             (await-result first-event-seen)))
      (cancel!)
      (is (= [:cancel] (await-result result)))
      (is (= 1 @producer-cancelled))
      (is (= 1 @aborts))
      (is (= 1 (count @releases)))
      (is (= :cancelled (:status (first @releases)))))))
