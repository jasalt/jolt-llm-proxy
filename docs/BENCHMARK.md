# Startup and memory benchmark findings 26-09-13

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
