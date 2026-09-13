(ns llm-proxy.dashboard
  "Optional read-only Glimmer/Datastar operator dashboard. The only reactive
  value is a bounded, redacted `inspect/snapshot` presentation model."
  (:require [clojure.java.io :as io]
            [ebb.core :as m]
            [glimmer.ratom :as ratom]
            [jolt.datastar.core :as datastar]
            [llm-proxy.flow :as flow]
            [llm-proxy.inspect :as inspect]))

(defn- sampler-task
  [runtime state interval-ms sleep-task]
  (m/sp
    (loop []
      (reset! state (inspect/snapshot runtime))
      (m/? (sleep-task interval-ms))
      (recur))))

(defn- escape-html [value]
  (let [s (str value)]
    (-> s
        (.replace "&" "&amp;")
        (.replace "<" "&lt;")
        (.replace ">" "&gt;")
        (.replace "\"" "&quot;")
        (.replace "'" "&#39;"))))

(defn- session-row [{:keys [id state age-seconds idle-seconds continuation]}]
  (str "<tr><td>" (escape-html id) "</td><td>" (escape-html (name state))
       "</td><td>" (escape-html age-seconds) "s</td><td>"
       (escape-html (or idle-seconds "—")) "</td><td>"
       (if continuation "yes" "no") "</td></tr>"))

(defn- fragment [snapshot]
  (let [pool (get-in snapshot [:codex :ws-pool])
        features (:features snapshot)
        token-expiry (get-in snapshot [:codex :token-expiry-seconds])]
    (str "<h2>Runtime</h2><dl>"
         "<dt>Listener</dt><dd>" (escape-html (get-in snapshot [:listener :address])) "</dd>"
         "<dt>nREPL Listener</dt><dd>" (if (:nrepl-enabled features)
                                          (escape-html (get-in snapshot [:nrepl :address]))
                                          "Disabled") "</dd>"
         "<dt>Uptime</dt><dd>" (escape-html (:uptime-seconds snapshot)) " seconds</dd>"
         "<dt>Requests served</dt><dd>" (escape-html (:requests snapshot)) "</dd>"
         "<dt>Request limit</dt><dd>" (escape-html (get-in snapshot [:listener :request-limit-bytes])) " bytes</dd>"
         "<dt>API-key guard</dt><dd>" (if (:api-key-auth-enabled features) "enabled" "disabled") "</dd>"
         "<dt>Codex authentication</dt><dd>" (if (get-in snapshot [:codex :authenticated]) "ready" "not ready") "</dd>"
         "<dt>Token expiry</dt><dd>" (if (some? token-expiry)
                                         (str (escape-html token-expiry) " seconds remaining")
                                         "unavailable") "</dd></dl>"
         "<h2>WebSocket pool</h2><dl>"
         "<dt>Sessions</dt><dd>" (escape-html (:entries pool)) " / " (escape-html (:capacity pool))
         " (busy " (escape-html (:busy pool)) ", idle " (escape-html (:idle pool)) ")</dd>"
         "<dt>Continuations</dt><dd>" (escape-html (:with-continuation pool)) "</dd>"
         "<dt>Idle TTL</dt><dd>" (escape-html (:idle-ttl-seconds pool)) " seconds</dd>"
         "<dt>Maximum age</dt><dd>" (escape-html (:max-age-seconds pool)) " seconds</dd></dl>"
         "<h3>Recent pooled sessions</h3>"
         (if (seq (:sessions pool))
           (str "<table><thead><tr><th>hash</th><th>state</th><th>age</th><th>idle</th><th>continuation</th></tr></thead><tbody>"
                (apply str (map session-row (:sessions pool))) "</tbody></table>")
           "<p class=\"muted\">No pooled WebSocket sessions.</p>")
         "<p class=\"muted\">Read-only local operator view. Session identifiers are short hashes; no credentials, API keys, prompts, or upstream payloads are shown.</p>")))

(defn- html-attributes [attributes]
  (apply str
         (map (fn [[key value]]
                (str " " (name key) "=\"" (escape-html value) "\""))
              attributes)))

(defn page [snapshot]
  (let [init (datastar/init-opts {:selector "#dashboard"})]
    (str "<!doctype html><html><head><meta charset=\"utf-8\"><title>Jolt LLM Proxy</title>"
         "<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'self'; script-src 'self' 'unsafe-eval'; style-src 'unsafe-inline'\">"
         "<style>body{font-family:system-ui,sans-serif;max-width:50rem;margin:2rem auto;padding:0 1rem}"
         "dl{display:grid;grid-template-columns:max-content 1fr;gap:.4rem 1rem}dt{font-weight:600}dd{margin:0}"
         "table{border-collapse:collapse;width:100%}th,td{border-bottom:1px solid #ddd;padding:.35rem;text-align:left}"
         ".muted{color:#666}</style>"
         "<script type=\"module\" src=\"/_llm-proxy/datastar.js\"></script></head>"
         "<body><h1>Jolt LLM Proxy</h1>"
         "<section id=\"dashboard\"" (html-attributes init) ">"
         (fragment snapshot) "</section></body></html>")))

(defn static-js []
  (slurp (or (io/resource "public/js/datastar.js")
             (throw (ex-info "vendored Datastar bundle is missing" {})))))

(defn- render-response [state request]
  (if (:jolt.datastar/sse-request request)
    {:status 200
     :headers {"Content-Type" "text/html; charset=utf-8"}
     :body (fragment @state)}
    {:status 200
     :headers {"Content-Type" "text/html; charset=utf-8"
               "Content-Security-Policy" "default-src 'self'; script-src 'self' 'unsafe-eval'; style-src 'unsafe-inline'"
               "X-Frame-Options" "DENY"}
     :body (page @state)}))

(defn js-response []
  {:status 200
   :headers {"Content-Type" "application/javascript"
             "Cache-Control" "public, max-age=31536000, immutable"}
   :body (static-js)})

(defn start!
  "Start one redacted snapshot ratom, its Ebb sampler, and the wrapped Datastar
  sub-handler. The returned map is an explicit core-owned lifecycle resource."
  ([runtime] (start! runtime {}))
  ([runtime {:keys [interval-ms sleep-task on-failure rate-limit-ms]
             :or {interval-ms 1000
                  sleep-task m/sleep
                  on-failure (fn [_] nil)
                  rate-limit-ms 15}}]
   (let [state (ratom/atom (inspect/snapshot runtime))
         handler (datastar/wrap-datastar
                   (partial render-response state)
                   {:rate-limit-ms rate-limit-ms})
         stop! (flow/start-task!
                 (sampler-task runtime state interval-ms sleep-task)
                 {:on-failure on-failure})]
     {:state state
      :handler handler
      :stop! stop!})))

(defn route [runtime req]
  (when (:dashboard-enabled runtime)
    (case (:uri req)
      "/_llm-proxy" ((get-in runtime [:dashboard :handler]) req)
      "/_llm-proxy/" ((get-in runtime [:dashboard :handler]) req)
      "/_llm-proxy/datastar.js" (js-response)
      nil)))
