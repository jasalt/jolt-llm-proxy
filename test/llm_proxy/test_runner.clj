(ns llm-proxy.test-runner
  (:require [clojure.test :as t]
            [codex.auth-test]
            [codex.collect-test]
            [llm-proxy.core-test]
            [codex.continuation-test]
            [llm-proxy.proxy-test]
            [llm-proxy.schema-test]
            [codex.translate-test]
            [codex.ws-test]
            [llm-proxy.transport-sse-test]
            [llm-proxy.dashboard-test]))

(defn -main [& _]
  (let [{:keys [fail error]}
        (t/run-tests 'codex.auth-test
                     'codex.collect-test
                     'llm-proxy.core-test
                     'codex.continuation-test
                     'llm-proxy.proxy-test
                     'llm-proxy.schema-test
                     'codex.translate-test
                     'codex.ws-test
                     'llm-proxy.transport-sse-test
                     'llm-proxy.dashboard-test)]
    (when (pos? (+ fail error))
      (throw (ex-info "test suite failed" {:fail fail :error error})))))
