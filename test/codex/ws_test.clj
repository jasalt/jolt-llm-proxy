(ns codex.ws-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [codex.ws :as ws]
            [jolt.host :as host]
            [llm-proxy.id]
            [llm-proxy.time]))

(defn- bytes [& xs]
  (byte-array (map #(unchecked-byte %) xs)))

(defn- frame [opcode payload & {:keys [fin] :or {fin true}}]
  (let [n (alength payload)
        header (cond (< n 126) 2 (< n 65536) 4 :else 10)
        out (byte-array (+ header n))]
    (aset out 0 (unchecked-byte (bit-or (if fin 0x80 0) opcode)))
    (cond (< n 126)
          (aset out 1 (unchecked-byte n))
          (< n 65536)
          (do (aset out 1 (unchecked-byte 126))
              (aset out 2 (unchecked-byte (bit-shift-right n 8)))
              (aset out 3 (unchecked-byte n)))
          :else
          (do (aset out 1 (unchecked-byte 127))
              (doseq [i (range 8)]
                (aset out (+ 2 i) (unchecked-byte (bit-shift-right n (* 8 (- 7 i))))))))
    (System/arraycopy payload 0 out header n)
    out))

(defn- conn-from [chunks]
  (let [remaining (atom (seq chunks))
        writes (atom [])]
    {:conn {:stream {} :wfn (fn [_ frame] (swap! writes conj frame))
            :rfn (fn [_ _] (let [x (first @remaining)]
                             (swap! remaining next) x))
            :wlock (Object.) :rlock (Object.)
            :buffer (atom {:data (byte-array 0) :pos 0})}
     :writes writes}))

(defn- frame-header-size [n]
  (cond (< n 126) 2 (< n 65536) 4 :else 10))

(defn- mock-stream [chunks writes closed?]
  (let [remaining (atom (seq chunks))
        stream (host/tagged-table :test/tls-stream)]
    (host/ref-put! stream :read (fn [_ _]
                                  (let [chunk (first @remaining)]
                                    (swap! remaining next)
                                    chunk)))
    (host/ref-put! stream :write (fn [_ data] (swap! writes conj data)))
    (host/ref-put! stream :close (fn [] (reset! closed? true)))
    stream))

(deftest leaf-time-and-randomness-bindings-are-deterministic
  (binding [llm-proxy.time/*now-ms* (constantly 1234)
            llm-proxy.id/*random-bytes* (fn [n] (byte-array (repeat n 42)))]
    (is (= 1234 (llm-proxy.time/now-ms)))
    (is (= "2a2a" (llm-proxy.id/random-hex 2)))
    (is (= "KioqKioqKioqKioqKioqKg==" (ws/handshake-key)))))

(defn- property-passes? [result]
  (:pass? result))

(deftest generated-frame-round-trips-and-fragmentation-remains-bounded
  (testing "text frames round-trip through arbitrary chunk boundaries"
    (is (property-passes?
         (tc/quick-check
          40
          (prop/for-all [payload (gen/vector (gen/choose 0 255) 0 2048)
                         chunk-size (gen/choose 1 128)]
            (let [data (byte-array (map unchecked-byte payload))
                  raw (frame 1 data)
                  chunks (mapv #(java.util.Arrays/copyOfRange
                                 raw % (min (+ % chunk-size) (alength raw)))
                               (range 0 (alength raw) chunk-size))
                  {:keys [conn]} (conn-from chunks)
                  message (ws/read-message conn)]
              (and (= :text (:type message))
                   (java.util.Arrays/equals data (:data message)))))))))
  (testing "fragmented messages preserve their aggregate payload"
    (is (property-passes?
         (tc/quick-check
          40
          (prop/for-all [left (gen/vector (gen/choose 0 255) 0 1024)
                         right (gen/vector (gen/choose 0 255) 0 1024)]
            (let [first-part (frame 1 (byte-array (map unchecked-byte left))
                                    :fin false)
                  last-part (frame 0 (byte-array (map unchecked-byte right)))
                  {:keys [conn]} (conn-from [(byte-array
                                              (concat (seq first-part)
                                                      (seq last-part)))])
                  message (ws/read-message conn)]
              (= (+ (count left) (count right))
                 (alength (:data message))))))))))

(deftest write-frame-masks-client-payloads-at-all-length-boundaries
  (binding [llm-proxy.id/*random-bytes* (fn [n] (byte-array (repeat n 42)))]
    (doseq [n [125 126 65535 65536]]
      (let [payload (byte-array n)
            writes (atom [])
            conn {:stream {} :wfn (fn [_ data] (swap! writes conj data))
                  :wlock (Object.)}]
        (ws/write-frame conn 0x1 payload)
        (let [raw (first @writes)
              header-size (frame-header-size n)
              mask-start header-size
              payload-start (+ header-size 4)]
          (is (= 0x81 (bit-and (aget raw 0) 0xFF)) (str "length " n))
          (is (not= 0 (bit-and (aget raw 1) 0x80)) (str "masked " n))
          (is (= n (case n
                    125 (bit-and (aget raw 1) 0x7F)
                    126 (bit-or (bit-shift-left (bit-and (aget raw 2) 0xFF) 8)
                                (bit-and (aget raw 3) 0xFF))
                    65535 (bit-or (bit-shift-left (bit-and (aget raw 2) 0xFF) 8)
                                  (bit-and (aget raw 3) 0xFF))
                    65536 (reduce (fn [total byte]
                                    (bit-or (bit-shift-left total 8)
                                            (bit-and byte 0xFF)))
                                  0 (map int (drop 2 (take 10 raw))))))
              (str "encoded length " n))
          (let [unmasked (byte-array n)]
            (dotimes [index n]
              (aset unmasked index (unchecked-byte
                                     (bit-xor (bit-and (aget raw (+ payload-start index)) 0xFF)
                                              (bit-and (aget raw (+ mask-start (mod index 4))) 0xFF)))))
            (is (java.util.Arrays/equals payload unmasked)
                (str "masked payload " n))))))))

(deftest write-text-uses-utf8-and-write-frame-serializes-writers
  (binding [llm-proxy.id/*random-bytes* (fn [n] (byte-array (repeat n 42)))]
    (let [writes (atom [])
          active (atom 0)
          peak (atom 0)
          conn {:stream {}
                :wfn (fn [_ data]
                       (let [now (swap! active inc)]
                         (swap! peak max now)
                         (Thread/sleep 20)
                         (swap! writes conj data)
                         (swap! active dec)))
                :wlock (Object.)}]
      (ws/write-text conn "hé")
      (let [raw (first @writes)
            start 6
            decoded (byte-array 3)]
        (dotimes [i 3]
          (aset decoded i (unchecked-byte
                           (bit-xor (bit-and (aget raw (+ start i)) 0xFF)
                                    (bit-and (aget raw (+ 2 (mod i 4))) 0xFF)))))
        (is (= "hé" (String. decoded "UTF-8"))))
      (reset! writes [])
      (let [left (future (ws/write-text conn "left"))
            right (future (ws/write-text conn "right"))]
        @left @right
        (is (= 2 (count @writes)))
        (is (= 1 @peak))))))

(deftest dial-validates-status-headers-limits-and-leftover-frame-bytes
  (let [key "KioqKioqKioqKioqKioqKg=="
        accept (ws/accept-key key)
        leftover (frame 1 (.getBytes "after-upgrade" "UTF-8"))]
    (testing "status and accept header are case-insensitive; frame bytes survive upgrade"
      (let [writes (atom [])
            closed? (atom false)
            stream (mock-stream [(byte-array (concat
                                               (seq (.getBytes
                                                     (str "HTTP/1.1 101 Switching Protocols\r\n"
                                                          "sEc-WeBsOcKeT-aCcEpT: " accept "\r\n\r\n")
                                                     "ISO-8859-1"))
                                               (seq leftover)))]
                                writes closed?)]
        (binding [llm-proxy.id/*random-bytes* (fn [n] (byte-array (repeat n 42)))]
          (binding [ws/*tls-connect* (fn [& _] stream)]
            (let [conn (ws/dial "token" "account" "session")]
              (is (true? (:accept-ok conn)))
              (is (= "after-upgrade" (String. (:data (ws/read-message conn)) "UTF-8")))
              (is (.contains (String. (first @writes) "ISO-8859-1")
                             (str "Sec-WebSocket-Key: " key))))))))
    (testing "non-101 status and invalid accept close and reject the connection"
      (doseq [response [(str "HTTP/1.1 200 OK\r\nSec-WebSocket-Accept: " accept "\r\n\r\n")
                        "HTTP/1.1 101 Switching Protocols\r\nSec-WebSocket-Accept: wrong\r\n\r\n"]]
        (let [writes (atom [])
              closed? (atom false)
              stream (mock-stream [(.getBytes response "ISO-8859-1")] writes closed?)]
          (binding [llm-proxy.id/*random-bytes* (fn [n] (byte-array (repeat n 42)))]
            (binding [ws/*tls-connect* (fn [& _] stream)]
              (is (thrown? Throwable (ws/dial "token" "account" "session")))))
          (is @closed?))))
    (testing "handshake headers over the fixed limit are rejected"
      (let [writes (atom [])
            closed? (atom false)
            oversized (byte-array (inc ws/ws-max-handshake-bytes))
            stream (mock-stream [oversized] writes closed?)]
        (binding [ws/*tls-connect* (fn [& _] stream)]
          (is (thrown? Throwable (ws/dial "token" "account" "session"))))))))

(deftest frame-length-boundaries-and-partial-reads
  (doseq [n [0 1 125 126 65535 65536]]
    (let [payload (byte-array n)
          raw (frame 1 payload)
          ;; Split the header and a small payload to exercise refill logic;
          ;; avoid thousands of copies for the 64 KiB boundary payload.
          chunks (if (<= n 125)
                   (mapv #(java.util.Arrays/copyOfRange raw % (min (+ % 7) (alength raw)))
                         (range 0 (alength raw) 7))
                   [(java.util.Arrays/copyOfRange raw 0 1)
                    (java.util.Arrays/copyOfRange raw 1 (min 4 (alength raw)))
                    (java.util.Arrays/copyOfRange raw (min 4 (alength raw))
                                                        (alength raw))])
          {:keys [conn]} (conn-from chunks)
          message (ws/read-message conn)]
      (is (= :text (:type message)) (str "length " n))
      (is (= n (alength (:data message))) (str "length " n)))))

(deftest fragmentation-and-interleaved-ping
  (let [ping (frame 9 (bytes 112 105 110 103))
        first-part (frame 1 (bytes 97 98) :fin false)
        last-part (frame 0 (bytes 99 100))
        {:keys [conn writes]} (conn-from [(byte-array (concat (seq ping)
                                                               (seq first-part)
                                                               (seq last-part)))])
        message (ws/read-message conn)]
    (is (= :text (:type message)))
    (is (= "abcd" (String. (:data message) "UTF-8")))
    (is (= 1 (count @writes)))
    (is (= 0xA (bit-and (aget (first @writes) 0) 0x0F)))))

(deftest control-and-fragment-errors
  (testing "fragmented and oversized control frames"
    (doseq [raw [(frame 9 (bytes 1) :fin false)
                 (frame 9 (byte-array 126))]]
      (let [{:keys [conn]} (conn-from [raw])]
        (is (thrown? Throwable (ws/read-message conn))))))
  (testing "invalid data-frame sequences"
    (let [new-data-before-final
          (byte-array (concat (seq (frame 1 (bytes 1) :fin false))
                              (seq (frame 2 (bytes 2)))))]
      (doseq [raw [(frame 0 (bytes 1)) new-data-before-final]]
        (let [{:keys [conn]} (conn-from [raw])]
          (is (thrown? Throwable (ws/read-message conn)))))))
  (testing "reserved bits and unknown opcodes are rejected"
    (doseq [raw [(bytes 193 0) (bytes 131 0)]]
      (let [{:keys [conn]} (conn-from [raw])]
        (is (thrown? Throwable (ws/read-message conn)))))))

(deftest close-and-json-event-handling
  (let [{:keys [conn]} (conn-from [(frame 8 (bytes 3 232))])]
    (is (= {:type :close} (ws/read-message conn))))
  (is (= "response.done"
         (:type (ws/read-event {:data (.getBytes "{\"type\":\"response.done\"}" "UTF-8")})))))

(deftest pool-resources-are-isolated
  (let [one (ws/make-pool (constantly 1000))
        two (ws/make-pool (constantly 2000))]
    (is (not (identical? (:sessions one) (:sessions two))))
    (is (not (identical? (:lock one) (:lock two))))
    (is (= 1000 ((:now-ms one))))
    (is (= 2000 ((:now-ms two))))))

(deftest acquire-reuses-idle-connections-and-isolates-busy-owners
  (let [pool (ws/make-pool (constantly 1000))
        dials (atom 0)
        conn (fn [n] {:closed? (atom true) :id n})]
    ;; Exception to ADR 260820A: ws/acquire invokes dial synchronously, so
    ;; this test can avoid opening sockets. Add an explicit pool :dial seam if
    ;; dialing becomes asynchronous or this test can run concurrently.
    (with-redefs [ws/dial (fn [& _] (conn (swap! dials inc)))]
      (let [first (ws/acquire pool "session" (constantly ["token" "account"]))
            concurrent (ws/acquire pool "session" (constantly ["token" "account"]))]
        (is (= 2 @dials))
        (is (= false (:reused first)))
        (is (= false (:reused concurrent)))
        ((:release concurrent) false)
        ((:release first) true)
        (let [reused (ws/acquire pool "session" (constantly ["token" "account"]))]
          (is (= 2 @dials))
          (is (= true (:reused reused)))
          ((:release reused) false)))
    (is (empty? @(:sessions pool)))))

(deftest pool-release-is-idempotent-and-stale-safe
  (let [pool (ws/make-pool (constantly 1000))
        old {:closed? (atom true)}
        new {:closed? (atom true)}
        release (ws/releaser pool "session" old)]
    (reset! (:sessions pool) {"session" {:conn old :busy true}})
    (release false)
    (release true)
    (is (nil? (get @(:sessions pool) "session")))
    (let [stale (ws/releaser pool "session" old)]
      (reset! (:sessions pool) {"session" {:conn new :busy true}})
      (stale false)
      (is (= new (get-in @(:sessions pool) ["session" :conn])))
      (is (= true (get-in @(:sessions pool) ["session" :busy])))))))