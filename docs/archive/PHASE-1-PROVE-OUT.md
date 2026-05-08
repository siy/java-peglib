# Phase 1 Prove-Out — Path A → Path D

> **Status:** historical / archived 2026-05-08. Interim phase results; final state in PHASE-1-RESULTS.md.


**Date:** 2026-05-07
**Branch:** release-0.5.0 (commits `8f844eb` through `8b27dd6`)
**Spec:** [`ARCHITECTURE-0.5.0.md`](../incremental/ARCHITECTURE-0.5.0.md), [`PHASE-0-RESULTS.md`](PHASE-0-RESULTS.md)

## Verdict

**Path D is Phase 1's architecture.** Path A (offset decoupling from records) was prove-out RED; Path D (stable-id ancestor preservation) is GREEN with **96× at 1000 nodes, 604× at 10000 nodes** — and a flat absolute cost (~25-40 ns) that confirms genuine O(δ) scaling independent of tree size.

Phase 0's spike measured 67× speedup on balanced trees. Real CSTs aren't balanced — Java method bodies have flat fanout (1 NonTerminal "Block" with N "Stmt" children). Phase 1's prove-out exposed that the spec §2 algorithm degrades to O(N) on flat trees because step 3 rewires every sibling under a freshly-IDed ancestor. Path D fixes this by reusing old ancestor IDs across splices — the parents-map entries for unchanged siblings stay valid, step 3 disappears.

## Why Path A was prove-out RED

**Hypothesis:** removing `SourceSpan` from `CstNode` records eliminates the deep-copy of right-of-edit subtrees in `TreeSplicer.shiftAll` (lines 113-142 in `TreeSplicer.java`). Sibling subtrees retain identity → incremental NodeIndex update stays at O(δ).

**Bench result (path A — `MidBufferBench`, flat tree, mid-buffer single-Terminal splice):**

| Tree size | productionStyle (μs) | offsetDecoupled (μs) | Speedup |
|----------:|---------------------:|---------------------:|--------:|
|       100 | 1.272                | 1.153                |  1.10×  |
|      1000 | 12.524               |  9.710               |  1.29×  |
|     10000 | 81.656               | 102.620              |  0.80×  |

**RED.** The hypothesis was wrong about the bottleneck. Path A *did* preserve right-of-edit sibling identity (correctness test green), but the dominant cost on flat trees isn't the deep-copy — it's `applyIncremental` step 3 rewiring every direct child of the freshly-IDed ancestor.

The Path A migration (eliminate `span()` from CstNode interface across all 5 modules; introduce SpanIndex; refactor every span-reading site) would have been weeks of cross-cutting refactor for a 1.10-1.29× best-case speedup. Honest dead end.

## Why Path D is GREEN

**Hypothesis:** reuse `oldAncestor.id()` when building new ancestor records during splice. Then:
- Old `parents` map entries for unchanged sibling subtrees remain valid (parent's *ID* didn't change, only its record instance did)
- step 3's "rewire children to new ancestor IDs" becomes redundant work
- step 1's "remove oldPath ancestors' up-pointers" becomes wrong (those entries are still valid)

Optimized `applyIncremental`:
- Skip step 1 oldPath ancestor removal
- Just remove oldPivot + descendants (those ARE dead — newPivot has fresh IDs)
- Step 2: insert newPivot subtree + set newPivot's parent to `oldPath[size-2].id()`
- Skip step 3 entirely

Total cost: O(oldPivotSize + newPivotSize). Independent of N AND of tree shape.

**Bench result (`PathDBench`, same flat-tree shape):**

| Tree size | productionStyle (μs) | stableIdNoOpt (μs) | pathD (μs) | pathD speedup vs production |
|----------:|---------------------:|-------------------:|-----------:|----------------------------:|
|       100 | 0.304                | 0.286              |  0.023     |  13.2×                      |
|      1000 | 2.697                | 2.843              |  0.028     |  **96.3×**                  |
|     10000 | 24.762               | 23.298             |  0.041     |  **604×**                   |

**GREEN.** And the absolute time is **flat** across tree sizes (~25-40 ns) — proving the algorithm's cost is purely a function of splice size, not tree size.

Microcount evidence: 1 `put` + 1 `remove` per `applyIncremental` for a single-node splice on a 1000-node flat tree, vs ~1002 ops for the original algorithm. The 500× microcount ratio tracks the wallclock speedup at the appropriate scale.

## Critical insight — splicer and index migrate as a unit

The bench's middle column (`stableIdNoOpt` — stable IDs from splicer, but original spec §2 `applyIncremental`) matches `productionStyle` within noise. Stable ancestor IDs alone don't help. The optimized `applyIncremental` that **trusts** the stability is what realizes the gain.

