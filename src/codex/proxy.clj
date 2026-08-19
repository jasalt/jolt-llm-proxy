(ns codex.proxy
  "Ring handler, routes, prompt-cache key derivation, and the SSE/WS event
  collectors that stream OpenAI-shaped output. Inbound Ring request/response
  maps are plain Clojure PMaps — use `(:headers req)` / `get-in` normally
  (NOT `jolt.host/ref-get`). The outbound `jolt.http.tls` stream is a
  tagged-table and is handled inside `codex.ws`."
  (:require [clojure.string :as str]
            [clojure.data.json :as json]
            [clojure.core.async :as async]
            [ring-chez.sse :as sse]
            [jolt.http-client :as http]
            [codex.auth :as auth]
            [codex.ws :as ws]
            [codex.continuation :as cont]
            [codex.translate :as tr]))

;; Runtime dependencies are passed explicitly through a handler closure made by
;; `make-handler`; this namespace owns no application lifecycle state.

(def upstream-responses-url "https://chatgpt.com/backend-api/codex/responses")

(def model-ids
  ["gpt-5.3-codex-spark" "gpt-5.4" "gpt-5.4-mini" "gpt-5.5"
   "gpt-5.6-luna" "gpt-5.6-sol" "gpt-5.6-terra"])

(def prompt-cache-key-max-length 64)
(def session-id-pattern #"^[A-Za-z0-9._:-]+$")

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- slurp-body [req]
  (let [body (:body req)]
    (cond
      (nil? body) ""
      (string? body) body
      (instance? java.io.Reader body) (slurp body)
      :else (str body))))

(defn write-openai-error [status code message]
  {:status status
   :headers {"Content-Type" "application/json"}
   :body (json/write-str {:error {:message message
                                  :type "invalid_request_error"
                                  :code code}})})

(defn- random-id []
  (let [b (byte-array 16)]
    (.nextBytes (java.security.SecureRandom.) b)
    (loop [i 0 s (StringBuilder.)]
      (if (>= i 16)
        (.toString s)
        (recur (inc i)
               (.append s (format "%02x" (bit-and (aget b i) 0xFF))))))))

(defn normalize-session-id
  "Validate and clamp a client session/cache identifier before it becomes a
  pool key or upstream header. Returns nil for blank input."
  [value]
  (let [key (str/trim (or value ""))]
    (when (not= key "")
      (when-not (re-matches session-id-pattern key)
        (throw (ex-info "session id contains unsupported characters"
                        {:type :invalid-session-id})))
      (if (<= (count key) prompt-cache-key-max-length)
        key
        (subs key 0 prompt-cache-key-max-length)))))

(defn resolve-session-id [runtime req]
  (or (some (fn [h]
              (normalize-session-id (get-in req [:headers h] "")))
            ["x-session-id" "x-prompt-cache-key"])
      (:session-id runtime)))

(defn apply-prompt-cache-key [request key]
  (if (or (= key "") (contains? request :prompt_cache_key))
    request
    (assoc request :prompt_cache_key key)))

;; ---------------------------------------------------------------------------
;; API-key guard
;; ---------------------------------------------------------------------------

(defn require-api-key [runtime req handler-fn]
  (let [api-key (:api-key runtime)]
    (if (= api-key "")
      (handler-fn req)
      (let [auth (get-in req [:headers "authorization"] "")
            provided (str/trim (if (str/starts-with? auth "Bearer ")
                                   (subs auth 7) auth))]
        (if (and (= (count provided) (count api-key))
                 (java.util.Arrays/equals (.getBytes provided "UTF-8")
                                          (.getBytes api-key "UTF-8")))
          (handler-fn req)
          (write-openai-error 401 "invalid_api_key" "Invalid proxy API key"))))))

;; ---------------------------------------------------------------------------
;; Routes
;; ---------------------------------------------------------------------------

(defn health []
  {:status 200
   :headers {"Content-Type" "application/json"}
   :body (json/write-str {:status "ok"})})

(defn models [_req]
  (let [now (long (/ (System/currentTimeMillis) 1000))
        data (vec (map (fn [id] {:id id :object "model"
                                 :created now :owned_by "openai-codex"})
                       model-ids))]
    {:status 200
     :headers {"Content-Type" "application/json"}
     :body (json/write-str {:object "list" :data data})}))

;; ---------------------------------------------------------------------------
;; SSE upstream + reader
;; ---------------------------------------------------------------------------

