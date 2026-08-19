(ns llm-proxy.inspect
  "Redacted, serialization-safe runtime inspection snapshots."
  (:require [codex.auth :as auth]
            [codex.ws :as ws]
            [llm-proxy.id :as id]))

(def dashboard-session-limit 8)

(defn- elapsed-seconds [now then]
  (when (number? then)
    (max 0 (long (/ (- now then) 1000)))))

(defn- session-summary [now [session-id {:keys [busy created-at last-used conn]}]]
  {:id (id/short-hash session-id)
   :state (if busy :busy :idle)
   :age-seconds (elapsed-seconds now created-at)
   :idle-seconds (when-not busy (elapsed-seconds now last-used))
   :continuation (and conn (:continuation conn)
                      (some? @(:continuation conn)))})

(defn- pool-summary [now pool]
  (let [sessions (or (some-> pool deref) {})
        entries (vals sessions)]
    {:entries (count entries)
     :capacity ws/ws-max-pooled-sessions
     :busy (count (filter :busy entries))
     :idle (count (remove :busy entries))
     :idle-ttl-seconds (long (/ ws/ws-idle-ttl-ms 1000))
     :max-age-seconds (long (/ ws/ws-session-max-age-ms 1000))
     :with-continuation
     (count (filter (fn [{:keys [conn]}]
                      (and conn (:continuation conn)
                           (some? @(:continuation conn)))) entries))
     ;; Never expose the client-supplied session key; only a bounded short hash.
     :sessions (->> sessions
                    (sort-by (fn [[_ session]] (or (:last-used session) 0)) >)
                    (take dashboard-session-limit)
                    (mapv #(session-summary now %)))}))

(defn- token-expiry-seconds [store now]
  (when-let [expires-at (:expires_at @store)]
    (elapsed-seconds expires-at now)))

(defn snapshot
  "Return only safe scalar operational state from an application runtime."
  [runtime]
  (let [now (System/currentTimeMillis)
        started-at (:started-at runtime)]
    {:started-at started-at
     :uptime-seconds (elapsed-seconds now started-at)
     :listener {:address (str "127.0.0.1:" (:port runtime))
                :request-limit-bytes 1048576}
     :features {:api-key-auth-enabled (not= "" (:api-key runtime))
                :nrepl-enabled (some? (:stop-nrepl runtime))
                :dashboard-enabled (true? (:dashboard-enabled runtime))}
     :requests (long (or @(:requests runtime) 0))
     :codex {:authenticated (auth/authenticated? (:store runtime))
             :token-expiry-seconds (token-expiry-seconds (:store runtime) now)
             :ws-pool (pool-summary now (:pool runtime))}}))
