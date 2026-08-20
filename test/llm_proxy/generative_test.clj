(ns llm-proxy.generative-test
  "Bounded property checks using test.check and Malli generators. Keep the
  test count modest: these protect structural invariants, not fuzz throughput."
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [malli.core :as m]
            [malli.generator :as mg]
            [codex.continuation :as continuation]
            [codex.translate :as translate]
            [llm-proxy.proxy :as proxy]
            [llm-proxy.schema :as schema]))

(defn- passes? [result]
  (:pass? result))

(def valid-response-request-gen
  (mg/generator schema/responses-request))

(deftest generated-responses-requests-translate-structurally
  (is (passes?
       (tc/quick-check
        40
        (prop/for-all [request valid-response-request-gen]
          (try
            (let [[prepared _ _]
                  (translate/prepare-responses (json/write-str request))]
              (and (m/validate schema/responses-request request)
                   (map? prepared)))
            (catch Throwable _ false)))))))

(deftest unknown-response-fields-survive-preparation
  (is (passes?
       (tc/quick-check
        40
        (prop/for-all [request valid-response-request-gen
                       marker (gen/choose 0 1000000)]
          (let [[prepared _ _]
                (translate/prepare-responses
                 (json/write-str (assoc request "future_upstream_field"
                                        {"marker" marker})))]
            (= {"marker" marker} (get prepared "future_upstream_field"))))))))

(deftest input-normalization-is-idempotent
  (let [items [{:role "user" :content "text"}
               {:role "assistant" :content [{:type :output_text :text "a"}
                                              {:type :text :text "b"}]}
               {:type "function_call" :name "f" :status "completed"}]]
    (is (passes?
         (tc/quick-check
          40
          (prop/for-all [indexes (gen/vector (gen/choose 0 2) 0 20)]
            (let [input (mapv #(nth items %) indexes)
                  normalized (continuation/normalize-input-list input)]
              (= normalized (continuation/normalize-input-list normalized)))))))))

(deftest continuation-delta-never-reintroduces-old-input
  (let [old {:role "user" :content "old"}
        assistant {:role "assistant" :content "server"}
        new {:role "user" :content "new"}
        state {:last-request-body {:model "m" :input [old] :stream true}
               :last-response-id "resp_1"
               :last-response-items [assistant]}]
    (is (passes?
         (tc/quick-check
          40
          (prop/for-all [extra-count (gen/choose 1 20)]
            (let [body {:model "m"
                        :stream true
                        :input (vec (concat [old assistant]
                                            (repeat extra-count new)))}
                  [delta ok?] (continuation/build-delta-request body state)]
              (and ok?
                   (every? #(not= old %) (:input delta))))))))))

(deftest invalid-session-characters-are-always-rejected
  (is (passes?
       (tc/quick-check
        40
        (prop/for-all [bad (gen/elements ["\r" "\n" " " "/" "?" "😀" "\u0000"])]
          (= :invalid-session-id
             (try
               ;; Keep the invalid character internal: normalize-session-id
               ;; intentionally trims leading/trailing whitespace first.
               (proxy/normalize-session-id (str "safe" bad "suffix"))
               nil
               (catch Throwable e (:type (ex-data e))))))))))
