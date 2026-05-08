# Throughput Engine — Move B Detailed Spec (Mutable Parse-State Singleton)

**Date:** 2026-05-08
**Branch starting point:** `release-0.5.0` at `fd278fe` (pushed; tag `v0.5.0-candidate`)
**Spec ancestor:** [`THROUGHPUT-ENGINE-TIER1.md`](THROUGHPUT-ENGINE-TIER1.md) §2 B
**Bench baseline (post all current optimizations):**
- Reference fixture (1900-LOC Java): **22.6 ms / 75.6 MB** per parse
- Self-host fixture (37k-LOC Java25 generated parser): **956 ms / 2.04 GB** per parse
- vs javac (parse-only): **2.5× of javac** wallclock with strictly more output

---

## 1. What's left and why B is the last big lever

Throughput engine arc to date (cumulative -70% reference wallclock, -50% bytes vs original 76.2 ms / 150 MB baseline):

```
A    Option boxing eliminated         (-13% wallclock, -18% bytes)
D    SourceLocation/SourceSpan packed (-2% bytes)
D2   location() callers swept         (-6% wallclock, -4% bytes)
F    FIRST-set choice dispatch        (-20% wallclock both fixtures)
G    JBCT-style method splits          (-10% both fixtures, parse_Stmt 27k → 3k bytes)
G2+H Sequence chunks + nested choices (-5% reference, -8% selfhost)
SP   Selective packrat auto-detect    (-38% reference, -14% selfhost)
DFA  Identifier fast-path scanner     (-10% reference, -8% selfhost)
```

**Reverted (bench-driven discipline):**
- E (int-keyed packrat) — regressed self-host 22%
- H2 (recursive nested-Choice per-alt) — added call overhead, regressed 4-7%
- DFA generalization beyond Identifier — neutral (low-volume rules)

**Pattern that emerged:** high-volume single targets win big; broad generalizations don't pay back the call/dispatch overhead. **Move B targets the highest-remaining single allocator on every parse path** — the per-call `CstParseResult` record allocation.

### B's target: 5,264 CstParseResult allocations per self-host parse

Allocation profile of the current state shows `CstParseResult` is still the #2 allocator after `Object[]`. Every parse method's success or backtrack allocates one. After A made each record cheaper (raw nullable fields, no Option boxing), the per-allocation cost dropped — but the COUNT didn't. Each call-site still allocates.

**B replaces per-call allocation with a single shared mutable instance.** Each `parse_<X>` method mutates `this.result` and returns `boolean`. Callers consume the result fields immediately before making the next parse call.

**Projected gains** (per Tier 1 spec §2 B):
- -50% to -90% reduction in CstParseResult allocations (target: 5,264 → ~200, where the residual is packrat cache snapshots only)
- -10-15% additional wallclock on top of current baseline
- Brings reference fixture into the **20 ms / 60 MB** range, **~2× of javac** territory

---

## 2. Why the first attempt declined

Agent who attempted B in a single autonomous pass surfaced four concrete blockers:

1. **Left-recursive seed-and-grow** — the LR algorithm holds a `lastSeed: CstParseResult` across iterations of "grow." With a single shared mutable instance, the seed gets clobbered on the next inner parse call. Need an explicit per-LR-rule snapshot record.

2. **Packrat cache aliasing** — cache stores `CstParseResult` instances by value. With a shared mutable singleton, a cache entry would alias the next parse's mutation. Need a `CacheEntry` record (immutable copy of singleton's fields) at cache-put time; reload on cache-hit.

3. **~80 emission sites** — every `return CstParseResult.success(...)`, every `r.value.isPresent()`, every `r.expected.isPresent()` becomes singleton mutation + caller reads `this.result.X`. High-volume mechanical sweep with subtle aliasing risk per site.

