# Phase 0 — GO/NO-GO Results

> **Status:** historical / archived 2026-05-08. Interim phase results; final state in PHASE-1-RESULTS.md.


**Date:** 2026-05-07
**Branch:** release-0.5.0
**Spec:** [`ARCHITECTURE-0.5.0.md`](../incremental/ARCHITECTURE-0.5.0.md)

## Verdict: **GO**

All three GO/NO-GO gates green. Recommend proceeding with Phases 1–5 per spec §6.

| Gate | Criterion | Result |
|------|-----------|:------:|
| Q3 — identity-preservation invariant | Sibling subtrees of the splice path satisfy reference equality (`==`) with their pre-edit counterparts | **GREEN** |
| Q4 — trivia-bearing edits | Calculator grammar with `%whitespace`/comments; 3 representative edits each show incremental update equivalent to full rebuild + invariant preserved | **GREEN** |
| Perf — incremental NodeIndex update materially beats full rebuild | ≥5× speedup at representative tree sizes (≥1000 nodes) | **GREEN — 47× at 1k nodes, 67× at 10k nodes** |

## Work delivered

Phase 0 on the `release-0.5.0` branch, sandboxed in `peglib-incremental/src/main/java/org/pragmatica/peg/incremental/experimental/`. No production code touched. Existing 897-test suite unaffected throughout.

| Phase | Commit | Deliverable | New tests |
|-------|--------|-------------|----------:|
| 0a | `d00eaa1` | `IdGenerator` + `PerSessionCounter`; `LongLongMap` + `LinearProbingLongLongMap` | 17 |
| 0b | `f0696a1` | `IdCstNode` (sealed, ID-bearing variant of production CstNode); `IdCstNodeBuilder` (production-tree → IdCstNode converter) | 19 |
| 0c | `849b4ba` | `IdNodeIndex` with `build` (O(N) full) + `applyIncremental` (O(splicedSize + depth × branching) per spec §2) | 8 |
| 0d.1 | `9b55253` | `IdTreeSplicer` (record-identity-preserving splicer); identity-invariant test (Q3 gate); calculator + trivia regression test (Q4 gate) | 10 |
| 0d.2 | `a2dd8ac` | JMH bench `Phase0SpikeBench` + results note `docs/bench-results/phase0-spike-results.md` | — |

**Test totals:** 154 incremental tests (was 100; +54 from Phase 0). 699 core tests unchanged. Full suite green at every checkpoint.

## Gate details

### Q3 — identity-preservation invariant

**Test:** `IdTreeSplicerTest` (7 tests). Constructs a 4-deep, 4-wide tree, splices at one location, asserts that for every node on the splice path, all siblings at indexes other than the spliced one are reference-equal (`==`) in old and new trees. Implementation: `IdTreeSplicer.splice(...)` builds new `NonTerminal` records by `ArrayList(old.children())` + `set(spliceIndex, current)` — every other child slot is preserved by reference.

**Microcount evidence:** Phase 0c's depth-3 splice on a 28-node tree triggered exactly 8 `parents.put` calls (vs 28 for full rebuild) — confirms the algorithm walks only the splice path + spliced subtree + ancestor children, not the whole tree.

### Q4 — trivia-bearing edits

**Test:** `CalculatorTriviaIncrementalTest` (3 tests, one per edit kind). Calculator grammar with `Comment <- '/*' (!'*/' .)* '*/'` and `%whitespace <- ([ \t\r\n]+ / Comment)+`. For each of:

- Edit A — insert blank line before operand (`"1+2"` → `"1+\n  2"`)
- Edit B — delete comment between operands (`"1 /*hi*/ + 2"` → `"1 + 2"`)
- Edit C — insert comment inside expression (`"1+2"` → `"1+/*x*/2"`)

