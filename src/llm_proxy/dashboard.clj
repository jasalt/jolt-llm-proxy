(ns llm-proxy.dashboard
  "Optional read-only Glimmer/Datastar operator dashboard. The only reactive
  value is a bounded, redacted `inspect/snapshot` presentation model."
  (:require [clojure.java.io :as io]
            [llm-proxy.jolt-shims]
            [dev.onionpancakes.chassis.core :as chassis]
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

(defn- chassis-html
  [tree]
  (chassis/html tree))

(defn- session-row
  [{:keys [id state age-seconds idle-seconds continuation]}]
  [:tr
   [:td id]
   [:td (or (some-> state name) "")]
   [:td age-seconds "s"]
   [:td (or idle-seconds "—")]
   [:td (if continuation "yes" "no")]])

(defn- definition-list
  [entries]
  [:dl
   (mapcat (fn [[term value]] [[:dt term] [:dd value]]) entries)])

(defn- fragment-tree
  [snapshot]
  (let [pool (get-in snapshot [:codex :ws-pool])
        features (:features snapshot)
        token-expiry (get-in snapshot [:codex :token-expiry-seconds])]
    (with-meta
      [
       [:h2 "Runtime"]
     (definition-list
       [["Listener" (get-in snapshot [:listener :address])]
        ["nREPL Listener" (if (:nrepl-enabled features)
                            (get-in snapshot [:nrepl :address])
                            "Disabled")]
        ["Uptime" (str (:uptime-seconds snapshot) " seconds")]
        ["Requests served" (:requests snapshot)]
        ["Request limit" (str (get-in snapshot [:listener :request-limit-bytes]) " bytes")]
        ["API-key guard" (if (:api-key-auth-enabled features) "enabled" "disabled")]
        ["Codex authentication" (if (get-in snapshot [:codex :authenticated]) "ready" "not ready")]
        ["Token expiry" (if (some? token-expiry)
                           (str token-expiry " seconds remaining")
                           "unavailable")]])
     [:h2 "WebSocket pool"]
     (definition-list
       [["Sessions" (str (:entries pool) " / " (:capacity pool)
                          " (busy " (:busy pool) ", idle " (:idle pool) ")")]
        ["Continuations" (:with-continuation pool)]
        ["Idle TTL" (str (:idle-ttl-seconds pool) " seconds")]
        ["Maximum age" (str (:max-age-seconds pool) " seconds")]])
     [:h3 "Recent pooled sessions"]
     (if (seq (:sessions pool))
       [:table
        [:thead [:tr
                 [:th "hash"] [:th "state"] [:th "age"]
                 [:th "idle"] [:th "continuation"]]]
        [:tbody (map session-row (:sessions pool))]]
       [:p {:class "muted"} "No pooled WebSocket sessions."])
     [:p {:class "muted"}
      "Read-only local operator view. Session identifiers are short hashes; no credentials, API keys, prompts, or upstream payloads are shown."]]
      {::chassis/content true})))

(defn- fragment
  [snapshot]
  (chassis-html (fragment-tree snapshot)))

(defn page
  [snapshot]
  (let [init (datastar/init-opts {:selector "#dashboard"})]
    (chassis-html
      [chassis/doctype-html5
       [:html
        [:head
         [:meta {:charset "utf-8"}]
         [:title "Jolt LLM Proxy"]
         [:meta {:http-equiv "Content-Security-Policy"
                 :content "default-src 'self'; script-src 'self' 'unsafe-eval'; style-src 'unsafe-inline'"}]
         [:style
          "body{font-family:system-ui,sans-serif;max-width:50rem;margin:2rem auto;padding:0 1rem}"
          "dl{display:grid;grid-template-columns:max-content 1fr;gap:.4rem 1rem}dt{font-weight:600}dd{margin:0}"
          "table{border-collapse:collapse;width:100%}th,td{border-bottom:1px solid #ddd;padding:.35rem;text-align:left}"
          ".muted{color:#666}"]
         [:script {:type "module" :src "/_llm-proxy/datastar.js"}]]
        [:body
         [:h1 "Jolt LLM Proxy"]
         [:section (merge {:id "dashboard"} init)
          (fragment-tree snapshot)]]]])))

(defn static-js []
  (slurp (or (io/resource "public/js/datastar-v1.0.3")
             (throw (ex-info "vendored Datastar bundle is missing" {})))))

(defn- render-response
  [state request]
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

(defn route
  [runtime req]
  (when (:dashboard-enabled runtime)
    (case (:uri req)
      "/_llm-proxy" ((get-in runtime [:dashboard :handler]) req)
      "/_llm-proxy/" ((get-in runtime [:dashboard :handler]) req)
      "/_llm-proxy/datastar.js" (js-response)
      nil)))