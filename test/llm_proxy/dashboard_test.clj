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

(defn- with-dashboard
  [rt]
  (assoc rt :dashboard (dashboard/start! rt {:interval-ms 100000})))

(defn- sse-request []
  {:request-method :get
   :uri "/_llm-proxy/"
   :query-string "datastar-sse=true&datastar-selector=%23dashboard"
   :headers {}})

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
        rt (with-dashboard (runtime))]
    (try
      (let [disabled (proxy/make-handler disabled-runtime)
            enabled (proxy/make-handler rt)
            response (enabled request)]
        (is (= 404 (:status (disabled request))))
        (is (= 200 (:status response)))
        (is (= 0 @(:requests rt)))
        (is (.contains (get-in response [:headers "Content-Security-Policy"])
                       "'unsafe-eval'"))
        (is (.contains (:body response) "datastar-sse=true"))
        (is (not (.contains (:body response) "/_llm-proxy/events")))
        (is (not (re-find #"secret-token|secret-api-key|raw-session-id"
                          (:body response)))))
      (finally ((get-in rt [:dashboard :stop!]))))))

(deftest dashboard-routes-do-not-increment-request-metric
  (let [rt (with-dashboard (runtime))
        handler (proxy/make-handler rt)]
    (try
      (is (= 200 (:status (handler {:request-method :get
                                    :uri "/_llm-proxy/" :headers {}}))))
      (is (= 200 (:status (handler {:request-method :get
                                    :uri "/_llm-proxy/datastar.js" :headers {}}))))
      (let [response (handler (sse-request))]
        (is (= 200 (:status response)))
        (async/<!! (:body response))
        (async/close! (:body response)))
      (is (= 404 (:status (handler {:request-method :get
                                    :uri "/_llm-proxy/events" :headers {}}))))
      (is (= 0 @(:requests rt)))
      (handler {:request-method :get :uri "/health" :headers {}})
      (is (= 1 @(:requests rt)))
      (finally ((get-in rt [:dashboard :stop!]))))))

(deftest dashboard-serves-compatible-vendored-datastar
  (let [response (dashboard/route (runtime)
                                  {:request-method :get
                                   :uri "/_llm-proxy/datastar.js"})]
    (is (= 200 (:status response)))
    (is (= "application/javascript" (get-in response [:headers "Content-Type"])))
    (is (.contains (:body response) "Datastar v1.0.3"))
    (is (.contains (:body response) "datastar-patch-elements"))))

(deftest reactive-state-renders-datastar-patches-without-duplicates
  (let [rt (with-dashboard (runtime))
        state (get-in rt [:dashboard :state])
        response ((get-in rt [:dashboard :handler]) (sse-request))
        ch (:body response)]
    (try
      (is (= "text/event-stream; charset=utf-8"
             (get-in response [:headers "Content-Type"])))
      (let [initial (async/<!! ch)]
        (is (.contains initial "event: datastar-patch-elements"))
        (is (.contains initial "data: selector #dashboard"))
        (is (.contains initial "WebSocket pool"))
        (is (.contains initial "Token expiry"))
        (is (.contains initial "nREPL Listener"))
        (is (.contains initial "127.0.0.1:8002"))
        (is (not (re-find #"secret-token|secret-api-key|raw-session-id" initial))))
      ;; A presentation-state change that does not affect HTML should not emit.
      (swap! state assoc :not-rendered true)
      (Thread/sleep 40)
      (is (nil? (async/poll! ch)))
      (swap! state assoc :requests 9)
      (let [patch (async/<!! ch)]
        (is (.contains patch "<dt>Requests served</dt><dd>9</dd>")))
      (finally
        (async/close! ch)
        ((get-in rt [:dashboard :stop!]))))))

(deftest disconnect-removes-glimmer-subscription
  (let [rt (with-dashboard (runtime))
        state (get-in rt [:dashboard :state])
        response ((get-in rt [:dashboard :handler]) (sse-request))
        ch (:body response)]
    (try
      (async/<!! ch)
      (is (= 1 (count @(:watches state))))
      (async/close! ch)
      ;; One change lets the render loop observe the closed response body; the
      ;; next lets its watcher observe the now-closed dirty notification queue.
      (swap! state assoc :requests 1)
      (Thread/sleep 40)
      (swap! state assoc :requests 2)
      (is (await! #(empty? @(:watches state))))
      (finally ((get-in rt [:dashboard :stop!]))))))

(deftest snapshot-remains-the-redaction-boundary
  (let [snapshot (inspect/snapshot (runtime))]
    (is (= false (contains? snapshot :session-id)))
    (is (= false (contains? snapshot :store)))
    (is (= 1 (get-in snapshot [:codex :ws-pool :entries])))
    (is (= 128 (get-in snapshot [:codex :ws-pool :capacity])))
    (is (= true (get-in snapshot [:codex :ws-pool :sessions 0 :continuation])))
    (is (not= "raw-session-id"
              (get-in snapshot [:codex :ws-pool :sessions 0 :id])))))
