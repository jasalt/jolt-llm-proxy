(require '[jolt.http.tls :as tls] '[jolt.http.net :as net]
         '[clojure.data.json :as json])
;; Convergence check: keyword-as-fn on a host tagged-table returns nil (silent),
;; whereas on a JVM/PMap Clojure map it invokes clojure.core/get. Upstream
;; platform.clj deliberately uses jolt.host/ref-get for this reason.
(def tt (jolt.host/tagged-table :jolt/tls-stream))
(jolt.host/ref-put! tt :write (fn [_ _] :w))
(println "convergence: (:write tt) =" (:write tt) "| (ref-get tt :write) =" ((jolt.host/ref-get tt :write) tt nil))
(def auth (json/read-str (slurp "/home/user/.config/jolt-llm-proxy/auth.json") :key-fn keyword))
(def st (tls/tls-connect "chatgpt.com" 443 false 20000 20000))
(def wfn (jolt.host/ref-get st :write))
(def rfn (jolt.host/ref-get st :read))
(def req (str "GET /backend-api/codex/responses HTTP/1.1\r\nHost: chatgpt.com\r\n"
              "Upgrade: websocket\r\nConnection: Upgrade\r\n"
              "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\n"
              "Authorization: Bearer " (:access_token auth) "\r\n"
              "ChatGPT-Account-Id: " (:account_id auth) "\r\n"
              "OpenAI-Beta: responses_websockets=2026-02-06\r\noriginator: pi\r\n"
              "User-Agent: jolt-llm-proxy/1\r\n"
              "session-id: wshtest01\r\nx-client-request-id: wshtest01\r\n\r\n"))
(wfn st (.getBytes req "ISO-8859-1"))
(Thread/sleep 1500)
(def chunks (atom []))
(loop [i 0]
  (when (< i 25)
    (let [ba (rfn st nil)]
      (when ba (swap! chunks conj (String. ba "ISO-8859-1")))
      (when (not (some #(clojure.string/includes? % "\r\n\r\n") @chunks))
        (recur (inc i))))))
(def resp (apply str @chunks))
(println "HANDSHAKE STATUS:" (first (clojure.string/split-lines resp)))
(println "PREVIEW:" (subs resp 0 (min 220 (.length resp))))
((jolt.host/ref-get st :close))
