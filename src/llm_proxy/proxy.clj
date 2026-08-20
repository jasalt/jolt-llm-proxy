(ns llm-proxy.proxy
  "Ring boundary: routing, API-key guard, session/prompt-cache key derivation,
  transport selection, and the endpoint handlers. Event collectors live in
  `codex.collect`; outbound transports in `llm-proxy.transport.*`. Inbound Ring
  request/response maps are plain Clojure PMaps — use `(:headers req)` /
  `get-in` normally (NOT `jolt.host/ref-get`). The outbound `jolt.http.tls`
  stream is a tagged-table and is handled inside `codex.ws`."
  (:require [clojure.string :as str]
            [clojure.data.json :as json]
            [clojure.core.async :as async]
            [ring-chez.sse :as sse]
            [ruuter.core :as ruuter]
            [codex.collect :as collect]
            [codex.translate :as tr]
            [llm-proxy.transport.sse :as transport-sse]
            [llm-proxy.transport.ws :as transport-ws]
            [llm-proxy.dashboard :as dashboard]))

;; Runtime dependencies are passed explicitly through a handler closure made by
;; `make-handler`; this namespace owns no application lifecycle state.

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
;; Transport selection: WebSocket continuation vs SSE fallback
;; ---------------------------------------------------------------------------

(defn sse-source [runtime request session-id]
  (transport-sse/source runtime request session-id))

(defn open-event-source [runtime request session-id for-chat]
  (let [pool (:pool runtime)
        default-session (:session-id runtime)]
    (if (and pool (not= session-id "") (not= session-id default-session))
      (try
        (transport-ws/source runtime request session-id for-chat)
        (catch Throwable e
          (println "websocket transport unavailable, falling back to SSE:"
                   (.getMessage ^Throwable e))
          (sse-source runtime request session-id)))
      (sse-source runtime request session-id))))

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
                (let [meta (collect/stream-chat (:read src) model ch)]
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
          (let [[response meta] (try (collect/collect-chat (:read src) model)
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
                (let [meta (collect/stream-responses (:read src) ch)]
                  (try ((:finalize src) meta) (catch Throwable _ nil)))
                (catch Throwable e
                  (collect/send-stream-error ch (.getMessage ^Throwable e))
                  (async/close! ch))))
            {:status 200
             :headers {"Content-Type" "text/event-stream"
                       "Cache-Control" "no-cache" "X-Accel-Buffering" "no"}
             :body ch})
          (let [[response meta] (try (collect/collect-response (:read src))
                                     (catch Throwable e
                                       ((:finalize src) {:completed false})
                                       (throw e)))]
            ((:finalize src) meta)
            {:status 200
             :headers {"Content-Type" "application/json"}
             :body (json/write-str response)}))))))))

;; ---------------------------------------------------------------------------
;; Top-level handler and routes
;; ---------------------------------------------------------------------------

(defn route-table
  "Return the Ring routes for one isolated runtime. Ruuter adds any matched
  path parameters under `:params`; these exact routes currently need none."
  [runtime]
  (let [routes [{:path "/health"
                 :method :get
                 :response (fn [_] (health))}
                {:path "/v1/models"
                 :method :get
                 :response #(require-api-key runtime % models)}
                {:path "/v1/chat/completions"
                 :method :post
                 :response (fn [req]
                             (require-api-key runtime req
                                              (fn [request] (chat runtime request))))}
                {:path "/v1/responses"
                 :method :post
                 :response (fn [req]
                             (require-api-key runtime req
                                              (fn [request] (responses runtime request))))}]
        dashboard-routes (when (:dashboard-enabled runtime)
                           [{:path "/_llm-proxy"
                             :method :get
                             :response (fn [req] (dashboard/route runtime req))}
                            {:path "/_llm-proxy/datastar.js"
                             :method :get
                             :response (fn [req] (dashboard/route runtime req))}
                            {:path "/_llm-proxy/events"
                             :method :get
                             :response (fn [req] (dashboard/route runtime req))}])]
    (conj (into routes dashboard-routes)
          {:path :not-found
           :response (fn [_] (write-openai-error 404 "not_found" "Not found"))})))

(defn app [runtime req]
  (ruuter/route (route-table runtime) req))

(defn make-handler
  "Build an isolated Ring handler from explicit runtime dependencies."
  [runtime]
  (let [runtime (if (:requests runtime)
                  runtime
                  (assoc runtime :requests (atom 0)))
        routes (ruuter/compile-routes (route-table runtime))]
    (fn [req]
      ;; Dashboard page, bundle, and SSE polling are operator traffic rather
      ;; than proxied client requests; keep them out of the API request metric.
      (when-not (str/starts-with? (or (:uri req) "") "/_llm-proxy")
        (swap! (:requests runtime) inc))
      (ruuter/route routes req))))
