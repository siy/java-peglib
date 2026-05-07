# Throughput Engine — Tier 1 Results

**Date:** 2026-05-07
**Branch:** release-0.5.0 (commits `0ed2dcd` through `9e9414a`)
**Baseline:** [`docs/bench-results/generator-profile-baseline.md`](generator-profile-baseline.md)
**Spec:** [`docs/incremental/THROUGHPUT-ENGINE-TIER1.md`](../incremental/THROUGHPUT-ENGINE-TIER1.md)
**Bench:** `Java25ParseBenchmark.parse`, 5 iter / 3 warmup / 1 fork, JDK 25, async-profiler 4.4

## Headline numbers (1900-LOC Java fixture, Regime: full reparse)

| Pass | Wallclock (ms/op) | Bytes/op | vs Original |
|---|---:|---:|:---:|
| Original baseline (post-Lever-D) | 76.2 | 150 MB | — |
| **+A** spike + coverage extension | **66.4** | **122.9 MB** | **-12.9% / -18%** |
| +D production sweep | 65.2 | 123.1 MB | -14.4% / -18% |
| +D emission templates (`inlineLocations` default-on) | 64.1 | 118.3 MB | -15.9% / -21% |
| ~~+E packrat int-keyed~~ — **REVERTED** (regressed self-host stress test by +22%) | — | — | — |
| **Current state** (post-revert = post-D) | **~64** | **~118 MB** | **-16% / -21%** |

The headline win is **A**: -90.5% Option$Some allocation samples, -12.9% wallclock alone. D added an incremental allocation reduction but the wallclock signal stayed within CI noise. **E was reverted** after self-host stress testing exposed a 22% regression masked by the small reference fixture (see "E reverted" below).

## What landed

### A — Sentinel-based ParseResult flow (mutable, no Option boxing)

Two passes:
1. **A spike** (`0ed2dcd`): added opt-in `mutableParseResult` ParserConfig flag. Emits a mutable `CstParseResult` class with raw nullable fields instead of `Option<Object> value` / `Option<String> expected`. Hot-path emission templates use `result.value != null` instead of `.isPresent()`.
2. **A coverage extension** (`fedc389`): applied the same pattern to the remaining residual emission paths (Capture, BackReference, Cut, And, Not, error-recovery infrastructure: `furthestExpected`, `pendingFailureRecoveryOverride`).

Result: Option$Some samples 6,088 → 0 (-100%). Bytes/op 150 MB → 123 MB (-18%). Wallclock 76.2 → 66.4 ms (-12.9%).

### D — Packed SourceLocation/SourceSpan emission

Two passes:
1. **Production sweep** (`478b89b`): replaced `.span().start().X()` patterns across 7 files (PegEngine, Diagnostic, FormatContext, Formatter, TreeSplicer, TriviaRedistribution + ParserGenerator error paths) with primitive accessors. Bench-neutral (production code paths aren't on the throughput bench's hot loop), but valuable cleanup (-63 net LOC).
2. **Emission templates** (`5b2b6a1`): completed the `inlineLocations` flag implementation in ParserGenerator and flipped its default to `true`. Emitted CST construction now uses `new SourceSpan(line, col, off, endLine, endCol, endOff)` directly instead of `SourceSpan.sourceSpan(new SourceLocation(...), new SourceLocation(...))`.

Result: SourceLocation samples 2,367 → 1,962 (-17%); SourceSpan samples 1,096 → 1,391 (sample noise — bytes/op confirmed -2-3%). Wallclock essentially unchanged.

## E reverted (E regressed self-host stress test)

**Status:** E (`2ad2674`) was reverted in `9e9414a` after the self-host stress test exposed a regression that the small reference fixture missed.

**What E was:** an emitted `IntCstParseResultMap` class (linear-probing, `int[]` keys + `CstParseResult[]` values, no Integer autoboxing). Replaced `HashMap<Integer, CstParseResult>` per-rule packrat caches.

**A/B numbers** (variant `phase1_allStructural_mutableResult`, self-host fixture — parser parsing its own generated source, ~37k lines / ~1.8 MB):

