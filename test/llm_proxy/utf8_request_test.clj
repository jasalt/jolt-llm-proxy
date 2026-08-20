(ns llm-proxy.utf8-request-test
  (:require [clojure.test :refer [deftest is testing]]
            [jolt.ffi :as ffi]
            [llm-proxy.utf8-request :as utf8]))

(defn- chunk-receiver [chunks]
  (let [remaining (atom chunks)]
    (fn [_conn buf]
      (if-let [chunk (first @remaining)]
        (do
          (swap! remaining subvec 1)
          (ffi/write-array buf chunk))
        0))))

(defn- split-bytes [bytes indexes]
  (loop [start 0
         indexes indexes
         out []]
    (if-let [end (first indexes)]
      (recur end (next indexes)
             (conj out (java.util.Arrays/copyOfRange bytes start end)))
      (conj out (java.util.Arrays/copyOfRange bytes start (alength bytes))))))

(deftest frames-content-length-in-octets
  (let [body "{\"text\":\"before — after\"}"
        body-bytes (.getBytes body "UTF-8")
        head (str "POST /v1/chat/completions HTTP/1.1\r\n"
                  "Host: localhost\r\n"
                  "Content-Length: " (alength body-bytes) "\r\n\r\n")
        request-bytes (.getBytes (str head body) "UTF-8")
        dash-start (.indexOf (str head body) "—")
        ;; Split inside the em dash's three-byte UTF-8 sequence. The old
        ;; adapter decoded each recv independently and counted characters.
        byte-dash-start (alength (.getBytes (subs (str head body) 0 dash-start)
                                           "UTF-8"))
        chunks (split-bytes request-bytes [(inc byte-dash-start)
                                           (+ byte-dash-start 2)])
        recv! (chunk-receiver (vec chunks))
        result (utf8/read-request nil "" 1048576 recv! recv!)]
    (is (= (str head body) (:text result)))
    (is (= "" (:leftover result)))))

(deftest preserves-pipelined-leftovers-byte-for-byte
  (let [first-request "POST /one HTTP/1.1\r\nHost: localhost\r\nContent-Length: 3\r\n\r\n€"
        second-request "GET /two HTTP/1.1\r\nHost: localhost\r\n\r\n"
        wire (.getBytes (str first-request second-request) "UTF-8")
        recv! (chunk-receiver [wire])
        first-result (utf8/read-request nil "" 1048576 recv! recv!)
        no-more (constantly 0)
        second-result (utf8/read-request nil (:leftover first-result)
                                         1048576 no-more no-more)]
    (is (= first-request (:text first-result)))
    (is (= second-request (:text second-result)))
    (is (= "" (:leftover second-result)))))

(deftest keeps-adapter-size-and-close-signals
  (testing "declared oversized body"
    (let [request (.getBytes
                    "POST / HTTP/1.1\r\nHost: x\r\nContent-Length: 5000\r\n\r\n"
                    "UTF-8")
          recv! (chunk-receiver [request])]
      (is (= :too-big (utf8/read-request nil "" 1000 recv! recv!)))))
  (testing "clean idle close"
    (is (= :closed
           (utf8/read-request nil "" 1000 (constantly 0) (constantly 0)))))
  (testing "mid-request close"
    (let [recv! (chunk-receiver [(.getBytes "GET / HTTP/1.1\r\n" "UTF-8")])]
      (is (= :bad (utf8/read-request nil "" 1000 recv! recv!))))))
