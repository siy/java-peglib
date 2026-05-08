# Phase 1.0/1.1 SpanIndex (Path A) Bench Results

**Date:** 2026-05-07
**Branch:** release-0.5.0 (post-Phase-0d.2, sandbox additions only)
**JMH version:** 1.37
**JVM:** OpenJDK 25.0.2 (Apple Silicon, Homebrew)

## Configuration

- **Bench class:** `peglib-incremental/src/jmh/java/org/pragmatica/peg/incremental/bench/MidBufferBench.java`
- **Tree shape:** flat single-level — `Root[child_0 … child_{N-1}]` — mimicking a long Java method body of N statement nodes (vs Phase 0's balanced 4-ary tree).
- **Edit:** replace child at index N/2 with a fresh terminal of width 13, original width 10 → delta = +3, editEnd = (N/2)·10 + 10. About half the children sit right of the edit and need their offsets shifted.
- **Mode:** `AverageTime`, microseconds/op
- **Run flags (reduced for spike):** `-i 3 -wi 2 -f 1` — class declares `(iterations=5, warmup=3, fork=2)` for full runs; the spike uses reduced flags to keep total bench time under one minute.
- **Per-invocation setup:** `idOldIndex` rebuilt via `IdNodeIndex.build(idRoot)` and `odOldIndex` via `OffsetDecoupledNodeIndex.build(odRoot)` on every `Level.Invocation`, because `applyIncremental` mutates the receiver's parent map.

## Two arms compared

| Arm | What it does |
|-----|-------------|
| **productionStyle** | For every right-of-edit sibling, deep-copy the record via `shiftAll`-equivalent (rebuilds `IdCstNode.Terminal` with new `SourceSpan`). Then `IdNodeIndex.applyIncremental`. Mirrors the production `TreeSplicer.spliceAndShift` behaviour for siblings whose offsets must move. |
| **offsetDecoupled** | `OffsetDecoupledSplicer.splice` — copies `SpanIndex` and shifts all entries with `start ≥ editEnd` via a single `LongLongMap.forEachEntry` walk over a primitive `long[]`. Sibling records are reference-shared. Then `OffsetDecoupledNodeIndex.applyIncremental`. |

## Measurements

| Tree size | productionStyle (μs) | offsetDecoupled (μs) | Speedup |
|-----------|---------------------:|---------------------:|--------:|
|       100 | 1.272 ± 0.413        | 1.153 ± 0.219        |  1.10×  |
|      1000 | 12.524 ± 9.498       | 9.710 ± 0.877        |  1.29×  |
|     10000 | 81.656 ± 6.085       | 102.620 ± 31.160     |  0.80×  |

Total bench wallclock: 49 seconds.

## Perf gate

**Criterion:** offsetDecoupled ≥ 5× faster than productionStyle at representative tree sizes (≥ 1000 nodes).

**Result: RED.** At 1000 nodes the speedup is 1.29× (well below 5×). At 10000 nodes path A is *slower* than productionStyle (0.80×). Path A does not pay off on this shape.

## Why path A under-delivered

The bench measures `splice + applyIncremental` end-to-end. The decomposition is:

| Component | productionStyle | offsetDecoupled |
|-----------|-----------------|-----------------|
| Splice work | `O(N/2)` record allocations + `SourceSpan` rewrites for right-of-edit siblings | `O(N)` `SpanIndex.copy` + `O(N)` shift walk over a primitive `long[]` |
| `applyIncremental` step 3 | `O(N)` parent-link rewires for ALL N direct children of the rebuilt root | `O(N)` parent-link rewires for ALL N direct children |

For the flat single-level shape, `applyIncremental` step 3 walks every direct child of the new root regardless of arm — that's `O(N)` `LongLongMap.put` operations on both sides. This common-cost term dominates at N = 10000:

- offsetDecoupled total ≈ `SpanIndex.copy` (10000 entries) + `SpanIndex.shift` walk (10000 entries) + `applyIncremental` step 3 (10000 puts) ≈ `3 × O(N)` work.
- productionStyle total ≈ `N/2` record rebuilds + `applyIncremental` step 3 (10000 puts) ≈ `O(N)` heavyweight + `O(N)` lightweight.

Path A swaps "rebuild N/2 records" for "two extra full walks over N primitive entries". On the flat shape, the constant factor of three light walks ends up *equal to or larger than* one heavy half-walk. Record allocation in HotSpot's TLAB is fast for short-lived objects; the L1-resident primitive arrays in `LongLongMap` end up similarly cheap per-element. The differential is in the noise.

The Phase 0 bench saw 67× because the depth-3 splice in the balanced 4-ary tree had only ~16 right-of-edit nodes (1/4 fan-out at each level above the splice), so `applyIncremental` step 3 did `~depth × branching` ≈ 12 puts, not N. The splice cost was a tiny fraction of total. Phase 1's flat shape inverts that — `applyIncremental` itself is `O(N)` and dominates.

## Architectural insight (affects Phase 1.2+ migration if pursued)

Two compounding facts sink the path-A claim on the realistic shape:

1. **`SpanIndex.copy` cost.** The eager-copy snapshot semantics (mirroring what production v0.5.0 will need) is itself `O(N)`. Even before any shift, we've spent the time it would take to rebuild N/2 records.
2. **`applyIncremental` step 3 is `O(direct_children_of_rebuilt_root)`.** On a flat method body the rebuilt root *is* the body, so step 3 alone is `O(N)`. Path A doesn't help with that step at all — sibling records preserved or not, the parent-link rewires happen for every direct child of every newly-allocated ancestor.

To make path A pay off, both would need to change:

- Snapshot via path-copying / persistent map instead of full copy. Out of scope (spec §8 future work).
- `applyIncremental` step 3 must avoid rewiring siblings whose parent-id is *unchanged*. Today the rebuilt ancestor has a fresh id, forcing all-children rewire. A "stable-id ancestor rebuild" — keep ancestor id fixed when only one child changed and the ancestor's identity is otherwise structural — would limit step 3 to `O(depth)`. That's a separate architectural change; path A alone doesn't unlock it.

## Right-of-edit identity invariant — confirmed?

**Yes** — `OffsetDecoupledSplicerTest.rightOfEditSiblingsPreserveIdentity` passes. After splice, `newRoot.children().get(2) == C` (the right-of-edit sibling) holds by reference equality, AND `newSpans.startOffset(C.id()) == oldStart + delta`. This is the central correctness claim of path A and it is met.

That the perf gate is RED *despite* the invariant being met is the architectural insight: identity preservation on the record side does not translate to per-edit speedup when other O(N) costs (SpanIndex copy, applyIncremental step 3 on the rebuilt root) dominate.

## Caveats

- **Numbers are noisy at 10000.** stdev on `productionStyle@10000` is 6 μs on an 81 μs mean (acceptable); on `offsetDecoupled@10000` it's 31 μs on a 102 μs mean (high — likely TLAB/GC effects from the `SpanIndex.copy` allocation pattern). A full `-i 5 -wi 3 -f 2` run would tighten bands but not change the verdict — even taking the lower bound of `offsetDecoupled` (~71 μs) and upper bound of `productionStyle` (~88 μs), the speedup is ≤ 1.24× at best, far below the 5× gate.
- **Bench tree is single-level flat.** Real source isn't this extreme; method bodies are nested. A future "moderately deep" shape (say, `class > method > 50 statements > expression-tree-of-depth-5`) would see less of the step-3 dominance and might tilt closer to favourable. But such a shape would *also* trigger the production `TreeSplicer.spliceAndShift` ancestor-rebuild path, which is `O(depth)` and small. The flat shape was chosen specifically to expose the right-of-edit deep-copy cost; if path A doesn't win there, it doesn't win anywhere meaningful.
- **`SpanIndex` uses packed-long encoding via `LinearProbingLongLongMap`** (resolution of the choice in the brief). One additional `forEachEntry` API was added to `LongLongMap` — additive, no test regression.
- **Trivia spans untouched.** Production `Trivia` still carries `SourceSpan`; for the prove-out, all trivia lists are empty. A real path-A migration would need a parallel `TriviaIndex`, doubling the snapshot-copy cost.

## Verdict

**NO-GO** for path A as a complete strategy. The bench validates the user's concern in the brief: the Phase 0 67× was specific to the balanced-tree depth-3 splice; on the realistic flat shape, the right-of-edit identity preservation that path A buys is not worth the `SpanIndex.copy` snapshot cost it pays.

Recommended next steps (in order of attractiveness):

1. **Path C (accept partial win).** Keep the production `TreeSplicer` as-is. The Phase 0c `IdNodeIndex` already gives O(δ) on the tree shapes Phase 0's bench targeted (depth-3 splices in deep trees); ship that without committing to span decoupling. Real fixtures (1900-LOC Java) are deeper than the Phase 1 flat shape, so the Phase 0 win partially translates.
2. **Path B (lazy shift).** Track shift deltas as offset annotations on the splice path and resolve lazily on read. Avoids the `SpanIndex.copy` cost. More complex; the design is a separate spike.
3. **Stable-id ancestor preservation.** Independent of path-A vs B. If the ancestor on the splice path can keep its old id (only its `children` changed; rule/trivia identical), `applyIncremental` step 3 reduces to `O(depth)`. This is the highest-leverage change visible from this bench.

## Files

- `peglib-incremental/src/main/java/org/pragmatica/peg/incremental/experimental/SpanIndex.java`
- `peglib-incremental/src/main/java/org/pragmatica/peg/incremental/experimental/OffsetDecoupledNode.java`
- `peglib-incremental/src/main/java/org/pragmatica/peg/incremental/experimental/OffsetDecoupledSplicer.java`
- `peglib-incremental/src/main/java/org/pragmatica/peg/incremental/experimental/OffsetDecoupledNodeIndex.java`
- `peglib-incremental/src/main/java/org/pragmatica/peg/incremental/experimental/LongLongMap.java` (additive: `forEachEntry`)
- `peglib-incremental/src/main/java/org/pragmatica/peg/incremental/experimental/LinearProbingLongLongMap.java` (impl of `forEachEntry`)
- `peglib-incremental/src/test/java/org/pragmatica/peg/incremental/experimental/SpanIndexTest.java`
- `peglib-incremental/src/test/java/org/pragmatica/peg/incremental/experimental/OffsetDecoupledNodeTest.java`
- `peglib-incremental/src/test/java/org/pragmatica/peg/incremental/experimental/OffsetDecoupledSplicerTest.java`
- `peglib-incremental/src/jmh/java/org/pragmatica/peg/incremental/bench/MidBufferTreeBuilder.java`
- `peglib-incremental/src/jmh/java/org/pragmatica/peg/incremental/bench/MidBufferBench.java`
- `peglib-incremental/target/phase1-spanindex.json` — raw JMH output (not committed)
