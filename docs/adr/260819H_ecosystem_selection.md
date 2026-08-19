# 260819H — Keep the runtime stack small and select Jolt-proven libraries

- **Date:** 2026-08-19
- **Status:** Accepted

## Context

Clojure offers mature routing, lifecycle, middleware, observability, database,
and testing libraries, but many common choices are JVM implementations rather
than portable Clojure. The adapter currently has four routes, a small resource
graph, no database, and one narrow JSON protocol.

## Decision

Keep the current runtime architecture lightweight. Add libraries only when they
replace a demonstrated concern, and prefer libraries proven by this project or
Jolt's maintained examples.

Current justified runtime choices are:

- Ring Chez adapter for inbound HTTP;
- Jolt HTTP/TLS libraries for outbound traffic;
- `clojure.data.json` for JSON;
- Malli for structural request validation;
- Jolt time support only where project code actually uses it.

Suitable additions when their concern becomes real are:

- Jolt's `clojure.tools.logging` port for leveled, redacted diagnostics;
- `clojure.test.check` and Malli generators for property tests;
- Integrant for a materially larger lifecycle graph;
- Reitit plus `jolt-lang/router` for a materially larger route graph;
- selective Ring middleware rather than a browser-oriented defaults stack;
- Jolt nREPL/CIDER middleware as a development-only dependency.

For adjacent Jolt applications, the maintained examples establish viable
stacks including Ring Defaults, Hiccup or Selmer, HoneySQL with `jolt-lang/db`
and SQLite, Tick, multipart handling, Glimmer desktop/TUI applications, and
server-driven Datastar interfaces.

Do not assume JVM-bound defaults such as Jetty, Undertow, http-kit,
Aleph/Manifold, Jackson-backed JSON libraries, JDBC pools/drivers, Java Kafka
clients, Logback, Java OpenTelemetry agents, or JVM coverage runners can run on
Jolt.

## Consequences

- The adapter avoids framework cost that would not simplify its present shape.
- Ecosystem adoption has an explicit trigger instead of becoming speculative
  architecture.
- Adjacent applications can use a broader conventional Clojure stack where
  Jolt examples or focused probes establish compatibility.
- Dependency removals are appropriate when source inspection confirms a
  library is no longer used; transitive availability is not treated as an
  intentional project dependency.
