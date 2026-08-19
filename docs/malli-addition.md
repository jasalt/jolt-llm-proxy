## Verdict

**Yes — Malli can apply usefully to `codex.translate` validation on Jolt, but it should complement rather than replace the existing translation/normalization code.**

The Jolt example demonstrates that Malli runs on Jolt with a small compatibility shim, and I verified a representative string-keyed request schema locally against **Jolt v0.7.13**.

### What I verified

Using the example’s pinned Malli revision and `borkdude.dynaload` shim:

- `m/validate` works for a string-keyed Chat request map.
- bounded vectors (`{:min 1 :max 1000}`) work.
- predicates such as `boolean?` and `number?` work.
- `m/explain` returns structured error paths.
- Malli maps are **open by default**, which is important:
  - unknown `/v1/responses` top-level keys validate and can remain string-keyed for upstream pass-through;
  - `{:closed true}` rejects unknown keys, if that policy is ever desired.

Example result from the local probe:

```clojure
(m/validate ChatRequest valid) ;=> true
(m/validate ChatRequest bad)   ;=> false
(m/explain ChatRequest bad)
;; => ... {:path ["stream"], :in ["stream"], :value "yes"} ...
```

## Integration requirements

The upstream Jolt example uses:

```clojure
{:paths ["src" "shims"]
 :deps {metosin/malli
        {:git/url "https://github.com/metosin/malli"
         :git/sha "a74e3b45efa30b3bcdb2e997f337c71614eba3c5"}}}
```

and a required shim at `shims/borkdude/dynaload.clj`:

```clojure
(ns borkdude.dynaload)

(defn dynaload [_sym & [opts]]
  (delay (:default opts)))
```

That is necessary because Malli’s load path references optional JVM-oriented dependencies. The Jolt example explicitly warns that Malli is heavily JVM-coupled internally, despite its supported validation surface working.

## Good fit for this project

Malli would reduce repetitive shape checks in `src/codex/translate.clj`, particularly:

| Current rule | Malli fit |
|---|---|
| JSON top-level object | Strong |
| required non-empty `model` string | Strong |
| `messages` / `input` vector bounds | Strong |
| vector entries must be maps | Strong |
| optional boolean `stream` | Strong |
| optional numeric `temperature` | Strong |
| tools/functions are vectors of maps, max 128 | Strong |
| `tool_choice` string-or-map | Strong |
| content is string or vector of maps | Strong |
| nested tool-call `function` object | Strong |
| detailed client-facing invalid-field diagnostics | Strong via `m/explain` |

A schema would need **string keys**, matching the deliberate no-keyword-interning parsing policy:

```clojure
(ns codex.schema
  (:require [malli.core :as m]))

(defn non-empty-string? [x]
  (and (string? x) (not= x "")))

(def chat-request
  [:map
   ["model" [:and :string [:fn non-empty-string?]]]
   ["messages"
    [:vector {:min 1 :max 1000}
     [:map
      ["role" :string]
      ["content" {:optional true}
       [:or :string [:vector :map]]]
      ["tool_calls" {:optional true}
       [:vector
        [:map ["function" :map]]]]]]]
   ["stream" {:optional true} boolean?]
   ["temperature" {:optional true} number?]
   ["tools" {:optional true}
    [:vector {:max 128} :map]]])
```

One Jolt-specific detail from the probe: use predicates such as `boolean?` and `number?` for these values. The attempted keyword schema `:number` was invalid with the pinned Malli revision. The example confirms primitives such as `:string` and `:int`; predicates are the more portable choice for broader numeric/boolean cases.

## What should remain handwritten

Malli should **not** replace `translate.clj` wholesale. The following are transformation or policy logic, not merely validation:

- parsing request JSON with string keys;
- explicit recognized-key extraction for Chat requests;
- preserving unknown `/v1/responses` keys as string keys;
- keywordizing the recognized `/v1/responses` allowlist;
- bounded recursive keywordization of `:input` for continuation compatibility;
- the 32-level custom nesting cap;
- translating Chat messages/tools/response formats to Responses API shape;
- defaulting `:instructions`, forcing `:stream true` and `:store false`;
- `max_output_tokens` / `max_tokens` removal;
- `service_tier "fast"` → `"priority"`;
- semantic rule `n` must equal 1;
- error-message compatibility and HTTP-400 mapping.

In particular, the current string-key policy is security-sensitive. A schema must validate string-keyed input **before** any keyword conversion; it should never encourage using `json/read-str` with `:key-fn keyword`.

## Recommended adoption shape

I recommend a small, isolated `codex.schema` namespace and a phased use:

1. Add pinned Malli dependency plus the exact shim from the Jolt example.
2. Define **open** string-keyed schemas:
   - `chat-request-schema`
   - `responses-request-schema`
   - reusable `content`, `tool`, and `input-item` schemas.
3. Keep current parsing and normalization in `codex.translate`.
4. Replace only mechanical `check` blocks with:
   ```clojure
   (when-not (m/validate chat-request-schema request)
     (throw (ex-info "invalid chat request"
                     {:malli/explain (m/explain chat-request-schema request)})))
   ```
5. Convert Malli explanations into the project’s stable, non-sensitive client error form rather than exposing raw schema internals.
6. Retain focused tests for:
   - unknown Responses-field pass-through;
   - continuation prefix matching;
   - max tool/message/input limits;
   - malformed nested function calls and content parts;
   - nesting depth.

## Trade-offs

**Benefits**
- More declarative request contracts.
- Less bespoke shape-validation code.
- Better structured diagnostics through `m/explain`.
- Easier expansion as OpenAI request support grows.

**Costs / risks**
- Adds a fairly large, JVM-oriented dependency to a deliberately small Jolt service.
- Requires a local compatibility shim.
- Jolt support is demonstrated by the example, but it is newer/less battle-tested than JVM Clojure usage.
- Does not eliminate the custom security/translation logic that makes this adapter correct.

## Recommendation

Adopt Malli **only for declarative structural validation**, behind a small `codex.schema` boundary, while retaining `codex.translate` as the owner of parsing, security-sensitive key handling, normalization, and request translation.

That would be a net improvement if the project expects the supported request surface to grow. If the current narrow API surface is intentionally stable, the existing explicit validators are already sound and avoid adding a nontrivial dependency.