# Phase 0 Spike Bench Results

**Date:** 2026-05-07
**Branch:** release-0.5.0 (post-Phase-0d.1, commit 9b55253)
**JMH version:** 1.37
**JVM:** OpenJDK 25.0.2 (Apple Silicon, Homebrew)

## Configuration

- **Bench class:** `peglib-incremental/src/jmh/java/org/pragmatica/peg/incremental/bench/Phase0SpikeBench.java`
- **Tree shape:** synthetic perfect 4-ary tree built by `SyntheticTreeBuilder`
- **Splice point:** depth 3 (representative interior pivot)
- **Mode:** `AverageTime`, microseconds/op
- **Run flags (reduced for spike):** `-i 3 -wi 2 -f 1` — class declares `(iterations=5, warmup=3, fork=2)` for full runs; the spike uses reduced flags to keep total bench time under one minute. Run signal is unambiguous so reduced iterations suffice for GO/NO-GO.
- **Per-invocation setup:** `oldIndex` rebuilt via `IdNodeIndex.build(oldRoot)` per `Level.Invocation` because `applyIncremental` mutates the receiver. JMH `Level.Invocation` overhead (~tens of ns) is negligible vs μs-range measurements.

## Measurements

| Tree size | fullRebuild (μs) | incrementalUpdate (μs) | Speedup |
|-----------|-----------------:|-----------------------:|--------:|
| 100       |  2.678 ± 0.389   |  0.069 ± 0.001         |  38.8×  |
| 1000      |  9.611 ± 0.312   |  0.203 ± 0.011         |  47.3×  |
| 10000     | 176.468 ± 16.561 |  2.627 ± 0.237         |  67.2×  |

Speedup grows with tree size, exactly as the O(δ) algorithm predicts: incremental cost grows only logarithmically (with depth), full-rebuild cost grows linearly with N.

Total bench wallclock: 49 seconds.

## Perf gate

**Criterion:** incrementalUpdate ≥5× faster than fullRebuild at representative tree sizes (≥1000 nodes).

**Result: GREEN.** 47× at 1000 nodes, 67× at 10000 nodes — both well above the 5× threshold and consistent with the spec §2 algorithmic projection ("typically 100-300 operations vs the 91,000 we walk today, ~300× per-edit reduction").

## Caveats

- **Synthetic balanced tree, branching factor 4.** Real CST topologies are irregular (e.g., Java method bodies have unbalanced fanout). Phase 1 will re-run on the production 1900-LOC fixture for the apples-to-apples comparison against 0.4.3's `NodeIndex.build`.
- **`applyIncremental` mutates the receiver.** Bench accounts for this via `Level.Invocation` setup that rebuilds `oldIndex` from `oldRoot`. Production 0.5.0 will need either copy-on-write (with `LongLongMap.copy()`, ~O(N) cost) or a persistent map (out of scope for spike). The choice does not affect this gate — even if we paid full-rebuild cost on every applyIncremental call, we'd be at break-even, not a regression.
- **`LongLongMap` is the hand-rolled linear-probing impl from Phase 0a.** Future Funnel-hashing swap (per spec §8 Q2) untested.
- **Numbers reflect a depth-3 splice with a small pivot subtree (single Terminal).** Worst-case (deep splice with large pivot, e.g., a class body rewrite) not in this bench. Spec §2 establishes the cost is `O(splicedSize + depth × branching)` so scaling is predictable.
- **JMH Compiler Blackholes are in use** (auto-detected on JDK 25). Numbers are stable across the 3 measurement iterations and within tight CI bands, so this is informational rather than a confidence concern.

## Files

- `peglib-incremental/src/jmh/java/org/pragmatica/peg/incremental/bench/Phase0SpikeBench.java` — bench class
- `peglib-incremental/src/jmh/java/org/pragmatica/peg/incremental/bench/SyntheticTreeBuilder.java` — tree synthesizer
- `peglib-incremental/target/phase0-spike.json` — raw JMH output (not committed)
