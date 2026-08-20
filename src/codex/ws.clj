(ns codex.ws
  "Minimal RFC 6455 WebSocket client over the `jolt.http.tls` stream, plus a
  per-session connection pool with idle TTL for multi-turn continuation.

  The outbound TLS stream is a `jolt.host/tagged-table` (NOT a Clojure map):
  read its `:write`/`:read`/`:close` closures with `jolt.host/ref-get`. Client
  frames MUST be masked.

  The frame encoding, buffering, fragmentation, and control-frame handling are
  generic WebSocket machinery retained here for now; the endpoint handshake,
  event protocol, and continuation-aware pool are Codex-specific."
  (:require [clojure.string :as str]
            [clojure.data.json :as json]
            [jolt.http.tls :as tls]
            [jolt.host]))

;; ---------------------------------------------------------------------------
;; Constants
;; ---------------------------------------------------------------------------

(def ws-beta-header "responses_websockets=2026-02-06")
(def ws-url "wss://chatgpt.com/backend-api/codex/responses")
(def ws-guid "258EAFA5-E914-47DA-95CA-C5AB0DC85B11")
(def ws-max-message 67108864) ; 64 MiB
(def ws-max-handshake-bytes 65536)
(def ws-session-max-age-ms (* 55 60 1000))
(def ws-idle-ttl-ms (* 5 60 1000))
(def ws-max-pooled-sessions 128)

;; ---------------------------------------------------------------------------
;; Handshake key + accept
;; ---------------------------------------------------------------------------

(defn handshake-key
  "16 random bytes, base64-std encoded (the Sec-WebSocket-Key value)."
  []
  (let [b (byte-array 16)]
    (.nextBytes (java.security.SecureRandom.) b)
    (.encodeToString (java.util.Base64/getEncoder) b)))

(defn accept-key
  "Expected Sec-WebSocket-Accept: SHA-1 of `key` + `ws-guid`, base64-std."
  [key]
  (let [d (java.security.MessageDigest/getInstance "SHA-1")]
    (.update d (.getBytes (str key ws-guid) "UTF-8"))
    (.encodeToString (java.util.Base64/getEncoder) (.digest d))))

;; ---------------------------------------------------------------------------
;; Inbound byte buffer (accumulates rfn chunks, consumed by frame parser)
;; ---------------------------------------------------------------------------

(defn- buf-ensure
  "Refill the conn's buffer from the TLS read stream until `need` bytes are
  available. Throws on EOF (rfn returns nil)."
  [conn need]
  (let [buf (:buffer conn)
        st (:stream conn)
        rfn (:rfn conn)]
    (loop []
      (let [{:keys [data pos]} @buf]
        (if (>= (- (alength data) pos) need)
          true
          (let [chunk (rfn st nil)]
            (if (nil? chunk)
              (throw (ex-info "ws: connection closed while reading frame"
                              {:available (- (alength data) pos) :need need}))
              (do (swap! buf (fn [{:keys [data pos]}]
                               (let [baos (java.io.ByteArrayOutputStream.)]
                                 (.write baos data pos (- (alength data) pos))
                                 (.write baos chunk 0 (alength chunk))
                                 {:data (.toByteArray baos) :pos 0})))
                  (recur)))))))))

(defn- buf-take!
  "Consume and return exactly `n` bytes from the conn buffer."
  [conn n]
  (buf-ensure conn n)
  (let [buf (:buffer conn)
        out (byte-array n)]
    (swap! buf (fn [{:keys [data pos]}]
                 (System/arraycopy data pos out 0 n)
                 {:data data :pos (+ pos n)}))
    out))

(defn- buf-take-byte [conn]
  ;; Return an unsigned 0..255 value (a signed byte masked to 8 bits) so
  ;; subsequent bit-ops on lengths never go negative.
  (bit-and (aget (buf-take! conn 1) 0) 0xFF))

;; ---------------------------------------------------------------------------
;; Frame writer (client -> server, masked)
;; ---------------------------------------------------------------------------

