# Memory benchmark and optimization findings 26-08-20

**Benchmark below led to finding Jolt compiler optimization bug that has been solved, Ruuter adds more towards 2MiB to idle RSS with optimizations on after the fix https://github.com/jolt-lang/jolt/pull/685**

## Scope

This is an idle-memory investigation of the standalone Jolt build. Initial
measurements were taken before the routing optimization; post-optimization
measurements are included below. Measurements were taken on this Linux x86-64
environment using the optimized, direct-linked build:

```sh
jolt build -m llm-proxy.core \
  -o /tmp/jolt-llm-proxy-measure \
  --opt --direct-link
```

The process was started, allowed to reach `:proxy-started`, left idle for two
seconds, and measured through `/proc/<pid>/status` and
`/proc/<pid>/smaps_rollup`. RSS is resident memory; PSS is included to make the
shared/private distinction explicit. The target for this investigation is
idle RSS below 100 MiB.

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
75 MiB below the full proxy closure. This makes a direct route dispatcher the
highest-confidence optimization.

The full production process adds approximately 10 MiB over the proxy closure
for lifecycle, CLI, and nREPL/core startup code. Therefore, replacing Ruuter
projects the complete server into roughly the 60–70 MiB RSS range. This is an
estimate until the real server is rebuilt with the replacement dispatcher, but
it has a substantial margin below 100 MiB.

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

## Post-optimization result

Ruuter was removed from `deps.edn` and replaced with a direct exact
method/path dispatcher in `llm-proxy.proxy`. The optimized rebuilt server was
measured with the same procedure:

| Configuration     |          RSS |      PSS | Threads |
|-------------------|-------------:|---------:|--------:|
| HTTP only         | **58.8 MiB** | 53.0 MiB |      11 |
| Dashboard + nREPL | **59.1 MiB** | 53.3 MiB |      12 |

This is approximately a 56 MiB RSS reduction, or 42%, from the 134.7 MiB
HTTP-only baseline, and meets the idle RSS target with substantial margin.
The optimized executable produced by this rebuild was approximately 18 MiB.

The route and full test suite passed after the replacement.

## Recommended follow-up work

### 1. Preserve the memory regression benchmark

Keep the same idle RSS/PSS measurement in release validation. The acceptance
criterion is idle HTTP-only RSS below 100 MiB, with dashboard and nREPL
measured separately.

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
memory. The dependency closure, especially Ruuter before its removal, was the
dominant factor. Chez heap-reservation tuning and worker-thread changes were
not needed to meet the target.
