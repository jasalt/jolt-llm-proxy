(ns llm-proxy.flow-test
  (:require [clojure.test :refer [deftest is testing]]
            [ebb.core :as m]
            [llm-proxy.flow :as flow]))

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
