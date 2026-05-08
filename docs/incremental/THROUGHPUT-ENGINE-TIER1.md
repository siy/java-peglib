# Throughput Engine — Tier 1 allocation reduction spec

**Date:** 2026-05-07
**Branch:** release-0.5.0 (post A-spike GO at commit `0ed2dcd`)
**Profile baseline:** [`docs/bench-results/generator-profile-baseline.md`](../bench-results/generator-profile-baseline.md)
**Naming:** the parser generator is now branded the **throughput engine** — distinct from the **incremental engine** (PegEngine + IncrementalSession). They have different optimization targets, different code shapes, and no shared code.

---

## 1. Motivation

The throughput engine's profiling baseline shows it allocates **150 MB per parse** of 1900 LOC and spends **26.6% of bench wallclock in GC**. Top allocators are internal plumbing, not user-visible CST:

| Samples | Class | Tier 1 move addressing it |
|---:|---|---|
| 3,190 | `Option$Some` | **A** (mutable ParseResult — Option boxing eliminated) |
| 3,008 | `Object[]` | C, D (fewer ArrayLists; primitive packing) |
| 2,574 | `CstParseResult` | **B** (mutable scratch — fewer record allocs) |
| 2,367 | `SourceLocation` | **D** (pack into primitive long fields) |
| 1,096 | `SourceSpan` | **D** |
| 587 | `CstNode.NonTerminal` (retained) | **C** (lazy materialization) — 3-5× more allocated than retained |
| 553 | `CstNode.Terminal` (retained) | **C** |

The use case constraint: **CST output is mandatory** (formatter + linter both consume the lossless tree). Pure-AST mode is off the table. Wins must come from changing *how* the CST is constructed, not whether.

The A spike already proved the approach is sound: 90.5% reduction in Option$Some samples + 16% reduction in bytes/op, with ZERO algorithmic change. Tier 1 stacks four coordinated moves to compound the win.

---

## 2. Tier 1 moves

### A — Sentinel-based ParseResult flow (PROVEN)

**Status:** spike landed at commit `0ed2dcd`. Opt-in via `mutableParseResult` ParserConfig flag.

**What changed:** the generator's emitted `ParseResult` class became a mutable record-like with raw nullable fields (no `Option<Object> value` / `Option<String> expected` wrappers). Emission templates use `result.value != null` instead of `result.value.isPresent()`, etc.

**Result (A alone vs phase1_allStructural baseline):**
- Option$Some allocations: 6,088 → 577 (**-90.5%**)
- Bytes/op: 150.4M → 126.3M (**-16%**)
- Wallclock: 81.97 → 75.01 ms/op (**-8.5%**, marginal due to CI variance)

**Open work in A:** the spike covered hot-path emission templates only. Capture, BackReference, Cut, And, Not still emit Option-style code. Coverage extension is a small follow-up — apply the same patterns. Estimated 2-3 hours.

### B — Mutable parse-state replacing per-attempt CstParseResult records

**Target allocation:** 2,574 CstParseResult samples per parse. Each successful or backtracked parse-method call currently allocates a fresh CstParseResult record holding (success, value, expected, endPos, endLine, endColumn, cutFailed).

**Proposal:** replace per-call allocation with a SINGLE shared mutable instance per parser invocation. Every parse method mutates this instance in place; callers read the fields immediately (no aliasing across calls).

```java
// Before
CstParseResult result = parseRule_X(pos, line, column);
if (result.success) { ... use result.endPos, result.value ... }

// After (B applied; the parser-instance field `state` is the singleton)
boolean ok = parseRule_X(pos, line, column);     // returns boolean (or int end-pos)
if (ok) { ... use state.endPos, state.value ... }
```

**Tradeoffs:**
- The singleton is non-aliasable: callers must consume the result fields BEFORE making any subsequent parse call. Emission templates already follow this pattern (parse + immediate read), so the constraint is already satisfied in practice.
- Packrat cache currently stores CstParseResult instances by value. Cache entries CANNOT use the shared mutable singleton — packrat needs immutable snapshots. Fix: when storing a cache entry, copy the singleton's fields into a small CacheEntry record (or directly into a primitive-packed long[]).
- Cut-failure short-circuits across rule calls work because the singleton's `cutFailed` field is checked immediately on return (existing pattern preserves).

**Expected gain:** -2,574 CstParseResult allocations (down to ~packrat-cache-entry count, which is bounded by rule × position memoization rate). Together with A's reduction in result-record-related allocations, this should knock another ~10-15% off bytes/op.

**Implementation effort:** ~3-4 days. Modifications to ParserGenerator emission templates: change `return CstParseResult.success(...)` to `state.setSuccess(...); return true;` patterns. Caller emission changes correspondingly. Packrat cache emission changes to copy fields into CacheEntry on put.

**Gate:** -50% CstParseResult allocations OR -10% wallclock vs (A applied) baseline.

### C — Lazy CST construction (parse → trace → materialize)

