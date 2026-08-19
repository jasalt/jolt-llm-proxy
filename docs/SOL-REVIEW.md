# Solution review

Review date: 2026-08-19

Scope: the tracked Jolt implementation under `src/codex/`, its executable
entry point, dependencies, tests, and user/developer documentation. The review
used the checked-in source as the authority and compared security-sensitive
behavior with the Go reference implementation where useful.

The implementation is a credible prototype: responsibilities are separated by
namespace, dependencies are pinned to exact commits, outbound TLS verifies the
peer through the upstream library, PKCE uses `SecureRandom` and SHA-256, the
proxy uses a timing-resistant API-key comparison, request size is bounded by
`ring-chez-adapter`, and the main namespaces load successfully on Jolt v0.7.16.
The highest-value remaining work is not a broad rewrite. It is to harden secret
storage and lifecycle/concurrency behavior, then replace the manual acceptance
scripts with deterministic tests.

## Implementation progress

Progress is updated with each atomic implementation commit.

| Status | Priority | Improvement | Why it matters |
| --- | --- | --- | --- |
| Done | P0 | Save credentials atomically with mode `0600` | Implemented POSIX mode enforcement, temporary-file rename, explicit read/write/delete errors, and credential-field filtering |
| Done | P0 | Make WebSocket pool acquisition/release atomic | Added serialized pool transitions, identity-checked/idempotent release, and idempotent connection close |
| Done | P0 | Guarantee acquired WS connections are released on every failure | `ws-source` now releases with `keep=false` if setup or the initial write fails |
| Done | P0 | Report chat stream failures as failures | Failed streams now emit an error, never a successful stop chunk, and finalize with `:completed false` |
| Done | P1 | Add real automated tests and CI | Added 10 deterministic tests (25 assertions), a failing test runner, and a Jolt v0.7.16 CI workflow |
| Done | P1 | Bound and normalize client session IDs and pool cardinality | IDs are allowlisted/clamped and the cache is capped at 128 sessions with idle LRU eviction |
| Done | P1 | Harden OAuth callback handling and cleanup | Callback now validates method/path, ignores wrong-state probes without aborting login, uses static HTML, and always stops the server |
| In progress | P1 | Consolidate runtime ownership and split `codex.proxy` | Runtime ownership and dependency injection are complete; splitting transport/collector namespaces remains |
| In progress | P1 | Validate request shapes and avoid keywordizing arbitrary JSON | Top-level/model/messages/input types and item counts are validated; string-key parsing and deeper per-field limits remain |
| Done | P2 | Correct operational documentation and CLI behavior | README now documents prerequisites, tests, private-API/local security, address constraints, and credential modes; TODO status was reconciled |

## P0 — security and correctness

### 1. Protect the credential file

**Locations:** `src/codex/auth.clj:29-46`, `src/codex/auth.clj:162-169`

**Status: implemented.** `save-cred!` now filters runtime-only fields, creates
an owner-only parent directory, writes an owner-only same-directory temporary
file, and renames it over the target. `load-cred` tightens existing file mode
and distinguishes absence from corruption; logout reports deletion failure.
The original review observed:

```text
644 user:user ~/.config/chatgpt-openai-api-adapter/auth.json
```

That file contains both access and refresh tokens. Any other local account able
to traverse the home/config directories may read it. A crash or full disk can
also leave a partially written JSON file. `load-cred` then catches every error
and returns nil, misreporting corruption or permission failure as "not logged
in".

The Go reference already has the desired behavior: create the directory as
`0700`, write `path.tmp` as `0600`, then atomically rename it.

**Implemented change:**

1. Serialize only credential fields, never `:path` or `:lock`.
2. Create the parent directory with owner-only permissions where Jolt exposes
   the necessary POSIX operation.
3. Write a same-directory temporary file, flush/close it, set mode `0600`, and
   rename it over the target.
4. Tighten an existing target's mode during load or save.
5. Propagate parse, permission, mkdir, write, rename, and delete failures with
   the path and operation in `ex-data`; only a genuine missing file should
   become nil.
6. Add tests for missing, malformed, truncated, and overly permissive files.

If Jolt lacks the required chmod/atomic-rename shim, implement the small POSIX
operation through `jolt.ffi` and document that platform requirement rather than
silently accepting world-readable tokens.

