(ns codex.continuation
  "Build the delta request (previous_response_id + only the new input items)
  when the current request extends the prior turn's conversation, so the
  OpenAI prompt cache covers growing history. Mirrors Go `continuation.go`.

  All functions operate on keyword-keyed PMaps (as produced by
  `codex.translate`): input/response items use string `:role` values."
  (:require [clojure.string :as str]))

;; A continuation-state PMap:
;;   {:last-request-body :last-response-id :last-response-items}

;; ---------------------------------------------------------------------------
;; Text extraction
;; ---------------------------------------------------------------------------

(defn message-item-text
  "Extract text from a message item's content (string or structured vector)."
  [item]
  (let [content (:content item)]
    (cond
      (string? content) content
      (vector? content)
      (let [sb (StringBuilder.)]
        (doseq [part content]
          (when (and (map? part)
                     (#{:output_text :text :input_text} (:type part)))
            (let [t (:text part)]
              (when (and t (not= t ""))
                (.append sb t)))))
        (.toString sb))
      :else "")))

(defn content-parts-text
  "Join the text of `:text`/`:output_text`/`:input_text` parts with \"\\n\"."
  [parts]
  (let [sb (StringBuilder.)]
    (doseq [part parts]
      (when (and (map? part)
                 (#{:text :output_text :input_text} (:type part)))
        (let [t (:text part)]
          (when (and t (not= t ""))
            (when (> (.length sb) 0) (.append sb "\n"))
            (.append sb t)))))
    (.toString sb)))

;; ---------------------------------------------------------------------------
;; Normalization for prefix comparison
;; ---------------------------------------------------------------------------

(defn normalize-input-item
  "Canonicalize one input item: message roles flatten content to a plain string;
  non-message items keep all keys except `:status`."
  [item]
  (let [role (:role item)]
    (if (#{"assistant" "user" "system" "developer"} role)
      (let [content (:content item)]
        (cond
          (string? content) {:role role :content content}
          (and (vector? content) (not (empty? content)))
          (let [t (content-parts-text content)]
            {:role role :content (if (= t "") "" t)})
          :else {:role role :content ""}))
      (dissoc item :status))))

(defn normalize-input-list
  "Normalize a vector of input items (same length as input)."
  [in]
  (vec (map normalize-input-item (or in []))))

;; ---------------------------------------------------------------------------
;; Server output -> input items
;; ---------------------------------------------------------------------------

(defn response-output-to-input-items
  "Convert Codex response output items into the input-item shape for the next
  turn's prefix comparison. `for-chat` selects the chat normalization; when
  false, reasoning/other items are retained."
  [output for-chat]
  (vec (keep (fn [raw]
               (when (map? raw)
                 (let [t (:type raw)]
                   (cond
                     (= t "message")
                     (let [text (message-item-text raw)]
                       (when (not= text "")
                         (normalize-input-item {:role "assistant" :content text})))
                     (= t "function_call")
                     (normalize-input-item raw)
                     (or (= t "reasoning") (= t "reasoning_summary"))
                     (when (not for-chat) raw)
                     :else
                     (when (not for-chat) raw)))))
             output)))

;; ---------------------------------------------------------------------------
;; Equality helpers
;; ---------------------------------------------------------------------------

(defn deep-equal-json [a b]
  (= a b))

(defn bodies-match-except-input
  "True when two request bodies are identical ignoring `:input` and
  `:previous_response_id`."
  [a b]
  (and
   (every? (fn [k] (or (= k :input) (= k :previous_response_id) (contains? b k)))
           (keys a))
   (every? (fn [k] (or (= k :input) (= k :previous_response_id) (contains? a k)))
           (keys b))
   (every? (fn [k]
             (or (= k :input) (= k :previous_response_id)
                 (deep-equal-json (get a k) (get b k))))
           (keys a))))

(defn prefix-matches-continuation
  "True when `current` is a valid continuation prefix of `baseline`. Assistant
  items are matched leniently on role only (the server replays its own text)."
  [current baseline]
  (if (not= (count current) (count baseline))
    false
    (every? (fn [[b-item c-item]]
              (if (and (map? b-item) (map? c-item))
                (let [role (:role b-item)]
                  (if (= role "assistant")
                    (= (:role c-item) "assistant")
                    (deep-equal-json c-item b-item)))
                (deep-equal-json c-item b-item)))
            (map vector baseline current))))

;; ---------------------------------------------------------------------------
;; Delta request builder
;; ---------------------------------------------------------------------------

(defn build-delta-request
  "Return `[delta-body ok?]`. When the current request extends the prior turn,
  `ok?` is true and `delta-body` carries `:previous_response_id` plus only the
  new input items. Otherwise `[body false]` (send full input)."
  [body cont]
  (if (or (nil? cont) (= (:last-response-id cont) ""))
    [body false]
    (if (contains? body :previous_response_id)
      [body false]
      (if (not (bodies-match-except-input body (:last-request-body cont)))
        [body false]
        (let [current-input (:input body)
              last-input (:input (:last-request-body cont))
              baseline (concat (normalize-input-list last-input)
                               (:last-response-items cont))
              current-norm (normalize-input-list current-input)]
          (if (< (count current-norm) (count baseline))
            [body false]
            (if (not (prefix-matches-continuation
                      (take (count baseline) current-norm) baseline))
              [body false]
              (let [delta-start (- (count current-input)
                                   (- (count current-norm) (count baseline)))
                    delta-start (if (< delta-start 0) 0 delta-start)
                    delta (subvec current-input delta-start)
                    delta-body (assoc body
                                      :previous_response_id (:last-response-id cont)
                                      :input delta)]
                [delta-body true]))))))))