**Target allocation:** 587 + 553 + 75 = 1,215 retained CstNode records, but profile suggests **3-5× more are allocated and discarded during speculative parsing**. The 1140 retained vs. allocated-and-discarded ratio is what makes this the second-largest design move under the CST-mandatory constraint.

**Proposal:** decouple parse-time work from CST construction.

```
Parse phase (hot loop):
  - Each rule that succeeds appends a TraceEntry to a pre-sized growing buffer.
  - TraceEntry is a 16-byte primitive record: { ruleId: int, startPos: int, endPos: int, childTraceCount: int }.
  - Children are pre-order: a node's TraceEntry is followed by its children's TraceEntries.
  - Backtracking = rewind the trace's write-pointer; no allocation rollback needed.

Materialization phase (post-parse):
  - Walk the committed trace top-down; for each TraceEntry, allocate the corresponding CstNode.
  - Trivia attribution happens during materialization, with full tree context — not during parse.
```

**Why this is high-leverage:**
- Backtracked branches no longer allocate CstNode records — they only advance the trace write pointer, which then gets rewound for free.
- TraceEntry is 16 bytes; the average CstNode (with its children list, span, trivia lists) is 80+ bytes. Even retained nodes pay less during the parse phase (just the TraceEntry).
- CST is materialized exactly once with full structural context — opens a clean home for **trivia attribution rework**, which Lever B is currently waiting on. The "context-sensitive trivia attribution" problem may dissolve here because materialization is single-pass with full tree knowledge.

**Tradeoffs:**
- Memory profile shifts: the trace buffer needs to grow to ~10× the retained CST size (because of speculative entries). Pre-size based on grammar + input length; growth via amortized doubling. Worst case: a few MB of trace, vs. the current 150 MB allocation pressure.
- Trivia attribution semantics may need careful design. Currently embedded in PegEngine + emission templates; concentrating it into one materialization pass is cleaner BUT changes the timing — trivia decisions are now made AFTER all parsing is done, with full context. **This may be where Lever B's blocker dissolves.**
- More invasive than A or B: emission templates change substantially. ~1 week of focused work.

**Gate:** -50% CST-related allocations (NonTerminal + Terminal + Token + ArrayList for children) OR -15% wallclock vs (A+B applied) baseline.

**Bonus payoff:** trace-walking pass becomes a natural place to fix Lever B. Worth investigating during C's implementation.

### D — Pack SourceLocation / SourceSpan into primitive long fields

**Target allocation:** 2,367 SourceLocation + 1,096 SourceSpan = 3,463 combined samples per parse. Already partially packed in 0.4.3 (SourceSpan as int triples). Remaining work: eliminate SourceLocation as a record entirely.

**Current state (per HANDOVER §5 v0.4.3 entry):**
```java
record SourceSpan(int startOffset, int startLine, int startColumn,
                  int endOffset, int endLine, int endColumn) {
    public SourceLocation start() { return new SourceLocation(startLine, startColumn, startOffset); }  // ALLOC
    public SourceLocation end()   { return new SourceLocation(endLine, endColumn, endOffset); }        // ALLOC
}
```

Every `node.span().start()` accessor currently allocates a SourceLocation. The 2,367 samples are these on-demand reconstructions.

**Proposal:** eliminate SourceLocation as a record. Replace with primitive accessors on SourceSpan:
```java
record SourceSpan(int startOffset, int startLine, int startColumn,
                  int endOffset, int endLine, int endColumn) {
    public int startOffset() { return startOffset; }
    public int startLine()   { return startLine; }
    public int startColumn() { return startColumn; }
    public int endOffset()   { return endOffset; }
    public int endLine()     { return endLine; }
    public int endColumn()   { return endColumn; }
    // start() / end() removed
}
```

Callers of `span.start().offset()` / `span.start().line()` change to `span.startOffset()` / `span.startLine()`. Pure mechanical sweep.

**Tradeoffs:**
- Public API change: SourceLocation may be referenced by user code (the maven-plugin or playground emits diagnostics). Likely small surface; maintain a `SourceLocation` record class but stop *constructing* it from SourceSpan accessors.
- Test sweep: many tests pattern-match on SourceLocation — those need updating. Estimated ~50-100 sites across 5 modules.

**Implementation effort:** ~3-4 days. Mostly mechanical sweep + test updates.

**Gate:** -90% SourceLocation allocations + -50% SourceSpan allocations OR -5% wallclock vs (A+B+C applied) baseline.

---

## 3. Combined Tier 1 target

| Metric | Baseline (current) | Tier 1 target | Reduction |
|---|---:|---:|---:|
| Bytes/op | 150 MB | ≤ 30 MB | **-80%** |
| Wallclock | 76.2 ms | ≤ 50 ms | **-34%** |
| GC time fraction | 26.6% | ≤ 10% | **-62%** |
| Option$Some allocs | 3,190 | < 500 | -85% (A confirmed) |
| CstParseResult allocs | 2,574 | < 200 | -92% (B target) |
| CST allocs (NonTerminal+Terminal+Token) | 1,215 retained, 3-5× discarded | 1,215 retained, 0 discarded | -75% (C target) |
| SourceLocation allocs | 2,367 | < 100 | -95% (D target) |