(defn write-frame
  "Write one RFC 6455 frame: byte0 = FIN|opcode; byte1 = len (client-masked);
  4 random mask bytes; then masked payload. Locks the write path so frames
  from concurrent callers never interleave."
  [conn opcode payload]
  (let [st (:stream conn)
        wfn (:wfn conn)
        pl (if (string? payload) (.getBytes payload "UTF-8") payload)
        n (alength pl)
        mask (byte-array 4)]
    (.nextBytes (java.security.SecureRandom.) mask)
    (let [hsize (cond (< n 126) 2 (< n 65536) 4 :else 10)
          frame (byte-array (+ hsize 4 n))]
      (aset frame 0 (unchecked-byte (bit-or 0x80 opcode)))
      (cond
        (< n 126)
        (aset frame 1 (unchecked-byte (bit-or 0x80 n)))
        (< n 65536)
        (do (aset frame 1 (unchecked-byte (bit-or 0x80 126)))
            (aset frame 2 (unchecked-byte (bit-shift-right (bit-and n 0xFF00) 8)))
            (aset frame 3 (unchecked-byte (bit-and n 0xFF))))
        :else
        (do (aset frame 1 (unchecked-byte (bit-or 0x80 127)))
            (doseq [i (range 8)]
              (aset frame (+ 2 i)
                    (unchecked-byte (bit-and (bit-shift-right n (* (- 7 i) 8)) 0xFF))))))
      (System/arraycopy mask 0 frame hsize 4)
      (dotimes [i n]
        (aset frame (+ hsize 4 i)
              (unchecked-byte (bit-xor (bit-and (aget pl i) 0xFF)
                                       (bit-and (aget mask (mod i 4)) 0xFF)))))
      (locking (:wlock conn)
        (wfn st frame)))))

(defn write-text [conn s] (write-frame conn 0x1 s))
(defn write-close [conn] (write-frame conn 0x8 (byte-array 0)))

(defn close-conn [conn]
  (let [closed? (:closed? conn)]
    (when (or (nil? closed?) (compare-and-set! closed? false true))
      (try (write-close conn) (catch Throwable _ nil))
      (try ((jolt.host/ref-get (:stream conn) :close)) (catch Throwable _ nil)))))


;; ---------------------------------------------------------------------------
;; Dial + handshake
;; ---------------------------------------------------------------------------

(defn- get-header
  "Case-insensitive lookup of an HTTP header value from a raw header block."
  [header-str name]
  (let [lines (str/split-lines header-str)]
    (some (fn [line]
            (let [idx (.indexOf line ":")]
              (when (and (>= idx 0)
                         (= (str/lower-case (subs line 0 idx))
                            (str/lower-case name)))
                (str/trim (subs line (inc idx))))))
          (rest lines))))

(defn dial
  "Open a WebSocket connection to `ws-url` and complete the upgrade handshake.
  Returns a conn map carrying the stream, read/write closRefs, a byte buffer for
  leftover frame bytes, and slots for pool state. Throws if the upgrade fails
  or the accept key mismatches."
  [token account-id session-id]
  (let [st (tls/tls-connect "chatgpt.com" 443 false 20000 20000)
        wfn (jolt.host/ref-get st :write)
        rfn (jolt.host/ref-get st :read)
        key (handshake-key)
        req (str "GET /backend-api/codex/responses HTTP/1.1\r\n"
                 "Host: chatgpt.com\r\n"
                 "Upgrade: websocket\r\n"
                 "Connection: Upgrade\r\n"
                 "Sec-WebSocket-Key: " key "\r\n"
                 "Sec-WebSocket-Version: 13\r\n"
                 "Authorization: Bearer " token "\r\n"
                 "ChatGPT-Account-Id: " account-id "\r\n"
                 "OpenAI-Beta: responses_websockets=2026-02-06\r\n"
                 "originator: pi\r\n"
                 "User-Agent: jolt-llm-proxy/1\r\n"
                 "session-id: " session-id "\r\n"
                 "x-client-request-id: " session-id "\r\n"
                 "\r\n")
        _ (wfn st (.getBytes req "ISO-8859-1"))
        [header-bytes leftover]
        (loop [acc (java.io.ByteArrayOutputStream.)]
          (let [chunk (rfn st nil)]
            (when (nil? chunk)
              (throw (ex-info "ws: no handshake response (connection closed)"
                              {:session-id session-id})))
            (.write acc chunk 0 (alength chunk))
            (when (> (.size acc) ws-max-handshake-bytes)
              (throw (ex-info "ws: handshake headers exceed 64 KiB"
                              {:limit ws-max-handshake-bytes})))
            (let [b (.toByteArray acc)
                  s (String. b "ISO-8859-1")]
              (if (str/includes? s "\r\n\r\n")
                (let [idx (.indexOf s "\r\n\r\n")
                      end (+ idx 4)
                      left (if (< end (alength b))
                             (java.util.Arrays/copyOfRange b end (alength b))
                             (byte-array 0))]
                  [b left])
                (recur acc)))))
        header-str (String. header-bytes "ISO-8859-1")
        status-line (first (str/split-lines header-str))
        accept (get-header header-str "sec-websocket-accept")
        accept-ok (= accept (accept-key key))
        conn {:stream st :wfn wfn :rfn rfn
              :wlock (Object.) :rlock (Object.)
              :buffer (atom {:data leftover :pos 0})
              :session-id session-id
              :created-at (System/currentTimeMillis)
              :closed? (atom false)
              :continuation (atom nil)
              :accept-ok accept-ok}]
    (when-not accept-ok
      (close-conn conn)
      (throw (ex-info "ws: handshake accept key mismatch"
                      {:status status-line :accept accept})))
    conn))


