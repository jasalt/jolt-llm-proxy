# 260819E — Validate string-keyed requests with Malli

- **Date:** 2026-08-19
- **Status:** Accepted and implemented

## Context

Client JSON is untrusted. Parsing arbitrary keys directly as keywords can intern
unbounded client-controlled names. Handwritten structural checks had also become
repetitive as nested message, content, tool, and option validation expanded.

The two endpoints have different compatibility policies:

- Chat translation builds a known upstream request and drops unknown fields.
- Responses preparation must preserve unknown top-level fields for private
  upstream compatibility.

Continuation prefix matching expects normalized input-item maps with keyword
keys.

## Decision

Parse request JSON with string keys, then validate it in `llm-proxy.schema` before
normalization. Malli is pinned to commit
`a74e3b45efa30b3bcdb2e997f337c71614eba3c5`.

The Chat and Responses schemas are open maps. They validate the structural
subset consumed by the adapter, including required model/input/messages,
collection bounds, booleans, numeric temperature, content forms, and tool
wrappers. Limits are 1,000 messages, 1,000 Responses input items, and 128 tools
or legacy functions.

Malli owns mechanical shape validation only. `codex.translate` retains:

- malformed JSON handling;
- the `n=1` adapter policy;
- Chat-to-Responses transformation and defaults;
- the recognized Responses top-level key set;
- unknown-key pass-through policy;
- the 32-level recursive conversion limit;
- keywordization of validated Responses input needed by continuation matching.

Validation exceptions expose stable endpoint messages. Detailed `m/explain`
data stays in local `ex-data` and is not serialized to clients.

## Consequences

- Arbitrary top-level client keys are not interned.
- Responses extensions survive with their original string keys.
- Structural schemas remain declarative without hiding translation policy in
  coercion or schema transformers.
- Malli maps, explanations, bounds, tuples, enums, regexes, map-of, multi
  schemas, humanization, transforms, JSON Schema/Swagger transforms, and
  generators were exercised successfully on Jolt v0.7.20. Standard
  `test.check` string generators are generator values, so use
  `(gen/generate gen/string-alphanumeric)` without invoking the value.
- No reproducible Jolt/Malli defect or required local dynaload shim was found
  under normal project dependency resolution.

## Evidence

`test/llm_proxy/schema_test.clj` covers project schemas, open/closed map behavior,
structured explanations, and the broader useful Malli vocabulary.
