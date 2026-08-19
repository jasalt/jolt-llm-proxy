;; Phase 3 acceptance: delta request + lenient prefix match.
(require '[codex.continuation :as c])

(def turn1
  {:model "gpt-5.4"
   :instructions "be brief"
   :input [{:role "user" :content "hi"}]
   :stream true
   :store false})

(def turn2
  {:model "gpt-5.4"
   :instructions "be brief"
   :input [{:role "user" :content "hi"}
           {:role "assistant" :content "hello"}
           {:role "user" :content "how are you"}]
   :stream true
   :store false})

(def cont
  {:last-request-body turn1
   :last-response-id "resp_1"
   :last-response-items [{:role "assistant" :content "hello"}]})

;; --- Case 1: continuation extends prior turn -------------------------------
(let [[delta-body ok?] (c/build-delta-request turn2 cont)]
  (println "case1 ok?:" ok?)
  (println "case1 prev-id:" (:previous_response_id delta-body))
  (println "case1 input:" (:input delta-body))
  (when (and ok?
             (= (:previous_response_id delta-body) "resp_1")
             (= (:input delta-body) [{:role "user" :content "how are you"}]))
    (println "PASS case1")))

;; --- Case 2: unrelated turn (different instructions) -----------------------
(let [turn2-bad (assoc turn2 :instructions "be verbose")
      [delta-body ok?] (c/build-delta-request turn2-bad cont)]
  (println "case2 ok?:" ok?)
  (when (not ok?)
    (println "PASS case2")))

;; --- Deep equality sanity --------------------------------------------------
(println "deep-equal [{:a [1 2]}]:" (c/deep-equal-json {:a [1 2]} {:a [1 2]}))
(println "deep-equal mismatch:" (c/deep-equal-json {:a [1 2]} {:a [1 3]}))
