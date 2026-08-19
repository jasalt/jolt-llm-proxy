(ns codex.id
  "Random identifiers used for internal sessions and OpenAI response IDs.")

(defn random-hex
  "Return `byte-count` cryptographically random bytes as lower-case hex."
  [byte-count]
  (let [bytes (byte-array byte-count)]
    (.nextBytes (java.security.SecureRandom.) bytes)
    (loop [index 0 out (StringBuilder.)]
      (if (>= index byte-count)
        (.toString out)
        (recur (inc index)
               (.append out
                        (format "%02x" (bit-and (aget bytes index) 0xFF))))))))
