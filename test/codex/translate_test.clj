(ns codex.translate-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.data.json :as json]
            [codex.translate :as translate]))

(deftest translates-chat-request
  (let [[request model stream]
        (translate/chat-to-responses
          (json/write-str {:model "gpt-5.4" :stream false
                           :messages [{:role "system" :content "brief"}
                                      {:role "user" :content "hello"}]}))]
    (is (= "gpt-5.4" model))
    (is (false? stream))
    (is (= false (:store request)))
    (is (= true (:stream request)))
    (is (= "brief" (:instructions request)))
    (is (= [{:role "user" :content "hello"}] (:input request)))))

(deftest validates-required-chat-fields
  (is (thrown? Throwable (translate/chat-to-responses "{}")))
  (is (thrown? Throwable
               (translate/chat-to-responses
                 (json/write-str {:model 1 :messages []}))))
  (is (thrown? Throwable
               (translate/chat-to-responses
                 (json/write-str {:model "m" :messages ["bad"]})))))

(deftest prepares-responses-request
  (let [[request model stream]
        (translate/prepare-responses
          (json/write-str {:model "gpt-5.4" :input "hello" :stream false
                           :max_output_tokens 100}))]
    (is (= "gpt-5.4" model))
    (is (false? stream))
    (is (= [{:role "user" :content "hello"}] (:input request)))
    (is (not (contains? request :max_output_tokens)))
    (is (= false (:store request)))))

(deftest validates-responses-shape
  (is (thrown? Throwable
               (translate/prepare-responses
                 (json/write-str {:model 2 :input "hello"}))))
  (is (thrown? Throwable
               (translate/prepare-responses
                 (json/write-str {:model "m" :input {}})))))
