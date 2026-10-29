# Build size and runtime memory — Jolt v0.8.21 (2026-10-11)

**Follow-up diagnosis:** [MEMORY-REGRESSION.md](MEMORY-REGRESSION.md) isolates
most of the increase to automatically embedded dependency resources and their
static vfasl representation. Excluding unused JS externs reduces optimized
HTTP-only idle RSS from 232.4 to 164.6 MiB; combining that with plain boot gives
125.6 MiB, but increases startup peak memory. The historical ~60 MiB artifact
has a different namespace closure (no Ebb/Glimmer); a same-current-source
v0.8.7 rebuild uses 123.1 MiB. Production build settings remain unchanged.

Upgraded the Nix input from v0.8.7 to the latest tagged release, **v0.8.21**,
commit `5b46858a171f30949f5b09eb484bc6c5ff93c6ea`. Nix reports the version as
`jolt 5b46858` because upstream uses the flake's short revision as its version.
The nixpkgs and application dependency pins were left unchanged.

## Controlled build comparison

All executable builds are AOT. Here **basic** means the default release build
(no flags), **AOT** means the project's optimized deployment build, and
**tree-shaken** means that same deployment build with shaking requested.
The last name is not a claim that pruning succeeded.

| Build/run | Executable MiB | Idle RSS MiB | Idle PSS MiB | Peak RSS MiB | HTTP ready, ms |
|-----------|---------------:|-------------:|-------------:|-------------:|---------------:|
| Basic: default `jolt build` | 55.99 | 283.7 | 279.3 | 337.7 | 1,035 |
| AOT: `--opt --direct-link` | **28.63** | **232.4** | **228.0** | **256.5** | 718 |
| Tree-shaking requested: `--opt --direct-link --tree-shake` | 27.82 | 253.5 | 249.1 | 280.4 | 704 |
| Development: `--dev` | 45.51 | 254.9 | 250.5 | 286.8 | 876 |
| Source run: `jolt -m llm-proxy.core` (warm namespace cache) | n/a | 178.5 | 165.2 | 178.5 | 1,030 |

**Recommendation:** keep `--opt --direct-link`. It cuts executable size by
48.9% and idle RSS by 18.1% versus the default build. The tree-shaking request
produces a 2.8% smaller executable but uses 9.1% more idle RSS than optimized
AOT in this experiment. These are measurements of this build/host, not a
universal claim about tree shaking.

**Actual tree-shake result: skipped.** The compiler names
`jolt.nrepl/evaluate -> clojure.core/load-string`, plus nREPL's runtime var and
namespace lookups. It also suggests an `:allow-dynamic` list naming
`borkdude.dynaload/resolve*`. We did not apply that list: nREPL really executes
dynamic code, and permitting lookups cannot make the evaluator safe over a
pruned core. Even after bailing out, the requested-shake build emits a single
application compile unit rather than the per-namespace units in the normal
build (observed in the build directories). Therefore its size/memory difference
is **not evidence of successful dead-code elimination**.

## Method and reproducibility

- Fedora 44 x86_64 Lima VM, Linux 6.19.10, glibc 2.43, eight vCPUs on
  AMD Ryzen 7 PRO 7840U. Built using the project's pinned Nix development shell.
- Five sequential fresh processes per mode; table entries are medians. Each
  process is measured two seconds after a successful loopback `/health` response.
  Readiness is process spawn to that response, not merely socket creation.
- RSS and kernel peak RSS (`VmHWM`, since process start) come from
  `/proc/<pid>/status`; PSS/private dirty from `smaps_rollup`. Memory units are
  MiB (1,048,576 bytes); raw proc measurements are KiB. All modes had 19 threads.
- HTTP-only, eight request workers, no dashboard/nREPL enabled for memory runs,
  no upstream requests, disposable fake credentials, an API-key guard, no manual
  GC, and the default boot encoding. Only `/health` precedes the memory sample;
  `/v1/models` is smoke-tested afterwards.
- Standalone binaries also passed `--help` and embedded `licenses` from a
  temporary working directory without project source/resources. All four builds
  separately passed dashboard sampling and nREPL (including the `-np` alias).
- The source-run row is a reference, not a self-contained artifact: it requires
  Jolt, project source/resources and dependency caches. Its launcher size would
  not be comparable to an executable containing the application.
- [Raw samples and binary hashes](benchmark-0821.json) preserve exact sizes,
  build flags, runtime package path, and all five samples per mode. Build logs,
  binaries, and fresh results are in ignored `target/benchmark/`.

Requires Linux `/proc`, Python 3, and the project's Nix shell:

```sh
nix develop --command python3 tools/benchmark-builds.py --include-source
# Measure existing artifacts without rebuilding:
nix develop --command python3 tools/benchmark-builds.py --skip-build --include-source
```

The script owns and terminates only its own processes, uses temporary fake
credentials, and never contacts the upstream model service. Artifacts are
built with the equivalent of:

