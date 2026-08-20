(ns llm-proxy.dashboard-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.core.async :as async]
            [llm-proxy.dashboard :as dashboard]
            [llm-proxy.inspect :as inspect]
            [llm-proxy.proxy :as proxy]))

(defn runtime []
  {:store (atom {:access_token "secret-token" :account_id "account"})
   :pool (atom {"raw-session-id" {:busy false
                                   :created-at (- (System/currentTimeMillis) 2000)
                                   :last-used (- (System/currentTimeMillis) 1000)
                                   :conn {:continuation (atom {:id "private"})}}})
   :session-id "raw-session-id"
   :api-key "secret-api-key"
   :port 8080
   :started-at (System/currentTimeMillis)
   :requests (atom 0)
   :dashboard-enabled true})

(deftest dashboard-is-optional-and-excluded-from-request-metrics
  (let [request {:request-method :get :uri "/_llm-proxy" :headers {}}
        disabled-runtime (assoc (runtime) :dashboard-enabled false)
        enabled-runtime (runtime)
        disabled (proxy/make-handler disabled-runtime)
        enabled (proxy/make-handler enabled-runtime)
        response (enabled request)]
    (is (= 404 (:status (disabled request))))
    (is (= 200 (:status response)))
    (is (= 0 @(:requests enabled-runtime)))
    (is (.contains (get-in response [:headers "Content-Security-Policy"])
                   "'unsafe-eval'"))
    (is (not (re-find #"secret-token|secret-api-key|raw-session-id"
                      (:body response))))))

(deftest dashboard-routes-do-not-increment-request-metric
  (let [rt (runtime)
        handler (proxy/make-handler rt)]
    (doseq [uri ["/_llm-proxy/" "/_llm-proxy/datastar.js" "/_llm-proxy/events"]]
      (let [response (handler {:request-method :get :uri uri :headers {}})]
        (is (= 0 @(:requests rt)) uri)
        (when (= uri "/_llm-proxy/events")
          (async/close! (:body response)))))
    (handler {:request-method :get :uri "/health" :headers {}})
    (is (= 1 @(:requests rt)))))

(deftest dashboard-serves-vendored-datastar
  (let [response (dashboard/route (runtime)
                                  {:request-method :get
                                   :uri "/_llm-proxy/datastar.js"})]
    (is (= 200 (:status response)))
    (is (= "application/javascript" (get-in response [:headers "Content-Type"])))
    (is (.contains (:body response) "Datastar v1.0.2"))))

(deftest snapshot-is-redacted-and-sse-is-datastar-shaped
  (let [rt (runtime)
        snapshot (inspect/snapshot rt)
        response (dashboard/events-response rt)
        event (async/<!! (:body response))]
    (is (= false (contains? snapshot :session-id)))
    (is (= false (contains? snapshot :store)))
    (is (= 1 (get-in snapshot [:codex :ws-pool :entries])))
    (is (= 128 (get-in snapshot [:codex :ws-pool :capacity])))
    (is (= true (get-in snapshot [:codex :ws-pool :sessions 0 :continuation])))
    (is (not= "raw-session-id" (get-in snapshot [:codex :ws-pool :sessions 0 :id])))
    (is (.contains event "event: datastar-patch-elements"))
    (is (.contains event "data: selector #dashboard"))
    (is (.contains event "WebSocket pool"))
    (is (.contains event "Token expiry"))
    (is (not (.contains event "secret-token")))
    (is (not (.contains event "raw-session-id")))
    (async/close! (:body response))))
