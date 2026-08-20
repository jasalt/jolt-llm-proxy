(ns llm-proxy.core
  "Server startup, runtime state, and CLI entry point."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [babashka.cli :as bcli]
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
            [llm-proxy.id :as id]
            [llm-proxy.time :as time]))

;; ---------------------------------------------------------------------------
;; System atom
;; ---------------------------------------------------------------------------

(defonce system (atom nil))

(defn- random-session-id [random-hex]
  (random-hex 16))

(defn system-now-ms [] (time/now-ms))

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
             nrepl-port start-nrepl dashboard now-ms random-hex]
      :or {port 8080 api-key "" dashboard false
           run-server adapter/run-server
           stop-server adapter/stop-server
           start-nrepl start-nrepl!
           now-ms system-now-ms
           random-hex id/random-hex}}]
  (when @system
    (throw (ex-info "system already started" {})))
  ;; 1. Token store
  (let [auth-path (or auth-path auth/default-auth-path)
        store (or store (auth/start! auth-path))]
    (when-not (auth/authenticated? store)
      (throw (ex-info (str "Not authenticated — run `jolt run login` first. Path: " auth-path)
                      {})))
    ;; Each start owns an isolated pool and an explicit handler dependency map.
    (let [session-id (random-session-id random-hex)
          pool (ws/make-pool now-ms)
          runtime {:store store :pool pool :session-id session-id
                   :api-key api-key :port port :started-at (now-ms)
                   :now-ms now-ms :random-hex random-hex
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
            (doseq [[_ sess] @(:sessions pool)]
              (try (ws/close-conn (:conn sess)) (catch Throwable _ nil)))
            (reset! (:sessions pool) {})
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
      (doseq [[_ sess] @(:sessions pool)]
        (try (ws/close-conn (:conn sess)) (catch Throwable _ nil)))
      (reset! (:sessions pool) {}))
    (log/info {:event :proxy-stopped})))

;; ---------------------------------------------------------------------------
;; CLI
;; ---------------------------------------------------------------------------

(defn- env
  "Read a project environment variable with a fallback."
  [key fallback]
  (or (System/getenv key) fallback))

(def serve-option-spec
  {:port {:alias :p
          :coerce :long
          :validate #(<= 1 % 65535)
          :desc "Listen for proxy HTTP requests on this loopback TCP port (overrides JOLT_LLM_PROXY_ADDR)"}
   :nrepl {:coerce :boolean
            :desc "Start the loopback development nREPL server"}
   :nrepl-port {:alias :np
                :coerce :long
                :validate #(<= 1 % 65535)
                :desc "Listen for nREPL connections on this port (requires --nrepl)"}
   :dashboard {:coerce :boolean
               :desc "Enable the loopback read-only operator dashboard"}})

(defn- serve-options
  "Strictly parse the supported `serve` options for direct unit coverage."
  [args]
  (bcli/parse-opts args {:spec serve-option-spec
                          :restrict true
                          :restrict-args true
                          :no-keyword-opts true}))

(defn- nrepl-flag? [args]
  "True only when the explicit development/debug nREPL flag is present."
  (true? (:nrepl (serve-options args))))

(defn serve-command
  "Start the proxy from a babashka.cli command dispatch result."
  [{:keys [opts]}]
  (let [nrepl? (:nrepl opts)
        dashboard? (:dashboard opts)
        cli-port (:port opts)
        cli-nrepl-port (:nrepl-port opts)
        _ (when (and cli-nrepl-port (not nrepl?))
            (throw (ex-info "--nrepl-port requires --nrepl" {})))
        addr    (env "JOLT_LLM_PROXY_ADDR" "127.0.0.1:8080")
        api-key (env "JOLT_LLM_PROXY_API_KEY" "")
        ;; ring-chez-adapter is loopback-only; reject misleading hosts.
        [host port-str] (str/split addr #":" 2)
        _ (when-not (contains? #{"127.0.0.1" "localhost"} host)
            (throw (ex-info "JOLT_LLM_PROXY_ADDR must use 127.0.0.1 or localhost"
                            {:address addr})))
        port (or cli-port (if port-str (Integer/parseInt port-str) 8080))
        nrepl-port (when nrepl?
                     (or cli-nrepl-port
                         (Integer/parseInt (env "JOLT_NREPL_PORT"
                                                (str default-nrepl-port)))))]
    (start! :port port :api-key api-key :nrepl-port nrepl-port
            :dashboard dashboard?)
    ;; Block until interrupted.
    (.addShutdownHook (Runtime/getRuntime)
      (Thread. ^Runnable stop!))
    @(promise)))

(defn licenses-command
  "Print the embedded third-party-license notice. The resource is included in
  standalone builds through the repository's `:jolt/build :embed` setting."
  [_]
  (print (slurp (io/resource "THIRD_PARTY_LICENSES.md"))))

(def command-tree
  "Extensible command tree. `:restrict` rejects unrecognized command options."
  {:cmd {"serve" {:fn serve-command
                   :doc "Start the OpenAI-compatible proxy"
                   :spec serve-option-spec
                   :restrict true
                   :restrict-args true}
         "login" {:fn (fn [_] (cli/login!))
                  :doc "Log in to ChatGPT / Codex"}
         "logout" {:fn (fn [_] (cli/logout!))
                   :doc "Remove saved credentials"}
         "usage" {:fn (fn [_] (cli/usage!))
                  :doc "Show weekly Codex allowance"}
         "info" {:fn (fn [_] (cli/info!))
                 :doc "Show saved credential and JWT information"}
         "licenses" {:fn licenses-command
                     :doc "Show third-party license notice"}}})

(defn -main [& args]
  ;; Preserve the historical no-argument behavior while gaining generated help,
  ;; command validation, and an extensible option surface.
  (bcli/dispatch command-tree (if (seq args) args ["serve"])
                 {:prog "jolt -m llm-proxy.core" :help true}))
