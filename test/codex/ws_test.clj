(ns codex.ws-test
  (:require [clojure.test :refer [deftest is testing]]
            [codex.ws :as ws]))

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

(deftest malformed-control-and-fragment-errors
  (testing "fragmented control frame"
    (let [{:keys [conn]} (conn-from [(frame 9 (bytes 1) :fin false)])]
      (is (thrown? Throwable (ws/read-message conn)))))
  (testing "oversized control frame"
    (let [{:keys [conn]} (conn-from [(frame 9 (byte-array 126))])]
      (is (thrown? Throwable (ws/read-message conn)))))
  (testing "continuation without initial data"
    (let [{:keys [conn]} (conn-from [(frame 0 (bytes 1))])]
      (is (thrown? Throwable (ws/read-message conn))))))

(deftest close-and-json-event-handling
  (let [{:keys [conn]} (conn-from [(frame 8 (bytes 3 232))])]
    (is (= {:type :close} (ws/read-message conn))))
  (is (= "response.done"
         (:type (ws/read-event {:data (.getBytes "{\"type\":\"response.done\"}" "UTF-8")})))))

(deftest pool-release-is-idempotent-and-stale-safe
  (let [pool (atom {})
        old {:closed? (atom true)}
        new {:closed? (atom true)}
        release (ws/releaser pool "session" old)]
    (reset! pool {"session" {:conn old :busy true}})
    (release false)
    (release true)
    (is (nil? (get @pool "session")))
    (let [stale (ws/releaser pool "session" old)]
      (reset! pool {"session" {:conn new :busy true}})
      (stale false)
      (is (= new (get-in @pool ["session" :conn])))
      (is (= true (get-in @pool ["session" :busy]))))))