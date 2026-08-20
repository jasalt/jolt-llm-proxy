(ns codex.ws-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [codex.ws :as ws]
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