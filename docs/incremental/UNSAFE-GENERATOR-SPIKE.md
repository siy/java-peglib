# UNSAFE-GENERATOR-SPIKE — boolean-returning + parser-state generator emission

Branch: `release-0.4.2` · Status: design (Phase 1) · Author: investigation pass

---

## 200-word summary

The generated CST parser allocates one `CstParseResult` (success or failure)
per parse-method call and per intermediate combinator step. The
post-Phase-1+P3 flame graph attributes ~27% of de-noised allocations to those
factories (25% success + 1.84% failure), and 75% of CPU samples to G1
evacuation, so allocation reduction is the dominant lever left.

Proposal: replace `CstParseResult` with two booleans returned from
`parse_<rule>()` and helper combinators (`isSuccess`, `isCutFailure`) plus
mutable parser-class state fields (`lastNode`, `lastText`, `lastEndOffset`,
`lastEndLine`, `lastEndColumn`, `lastExpected`). Callers capture state into
locals immediately after each call, before issuing the next. The packrat
cache becomes a parallel `long[]`-backed structure of primitive `endOffset`,
`text` reference, and `node` reference per key.

**Scope decision:** BASIC + CST-only (mirrors P3.2). ADVANCED and action
mode are out of scope for the spike but the design is forward-compatible.

**Top 3 risks:** (1) trivia-attribution code paths (Bugs A–C'') depend on
the cache hit path returning the *same* immutable result that was put — a
mutable cache layout must replicate that semantic. (2) Cut-failure
propagation through nested combinators is currently expressed via
`asCutFailure`/`asRegularFailure`, which mutate one bit on a fresh object;
the new design needs an explicit `cutFailed` field that is *not* clobbered
by intermediate combinators that succeed in between. (3) The literal /
char-class failure caches (`literalFailureCache`, `charClassFailureCache`)
exist *because* result objects are reused; the new design eliminates the
need for that cache entirely, which simplifies one path but invalidates
existing micro-benchmarks of those flags.

**Recommended next step:** prototype on `Reference`, `Sequence`, `Choice`
emission only (3 of ~13 patterns) behind a `--unsafe-state` ParserConfig
flag, gate with all 565 tests, and re-measure flame graph before expanding
to remaining patterns.

---

## 1. Goal + projected gain

Replace per-call `CstParseResult` allocation with `boolean`-returning parse
methods backed by mutable parser-class state. Empirical justification (see
`docs/bench-results/`):

- `CstParseResult.success`: **~25%** of de-noised allocations
- `CstParseResult.failure`: **~1.84%**
- Combined: **~27%** allocation pressure relief
- Wall-clock projection: 76–79 ms → 55–65 ms on the 1900-LOC Java25
  fixture (pending verification)

Currently the generated parser holds a packrat cache of `CstParseResult`
objects keyed by `cacheKey(ruleId, offset)` (long-packed). Cache hits
return the cached object verbatim, which is the *only* path where a result
is reused. Every other call allocates fresh.

---

## 2. Current emission map

Line ranges below refer to
`peglib-core/src/main/java/org/pragmatica/peg/generator/ParserGenerator.java`
on `release-0.4.2`. The CST emission path is `generateCstRuleMethod` at
2494, `emitCstLeftRecursiveWrapper` at 2680, `generateCstRuleBodyMethod`
at 2846, `generateCstExpressionCode` at 2990, and `emitCstChoiceAlt` at
4264.

### 2.1 The CstParseResult class itself (4709–4749)

Inner class of the generated parser. Fields:

| Field | Type | Purpose |
|-------|------|---------|
| `success` | `boolean` | success/failure flag |
| `node` | `Option<CstNode>` | result node when success (may also be empty success — predicates, ignore, sequence intermediate) |
| `text` | `Option<String>` | matched literal text (TokenBoundary, Literal, CharClass, Any, Reference settle) |
| `expected` | `Option<String>` | failure reason (`%expected`, `%error`, generic) |
| `endLocation` | `Option<SourceLocation>` | post-match cursor (for cache replay; LR seed compares offsets) |
| `cutFailed` | `boolean` | cut barrier — propagates past Choice |

Factories: `success(node, text, endLocation)`, `failure(expected)`,
`cutFailure(expected)`, plus mutators-by-copy `asCutFailure()` /
`asRegularFailure()`.

