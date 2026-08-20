(ns codex.auth-test
  (:require [clojure.test :refer [deftest is testing]]
            [codex.auth :as auth]))

(defn- temp-path []
  (str "/tmp/codex-auth-test-" (System/currentTimeMillis) "-"
       (rand-int 1000000) "/config/auth.json"))

(deftest credential-roundtrip
  (let [path (temp-path)
        cred {:access_token "access" :refresh_token "refresh"
              :expires_at 123 :account_id "account" :lock :must-not-persist}]
    (try
      (auth/save-cred! path cred)
      (is (= (select-keys cred auth/credential-keys) (auth/load-cred path)))
      (is (not (contains? (auth/load-cred path) :lock)))
      (finally
        (auth/logout! (auth/start! path))))))

(deftest token-refresh-uses-store-clock-and-http-client
  (let [requests (atom [])
        now-ms (constantly 1000)
        store (atom {:access_token "old" :refresh_token "refresh" :expires_at 0
                     :account_id "account" :path "/tmp/unused" :lock (Object.)
                     :now-ms now-ms
                     :http-post (fn [url options]
                                  (swap! requests conj [url options])
                                  {:status 200
                                   :body "{\"access_token\":\"new\",\"expires_in\":60}"})})]
    ;; Exception to ADR 260820A: this fully synchronous test suppresses
    ;; credential-file I/O and JWT parsing. Replace with explicit seams if
    ;; token refresh can perform asynchronous work.
    (with-redefs [auth/save-cred! (fn [_ cred] cred)
                  auth/account-id-from-jwt (constantly "new-account")]
      (is (= ["new" "new-account"] (auth/token store)))
      (is (= 61000 (:expires_at @store)))
      (is (= 1 (count @requests))))))

(deftest missing-credential-is-not-an-error
  (is (nil? (auth/load-cred (temp-path)))))

(deftest malformed-credential-is-reported
  (let [path (temp-path)]
    (try
      (auth/save-cred! path {:access_token "a"})
      (spit path "{bad")
      (is (= :credential-file
             (try (auth/load-cred path) nil
                  (catch Throwable e (:type (ex-data e))))))
      (finally
        (let [f (java.io.File. path)]
          (when (.exists f) (.delete f)))))))
