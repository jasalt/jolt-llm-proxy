(ns codex.collect-test
  (:require [clojure.test :refer [deftest is]]
            [codex.collect :as collect]))

(defn- replay
  "Build a fake event-source read fn from a vector of events."
  [events]
  (fn [emit] (doseq [event events] (emit event))))

(defn- text-delta [s]
  {:name "response.output_text.delta" :data {:delta s}})

(defn- completed [response]
  {:name "response.completed" :data {:response response}})

(deftest collects-chat-text-and-usage
  (let [[response meta]
        (collect/collect-chat
          (replay [(text-delta "hello ")
                   (text-delta "world")
                   (completed {:id "resp_1"
                               :usage {:input_tokens 3 :output_tokens 5}
                               :output [{:type "message" :role "assistant"}]})])
          "gpt-5.4")]
    (is (= "resp_1" (:response-id meta)))
    (is (:completed meta))
    (is (= 1 (count (:choices response))))
    (is (= "hello world"
           (get-in response [:choices 0 :message :content])))
    (is (= "stop" (get-in response [:choices 0 :finish_reason])))
    (is (= 3 (get-in response [:usage :prompt_tokens])))
    (is (= 8 (get-in response [:usage :total_tokens])))))

(deftest collects-chat-tool-calls
  (let [[response meta]
        (collect/collect-chat
          (replay [{:name "response.output_item.added"
                    :data {:item {:type "function_call" :call_id "call_1"
                                  :id "fc_1" :name "get_weather"}}}
                   {:name "response.function_call_arguments.delta"
                    :data {:call_id "call_1" :delta "{\"city\":"}}
                   {:name "response.function_call_arguments.delta"
                    :data {:call_id "call_1" :delta "\"oslo\"}"}}
                   (completed {:id "resp_2" :output []})])
          "gpt-5.4")]
    (is (= "resp_2" (:response-id meta)))
    (is (= "tool_calls" (get-in response [:choices 0 :finish_reason])))
    (is (= [{:id "call_1" :type "function"
             :function {:name "get_weather" :arguments "{\"city\":\"oslo\"}"}}]
           (get-in response [:choices 0 :message :tool_calls])))
    (is (nil? (get-in response [:choices 0 :message :content])))))

(deftest chat-collector-throws-on-failed-response
  (is (thrown? Throwable
               (collect/collect-chat
                 (replay [(text-delta "partial")
                          {:name "response.failed"
                           :data {:error {:message "upstream exploded"}}}])
                 "gpt-5.4")))
  (is (thrown? Throwable
               (collect/collect-chat
                 (replay [(text-delta "no terminal")])
                 "gpt-5.4"))))

(deftest collect-response-fabricates-output-from-deltas
  (let [[response meta]
        (collect/collect-response
          (replay [(text-delta "hi")
                   (completed {:id "resp_3" :output []})]))]
    (is (= "resp_3" (:response-id meta)))
    (is (:completed meta))
    (is (= "hi" (:output_text response)))
    (is (= [{:type "message" :role "assistant" :status "completed"
             :content [{:type "output_text" :text "hi"}]}]
           (:output response)))))

(deftest collect-response-requires-terminal-event
  (is (thrown? Throwable
               (collect/collect-response
                 (replay [(text-delta "orphan")])))))
