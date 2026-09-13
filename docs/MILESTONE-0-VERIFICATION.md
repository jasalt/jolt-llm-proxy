# Milestone 0 verification

Milestone 0 upgrades the supported runtime and adds the libraries needed by the
later lifecycle/reactivity milestones. It intentionally does not change proxy
or dashboard behavior.

## Baseline

Captured before dependency changes on 2026-09-13:

- `jolt` was not installed directly on the sandbox `PATH`.
- The pre-upgrade Nix lock resolved Jolt to the floating `main` commit
  `8acccbdf3c46e72ed1521adaa2020258c12f9abd` (`jolt 8acccbd`).
- `nix develop --command jolt -M:test -m llm-proxy.test-runner` passed:
  63 tests, 260 assertions, 0 failures, 0 errors.
- No test-suite failures were present to carry forward.

Live backend checks could not be run because no credentials were available in
the sandbox. They remain a release/manual verification step.

## Upgraded verification

The supported runtime is Jolt v0.8.7, pinned in `flake.nix` to release commit
`d782ba692f4bdc2a2975e19832bed0a32a1fd331`. CI downloads that exact release
and verifies its SHA-256 checksum.

The first upgraded namespace load exposed a real compatibility boundary in the
old `jolt-lang/http-client` pin: Jolt v0.8.7 correctly rejected a `recur` across
`try`. `http-client` and `ring-chez-adapter` were therefore advanced to exact
Jolt-0.8-compatible commits. No application behavior was changed.

Verified after the upgrade:

- all production namespaces plus `ebb.core`, `glimmer.ratom`, and
  `jolt.datastar.core` load;
- 63 tests and 260 assertions pass;
- a standalone development AOT build succeeds and its embedded CLI help runs;
- the standalone server starts with an isolated smoke-test credential, `/health`,
  `/v1/models`, and `/_llm-proxy/` respond, and its nREPL socket accepts a
  connection;
- the Nix package builds;
- `nix flake check --no-build` passes on `x86_64-linux`.

The full credentialed `/v1/responses`, `/v1/chat/completions`, and WebSocket
continuation checks still require the manual verification described in
`PLAN.md`.