(defn upstream-sse [runtime body session-id]
  (let [store (:store runtime)
        http-post (or (:http-post runtime) http/post)
        payload (json/write-str body)]
    (loop [attempt 0]
      (let [[token account-id] (auth/token store :force (= attempt 1))
            resp (http-post upstream-responses-url
                    {:body payload
                     :content-type "application/json"
                     :accept "text/event-stream"
                     :throw-exceptions false
                     :headers {"Authorization" (str "Bearer " token)
                               "ChatGPT-Account-Id" account-id
                               "OpenAI-Beta" "responses=experimental"
                               "originator" "pi"
                               "User-Agent" "chatgpt-openai-api-adapter/1"
                               "session-id" session-id
                               "x-client-request-id" session-id}})]
        (cond
          (and (= attempt 0) (= (:status resp) 401))
          (do (when (instance? java.io.InputStream (:body resp))
                (.close ^java.io.InputStream (:body resp)))
              (recur 1))
          (>= (:status resp) 400)
          (do (when (instance? java.io.InputStream (:body resp))
                (.close ^java.io.InputStream (:body resp)))
              (throw (ex-info (str "upstream HTTP " (:status resp))
                              {:status (:status resp) :body (:body resp)})))
          :else (:body resp))))))

(defn- dispatch-sse [event data emit]
  (when (seq data)
    (let [raw (str/join "\n" data)]
      (when (not= raw "[DONE]")
        (let [obj (json/read-str raw :key-fn keyword)
              name (if (and (string? event) (not= event ""))
                     event (get obj :type))]
          (emit {:name name :data obj}))))))

(defn read-sse [in emit]
  (let [rdr (java.io.BufferedReader.
              (if (string? in)
                (java.io.StringReader. in)
                (java.io.InputStreamReader. in "UTF-8")))]
    (loop [event nil data []]
      (let [line (.readLine rdr)]
        (if (nil? line)
          (do (when (seq data) (dispatch-sse event data emit)) nil)
          (let [line (str/trim line)]
            (cond
              (= line "")
              (do (when (seq data) (dispatch-sse event data emit))
                  (recur nil []))
              (str/starts-with? line "event:")
              (recur (str/trim (subs line 6)) data)
              (str/starts-with? line "data:")
              (recur event (conj data (str/trim (subs line 5))))
              :else (recur event data))))))))

;; ---------------------------------------------------------------------------
;; Transport selection: WebSocket continuation vs SSE fallback
;; ---------------------------------------------------------------------------

(defn acquire-ws [pool full-body session-id header-builder]
  (let [acq (ws/acquire pool session-id header-builder)
        conn (:conn acq)
        cont (ws/get-continuation conn)]
    (if (nil? cont)
      [acq full-body false]
      (let [[delta ok?] (cont/build-delta-request full-body cont)]
        (if ok?
          [acq delta true]
          (do (ws/clear-continuation! conn)
              [acq full-body false]))))))

(defn ws-source [runtime full-body session-id for-chat]
  (let [store (:store runtime)
        pool (:pool runtime)
        header-builder (fn [] (auth/token store))
        [acq request-body used-delta] (acquire-ws pool full-body session-id header-builder)]
    (try
      (when used-delta
        (println "ws-source: using delta continuation for" session-id))
      (let [frame (assoc request-body :type "response.create")]
        (ws/write-text (:conn acq) (json/write-str frame)))
      (let [read (fn [emit]
                   (ws/read-until-terminal
                    (:conn acq)
                    (fn [event]
                      (emit {:name (:type event) :data (:data event)}))))
            finalize (fn [meta]
                       (let [keep (and (:completed meta)
                                       (not= (:response-id meta) ""))]
                         (when keep
                           (let [items (cont/response-output-to-input-items
                                        (:items meta) for-chat)]
                             (ws/set-continuation!
                              (:conn acq)
                              {:last-request-body full-body
                               :last-response-id (:response-id meta)
                               :last-response-items items})))
                         ((:release acq) keep)))]
        {:read read :finalize finalize})
      (catch Throwable e
        ;; Ownership has not transferred to the returned source yet.
        ((:release acq) false)
        (throw e)))))

(defn sse-source [runtime request session-id]
  (let [in (upstream-sse runtime request session-id)]
    {:read (fn [emit] (read-sse in emit))
     :finalize (fn [_] (try (when (instance? java.io.Closeable in)
                              (.close ^java.io.Closeable in))
                            (catch Throwable _ nil)))}))

(defn open-event-source [runtime request session-id for-chat]
  (let [pool (:pool runtime)
        default-session (:session-id runtime)]
    (if (and pool (not= session-id "") (not= session-id default-session))
      (try
        (ws-source runtime request session-id for-chat)
        (catch Throwable e
          (println "websocket transport unavailable, falling back to SSE:"
                   (.getMessage ^Throwable e))
          (sse-source runtime request session-id)))
      (sse-source runtime request session-id))))

;; ---------------------------------------------------------------------------
;; Collectors: chat
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

(defn error-message [obj]
  (cond
    (string? (get obj :message)) (get obj :message)
    (map? (get obj :error))
    (or (get-in obj [:error :message]) (json/write-str (get obj :error)))
    (map? (get obj :response))
    (or (get-in obj [:response :error :message]) (json/write-str (get obj :response)))
    :else (json/write-str obj)))

