(ns llm-proxy.utf8-request
  "Byte-correct request framing workaround for ring-chez-adapter.

  The pinned adapter accumulates socket reads as decoded strings and compares
  their character count with HTTP Content-Length, which is an octet count.
  Any non-ASCII request can therefore time out and become a plain 400 before
  reaching the Ring handler. Keep the wire data as byte arrays until one full
  request has been framed, then decode only that request as UTF-8.

  Remove this namespace once ring-chez-adapter provides byte-based framing.
  - https://github.com/jolt-lang/ring-chez-adapter/issues/11
  - https://github.com/jasalt/jolt-llm-proxy/issues/2
  "
  (:require [clojure.string :as str]
            [jolt.ffi :as ffi]
            [ring-chez.http :as http]
            [ring-chez.socket :as socket]))

(defn- concat-bytes [left right]
  (let [left-n (alength left)
        right-n (alength right)
        out (byte-array (+ left-n right-n))]
    (System/arraycopy left 0 out 0 left-n)
    (System/arraycopy right 0 out left-n right-n)
    out))

(defn- subbytes [bytes start end]
  (let [out (byte-array (- end start))]
    (System/arraycopy bytes start out 0 (- end start))
    out))

(defn- header-end
  "Return the byte offset immediately after CRLF CRLF, or nil."
  [bytes]
  (let [n (alength bytes)]
    (loop [i 0]
      (cond
        (> i (- n 4)) nil
        (and (= 13 (bit-and 0xff (aget bytes i)))
             (= 10 (bit-and 0xff (aget bytes (inc i))))
             (= 13 (bit-and 0xff (aget bytes (+ i 2))))
             (= 10 (bit-and 0xff (aget bytes (+ i 3))))) (+ i 4)
        :else (recur (inc i))))))

(defn- content-length [bytes body-start]
  (let [headers (-> (String. (subbytes bytes 0 (- body-start 4)) "ISO-8859-1")
                    str/lower-case)
        marker "content-length:"
        i (str/index-of headers marker)]
    (if-not i
      0
      (let [start (+ i (count marker))
            end (loop [j start]
                  (if (or (>= j (count headers))
                          (= \return (nth headers j))
                          (= \newline (nth headers j)))
                    j
                    (recur (inc j))))]
        (or (parse-long (str/trim (subs headers start end))) 0)))))

(defn read-request
  "Byte-based replacement for `ring-chez.http/read-request`.

  The adapter carries pipelined leftovers as a string, so leftovers use
  ISO-8859-1 as a reversible byte-to-character mapping. Complete HTTP request
  bytes are decoded as UTF-8 before `request->ring` parses them."
  [conn acc max-bytes recv! idle-recv!]
  (let [buf (ffi/alloc socket/bufsize)]
    (try
      (loop [bytes (.getBytes (or acc "") "ISO-8859-1")]
        (if-let [body-start (header-end bytes)]
          (let [body-length (content-length bytes body-start)
                request-end (+ body-start body-length)]
            (cond
              (> request-end max-bytes)
              :too-big

              (>= (alength bytes) request-end)
              {:text (String. (subbytes bytes 0 request-end) "UTF-8")
               :leftover (String. (subbytes bytes request-end (alength bytes))
                                  "ISO-8859-1")}

              :else
              (let [n (recv! conn buf)]
                (if (pos? n)
                  (recur (concat-bytes bytes (ffi/read-array buf n)))
                  :bad))))
          (if (> (alength bytes) max-bytes)
            :headers-too-big
            (let [n ((if (zero? (alength bytes)) idle-recv! recv!) conn buf)]
              (cond
                (pos? n) (recur (concat-bytes bytes (ffi/read-array buf n)))
                (zero? (alength bytes)) :closed
                :else :bad)))))
      (finally
        (ffi/free buf)))))

(defn install!
  "Install byte-correct framing into the adapter process before workers start."
  []
  (alter-var-root #'http/read-request (constantly read-request)))