Accessors: `isSuccess()`, `isFailure()`, `isCutFailure()`.

### 2.2 Emission sites grouped by pattern

| # | Pattern | Lines | Allocation form |
|---|---------|-------|-----------------|
| A | Rule method success-return (non-LR) | 2628, 2629 | `CstParseResult.success(node, text, endLoc)` ×2 (cacheable + final) |
| B | Rule method failure-return (non-LR) | 2644, 2654, 2659 | `CstParseResult.failure(expected)` or pass-through |
| C | Rule method cache-hit success rebuild | 2551 | `CstParseResult.success(hitNode, cached.text.or(""), hitEndLoc)` |
| D | LR wrapper success-return | 2790 | `CstParseResult.success(node, ...)` |
| E | LR wrapper failure / cut-fired | 2808, 2817, 2822, 2826 | `CstParseResult.failure` / `CstParseResult.cutFailure` |
| F | LR wrapper seed init | 2741 | `CstParseResult.failure("left-recursion seed")` |
| G | LR body return success | 2868 | `CstParseResult.success(node, text, endLoc)` |
| H | Sequence init / settle | 3134, 3248 | `CstParseResult.success(null, "", location())` (init), `success(null, substring(...), location())` (settle) |
| I | Choice init / all-fail | 3266, 3331 | `null` init, `CstParseResult.failure("one of alternatives")` settle |
| J | Choice alt classification | 4286, 4295, 4302 (asRegularFailure) | `asRegularFailure()` |
| K | Sequence elem cut promotion | 3226 | `asCutFailure()` |
| L | ZeroOrMore init / settle | 3343, 3458, 3480 | `success(null, "", location())` init, `success(null, substring(...), location())` settle |
| M | OneOrMore init / pass-through | 3514 | `CstParseResult resultVar = oomFirst;` |
| N | Optional empty / cut-pass | 3680 (decl), 3758 | `CstParseResult resultVar;` decl, settle `success(null, "", location())` |
| O | Repetition init / settle | 3862 (decl), 3930, 3950 | `success(null, substring(...), location())` settle, `failure("at least N reps")` |
| P | And / Not predicate ternary | 3997, 4041 | `andElem.isSuccess() ? CstParseResult.success(null, "", location()) : andElem` |
| Q | TokenBoundary success | 4112 | `CstParseResult.success(tbNode_X, tbText_X, location())` |
| R | Ignore ternary | 4151 | same shape as P |
| S | BackReference | 4224 | `matchLiteralCst(captured, false)` (returns CstParseResult) or `failure("capture '")` |
| T | Cut | 4244 | `CstParseResult.success(null, "", location())` |
| U | Literal/CharClass/Any helpers | 4762, 4862, 4899, 4963 (and inner returns) | many `success(node, text, endLoc)` and `failure(...)` |
| V | Failure caches (literal, charClass) | 2060, 2065, 4843, 4942 | reuses `CstParseResult` instances stored in `Map<String, CstParseResult>` |

The cache itself: `Map<Long, CstParseResult> cache` (1984), and growing
seed `Map<Long, CstParseResult> growingSeeds` (1990).

### 2.3 Field-access sites in emission

The emission also reads cached results: `cached.isSuccess()`,
`cached.endLocation.unwrap()`, `cached.node.unwrap()`, `cached.text.or("")`
at 2544–2553 (rule method) and 2710–2722 (LR wrapper). The active-seed path
at 2728–2732 reads `activeSeed.endLocation.unwrap()`. The settle path at
2763–2764 reads `iter.endLocation.unwrap().offset()` and
`lastSeed.endLocation.unwrap().offset()`. The wrapper at line 4526 reads
`result.node` to re-wrap via `wrapWithRuleName`.

---

## 3. Proposed parser-class state fields

All fields live on the generated parser class as primitive or single-ref
fields. Naming convention: `last...` for the result of the most recent
parse call, `cutFailed` as an orthogonal failure-flavor flag.

