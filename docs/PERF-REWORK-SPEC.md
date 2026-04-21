# java-peglib performance rework — detailed spec

**Target repo:** `java-peglib` (self-contained; no dependency on any other repo).
**Status:** proposal / spec for an implementing agent. Not yet executed.
**Scope:** optimizations to the PEG code generator (`ParserGenerator`) only.
`PegEngine` (the interpreter) is **out of scope** for this pass.

---

## 1. Context

`ParserGenerator` (`src/main/java/org/pragmatica/peg/generator/ParserGenerator.java`,
~3,600 LOC) emits self-contained Java parsers from a `Grammar` IR.

The Java 25 grammar (`src/test/resources/java25.peg`) exercised by
`Java25GrammarExample` / `Java25ParserTest` produces a ~25,000-line parser. A
1,900-LOC real-world Java fixture
(`src/test/resources/perf-corpus/large/FactoryClassGenerator.java.txt`) takes
**~700 ms** to parse with the current generator output. Packrat memoization is
already implemented, on by default, and works correctly — but the cache has a
~10% hit rate on this workload, so packrat alone does not explain (or solve)
the bulk of the cost.

A prototype hand-edit of the generator's output (not this repo; validated
offline) demonstrated **~1.9×** end-to-end speedup from a set of focused
low-risk changes in the emitted helpers (`matchLiteralCst`, `matchCharClassCst`,
`trackFailure`, `skipWhitespace`, success paths). The goal of this work is to
port those changes into the generator itself (so every emitted parser benefits)
and to add a second tier of structural changes that require generator-level
work.

---

## 2. Objectives

1. **Performance:** ≥1.5× reduction in wall-clock parse time for the
   `FactoryClassGenerator.java.txt` fixture, measured via JMH with settings
   documented in §8. Stretch: ≥2×.
2. **Correctness:** emitted parsers produce **bit-identical CST** to the
   current generator on every file in `src/test/resources/perf-corpus/`.
   Verification infrastructure is part of the deliverable (§4).
3. **Incrementality:** every optimization is **individually toggleable via
   `ParserConfig`** (default on once validated). A regression from any single
   optimization can be bisected by flipping one flag.
4. **No grammar changes.** The grammar file stays untouched; only generated
   parser code changes.
5. **No public-API breakage.** Existing users of `PegParser` / emitted parsers
   must continue to work without source changes.

---

## 3. Corpus

Lives under `src/test/resources/perf-corpus/`:

