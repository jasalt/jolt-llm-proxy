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