### 2. Serialize all WebSocket pool state transitions

**Status: implemented.** Pool transitions now use a stable lock, release hooks
are idempotent and connection-identity checked, and connection close is
idempotent. Concurrent requests cannot both claim one cached reader. Dialing is
intentionally serialized as a simple correctness-first tradeoff; it can later
be replaced with reserved entries if handshake throughput requires it.

**Locations:** `src/codex/ws.clj:313-390`

The original pool used an atom, but acquisition was a multi-step read/check/dial/write
sequence. An atom only makes an individual dereference or `swap!` atomic. It
does not make the overall transaction atomic.

Examples of current races:

- Two requests can both observe no session, both dial, and both `assoc`; one
  live connection is overwritten and leaked.
- Two requests can both observe `:busy false`, both set it true, and both reuse
  the same socket. The write lock prevents frame-byte interleaving, but both
  readers can consume each other's events and corrupt request/response pairing.
- An idle timer can close a connection while an acquire is cancelling the timer
  and marking the session busy.
- A stale releaser can write `:busy false` into a newer entry because some
  updates do not consistently compare connection identity.

The Go implementation protects the pool and each session with mutexes. Preserve
that design in the port. Use a stable lock for pool membership and a per-session
lock for `busy`, timer, age, continuation, and close transitions. Do not hold
the global lock during network dialing; reserve an entry or dial outside the
lock and install it with an identity-checked transition. Add an idempotent
`closed?`/`released?` guard.

Required tests should force barriers around the race points and assert:

- one cached connection is installed for simultaneous first acquisition;
- a second request never receives a busy cached connection;
- timer cleanup cannot close a newly reacquired connection;
- every discarded connection is closed exactly once;
- a stale releaser cannot mutate a replacement session.

### 3. Release a WS acquisition if request setup fails

**Status: implemented.** `ws-source` now retains ownership inside a `try` and
releases with `keep=false` on setup, JSON encoding, or initial write failure.
Release is idempotent, so later cleanup cannot double-close the connection.

**Locations:** `src/codex/proxy.clj:178-213`, `src/codex/proxy.clj:223-233`

The original `ws-source` acquired a connection and then called `ws/write-text` before it
returns the source/finalizer. If JSON encoding or writing throws,
`open-event-source` catches the exception and falls back to SSE, but no code
calls `(:release acq)`. A cached session can remain `:busy true` indefinitely,
and a one-off socket can leak.

Wrap all post-acquisition setup in `try`/`catch` (or an explicit ownership
helper). Until ownership is successfully transferred to the returned source,
any exception must invoke release with `false`. Make release idempotent so the
same guarantee can safely cover failures in readers and finalizers as well.

A focused regression test can inject a `write-text` function that throws and
assert that the connection was closed and removed from the pool before SSE
fallback begins.

### 4. Do not convert a failed chat stream into successful completion

**Status: implemented.** A throwable now emits a generic OpenAI-shaped stream
error, no successful finish chunk, and metadata with `:completed false`. `[DONE]`
still closes the SSE protocol cleanly without claiming model success.

**Location:** `src/codex/proxy.clj:375-406`

The original `stream-chat` captured a throwable in `err`. Its conditional only treated
`nil err` plus an incomplete collector as an error. When `err` is non-nil, it
enters the success branch and emits a final chunk whose finish reason is usually
`"stop"`, followed by `[DONE]`. The caller therefore sees a normal completion
although the upstream stream failed.

Emit an OpenAI-shaped error event/chunk for `err` (with a sanitized public
message), do not emit a successful stop chunk, and finalize the transport with
`:completed false`. Align chat and Responses streaming around one explicit
terminal result, for example:

```clojure
{:status :completed|:incomplete|:failed
 :response-id ...
 :items ...
 :error ...}
```

Test disconnect, malformed event, explicit `response.failed`, emitter failure,
and normal completion separately.

## P1 — hardening and architecture

### 5. Replace smoke scripts with a real test suite and CI gate

**Status: implemented for the first deterministic baseline.** Tests now live
under `test/codex/`, run through `codex.test-runner`, and throw on any failure.
The initial 10 tests/25 assertions cover credential persistence/error handling,
continuation, translation, session validation, and the API-key guard. GitHub CI
loads every production namespace and runs the suite on Jolt v0.7.16. Transport
frame and forced-concurrency coverage should continue to expand with future
changes.