;; ---------------------------------------------------------------------------
;; Frame reader + message defragmentation
;; ---------------------------------------------------------------------------

(defn- read-frame
  "Read one frame from the buffer. Returns [opcode payload fin] (payload is a
  byte array). Handles masked (server) frames."
  [conn]
  (let [b0 (buf-take-byte conn)
        b1 (buf-take-byte conn)
        fin (not= 0 (bit-and b0 0x80))
        opcode (bit-and b0 0x0F)
        rsv (bit-and b0 0x70)
        masked (not= 0 (bit-and b1 0x80))
        len0 (bit-and b1 0x7F)
        [len mask] (cond
                     (= len0 126)
                     (let [hi (buf-take-byte conn) lo (buf-take-byte conn)]
                       [(bit-or (bit-shift-left hi 8) lo)
                        (when masked (buf-take! conn 4))])
                     (= len0 127)
                     (let [bs (buf-take! conn 8)]
                       [(reduce (fn [acc b] (bit-or (bit-shift-left acc 8)
                                                    (bit-and b 0xFF)))
                                0 (map int bs))
                        (when masked (buf-take! conn 4))])
                     :else
                     [len0 (when masked (buf-take! conn 4))])]
    (when (not= rsv 0)
      (throw (ex-info "ws: unsupported reserved bits" {:rsv rsv})))
    (when (and (#{0x8 0x9 0xA} opcode) (or (not fin) (> len 125)))
      (throw (ex-info "ws: invalid control frame" {:opcode opcode :fin fin :len len})))
    (when (> len ws-max-message)
      (throw (ex-info "ws message exceeds 64 MiB" {:len len})))
    (let [payload (buf-take! conn len)]
      (if masked
        (let [out (byte-array len)]
          (dotimes [i len]
            (aset out i (unchecked-byte (bit-xor (bit-and (aget payload i) 0xFF)
                                                 (bit-and (aget mask (mod i 4)) 0xFF)))))
          [opcode out fin])
        [opcode payload fin]))))

(defn- append-fragment! [acc payload]
  (when (> (+ (.size acc) (alength payload)) ws-max-message)
    (throw (ex-info "ws message exceeds 64 MiB"
                    {:len (+ (.size acc) (alength payload))})))
  (.write acc payload 0 (alength payload))
  acc)

(defn read-message
  "Read one complete (defragmented) text/binary message. Aggregate size is
  bounded and control frames are handled between fragments."
  [conn]
  (loop [acc nil message-type nil]
    (let [[opcode payload fin] (read-frame conn)]
      (cond
        (= opcode 0x8) {:type :close}
        (= opcode 0x9) (do (write-frame conn 0xA payload)
                           (recur acc message-type))
        (= opcode 0xA) (recur acc message-type)

        (or (= opcode 0x1) (= opcode 0x2))
        (if acc
          (throw (ex-info "ws: new data frame before fragmented message ended" {}))
          (if fin
            {:type (if (= opcode 0x1) :text :binary) :data payload}
            (let [out (java.io.ByteArrayOutputStream.)]
              (append-fragment! out payload)
              (recur out (if (= opcode 0x1) :text :binary)))))

        (= opcode 0x0)
        (if (nil? acc)
          (throw (ex-info "ws: continuation without initial data frame" {}))
          (do
            (append-fragment! acc payload)
            (if fin
              {:type message-type :data (.toByteArray acc)}
              (recur acc message-type))))

        :else
        (throw (ex-info (str "ws: unexpected opcode 0x"
                             (Integer/toHexString opcode)) {}))))))

(defn read-event
  "Parse a text message payload as a JSON event. Returns `{:type :data}` or nil
  for empty/non-JSON messages (caller should skip and read again)."
  [msg]
  (let [data (:data msg)]
    (if (or (nil? data) (zero? (alength data)))
      nil
      (try
        (let [obj (json/read-str (String. data "UTF-8") :key-fn keyword)
              nm (get obj :type)]
          (if (and nm (string? nm) (not= nm ""))
            {:type nm :data obj}
            nil))
        (catch Throwable _ nil)))))

(def terminal-types
  #{"response.completed" "response.done" "response.incomplete"
    "response.failed" "error"})

(defn read-until-terminal
  "Read events, calling `(emit event)` for each, until a terminal type is seen."
  [conn emit]
  (loop []
    (let [msg (read-message conn)]
      (when (= :close (:type msg))
        (throw (ex-info "ws: connection closed by peer" {})))
      (if-let [event (read-event msg)]
        (do (emit event)
            (if (terminal-types (:type event))
              nil
              (recur)))
        (recur)))))