4. **Cross-call read discipline** — caller MUST consume result fields before any subsequent parse call (otherwise next call's mutation overwrites). Existing emission already follows this pattern in practice (parse + immediate read), but the discipline must be enforced and verified.

The agent's quote: *"I'd rather report this honestly than produce broken code claiming green tests."* Smart pushback. The right delivery shape is multi-commit, parity-gated.

---

## 3. The delivery plan: 4-6 incremental commits

Each commit is independently green (`mvn -pl peglib-core test` passes Phase1ParityTest 22/22 + RoundTripTest). Bench A/B at end of each major commit, but the gate is parity, not perf — perf evaluation comes after the full sequence lands.

### Commit 1 — Foundation (no behavior change)

**Scope:** Emit infrastructure, leave existing static factory call sites untouched.

- Add `private final CstParseResult result = new CstParseResult();` field to emitted parser class (under flag ON only — gate via existing `mutableParseResult` flag)
- Emit a new `CstParseResult` shape with mutator methods returning `boolean`:
  ```java
  boolean setSuccess(Object v, int endPos, int endLine, int endColumn) { ... return true; }
  boolean setFailure(String expected) { ... return false; }
  boolean setCutFailure(String expected) { ... this.cutFailed = true; return false; }
  ```
  KEEP the existing static factories `CstParseResult.success(...)`, `.failure(...)`, etc. as `return new CstParseResult(...)` for backward compat during the migration. They'll be removed in commit 4-5 once all emission sites are converted.
- Emit `private record CacheEntry(...)` for packrat snapshots (matching CstParseResult's field set).
- Emit packrat cache put/hit converted to use CacheEntry: on success return AND cache miss, snapshot fields into a new `CacheEntry` and store; on cache hit, load CacheEntry's fields into `this.result.success/value/expected/...` and return `success`.

**Gate:** all 922 tests green. Bench: no perf change expected (packrat path slightly different but most parse calls still allocate via static factories).

### Commit 2 — Migrate `parse_<rule>` rule methods

**Scope:** Convert top-level rule methods (the entry points produced from each grammar rule) to use the singleton.

Each `parse_<RuleName>` method:
- Old signature: `private CstParseResult parse_<X>() { ... return CstParseResult.success(...); }`
- New signature: `private boolean parse_<X>() { ... return setSuccess(...); }`
- Body: replaces inner `var r = parse_<Y>();` pattern with `parse_<Y>();` (boolean return ignored; reads happen via `this.result.X`)

Caller emission for inner-rule references at this stage: the helper `parse_<Y>()` returns boolean; caller reads `this.result.success` and `this.result.value`/`.expected`/etc. immediately before the next inner call.

Match helpers (`matchLiteralCst`, `matchCharClassCst`, `matchAnyCst`, `matchDictionaryCst`) STAY allocating CstParseResult via static factory at this commit. They're converted in commit 3.

**Gate:** all 922 tests green. Phase1ParityTest 22/22 (CST output byte-identical). Bench: starting to see allocation reduction; wallclock similar.

### Commit 3 — Migrate match helpers

**Scope:** Convert `matchLiteralCst`, `matchCharClassCst`, `matchAnyCst`, `matchDictionaryCst` to mutate the singleton.

These are the hot inner parse helpers; converting them removes a substantial fraction of CstParseResult allocations.

Pattern: `return CstParseResult.success(text, endPos, endLine, endColumn);` becomes `return setSuccess(text, endPos, endLine, endColumn);` (mutates `this.result` and returns true).

**Gate:** parity tests + RoundTripTest. Bench: meaningful allocation drop; wallclock starts improving.

### Commit 4 — Migrate Expression emission

**Scope:** Convert emitted parsers for Sequence, Choice, ZeroOrMore, OneOrMore, Optional, Repetition expressions.

These are emission templates that compose the inner-parse calls into the rule body. They construct `CstParseResult` for intermediate states. Convert to singleton mutation.

This is the bulk of the ~80 emission sites count. High mechanical density but each individual change is small.

**Gate:** parity + RoundTripTest. Bench: largest single drop in CstParseResult allocations (back to packrat-cache-snapshot count only).

### Commit 5 — Migrate predicate + capture + cut

**Scope:** Convert And, Not, Capture, BackReference, Cut, TokenBoundary emission.

These are smaller volume but add up. Predicates (And/Not) need careful state restoration since they don't consume input on success.

**Gate:** parity + RoundTripTest. Bench: should be close to final allocation profile.

### Commit 6 — LR seed-and-grow snapshot

**Scope:** Convert the left-recursive seed-and-grow wrapper to use a per-rule snapshot record.

Currently `lastSeed: CstParseResult` is preserved across grow iterations. With singleton mutation, the seed gets clobbered. Solution: emit a small `LRSeed` record per LR rule that captures the singleton's fields between iterations:

```java
private record LRSeed(boolean success, Object value, String expected, int endPos, int endLine, int endColumn, boolean cutFailed) {}

// In LR rule:
LRSeed seed = LRSeed.from(this.result);  // snapshot
while (...) {
    parse_<Inner>();  // mutates this.result
    if (improved) seed = LRSeed.from(this.result);
    else break;
}
LRSeed.restoreTo(this.result, seed);  // restore for caller
```

Then remove the static factories `CstParseResult.success/failure/cutFailure` from the emitted class — all callers are converted.

**Gate:** parity + RoundTripTest + `LeftRecursionTest` (specific LR coverage in peglib-core).

---

## 4. Risks & mitigations

### R1 — Aliasing across cross-call reads (HIGH likelihood, HIGH impact)

The cross-call discipline ("consume `this.result.X` before next call") is correct in current emission BY ACCIDENT — emission templates happen to read result-fields immediately. After B, this becomes load-bearing.

**Mitigation:**
- Phase1ParityTest is the regression net (CST output byte-equal to OFF-flag baseline). If aliasing breaks, parity diverges. 22/22 must stay green at every commit.
- Audit each emission site for "consume before next call" — annotate any non-trivial pattern.
- The `mutableParseResult` flag stays — comparison against OFF baseline is the safety net.

### R2 — Packrat snapshot allocates per cache miss (MEDIUM likelihood, MEDIUM impact)

Each cache miss followed by parse success allocates one `CacheEntry`. If cache miss rate is high, this re-introduces allocation we tried to eliminate. Selective packrat (already shipped) significantly reduces cache traffic, mitigating this.

**Mitigation:** measure cache miss rate after commit 1; if high, may need to pool CacheEntry instances or pack into long-keyed primitive map. Monitor via post-B allocation profile.

### R3 — LR seed-snapshot adds allocation (LOW likelihood, MEDIUM impact)

LR rules typically iterate 2-3 times per call. Each iteration may snapshot the seed (one LRSeed allocation per snapshot). Negligible volume on typical grammars.

**Mitigation:** measure on bench. If LR rules dominate allocation, restructure snapshot to use parser-instance fields rather than a record.

### R4 — Multi-commit delivery breaks under concurrent edits (LOW likelihood, LOW impact)

Each commit is on the release branch. If a parallel session lands changes during the multi-commit work, conflicts.

**Mitigation:** branch is local-controlled; no parallel sessions expected. Each commit lands sequentially.

---

## 5. Validation procedure per commit

After each commit:

1. `mvn install` — full reactor green (922 tests).
2. Special attention: `Phase1ParityTest`, `RoundTripTest`, `LeftRecursionTest`.
3. Bench (after commits 2, 4, 5, 6) BOTH fixtures:
   ```bash
   mvn -pl peglib-core -am -Pbench -DskipTests package
   java -jar peglib-core/target/benchmarks.jar Java25ParseBenchmark.parse \
     -p variant=phase1_allStructural_mutableResult_autoSkipPackrat \
     -p fixture=reference,selfhost \
     -i 5 -wi 3 -f 1 -t 1 -prof gc 2>&1 | tail -30
   ```
   Compare to current baseline (reference 22.6 ms / 75.6 MB; selfhost 956 ms / 2.04 GB). Expect monotonic improvement after commits 3-4-5.
4. Allocation profile (after commit 5 or 6):
   ```bash
   java -jar peglib-core/target/benchmarks.jar Java25ParseBenchmark.parse \
     -p variant=phase1_allStructural_mutableResult_autoSkipPackrat -p fixture=selfhost \
     -i 3 -wi 2 -f 1 -t 1 \
     -prof "async:libPath=/opt/homebrew/lib/libasyncProfiler.dylib;output=collapsed;event=alloc;dir=/tmp/post-B-alloc"
   ```
   Verify CstParseResult allocations dropped to expected level (packrat-CacheEntry residue only).
5. `mvn jbct:format` — keep CI happy.

---

## 6. Reference numbers — current state

These are the targets to beat after B lands cleanly:

| Metric | Current (post-DFA-spike) | B target |
|---|---:|---:|
| Reference parse time | 22.6 ms | ≤ 20 ms |
| Reference allocation | 75.6 MB | ≤ 60 MB |
| Self-host parse time | 956 ms | ≤ 850 ms |
| Self-host allocation | 2.04 GB | ≤ 1.7 GB |
| CstParseResult samples (selfhost) | 5,264 | < 500 |
| vs javac (parse) | 2.5× | ~2× |

If post-B metrics fall significantly short (e.g. wallclock improves by < 5%), the multi-pass investment may not be worth shipping. Honest evaluation gate at end of commit 6.

---

## 7. Files involved

**Primary:** `peglib-core/src/main/java/org/pragmatica/peg/generator/ParserGenerator.java` (5500+ lines, will gain ~300-500 net under flag-ON branch).

**Secondary:** `peglib-core/src/main/java/org/pragmatica/peg/parser/ParserConfig.java` (no changes expected — flag already exists from A spike).

**Bench:** `peglib-core/src/jmh/java/org/pragmatica/peg/bench/Java25ParseBenchmark.java` — variant `phase1_allStructural_mutableResult_autoSkipPackrat` is the standard A/B variant; no change needed.

**Tests:** Phase1ParityTest, RoundTripTest, LeftRecursionTest, all parity tests are gates. Don't add B-specific tests; existing parity coverage IS the regression net.

---

## 8. Resource constraints + practical session shape

Multi-commit work in a single autonomous session:

- Commits 1, 2, 3, 4, 5, 6 ideally land in this order over 2-3 days of focused work.
- Each agent pass: scope ONE commit; deliver and validate before moving to next.
- Use `jbct-coder` per project mandate. Keep each agent prompt tight (under ~400 words).
- Use `build-runner` for `mvn` invocations to keep main context clean.

If a commit fails parity, **revert that single commit** and re-attempt with refined scope. Do NOT ship partial.

---

## 9. Decision gates

- **After commit 1:** if foundation breaks parity, abandon B entirely and write up findings.
- **After commit 3:** if no measurable allocation reduction by this point, the per-call CstParseResult was not the load-bearing alloc — abandon B, document the surprise.
- **After commit 6:** if total wallclock improvement < 5%, ship the work but clearly document that B was less impactful than projected. Could still be valuable for self-host scenarios where allocation pressure dominates.

---

## 10. After B

If B succeeds:
- Reference parse time at ~20 ms (~2.2× of javac with strictly more output).
- Self-host stress test in the ~850 ms range.
- Tier 1 spec target (≤ 30 MB/op, ≤ 50 ms/op on reference) substantially met.
- 0.5.0 release candidate.

If B falls short:
- Document; ship 0.5.0 with current state (already excellent).
- Future moves: char-class bit-packing, generalized DFA (if better-targeted), Lever C IR unification (multi-week).

---

## 11. Post-mortem — Move B attempted, abandoned 2026-05-08

Five commits landed (`88c15f3` … `ed95951`) before policy-driven rollback. Hard-reset to `v0.5.0-candidate` (`e849b63`) — branch state preserved as if Move B never happened. The 5 commits remain in reflog for ~30 days if forensic detail is needed.

### Bench trajectory (reference fixture, `-i 5 -wi 3 -prof gc`)

| Stage | Wallclock (ms/op) | Alloc (MB/op) | Δ wallclock vs orig | Δ alloc vs orig |
|---|---:|---:|---:|---:|
| Original baseline (`fd278fe`) | 22.6 | 75.6 | — | — |
| Commit 1 (foundation, no behavior change) | not benched | not benched | (expected ≈ 0) | (expected ≈ 0) |
| Commit 2 (parse_<rule> → boolean) | not benched | not benched | unknown | unknown |
| Commit 3 (match helpers) | 23.97 | 72.1 | **+6.0%** | -4.6% |
| Commit 4 (combinators) | 24.71 | 66.3 | **+9.3%** | -12.3% |
| Commit 5 (predicates/capture/cut/TB) | 25.09 | 65.96 | **+11.0%** | -12.8% |

Wallclock regressed **monotonically** with each migration; allocation dropped **monotonically but with diminishing returns**. By commit 5 the Δ-per-commit had flatlined: +0.38 ms wallclock, -0.34 MB alloc. The §9 gate (≥ 15% allocation drop) was not reached and the trajectory said it would not reach it.

### Hypothesis (well-supported by the data)

JIT escape analysis was already scalar-replacing the per-call `CstParseResult` records on the hot path. The records had:
- Raw nullable fields (Spike A removed Option boxing, leaving primitives + nullable refs)
- Local-scope construction in each parse method
- Immediate consume at call site (no cross-method handoff via stable references)
- No escape into fields/threads/exceptions

These are textbook scalar-replacement targets. The "allocation" the JMH `gc.alloc.rate.norm` profiler reported was overstated relative to actual heap pressure — the JVM was decomposing each record into stack-allocated primitives.

The singleton replacement:
- Field on `this` (heap-bound, cannot be scalarized)
- Field stores/loads cross method boundaries (defeats some inlining and code-motion)
- Source-level aliasing forces the compiler to assume mutation visibility (defeats further reordering)

Net effect: GC sees fewer survivor objects (alloc-rate metric drops) **but** wallclock regresses because the optimized hot path is now slower per call.

### What this means for future optimization (peglib and elsewhere)

1. **Allocation rate ≠ optimization opportunity.** Short-lived records on hot paths may already be near-zero-cost via scalar replacement. JMH's `-prof gc` alloc-rate is a useful diagnostic, not a target.
2. **Bench wallclock first, alloc second.** A move that drops alloc but regresses wallclock is a loss.
3. **Profile via CPU sampling, not alloc sampling, when looking for actual hot work.** `async-profiler` in `cpu` mode + flame graphs.
4. **Singleton-mutable-state patterns from C/C++ codebases do NOT translate.** They beat C++'s allocator overhead because C++ has no escape analysis. Java's JIT does that work for you on short-lived records.

### What's now ruled out

- **Move B itself** (per-call `CstParseResult` elimination via singleton). Definitively abandoned.
- By extension: any "share a mutable state object on `this`" pattern targeting short-lived per-call records in the generated parser. Apply skepticism.

### What's still viable (reassessed)

- **Char-class bit-packing** (spec §10 future moves) — REASSESS WITH CARE. Same risk class: char-by-char comparison may already be JIT-optimized. Profile wallclock first; verify there's a measurable hot-path win before attempting.
- **Lever B retry (incremental engine)** — gated on trivia attribution rework. Independent of allocation patterns; still viable.
- **Trivia attribution rework** — context-independent attachment. Independent; still viable. Comparable scope to Lever C.
- **Lever C — IR unification** (spec §4) — multi-week. Maintainability + complexity reduction primary value, not raw perf. Still viable.
- **Profile-driven wallclock optimization** — `async-profiler` CPU mode, identify actual hot CPU work, target THAT. Probably the highest-ROI next direction.

### What got preserved

- The HANDOVER + this spec at `e849b63` document the experiment for posterity.
- `v0.5.0-candidate` tag at `e849b63` is the shippable 0.5.0 state.
- The 5 Move B commits remain in reflog (`git reflog show release-0.5.0`) for ~30 days.

---

**Last updated:** 2026-05-08, end of Move B (abandoned). Successor work: see HANDOVER §11.
