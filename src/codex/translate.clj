(ns codex.translate
  "Translate between `/v1/chat/completions` and `/v1/responses` bodies.
  Mirrors Go `translate.go` (`chatToResponses`, `prepareResponses`, and the
  flattening helpers). All produced request bodies set `:store false` (R6)."
  (:require [clojure.data.json :as json]))

(defn- to-str
  "Coerce a request body (string or byte array) to a string for JSON parsing."
  [raw]
  (if (string? raw) raw (String. raw "UTF-8")))

;; ---------------------------------------------------------------------------
;; Content extraction / construction
;; ---------------------------------------------------------------------------

(defn content-text
  "Join the text of `:text` content parts with \"\\n\"."
  [value]
  (cond
    (string? value) value
    (vector? value)
    (let [sb (StringBuilder.)]
      (doseq [part value]
        (when (and (map? part) (= (:type part) "text"))
          (let [t (:text part)]
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
                       (= (:type part) "text")
                       [{:type "input_text" :text (:text part)}]
                       (= (:type part) "image_url")
                       (let [image (or (:image_url part) (:url part))
                             image (if (map? image) (:url image) image)]
                         [{:type "input_image" :image_url image}])
                       :else [])))
                 value))
    :else nil))

;; ---------------------------------------------------------------------------
;; Flattening helpers
;; ---------------------------------------------------------------------------

(defn flatten-tools
  "Drop the `function` wrapper from function tools, keeping known sub-keys."
  [tools]
  (vec (map (fn [tool]
              (if (not= (:type tool) "function")
                tool
                (let [fnm (:function tool)]
                  (reduce (fn [flat k]
                            (if (contains? fnm k)
                              (assoc flat k (get fnm k))
                              flat))
                          {:type "function"}
                          [:name :description :parameters :strict]))))
            tools)))

(defn flatten-tool-choice
  "Flatten a function tool_choice to `{:type \"function\" :name ...}`."
  [value]
  (if (and (map? value) (= (:type value) "function"))
    (let [fnm (:function value)]
      {:type "function" :name (:name fnm)})
    value))

(defn flatten-response-format
  "Keep only name/schema/strict from a json_schema response_format."
  [format]
  (if (not= (:type format) "json_schema")
    format
    (reduce (fn [r k]
              (if (contains? format k)
                (assoc r k (get format k))
                r))
            {:type "json_schema"}
            [:name :schema :strict])))

;; ---------------------------------------------------------------------------
;; chat/completions -> responses
;; ---------------------------------------------------------------------------