;; ---------------------------------------------------------------------------
;; Per-session connection pool
;; ---------------------------------------------------------------------------

;; A stable lock covers every pool map transition. Network dialing is currently
;; performed under this lock: connection creation is rare and correctness is
;; more important than parallel handshakes. It also makes custom pools passed by
;; tests obey the same ownership rules.
(def ^:private pool-lock (Object.))

(defn- new-conn [header-builder session-id]
  (let [[token account-id] (header-builder)]
    (dial token account-id session-id)))

(defn- one-off-acquisition [header-builder session-id]
  (let [conn (new-conn header-builder session-id)
        released? (atom false)]
    {:conn conn :reused false
     :release (fn [_]
                (when (compare-and-set! released? false true)
                  (close-conn conn)))}))

(defn- make-room!
  "Evict the oldest idle session when the pool is at capacity. Returns true
  when a cache slot is available; busy sessions are never interrupted."
  [pl]
  (if (< (count @pl) ws-max-pooled-sessions)
    true
    (when-let [[session-id session]
               (first (sort-by (fn [[_ value]] (:last-used value))
                               (filter (fn [[_ value]] (not (:busy value))) @pl)))]
      (when-let [f (:idle-future session)] (future-cancel f))
      (swap! pl dissoc session-id)
      (close-conn (:conn session))
      true)))

(defn releaser
  "Return an idempotent release hook for exactly `conn`. Stale hooks never
  mutate a replacement pool entry."
  [pl session-id conn]
  (let [released? (atom false)]
    (fn [keep]
      (when (compare-and-set! released? false true)
        (locking pool-lock
          (let [current (get @pl session-id)
                owned? (= (:conn current) conn)]
            (if (and keep owned?)
              (do
                (swap! pl assoc session-id
                       (assoc current :busy false :idle-future nil
                              :last-used (System/currentTimeMillis)))
                (let [f (future
                          (Thread/sleep ws-idle-ttl-ms)
                          (locking pool-lock
                            (let [session (get @pl session-id)]
                              (when (and (= (:conn session) conn)
                                         (not (:busy session)))
                                (swap! pl dissoc session-id)
                                (close-conn conn)))))]
                  ;; Install only if this same idle entry still owns the key.
                  (let [session (get @pl session-id)]
                    (when (and (= (:conn session) conn)
                               (not (:busy session)))
                      (swap! pl assoc-in [session-id :idle-future] f)))))
              (do
                (when owned? (swap! pl dissoc session-id))
                (close-conn conn)))))))))

(defn acquire
  "Atomically acquire a WebSocket session. A cached socket has at most one
  reader/owner; concurrent requests receive one-off connections."
  [pl session-id header-builder]
   (if (or (nil? session-id) (= session-id ""))
     (one-off-acquisition header-builder session-id)
     (locking pool-lock
       (let [sess (get @pl session-id)]
         (when-let [f (:idle-future sess)]
           (future-cancel f))
         (cond
           (nil? sess)
           (if (make-room! pl)
             (let [now (System/currentTimeMillis)
                   conn (new-conn header-builder session-id)
                   entry {:conn conn :busy true :created-at now :last-used now
                          :idle-future nil}]
               (swap! pl assoc session-id entry)
               {:conn conn :reused false
                :release (releaser pl session-id conn)})
             ;; Capacity is fully occupied by active requests; do not grow it.
             (one-off-acquisition header-builder session-id))

           (:busy sess)
           (one-off-acquisition header-builder session-id)

           (>= (- (System/currentTimeMillis) (:created-at sess)) ws-session-max-age-ms)
           (do
             (swap! pl dissoc session-id)
             (close-conn (:conn sess))
             (let [now (System/currentTimeMillis)
                   conn (new-conn header-builder session-id)
                   entry {:conn conn :busy true :created-at now :last-used now
                          :idle-future nil}]
               (swap! pl assoc session-id entry)
               {:conn conn :reused false
                :release (releaser pl session-id conn)}))

           :else
           (do
             (swap! pl assoc session-id
                    (assoc sess :busy true :idle-future nil
                           :last-used (System/currentTimeMillis)))
             {:conn (:conn sess) :reused true
              :release (releaser pl session-id (:conn sess))}))))))

;; ---------------------------------------------------------------------------
;; Continuation state accessors (per-connection, used by llm-proxy.proxy)
;; ---------------------------------------------------------------------------

(defn get-continuation [conn] @(:continuation conn))
(defn set-continuation! [conn c] (reset! (:continuation conn) c))
(defn clear-continuation! [conn] (reset! (:continuation conn) nil))