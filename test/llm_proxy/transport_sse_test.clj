(ns llm-proxy.transport-sse-test
  (:require [clojure.test :refer [deftest is]]
            [llm-proxy.transport.sse :as transport]))

(deftest parses-named-and-inferred-events
  (let [events (atom [])
        input (str "event: response.created\n"
                   "data: {\"type\":\"ignored\",\"id\":1}\n\n"
                   "data: {\"type\":\"response.completed\"}\n\n"
                   "data: [DONE]\n\n")]
    (transport/read-events input #(swap! events conj %))
    (is (= ["response.created" "response.completed"]
           (mapv :name @events)))
    (is (= 1 (get-in @events [0 :data :id])))))

(deftest joins-multiline-data
  (let [events (atom [])]
    (transport/read-events
      "data: {\"type\":\"response.created\",\ndata: \"id\":2}\n\n"
      #(swap! events conj %))
    (is (= 2 (get-in @events [0 :data :id])))))
