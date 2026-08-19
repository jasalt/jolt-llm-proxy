(ns llm-proxy.transport.ws
  "Pooled WebSocket event-source lifecycle and continuation state."
  (:require [clojure.data.json :as json]
            [codex.auth :as auth]
            [codex.ws :as ws]
            [codex.continuation :as continuation]))

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

(defn source
  "Acquire, initialize, and return a WebSocket event source. Any setup failure
  releases ownership before propagating the exception."
  [runtime full-body session-id for-chat]
  (let [store (:store runtime)
        pool (:pool runtime)
        header-builder (fn [] (auth/token store))
        [acquisition request-body used-delta?]
        (acquire-request pool full-body session-id header-builder)]
    (try
      (when used-delta?
        (println "ws-source: using delta continuation"))
      (ws/write-text (:conn acquisition)
                     (json/write-str (assoc request-body :type "response.create")))
      {:read
       (fn [emit]
         (ws/read-until-terminal
           (:conn acquisition)
           (fn [event]
             (emit {:name (:type event) :data (:data event)}))))

       :finalize
       (fn [meta]
         (let [keep? (and (:completed meta) (not= (:response-id meta) ""))]
           (when keep?
             (ws/set-continuation!
               (:conn acquisition)
               {:last-request-body full-body
                :last-response-id (:response-id meta)
                :last-response-items
                (continuation/response-output-to-input-items
                  (:items meta) for-chat)}))
           ((:release acquisition) keep?)))}
      (catch Throwable error
        ((:release acquisition) false)
        (throw error)))))
