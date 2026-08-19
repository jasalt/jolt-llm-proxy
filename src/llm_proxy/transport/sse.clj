(ns llm-proxy.transport.sse
  "Outbound Codex HTTP/SSE transport. Runtime dependencies are explicit so the
  parser and retry behavior can be tested without application globals."
  (:require [clojure.string :as str]
            [clojure.data.json :as json]
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

(defn- dispatch! [event data emit]
  (when (seq data)
    (let [raw (str/join "\n" data)]
      (when (not= raw "[DONE]")
        (let [object (json/read-str raw :key-fn keyword)
              name (if (and (string? event) (not= event ""))
                     event
                     (:type object))]
          (emit {:name name :data object}))))))

(defn read-events
  "Parse SSE input and emit `{:name :data}` event maps. Input may be a complete
  string or an InputStream."
  [in emit]
  (let [reader (java.io.BufferedReader.
                 (if (string? in)
                   (java.io.StringReader. in)
                   (java.io.InputStreamReader. in "UTF-8")))]
    (loop [event nil data []]
      (let [line (.readLine reader)]
        (if (nil? line)
          (when (seq data) (dispatch! event data emit))
          (let [line (str/trim line)]
            (cond
              (= line "")
              (do (when (seq data) (dispatch! event data emit))
                  (recur nil []))

              (str/starts-with? line "event:")
              (recur (str/trim (subs line 6)) data)

              (str/starts-with? line "data:")
              (recur event (conj data (str/trim (subs line 5))))

              :else (recur event data))))))))

(defn source
  "Return the common event-source interface used by the proxy."
  [runtime request session-id]
  (let [in (open-upstream runtime request session-id)]
    {:read (fn [emit] (read-events in emit))
     :finalize (fn [_]
                 (when (instance? java.io.Closeable in)
                   (.close ^java.io.Closeable in)))}))
