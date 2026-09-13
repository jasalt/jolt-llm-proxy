(ns llm-proxy.core-test
  (:require [clojure.test :refer [deftest is]]
            [ebb.core :as m]
            [llm-proxy.core :as core]
            [llm-proxy.dashboard :as dashboard]))

(def authenticated-store
  (atom {:access_token "token" :account_id "account"}))

(def serve-options
  (ns-resolve 'llm-proxy.core 'serve-options))

(deftest owns-and-stops-an-isolated-system
  (let [stopped (atom [])
        running (core/start! :port 9999
                             :store authenticated-store
                             :run-server (fn [handler options]
                                           {:handler handler :options options})
                             :stop-server #(swap! stopped conj %))]
    (is (some? (:pool running)))
    (is (= 1048576 (get-in running [:server :options :max-request-bytes])))
    (core/stop!)
    (core/stop!)
    (is (= 1 (count @stopped)))
    (is (nil? @core/system))))

(deftest failed-start-leaves-no-system
  (is (thrown? Throwable
               (core/start! :store authenticated-store
                            :run-server (fn [_ _]
                                          (throw (ex-info "bind failed" {}))))))
  (is (nil? @core/system)))

(deftest optional-nrepl-stops-with-the-proxy
  (let [started (atom [])
        stopped (atom 0)
        running (core/start! :port 9999
                             :nrepl-port 7888
                             :store authenticated-store
                             :run-server (fn [handler options]
                                           {:handler handler :options options})
                             :stop-server (fn [_] nil)
                             :start-nrepl (fn [port]
                                            (swap! started conj port)
                                            #(swap! stopped inc)))]
    (is (= [7888] @started))
    (is (fn? (:stop-nrepl running)))
    (core/stop!)
    (is (= 1 @stopped))))

(deftest dashboard-sampler-is-owned-by-core-lifecycle
  (let [starts (atom 0)
        stops (atom 0)
        running (core/start! :port 9999
                             :dashboard true
                             :store authenticated-store
                             :run-server (fn [handler options]
                                           {:handler handler :options options})
                             :stop-server (fn [_] nil)
                             :start-dashboard
                             (fn [_runtime]
                               (swap! starts inc)
                               {:state :safe-presentation-state
                                :stop! #(swap! stops inc)}))]
    (is (= 1 @starts))
    (is (= :safe-presentation-state (get-in running [:dashboard :state])))
    (core/stop!)
    (core/stop!)
    (is (= 1 @stops))))

(deftest failed-start-cancels-dashboard-sampler
  (let [stops (atom 0)]
    (is (thrown? Throwable
                 (core/start! :dashboard true
                              :store authenticated-store
                              :start-dashboard
                              (fn [_runtime]
                                {:state :safe-presentation-state
                                 :stop! #(swap! stops inc)})
                              :run-server
                              (fn [_handler _options]
                                (throw (ex-info "bind failed" {}))))))
    (is (= 1 @stops))
    (is (nil? @core/system))))

(deftest repeated-start-stop-cancels-every-dashboard-task
  (let [starts (atom 0)
        cancels (atom 0)
        sleep-task (fn [_]
                     (fn [_success failure]
                       (fn []
                         (swap! cancels inc)
                         (failure (m/cancelled "dashboard stopped")))))
        start-dashboard (fn [runtime]
                          (swap! starts inc)
                          (dashboard/start! runtime {:sleep-task sleep-task}))]
    (doseq [_ (range 2)]
      (core/start! :port 9999
                   :dashboard true
                   :store authenticated-store
                   :start-dashboard start-dashboard
                   :run-server (fn [handler options]
                                 {:handler handler :options options})
                   :stop-server (fn [_] nil))
      (core/stop!)
      (core/stop!))
    (is (= 2 @starts))
    (is (= 2 @cancels))
    (is (nil? @core/system))))

(deftest licenses-command-prints-the-embedded-notice
  (let [out (with-out-str (core/licenses-command {}))]
    (is (.contains out "This software is licensed under the MIT License."))
    (is (.contains out "third-party open-source"))
    (is (.contains out "https://github.com/jasalt/jolt-llm-proxy"))))

(deftest parses-only-the-explicit-serve-options
  (is (false? (core/nrepl-flag? [])))
  (is (true? (core/nrepl-flag? ["--nrepl"])))
  (is (= {:dashboard true}
         (serve-options ["--dashboard"])))
  (is (= {:port 8090}
         (serve-options ["--port" "8090"])))
  (is (= {:port 8090}
         (serve-options ["-p" "8090"])))
  (is (= {:nrepl true :nrepl-port 7889}
         (serve-options ["--nrepl" "--nrepl-port" "7889"])))
  (is (= {:nrepl-port 7889}
         (serve-options ["-np" "7889"])))
  (is (thrown? Throwable (serve-options ["--port" "0"])))
  (is (thrown? Throwable (serve-options ["--nrepl-port" "65536"])))
  (is (thrown? Throwable (core/serve-command {:opts {:nrepl-port 7889}})))
  (is (thrown? Throwable (serve-options ["--port" "65536"])))
  (is (thrown? Throwable (serve-options ["--unexpected"])))
  (is (thrown? Throwable (serve-options ["unexpected-argument"]))))