**Locations:** `test_continuation.clj`, `TODO.md:713-746`, repository root

Previously there was no tracked `test/` suite or CI workflow. `test_continuation.clj` prints
`PASS` only inside successful branches but never throws or exits nonzero when an
assertion fails. The TODO says to write `test_e2e.clj`, yet that file is not
tracked. Several complex areas have no automated coverage: translation,
collectors, OAuth callback, token persistence, frame parsing, pool races,
routes, and error cleanup.

Create test namespaces under `test/codex/` and a single command that returns a
nonzero status on failure. Keep live-subscription tests in a separate opt-in
suite; the default suite must need no credentials or network. Add injectable
boundaries for clock, random bytes, HTTP, TLS dial/read/write, browser launch,
and server start/stop so tests remain deterministic.

Highest-value test matrix:

- table-driven chat/Responses translation, invalid JSON and invalid shapes;
- SSE parsing, multi-line data, `[DONE]`, malformed JSON, and premature EOF;
- chat/Responses collectors for every terminal and tool-call event;
- WebSocket lengths (125/126/65535/65536), fragmentation, ping/pong, close,
  invalid opcodes, oversized aggregate messages, and partial reads;
- concurrent pool acquire/release and timer races;
- 401 refresh retry and refresh-save failure;
- API-key guard and every route;
- OAuth callback success, wrong state, provider error, duplicate callback, and
  timeout cleanup;
- lifecycle start failure and idempotent stop.

Run namespace loading, `brepl balance` (while accounting for its documented
limitations), unit tests, and secret scanning in CI. A test failure must be a
failing process, not merely missing `PASS` text.

### 6. Bound client-controlled session state

**Status: implemented.** Client-provided IDs are trimmed, restricted to an
HTTP-header-safe ASCII allowlist, and clamped to 64 characters before becoming
pool keys or upstream headers. Invalid values receive a `400`. The connection
cache is capped at 128 entries; the least-recently-used idle connection is
closed to make room, and a fully busy cache uses an uncached one-off connection
rather than growing. Per-session timers remain an optimization opportunity.

**Locations:** `src/codex/proxy.clj:59-73`, `src/codex/ws.clj:313-390`

Originally `clamp-prompt-cache-key` existed but was never called. `resolve-session-id`
returns an arbitrary client header unchanged, and that value becomes:

- a WebSocket pool key;
- continuation history containing prior request and response items;
- two upstream request headers;
- a log value;
- the owner of a five-minute future/timer.

An authenticated client—or any local process when no API key is configured—can
continually submit unique IDs and create many outbound connections, retained
conversation maps, and sleeping futures. The inbound adapter's 1 MiB request
cap helps with individual requests but does not bound aggregate pool state.

Normalize once at the trust boundary: trim, enforce an explicit UTF-8 byte or
ASCII-safe character policy, clamp to the documented 64-character limit, and
reject control characters. Add configurable maximum pooled sessions and total
continuation bytes, with LRU eviction and connection close. Prefer one periodic
sweeper over one future per session. Document that a session ID is a routing
and conversation-isolation key and should be unguessable if the proxy is shared
between mutually untrusted clients.

### 7. Harden the OAuth callback

**Status: implemented.** The callback now requires the expected GET path,
wrong-state probes cannot consume the pending login, browser HTML contains no
provider/exception text, and callback server shutdown is guaranteed by
`finally`. The pinned adapter is loopback-only.

**Locations:** `src/codex/cli.clj:39-139`

Useful PKCE protections were present, but callback handling had several gaps:

- Any request with a missing/wrong state delivers an error into the one-shot
  promise. A stray request, browser prefetch, or deliberate localhost request
  can abort the real login. Reject it without completing the pending flow.
- The handler does not require `GET /auth/callback`.
- Provider-controlled `error_description` and exception messages are embedded
  into HTML without escaping.
- Server shutdown is not in `finally`, so future edits or unexpected throws can
  leave port 1455 occupied.
- The callback bind address is not an explicit option at the call site. The
  current adapter is loopback-only, but that security assumption should be
  asserted and tested rather than inferred.

Validate method, path, state, and one-time use; HTML-escape all dynamic text;
return generic browser text while logging a sanitized diagnostic; and put
`stop-server` in `finally`. Consider accepting only the first **valid-state**
terminal callback and ignoring duplicates.

