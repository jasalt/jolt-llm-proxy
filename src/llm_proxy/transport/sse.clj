(ns llm-proxy.transport.sse
  "Outbound Codex HTTP/SSE transport. Runtime dependencies are explicit so the
  parser and retry behavior can be tested without application globals."
  (:require [clojure.string :as str]
            [clojure.data.json :as json]
            [ebb.core :as m]
            [jolt.http-client :as http]
            [codex.auth :as auth]))

(def upstream-responses-url
  "https://chatgpt.com/backend-api/codex/responses")

(defn open-upstream
  "Open an upstream SSE response body. Retries one 401 after forced refresh."
  [runtime body session-id]
  (let [store (:store runtime)
        http-post (or (:http-post runtime) http/post)
        payload (json/write-str body)]
    (loop [attempt 0]
      (let [[token account-id] (auth/token store :force (= attempt 1))
            response (http-post upstream-responses-url
                       {:body payload
                        :content-type "application/json"
                        :accept "text/event-stream"
                        :throw-exceptions false
                        :headers {"Authorization" (str "Bearer " token)
                                  "ChatGPT-Account-Id" account-id
                                  "OpenAI-Beta" "responses=experimental"
                                  "originator" "pi"
                                  "User-Agent" "jolt-llm-proxy/1"
                                  "session-id" session-id
                                  "x-client-request-id" session-id}})]
        (cond
          (and (= attempt 0) (= (:status response) 401))
          (do
            (when (instance? java.io.InputStream (:body response))
              (.close ^java.io.InputStream (:body response)))
            (recur 1))

          (>= (:status response) 400)
          (do
            (when (instance? java.io.InputStream (:body response))
              (.close ^java.io.InputStream (:body response)))
            (throw (ex-info (str "upstream HTTP " (:status response))
                            {:type :upstream-http
                             :status (:status response)})))

          :else (:body response))))))

(defn- event-map [event data]
  (when (seq data)
    (let [raw (str/join "\n" data)]
      (when (not= raw "[DONE]")
        (let [object (json/read-str raw :key-fn keyword)
              name (if (and (string? event) (not= event ""))
                     event
                     (:type object))]
          {:name name :data object})))))

(defn- consume-line
  "Advance the pure SSE framing state for one line. The returned `:event` is
  either one parsed Codex event or nil; callers decide how to consume it."
  [{:keys [event data]} line]
  (let [line (str/trim line)]
    (cond
      (= line "") {:event nil :data [] :emitted (event-map event data)}
      (str/starts-with? line "event:")
      {:event (str/trim (subs line 6)) :data data :emitted nil}
      (str/starts-with? line "data:")
      {:event event :data (conj data (str/trim (subs line 5))) :emitted nil}
      :else {:event event :data data :emitted nil})))

(defn read-events
  "Parse SSE input and emit `{:name :data}` event maps. Input may be a complete
  string or an InputStream. This deterministic parser intentionally stays
  synchronous; the live source below owns blocking reads through Ebb."
  [in emit]
  (let [reader (java.io.BufferedReader.
                 (if (string? in)
                   (java.io.StringReader. in)
                   (java.io.InputStreamReader. in "UTF-8")))]
    (loop [state {:event nil :data []}]
      (if-let [line (.readLine reader)]
        (let [{:keys [emitted] :as next-state} (consume-line state line)]
          (when emitted (emit emitted))
          (recur (dissoc next-state :emitted)))
        (when-let [emitted (event-map (:event state) (:data state))]
          (emit emitted))))))

(defn- produce-events
  "Return an Ebb task that reads and emits one parsed event at a time. Each
  `emit!` is a rendezvous task supplied by `flow/with-event-source`, so a slow
  downstream consumer backpressures reads instead of buffering or dropping
  Codex events."
  [in emit!]
  (m/sp
    (let [reader (java.io.BufferedReader. (java.io.InputStreamReader. in "UTF-8"))]
      (loop [state {:event nil :data []}]
        (let [line (m/? (m/via m/blk (.readLine reader)))]
          (if (nil? line)
            (do
              (when-let [event (event-map (:event state) (:data state))]
                (m/? (emit! event)))
              :eof)
            (let [{:keys [emitted] :as next-state} (consume-line state line)]
              (when emitted (m/? (emit! emitted)))
              (recur (dissoc next-state :emitted)))))))))

(defn acquire
  "Return an Ebb task acquiring one structured SSE resource for
  `flow/with-event-source`. Opening and reading the HTTP stream occur on Ebb's
  blocking executor. `:abort!` and `:release!` are idempotent and close the
  response body, including when a downstream Ring client disconnects."
  [runtime request session-id]
  (m/sp
    (let [in (m/? (m/via m/blk (open-upstream runtime request session-id)))
          closed? (atom false)
          close! (fn []
                   (when (compare-and-set! closed? false true)
                     (when (instance? java.io.Closeable in)
                       (.close ^java.io.Closeable in))))]
      {:produce (fn [emit!] (produce-events in emit!))
       :abort! close!
       :release! (fn [_] (close!))})))
