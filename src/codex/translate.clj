(ns codex.translate
  "Translate between `/v1/chat/completions` and `/v1/responses` bodies.
  Mirrors Go `translate.go` (`chatToResponses`, `prepareResponses`, and the
  flattening helpers). All produced request bodies set `:store false` (R6).

  Both entry points parse client JSON with **string keys** and validate it
  before any keyword conversion, so arbitrary client-controlled key names are
  never interned as keywords. `chat-to-responses` extracts recognized fields
  explicitly and drops unknown ones. `prepare-responses` keywordizes only a
  known top-level key set and passes unknown keys through upstream with their
  original string keys (deliberate compatibility policy); input items are
  keywordized recursively (bounded by item count, body size, and a nesting
  cap) so `codex.continuation` keeps matching prefixes."
  (:require [clojure.data.json :as json]
            [codex.schema :as schema]))

(def max-nesting-depth 32)

;; ---------------------------------------------------------------------------
;; Parsing + validation helpers
;; ---------------------------------------------------------------------------

(defn- to-str
  "Coerce a request body (string or byte array) to a string for JSON parsing."
  [raw]
  (if (string? raw) raw (String. raw "UTF-8")))

(defn- check
  ([ok message]
   (when-not ok (throw (ex-info message {}))))
  ([ok message data]
   (when-not ok (throw (ex-info message data)))))

(defn- parse-object
  "Parse a JSON body with string keys, rejecting non-object bodies."
  [raw message]
  (let [parsed (json/read-str (to-str raw))]
    (check (map? parsed) message)
    parsed))

(defn- validate-chat-semantics
  "Validate translation policy not expressible as a structural schema."
  [chat]
  (let [n (get chat "n")]
    (when (and (number? n) (not= n 1))
      (throw (ex-info "only n=1 is supported" {})))))

;; ---------------------------------------------------------------------------
;; Content extraction / construction (string-keyed input)
;; ---------------------------------------------------------------------------

(defn content-text
  "Join the text of `\"text\"` content parts with \"\\n\"."
  [value]
  (cond
    (string? value) value
    (vector? value)
    (let [sb (StringBuilder.)]
      (doseq [part value]
        (when (and (map? part) (= (get part "type") "text"))
          (let [t (get part "text")]
            (when (and t (not= t ""))
              (when (> (.length sb) 0) (.append sb "\n"))
              (.append sb t)))))
      (.toString sb))
    :else ""))

(defn response-content
  "Normalize user message content into the Responses input-item content shape."
  [value]
  (cond
    (string? value) value
    (vector? value)
    (vec (mapcat (fn [part]
                   (when (map? part)
                     (cond
                       (= (get part "type") "text")
                       [{:type "input_text" :text (get part "text")}]
                       (= (get part "type") "image_url")
                       (let [image (or (get part "image_url") (get part "url"))
                             image (if (map? image) (get image "url") image)]
                         [{:type "input_image" :image_url image}])
                       :else [])))
                 value))
    :else nil))

;; ---------------------------------------------------------------------------
;; Flattening helpers (string-keyed input)
;; ---------------------------------------------------------------------------

(defn flatten-tools
  "Drop the `function` wrapper from function tools, keeping known sub-keys."
  [tools]
  (vec (map (fn [tool]
              (if (not= (get tool "type") "function")
                tool
                (let [fnm (get tool "function")]
                  (reduce (fn [flat [src dst]]
                            (if (contains? fnm src)
                              (assoc flat dst (get fnm src))
                              flat))
                          {:type "function"}
                          [["name" :name] ["description" :description]
                           ["parameters" :parameters] ["strict" :strict]]))))
            tools)))

(defn flatten-tool-choice
  "Flatten a function tool_choice to `{:type \"function\" :name ...}`."
  [value]
  (if (and (map? value) (= (get value "type") "function"))
    (let [fnm (get value "function")]
      {:type "function" :name (get fnm "name")})
    value))

(defn flatten-response-format
  "Keep only name/schema/strict from a json_schema response_format."
  [format]
  (if (not= (get format "type") "json_schema")
    format
    (reduce (fn [r [src dst]]
              (if (contains? format src)
                (assoc r dst (get format src))
                r))
            {:type "json_schema"}
            [["name" :name] ["schema" :schema] ["strict" :strict]])))

;; ---------------------------------------------------------------------------
;; chat/completions -> responses
;; ---------------------------------------------------------------------------

