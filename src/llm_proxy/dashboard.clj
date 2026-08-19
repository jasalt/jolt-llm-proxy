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

(defn- fragment [runtime]
  (let [s (inspect/snapshot runtime)
        pool (get-in s [:codex :ws-pool])]
    (str "<section id=\"dashboard\">"
         "<p><strong>Proxy</strong> listening on 127.0.0.1:" (escape-html (:proxy-port s))
         " · uptime " (escape-html (:uptime-seconds s)) "s</p>"
         "<p>Backend authentication: <strong>" (if (get-in s [:codex :authenticated]) "ready" "not ready") "</strong>"
         " · API-key guard: " (if (:api-key-auth-enabled s) "enabled" "disabled")
         " · nREPL: " (if (:nrepl-enabled s) "enabled" "disabled") "</p>"
         "<p>Requests: " (escape-html (:requests s))
         " · WebSocket sessions: " (escape-html (:entries pool))
         " (busy " (escape-html (:busy pool))
         ", idle " (escape-html (:idle pool))
         ", continuations " (escape-html (:with-continuation pool)) ")</p>"
         "<p class=\"muted\">Read-only local operator view. Refreshes over SSE.</p>"
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