| Metric | pre-E (post-D) | post-E (with IntCstParseResultMap) | Delta |
|---|---:|---:|---:|
| Wallclock | 1700 ms | 2074 ms | **+22%** |
| Allocated | 2915 MB | 2741 MB | -6% |

**Diagnosis:** `IntCstParseResultMap` likely has worse cache behavior than `HashMap` at the load factors the self-host fixture stresses. Linear probing with millions of insertions and high collision density turns into linear scans in `get`/`put`; `HashMap`'s tree-bin promotion bounds worst-case probe length. The small reference fixture's per-rule cache populations stay below the threshold where this matters, so the regression sat inside CI noise on the small bench while costing 22% on the large one.

**Lesson:** the small bench was incomplete coverage. Pre-Tier-1 the parser couldn't even complete files this size ("previously such files caused OOM"); now it parses them in 1.7 sec / 2.9 GB allocated. That capability headroom let us add **self-host as a second JMH fixture** (`Java25ParseBenchmark` `fixture=selfhost`) — the parser parses its own freshly generated source code (~25× the work of `FactoryClassGenerator.java.txt`). Future structural changes are now A/B-tested on both fixtures before landing.

## What didn't land

### B — Mutable parse-state singleton

The agent flagged real complexity (left-recursive seed-and-grow snapshotting, packrat cache aliasing, ~80 emission sites, cross-call read discipline) that can't be safely landed in a single autonomous pass. Per the agent's recommendation, B would need 4-6 incremental commits, each gated on parity tests. Deferred for incremental delivery in a future session.

Target if landed: -50% CstParseResult allocations (3,123 → ~200), expected -10-15% additional wallclock.

### C — Lazy CST construction (parse → trace → materialize)

Multi-week refactor per spec. Targets the 3-5× ratio of speculative-and-discarded CstNode allocations. May incidentally unblock Lever B's trivia attribution problem.

Target if landed: -50% CST allocations, -15-20% wallclock, opens trivia attribution rework.

### F — First-set Choice dispatch

The existing `choiceDispatch` flag was investigated and found to be already partially implemented per the bench's variant naming (`phase1_choiceDispatch`). The flag is on by DEFAULT in ParserConfig per the v0.4.x perf rework. Not a separate move — it's already shipping.

### G — DFA-based lexer for token rules

Biggest individual potential gain (2-3× on lex-heavy paths) but biggest architectural lift (~2-3 weeks). Not attempted.

## Honest assessment

We hit ~40% of the Tier 1 spec target on wallclock and ~26% on bytes/op (post-revert):

| Metric | Baseline | Spec target | Actual (post-D, post-E-revert) | % of target |
|---|---:|---:|---:|---:|
| Wallclock | 76.2 ms | ≤ 50 ms (-34%) | 64.1 ms (-16%) | 47% of target |
| Bytes/op | 150 MB | ≤ 30 MB (-80%) | 118.3 MB (-21%) | 26% of target |

The remaining gain requires either:
- B's mutable parse-state (incremental delivery, ~4-6 days)
- C's lazy CST construction (single architectural lift, ~1-2 weeks)
- DFA lexer (architectural, ~2-3 weeks)

A's gain held; D was modest because most of D's targets are inside the emitted parser, not production code; E was reverted (self-host regression) — a re-attempt would need a packrat structure that doesn't degrade at large cache populations.

## Recommendation

Ship the current state as 0.5.0-alpha (it's a real ~16% throughput improvement + ~21% allocation reduction with byte-equal CST output, A+D landed, E reverted). Tackle B/C/DFA in dedicated multi-day sessions when the time is available — they require careful incremental work that doesn't fit single-pass autonomous delivery. Any re-attempt of E (or a different packrat structure) must A/B against both `fixture=reference` and `fixture=selfhost`.

Cursor + parser optimization arc summary: -54% median + -50% p95 + 96.5% frame budget on the incremental engine (Lever A+D shipped); -16% throughput + -21% allocation on the throughput engine (Tier 1 partial, A+D shipped, E reverted). Both engines now have measurable improvements over 0.4.3 baselines with clear forward paths documented.
