(ns codex.core-test
  (:require [clojure.test :refer [deftest is]]
            [codex.core :as core]))

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