(defn consume-chat [acc event emit]
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

(defn chat-response-meta [acc]
  {:response-id (:response-id acc)
   :items (:output acc)
   :completed (:completed acc)
   :incomplete (:incomplete acc)})

(defn openai-usage [usage]
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

(defn stream-chat [read-fn model ch]
  (let [id (str "chatcmpl-" (random-id))
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

(defn collect-chat [read-fn model]
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
        [{:id (str "chatcmpl-" (random-id))
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
;; Collectors: responses
;; ---------------------------------------------------------------------------

(defn- send-stream-error [ch message]
  (sse/send! ch
    {:event "response.failed"
     :data (json/write-str
             {:type "response.failed"
              :response {:status "failed"
                         :error {:type "server_error"
                                 :code "stream_disconnected"
                                 :message message}}})}))

(defn stream-responses [read-fn ch]
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

(defn collect-response [read-fn]
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

;; ---------------------------------------------------------------------------
;; Handlers
;; ---------------------------------------------------------------------------

(defn chat [runtime req]
  (let [raw (slurp-body req)
        parsed (try (tr/chat-to-responses raw)
                    (catch Throwable e {:error e}))]
    (if (contains? parsed :error)
      (write-openai-error 400 "invalid_request" (.getMessage ^Throwable (:error parsed)))
      (let [session-result (try (resolve-session-id runtime req)
                                (catch Throwable e {:error e}))]
        (if (map? session-result)
          (write-openai-error 400 "invalid_session_id"
                              (.getMessage ^Throwable (:error session-result)))
          (let [[request model stream] parsed
                session-id session-result
                request (apply-prompt-cache-key request session-id)
                src (open-event-source runtime request session-id true)]
        (if stream
          (let [ch (async/chan)]
            (async/thread
              (try
                (let [meta (stream-chat (:read src) model ch)]
                  (try ((:finalize src) meta) (catch Throwable _ nil)))
                (catch Throwable e
                  (sse/send! ch {:event "message"
                                  :data (json/write-str
                                          {:error {:message (.getMessage ^Throwable e)
                                                   :type "upstream_error"
                                                   :code "upstream_error"}})})
                  (sse/send! ch {:event "message" :data "[DONE]"})
                  (async/close! ch))))
            {:status 200
             :headers {"Content-Type" "text/event-stream"
                       "Cache-Control" "no-cache" "X-Accel-Buffering" "no"}
             :body ch})
          (let [[response meta] (try (collect-chat (:read src) model)
                                     (catch Throwable e
                                       ((:finalize src) {:completed false})
                                       (throw e)))]
            ((:finalize src) meta)
            {:status 200
             :headers {"Content-Type" "application/json"}
             :body (json/write-str response)}))))))))

(defn responses [runtime req]
  (let [raw (slurp-body req)
        parsed (try (tr/prepare-responses raw)
                    (catch Throwable e {:error e}))]
    (if (contains? parsed :error)
      (write-openai-error 400 "invalid_request" (.getMessage ^Throwable (:error parsed)))
      (let [session-result (try (resolve-session-id runtime req)
                                (catch Throwable e {:error e}))]
        (if (map? session-result)
          (write-openai-error 400 "invalid_session_id"
                              (.getMessage ^Throwable (:error session-result)))
          (let [[request _model stream] parsed
                session-id session-result
                request (apply-prompt-cache-key request session-id)
                src (open-event-source runtime request session-id false)]
        (if stream
          (let [ch (async/chan)]
            (async/thread
              (try
                (let [meta (stream-responses (:read src) ch)]
                  (try ((:finalize src) meta) (catch Throwable _ nil)))
                (catch Throwable e
                  (send-stream-error ch (.getMessage ^Throwable e))
                  (async/close! ch))))
            {:status 200
             :headers {"Content-Type" "text/event-stream"
                       "Cache-Control" "no-cache" "X-Accel-Buffering" "no"}
             :body ch})
          (let [[response meta] (try (collect-response (:read src))
                                     (catch Throwable e
                                       ((:finalize src) {:completed false})
                                       (throw e)))]
            ((:finalize src) meta)
            {:status 200
             :headers {"Content-Type" "application/json"}
             :body (json/write-str response)}))))))))

;; ---------------------------------------------------------------------------
;; Top-level handler (var, so redefs are live via run-server #'handler)
;; ---------------------------------------------------------------------------

(defn app [runtime req]
  (let [method (:request-method req)
        uri (:uri req)]
    (cond
      (and (= method :get) (= uri "/health")) (health)
      (and (= method :get) (= uri "/v1/models"))
      (require-api-key runtime req models)
      (and (= method :post) (= uri "/v1/chat/completions"))
      (require-api-key runtime req #(chat runtime %))
      (and (= method :post) (= uri "/v1/responses"))
      (require-api-key runtime req #(responses runtime %))
      :else (write-openai-error 404 "not_found" "Not found"))))

(defn make-handler
  "Build an isolated Ring handler from explicit runtime dependencies."
  [runtime]
  (fn [req] (app runtime req)))