```sh
jolt build -m llm-proxy.core -o target/benchmark/basic
jolt build -m llm-proxy.core -o target/benchmark/aot --opt --direct-link
jolt build -m llm-proxy.core -o target/benchmark/tree-shaken --opt --direct-link --tree-shake
jolt build -m llm-proxy.core -o target/benchmark/dev --dev
```

## Upgrade compatibility and limitations

The suite passes: **76 tests, 289 assertions, zero failures/errors**. The
upgrade required moving a test's namespace alias into its `ns` require,
preserving the old multi-character `-np` alias before bundled CLI parsing,
and adapting pinned Ebb's epoch-based timer binding to monotonic deadlines
(see [JI-5](JOLT-ISSUES.md#ji-5-pinned-ebb-sleep-never-completes-on-jolt-v0821)).
`nix build .#default` and the resulting launcher's `--help` also pass. The Nix
package remains a source launcher, not one of the standalone artifacts.

**The current builds do not meet the previous <100 MiB idle RSS target.** They
also have larger executables and higher measured RSS than the historical
v0.8.7 findings below. Those older results are not a controlled same-source,
same-toolchain comparison and should not be used to attribute the regression
solely to the Jolt version. The upstream Nix flake uses nixpkgs' `pkgs.chez`
directly, whereas the v0.8.21 release notes describe gains requiring Jolt's
patched Chez kernel. This experiment measures the Nix package actually used
by this project, not upstream's prebuilt release binaries; the packaging
difference is a follow-up to investigate, not a proven explanation of all the
memory increase. In particular, source-run retained memory is lower here than
standalone retained memory. No forced-GC or loaded-traffic measurements were
made, and startup timing is subject to VM scheduling/cache noise.

Runtime hosts still need zlib and OpenSSL: the compiler reports `z`, `crypto`,
and `ssl` as dynamically loaded native libraries. “Standalone” means no Jolt
or Chez installation or Clojure source is needed, not static linkage of every
system library.

---

# Historical startup and memory benchmark findings 26-09-13

**Updated for Jolt v0.8.7.** The large reduction from the original baseline is
primarily attributable to the Jolt compiler/runtime upgrade from v0.7.20 to
v0.8.7. Removing Ruuter and using the direct dispatcher also contributes, but
the measurements do not isolate those effects from the runtime upgrade. The
Jolt optimization issue noted in the original investigation was fixed upstream:
<https://github.com/jolt-lang/jolt/pull/685>

## Scope

This is a startup and idle-memory investigation of the standalone Jolt build.
The current measurements use the optimized, direct-linked build produced with
Jolt v0.8.7:

```sh
jolt build -m llm-proxy.core \
  -o target/jolt-llm-proxy \
  --opt --direct-link
```

The process was started with a valid local credential file, allowed to reach
its listening socket, left idle for approximately one second, and measured
through `/proc/<pid>/status` and `/proc/<pid>/smaps_rollup`. Startup time is
process start to listening-socket readiness. RSS is resident memory; PSS is
included to make the shared/private distinction explicit. The target for this
investigation is idle HTTP-only RSS below 100 MiB.

## Baseline measurements

| Configuration     |       RSS |       PSS | Private dirty | Threads |
|-------------------|----------:|----------:|--------------:|--------:|
| HTTP only         | 134.7 MiB | 128.8 MiB |     128.6 MiB |      11 |
| Dashboard enabled | 134.4 MiB | 128.3 MiB |     127.8 MiB |      11 |
| nREPL enabled     | 133.3 MiB | 127.2 MiB |     126.7 MiB |      12 |
| Dashboard + nREPL | 134.4 MiB | 128.4 MiB |     128.0 MiB |      12 |

The baseline process also reported approximately 1.1 GiB of virtual address
space. This is not resident memory and should not be confused with the RSS
target.

The executable is approximately 22 MiB. A timed build consumed a maximum of
2,274,992 KiB (approximately 2.17 GiB) resident memory, took 3:07.60, and
completed successfully. Build-time memory is therefore a separate CI/build
machine concern from deployed idle memory.

## Startup profile

`JOLT_STARTUP_PROFILE=1` showed the final startup heap at approximately 140 MB
(133 MiB). The largest startup allocation jump occurred while loading
`ruuter.core`:

- heap before `ruuter.core`: approximately 102 MiB;
- heap after `ruuter.core`: approximately 210 MiB;
- final heap after startup collection: approximately 133 MiB.

The large intermediate value is not all retained, but it identifies Ruuter as
the most important dependency to isolate.

## Dependency probes

Temporary probe entry points were built outside the repository's final source
state. Each probe only loaded the named dependency closure and then remained
idle.

| Probe                                       |           RSS |
|---------------------------------------------|--------------:|
| Empty standalone Jolt binary                |      35.1 MiB |
| `malli.core`                                |      41.1 MiB |
| `ruuter.core`                               | **108.9 MiB** |
| Proxy-related dependencies excluding Ruuter |  **49.8 MiB** |
| Full proxy dependency closure               |     124.6 MiB |

The Ruuter-only probe is approximately 73.8 MiB above the empty binary. The
probe containing the proxy dependency closure but no Ruuter is approximately
75 MiB below the full proxy closure. These historical probes identified the
direct route dispatcher as a worthwhile application optimization, but they do
not account for the later Jolt v0.8.7 runtime/compiler change.

The full production process adds approximately 10 MiB over the proxy closure
for lifecycle, CLI, and nREPL/core startup code. The current measurements show
that the upgraded runtime and the dispatcher together keep the complete server
in the roughly 60–70 MiB RSS range, with substantial margin below 100 MiB.

## Build optimization findings

The build was attempted with tree shaking:

```sh
jolt build -m llm-proxy.core \
  -o /tmp/jolt-llm-proxy-tree \
  --opt --direct-link --tree-shake
```

Jolt reported that tree shaking was skipped because reachable code resolves
vars at runtime, including:

- `jolt.nrepl` using `resolve`, `load-string`, and namespace discovery;
- `malli.sci` using `load-string`.

Consequently, adding `--tree-shake` currently does not reduce this binary.

## Research

Jolt's build documentation describes `--opt`, `--direct-link`, and
`--tree-shake`, and notes that tree shaking bails out around dynamic
`eval`/resolution:

- <https://jolt-lang.github.io/docs/building-and-deps.html>
- <https://github.com/jolt-lang/jolt#compile-a-binary>

Chez Scheme's storage-management documentation describes `heap-reserve-ratio`
and `release-minimum-generation`. These affect reserved/released virtual heap
space and collection behavior; they do not remove live application code and
data retained by the process:

- <https://cisco.github.io/ChezScheme/csug10.0/smgmt.html>

The measured RSS is mostly private resident memory, so heap-reservation tuning
is not the first-order solution for this target.

## Current Jolt v0.8.7 result

The current binary is approximately 19.6 MiB and reaches HTTP readiness in
approximately 0.3–0.5 seconds. Repeated startup measurements had a median of
about 0.36 seconds for HTTP-only mode. The process briefly reached
approximately 177 MiB RSS during startup before settling near 60 MiB; startup
peak and idle RSS should therefore be treated as separate deployment metrics.

| Configuration     | Startup to listening socket | RSS after ~1 s |      PSS | Threads |
|-------------------|----------------------------:|---------------:|---------:|--------:|
| HTTP only         |              ~357 ms median |       59.6 MiB | 54.7 MiB |      11 |
| Dashboard enabled |                     ~598 ms |       59.3 MiB | 54.3 MiB |      11 |
| nREPL enabled     |                     ~629 ms |       60.7 MiB | 55.9 MiB |      12 |
| Dashboard + nREPL |                     ~600 ms |       60.0 MiB | 55.1 MiB |      12 |

The measured HTTP-only RSS is approximately 75 MiB below the original
v0.7.20-era baseline of 134.7 MiB, a reduction of roughly 56%. Since the
application changed at the same time, this is not a controlled attribution;
the evidence indicates that the Jolt v0.8.7 upgrade accounts for most of the
improvement, with Ruuter's removal providing an additional reduction.

The current result meets the idle RSS target with approximately 40 MiB of
margin. Dashboard and nREPL add little retained memory, although nREPL adds
one thread. Measurements were taken without making upstream network requests.

## Attribution and limitations

The historical baseline was measured with Jolt v0.7.20, while the current
binary uses Jolt v0.8.7. The direct dispatcher and dependency changes were
also made between those measurements, so the benchmark cannot mathematically
separate compiler/runtime improvements from application changes. Do not treat
the 56% reduction as a Ruuter-only result.

The executable is a platform-specific artifact; record the Jolt version, build
flags, target platform, and binary hash with release measurements. The
optimized build is not the same as a development build, and tree shaking still
bails out when dynamic resolution is reachable.

The route and full test suite passed after the dispatcher replacement.

## Recommended follow-up work

### 1. Preserve the startup and memory regression benchmark

Keep the same startup and idle RSS/PSS measurement in release validation. The
acceptance criteria are:

- idle HTTP-only RSS below 100 MiB;
- startup to HTTP readiness below one second on the benchmark host;
- dashboard and nREPL measured separately;
- startup peak RSS reported separately from settled idle RSS.

### 2. Reduce optional build closure if additional margin is required

Potential follow-ups are:

- replace Malli with focused validation if its retained closure remains
  material;
- separate interactive CLI/nREPL functionality from a minimal server binary;
- separate the dashboard from the minimal API binary if deployment permits;
- revisit tree-shaking blockers after removing dynamic nREPL/SCI paths.

The nREPL and dashboard flags themselves showed negligible idle RSS
difference in the current build because their namespaces are linked into the common
application closure regardless of whether the feature is enabled.

### 3. Defer low-value tuning

Reducing worker threads and changing Chez heap reservation parameters should
not be attempted as the first fix. The process used 11 threads, but the
standalone probe used one thread and still retained the dependency-driven
memory. The Jolt v0.8.7 compiler/runtime improvement is the primary explanation
for the current reduction; dependency and dispatcher changes are secondary
contributors. Further tuning was not needed to meet the target.
