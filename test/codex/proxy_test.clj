(ns codex.proxy-test
  (:require [clojure.test :refer [deftest is]]
            [codex.proxy :as proxy]))

(deftest normalizes-session-identifiers
  (is (= "abc_1:two" (proxy/normalize-session-id " abc_1:two ")))
  (is (= 64 (count (proxy/normalize-session-id (apply str (repeat 80 "a"))))))
  (is (= :invalid-session-id
         (try (proxy/normalize-session-id "bad\r\nheader") nil
              (catch Throwable e (:type (ex-data e)))))))

(deftest api-key-guard
  (let [old @proxy/system
        ok (fn [_] {:status 204})]
    (try
      (proxy/set-system! {:api-key "secret"})
      (is (= 401 (:status (proxy/require-api-key {:headers {}} ok))))
      (is (= 204 (:status (proxy/require-api-key
                            {:headers {"authorization" "Bearer secret"}} ok))))
      (finally (proxy/set-system! old)))))
