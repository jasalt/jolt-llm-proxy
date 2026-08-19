(ns codex.continuation-test
  (:require [clojure.test :refer [deftest is]]
            [codex.continuation :as continuation]))

(def turn-1
  {:model "gpt-5.4" :instructions "brief"
   :input [{:role "user" :content "hi"}] :stream true :store false})

(def turn-2
  (assoc turn-1 :input [{:role "user" :content "hi"}
                        {:role "assistant" :content "client replay"}
                        {:role "user" :content "next"}]))

(deftest builds-delta-for-an-extended-conversation
  (let [state {:last-request-body turn-1 :last-response-id "resp_1"
               :last-response-items [{:role "assistant" :content "server text"}]}
        [body ok?] (continuation/build-delta-request turn-2 state)]
    (is ok?)
    (is (= "resp_1" (:previous_response_id body)))
    (is (= [{:role "user" :content "next"}] (:input body)))))

(deftest refuses-delta-when-other-fields-change
  (let [state {:last-request-body turn-1 :last-response-id "resp_1"
               :last-response-items []}
        [_ ok?] (continuation/build-delta-request
                  (assoc turn-2 :instructions "different") state)]
    (is (false? ok?))))
