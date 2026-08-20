# 260819B — Keep one explicit runtime owner

- **Date:** 2026-08-19
- **Status:** Accepted and implemented

## Context

Early versions spread runtime state across namespace-global atoms in the core,
proxy, and WebSocket layers. That made startup failure, shutdown, live reload,
and independent handler tests difficult to reason about.

The service currently owns only a small number of resources: credential state,
a WebSocket session pool, the Ring handler, and the HTTP server.

## Decision

`llm-proxy.core` is the sole application lifecycle owner.

Each `start!` creates an isolated token store, session pool, session identifier,
and immutable runtime dependency map. `llm-proxy.proxy/make-handler` closes over
that runtime; production namespaces do not copy the system into another global.
Server start/stop functions and key lifecycle inputs are injectable for tests.

Startup is transactional: a server-start failure closes created connections,
clears state, and rethrows. `stop!` clears ownership first, stops the exact
server instance, closes pooled connections, and is idempotent.

Keep this explicit lifecycle instead of introducing Integrant, Component, or
Mount while the resource graph remains this small.

## Consequences

- A handler can be tested with a private store, pool, API key, and HTTP seam.
- Start/stop cycles do not intentionally share conversation or credential-store
  runtime state.
- Resource ownership and cleanup are visible in one namespace.
- A lifecycle framework remains suitable only if the application develops a
  materially larger graph such as a database, metrics server, scheduler,
  configuration watcher, or independent background workers.

## Evidence

The lifecycle behavior is covered by `test/llm_proxy/core_test.clj`, including
explicit server options, cleanup after failed startup, isolated configuration,
and idempotent stop behavior.