(defn chat-to-responses
  "Parse a `/v1/chat/completions` body and return `[request model stream]`.
  Throws on invalid input."
  [raw]
  (let [chat (json/read-str (to-str raw) :key-fn keyword)
        model (:model chat)
        messages (:messages chat)]
    (when-not (map? chat)
      (throw (ex-info "request body must be a JSON object" {})))
    (when-not (and (string? model) (not= model ""))
      (throw (ex-info "model must be a non-empty string" {})))
    (when-not (and (vector? messages) (not (empty? messages)))
      (throw (ex-info "messages must be a non-empty array" {})))
    (when (> (count messages) 1000)
      (throw (ex-info "too many messages" {:limit 1000})))
    (let [stream (:stream chat)
          instructions (atom "")
          input (atom [])]
      (doseq [value messages]
        (when-not (map? value)
          (throw (ex-info "each message must be an object" {})))
        (let [role (:role value)]
          (when-not (string? role)
            (throw (ex-info "message role must be a string" {})))
          (cond
              (or (= role "system") (= role "developer"))
              (let [text (content-text (:content value))]
                (when (not= text "")
                  (when (not= @instructions "")
                    (swap! instructions #(str % "\n\n")))
                  (swap! instructions #(str % text))))
              (= role "assistant")
              (let [text (content-text (:content value))
                    calls (:tool_calls value)
                    legacy (:function_call value)]
                (when (or (not= text "")
                          (and (empty? calls) (nil? legacy)))
                  (swap! input conj {:role "assistant" :content text}))
                (doseq [call calls]
                  (let [fnm (:function call)]
                    (swap! input conj {:type "function_call"
                                       :call_id (:id call)
                                       :name (:name fnm)
                                       :arguments (:arguments fnm)})))
                (when (map? legacy)
                  (swap! input conj {:type "function_call"
                                     :call_id (str "fc_" (:name legacy))
                                     :name (:name legacy)
                                     :arguments (:arguments legacy)})))
              (= role "tool")
              (swap! input conj {:type "function_call_output"
                                 :call_id (:tool_call_id value)
                                 :output (content-text (:content value))})
              (= role "function")
              (swap! input conj {:type "function_call_output"
                                 :call_id (str "fc_" (:name value))
                                 :output (content-text (:content value))})
              (= role "user")
              (swap! input conj {:role "user"
                                 :content (response-content (:content value))})
            :else
            (throw (ex-info (str "unsupported message role: " role) {})))))
      (when (= @instructions "")
        (reset! instructions "You are a helpful assistant."))
      (when (empty? @input)
        (reset! input [{:role "user" :content ""}]))
      (when (and (number? (:n chat)) (not= (:n chat) 1))
        (throw (ex-info "only n=1 is supported" {})))
      (let [request (merge
                     {:model model
                      :instructions @instructions
                      :input @input
                      :stream true
                      :store false}
                     (when (and (string? (:reasoning_effort chat))
                                (not= (:reasoning_effort chat) ""))
                       {:reasoning {:effort (:reasoning_effort chat)
                                    :summary "auto"}})
                     (when (contains? chat :service_tier)
                       {:service_tier (:service_tier chat)})
                     (when (contains? chat :parallel_tool_calls)
                       {:parallel_tool_calls (:parallel_tool_calls chat)})
                     (when (contains? chat :temperature)
                       {:temperature (:temperature chat)})
                     (when (contains? chat :tools)
                       {:tools (flatten-tools (:tools chat))})
                     (when (contains? chat :functions)
                       {:tools (flatten-tools
                                (vec (map (fn [f] {:type "function" :function f})
                                          (:functions chat))))})
                     (when (contains? chat :tool_choice)
                       {:tool_choice (flatten-tool-choice (:tool_choice chat))})
                     (when (map? (:response_format chat))
                       {:text {:format (flatten-response-format
                                        (:response_format chat))}}))]
        [request model stream]))))

;; ---------------------------------------------------------------------------
;; responses -> normalized responses
;; ---------------------------------------------------------------------------

(defn prepare-responses
  "Parse a `/v1/responses` body and return `[request model stream]`. Forces
  `:stream true`, `:store false`, a default `:instructions`, and drops
  `:max_output_tokens`/`:max_tokens`. `:previous_response_id` is passed through
  unchanged (R6)."
  [raw]
  (let [request (json/read-str (to-str raw) :key-fn keyword)
        model (:model request)]
    (when-not (map? request)
      (throw (ex-info "request body must be a JSON object" {})))
    (when-not (and (string? model) (not= model ""))
      (throw (ex-info "model must be a non-empty string" {})))
    (when (not (contains? request :input))
      (throw (ex-info "input is required" {})))
    (when-not (or (string? (:input request)) (vector? (:input request)))
      (throw (ex-info "input must be a string or array" {})))
    (when (and (vector? (:input request)) (> (count (:input request)) 1000))
      (throw (ex-info "too many input items" {:limit 1000})))
    (let [request (if (string? (:input request))
                    (assoc request :input [{:role "user"
                                            :content (:input request)}])
                    request)
          stream (:stream request)
          request (assoc request :stream true :store false)
          request (if (contains? request :instructions)
                    request
                    (assoc request :instructions "You are a helpful assistant."))
          request (dissoc request :max_output_tokens :max_tokens)
          request (if (= (:service_tier request) "fast")
                    (assoc request :service_tier "priority")
                    request)]
      [request model stream])))
