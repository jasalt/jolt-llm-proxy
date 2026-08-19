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
  (let [runtime {:api-key "secret"}
        ok (fn [_] {:status 204})]
    (is (= 401 (:status (proxy/require-api-key runtime {:headers {}} ok))))
    (is (= 204 (:status (proxy/require-api-key
                          runtime
                          {:headers {"authorization" "Bearer secret"}} ok))))))

(deftest handler-instances-have-isolated-configuration
  (let [open (proxy/make-handler {:api-key "" :session-id "one"})
        guarded (proxy/make-handler {:api-key "secret" :session-id "two"})
        request {:request-method :get :uri "/v1/models" :headers {}}]
    (is (= 200 (:status (open request))))
    (is (= 401 (:status (guarded request))))))
