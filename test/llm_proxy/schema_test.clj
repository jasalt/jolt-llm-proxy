(ns llm-proxy.schema-test
  (:require [clojure.test :refer [deftest is testing]]
            [malli.core :as m]
            [llm-proxy.schema :as schema]))

(deftest validates-project-request-schemas
  (is (m/validate schema/chat-request
                  {"model" "m"
                   "messages" [{"role" "user" "content" "hi"}]
                   "unknown" {"nested" "is allowed"}}))
  (is (not (m/validate schema/chat-request
                       {"model" ""
                        "messages" [{"role" "user"}]})))
  (is (not (m/validate schema/chat-request
                       {"model" "m"
                        "messages" [{"role" "user"}]
                        "stream" "yes"})))
  (is (not (m/validate schema/chat-request
                       {"model" "m"
                        "messages" [{"role" "user"}]
                        "tools" (vec (repeat 129 {}))})))
  (is (m/validate schema/responses-request
                  {"model" "m" "input" [{"role" "user" "content" "hi"}]
                   "future_upstream_field" {"kept" true}}))
  (is (not (m/validate schema/responses-request
                       {"model" "m" "input" ["not an object"]}))))

(deftest explain-is-structured-and-local-only
  (let [bad {"model" "m" "messages" [{"role" "user"}] "stream" "yes"}
        explanation (m/explain schema/chat-request bad)]
    (is (map? explanation))
    (is (seq (:errors explanation)))
    (is (some #(= ["stream"] (:in %)) (:errors explanation))))
  (let [error (try
                (schema/validate-chat! {"model" "" "messages" []})
                nil
                (catch Throwable e e))]
    (is (= "invalid chat request" (ex-message error)))
    (is (map? (:malli/explain (ex-data error))))))

(deftest jolt-supported-malli-core-vocabulary
  ;; Keep executable coverage for the forms documented by Jolt's malli-app
  ;; example and useful for future request schemas.
  (is (m/validate [:map {:closed true} ["id" :int]] {"id" 1}))
  (is (not (m/validate [:map {:closed true} ["id" :int]]
                       {"id" 1 "extra" true})))
  (is (m/validate [:tuple :string :int] ["id" 1]))
  (is (m/validate [:enum "auto" "required"] "auto"))
  (is (m/validate [:re "[a-z]+"] "valid"))
  (is (m/validate [:map-of :string :int] {"one" 1 "two" 2}))
  (is (m/validate [:multi {:dispatch (fn [value] (get value "type"))}
                   ["text" [:map ["type" [:= "text"]] ["text" :string]]]
                   ["image" [:map ["type" [:= "image"]] ["url" :string]]]]
                  {"type" "text" "text" "hello"})))
