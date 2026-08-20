(ns llm-proxy.id
  "Random identifiers used for internal sessions and OpenAI response IDs,
  plus a short hash for correlating identifiers in logs without printing the
  raw value.")

(def ^:dynamic *random-bytes*
  "Cryptographically secure bytes in production; bind only in synchronous leaf tests."
  (fn [byte-count]
    (let [bytes (byte-array byte-count)]
      (.nextBytes (java.security.SecureRandom.) bytes)
      bytes)))

(defn random-bytes
  "Return cryptographically random bytes, or bytes supplied by a test binding."
  [byte-count]
  (*random-bytes* byte-count))

(defn random-hex
  "Return `byte-count` cryptographically random bytes as lower-case hex."
  [byte-count]
  (let [bytes (random-bytes byte-count)]
    (loop [index 0 out (StringBuilder.)]
      (if (>= index byte-count)
        (.toString out)
        (recur (inc index)
               (.append out
                        (format "%02x" (bit-and (aget bytes index) 0xFF))))))))

(defn sha256-hex
  "Return the SHA-256 digest of a UTF-8 string as lower-case hex."
  [s]
  (let [digest (.digest (java.security.MessageDigest/getInstance "SHA-256")
                        (.getBytes ^String s "UTF-8"))]
    (loop [index 0 out (StringBuilder.)]
      (if (>= index (alength digest))
        (.toString out)
        (recur (inc index)
               (.append out
                        (format "%02x" (bit-and (aget digest index) 0xFF))))))))

(defn short-hash
  "First 8 hex chars of the SHA-256 of `s` — enough to correlate a session id
  in logs without exposing the raw identifier."
  [s]
  (subs (sha256-hex s) 0 8))
