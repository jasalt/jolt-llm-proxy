(ns llm-proxy.error
  "Classify failures at the local proxy boundary and keep internal causes out of
  client responses and logs. Transport namespaces annotate expected failures
  with an `:type` in ex-data; all unannotated failures are internal."
  (:require [clojure.data.json :as json]
            [clojure.tools.logging :as log]))

(defn category
  "Return the public error category for `error`. Never infer one from its
  message: messages can contain credentials or upstream response bodies."
  [error]
  (case (:type (ex-data error))
    :input :input
    :invalid-session-id :input
    :invalid-request :input
    :auth :auth
    :upstream-http :upstream-http
    :timeout :timeout
    :ws-transport :ws-transport
    :internal))

(defn ws-fallback?
  "Only a failure establishing or writing to the private WS transport merits
  retrying the request through SSE. Auth, input, and programmer errors do not."
  [error]
  (= :ws-transport (category error)))

(defn public-details [error]
  (case (category error)
    :input {:status 400 :code "invalid_request" :type "invalid_request_error"
            :message "Invalid request"}
    :auth {:status 401 :code "authentication_error" :type "authentication_error"
           :message "Unable to authenticate with the upstream service"}
    :upstream-http {:status 502 :code "upstream_error" :type "api_error"
                    :message "Upstream service request failed"}
    :timeout {:status 504 :code "upstream_timeout" :type "api_error"
              :message "Upstream service timed out"}
    ;; A WS error reaching this point means the SSE fallback also was not used
    ;; (or failed); it is still an upstream availability problem.
    :ws-transport {:status 502 :code "upstream_unavailable" :type "api_error"
                   :message "Upstream service is unavailable"}
    {:status 500 :code "internal_error" :type "server_error"
     :message "Internal proxy error"}))

(defn response
  "Build an OpenAI-shaped, redacted JSON error response."
  [error]
  (let [{:keys [status code type message]} (public-details error)]
    {:status status
     :headers {"Content-Type" "application/json"}
     :body (json/write-str {:error {:message message :type type :code code}})}))

(defn stream-message [error]
  (:message (public-details error)))

(defn log! [level event error context]
  "Log only classification and explicitly selected non-secret context. Never
  pass the Throwable itself or its message/body to the logging backend."
  (let [details (public-details error)
        data (merge {:event event
                     :error-category (category error)
                     :error-code (:code details)}
                    (select-keys (ex-data error) [:status])
                    context)]
    (case level
      :warn (log/warn data)
      :error (log/error data)
      (log/info data))))