### 8. Consolidate runtime state and dependency injection

**Status: implemented.** `codex.core` now owns the only application lifecycle
atom. Every start creates an isolated pool and immutable runtime dependency map;
`codex.proxy/make-handler` closes over it. HTTP POST is injectable, startup is
transactional, server limits are explicit, and stop is idempotent. Tests prove
that handler instances retain independent API-key configuration.

**Locations:** `src/codex/core.clj:12`, `src/codex/proxy.clj:20`,
`src/codex/ws.clj:313`

Previously runtime ownership was spread across `codex.core/system`,
`codex.proxy/system`, and the global `codex.ws/pool`. `start!` copied a snapshot
from one atom into another, which could drift during partial startup, failed
startup, live reload, or programmatic use.

Have `codex.core` own one system map and construct a handler closure from
explicit dependencies:

```clojure
(make-handler {:token-store store
               :session-pool pool
               :http-client client
               :clock clock
               :config config})
```

The handler can still be held behind one reloadable Var for REPL development.
Make startup transactional: if anything after token-store creation fails, close
all resources and leave system nil. Make `stop!` idempotent and operate on the
exact owned resources. Avoid globally shared pools across start/stop cycles.

### 9. Split transport, collection, and routing

**Status: in progress.** Outbound HTTP, 401 retry, SSE parsing, and source
cleanup now live in `codex.transport.sse` with focused parser tests. Pooled WS
acquisition, delta continuation, initialization cleanup, event adaptation, and
finalization now live in `codex.transport.ws`. Runtime injection was completed
in item 8. Collectors and Ring routes still need extraction in similarly bounded
commits.

**Location:** `src/codex/proxy.clj`

The namespace originally owned configuration, auth middleware, routes, outbound
HTTP, SSE parsing, WS lifecycle, two stateful event collectors, streaming, and
response formatting. This was the main maintainability hotspot.

A useful split is:

- `codex.transport.sse` — upstream request and SSE parser;
- `codex.transport.ws` — source acquisition/finalization;
- `codex.collect.chat` and `codex.collect.responses` — pure event reducers;
- `codex.http` — Ring routes, middleware, and response formatting.

Represent collector text/calls as immutable data where practical. The current
maps contain mutable `StringBuilder` instances, so values that look immutable
can change without an atom transition. Pure reducers make replay-based tests
straightforward and eliminate subtle `swap!`/mutation interactions.

### 10. Validate untrusted JSON before translation

**Status: partially implemented.** Both endpoints now require an object body,
a non-empty string model, correct messages/input container types, map-shaped
chat messages with string roles, and at most 1000 messages/input items. Parsing
with string keys and detailed limits for nested tools/content remain open.

**Locations:** `src/codex/translate.clj`, `src/codex/proxy.clj:34-47`

Originally validation checked only a few fields. Examples include accepting a
non-string model, a non-vector `messages`, non-map tools, malformed content
parts, and arbitrary nested sizes until deeper functions fail. Every JSON key
is converted with `:key-fn keyword`; for arbitrary client-controlled keys this
may intern unbounded names in the runtime.

Add a bounded decoding/validation layer at the HTTP boundary:

- require object top level and exact basic types for model, input/messages,
  stream, tools, and content parts;
- cap message count, tool count, string lengths, nesting, and decoded body size
  below the adapter's global maximum;
- allowlist known top-level keys before converting them to keywords, or parse
  string-keyed JSON and explicitly extract recognized fields;
- preserve unknown pass-through fields only under a deliberate compatibility
  policy;
- distinguish malformed JSON (`400`) from unsupported options (`400`) and
  upstream/auth failures (`502`/`503`).

This also prevents low-quality error messages such as protocol internals being
returned directly from generic exception text.

### 11. Enforce aggregate WebSocket message limits and protocol invariants

**Status: implemented.** Fragmented messages now use a linear accumulator with
an aggregate 64 MiB cap. Upgrade headers are capped at 64 KiB. Reserved bits,
control-frame FIN/size rules, continuation order, and overlapping fragmented
messages are validated before accepting payloads.

**Locations:** `src/codex/ws.clj:222-310`

