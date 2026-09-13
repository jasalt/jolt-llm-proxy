(ns llm-proxy.transport-sse-test
  (:require [clojure.test :refer [deftest is]]
            [ebb.core :as m]
            [llm-proxy.flow :as flow]
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

(deftest acquired-sse-source-streams-events-through-the-ebb-lifecycle
  (let [input (str "event: response.created\n"
                   "data: {\"type\":\"ignored\",\"id\":1}\n\n"
                   "data: {\"type\":\"response.completed\",\"id\":2}\n\n")
        consume (fn [next-event]
                  (m/sp
                    (loop [events []]
                      (let [{:keys [status value terminal]} (m/? (next-event))]
                        (if (= :event status)
                          (recur (conj events value))
                          {:events events :terminal terminal})))))]
    (with-redefs [transport/open-upstream
                  (fn [& _] (java.io.ByteArrayInputStream. (.getBytes input "UTF-8")))]
      (is (= {:events [{:name "response.created"
                        :data {:type "ignored" :id 1}}
                       {:name "response.completed"
                        :data {:type "response.completed" :id 2}}]
              :terminal :eof}
             (m/? (flow/with-event-source
                     (transport/acquire {} {} "session")
                     consume)))))))