This means Phase 1 must migrate splicer + index together, not piecemeal. A staging branch that flips the splicer to stable IDs but keeps the old `applyIncremental` would show no perf change — Phase 1 must commit to both at once.

## Phase 1 production migration plan (Path D)

**Total scope: ~3-5 days focused engineering.** Substantially smaller than original spec §6 estimate of 1 week, because:
- No `SpanIndex` needed (Path A is shelved)
- No CstNode interface change beyond adding `id()` accessor
- TreeSplicer change is **one line** (replace fresh ancestor record allocation with reused-id construction)
- `applyIncremental` simplifies (steps 1 and 3 deleted)

### Sub-phases

1. **1.2 — production `CstNode` ID field.** Add `long id` as the leading record component to all four variants (`Terminal`, `NonTerminal`, `Token`, `Error`). Override `equals`/`hashCode` to exclude id (per spec §7 R1). Migrate all pattern-match callers across `peglib-core`, `peglib-incremental`, `peglib-formatter`, `peglib-maven-plugin`, `peglib-playground` to include the new component. Hundreds of sites; mostly mechanical sweep.

2. **1.3 — production `IdGenerator` plumbing.** Thread `IdGenerator` through `PegEngine` and `ParserGenerator`'s emission templates. Per-Session counter is the v0.5.0 default per spec §8 Q1.

3. **1.4 — `TreeSplicer.spliceAndShift` reuses ancestor IDs.** One-line behavioral change. Add a parity test asserting `oldPath[i].id() == newPath[i].id()` for ancestors.

4. **1.5 — `NodeIndex` switches from `IdentityHashMap` to `LongLongMap`.** Port the experimental package's `LinearProbingLongLongMap` to production. NodeIndex's API stays put externally.

5. **1.6 — `IncrementalSession.applyIncremental` adopts Path D's optimized algorithm.** Steps 1 and 3 deleted (per Path D's analysis). Microcount instrumentation in tests as a hard regression net.

6. **1.7 — verify parity + bench against 0.4.3 baseline on the 1900-LOC fixture.** All 897 tests + IncrementalParityTest 22×100 must stay green. JMH bench replicates the IncrementalSessionBench suite to confirm the speedup translates to real workloads (mid-buffer realistic edits, not just synthetic).

### Risks specific to Path D

- **TreeSplicer's `shiftAll` for right-of-edit subtrees still deep-copies.** Path D fixes the *NodeIndex* perf problem but doesn't address record allocation pressure for right-of-edit sibling subtrees. Phase 1 ships with this remaining; if profiling shows it's a tail-latency contributor, address as 0.5.x patch via path-A-style offset decoupling, but only if the bench shows it's worth it. The MidBufferBench Path A run (`phase1-spanindex-results.md`) is the existing data point — at 10k nodes path A was *slower* due to its own overheads. So decoupling for allocation-pressure reasons alone isn't a clear win.

- **ID semantics shift.** Pre-0.5.0: nodes have JVM identity. Post-0.5.0: nodes have explicit `long id`, which is preserved across edits *unless the splicer says otherwise*. This is the right abstraction for incremental editing (LSP, IDE plugins want to track "is this still the same Block as before this keystroke?"), but downstream callers must understand the new semantic. Document loudly in the migration guide.

- **Pattern-match call site sweep.** Adding `long id` to record variants breaks every `case Terminal(SourceSpan span, ...)` style match across the codebase. Mechanical but high-volume. Phase 1.2 is the bulk of the work; recommend doing it via a single `jbct-coder` pass with a clean baseline.

## Files added during prove-out

In `peglib-incremental/src/main/java/.../experimental/` (sandbox, all additive):

- Path A (commit `8f844eb`, RED): `SpanIndex`, `OffsetDecoupledNode`, `OffsetDecoupledNodeIndex`, `OffsetDecoupledSplicer`, plus `LongLongMap.forEachEntry` extension.
- Path D (commit `8b27dd6`, GREEN): `StableIdSplicer`, `StableIdNodeIndex`.

In test sources: corresponding test classes for both paths.

In `peglib-incremental/src/jmh/java/.../bench/`: `MidBufferBench`, `MidBufferTreeBuilder` (Path A), `PathDBench` (Path D).

Bench result MDs:
- `docs/bench-results/phase1-spanindex-results.md` (Path A)
- `docs/bench-results/path-d-results.md` (Path D)

**Sandbox stays sandbox.** No production source touched. Existing 897-test suite unchanged. Sandbox tests: 190 (was 100 pre-Phase-0; +90 from Phase 0 + Path A + Path D combined).
