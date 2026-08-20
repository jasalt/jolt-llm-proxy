(ns llm-proxy.core-test
  (:require [clojure.test :refer [deftest is]]
            [llm-proxy.core :as core]))

(def authenticated-store
  (atom {:access_token "token" :account_id "account"}))

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

(deftest licenses-command-prints-the-embedded-notice
  (let [out (with-out-str (core/licenses-command {}))]
    (is (.contains out "This software is licensed under the MIT License."))
    (is (.contains out "third-party open-source"))
    (is (.contains out "https://github.com/jasalt/jolt-llm-proxy"))))

(deftest parses-only-the-explicit-serve-options
  (is (false? (core/nrepl-flag? [])))
  (is (true? (core/nrepl-flag? ["--nrepl"])))
  (is (= {:dashboard true}
         (core/serve-options ["--dashboard"])))
  (is (= {:port 8090}
         (core/serve-options ["--port" "8090"])))
  (is (= {:port 8090}
         (core/serve-options ["-p" "8090"])))
  (is (= {:nrepl true :nrepl-port 7889}
         (core/serve-options ["--nrepl" "--nrepl-port" "7889"])))
  (is (= {:nrepl-port 7889}
         (core/serve-options ["-np" "7889"])))
  (is (thrown? Throwable (core/serve-options ["--port" "0"])))
  (is (thrown? Throwable (core/serve-options ["--nrepl-port" "65536"])))
  (is (thrown? Throwable (core/serve-command {:opts {:nrepl-port 7889}})))
  (is (thrown? Throwable (core/serve-options ["--port" "65536"])))
  (is (thrown? Throwable (core/serve-options ["--unexpected"])))
  (is (thrown? Throwable (core/serve-options ["unexpected-argument"]))))
