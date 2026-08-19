(ns codex.schema
  "Declarative, string-keyed request-shape schemas.

  These schemas run immediately after JSON parsing and before any client key is
  converted to a keyword. Their `:map` schemas deliberately remain open: chat
  translation explicitly drops unknown fields, while Responses requests must
  preserve unknown top-level string keys for upstream compatibility. Semantic
  translation policy remains in `codex.translate`."
  (:require [malli.core :as m]))

(def max-messages 1000)
(def max-tools 128)
(def max-input-items 1000)

(defn non-empty-string? [value]
  (and (string? value) (not= value "")))

(def content
  "A Chat content value accepted by the translator. Content-part maps stay
  open because the translator only consumes the supported part fields."
  [:maybe [:or :string [:vector :map]]])

(def tool-call
  [:map
   ["function" map?]])

(def chat-message
  [:map
   ["role" :string]
   ["content" {:optional true} content]
   ["tool_calls" {:optional true} [:maybe [:vector tool-call]]]
   ["tool_call_id" {:optional true} [:maybe :string]]])

(def tool
  "An open tool object. A supplied `function` wrapper must itself be an object;
  type-specific flattening remains translation policy."
  [:map
   ["type" {:optional true} :string]
   ["function" {:optional true} :map]])

(def tool-list
  [:maybe [:vector {:max max-tools} tool]])

(def chat-request
  "Open, string-keyed `/v1/chat/completions` request shape."
  [:map
   ["model" [:fn non-empty-string?]]
   ["messages" [:vector {:min 1 :max max-messages} chat-message]]
   ["stream" {:optional true} [:maybe boolean?]]
   ["temperature" {:optional true} [:maybe number?]]
   ["parallel_tool_calls" {:optional true} [:maybe boolean?]]
   ["tool_choice" {:optional true} [:maybe [:or :string :map]]]
   ["tools" {:optional true} tool-list]
   ["functions" {:optional true} tool-list]])

(def responses-request
  "Open, string-keyed `/v1/responses` request shape. Unknown top-level fields
  intentionally remain valid and pass through upstream with string keys."
  [:map
   ["model" [:fn non-empty-string?]]
   ["input" [:or :string [:vector {:max max-input-items} :map]]]
   ["stream" {:optional true} [:maybe boolean?]]
   ["tools" {:optional true} tool-list]])

(defn validate!
  "Validate `value` and throw the endpoint's stable client-facing message.
  Detailed Malli data is retained in ex-data for local diagnostics only; proxy
  handlers expose just `ex-message` in their OpenAI-shaped 400 response."
  [schema value message]
  (when-not (m/validate schema value)
    (throw (ex-info message {:malli/explain (m/explain schema value)})))
  value)

(defn validate-chat! [value]
  (validate! chat-request value "invalid chat request"))

(defn validate-responses! [value]
  (validate! responses-request value "invalid responses request"))
