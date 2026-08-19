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

(deftest rejects-malformed-nested-options
  ;; Non-boolean stream on both endpoints.
  (is (thrown? Throwable
               (translate/chat-to-responses
                 (json/write-str {:model "m" :stream "yes"
                                  :messages [{:role "user" :content "hi"}]}))))
  (is (thrown? Throwable
               (translate/prepare-responses
                 (json/write-str {:model "m" :input "hi" :stream 1}))))
  ;; tools must be a bounded array of objects (both endpoints).
  (doseq [body [{:model "m" :messages [{:role "user" :content "hi"}]
                 :tools [{:type "function" :function {:name "f"}} "bad"]}
                {:model "m" :messages [{:role "user" :content "hi"}]
                 :tools {}}]]
    (is (thrown? Throwable (translate/chat-to-responses (json/write-str body)))))
  (is (thrown? Throwable
               (translate/prepare-responses
                 (json/write-str {:model "m" :input "hi"
                                  :tools [{:type "function"} "bad"]}))))
  (is (thrown? Throwable
               (translate/chat-to-responses
                 (json/write-str
                   {:model "m"
                    :messages [{:role "user" :content "hi"}]
                    :tools (vec (repeat 129 {:type "function"
                                             :function {:name "f"}}))}))))
  ;; Chat message shapes.
  (is (thrown? Throwable
               (translate/chat-to-responses
                 (json/write-str {:model "m"
                                  :messages [{:role "user" :content ["bad"]}]}))))
  (is (thrown? Throwable
               (translate/chat-to-responses
                 (json/write-str {:model "m"
                                  :messages [{:role "assistant" :content "hi"
                                              :tool_calls ["bad"]}]}))))
  ;; Input items must be objects.
  (is (thrown? Throwable
               (translate/prepare-responses
                 (json/write-str {:model "m" :input ["bad"]})))))

(deftest responses-key-policy
  (let [[request _model _stream]
        (translate/prepare-responses
          (json/write-str {:model "m" :input [{:role "user" :content "hi"}]
                           :previous_response_id "resp_1"
                           :temperature 0.5
                           :metadata {:trace "abc"}
                           :totally_unknown_field {:x 1}}))]
    ;; Recognized keys become keywords.
    (is (= "resp_1" (:previous_response_id request)))
    (is (= 0.5 (:temperature request)))
    ;; Input items are keywordized recursively for continuation matching.
    (is (= [{:role "user" :content "hi"}] (:input request)))
    ;; Unknown top-level keys pass through upstream with string keys (nested
    ;; values keep their parsed string keys too).
    (is (= {"x" 1} (get request "totally_unknown_field")))
    (is (nil? (get request :totally_unknown_field)))))

(deftest chat-drops-unknown-top-level-keys
  (let [[request _model _stream]
        (translate/chat-to-responses
          (json/write-str {:model "m"
                           :messages [{:role "user" :content "hi"}]
                           :custom_field "drop me"
                           :nested {:arbitrary "keys"}}))]
    (is (not (contains? request "custom_field")))
    (is (not (contains? request :custom_field)))
    (is (= [{:role "user" :content "hi"}] (:input request)))))

(deftest prepared-input-supports-delta-continuation
  ;; Input items keywordized by prepare-responses must keep matching prefixes
  ;; in codex.continuation (regression guard for the string-key parser).
  (require '[codex.continuation :as continuation])
  (let [[turn1 _ _] (translate/prepare-responses
                      (json/write-str {:model "m"
                                       :input [{:role "user" :content "hi"}]}))
        [turn2 _ _] (translate/prepare-responses
                      (json/write-str
                        {:model "m"
                         :input [{:role "user" :content "hi"}
                                 {:role "assistant" :content "hello"}
                                 {:role "user" :content "how are you"}]}))
        cont {:last-request-body turn1
              :last-response-id "resp_1"
              :last-response-items [{:role "assistant" :content "hello"}]}
        [delta ok?] (continuation/build-delta-request turn2 cont)]
    (is ok?)
    (is (= "resp_1" (:previous_response_id delta)))
    (is (= [{:role "user" :content "how are you"}] (:input delta)))))