| Directory | Content | Purpose |
|---|---|---|
| `format-examples/` | 17 Java files (one per language feature: `Annotations`, `Enums`, `Lambdas`, `Records`, `SwitchExpressions`, `TextBlocks`, …) | Feature coverage (imported from the formatter's curated golden set) |
| `flow-format-examples/` | 1 file (`BlankLineRules.java`) | Blank-line / trivia edge cases |
| `large/FactoryClassGenerator.java.txt` | 1,900-LOC real production file | Benchmark fixture + larger-scale smoke |

**Coverage expectation:** at least 90% of grammar rules should fire on this
corpus. Verify with a rule-hit histogram as the first deliverable (§4.1). If
coverage is below threshold, add gap-filler files rather than lower the bar.

**Candidate gap fillers** (add only if histogram shows they're needed):
- `module-info.java` (module directives: `requires`, `exports`, `opens`, `provides`)
- Deeply nested generics: `Map<String, List<? extends Comparable<? super Integer>>>`
- Exhaustive `switch` with record patterns + guards + deconstruction

---

## 4. Equivalence / verification plan

This is the backbone of the rework. **Do this first**, before touching
`ParserGenerator`.

### 4.1  Structural-hash baseline

Write a test utility `CstHash` that walks a `CstNode` in deterministic pre-order
and mixes into a hash:

- Rule name (or ordinal)
- `SourceSpan` start offset
- `SourceSpan` end offset
- `Terminal.text()` / `Token.text()` for leaves
- For `NonTerminal`, the child count, then recurse into each child
- For `Error` nodes: the span + expected + skipped text
- Trivia attached to the node (optional; make it toggleable — see §4.3)

Implement the hash as a `long` accumulator (e.g., fmix64 or XXH3-style mixer)
or a byte-stream fed into SHA-256 — either works; reproducibility and
determinism matter more than speed here.

Add a gradle/maven task (or simple `main`) that, for each file in
`perf-corpus/`, computes the CST hash and writes it to
`src/test/resources/perf-corpus-baseline/<relative-path>.hash`. Check these
files into git on main **before any generator change**. This is the ground
truth the rework must preserve.

Also emit per-file **rule-hit histograms** to
`perf-corpus-baseline/<relative-path>.ruleHits.txt` and an aggregate
`ruleCoverage.txt` summarizing which grammar rules fired on the full corpus
and how often. Use these to verify corpus coverage and to monitor for
unexpected changes (a rule that stops firing after an optimization is a red
flag).

### 4.2  Round-trip equality

CST preserves trivia. Therefore:

    reconstruct(parse(source)).equals(source)

must hold byte-for-byte for every file in the corpus. Implement a
`CstReconstruct` utility that walks the CST in order, concatenating leaf text
and trivia. Run this check in CI on every corpus file. This is a *free*
invariant — it catches whole classes of bugs (dropped tokens, trivia
misattribution, off-by-one span offsets) without needing a baseline artifact.

### 4.3  Per-optimization flag

Extend `ParserConfig` with one boolean per optimization in §6 and §7 (e.g.,
`fastTrackFailure`, `skipWhitespaceFastPath`, `literalFailureCache`,
`charClassFailureCache`, `bulkAdvanceLiteral`, `choiceDispatch`,
`markResetChildren`). The generator honours these flags when emitting code:
with the flag off, it emits the current code path; with the flag on, it emits
the optimized variant.

Defaults:
- Phase 1 (hand-edit ports from §6): default **off** during implementation;
  default **on** once §4.1 and §4.2 verify parity on the corpus.
- Phase 2 (structural, §7): default **off** until each passes §4.1 + §4.2 +
  a measured win.

This is the mechanism for safe, bisectable, measurable rollout.

### 4.4  CI gates

Add the following to the regular test suite (not opt-in):

- **Parity check:** for each file in `perf-corpus/`, parse and compare CST
  hash to the baseline. Fail loudly on any mismatch; include the file path
  and a pretty-printed CST diff (walk both trees, report first divergent
  node).
- **Round-trip check:** parse → reconstruct → equals.
- **Rule-hit floor:** regression guard — if an optimization drops total rule
  hits on the corpus (rule count diverges), something's wrong.
- **Smoke benchmark:** a single-iteration parse of
  `FactoryClassGenerator.java.txt` with a wall-time assertion
  (e.g., `< 2 × baseline`). Not a precise measurement, but catches 10×
  regressions before they land.

### 4.5  Optional: differential fuzz

If phase 2 discovers subtle mis-parses that the corpus misses, add a
property-based test using jqwik (or similar) that generates syntactically
valid Java fragments and asserts identical CST between flag-on and flag-off
code paths. Low priority — add only if something slips through §4.1 + §4.2.

---

## 5. Pre-work

Before any generator edit:

1. Implement §4.1, §4.2, §4.3, §4.4.
2. Run them against the current generator; capture the baseline artifacts.
3. Verify all tests pass. Commit the baseline.

Only then proceed to §6.

---

## 6. Phase 1 — port validated hand-edits

These are small, focused, proven wins from the offline prototype. Each lives
in an emitted helper (not the per-rule `parse_*` bodies), so the generator
change is localized to the runtime-helper emission in `ParserGenerator`.
Expected combined speedup: **~1.9×** (measured on the fixture offline).

Apply them in the order below. Run §4.4 after **each** change. Each is gated
by its own `ParserConfig` flag per §4.3.

### 6.1  `trackFailure` early-exit

**Problem.** The current `trackFailure(String)` helper allocates a
`SourceLocation` via `location()` on *every* call, even when the current
position is behind the furthest-seen failure (which is ~90% of calls during
PEG backtracking). The result is discarded.

**Fix.** Short-circuit when `pos` is strictly less than the furthest offset,
and handle the equality case without allocation. Only allocate when actually
updating the tracker.

**Reference patch (current shape → new shape):**

```java
private void trackFailure(String expected) {
    // Fast path: behind the furthest seen failure — no update, no allocation.
    if (!furthestFailure.isEmpty()) {
        int furthestOffset = furthestFailure.unwrap().offset();
        if (pos < furthestOffset) return;
        if (pos == furthestOffset) {
            String existing = furthestExpected.or("");
            if (existing.contains(expected)) return;
            furthestExpected = Option.some(
                existing.isEmpty() ? expected : existing + " or " + expected);
            return;
        }
    }
    furthestFailure = Option.some(location());
    furthestExpected = Option.some(expected);
}
```

**Where in the generator.** `ParserGenerator` emits `trackFailure` in the
runtime-helper section of the generated parser. Update the emitter template.

**Flag:** `fastTrackFailure`.

**Correctness:** the fast path returns *before* any state mutation when the
incoming position is dominated, and the non-dominated path is behaviourally
identical to the current implementation. Diagnostics quality is unchanged
(same "furthest error" message; same " or "-joined alternatives). §4.2 will
catch any edge case.

### 6.2  `matchLiteralCst` — hoist the quoted message

**Problem.** The emitted `matchLiteralCst` method builds `"'" + text + "'"`
inline at each of three failure branches (length check, case-sensitive
mismatch, case-insensitive mismatch). String concatenation allocates a
transient `StringBuilder` + result `String` on every failure path.

**Fix.** Compute the quoted string once at the top of the method.

**Patch.**

```java
private CstParseResult matchLiteralCst(String text, boolean caseInsensitive) {
    int len = text.length();
    if (input.length() - pos < len) {
        String msg = "'" + text + "'";
        trackFailure(msg);
        return CstParseResult.failure(msg);
    }
    // ... loops below reuse `msg` built once when about to fail
```

Combine with §6.3 and §6.5 below to eliminate the allocation entirely from
the hot failure path.

**Flag:** folded into the same change as §6.5 (`literalFailureCache`).

### 6.3  `matchLiteralCst` — specialize the match loop by `caseInsensitive`

**Problem.** The current loop branches on `caseInsensitive` inside the body:

```java
for (int i = 0; i < len; i++) {
    if (caseInsensitive) {
        if (Character.toLowerCase(text.charAt(i)) != Character.toLowerCase(peek(i))) ...
    } else {
        if (text.charAt(i) != peek(i)) ...
    }
}
```

JIT cannot hoist the `caseInsensitive` check without inlining the whole
method at each call site, which it often declines to do for methods of this
size.

**Fix.** Emit two specialized loops, outer-branched on `caseInsensitive`:

```java
if (caseInsensitive) {
    for (int i = 0; i < len; i++) {
        if (Character.toLowerCase(text.charAt(i)) != Character.toLowerCase(input.charAt(pos + i))) {
            /* fail */
        }
    }
} else {
    for (int i = 0; i < len; i++) {
        if (text.charAt(i) != input.charAt(pos + i)) {
            /* fail */
        }
    }
}
```

Also inlines `peek(i)` as `input.charAt(pos + i)` — small but helps the JIT
coalesce bounds checks.

**Flag:** same as §6.2.

### 6.4  `matchLiteralCst` — bulk-advance on no-newline text

**Problem.** On successful match, the current code calls `advance()` in a
loop to consume each matched char, updating `pos`/`line`/`column` with a
`\n` check per char.

**Fix.** For literal text with no `\n` (universal for keywords and
punctuation), bulk-update:

```java
if (text.indexOf('\n') < 0) {
    pos += len;
    column += len;
} else {
    for (int i = 0; i < len; i++) advance();
}
```

**Flag:** `bulkAdvanceLiteral`.

**Correctness:** the fallback loop is the original path; only no-newline
text uses the bulk update. `indexOf` is O(len) but amortized away by the
saved per-char work.

### 6.5  `matchLiteralCst` and `matchCharClassCst` — per-instance failure cache

**Problem.** Every failed literal/char-class match allocates a fresh
`CstParseResult` with `Option.some(expected)` wrapping. For a parse of the
fixture this happens ~150 000 times. The set of distinct `text` /
`(pattern, negated)` keys, however, is small and bounded (the grammar
literals and char classes are compile-time constants of the emitted code —
probably ~100 unique literals and ~20 unique char classes for the Java
grammar).

**Fix.** Per-parser-instance `HashMap<String, CstParseResult>` keyed on
the literal text / `(pattern, negated)` pair. First miss builds + stores;
subsequent misses return the cached instance. After warmup, the failure
path allocates *nothing*.

```java
private final Map<String, CstParseResult> literalFailureCache = new HashMap<>();

private CstParseResult literalFailure(String text) {
    CstParseResult r = literalFailureCache.get(text);
    if (r == null) {
        r = CstParseResult.failure("'" + text + "'");
        literalFailureCache.put(text, r);
    }
    return r;
}
```

Analogous helper for char-class failures keyed on `negated ? "^" + pattern
: pattern`. Store the quoted label (`"[...]"` / `"[^...]"`) inside the
cached `CstParseResult.expected`, and in the match helpers grab it back
with `f.expected.unwrap()` to pass to `trackFailure` — zero allocation on
the failure path.

**Flags:** `literalFailureCache`, `charClassFailureCache`.

**Correctness:** the cached `CstParseResult` is immutable and carries the
same `expected` string the original code produced. Ensure map is on a
per-parser-instance field, **not static**: the parser itself is single-use
and the literal set is bounded per grammar, so a fresh map per parse is
fine and thread-safe by construction.

**Subtle point:** currently `matchCharClassCst` passes one string
(`"[...]"`) to `trackFailure` and a different one (`"character class"`) to
`CstParseResult.failure`. Unify both to the bracketed label — it's more
informative and simplifies caching. §4.1 (baseline) captures this so if the
unified message propagates into error output, the baseline hashes will
reflect it. If the CST hash excludes error-expected strings (which it
should by default — it's a CST not a diagnostics report), this is
invisible to the parity check.

### 6.6  `skipWhitespace` fast-path

**Problem.** The emitted `skipWhitespace` allocates an
`ArrayList<Trivia>` and runs a mini-PEG loop (whitespace char class + line
comment + block comment alternatives) on every rule entry. In production
Java source, the common case is "current char is neither whitespace nor the
start of a comment" — the mini-parser does nothing but we still pay the
setup cost.

**Fix.** Check the current char up front:

```java
private List<Trivia> skipWhitespace() {
    if (skippingWhitespace || tokenBoundaryDepth > 0 || pos >= input.length()) {
        return List.of();
    }
    char c = input.charAt(pos);
    if (c != ' ' && c != '\t' && c != '\r' && c != '\n') {
        if (c != '/' || pos + 1 >= input.length()) return List.of();
        char c2 = input.charAt(pos + 1);
        if (c2 != '/' && c2 != '*') return List.of();
    }
    // slow path (existing logic) only runs when there's *potential* trivia
    var trivia = new ArrayList<Trivia>();
    ...
}
```

`List.of()` is a shared immutable empty list — no allocation on the fast
path. Callers use only `size()` + `addAll()`, both work with it.

**Flag:** `skipWhitespaceFastPath`.

**Correctness:** the fast path returns the same empty-trivia result the
slow path would produce for non-trivia positions. The tests that currently
inspect `pendingTrivia` see no behaviour change; §4.1 and §4.2 will catch
any deviation.

**Generator note:** `skipWhitespace` is generated from the grammar's
`%whitespace` directive. The generator already knows which characters
start each whitespace-rule alternative; it can emit the exact fast-path
char set (for the Java grammar: `' '`, `'\t'`, `'\r'`, `'\n'`, and `'/'`
when followed by `/` or `*`). Don't hard-code this — derive it from the
grammar's `%whitespace` rule so the optimization generalizes.

### 6.7  Success-path `endLoc` reuse

**Problem.** `matchLiteralCst` and `matchCharClassCst` call `location()`
twice on success — once for the span's end, once for `CstParseResult.success(…, endLocation)`.
Each call allocates a `SourceLocation` record at the same `(line, column, pos)`.

**Fix.** Allocate once, reuse:

```java
var endLoc = location();
var span = SourceSpan.of(startLoc, endLoc);
var node = new CstNode.Terminal(span, RULE_…, text, List.of(), List.of());
return CstParseResult.success(node, text, endLoc);
```

**Flag:** `reuseEndLocation`.

### 6.8  Measured gate

After all of §6 is landed and flags default on, re-run §4.4 + a JMH
benchmark on the fixture. Expected speedup on the fixture: **~1.9× from
baseline** (validated offline). If actual is below 1.5×, investigate before
proceeding to §7 — either a flag isn't actually on in the emitted code, or
the grammar/input exercises the hot paths differently.

---

## 7. Phase 2 — structural generator changes

Bigger wins but bigger risk. Each is gated by its own flag. Expect to need
iteration on each — land behind a default-off flag, measure, flip on if
§4.4 stays green.

### 7.1  Character-dispatch for Choice over literals

**Problem.** A `Choice` whose alternatives are all `Literal` or
`Literal`-led sequences (e.g. `parse_Keyword`, `parse_Modifier`,
`parse_Punctuation`) is emitted as a flat chain: try the first, on failure
save state and try the next, etc. For ~50 Java keywords this is a linear
~50-call chain per attempt, with most attempts happening at positions where
*no* alternative matches.

**Fix.** At generation time, when the `Choice` alternatives are all
literal-prefixed, emit a `switch(input.charAt(pos))` dispatch that maps the
current char to the subset of alternatives whose first char matches —
usually zero or one. Fall through to the general backtracking chain as a
safety net for alternatives that don't start with a fixed char.

For single-char-indexed literals, a `switch` on the first char is enough.
For overlapping prefixes (e.g. `'do'` vs `'double'`), the dispatched branch
still needs to try both but only those two — from 50 comparisons down to
2.

**Where in the generator.** Inside the `Expression.Choice` emission path in
`ParserGenerator.generateExpressionCode` (or equivalent). Add a prepass
over the choice's alternatives that classifies by first-char reachability.

**Expected win:** 2–3× on keyword-heavy rules; hard to predict overall
speedup without measurement (depends on how much time the parse spends in
keyword-dispatch rules).

**Flag:** `choiceDispatch`.

**Correctness risks:**
- Misidentifying the fast-path set (e.g. alternatives with lookahead /
  char-class prefixes). Start conservative: only literal-prefix
  alternatives take the fast path. Anything else falls through to the
  general chain.
- Case-insensitivity: the grammar's `caseInsensitive` flag must flow
  through. For case-insensitive choice, dispatch on `toLowerCase`.
- Unicode surrogate pairs: Java `switch(char)` handles the BMP subset
  correctly, which is what keyword chars need.

### 7.2  Mark/reset for `children` in Choice

**Problem.** The current `Choice` emission does, per alternative:

```java
var savedChildren0 = new ArrayList<>(children);   // clone at Choice entry
...
children.clear(); children.addAll(savedChildren0); // restore per failed alt
```

For a Choice with N alternatives and failure rate F, this performs N×F
`addAll` copies of `savedChildren0.size()` elements. Often `children` is
near-empty at Choice entry, but a more deeply nested Choice inherits a
populated `children`.

**Fix.** Replace clone + clear+addAll with mark-and-trim:

```java
int childrenMark = children.size();                   // at Choice entry
...
// on failed alternative, before trying the next:
if (children.size() > childrenMark) {
    children.subList(childrenMark, children.size()).clear();
}
```

O(1) to mark, O(delta) to trim only what was added. No pre-copy; no
full-list addAll.

**Where in the generator.** Choice emission, same location as §7.1.

**Flag:** `markResetChildren`.

**Correctness:** `subList().clear()` is defined to remove exactly the range
from the backing list. Any test that inspects `children` mid-Choice will
behave identically as long as the rule emission is self-consistent. §4.1
catches any deviation.

### 7.3  Reduce `SourceLocation` allocations

**Problem.** `location()` allocates a `SourceLocation` record every call;
the generator emits many such calls per rule (for span starts/ends, failure
tracking, recovery anchors). The JIT can scalarize short-lived records in
some cases but not reliably.

**Fix (option A — lightweight):** introduce per-rule int locals
`startOffset` / `startLine` / `startColumn` in the generator's rule
template, used for span construction. Materialize `SourceLocation` only at
span boundaries (`SourceSpan.of(new SourceLocation(startLine, startColumn,
startOffset), new SourceLocation(line, column, pos))`), not on every
possibly-failed alternative.

**Fix (option B — heavier):** change `SourceSpan` to hold primitive ints
directly and expose `SourceLocation` only on demand:

```java
record SourceSpan(int startLine, int startColumn, int startOffset,
                  int endLine, int endColumn, int endOffset) { ... }
```

This is a public-API change to `SourceSpan`, so it breaks consumers unless
accessors are preserved. Likely out of scope for this pass; note as a
follow-up.

**Flag:** `inlineLocations` (option A).

**Expected win:** 5–10% — not transformative alone, but compounds with
§6 in the success path of every successful rule.

### 7.4  Selective packrat

**Problem.** The packrat cache is populated for every rule invocation but
has only ~10% hit rate on the fixture. ~90% of cache entries are never
read again — we pay the put cost (including a `Long.valueOf` autobox on
every put / get) for nothing.

**Fix.** Gather per-rule cache statistics (gets, hits, puts) on a few
representative files, offline. Rules where `hits/puts < threshold` (say
0.1) are marked "unproductive" and skip caching entirely. Rules where
`hits/puts > threshold` keep packrat on. Emit a `static final boolean[]
PACKRAT_RULE_MASK` indexed by rule ordinal; cache lookups/stores consult
the mask first.

Alternative: let the grammar author annotate rules with
`@memoize` / `@no-memoize` attributes in the grammar DSL. More work but
user-controllable.

**Flag:** `selectivePackrat` (runtime); may also require a grammar DSL
extension.

**Expected win:** 10–20% on typical workloads; eliminates the
cost-without-benefit of caching high-miss-rate rules.

**Correctness:** skipping the cache is always sound — caching is purely
optional. The only risk is a packrat-dependent rule that blows up without
caching (exponential backtracking). Catch via §4.4 smoke benchmark.

---

## 8. Measurement

### 8.1  JMH setup

A JMH benchmark already exists in the downstream pragmatica-clone repo
that references the same fixture. Re-implement it here (don't take a
dependency) under `src/jmh/java` (or a sibling Maven module):

- Fixture: `src/test/resources/perf-corpus/large/FactoryClassGenerator.java.txt`
- Modes: `Throughput` + `AverageTime`, output `ms/op` and `ops/ms`
- Warmup: 3 iterations × 2s
- Measurement: 5 iterations × 2s
- Forks: 2
- Fresh parser per `@Benchmark` invocation

Parameterize on `ParserConfig` flag combinations (e.g., `{none, phase1,
phase1+phase2}`) so a single run produces the full matrix.

### 8.2  Metrics to report per change

- Median min over N=5 runs (noise-resistant)
- Packrat hit rate on the fixture
- Allocation rate (JMH `-prof gc` or JFR `ObjectAllocationSample`)
- Rule-hit histogram delta vs baseline (must be identical)

### 8.3  Expected results

| Change set | Expected speedup vs baseline | Source |
|---|---|---|
| Phase 1 only (all §6 flags on) | ~1.9× | Offline prototype |
| Phase 1 + Phase 2 | ~2.5–3× | Extrapolated; validate |

If Phase 1 delivers noticeably less than 1.9×, stop and diagnose. Either
an emitter isn't actually picking up the flag, or the workload differs.

---

## 9. Rollout order

1. **§5 pre-work** — verification infra, baseline, CI gates. *No parser
   behaviour change.*
2. **§6 phase 1** — one flag at a time, in the order listed. After each:
   §4.4 must stay green, JMH must show expected direction of change (may
   be noisy per-step). Once all 6.1–6.7 land, flip defaults to on and run
   §6.8.
3. **§7 phase 2** — in the order 7.1, 7.2, 7.3, 7.4 (easiest → hardest).
   Default-off until individually validated.
4. **Cleanup** — once flags have been default-on without incident for a
   meaningful window, remove the flags (keep only the optimized path).
   This avoids leaving dead code paths long-term. Reserve a "legacy" mode
   only if there's a concrete reason (e.g. debugging).

---

## 10. Out of scope

- `PegEngine` interpreter (`src/main/java/org/pragmatica/peg/parser/PegEngine.java`).
  The same optimizations mostly apply, but pursuing them here bloats the
  effort. Defer to a follow-up spec once the generator wins are validated.
- Grammar semantics. No `java25.peg` change in this rework.
- Packrat algorithm changes (left-recursion, Warth-style seeding, etc.).
  Not needed for the Java grammar.
- API changes to `CstNode`, `SourceSpan`, `CstParseResult`, `ParserConfig`
  (other than additive flag fields on `ParserConfig`).
- Benchmark comparison against external parsers (JavaParser, Spoon, etc.).

---

## 11. Risks & unknowns

| Risk | Mitigation |
|---|---|
| Subtle CST differences caused by a "refactoring" optimization (e.g. unified error-message strings) | §4.1 structural-hash parity catches these; decide case-by-case whether to preserve or update the baseline |
| JIT behaviour differs between test JVM and user JVMs | Measure on JDK 25 (the project's target); document the result |
| Packrat correctness interaction with captures / back-references in future grammars | This grammar doesn't use captures heavily; note the concern for the follow-up |
| §7.1 char-dispatch misclassifies an alternative and breaks parsing | Conservative classification (literal-prefix only); full §4.4 parity check catches it |
| Optimization combinations produce non-additive wins (or even regressions) | JMH matrix (§8.1) detects this |

---

## 12. Acceptance criteria

Definition of done:

1. All files under `perf-corpus/` produce CST hashes bit-identical to the
   baseline committed in phase §5.
2. Round-trip (§4.2) passes on every corpus file.
3. JMH shows ≥1.5× speedup on `FactoryClassGenerator.java.txt` vs the
   baseline (stretch ≥2×).
4. All existing `Java25GrammarExample` and `Java25ParserTest` tests pass
   unchanged.
5. CI smoke benchmark (§4.4) enforces no wall-time regression.
6. Phase 1 flags default on; phase 2 flags default on if their individual
   measured win > 5%, otherwise default off with a note in this document.
7. `ParserGenerator`'s emitted helper code is the only area that changed.
   No grammar edits. No `PegEngine` edits. No `Grammar` IR edits (other
   than additive `ParserConfig` fields).

---

## Appendix A — reference prototype location

A validated offline prototype of §6 lives in the pragmatica-clone repo at
`jbct/jbct-parser-benchmark/src/main/java/org/pragmatica/jbct/parser/benchmark/Java25ParserExp.java`.
Optional reference only — this spec is intended to stand alone. Do not take
a dependency on that file or repo.

## Appendix B — file touchpoints in `java-peglib`

Expected files to be created or modified:

- `src/main/java/org/pragmatica/peg/generator/ParserGenerator.java` (main
  edit surface)
- `src/main/java/org/pragmatica/peg/parser/ParserConfig.java` (new flag
  fields)
- `src/test/java/org/pragmatica/peg/generator/…CstHashTest.java` (new)
- `src/test/java/org/pragmatica/peg/generator/…RoundTripTest.java` (new)
- `src/test/java/org/pragmatica/peg/generator/…CorpusParityTest.java` (new)
- `src/test/resources/perf-corpus-baseline/…` (new; committed)
- JMH module or `src/jmh/java/` (new)
- `CHANGELOG.md` — document each flag and measured win as it lands

No changes to `src/test/resources/java25.peg` or
`src/test/resources/perf-corpus/` — those are fixtures.
