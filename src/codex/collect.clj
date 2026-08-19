(ns codex.collect
  "Event collectors that reduce Codex upstream events into OpenAI-shaped
  output: the chat collector (`/v1/chat/completions`, streaming and
  non-streaming) and the responses collector (`/v1/responses`). Pure event
  reducers plus the SSE streaming wrappers around them.

  Consumed events are `{:name <event-name> :data <parsed PMap>}` as produced by
  the `llm-proxy.transport.*` sources. Inbound Ring request/response maps are plain
  Clojure PMaps — use `(:headers req)` normally (NOT `jolt.host/ref-get`)."
  (:require [clojure.data.json :as json]
            [clojure.core.async :as async]
            [ring-chez.sse :as sse]
            [llm-proxy.id :as id]))

;; ---------------------------------------------------------------------------
;; Shared helpers
;; ---------------------------------------------------------------------------

(defn error-message
  "Extract a human-readable message from an upstream error event payload."
  [obj]
  (cond
    (string? (get obj :message)) (get obj :message)
    (map? (get obj :error))
    (or (get-in obj [:error :message]) (json/write-str (get obj :error)))
    (map? (get obj :response))
    (or (get-in obj [:response :error :message]) (json/write-str (get obj :response)))
    :else (json/write-str obj)))

(defn openai-usage
  "Translate Codex `:usage` into the OpenAI chat usage shape."
  [usage]
  (when (map? usage)
    (let [input (long (or (get usage :input_tokens) 0))
          output (long (or (get usage :output_tokens) 0))]
      (merge {:prompt_tokens input
              :completion_tokens output
              :total_tokens (+ input output)}
             (when (map? (get usage :input_tokens_details))
               {:prompt_tokens_details (get usage :input_tokens_details)})
             (when (map? (get usage :output_tokens_details))
               {:completion_tokens_details (get usage :output_tokens_details)})))))

(defn send-stream-error
  "Emit a `response.failed` SSE event shaped like the OpenAI Responses API."
  [ch message]
  (sse/send! ch
    {:event "response.failed"
     :data (json/write-str
             {:type "response.failed"
              :response {:status "failed"
                         :error {:type "server_error"
                                 :code "stream_disconnected"
                                 :message message}}})}))

;; ---------------------------------------------------------------------------
;; Chat collector: /v1/chat/completions
;; ---------------------------------------------------------------------------

(defn new-chat-collector []
  {:text (StringBuilder.)
   :reasoning (StringBuilder.)
   :calls []
   :call-index {}
   :usage nil
   :response-id nil
   :output []
   :completed false
   :incomplete false})

(defn- event-call-id [data]
  (or (get data :call_id) (get data :item_id)))

(defn consume-chat
  "Reduce one event into the chat collector accumulator. `emit` (optional)
  receives streaming deltas shaped for chat.completion.chunk payloads."
  [acc event emit]
  (let [name (:name event)
        data (:data event)]
    (cond
      (= name "response.output_text.delta")
      (let [delta (get data :delta)]
        (when (and delta (not= delta ""))
          (.append (:text acc) delta)
          (when emit (emit {:content delta})))
        acc)

      (or (= name "response.reasoning_summary_text.delta")
          (= name "response.reasoning_text.delta"))
      (let [delta (get data :delta)]
        (when (and delta (not= delta ""))
          (.append (:reasoning acc) delta)
          (when emit (emit {:reasoning_content delta})))
        acc)

      (or (= name "response.output_item.added")
          (= name "response.output_item.done"))
      (let [item (get data :item)]
        (cond
          (and (map? item) (= (:type item) "function_call"))
          (let [id (get item :call_id)
                item-id (get item :id)
                name_ (get item :name)
                idx (get (:call-index acc) id)]
            (if idx
              (do (when (and (= name "response.output_item.done")
                             (not (get-in acc [:calls idx :gotDelta])))
                    (let [sb (get-in acc [:calls idx :arguments])]
                      (.setLength sb 0)
                      (.append sb (get item :arguments ""))))
                  acc)
              (let [new-idx (count (:calls acc))
                    calls (conj (:calls acc)
                                {:id id :name name_ :arguments (StringBuilder.)
                                 :gotDelta false})
                    call-index (assoc (:call-index acc) id new-idx)
                    call-index (if (and item-id (not= item-id id))
                                 (assoc call-index item-id new-idx)
                                 call-index)]
                (when emit
                  (emit {:tool_calls
                         [{:index new-idx :id id :type "function"
                           :function {:name name_ :arguments ""}}]}))
                (assoc acc :calls calls :call-index call-index))))
          (and (= name "response.output_item.done") (map? item))
          (assoc acc :output (conj (:output acc) item))
          :else acc))

      (= name "response.function_call_arguments.delta")
      (let [id (event-call-id data)]
        (if-let [idx (get (:call-index acc) id)]
          (let [delta (get data :delta)]
            (when (and delta (not= delta ""))
              (.append (get-in acc [:calls idx :arguments]) delta)
              (when emit
                (emit {:tool_calls [{:index idx :function {:arguments delta}}]})))
            (assoc-in acc [:calls idx :gotDelta] true))
          acc))

      (= name "response.function_call_arguments.done")
      (let [id (event-call-id data)]
        (if-let [idx (get (:call-index acc) id)]
          (if (not (get-in acc [:calls idx :gotDelta]))
            (let [arguments (get data :arguments "")]
              (when emit
                (emit {:tool_calls [{:index idx :function {:arguments arguments}}]}))
              (assoc-in acc [:calls idx :arguments] (StringBuilder. arguments)))
            acc)
          acc))

      (or (= name "response.completed")
          (= name "response.done")
          (= name "response.incomplete"))
      (let [response (get data :response)]
        (if (map? response)
          (let [output (get response :output)]
            (assoc acc
                   :response-id (get response :id)
                   :usage (get response :usage)
                   :completed true
                   :incomplete (= name "response.incomplete")
                   :output (if (and (vector? output) (not (empty? output)))
                             output (:output acc))))
          acc))

      (or (= name "response.failed") (= name "error"))
      (throw (ex-info (str "Codex response failed: " (error-message data))
                      {:data data}))

      :else acc)))

