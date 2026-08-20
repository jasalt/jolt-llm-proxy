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
            [codex.collect :as collect]
            [codex.translate :as tr]
            [llm-proxy.transport.sse :as transport-sse]
            [llm-proxy.transport.ws :as transport-ws]
            [llm-proxy.dashboard :as dashboard]
            [llm-proxy.error :as error]
            [llm-proxy.id :as id]
            [llm-proxy.time :as time]))

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

(defn- invalid-field-paths
  "Return bounded schema paths only, never rejected values or request text."
  [explanation]
  (->> (:errors explanation)
       (keep (fn [problem]
               (let [path (:in problem)]
                 (when (vector? path)
                   (str/join "." (map str path))))))
       distinct
       (take 16)
       vec))

(defn- input-error [exception]
  "Wrap a rejected client request with diagnostics that are safe to log.
  Malli's explanation contains submitted values, so retain only field paths.
  Translation failures intentionally expose no exception message."
  (let [explanation (:malli/explain (ex-data exception))
        fields (when explanation (invalid-field-paths explanation))]
    (ex-info "invalid client request"
             (cond-> {:type :input
                      :input-reason (if explanation :schema-validation :translation)}
               (seq fields) (assoc :invalid-fields fields))
             exception)))

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

(defn models [runtime _req]
  (let [now (long (/ ((or (:now-ms runtime) time/now-ms)) 1000))
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
        (catch Throwable ws-error
          (if (error/ws-fallback? ws-error)
            (do
              (error/log! :warn :ws-fallback ws-error
                          {:session-hash (when session-id (id/short-hash session-id))})
              (sse-source runtime request session-id))
            (throw ws-error))))
      (sse-source runtime request session-id))))

;; ---------------------------------------------------------------------------
;; Handlers
;; ---------------------------------------------------------------------------

(defn chat [runtime req]
  (let [raw (slurp-body req)
        parsed (try (tr/chat-to-responses raw)
                    (catch Throwable e {:error e}))]
    (if (contains? parsed :error)
      (let [failure (input-error (:error parsed))]
        (error/log! :warn :invalid-client-request failure
                    {:method (:request-method req) :uri (:uri req)})
        (error/response failure))
      (let [session-result (try (resolve-session-id runtime req)
                                (catch Throwable e {:error e}))]
        (if (map? session-result)
          (error/response (input-error (:error session-result)))
          (let [[request model stream] parsed
                session-id session-result
                request (apply-prompt-cache-key request session-id)
                src (open-event-source runtime request session-id true)]
        (if stream
          (let [ch (async/chan)]
            (async/thread
              (try
                (let [meta (collect/stream-chat (:read src) model ch runtime)]
                  (try ((:finalize src) meta) (catch Throwable _ nil)))
                (catch Throwable e
                  (sse/send! ch {:event "message"
                                  :data (json/write-str
                                          {:error {:message (error/stream-message e)
                                                   :type "api_error"
                                                   :code "upstream_error"}})})
                  (sse/send! ch {:event "message" :data "[DONE]"})
                  (async/close! ch))))
            {:status 200
             :headers {"Content-Type" "text/event-stream"
                       "Cache-Control" "no-cache" "X-Accel-Buffering" "no"}
             :body ch})
          (let [[response meta] (try (collect/collect-chat (:read src) model runtime)
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
      (let [failure (input-error (:error parsed))]
        (error/log! :warn :invalid-client-request failure
                    {:method (:request-method req) :uri (:uri req)})
        (error/response failure))
      (let [session-result (try (resolve-session-id runtime req)
                                (catch Throwable e {:error e}))]
        (if (map? session-result)
          (error/response (input-error (:error session-result)))
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
                  (collect/send-stream-error ch (error/stream-message e))
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
  "Return a direct Ring request dispatcher for one isolated runtime.
  The proxy has a fixed set of exact paths, so a small method/path dispatch
  avoids retaining a general-purpose routing dependency in the server."
  [runtime]
  ;; Keep dashboard responses live-reloadable. The lifecycle atom is
  ;; dereferenced for every request, rather than capturing the startup runtime
  ;; map in the dashboard route closures.
  (let [runtime-state (:runtime-state runtime)
        live-runtime #(or (when runtime-state @runtime-state) runtime)
        not-found (fn [_]
                    (write-openai-error 404 "not_found" "Not found"))]
    (fn [req]
      (let [method (:request-method req)
            uri (:uri req)]
        (cond
          (and (= :get method) (= "/health" uri))
          (health)

          (and (= :get method) (= "/v1/models" uri))
          (require-api-key runtime req (partial models runtime))

          (and (= :post method) (= "/v1/chat/completions" uri))
          (require-api-key runtime req
                           (fn [request] (chat runtime request)))

          (and (= :post method) (= "/v1/responses" uri))
          (require-api-key runtime req
                           (fn [request] (responses runtime request)))

          (and (:dashboard-enabled runtime)
               (= :get method)
               (contains? #{"/_llm-proxy"
                            "/_llm-proxy/"
                            "/_llm-proxy/datastar.js"
                            "/_llm-proxy/events"}
                          uri))
          (dashboard/route (live-runtime) req)

          :else
          (not-found req))))))

(defn app [runtime req]
  ((route-table runtime) req))

(defn make-handler
  "Build an isolated Ring handler from explicit runtime dependencies."
  [runtime]
  (let [runtime (if (:requests runtime)
                  runtime
                  (assoc runtime :requests (atom 0)))
        dispatch (route-table runtime)]
    (fn [req]
      ;; Dashboard page, bundle, and SSE polling are operator traffic rather
      ;; than proxied client requests; keep them out of the API request metric.
      (when-not (str/starts-with? (or (:uri req) "") "/_llm-proxy")
        (swap! (:requests runtime) inc))
      (try
        (dispatch req)
        (catch Throwable request-error
          (error/log! :error :request-failed request-error
                      {:method (:request-method req) :uri (:uri req)})
          (error/response request-error))))))