Originally `ws-max-message` was checked per frame, not against the accumulated fragmented
message. A sequence of individually valid fragments can therefore allocate
well beyond 64 MiB. `cat-bytes` also copies the entire accumulator for every
fragment, producing quadratic copy cost. Handshake header accumulation is
unbounded until `\r\n\r\n` appears.

Track aggregate length before allocation, accumulate fragments in a
`ByteArrayOutputStream`, and cap the HTTP upgrade header. Also validate RFC 6455
control-frame rules (FIN required, payload ≤125), continuation sequencing,
reserved bits, and 64-bit lengths whose high bit is set. Server frames should
normally be unmasked; decide whether to reject masked server frames rather than
silently accepting them.

The peer is a fixed TLS-authenticated upstream, so this is lower exploitability
than inbound parsing, but it materially improves resilience to upstream defects
and protocol drift.

## P2 — code quality, operations, and documentation

### 12. Stop printing secrets and raw identifiers

**Status: partially implemented.** Server startup now prints only whether API
key authentication is enabled, never the key itself. Session diagnostics and
the deliberately detailed `info!` output still need a redaction policy.

**Locations:** `src/codex/core.clj:63-66`, `src/codex/proxy.clj:194-231`,
`src/codex/cli.clj:320-380`

`start!` prints the complete proxy API key and internal session ID. The API key
may be captured by terminal scrollback, service logs, CI logs, or process
supervisors. Remove it entirely or print only that authentication is enabled.
Log a short hash of session IDs when correlation is needed.

`info!` intentionally prints email, subject, organization data, and arbitrary
JWT claims. Keep the command if useful, but document its sensitivity and offer
a default redacted view plus an explicit `--full` mode.

Use a tiny structured logger with levels and a redaction function instead of
scattered `println`. Never log access tokens, refresh tokens, authorization
headers, full prompts, full upstream bodies, API keys, device codes, or OAuth
codes.

### 13. Clarify listen-address behavior and local threat model

**Status: implemented in CLI validation.** Because the pinned adapter is
loopback-only, `serve` now rejects hosts other than `127.0.0.1` and `localhost`
instead of silently ignoring them. External exposure still requires a separate
TLS/authenticating reverse proxy and is not claimed as native support.

**Locations:** `src/codex/core.clj:92-101`, `README.md:18-42`

Previously `CHATGPT_ADAPTER_ADDR` was split into `host` and port, but `host` was unused.
`ring-chez-adapter` currently binds only `127.0.0.1`, and `start!` always reports
that address. Thus values such as `0.0.0.0:8080` or `[::1]:8080` are parsed
incorrectly or misleadingly. The TODO asks to mirror Go's non-loopback safety
check, but the current dependency cannot bind non-loopback through this API.

Until bind-host support exists, accept a port-only setting or reject any host
other than `127.0.0.1`/`localhost` with a clear message. Do not imply that
`CHATGPT_ADAPTER_ADDR` supports arbitrary addresses. If external binding is
later added, refuse non-loopback without an API key, as the Go reference does,
and document TLS/reverse-proxy requirements.

Even on loopback, explain the local trust model: with no API key, any local
process can spend the user's subscription and select session IDs. Consider
requiring a key by default, or at minimum rejecting unexpected `Origin` values
and documenting browser/DNS-rebinding considerations. `/health` may remain
unauthenticated but should reveal no account state.

### 14. Improve error taxonomy and preserve diagnostics safely

**Locations:** `src/codex/auth.clj:29-38`, `src/codex/proxy.clj`, catch blocks
throughout

Several catches intentionally discard all information; others return raw
exception messages to clients. Introduce typed `ex-info` categories such as
`:input`, `:auth`, `:upstream-http`, `:upstream-protocol`, `:timeout`, and
`:internal`. Map them centrally to OpenAI-shaped responses and suitable status
codes. Return a generic request ID to clients and log the detailed cause after
redaction.

Do not silently ignore resource-close and persistence failures. A close failure
may be debug-level, but a credential rename/delete failure or finalizer failure
needs a visible diagnostic. In particular, saving a refreshed token must be
part of the refresh transaction: define whether a valid in-memory token is
usable when persistence fails and communicate that state clearly.

### 15. Remove duplication and use idiomatic data transformations

**Status: in progress.** Cryptographic hex ID generation is centralized in
`codex.id/random-hex`, and continuation normalization now uses immutable
`dissoc` rather than an atom/doseq map rebuild. The collector decomposition
from item 9 remains the largest outstanding style improvement.

