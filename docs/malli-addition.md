# Malli addition review and implementation status

**Status: implemented for structural request validation (2026-08-19).**

Malli is now used as a small, isolated validation boundary in
`codex.schema`. It validates parsed, **string-keyed** client JSON before any
client-controlled key is converted to a keyword. `codex.translate` retains
translation, normalization, continuation compatibility, and all other policy
logic.

## Completed recommendation checklist

| Recommendation | Status | Implementation |
| --- | --- | --- |
| Pin Malli | **Done** | `deps.edn` pins `metosin/malli` at `a74e3b45efa30b3bcdb2e997f337c71614eba3c5`. Malli's resolved Maven dependencies include `borkdude/dynaload`, so no local shim is needed in this project. |
| Keep schemas in a dedicated namespace | **Done** | `src/codex/schema.clj` owns reusable schemas and no proxy/transport namespace requires Malli directly. |
| Validate string-keyed input before keyword conversion | **Done** | `chat-to-responses` and `prepare-responses` parse through `json/read-str` without a key function, then call `schema/validate-chat!` / `schema/validate-responses!`. |
| Use open maps for compatibility | **Done** | Both endpoint schemas are open. Chat translation still drops unknown fields explicitly; Responses keeps unknown top-level fields as string keys for upstream pass-through. |
| Replace mechanical shape checks only | **Done** | Malli owns model/messages/input/tool/content/option shapes and collection bounds. Translation and security policies remain handwritten. |
| Preserve stable non-sensitive client errors | **Done** | `schema/validate!` puts `m/explain` under `:malli/explain` in local `ex-data`, but throws endpoint-stable messages. The proxy exposes only `ex-message` in its OpenAI-shaped HTTP 400. |
| Test project schemas and useful Malli forms | **Done** | `test/codex/schema_test.clj`, registered in the deterministic test runner, covers project schemas, explanations, open/closed maps, tuples, enums, regexes, map-of, and discriminated multi schemas. |

The suite currently passes with **28 tests / 96 assertions**:

```console
jolt -M:test -m codex.test-runner
```

## Schema boundary

`codex.schema` currently validates the structural subset the proxy consumes:

- non-empty string `model`;
- one to 1,000 Chat messages, each an object with string `role` and optional
  string/vector-of-object `content`;
- one to 1,000 Responses input objects (or a string input);
- optional boolean `stream` and `parallel_tool_calls`;
- optional numeric `temperature`;
- optional string-or-object `tool_choice`;
- `tools`/legacy `functions` as at most 128 objects;
- supplied `tool.function` and assistant `tool_calls[*].function` wrappers as
  objects;
- optional string `tool_call_id`.

Schemas intentionally use **string JSON keys**. This maintains the protection
against unbounded interning of arbitrary top-level client keys. The only
recursive keyword conversion remains the bounded Responses `input` conversion,
which is necessary for keyword-keyed continuation prefix matching.

## Deliberately left outside Malli

These items are still owned by `codex.translate` or endpoint handling and are
not omissions:

| Concern | Reason it remains handwritten |
| --- | --- |
| Parsing bytes and malformed JSON | Transport/body concern preceding schema validation. |
| `n` must be `1` | Adapter support policy rather than input shape. |
| 32-level nested-input cap | Applied during the only recursive keyword conversion; it limits the conversion work itself. |
| Chat-to-Responses message/tool/format transformation | Produces a different API representation, defaults fields, and drops unsupported fields. |
| `:stream true`, `:store false`, default instructions, max-token removal, service-tier remapping | Intentional proxy behavior, not client validity. |
| Responses recognized-key allowlist and unknown-key pass-through | Security/compatibility transformation policy. |
| Exact per-field OpenAI semantic rules | The adapter supports a constrained API subset; adding every upstream contract field is separate product work. |
| Humanized Malli errors in HTTP responses | `m/explain` is retained locally, but external error wording remains stable and avoids exposing schema internals. |

## Jolt capability investigation

The following operations were exercised in this repository with Jolt **v0.7.13**
and Malli commit `a74e3b45efa30b3bcdb2e997f337c71614eba3c5`:

| Malli facility | Result | Relevance |
| --- | --- | --- |
| `m/validate`, `m/explain` | Works | Used by `codex.schema`. Explain results include useful `:in` paths. |
| Open and `{:closed true}` `:map` schemas | Works | Open maps implement Responses compatibility; closed maps are available for future internal payloads. |
| `:vector` bounds, `:tuple`, `:enum`, `:re`, `:map-of` | Works | Suitable for additional declarative request constraints. |
| `:multi` with a function dispatch over string-keyed maps | Works | Suitable if content-part variants later need strict discrimination. |
| `malli.error/humanize` | Works | Available for local/operator diagnostics; intentionally not emitted to clients. |
| `malli.transform/string-transformer` and `m/decode` | Works | Available but not used: coercion would weaken the proxy's strict wire validation. |
| `malli.json-schema/transform`, `malli.swagger/transform` | Works | Available for future generated documentation, not used at runtime. |
| `malli.generator/generate` | Works | Available for future property/generative tests, not introduced into the deterministic suite. |

### Important usage notes

- The tested Malli revision accepts `:string` and `:int` keyword schemas, but
  the attempted `:number` keyword schema was invalid. Use the portable
  predicate `number?` for JSON numeric fields and `boolean?` for booleans, as
  `codex.schema` does. This is Malli schema vocabulary behavior, not a Jolt
  divergence.
- `:multi` dispatch is a value/function (for string-keyed JSON, e.g.
  `(fn [value] (get value "type"))`), not a literal string key.
- Malli `.cljc` sources select their `:clj` branches on Jolt. Its internals use
  JVM-shaped compatibility layers, but all facilities listed above loaded and
  executed in the project test environment. The pinned Malli dependency resolves
  its real `borkdude/dynaload` transitive dependency on this Jolt installation;
  unlike the standalone Jolt example, this project needs no copied shim.

## Upstream gap / feature-request assessment

**No reproducible missing Malli operation, packaging issue, or Jolt
language-convergence defect was found in the validation surface exercised
above.** Consequently, no Malli entry has been added to `JOLT-ISSUES.md`.
Filing one would overstate the evidence.

The official `malli-app` example includes a small local `borkdude.dynaload`
shim, but dependency resolution for this project brings in Malli's real
transitive `borkdude/dynaload 0.3.5` artifact. A fresh minimal project with the
same pinned Git dependency also loaded `malli.core` and `malli.generator`
without that shim. The example's shim may be useful for a narrower/manual
classpath setup, but it is not a demonstrated requirement or upstream gap for
normal `deps.edn` resolution.

## Decision

Keep Malli limited to `codex.schema` structural validation. This removes
repetitive shape checks while preserving the existing security-sensitive
string-key parsing and translation semantics. Reassess schema expansion only
when the supported OpenAI request subset grows or generated API documentation
becomes a concrete need.
