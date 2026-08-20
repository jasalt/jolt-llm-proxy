(ns llm-proxy.proxy-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.string :as str]
            [clojure.data.json :as json]
            [llm-proxy.error :as error]
            [llm-proxy.proxy :as proxy]
            [llm-proxy.transport.sse :as transport-sse]
            [llm-proxy.transport.ws :as transport-ws]))

(deftest normalizes-session-identifiers
  (is (= "abc_1:two" (proxy/normalize-session-id " abc_1:two ")))
  (is (= 64 (count (proxy/normalize-session-id (apply str (repeat 80 "a"))))))
  (is (= :invalid-session-id
         (try (proxy/normalize-session-id "bad\r\nheader") nil
              (catch Throwable e (:type (ex-data e)))))))

(deftest api-key-guard
  (let [runtime {:api-key "secret"}
        ok (fn [_] {:status 204})]
    (is (= 401 (:status (proxy/require-api-key runtime {:headers {}} ok))))
    (is (= 204 (:status (proxy/require-api-key
                          runtime
                          {:headers {"authorization" "Bearer secret"}} ok))))))

(deftest handler-instances-have-isolated-configuration
  (let [open (proxy/make-handler {:api-key "" :session-id "one"})
        guarded (proxy/make-handler {:api-key "secret" :session-id "two"})
        request {:request-method :get :uri "/v1/models" :headers {}}]
    (is (= 200 (:status (open request))))
    (is (= 401 (:status (guarded request))))))

(deftest model-timestamps-use-runtime-clock
  (let [handler (proxy/make-handler {:api-key "" :session-id "one"
                                     :now-ms (constantly 123000)})
        response (handler {:request-method :get :uri "/v1/models" :headers {}})
        body (json/read-str (:body response) :key-fn keyword)]
    (is (= 123 (get-in body [:data 0 :created]))))

(deftest public-errors-are-classified-and-redacted
  (let [secret "Bearer secret-token upstream body"
        response (error/response (ex-info secret {:type :upstream-http :status 500}))]
    (is (= 502 (:status response)))
    (is (= {:message "Upstream service request failed"
            :type "api_error"
            :code "upstream_error"}
           (:error (json/read-str (:body response) :key-fn keyword))))
    (is (not (.contains (:body response) secret))))
  (is (= 400 (:status (error/response (ex-info "bad input" {:type :input})))))
  (is (= 401 (:status (error/response (ex-info "expired token" {:type :auth})))))
  (is (= 500 (:status (error/response (Exception. "unclassified secret"))))))

(deftest websocket-fallback-is-limited-to-marked-transport-failures
  (let [runtime {:pool {} :session-id "default"}
        request {:model "gpt-5.4"}
        sse-source {:read identity :finalize identity}]
    (with-redefs [transport-ws/source (fn [& _]
                                        (throw (ex-info "socket unavailable"
                                                        {:type :ws-transport})))
                  transport-sse/source (fn [& _] sse-source)]
      (is (= sse-source (proxy/open-event-source runtime request "client" false))))
    (with-redefs [transport-ws/source (fn [& _]
                                        (throw (ex-info "invalid token: secret"
                                                        {:type :auth})))
                  transport-sse/source (fn [& _]
                                         (throw (Exception. "must not fall back")))]
      (is (= :auth
             (try (proxy/open-event-source runtime request "client" false)
                  nil
                  (catch Throwable e (:type (ex-data e)))))))))

(deftest rejected-input-logging-keeps-only-safe-schema-paths
  (let [captured (atom [])
        runtime {:api-key "" :session-id "default" :pool {}}
        request {:request-method :post :uri "/v1/chat/completions"
                 :headers {}
                 :body "{\"model\":\"m\",\"messages\":[{\"role\":\"user\"}],\"stream\":\"yes\"}"}]
    (binding [error/*emit!* (fn [level data] (swap! captured conj [level data]))]
      (let [response ((proxy/make-handler runtime) request)]
        (is (= 400 (:status response)))))
    (is (= [[:warn {:event :invalid-client-request
                    :error-category :input
                    :error-code "invalid_request"
                    :input-reason :schema-validation
                    :invalid-fields ["stream"]
                    :method :post
                    :uri "/v1/chat/completions"}]]
           @captured))
    (let [rendered (pr-str @captured)]
      (is (not (.contains rendered "\"model\":\"m\"")))
      (is (not (.contains rendered "\"stream\":\"yes\""))))))

(deftest error-logging-is-classified-and-secret-free
  (let [secret-values ["Bearer token-123" "session-456"
                       "Authorization: Bearer token-123" "prompt: private text"
                       "raw upstream body"]
        captured (atom [])
        failure (ex-info (str/join " | " secret-values)
                         {:type :upstream-http :status 500
                          :authorization "Bearer token-123"
                          :session-id "session-456"
                          :prompt "private text"
                          :body "raw upstream body"})]
    (binding [error/*emit!* (fn [level data] (swap! captured conj [level data]))]
      (error/log! :error :request-failed failure
                  {:method :post :uri "/v1/responses"}))
    (is (= [[:error {:event :request-failed
                     :error-category :upstream-http
                     :error-code "upstream_error"
                     :status 500
                     :method :post
                     :uri "/v1/responses"}]]
           @captured))
    (let [rendered (pr-str @captured)]
      (doseq [secret secret-values]
        (is (not (.contains rendered secret)))))))

(deftest handler-maps-classified-and-unknown-failures
  (let [runtime {:api-key "" :session-id "default" :pool {}}
        request {:request-method :post :uri "/v1/responses"
                 :headers {} :body "{\"model\":\"gpt-5.4\",\"input\":\"hello\"}"}]
    (doseq [[failure expected-status expected-code]
            [[(ex-info "upstream raw body" {:type :upstream-http :status 429})
              502 "upstream_error"]
             [(ex-info "Bearer expired-token" {:type :auth})
              401 "authentication_error"]
             [(ex-info "upstream timeout" {:type :timeout})
              504 "upstream_timeout"]
             [(Exception. "internal token=private")
              500 "internal_error"]]]
      (with-redefs [transport-sse/source (fn [& _] (throw failure))]
        (let [response ((proxy/make-handler runtime) request)
              body (json/read-str (:body response) :key-fn keyword)]
          (is (= expected-status (:status response)))
          (is (= expected-code (get-in body [:error :code])))
          (is (not (.contains (:body response) (.getMessage failure))))))))))
