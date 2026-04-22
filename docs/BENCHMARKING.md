# Benchmarking

Peglib ships with a JMH harness under `src/jmh/java/` that measures
parse throughput and average time on a 1,900-LOC real-world Java 25
fixture. The harness is the source of the numbers in
[`PERF-FLAGS.md`](PERF-FLAGS.md) and [`CHANGELOG.md`](../CHANGELOG.md).

## Prerequisites

- JDK 25 (the project's target release)
- Maven 3.9+
- Clean build environment — close other JVMs and CPU-heavy workloads for
  stable numbers

## Running the harness

The `bench` Maven profile adds `src/jmh/java/` as a source root, pulls
in `jmh-core` + `jmh-generator-annprocess`, and builds an uber-jar.

```bash
# Build the benchmarks uber-jar
mvn -Pbench -DskipTests clean package

# Run all benchmarks
java -jar target/benchmarks.jar

# Run a specific class
java -jar target/benchmarks.jar org.pragmatica.peg.bench.Java25ParseBenchmark

# Run one variant only
java -jar target/benchmarks.jar \
    -p variant=phase1_allStructural \
    org.pragmatica.peg.bench.Java25ParseBenchmark
```

The skipTests flag is intentional — benchmark builds should not depend
on the test suite being green. CI pins test runs to ordinary `mvn test`.

## What the harness measures

`Java25ParseBenchmark` (`src/jmh/java/org/pragmatica/peg/bench/Java25ParseBenchmark.java`)
is parametrized on a single string `variant` and produces per-variant
numbers in both `AverageTime` (ms/op) and `Throughput` (ops/ms) modes.

JMH config baked into the harness:

| Setting | Value |
|---|---|
| Mode | `AverageTime` + `Throughput` |
| Output unit | milliseconds |
| Warmup | 3 iterations × 2 s |
| Measurement | 5 iterations × 2 s |
| Forks | 2 |
| State scope | Benchmark (one per-variant setup) |

Each `@Setup(Level.Trial)` compiles the generated parser class fresh for
its variant via `ToolProvider.getSystemJavaCompiler()` and loads it into
a dedicated `URLClassLoader`. Each `@Benchmark` invocation instantiates
a fresh parser (generated parsers are single-use) and calls
`parse(fixtureSource)`. The `interpreter` variant reuses one `PegEngine`
and calls `parseCst(fixtureSource)` per invocation.

## Available variants

Listed in the harness's `@Param` annotation:

| Variant | Configuration |
|---|---|
| `none` | All generator flags off — unflagged baseline |
| `phase1` | `ParserConfig.DEFAULT` (phase-1 on, `choiceDispatch` on, other phase-2 off) |
| `phase1_choiceDispatch` | phase-1 + `choiceDispatch` only |
| `phase1_markResetChildren` | phase-1 + `markResetChildren` only |
| `phase1_inlineLocations` | phase-1 + `inlineLocations` only |
| `phase1_allStructural` | phase-1 + all phase-2 (no `selectivePackrat`) |
| `phase1_allStructural_skipPackrat` | above + `selectivePackrat` with skip-set `{Identifier, QualifiedName, Type}` |
| `interpreter` | `PegEngine` path, `ParserConfig.DEFAULT` |

## Adding a new variant

Edit `Java25ParseBenchmark.configFor(...)` and add an entry to the
`@Param` list. For a simple new phase-2 combination, extend `withStructural`:

```java
@Param({
    ...existing values...,
    "phase1_myExperiment"
})
public String variant;

private static ParserConfig configFor(String variant) {
    return switch (variant) {
        ...existing cases...,
        case "phase1_myExperiment" -> withStructural(
            true,    // choiceDispatch
            true,    // markResetChildren
            false,   // inlineLocations
            false,   // selectivePackrat
            Set.of());
        default -> throw new IllegalArgumentException("Unknown variant: " + variant);
    };
}
```

Rebuild with `mvn -Pbench -DskipTests clean package`. Run by passing
`-p variant=phase1_myExperiment` to the uber-jar. No changes to `pom.xml`
required — JMH picks up new `@Param` values automatically.

## Interpreting results

Sample output block (Average time mode):

```
Benchmark                             (variant)  Mode  Cnt    Score    Error  Units
Java25ParseBenchmark.parse               phase1  avgt   10  250.185 ± 31.489  ms/op
Java25ParseBenchmark.parse  phase1_choiceDispatch  avgt   10  100.733 ± 32.583  ms/op
```

- **Score** is the mean of the 10 measurement iterations across the 2
  forks. Lower is better in `avgt` mode, higher is better in `thrpt` mode.
- **Error** is the 99.9% confidence interval — not the standard deviation.
  Two variants whose error bars overlap are **not distinguishable** at
  the benchmark's configured confidence level. (See `phase1_markResetChildren`
  vs `phase1`: the flag shows a 16% slowdown in the mean, but the error
  bars overlap — hence "no statistically significant individual win" in
  the flag description.)
- **Units** apply to the score only; `avgt` reports ms/op, `thrpt`
  reports ops/ms.

## Additional profilers

JMH ships with several profilers usable via `-prof`:

```bash
# GC allocation rate (useful for measuring allocation-elision flags)
java -jar target/benchmarks.jar -prof gc

# JFR recording (detailed flame graph source)
java -jar target/benchmarks.jar -prof jfr

# Perfasm (Linux only; requires perf)
java -jar target/benchmarks.jar -prof perfasm
```

For the allocation-heavy optimizations in phase-1 (`literalFailureCache`,
`charClassFailureCache`, `reuseEndLocation`), `-prof gc` shows the
allocation rate drop directly:

```
·gc.alloc.rate.norm    MB/sec   (ratio)
phase1                 1480.3   1.00
phase1_choiceDispatch   680.9   0.46
```

## Committing results

When a release touches the hot path, commit the raw JMH output under
`docs/bench-results/<benchmark-class-slug>.<log|json>`. Existing files:

- `docs/bench-results/java25-parse.json` — JMH `-rf json -rff ...` output
  for the 0.2.2 generator flag matrix
- `docs/bench-results/java25-parse.log` — the console log
- `docs/bench-results/java25-parse-interpreter.json` — 0.2.3 interpreter
  phase-1 port numbers
- `docs/bench-results/packrat-stats.txt` — `PackratStatsProbe` output

The `CHANGELOG.md` Performance section then summarises the numbers; the
raw files serve as machine-readable backup.

## Related

- [`PERF-FLAGS.md`](PERF-FLAGS.md) — what each `ParserConfig` flag does
- [`PERF-REWORK-SPEC.md`](PERF-REWORK-SPEC.md) — design rationale and
  phase-by-phase specification
- [`CHANGELOG.md`](../CHANGELOG.md) — per-release measured numbers
