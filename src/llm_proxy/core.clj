(ns llm-proxy.core
  "Server startup, runtime state, and CLI entry point."
  (:require [clojure.string :as str]
            [ring-chez.adapter :as adapter]
            [codex.auth :as auth]
            [codex.ws :as ws]
            [llm-proxy.proxy :as proxy]
            [codex.cli :as cli]
            [llm-proxy.id :as id]))

;; ---------------------------------------------------------------------------
;; System atom
;; ---------------------------------------------------------------------------

(defonce system (atom nil))

(defn- random-session-id []
  (id/random-hex 16))

;; ---------------------------------------------------------------------------
;; Start / stop
;; ---------------------------------------------------------------------------

(defn start!
  "Initialise the proxy. Production defaults can be replaced at explicit
  dependency seams for deterministic lifecycle tests."
  [& {:keys [port api-key auth-path store run-server stop-server]
      :or {port 8080 api-key ""
           run-server adapter/run-server
           stop-server adapter/stop-server}}]
  (when @system
    (throw (ex-info "system already started" {})))
  ;; 1. Token store
  (let [auth-path (or auth-path auth/default-auth-path)
        store (or store (auth/start! auth-path))]
    (when-not (auth/authenticated? store)
      (throw (ex-info (str "Not authenticated — run `jolt run login` first. Path: " auth-path)
                      {})))
    ;; Each start owns an isolated pool and an explicit handler dependency map.
    (let [session-id (random-session-id)
          pool (atom {})
          runtime {:store store :pool pool :session-id session-id
                   :api-key api-key}
          handler (proxy/make-handler runtime)]
      (try
        (let [srv (run-server handler
                    {:port port
                     :strategy :threads
                     :worker-threads 8
                     :max-request-bytes 1048576
                     :keep-alive-timeout-ms 30000
                     :write-timeout-ms 30000})
              running (assoc runtime :server srv :handler handler
                             :stop-server stop-server)]
          (reset! system running)
          (println (str "Proxy listening on http://127.0.0.1:" port))
          ;; Correlate sessions via a short hash; never log the raw id.
          (println (str "  session-id: " (id/short-hash session-id) " (hashed)"))
          (println (str "  api-key auth: " (if (= api-key "") "disabled" "enabled")))
          running)
        (catch Throwable e
          (doseq [[_ sess] @pool]
            (try (ws/close-conn (:conn sess)) (catch Throwable _ nil)))
          (reset! pool {})
          (reset! system nil)
          (throw e))))))

(defn stop!
  "Shut down the server, close pooled WS connections, clear system."
  []
  (when-let [running @system]
    ;; Clear ownership first so repeated stop calls are harmless.
    (reset! system nil)
    (when-let [srv (:server running)]
      (try ((:stop-server running) srv) (catch Throwable _ nil)))
    (let [pool (:pool running)]
      (doseq [[_ sess] @pool]
        (try (ws/close-conn (:conn sess)) (catch Throwable _ nil)))
      (reset! pool {}))
    (println "Proxy stopped.")))

;; ---------------------------------------------------------------------------
;; CLI
;; ---------------------------------------------------------------------------

(defn- env
  "Read a project environment variable with a fallback."
  [key fallback]
  (or (System/getenv key) fallback))

(defn -main [& args]
  (let [command (if (seq args) (first args) "serve")]
    (case command
      "serve"
      (let [addr    (env "JOLT_LLM_PROXY_ADDR" "127.0.0.1:8080")
            api-key (env "JOLT_LLM_PROXY_API_KEY" "")
            ;; ring-chez-adapter is loopback-only; reject misleading hosts.
            [host port-str] (str/split addr #":" 2)
            _ (when-not (contains? #{"127.0.0.1" "localhost"} host)
                (throw (ex-info "JOLT_LLM_PROXY_ADDR must use 127.0.0.1 or localhost"
                                {:address addr})))
            port (if port-str (Integer/parseInt port-str) 8080)]
        (start! :port port :api-key api-key)
        ;; Block until interrupted.
        (.addShutdownHook (Runtime/getRuntime)
          (Thread. ^Runnable stop!))
        @(promise))

      "login"
      (cli/login!)

      "logout"
      (cli/logout!)

      "usage"
      (cli/usage!)

      "info"
      (cli/info!)

      (println (str "Unknown command: " command "\nUsage: serve | login | logout | usage | info")))))
