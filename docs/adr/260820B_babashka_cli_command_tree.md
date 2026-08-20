# 260820B — Use `org.babashka/cli` for command dispatch

- **Date:** 2026-08-20
- **Status:** Accepted and implemented

## Context

The initial CLI used a small hand-written `case` dispatch and a custom parser
for `serve --nrepl` and `serve --dashboard`. It was adequate for five positional
commands, but it duplicated option validation and did not provide generated
help or a structured path for command-specific options.

The service is expected to grow command options such as `serve --port`,
`login --device`, `usage --json`, and `info --full`. The prior review identified
`org.babashka/cli` as a lightweight command-tree parser suitable for that
future surface. It was directly probed on Jolt with version `0.12.86`: command
dispatch and `:long` / `:boolean` coercion succeeded.

## Decision

Use pinned `org.babashka/cli` `0.12.86` as a runtime dependency for the
`llm-proxy.core` command tree.

- Keep `serve`, `login`, `logout`, `usage`, and `info` as explicit commands.
- Preserve the historical no-argument behavior by dispatching no arguments as
  `serve`.
- Define each command's option spec next to its handler. `serve` accepts
  `--port` / `-p` for the validated proxy HTTP port, boolean `--nrepl`, and
  `--nrepl-port` / `-np` for the validated nREPL port (only with `--nrepl`),
  plus `--dashboard`. The CLI HTTP port takes precedence over the port in
  `JOLT_LLM_PROXY_ADDR`; the address host remains loopback-only.
- Use strict option and positional-argument validation for `serve`; new options
  must be declared with coercion, validation, and help text before use.
- Delegate command/help/error rendering to `babashka.cli`; do not reintroduce
  an ad hoc option parser or add `clojure.tools.cli`.

## Consequences

- The CLI has generated `--help` output and consistent parser errors.
- Adding options does not require replacing the command-dispatch mechanism.
- The dependency is intentionally runtime-scoped because the production entry
  point invokes it.
- Command handlers remain ordinary functions, so parser behavior and handlers
  can be tested independently without starting a blocking server.

## Evidence

`test/llm_proxy/core_test.clj` verifies strict parsing, aliases, and port
bounds for the `serve` options. A direct Jolt compatibility probe of `babashka.cli/dispatch`
with version `0.12.86` parsed `serve --port 8081 --dashboard` into
`{:port 8081 :dashboard true}` before adoption.
