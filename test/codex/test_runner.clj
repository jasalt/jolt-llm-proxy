(ns codex.test-runner
  (:require [clojure.test :as t]
            [codex.auth-test]
            [codex.continuation-test]
            [codex.proxy-test]
            [codex.translate-test]))

(defn -main [& _]
  (let [{:keys [fail error]}
        (t/run-tests 'codex.auth-test
                     'codex.continuation-test
                     'codex.proxy-test
                     'codex.translate-test)]
    (when (pos? (+ fail error))
      (throw (ex-info "test suite failed" {:fail fail :error error})))))