(defn chat-response-meta
  "Extract the continuation/finalization metadata from a chat collector."
  [acc]
  {:response-id (:response-id acc)
   :items (:output acc)
   :completed (:completed acc)
   :incomplete (:incomplete acc)})

(defn stream-chat
  "Stream a chat completion over `ch` from the event source `read-fn`.
  Returns the response meta, with `:completed false` and `:error` set on
  upstream failure — a failed stream never reports success."
  [read-fn model ch]
  (let [id (str "chatcmpl-" (id/random-hex 16))
        created (long (/ (System/currentTimeMillis) 1000))
        collector (atom (new-chat-collector))
        emit (fn [delta]
               (sse/send! ch
                 {:event "message"
                  :data (json/write-str
                          {:id id :object "chat.completion.chunk"
                           :created created :model model
                           :choices [{:index 0 :delta delta :finish_reason nil}]})}))
        err (try
              (read-fn (fn [event]
                         (swap! collector #(consume-chat % event emit))))
              nil
              (catch Throwable t t))]
    (cond
      err
      (sse/send! ch {:event "message"
                     :data (json/write-str
                             {:error {:message "upstream stream failed"
                                      :type "upstream_error"
                                      :code "upstream_error"}})})

      (not (:completed @collector))
      (sse/send! ch {:event "message"
                     :data (json/write-str
                             {:error {:message "upstream stream closed before response.completed"
                                      :type "upstream_error"
                                      :code "upstream_error"}})})

      :else
      (let [finish (cond (:incomplete @collector) "length"
                         (pos? (count (:calls @collector))) "tool_calls"
                         :else "stop")]
        (sse/send! ch {:event "message"
                       :data (json/write-str
                               {:id id :object "chat.completion.chunk"
                                :created created :model model
                                :choices [{:index 0 :delta {} :finish_reason finish}]
                                :usage (openai-usage (:usage @collector))})})))
    (sse/send! ch {:event "message" :data "[DONE]"})
    (async/close! ch)
    (let [meta (chat-response-meta @collector)]
      (if err
        (assoc meta :completed false :error err)
        meta))))

(defn collect-chat
  "Collect a full (non-streaming) chat completion from `read-fn`.
  Returns `[response meta]`; throws if the stream ends without a terminal
  event."
  [read-fn model]
  (let [collector (atom (new-chat-collector))]
    (try
      (read-fn (fn [event] (swap! collector #(consume-chat % event nil))))
      (catch Throwable t (throw t)))
    (let [c @collector]
      (when (not (:completed c))
        (throw (ex-info "upstream stream closed before response.completed" {})))
      (let [message (merge {:role "assistant" :content (.toString (:text c))}
                           (when (pos? (.length (:reasoning c)))
                             {:reasoning_content (.toString (:reasoning c))})
                           (when (pos? (count (:calls c)))
                             (let [calls (vec (map (fn [call]
                                                     {:id (:id call) :type "function"
                                                      :function {:name (:name call)
                                                                 :arguments (.toString (:arguments call))}})
                                                   (:calls c)))]
                               (if (= (.length (:text c)) 0)
                                 {:tool_calls calls :content nil}
                                 {:tool_calls calls}))))]
        [{:id (str "chatcmpl-" (id/random-hex 16))
          :object "chat.completion"
          :created (long (/ (System/currentTimeMillis) 1000))
          :model model
          :choices [{:index 0 :message message :finish_reason
                     (cond (:incomplete c) "length"
                           (pos? (count (:calls c))) "tool_calls"
                           :else "stop")}]
          :usage (openai-usage (:usage c))}
         (chat-response-meta c)]))))

;; ---------------------------------------------------------------------------
;; Responses collector: /v1/responses
;; ---------------------------------------------------------------------------

(defn stream-responses
  "Stream Responses API events over `ch`, normalizing `response.done` to
  `response.completed` and tracking terminal state. Returns the stream meta;
  a stream that ends without a terminal event emits `response.failed`."
  [read-fn ch]
  (let [m (atom {:response-id nil :items [] :completed false
                 :incomplete false :terminal false})]
    (try
      (read-fn (fn [event]
                 (let [event (if (= (:name event) "response.done")
                               (assoc event :name "response.completed"
                                       :data (assoc (:data event) :type "response.completed"))
                               event)
                       name (:name event)
                       data (:data event)]
                   (cond
                     (= name "response.output_item.done")
                     (when (map? (get data :item))
                       (swap! m update :items conj (get data :item)))
                     (or (= name "response.completed") (= name "response.incomplete"))
                     (let [response (get data :response)]
                       (swap! m assoc
                              :response-id (get response :id)
                              :completed (= name "response.completed")
                              :incomplete (= name "response.incomplete")
                              :terminal true
                              :items (if (and (vector? (get response :output))
                                           (not (empty? (get response :output))))
                                       (get response :output) (:items @m))))
                     (= name "response.failed")
                     (swap! m assoc :terminal true))
                   (sse/send! ch {:event name :data (json/write-str data)}))))
      (catch Throwable t
        (send-stream-error ch (.getMessage ^Throwable t))))
    (when (not (:terminal @m))
      (send-stream-error ch "upstream stream closed before terminal"))
    (async/close! ch)
    @m))

(defn collect-response
  "Collect a full (non-streaming) response from `read-fn`. Returns
  `[response meta]`; throws if the stream ends without a terminal event."
  [read-fn]
  (let [acc (atom {:result nil :text (StringBuilder.) :response-id nil
                   :items [] :completed false :incomplete false})]
    (try
      (read-fn (fn [event]
                 (let [name (:name event) data (:data event)]
                   (cond
                     (= name "response.output_text.delta")
                     (let [delta (get data :delta)]
                       (when (and delta (not= delta ""))
                         (.append (:text @acc) delta)))
                     (= name "response.output_item.done")
                     (when (map? (get data :item))
                       (swap! acc update :items conj (get data :item)))
                     (or (= name "response.completed") (= name "response.done")
                         (= name "response.incomplete"))
                     (let [response (get data :response)]
                       (swap! acc assoc
                              :result response
                              :response-id (get response :id)
                              :completed (or (= name "response.completed")
                                             (= name "response.done"))
                              :incomplete (= name "response.incomplete")
                              :items (if (and (vector? (get response :output))
                                              (not (empty? (get response :output))))
                                       (get response :output) (:items @acc))))
                     (or (= name "response.failed") (= name "error"))
                     (throw (ex-info (str "Codex response failed: "
                                          (error-message data)) {}))))))
      (catch Throwable t (throw t)))
    (let [a @acc
          result (:result a)]
      (when (nil? result)
        (throw (ex-info "upstream stream closed before response.completed" {})))
      (let [output (get result :output)
            result (cond
                     (and (vector? output) (pos? (count output))) result
                     (pos? (count (:items a)))
                     (assoc result :output (:items a))
                     (pos? (.length (:text a)))
                     (assoc result :output
                            [{:type "message" :role "assistant" :status "completed"
                              :content [{:type "output_text" :text (.toString (:text a))}]}])
                     :else result)
            result (if (and (nil? (get result :output_text)) (pos? (.length (:text a))))
                     (assoc result :output_text (.toString (:text a)))
                     result)]
        [result {:response-id (:response-id a) :items (:items a)
                 :completed (:completed a) :incomplete (:incomplete a)}]))))
