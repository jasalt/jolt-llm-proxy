(ns llm-proxy.inspect
  "Redacted, serialization-safe runtime inspection snapshots."
  (:require [codex.auth :as auth]))

(defn- pool-summary [pool]
  (let [entries (vals (or (some-> pool deref) {}))]
    {:entries (count entries)
     :busy (count (filter :busy entries))
     :idle (count (remove :busy entries))
     :with-continuation
     (count (filter (fn [{:keys [conn]}]
                      (and conn (:continuation conn)
                           (some? @(:continuation conn)))) entries))}))

(defn snapshot
  "Return only safe scalar operational state from an application runtime."
  [runtime]
  (let [now (System/currentTimeMillis)
        started-at (:started-at runtime)]
    {:started-at started-at
     :uptime-seconds (long (/ (- now started-at) 1000))
     :proxy-port (:port runtime)
     :api-key-auth-enabled (not= "" (:api-key runtime))
     :nrepl-enabled (some? (:stop-nrepl runtime))
     :dashboard-enabled (true? (:dashboard-enabled runtime))
     :codex {:authenticated (auth/authenticated? (:store runtime))
             :ws-pool (pool-summary (:pool runtime))}
     :requests (long (or @(:requests runtime) 0))}))
