(ns codex.core
  "Server startup, runtime state, and CLI entry point."
  (:require [clojure.string :as str]
            [ring-chez.adapter :as adapter]
            [codex.auth :as auth]
            [codex.ws :as ws]
            [codex.proxy :as proxy]))

;; ---------------------------------------------------------------------------
;; System atom
;; ---------------------------------------------------------------------------

(defonce system (atom nil))

(defn- random-session-id
  "16 random bytes, hex-encoded, clamped to 64 codepoints."
  []
  (let [b (byte-array 16)]
    (.nextBytes (java.security.SecureRandom.) b)
    (loop [i 0 s (StringBuilder.)]
      (if (>= i 16)
        (let [raw (.toString s)]
          (subs raw 0 (min 64 (count raw))))
        (recur (inc i)
               (.append s (format "%02x" (bit-and (aget b i) 0xFF))))))))

;; ---------------------------------------------------------------------------
;; Start / stop
;; ---------------------------------------------------------------------------

(defn start!
  "Initialise the proxy.  Options:
    :port      — listen port (default 8080)
    :api-key   — proxy API key; empty string = no auth
    :auth-path — path to auth.json (default codex.auth/default-auth-path)"
  [& {:keys [port api-key auth-path]
      :or {port 8080 api-key ""}}]
  (when @system
    (throw (ex-info "system already started" {})))
  ;; 1. Token store
  (let [auth-path (or auth-path auth/default-auth-path)
        store     (auth/start! auth-path)]
    (when-not (auth/authenticated? store)
      (throw (ex-info (str "Not authenticated — run `jolt run login` first. Path: " auth-path)
                      {})))
    ;; 2. Populate proxy/system so handlers can access store + pool + config.
    (let [session-id (random-session-id)
          pool      ws/pool
          _         (proxy/set-system! {:store store
                                        :pool  pool
                                        :server nil  ; filled below
                                        :session-id session-id
                                        :api-key api-key})
          ;; 3. Start HTTP server — pass #'handler for live reload (R3).
          srv       (adapter/run-server #'proxy/handler
                      {:port port
                       :strategy :threads
                       :worker-threads 8})]
      ;; Update system with the running server handle.
      (swap! proxy/system assoc :server srv)
      (reset! system @proxy/system)
      (println (str "Proxy listening on http://127.0.0.1:" port))
      (println (str "  session-id: " session-id))
      (when (not= api-key "")
        (println (str "  api-key:    " api-key)))
      @system)))

(defn stop!
  "Shut down the server, close pooled WS connections, clear system."
  []
  (when-let [srv (:server @system)]
    (try (adapter/stop-server srv) (catch Throwable _ nil))
    (doseq [[_ sess] @ws/pool]
      (try (ws/close-conn (:conn sess)) (catch Throwable _ nil)))
    (reset! ws/pool {})
    (reset! proxy/system nil)
    (reset! system nil)
    (println "Proxy stopped.")))

;; ---------------------------------------------------------------------------
;; CLI
;; ---------------------------------------------------------------------------

(defn- env
  "Read env var with fallback."
  [key fallback]
  (or (System/getenv key) fallback))

(defn- -main [& args]
  (let [command (if (seq args) (first args) "serve")]
    (case command
      "serve"
      (let [addr    (env "CHATGPT_ADAPTER_ADDR" "127.0.0.1:8080")
            api-key (env "CHATGPT_ADAPTER_API_KEY" "")
            ;; Parse host:port
            [host port-str] (str/split addr #":" 2)
            port    (if port-str (Integer/parseInt port-str) 8080)]
        (start! :port port :api-key api-key)
        ;; Block until interrupted.
        (.addShutdownHook (Runtime/getRuntime)
          (Thread. ^Runnable stop!))
        @(promise))

      "login"
      (println "TODO: interactive login — not yet ported. Use `jolt run login`.")

      "logout"
      (println "TODO: logout — not yet ported.")

      "usage"
      (println "TODO: usage — not yet ported.")

      "info"
      (println "TODO: info — not yet ported.")

      (println (str "Unknown command: " command "\nUsage: serve | login | logout | usage | info")))))