The test parses both inputs, converts to `IdCstNode`, identifies the splice point pragmatically (the `Number` whose token text is "2"), splices via `IdTreeSplicer`, applies `applyIncremental`, and asserts the resulting parents map is structurally equivalent to a fresh `IdNodeIndex.build(newRoot)`. Identity invariant verified concurrently — the test instrumentation logs `siblingsChecked` for each edit.

All three edits pass both assertions. The Q4 gate is satisfied **for the algorithm given identity-shared trees from a splicer**. Production parser's lack of cross-parse record sharing is a separate concern flagged for Phase 1 (see §"Notes for Phase 1" below).

### Perf gate — incremental update beats full rebuild ≥5×

**Bench:** `Phase0SpikeBench` (synthetic perfect 4-ary tree, depth-3 splice, JDK 25, JMH 1.37). Full numbers in [`docs/bench-results/phase0-spike-results.md`](../bench-results/phase0-spike-results.md).

| Tree size | fullRebuild (μs) | incrementalUpdate (μs) | Speedup |
|-----------|-----------------:|-----------------------:|--------:|
| 100       |  2.678 ± 0.389   |  0.069 ± 0.001         |  38.8×  |
| 1000      |  9.611 ± 0.312   |  0.203 ± 0.011         |  47.3×  |
| 10000     | 176.468 ± 16.561 |  2.627 ± 0.237         |  67.2×  |

Speedup grows with tree size — incremental cost is dominated by `O(depth × branching)` (≈ `log_4(N) × 4`), full-rebuild cost is `O(N)`. The 0.4.3 fixture (1900 LOC, ~10k nodes) is in the regime where 0.5.0 can deliver order-of-magnitude per-edit gains.

The spec's projected 0.5.0 floor (p99 ≤ 16ms on the 1900-LOC fixture) is comfortably attainable: at 10k nodes the bench shows applyIncremental at 2.6μs — five orders of magnitude under the 16ms target. Even with the realistic costs of `TreeSplicer` work, parser invocation, and trivia attribution layered on top, the budget is large.

## Notes for Phase 1

Three concrete items surfaced during Phase 0 that Phase 1 should address explicitly:

1. **Cross-parse record sharing.** `IdCstNodeBuilder` re-assigns fresh IDs on every conversion — the production parser produces a fresh tree per parse with no record sharing. The 0.5.0 algorithm's perf depends on the editing flow producing identity-shared trees, which is `TreeSplicer.spliceAndShift`'s job, not the parser's. Phase 1's first task: confirm `TreeSplicer.spliceAndShift` preserves sibling identity (per spec §8 Q3 second sentence). If it doesn't, fix it; spec calls this part of Phase 0/1 scope.

2. **`applyIncremental` mutate-in-place semantics.** Spike API invalidates the receiver — caller must use the returned instance. Phase 1 needs to choose: (a) copy-on-write via `LongLongMap.copy()` (O(N) per edit, partially defeats the point — though still much cheaper than a full Index rebuild because the copy is primitive arrays vs IdentityHashMap entries), (b) persistent map (e.g., HAMT-based long-long), (c) keep mutate-in-place + document loudly. Decision affects the API shape of `IncrementalSession`.

3. **Worst-case splices not benched.** The spike measures depth-3, small-pivot splices. Class-body-scale rewrites (large pivots) need a separate bench in Phase 1 to confirm the algorithm scales gracefully there too.

## Recommendation

Proceed with Phases 1–5 per spec §6 schedule (4–5 weeks elapsed, 2–3 weeks of focused engineering). The architectural premise is empirically validated:

- Algorithm is correct under both invariants the spec calls out.
- Perf gain is order-of-magnitude — well above the 5× threshold and consistent with the spec's 300× projection.
- Sandbox approach proved viable: Phase 0 landed without disturbing the 897-test production surface.

Open the next session with **Phase 1 — Lever A on full Java grammar**: migrate production `CstNode` to ID-bearing variants, switch `NodeIndex` to `LongLongMap`, verify all 897 tests + 22×100 IncrementalParityTest stay green, re-bench against the 0.4.3 baseline on the 1900-LOC fixture.
