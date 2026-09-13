(ns llm-proxy.dashboard-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.core.async :as async]
            [ebb.core :as m]
            [llm-proxy.dashboard :as dashboard]
            [llm-proxy.inspect :as inspect]
            [llm-proxy.proxy :as proxy]))

(defn runtime []
  {:store (atom {:access_token "secret-token" :account_id "account"})
   :pool {:sessions (atom {"raw-session-id" {:busy false
                                   :created-at (- (System/currentTimeMillis) 2000)
                                   :last-used (- (System/currentTimeMillis) 1000)
                                   :conn {:continuation (atom {:id "private"})}}})
          :lock (Object.)
          :now-ms #(System/currentTimeMillis)}
   :session-id "raw-session-id"
   :api-key "secret-api-key"
   :port 8080
   :nrepl-port 8002
   :started-at (System/currentTimeMillis)
   :requests (atom 0)
   :dashboard-enabled true
   :runtime-state (atom nil)})

(defn- await!
  [pred]
  (loop [remaining 100]
    (cond
      (pred) true
      (zero? remaining) false
      :else (do (Thread/sleep 10)
                (recur (dec remaining))))))

(deftest dashboard-sampler-publishes-only-safe-snapshots
  (let [rt (runtime)
        {:keys [state stop!]} (dashboard/start! rt {:interval-ms 10})]
    (try
      (reset! (:requests rt) 7)
      (is (await! #(= 7 (:requests @state))))
      (is (= false (contains? @state :session-id)))
      (is (= false (contains? @state :store)))
      (is (not (re-find #"secret-token|secret-api-key|raw-session-id"
                        (pr-str @state))))
      (finally (stop!)))))

(deftest dashboard-sampler-cancellation-is-idempotent
  (let [cancels (atom 0)
        sleep-task (fn [_]
                     (fn [_success failure]
                       (fn []
                         (swap! cancels inc)
                         (failure (m/cancelled "sampler stopped")))))
        {:keys [stop!]} (dashboard/start! (runtime)
                                          {:sleep-task sleep-task})]
    (stop!)
    (stop!)
    (is (= 1 @cancels))))

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

(deftest dashboard-uses-live-runtime-state
  (let [rt (runtime)
        state (atom (assoc rt :nrepl-port nil))
        handler (proxy/make-handler (assoc rt :runtime-state state))
        disabled (handler {:request-method :get :uri "/_llm-proxy/events" :headers {}})
        disabled-event (async/<!! (:body disabled))]
    (is (.contains disabled-event "<dt>nREPL Listener</dt><dd>Disabled</dd>"))
    (async/close! (:body disabled))
    (swap! state assoc :nrepl-port 8002)
    (let [enabled (handler {:request-method :get :uri "/_llm-proxy/events" :headers {}})
          enabled-event (async/<!! (:body enabled))]
      (is (.contains enabled-event "<dt>nREPL Listener</dt><dd>127.0.0.1:8002</dd>"))
      (is (.contains enabled-event "127.0.0.1:8002"))
      (async/close! (:body enabled)))))

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
    (is (.contains event "nREPL Listener"))
    (is (.contains event "127.0.0.1:8002"))
    (is (.contains event "<dt>nREPL Listener</dt><dd>127.0.0.1:8002</dd>"))
    (is (not (.contains event "secret-token")))
    (is (not (.contains event "raw-session-id")))
    (async/close! (:body response))))
