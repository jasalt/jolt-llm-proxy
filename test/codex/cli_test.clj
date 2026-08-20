(ns codex.cli-test
  (:require [clojure.test :refer [deftest is]]
            [codex.cli :as cli]))

(deftest reads-console-lines-terminated-by-lf-cr-or-eof
  (doseq [[input expected] [["1\n" "1"]
                           ["2\r" "2"]
                           ["  1  \r\n" "  1  "]
                           ["" ""]]]
    (is (= expected
           (cli/read-console-line
            (java.io.BufferedReader. (java.io.StringReader. input)))))))
