(ns llm-proxy.transport.ws
  "Pooled WebSocket event-source lifecycle and continuation state."
  (:require [clojure.data.json :as json]
            [clojure.tools.logging :as log]
            [ebb.core :as m]
            [codex.auth :as auth]
            [codex.ws :as ws]
            [codex.continuation :as continuation]
            [llm-proxy.id :as id]))

(defn- acquire-request [pool full-body session-id header-builder]
  (let [acquisition (ws/acquire pool session-id header-builder)
        conn (:conn acquisition)
        state (ws/get-continuation conn)]
    (if (nil? state)
      [acquisition full-body false]
      (let [[delta valid?]
            (continuation/build-delta-request full-body state)]
        (if valid?
          [acquisition delta true]
          (do
            (ws/clear-continuation! conn)
            [acquisition full-body false]))))))

(defn- produce-events
  [conn emit!]
  (m/sp
    (loop []
      (let [message (m/? (m/via m/blk (ws/read-message conn)))]
        (when (= :close (:type message))
          (throw (ex-info "ws: connection closed by peer" {})))
        (if-let [event (ws/read-event message)]
          (do (m/? (emit! {:name (:type event) :data (:data event)}))
              (if (ws/terminal-types (:type event)) :eof (recur)))
          (recur))))))

(defn acquire
  "Return an Ebb task owning WebSocket setup, reads, and exactly one release.
  `:release!` commits continuation only for a successfully completed consumer."
  [runtime full-body session-id for-chat]
  (m/sp
    (let [store (:store runtime)
          pool (:pool runtime)
          owned (atom nil)]
      (try
        (let [[acquisition request-body used-delta?]
              (m/? (m/via m/blk
                     (acquire-request pool full-body session-id
                                      (fn [] (auth/token store)))))]
          (reset! owned acquisition)
          (when used-delta?
            (log/debug {:event :ws-delta-continuation
                        :session-hash (when session-id (id/short-hash session-id))}))
          (m/? (m/via m/blk
                 (ws/write-text (:conn acquisition)
                                (json/write-str (assoc request-body :type "response.create")))))
          {:produce (fn [emit!] (produce-events (:conn acquisition) emit!))
           :abort! (fn [] ((:release acquisition) false))
           :release! (fn [outcome]
                       (let [meta (:result outcome)
                             keep? (and (= :completed (:status outcome))
                                        (:completed meta)
                                        (not= (:response-id meta) ""))]
                         (when keep?
                           (ws/set-continuation!
                             (:conn acquisition)
                             {:last-request-body full-body
                              :last-response-id (:response-id meta)
                              :last-response-items
                              (continuation/response-output-to-input-items
                                (:items meta) for-chat)}))
                         ((:release acquisition) keep?)))})
        (catch Throwable error
          (when-let [acquisition @owned]
            ((:release acquisition) false))
          (if (:type (ex-data error))
            (throw error)
            (throw (ex-info "websocket transport setup failed"
                            {:type :ws-transport} error))))))))

(defn source
  "Acquire, initialize, and return a WebSocket event source. Any setup failure
  releases ownership before propagating the exception."
  [runtime full-body session-id for-chat]
  (let [store (:store runtime)
        pool (:pool runtime)
        header-builder (fn [] (auth/token store))
        acquisition (atom nil)]
    (try
      (let [[owned request-body used-delta?]
            (acquire-request pool full-body session-id header-builder)
            _ (reset! acquisition owned)]
        (when used-delta?
          (log/debug {:event :ws-delta-continuation
                      :session-hash (when session-id (id/short-hash session-id))}))
        (ws/write-text (:conn owned)
                       (json/write-str (assoc request-body :type "response.create")))
        {:read
         (fn [emit]
           (ws/read-until-terminal
             (:conn owned)
             (fn [event]
               (emit {:name (:type event) :data (:data event)}))))

         :finalize
         (fn [meta]
           (let [keep? (and (:completed meta) (not= (:response-id meta) ""))]
             (when keep?
               (ws/set-continuation!
                 (:conn owned)
                 {:last-request-body full-body
                  :last-response-id (:response-id meta)
                  :last-response-items
                  (continuation/response-output-to-input-items
                    (:items meta) for-chat)}))
             ((:release owned) keep?)))})
      (catch Throwable error
        (when-let [owned @acquisition]
          ((:release owned) false))
        ;; The proxy can safely retry WS establishment and initial-write
        ;; failures through SSE. Preserve already classified failures (notably
        ;; credential refresh errors) so they retain their correct public
        ;; status and do not trigger a transport change.
        (if (:type (ex-data error))
          (throw error)
          (throw (ex-info "websocket transport setup failed"
                          {:type :ws-transport}
                          error))))))
)