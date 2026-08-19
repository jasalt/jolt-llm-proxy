(ns llm-proxy.dashboard
  "Optional read-only Datastar SSE operator dashboard."
  (:require [clojure.core.async :as async]
            [ring-chez.sse :as sse]
            [llm-proxy.inspect :as inspect]))

(def prefix "/_llm-proxy")

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

(defn- fragment [runtime]
  (let [s (inspect/snapshot runtime)
        pool (get-in s [:codex :ws-pool])
        features (:features s)
        token-expiry (get-in s [:codex :token-expiry-seconds])]
    (str "<section id=\"dashboard\">"
         "<h2>Runtime</h2><dl>"
         "<dt>Listener</dt><dd>" (escape-html (get-in s [:listener :address])) "</dd>"
         "<dt>Uptime</dt><dd>" (escape-html (:uptime-seconds s)) " seconds</dd>"
         "<dt>Requests served</dt><dd>" (escape-html (:requests s)) "</dd>"
         "<dt>Request limit</dt><dd>" (escape-html (get-in s [:listener :request-limit-bytes])) " bytes</dd>"
         "<dt>API-key guard</dt><dd>" (if (:api-key-auth-enabled features) "enabled" "disabled") "</dd>"
         "<dt>nREPL</dt><dd>" (if (:nrepl-enabled features) "enabled" "disabled") "</dd>"
         "<dt>Codex authentication</dt><dd>" (if (get-in s [:codex :authenticated]) "ready" "not ready") "</dd>"
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
         "<p class=\"muted\">Read-only local operator view. Session identifiers are short hashes; no credentials, API keys, prompts, or upstream payloads are shown.</p>"
         "</section>")))

(defn- patch-event [runtime]
  {:event "datastar-patch-elements"
   :data (str "selector #dashboard\n"
              "mode outer\n"
              "elements " (fragment runtime))})

(defn- offer-event! [ch event]
  (async/offer! ch (sse/format-event event)))

(defn page []
  (str "<!doctype html><html><head><meta charset=\"utf-8\"><title>Jolt LLM Proxy</title>"
       "<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'self'; script-src 'self' 'unsafe-eval'; style-src 'unsafe-inline'\">"
       "<style>body{font-family:system-ui,sans-serif;max-width:50rem;margin:2rem auto;padding:0 1rem}"
       "dl{display:grid;grid-template-columns:max-content 1fr;gap:.4rem 1rem}dt{font-weight:600}dd{margin:0}"
       "table{border-collapse:collapse;width:100%}th,td{border-bottom:1px solid #ddd;padding:.35rem;text-align:left}"
       ".muted{color:#666}</style>"
       "<script type=\"module\" src=\"/_llm-proxy/datastar.js\"></script></head>"
       "<body><h1>Jolt LLM Proxy</h1>"
       "<div id=\"dashboard\" data-init=\"@get('/_llm-proxy/events')\">"
       "<p>Connecting…</p></div></body></html>"))

(defn static-js []
  (slurp (or (clojure.java.io/resource "public/js/datastar.js")
             (throw (ex-info "vendored Datastar bundle is missing" {})))))

(defn page-response []
  {:status 200
   :headers {"Content-Type" "text/html; charset=utf-8"
             "Content-Security-Policy" "default-src 'self'; script-src 'self' 'unsafe-eval'; style-src 'unsafe-inline'"
             "X-Frame-Options" "DENY"}
   :body (page)})

(defn js-response []
  {:status 200
   :headers {"Content-Type" "application/javascript"
             "Cache-Control" "public, max-age=31536000, immutable"}
   :body (static-js)})

(defn events-response [runtime]
  (let [ch (async/chan 4)
        watch-key (str (gensym "dashboard-"))
        closed? (atom false)
        stop! (fn []
                (when (compare-and-set! closed? false true)
                  (remove-watch (:requests runtime) watch-key)
                  (async/close! ch)))]
    (offer-event! ch (patch-event runtime))
    (add-watch (:requests runtime) watch-key
               (fn [_ _ _ _]
                 (if (offer-event! ch (patch-event runtime))
                   nil
                   (stop!))))
    ;; Also refresh uptime while otherwise idle. A closed channel causes the
    ;; loop to stop, so disconnected tabs do not retain a watcher indefinitely.
    (async/thread
      (loop []
        (Thread/sleep 1000)
        (when-not @closed?
          (if (offer-event! ch (patch-event runtime))
            (recur)
            (stop!)))))
    {:status 200
     :headers {"Content-Type" "text/event-stream; charset=utf-8"
               "Cache-Control" "no-cache"
               "X-Accel-Buffering" "no"}
     :body ch}))

(defn route [runtime req]
  (when (:dashboard-enabled runtime)
    (case (:uri req)
      "/_llm-proxy" (page-response)
      "/_llm-proxy/" (page-response)
      "/_llm-proxy/datastar.js" (js-response)
      "/_llm-proxy/events" (events-response runtime)
      nil)))
