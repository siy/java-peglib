# Benchmarking

JMH harnesses for the parse path, plus the measurement discipline that keeps their numbers
believable. Everything here targets the 0.6.0+ tokens-first engine; the 0.5.x interpreter and its
A/B suites were deleted in 0.7.0.

## The benchmarks

All live in `peglib-core/src/jmh/java/org/pragmatica/peg/perf/`.

| Class | Mode | Measures |
|---|---|---|
| `Java25LargeFixturesBenchmark` | AverageTime | Warm parse of two large fixtures. **The headline throughput number.** |
| `Java25ParseBenchmark` | AverageTime | Warm parse across the `format-examples` corpus |
| `JavacParseOnlyBenchmark` | AverageTime | The same fixtures through `JavacTask.parse()` — the comparison baseline |
| `IncrementalEditBenchmark` | SampleTime | Edit latency (p50/p99) via `IncrementalParser` |
| `Java25ColdCompileBenchmark` | SingleShotTime | `PegParser.fromGrammar` cost: classify → DFA → generate → compile |

`Java25LargeFixturesBenchmark`, `JavacParseOnlyBenchmark` and `IncrementalEditBenchmark` take a
`fixture` parameter with two values:

- `reference` — `perf-corpus/large/FactoryClassGenerator.java.txt`, ~1.9k LOC of real-world Java
- `selfhost` — `bench-fixtures/Java25SelfHost-v51.java.txt`, ~40k LOC

## Running

```bash
mvn -pl peglib-core -am -Pbench -DskipTests package
cd peglib-core && java -jar target/benchmarks.jar Java25LargeFixturesBenchmark -wi 5 -i 10 -f 2
```

**Run from `peglib-core/`.** The harnesses resolve fixtures relative to the working directory, so
from the repo root every iteration fails with `NoSuchFileException`.

Benchmark selection is a **regex**, and this project's shell is zsh, which does *not* word-split
unquoted parameter expansions. This silently measures nothing:

```bash
ARGS="Java25LargeFixturesBenchmark -wi 5 -i 10 -f 2"
java -jar target/benchmarks.jar $ARGS     # ONE argument -> "No matching benchmarks", exits in <1s
```

Pass the arguments literally, or use `${=ARGS}`.

## Profiling

Async-profiler at `/opt/homebrew/lib/libasyncProfiler.dylib`:

```bash
java -jar target/benchmarks.jar Java25LargeFixturesBenchmark -f 1 -wi 3 -i 5 \
  -prof "async:libPath=/opt/homebrew/lib/libasyncProfiler.dylib;event=cpu;output=collapsed;dir=/tmp/profile"
```

Always pass `dir=` — without it JMH drops a `<benchmark>-<mode>-variant-<param>/` directory per
combination at the repo root. `.gitignore` covers the pattern, but they accumulate.

## Measurement discipline

These rules were learned by getting them wrong, each one on this codebase.

**Compare against a baseline you built the same day, on the same machine.** Cross-day numbers are
not comparable. Clone rather than stash — a stash across parallel work has lost changes here:

```bash
git clone . /tmp/peglib-base -b main
```

**Interleave variants (A, B, A, B) instead of running each to completion.** Machine drift over a
ten-minute window is routinely larger than the effect being measured.

**Check the error bars before believing a delta.** A run with ±14% error cannot resolve an 8%
change. During the 0.7.1 measurement a background Spotlight index (`mds` at 44% CPU, load 13)
produced ±43% error bars and a result that flatly contradicted a clean run twenty minutes
earlier. `uptime` and `ps` before trusting a surprising number — and note that cloning a repo
into a temp directory is itself enough to trigger indexing.

**Profile before optimising.** Repeatedly on this project the profiler has contradicted a
confident hypothesis. The top CST-node builder turned out to be `parseAnnotation`, not the
statement rule everyone suspected; `truncate`'s cost was the backward walk, not the link repair
that looked expensive.

**Re-run the benchmark after any hot-path change** — anything touching `CstArrayBuilder`,
`TokenArray.spliceLex`, `LexerEngine.lex`, or generated parser emit.

## Where the numbers live

Per-release figures belong in the CHANGELOG entry for that release, next to the change that moved
them. `docs/HANDOVER.md` carries the current session's measurements and the reasoning behind
optimisations that were tried, kept, or rejected — including the ones that measured as *no
improvement*, which are worth recording so they are not retried.

This document deliberately contains no numbers: a benchmark result is a measurement on one
machine on one day, and quoting one here guarantees it goes stale. Re-measure.