Examples:

- Random hexadecimal ID generation appears in both `codex.core` and
  `codex.proxy`; move it to one utility.
- `ws-beta-header` and `ws-url` are defined but the dial request repeats their
  literal values.
- `normalize-input-item` builds a map through an atom and `doseq`; `(dissoc item
  :status)` is clearer and purely functional.
- Nested `if` chains in `build-delta-request` are easier to audit as `cond` or
  explicit guard clauses.
- Collector event dispatch is a long `cond`; multimethods are unnecessary, but
  small per-event pure functions would improve testability.
- Avoid type-hint syntax that suggests JVM reflection concerns; Jolt's Java
  classes are shims, and comments should describe Jolt behavior directly.

Add a formatter/linter configuration that is known to work for Jolt-compatible
Clojure. Treat warnings as review prompts rather than blindly applying JVM-only
advice.

### 16. Reconcile documentation with the repository

**Status: implemented for current behavior.** README now documents tested
prerequisites, the deterministic test command, private Subscription API risk,
loopback/local trust assumptions, address constraints, and credential modes.
TODO phase/status contradictions and completed review tasks were reconciled;
generated development directories are now ignored.

**Locations:** `README.md`, `TODO.md`, `JOLT-ISSUES.md`

Documentation was unusually detailed, but parts were stale or contradictory:

- TODO's progress table says Phase 1 is done, while the Phase 1 heading says
  "IN PROGRESS".
- Phase 8 says the README update is pending although it has been updated.
- TODO describes a `test_e2e.clj` deliverable that is absent while marking all
  end-to-end items complete.
- README's issue summary omits the newly documented Base64 shim issue and still
  describes the tagged-table material as a Clojure-language divergence.
- The exact supported subset of OpenAI request/response fields is not listed.
- There is no installation section for obtaining a compatible Jolt version,
  resolving git dependencies, or verifying OpenSSL availability.
- There is no support policy for the private ChatGPT Subscription backend,
  whose endpoints and beta headers can change without notice.

Turn `TODO.md` into either a historical implementation plan or a current
backlog, not both. Add:

1. prerequisites and tested Jolt/OS/OpenSSL versions;
2. all environment variables, defaults, validation, and secret handling;
3. endpoint/field compatibility matrix and known deviations from OpenAI;
4. local-only security model and reverse-proxy guidance;
5. credential file permissions and logout behavior;
6. troubleshooting with sanitized diagnostics;
7. unit-test and opt-in live-test commands;
8. dependency update procedure for pinned git SHAs;
9. statement that the upstream Subscription API is unofficial/private and may
   be subject to service terms and breaking changes.

## Suggested implementation sequence

1. **Secret storage patch:** atomic `0600` credentials, explicit load errors,
   tests, and migration of existing permissions.
2. **WS ownership patch:** serialized pool transitions, idempotent release,
   write-failure cleanup, bounded pool, and race tests.
3. **Streaming correctness patch:** unified terminal result and regression tests
   for failures/disconnects.
4. **Test harness:** deterministic unit suite plus CI; convert existing manual
   checks into assertions.
5. **Boundary hardening:** session normalization, request schemas/limits, OAuth
   callback validation, and sanitized error mapping.
6. **Architecture cleanup:** one system owner, injected dependencies, split
   proxy transport/collectors/routes.
7. **Documentation pass:** accurately describe configuration, compatibility,
   tests, security assumptions, and private-upstream risk.

## Verification notes

- All seven `codex.*` namespaces loaded successfully with the downloaded Jolt
  v0.7.16 binary.
- The project has no LSP provider for `.clj` in this environment; review was
  performed from source and dependency code rather than JVM-Clojure type
  inference.
- The pinned `ring-chez-adapter` already caps inbound requests at 1 MiB by
  default, applies keep-alive and write timeouts, handles worker failures, and
  binds loopback. Those controls should be configured explicitly in
  `codex.core` so upgrades do not silently change the proxy's limits.
- No credentials or token contents were read during this review; only the
  credential file's metadata was inspected.
- Existing unrelated edits to `JOLT-ISSUES.md` and
  `CLOJURE-CONVERGENCE.md`, plus generated untracked tool directories, were
  left untouched.