| Field | Type | Purpose |
|-------|------|---------|
| `lastSuccess` | `boolean` | true if the last `parse_X()` / inner-combinator call succeeded |
| `lastCutFailed` | `boolean` | true if the last failure was a cut failure (only meaningful when `!lastSuccess`) |
| `lastNode` | `CstNode` (nullable) | success-only; null for empty-success (Sequence init, predicates, Cut, Ignore, ZoM-non-children) |
| `lastText` | `String` | matched text or `""` for empty-success; intentionally never null in success |
| `lastEndOffset` | `int` | end cursor offset on success (replaces `endLocation.offset()`) |
| `lastEndLine` | `int` | end cursor line on success (only materialized when needed for SourceSpan) |
| `lastEndColumn` | `int` | end cursor column on success |
| `lastExpected` | `String` | failure reason; never null on failure |

`lastEndLine` / `lastEndColumn` exist because some callers
(`SourceSpan.sourceSpan(startLoc, endLoc)`) need a full `SourceLocation`,
not just offset. With `inlineLocations=true` (default after P3), most
callers already work with int triples; we extend that to the result.

### 3.1 Cache layout

Replace `Map<Long, CstParseResult>` with parallel arrays / a small record
holding primitives + refs. Two viable shapes:

**Option α (record):**
```java
private record CacheEntry(
    boolean success, boolean cutFailed,
    CstNode node, String text, String expected,
    int endOffset, int endLine, int endColumn) {}
private Map<Long, CacheEntry> cache;
```
Trades one allocation for another, but `CacheEntry` is created once per
cache miss; the win is per-call (non-cached) emission, not the cache.

**Option β (parallel `long[]` + `Object[]`):**
Avoid the record allocation too. Higher complexity; deferred.

Recommended for spike: **Option α**. CacheEntry construction happens once
per (rule, offset) — typical Java25 fixture sees ~5–8% cache miss rate per
profile, so this is ~2–4% of total allocations, dwarfed by the per-call
savings.

### 3.2 Failure caches removed

`literalFailureCache` and `charClassFailureCache` (2060, 2065) become dead
weight: they cache *failure* `CstParseResult` instances whose entire
purpose is to avoid the `failure(...)` allocation per call. With booleans,
there is nothing to cache — a failure is just `lastSuccess=false;
lastExpected="..."`. Emission of these caches and their feature flag
(`config.literalFailureCache()`, `config.charClassFailureCache()`) becomes
no-ops in unsafe mode.

---

## 4. State-capture discipline

**Rule:** every parse-call invalidates the `last*` fields. Callers must
capture into locals **immediately** after the call, before any other call.

### 4.1 Comment template (emit at top of every combinator block)

```java
// UNSAFE state: capture last* fields now — any nested parse call will
// clobber them. Locals: localSuccess, localCutFailed, localNode, ...
```

### 4.2 Patterns where this matters

1. **Sequence between elements** (3158–3239): after each
   `generateCstExpressionCode` for `elem`, the caller must capture
   `localSuccess_i = lastSuccess; localCutFailed_i = lastCutFailed;
   localNode_i = lastNode; localText_i = lastText; localEndOff_i =
   lastEndOffset;` before the next iteration's element-call. The current
   emission stores `elem<id>_<i>` as a `CstParseResult` ref, capturing
   everything in one ref-write. The new emission needs ≤6 int/ref writes.

2. **Choice between alternatives** (4264–4318): each alt emits a parse
   call followed by `if (lastSuccess) ... else if (lastCutFailed) ...`.
   The check itself must read `lastSuccess` first into a local because the
   `else if` body may itself call `restoreLocation(...)` which doesn't
   call into a parser method but is safe; however the decision branches
   should not re-call any parse method between flag reads.

3. **ZeroOrMore between iterations** (3358–3411): after the inner element
   call, the loop reads `lastSuccess` / `lastCutFailed` / `lastEndOffset`.
   Capture once per iteration.

4. **Optional / Repetition** (3649–3760, 3762–3954): same as ZoM.

5. **And / Not / TokenBoundary** (3956–4126): predicate emits
   `andElem.isSuccess() ? ...` — capture `lastSuccess` into
   `localAndSuccess` immediately, then evaluate the rest after
   `restoreLocation` and trivia restore.