**Aggregate target: 5× allocation reduction, 1.5× throughput improvement.**

---

## 4. Implementation sequence

Each move is GATED by re-profiling. If a move misses its gate, pause and reassess before continuing.

1. **A coverage extension** (3 hours) — apply the spike's pattern to the remaining emission paths (Capture, BackReference, Cut, And, Not). Re-bench. Close the A gate at the wallclock margin.

2. **B implementation + parity tests** (3-4 days) — mutable parse-state singleton; emission template rewrite; packrat cache CacheEntry copy. Bench gate: -50% CstParseResult allocs, -10% wallclock.

3. **Re-profile after A+B** — fresh profile. Confirms Tier 1 is on track. Identifies which move (C or D) to prioritize next based on residual allocator weights.

4. **C implementation** (1 week) — trace + materialization. **Investigate Lever B retry during C** — if trivia attribution moves into materialization, the literal-prefix gate may become unnecessary. Bench gate: -50% CST allocs, -15% wallclock.

5. **D implementation + sweep** (3-4 days) — eliminate SourceLocation construction; mechanical caller sweep. Bench gate: -90% SourceLocation allocs.

6. **Final Tier 1 bench + writeup** (1 day) — JFR + async-profiler runs against the final state; comparison table vs baseline; results doc at `docs/bench-results/throughput-tier1-results.md`.

**Total elapsed:** 2-3 weeks. Each gate provides a stop-loss; if A+B alone clear the user's actual perf needs, C and D can be deferred.

---

## 5. Risks

### R1 — Mutable shared state in B introduces aliasing bugs (LOW likelihood, MEDIUM impact)

The mutable singleton requires "consume immediately, then make next call" discipline. Existing emission already follows this pattern; the risk is in subtle paths where a result is held across calls.

**Mitigation:** the parity tests (`Phase1ParityTest`, `Phase2*ParityTest`) compare CST output between flag-on and flag-off variants. If aliasing breaks something, parity diverges immediately.

### R2 — C's trace-buffer architecture is invasive (MEDIUM likelihood, HIGH impact)

Lazy CST is the largest emission-template change in Tier 1. Subtle bugs in trace materialization (missing children, wrong rule-id mapping, trivia mis-attribution) could break the CST output.

**Mitigation:** parity tests + RoundTripTest. C's implementation gates on RoundTripTest stays green; if it breaks, fix before merging.

### R3 — D's SourceLocation removal is a public API change (HIGH likelihood, LOW impact)

Downstream consumers may pattern-match on SourceLocation records (maven-plugin diagnostics, playground UI rendering).

**Mitigation:** keep `SourceLocation` as a record class; just stop *constructing* it from SourceSpan accessors. External code that explicitly creates SourceLocation continues to work.

### R4 — The 5× allocation target is overshoot; may settle at 3-4× (MEDIUM likelihood, MEDIUM impact)

The 80% reduction target assumes A+B+C+D compound multiplicatively. They likely overlap (same allocations targeted from different angles). Real combined reduction may be 60-70% (still excellent).

**Mitigation:** measure each gate empirically; gates are sized for individual-move reduction, not the combined target.

### R5 — Wallclock improvement gates may continue to be CI-variance-bound (MEDIUM likelihood, LOW impact)

The A spike showed 8.5% wallclock improvement but with ±37 ms CI bands. JIT warmup and GC pauses dominate variance.

**Mitigation:** when measuring Tier 1, use longer iteration counts (`-i 10 -wi 5 -f 2`) to tighten CI. The bytes/op metric is much less variable; treat it as the primary gate; wallclock as confirmation.

---

## 6. Compatibility commitments

- **Generator output remains a single self-contained Java file** — depending only on `pragmatica-lite:core`. Tier 1 doesn't introduce a runtime dependency.
- **CST output is byte-identical to current generator** — byte-equality verified by RoundTripTest 22/22.
- **Public Session API unchanged** — Tier 1 is generator-internal.
- **`mutableParseResult` flag** (and any new gates we add) — opt-in for users who want the new emission. Once Tier 1 is fully proven, can flip the default; old behavior preserved as a flag.

---

## 7. Out of scope for Tier 1

Deferred items, ranked roughly by next-pass priority:

- **Packrat as `long[][]` array** (was Tier 2 E in the profile doc). 622 + 174 HashMap allocations + 41 CPU samples. Worth ~5-10% additional after Tier 1.
- **First-set Choice dispatch** (was Tier 3 F). Existing `choiceDispatch` flag — investigate first; may be partial. ~10-30% on Java-shaped grammars.
- **DFA-based lexer for token rules** (was Tier 3 G). Biggest individual gain but biggest architectural lift. 2-3 weeks. Evaluate after Tier 1's residual profile.
- **Vectorized terminator scanning, ASCII whitespace fast path** — small tactical wins; defer.
- **Lever B retry** — gated on C's trivia-attribution rework. May dissolve the literal-prefix gate.

---

**Last updated:** 2026-05-07 by the post-A-spike review.
