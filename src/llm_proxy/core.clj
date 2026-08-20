(ns llm-proxy.core
  "Server startup, runtime state, and CLI entry point."
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [ring-chez.adapter :as adapter]
            [jolt.nrepl :as nrepl]
            ;; Kept as a concrete require so Jolt includes the middleware in a
            ;; standalone build as well as interpreted `serve --nrepl` runs.
            [nrepl.middleware]
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

(def default-nrepl-port 7888)

(defn start-nrepl!
  "Start the optional loopback nREPL server and return its stop function.
  `nrepl.middleware/default-middleware` adds sessions, interruptible eval,
  completion, and lookup to Jolt's built-in nREPL handler."
  ([port]
   (start-nrepl! port nrepl/start))
  ([port nrepl-start]
   (nrepl-start port ['nrepl.middleware/default-middleware])))

(defn start!
  "Initialise the proxy. Production defaults can be replaced at explicit
  dependency seams for deterministic lifecycle tests."
  [& {:keys [port api-key auth-path store run-server stop-server
             nrepl-port start-nrepl dashboard]
      :or {port 8080 api-key "" dashboard false
           run-server adapter/run-server
           stop-server adapter/stop-server
           start-nrepl start-nrepl!}}]
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
                   :api-key api-key :port port :started-at (System/currentTimeMillis)
                   :requests (atom 0) :dashboard-enabled dashboard}
          handler (proxy/make-handler runtime)]
      (let [started (atom {})]
        (try
          (let [srv (run-server handler
                      {:port port
                       :strategy :threads
                       :worker-threads 8
                       :max-request-bytes 1048576
                       :keep-alive-timeout-ms 30000
                       :write-timeout-ms 30000})
                _ (swap! started assoc :server srv)
                stop-nrepl (when nrepl-port (start-nrepl nrepl-port))
                _ (swap! started assoc :stop-nrepl stop-nrepl)
                running (assoc runtime :server srv :handler handler
                               :stop-server stop-server
                               :stop-nrepl stop-nrepl)]
            (reset! system running)
            ;; Correlate sessions via a short hash; never log the raw id.
            (log/info {:event :proxy-started
                       :address (str "127.0.0.1:" port)
                       :session-hash (id/short-hash session-id)
                       :api-key-guard (not= api-key "")
                       :nrepl-port nrepl-port
                       :dashboard-enabled dashboard})
            running)
          (catch Throwable e
            (when-let [stop-nrepl (:stop-nrepl @started)]
              (try (stop-nrepl) (catch Throwable _ nil)))
            (when-let [srv (:server @started)]
              (try (stop-server srv) (catch Throwable _ nil)))
            (doseq [[_ sess] @pool]
              (try (ws/close-conn (:conn sess)) (catch Throwable _ nil)))
            (reset! pool {})
            (reset! system nil)
            (throw e)))))))

(defn stop!
  "Shut down the server, close pooled WS connections, clear system."
  []
  (when-let [running @system]
    ;; Clear ownership first so repeated stop calls are harmless.
    (reset! system nil)
    (when-let [stop-nrepl (:stop-nrepl running)]
      (try (stop-nrepl) (catch Throwable _ nil)))
    (when-let [srv (:server running)]
      (try ((:stop-server running) srv) (catch Throwable _ nil)))
    (let [pool (:pool running)]
      (doseq [[_ sess] @pool]
        (try (ws/close-conn (:conn sess)) (catch Throwable _ nil)))
      (reset! pool {}))
    (log/info {:event :proxy-stopped})))

;; ---------------------------------------------------------------------------
;; CLI
;; ---------------------------------------------------------------------------

(defn- env
  "Read a project environment variable with a fallback."
  [key fallback]
  (or (System/getenv key) fallback))

(defn- serve-flags
  [args]
  (cond
    (empty? args) #{}
    (every? #{"--nrepl" "--dashboard"} args) (set args)
    :else (throw (ex-info "Usage: serve [--nrepl] [--dashboard]" {:args args}))))

(defn- nrepl-flag? [args]
  "True only when the explicit development/debug nREPL flag is present."
  (contains? (serve-flags args) "--nrepl"))

(defn -main [& args]
  (let [command (if (seq args) (first args) "serve")
        command-args (if (seq args) (rest args) [])]
    (case command
      "serve"
      (let [flags (serve-flags command-args)
            nrepl? (contains? flags "--nrepl")
            dashboard? (contains? flags "--dashboard")
            addr    (env "JOLT_LLM_PROXY_ADDR" "127.0.0.1:8080")
            api-key (env "JOLT_LLM_PROXY_API_KEY" "")
            ;; ring-chez-adapter is loopback-only; reject misleading hosts.
            [host port-str] (str/split addr #":" 2)
            _ (when-not (contains? #{"127.0.0.1" "localhost"} host)
                (throw (ex-info "JOLT_LLM_PROXY_ADDR must use 127.0.0.1 or localhost"
                                {:address addr})))
            port (if port-str (Integer/parseInt port-str) 8080)
            nrepl-port (when nrepl?
                         (Integer/parseInt (env "JOLT_NREPL_PORT"
                                                 (str default-nrepl-port))))]
        (start! :port port :api-key api-key :nrepl-port nrepl-port
                :dashboard dashboard?)
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