6. **TokenBoundary success** (4112): builds `tbNode<id>` from
   `substring(tbStart.offset(), pos)` and the new TokenBoundary span.
   The `tbElem` call's text is *not* used here (text comes from
   substring) — fewer locals needed. But we must keep `lastSuccess`
   captured because `appendPending(...)` and `takePendingLeading()` calls
   between read and use are safe (they don't reset `last*`).

7. **Action sites** (out of scope for spike, but noted): the inline
   action receives `sv.token()` (text), `sv.get(i)` (semantic value of
   child i). For action mode, `lastValue` must also become a parser-class
   field, with the same capture discipline in Sequence between elements.

### 4.3 Helper methods (`matchLiteralCst`, `matchCharClassCst`, `matchAnyCst`)

These helpers also write `last*` fields and return `boolean`. The wrap
helper `wrapWithRuleName(CstParseResult, ...)` (4526) currently takes the
result; the new signature reads `lastNode` directly or takes a `CstNode`
parameter.

---

## 5. Trivia attribution

Per `docs/TRIVIA-ATTRIBUTION.md` and HANDOVER §5, three trivia bugs were
fixed in 0.3.5: pendingLeading rollback on Choice/Sequence backtrack
(Bug A), cache-hit leading-trivia rebuild (Bug B/B'), and rule-exit
trailing-trivia attribution (Bug C/C'/C''). Two are sensitive to result
*identity*:

### 5.1 Bug B (cache-hit leading-trivia rebuild) — 2544–2551

Cache hit calls `attachLeadingTrivia(cached.node.unwrap(), hitLeading)`
to rebuild the leading trivia at the hit site, then constructs a *fresh*
`CstParseResult.success(hitNode, cached.text.or(""), hitEndLoc)`. The
cached object is *not* returned — it stays in the cache with its
original (empty-leading) node. **This is critical:** the cache stores
the empty-leading node so subsequent hits don't accumulate stale
trivia.

In the new design, `CacheEntry` (Option α) stores empty-leading node
(per Bug C fix at line 2617: `var cacheNode = wrapWithRuleName(result,
children, span, ruleIdConst, List.<Trivia>of());`). On hit, we call
`attachLeadingTrivia(entry.node, hitLeading)` and write the *attached*
node to `lastNode`, then write `lastEndOffset = entry.endOffset` etc.
The cache entry remains immutable. **No trivia-semantic regression.**

### 5.2 Bug C/C'/C'' (rule-exit trailing-trivia attribution) — 2613–2627

After a successful body parse, the rule reads `pendingAtExit =
takePendingLeading()` and reattaches it via `attachTrailingToTail` on
both `cacheNode` and `node`. This logic is independent of result
representation — it operates on `CstNode` references, not result
objects. **No change.**

### 5.3 Bug A (Choice/Sequence backtrack) — 3141, 3274, 3325, 3683, 3713

The Choice and Sequence emission saves `pendingLeadingTrivia` snapshots
and restores them on alt-failure / sequence-failure. This is also
node-level, not result-level. **No change.**

### 5.4 Verdict

Trivia attribution does **not** depend on `CstParseResult` immutability
beyond the cache-hit fresh-result rebuild, which the new design
preserves by allocating only on cache hits (rare) and reading
immutable `CacheEntry` fields. The rest of trivia handling operates
on parser-class trivia buffers (`pendingLeadingTrivia`) and `CstNode`
refs.

---

## 6. Cut-failure propagation strategy

`cutFailed` is an orthogonal flag on top of `!success`. The simplest
correct mechanism: **dedicate one parser-class boolean field
`lastCutFailed`** plus a discipline that callers convert on demand.

### 6.1 Promotion / demotion sites

- `asCutFailure()` (4742): emission at 3226 — Sequence *promotes* a
  regular elem-failure to cut-failure when a previous element was a
  Cut. New emission: `if (cutVar) { lastCutFailed = true; }` after
  reading the elem result.

- `asRegularFailure()` (4746): emission at 4302 — Choice *demotes* a
  cut-failure inside an alt to a regular failure (so the outer Choice
  can still try the next alt — but only because cut-fired causes the
  Choice itself to abort, while inside the alt cut-failure already
  bubbled past). Wait: the comment at 4296 says "Convert CutFailure to
  regular failure so enclosing choices can still fail-forward". Reading
  the actual logic: if the alt cut-failed, `resultVar = altVar` is set
  to a *regular* failure (so the enclosing Choice settle treats this
  Choice as plain failure)?

  Actually, re-reading lines 4292–4302: when an alt cut-fails, the
  Choice sets `resultVar = altVar.asRegularFailure()`. This makes the
  Choice as a whole *fail* (since `resultVar` is now non-null and
  non-success), and **does NOT try further alts** because of the
  `else if (altVar.isCutFailure())` branch — control falls past the
  remaining alts in the chain. The `asRegularFailure()` strips the cut
  bit so the *outer* Choice sees a regular failure (cuts don't
  propagate past the Choice that contained them — that's the cut
  semantics).

  New emission for this case:
  ```java
  } else if (localCutFailed_X) {
      lastSuccess = false;
      lastCutFailed = false;       // demote
      lastExpected = localExpected_X;
      // resultVar bookkeeping: choice settled on this alt
      choiceResolved = true;        // sentinel local replacing resultVar != null
  }
  ```

- ZeroOrMore / OneOrMore / Repetition cut-propagation (3382, 3601,
  3826): when the inner element cut-fails, the loop breaks and the
  outer combinator forwards the cut. New emission: read
  `localCutFailed = lastCutFailed;` after the inner call; if true,
  set `lastSuccess = false; lastCutFailed = true;` and break.

- Optional cut-propagation (3686): same shape — Optional must NOT
  swallow a cut into success.

### 6.2 Sentinel for "Choice not yet resolved"

Currently Choice uses `resultVar = null` as the sentinel for "no alt
matched yet". New emission needs an equivalent local, since `lastSuccess`
is overwritten by each alt call:

```java
boolean choiceResolved<id> = false;
... per alt ...
if (lastSuccess) {
    choiceResolved<id> = true;
    // capture node/text/endOff into local for restoring after subsequent code
} else if (lastCutFailed) {
    choiceResolved<id> = true;
    lastSuccess = false; lastCutFailed = false;  // demote
    lastExpected = localExpected_alt;
} else {
    restoreLocation(choiceStart);
    restorePendingLeading(choicePending<id>);
}
... settle ...
if (!choiceResolved<id>) {
    lastSuccess = false; lastCutFailed = false;
    lastExpected = "one of alternatives";
}
```

---

## 7. Scope for the spike

### Included
- BASIC error reporting only (no ADVANCED diagnostics path)
- CST mode only (`generateCst`)
- Non-LR rule emission (`generateCstRuleMethod`)
- LR rule emission (`emitCstLeftRecursiveWrapper`,
  `generateCstRuleBodyMethod`) — needed for grammar coverage
- All combinator patterns A–U from §2.2
- Cache layout: `CacheEntry` record (Option α)
- Match helpers: `matchLiteralCst`, `matchCharClassCst`, `matchAnyCst`,
  `matchDictionaryCst`

### Deferred
- ADVANCED error reporting (parseWithDiagnostics, recovery overrides,
  Error nodes). The recovery-override emission at 2570–2592 / 2747–2778
  reads `result.isSuccess()` — would need a flag-aware variant. Scope to
  v0.4.3.
- Action mode (`generateRuleMethod` at 381, `generateExpressionCode` at
  470 — separate emission path returning `ParseResult`, not
  `CstParseResult`). Same transformation applies but doubles the
  emission churn. Scope to v0.4.3.
- AST mode is just `parseAst = parse.map(toAst)` — no emission change.
- Failure-cache flags (`literalFailureCache`, `charClassFailureCache`)
  become moot under unsafe mode. The emission should silently ignore
  them when the unsafe flag is on.

### Gating
- New `ParserConfig` flag `unsafeStateEmission()` (default false).
- All 565 tests + bench fixture must pass with the flag on.
- Compare wall-clock against current generator on `Java25Grammar`
  fixture using `BenchmarkRunner`.

---

## 8. Risk register

### R1 — Trivia attribution regression on cache hit (HIGH likelihood, HIGH impact)

The cache-hit path is the *only* place where the new design still
allocates (one `CacheEntry` write per miss is fine; reading on hit
must reproduce the empty-leading-node + skipWhitespace + reattach
sequence). Bug B's fix is 8 lines of ordering-sensitive logic at
2544–2551. If `attachLeadingTrivia` is called on a cached node that
*already* carries leading trivia (because we mistakenly stored a
trivia-attached node), trivia accumulates per cache hit.

**Mitigation:** assert in `CacheEntry` construction that
`node.leadingTrivia().isEmpty()`. Run the round-trip corpus (the one
RoundTripTest skipped test gives partial coverage; 565 tests via
TriviaTest + GeneratedParserTriviaTest cover the rest).

### R2 — Cut-failure leakage across siblings (HIGH likelihood, HIGH impact)

Currently `asCutFailure` / `asRegularFailure` produce *new* objects so
the original is unchanged. With shared mutable state, if a sibling
combinator reads `lastCutFailed` after a successful intermediate call,
the bit could be stale (if not properly reset on success). The current
emission resets `cutFailed=false` implicitly via `success(...)` factory.

**Mitigation:** every success-path write must be paired:
```java
lastSuccess = true;
lastCutFailed = false;          // reset
lastNode = ...; lastText = ...; lastEndOffset = ...;
```
Add an emission helper `emitSuccessAssign(sb, pad, node, text)` that
writes all five fields atomically and is the *only* way to set
`lastSuccess = true` in emitted code. Add a same-shape
`emitFailureAssign(sb, pad, expected, cut)`. Verify by greps over the
generated source.

### R3 — Action mode divergence makes maintenance painful (MEDIUM likelihood, MEDIUM impact)

Two emission paths (CST + actions) already exist in parallel; adding
a third (CST-unsafe) triples emission surface area. If unsafe mode
proves the gain, action mode follows; if not, we have dead code.

**Mitigation:** keep unsafe mode behind a config flag and gate
*emission* sites with the flag, not via parallel methods. After
verification, deprecate the safe CST path and delete it (one-step
migration, not three modes long-term). Plan flag removal in v0.5.0.

### R4 — Match-helper reuse across non-cached call sites (MEDIUM likelihood, LOW impact)

`matchLiteralCst` is called from BackReference (4220) and from rule
bodies via direct emission of `Literal` (3000+) — the latter inlines,
the former calls. Currently both return `CstParseResult`; new design
returns `boolean` and writes `last*`. BackReference at 4220–4238 reads
`resultVar.isSuccess()` and `resultVar.node`. New emission reads
`lastSuccess` and `lastNode` directly. Straightforward but two
patterns must agree on field semantics — particularly `lastNode` must
be set even for inline `Literal` since BackReference adds it to
children.

**Mitigation:** unify on the rule that *every* successful match writes
`lastNode` (possibly to a `Terminal` node for inline Literal/CharClass/
Any). Today inline emission for Literal at 3009 also constructs a
`CstNode.Terminal` and adds to children — that emission stays
identical except the result-allocation line.

### R5 — Generator churn breaks downstream peglib-formatter / -incremental / -maven-plugin (LOW likelihood, MEDIUM impact)

Those modules consume the generator output for their own parsers.
Compile-fail at consumer sites is loud and recoverable; correctness
regression is silent.

**Mitigation:** run `mvn verify` across all modules before merging the
spike. The CHANGELOG plus a config-flag default-false rollout means
consumers opt in.

### R6 — Verification cost (KNOWN, LOW impact)

Reading generated parser diff for 4 grammars (Java25, Calculator,
JSON, S-Expr) is mechanical but tedious. Diff drift between safe and
unsafe emission must be byte-identical when flag is off; this is the
existing test discipline and should hold.

**Mitigation:** byte-equality test on small grammars under
`unsafeStateEmission(false)` vs trunk. Add a generator
golden-file test for one small grammar.

---

## 9. Implementation roadmap (Phase 2)

1. Add `ParserConfig.unsafeStateEmission` flag, default false.
2. Write the field declarations in `generateCstParseContext` (1976+)
   guarded by the flag.
3. Replace `CstParseResult` inner-class emission (4707–4750) with a
   no-op when flag is on; keep the class for action-mode and
   off-flag CST mode.
4. Add `CacheEntry` record emission (when flag is on).
5. Replace pattern A (rule method success-return) only. Run all 565
   tests. Read generated `Java25Parser.java` diff. Bench.
6. Pattern B (rule method failure-return). Tests + bench.
7. Pattern C (cache-hit rebuild). Tests + bench.
8. Patterns D–G (LR). Tests.
9. Patterns H–T (combinators) in dependency order: H (Sequence) ↔ I
   (Choice) ↔ J (alt classification) ↔ K (cut promotion) together
   (these interlock); then L–O (repetitions); then P–T (predicates,
   token boundary, capture, cut).
10. Pattern U (match helpers).
11. Remove pattern V (failure caches) emission when flag is on.
12. Default the flag to true. Run full bench. Document gain in
    `docs/bench-results/`.
13. Remove safe-mode CST emission in v0.5.0 if gain holds.
