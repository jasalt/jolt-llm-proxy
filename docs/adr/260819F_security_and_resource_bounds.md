# 260819F — Apply local-proxy security and explicit resource bounds

- **Date:** 2026-08-19
- **Status:** Accepted and implemented

## Context

The service handles refresh/access tokens and can spend a user's ChatGPT
subscription allowance. Although the server is local, any local process can
reach it when proxy API-key authentication is disabled. Client-controlled
session identifiers also select retained WebSocket and continuation state.

## Decision

Adopt the following security boundary:

- bind through the loopback-only Ring adapter and reject misleading non-loopback
  `JOLT_LLM_PROXY_ADDR` hosts;
- support an optional bearer API-key guard with timing-resistant comparison;
- never print the API key and print only a short SHA-256 hash of the generated
  startup session identifier;
- normalize session identifiers to an HTTP-header-safe ASCII allowlist and at
  most 64 characters before using them as pool keys or upstream headers;
- cap request bodies through explicit server options and cap decoded request
  collections/nesting in translation schemas;
- cap pooled sessions and WebSocket message/header allocation;
- validate OAuth callback method, path, state, and cleanup; wrong-state probes
  do not consume the pending valid callback;
- persist only credential fields in an owner-only directory and atomic
  owner-only credential file (`0700` directory, `0600` file);
- distinguish a missing credential file from parse, permission, write, rename,
  and delete failures;
- classify boundary failures into stable public input/auth/upstream/timeout/
  internal responses, redact exception messages and bodies from clients, and
  log only classification plus explicitly selected safe context.

The proxy is not presented as a public multi-user service. External access must
be placed behind a separately authenticated TLS reverse proxy and changes the
threat model.

## Consequences

- Local unauthenticated operation remains possible but is explicitly a local
  trust choice.
- Client input cannot grow retained pool state without fixed bounds.
- Credential corruption and persistence failures are visible rather than
  silently interpreted as logout.
- Startup and failure logs no longer disclose reusable secrets or raw routing
  identifiers; public error responses do not expose raw exception messages.
- The private upstream API remains an operational compatibility risk and may
  change independently of this adapter.
