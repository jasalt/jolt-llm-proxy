# 260819A — Use Jolt with narrow platform boundaries

- **Date:** 2026-08-19
- **Status:** Accepted and implemented

## Context

The adapter ports a Go service to Clojure without a JVM. Jolt runs Clojure on
Chez Scheme and supplies only selected `java.*` compatibility shims. Network,
cryptographic, file-permission, and concurrency behavior therefore cannot be
assumed from JVM implementations.

The service needs an inbound HTTP server, outbound HTTPS/SSE, an outbound RFC
6455 WebSocket client, OAuth token handling, and POSIX credential protection.

## Decision

Use Jolt as the runtime and keep platform-specific behavior at narrow edges:

- `ring-chez-adapter` owns inbound HTTP/1.1 and Ring conversion;
- `jolt-lang/http-client` owns ordinary outbound HTTPS/SSE requests;
- `codex.ws` uses `jolt.http.tls` for the private upstream WebSocket;
- `jolt.ffi` exposes the small POSIX `chmod` surface required by credential
  storage;
- `clojure.data.json` is the JSON representation boundary;
- dependencies are pinned to exact Git commits.

Ring requests, HTTP responses, and decoded JSON are persistent Clojure maps.
The outbound TLS stream is a Jolt tagged-table and is accessed only through
`jolt.host/ref-get`. This distinction is documented in namespace docstrings and
`docs/JOLT-GOTCHAS.md`.

## Consequences

- Most application code remains ordinary data-oriented Clojure.
- Jolt compatibility hazards are concentrated in `codex.auth` and `codex.ws`.
- JVM-backed libraries are not assumed usable merely because they expose a
  Clojure API; pure Clojure, `.cljc`, Jolt ports, or FFI-backed libraries are
  preferred.
- Platform gaps and surprising behavior remain recorded separately in
  `docs/JOLT-ISSUES.md`, `docs/JOLT-GOTCHAS.md`, and
  `docs/CLOJURE-CONVERGENCE.md` because they are upstream engineering records,
  not application design documents.