(defn chat-to-responses
  "Parse a `/v1/chat/completions` body and return `[request model stream]`.
  Throws on invalid input. Unknown top-level fields are dropped: the upstream
  request is built only from recognized fields."
  [raw]
  (let [chat (-> (parse-object raw "request body must be a JSON object")
                 (schema/validate-chat!))
        model (get chat "model")
        messages (get chat "messages")]
    (validate-chat-semantics chat)
    (let [stream (get chat "stream")
          instructions (atom "")
          input (atom [])]
      (doseq [value messages]
        (let [role (get value "role")]
          (cond
              (or (= role "system") (= role "developer"))
              (let [text (content-text (get value "content"))]
                (when (not= text "")
                  (when (not= @instructions "")
                    (swap! instructions #(str % "\n\n")))
                  (swap! instructions #(str % text))))
              (= role "assistant")
              (let [text (content-text (get value "content"))
                    calls (get value "tool_calls")
                    legacy (get value "function_call")]
                (when (or (not= text "")
                          (and (empty? calls) (nil? legacy)))
                  (swap! input conj {:role "assistant" :content text}))
                (doseq [call calls]
                  (let [fnm (get call "function")]
                    (swap! input conj {:type "function_call"
                                       :call_id (get call "id")
                                       :name (get fnm "name")
                                       :arguments (get fnm "arguments")})))
                (when (map? legacy)
                  (swap! input conj {:type "function_call"
                                     :call_id (str "fc_" (get legacy "name"))
                                     :name (get legacy "name")
                                     :arguments (get legacy "arguments")})))
              (= role "tool")
              (swap! input conj {:type "function_call_output"
                                 :call_id (get value "tool_call_id")
                                 :output (content-text (get value "content"))})
              (= role "function")
              (swap! input conj {:type "function_call_output"
                                 :call_id (str "fc_" (get value "name"))
                                 :output (content-text (get value "content"))})
              (= role "user")
              (swap! input conj {:role "user"
                                 :content (response-content (get value "content"))})
            :else
            (throw (ex-info (str "unsupported message role: " role) {})))))
      (when (= @instructions "")
        (reset! instructions "You are a helpful assistant."))
      (when (empty? @input)
        (reset! input [{:role "user" :content ""}]))
      (let [request (merge
                     {:model model
                      :instructions @instructions
                      :input @input
                      :stream true
                      :store false}
                     (when (and (string? (get chat "reasoning_effort"))
                                (not= (get chat "reasoning_effort") ""))
                       {:reasoning {:effort (get chat "reasoning_effort")
                                    :summary "auto"}})
                     (when (contains? chat "service_tier")
                       {:service_tier (get chat "service_tier")})
                     (when (contains? chat "parallel_tool_calls")
                       {:parallel_tool_calls (get chat "parallel_tool_calls")})
                     (when (contains? chat "temperature")
                       {:temperature (get chat "temperature")})
                     (when (contains? chat "tools")
                       {:tools (flatten-tools (get chat "tools"))})
                     (when (contains? chat "functions")
                       {:tools (flatten-tools
                                (vec (map (fn [f] {:type "function" :function f})
                                          (get chat "functions"))))})
                     (when (contains? chat "tool_choice")
                       {:tool_choice (flatten-tool-choice (get chat "tool_choice"))})
                     (when (map? (get chat "response_format"))
                       {:text {:format (flatten-response-format
                                        (get chat "response_format"))}}))]
        [request model stream]))))

;; ---------------------------------------------------------------------------
;; responses -> normalized responses
;; ---------------------------------------------------------------------------

(def responses-request-keys
  "Top-level `/v1/responses` keys the proxy recognizes and keywordizes.
  Unknown top-level keys pass through upstream unchanged (string keys) under
  the documented compatibility policy."
  ["model" "input" "stream" "store" "instructions" "previous_response_id"
   "max_output_tokens" "max_tokens" "service_tier" "temperature" "top_p"
   "tools" "tool_choice" "parallel_tool_calls" "reasoning" "text"
   "prompt_cache_key" "include" "metadata" "truncation" "prompt" "seed"])

(defn- keywordize-known-keys
  "Convert recognized top-level string keys to keywords; leave unknown keys
  as strings so arbitrary client-controlled names are never interned."
  [m]
  (reduce (fn [m k]
            (if (contains? m k)
              (-> m (assoc (keyword k) (get m k)) (dissoc k))
              m))
          m
          responses-request-keys))

(defn keywordize-nested
  "Recursively convert string-keyed maps/vectors to keyword keys, up to
  `max-nesting-depth`. Input items are bounded (count + adapter body cap), so
  this keywordizes a bounded structure; deeper arbitrary keys outside `input`
  are never converted."
  [v]
  (letfn [(walk [v depth]
            (check (<= depth max-nesting-depth) "request nesting too deep"
                   {:limit max-nesting-depth})
            (cond
              (map? v)
              (reduce (fn [m entry]
                        (let [k (key entry)]
                          (assoc m (keyword k) (walk (val entry) (inc depth)))))
                      {} v)
              (vector? v)
              (mapv #(walk % (inc depth)) v)
              :else v))]
    (walk v 0)))

(defn prepare-responses
  "Parse a `/v1/responses` body and return `[request model stream]`. Forces
  `:stream true`, `:store false`, a default `:instructions`, and drops
  `:max_output_tokens`/`:max_tokens`. `:previous_response_id` is passed through
  unchanged (R6). Unknown top-level keys pass through upstream with their
  original string keys."
  [raw]
  (let [request (-> (parse-object raw "request body must be a JSON object")
                    (schema/validate-responses!))
        model (get request "model")
        input (get request "input")]
    (let [request (keywordize-known-keys request)
          request (if (vector? input)
                    (assoc request :input (keywordize-nested input))
                    request)
          stream (get request :stream)
          request (if (string? (get request :input))
                    (assoc request :input [{:role "user"
                                            :content (get request :input)}])
                    request)
          request (assoc request :stream true :store false)
          request (if (contains? request :instructions)
                    request
                    (assoc request :instructions "You are a helpful assistant."))
          request (dissoc request :max_output_tokens :max_tokens)
          request (if (= (:service_tier request) "fast")
                    (assoc request :service_tier "priority")
                    request)]
      [request model stream])))
