# peglib-incremental — detailed spec

**Target module:** `peglib-incremental` (new; sibling to `peglib`).
**Depends on:** `peglib:0.2.x+` (compile-scope; read-only use of `Grammar`, `CstNode`, `Trivia`, `SourceSpan`, `PegParser`).
**Status:** proposal / spec for an implementing agent. Not yet executed.
**Scope:** cursor-anchored incremental CST reparsing. Non-destructive sibling module — zero source changes to `peglib-core`.

---

## 1. Context

`peglib` at 0.2.2 parses a 1,900-LOC Java 25 fixture in ~100 ms (JMH avgT, JDK 25, Apple Silicon; see `docs/bench-results/`). Fast enough for one-shot workflows — source generation, batch validation, compiler frontends. Too slow for editor-scale use: sub-16 ms end-to-end is the budget for "instant" perception at 60 Hz, and 100 ms of reparse per keystroke is incompatible with live tooling (formatter-on-save, live diagnostics, language server responsiveness).

The standard answer is incremental reparsing: on edit, identify the smallest subtree affected by the change, reparse just that subtree, splice the result back in. Tree-sitter popularized this for editor-facing parsers; peglib has the precondition pieces (lossless CST, `Grammar` IR, packrat cache) but no incremental layer.

This spec proposes **`peglib-incremental`** — a separate module with a cursor-anchored stateful API — that turns single-edit reparses into sub-millisecond operations in the common case and falls back to full parse in worst-case shape changes.

The design deliberately confines the incremental logic to a new module. Zero source changes to `peglib` / `peglib-core`; if incremental doesn't deliver, the module is retired without breaking any 0.2.x user.

---

## 2. Objectives

1. **Performance targets** (on the 1,900-LOC fixture, JDK 25):
   - Single-character edit: **< 1 ms median** (stretch: < 0.5 ms)
   - Word-level edit (5–10 chars): **< 5 ms median**
   - Line-level paste/delete (50–200 chars): **< 20 ms median**
   - Worst-case fallback (full reparse): **≤ `peglib-core` full-parse time** (~100 ms)
2. **Correctness:** CST produced by any incremental edit sequence is bit-identical (via `CstHash`) to the CST produced by a fresh full parse of the post-edit buffer.
3. **Module isolation:** `peglib-incremental` depends on `peglib` but does not patch, subclass, or reach into internal state of `PegEngine` / `ParserGenerator`. All interaction is via the published `peglib` API.
4. **API stability:** published `peglib` API surface unchanged. `peglib-incremental` adds its own types — `IncrementalParser`, `Session`, `Stats` — under `org.pragmatica.peg.incremental`.
5. **Reversibility:** the module can be dropped from a minor release without breaking any `peglib` consumer that doesn't use it.

---

## 3. Module boundary

### 3.1 New module layout

```
peglib-incremental/
├── pom.xml
├── src/main/java/org/pragmatica/peg/incremental/
│   ├── IncrementalParser.java         # public API entry
│   ├── Session.java                    # public API, immutable view
│   ├── Edit.java                       # public record (offset, oldLen, newText)
│   ├── Stats.java                      # public record
│   └── internal/
│       ├── SessionImpl.java            # package-private
│       ├── ReparseBoundary.java        # boundary expansion algorithm
│       ├── CacheRemap.java             # packrat offset remapping
│       ├── NodeIndex.java              # span-to-node lookup (bootstrap only)
│       └── TriviaRedistribution.java   # v2 only — requires trivia round-trip
└── src/test/java/org/pragmatica/peg/incremental/
    ├── IncrementalParityTest.java
    ├── EditFuzzTest.java
    ├── ReparseBoundaryTest.java
    ├── CacheRemapTest.java
    ├── SessionApiTest.java
    └── IncrementalBenchmark.java       # JMH, behind `bench` profile
```

### 3.2 Dependency rules

- `peglib-incremental` **may** depend on: `peglib` (compile), JUnit (test), AssertJ (test), JMH (bench profile).
- `peglib-incremental` **must not** depend on anything that `peglib` doesn't already depend on — same transitive footprint for downstream users.
- `peglib-incremental` **must not** reach into non-API internals of `peglib`. If incremental reparsing genuinely needs an internal hook (e.g. packrat cache access), add a minimal public API to `peglib-core` in a separate, tracked commit with its own spec entry. Do not reflect into private fields.
- `peglib-incremental` **must not** modify or shadow types in `peglib`.

### 3.3 Versioning

- `peglib-incremental` follows `peglib`'s version lockstep during 0.2.x (incremental is experimental; co-versioned to keep the compat story simple).
- When `peglib` goes 1.0.0, `peglib-incremental` picks its own versioning trajectory based on API maturity.

### 3.4 Repo layout

Preferred: multi-module Maven parent `peglib-parent` with `peglib-core` (current content) and `peglib-incremental` as modules. Single-repo, single-CI, matches existing release workflow.

Alternative: separate repo `java-peglib-incremental` consuming `peglib` as an external dependency. Independent release cadence. More appropriate if incremental becomes its own significant project.

Recommend multi-module to start; separate repo is a later migration if warranted.

---

## 4. API

### 4.1 Public types

```java
package org.pragmatica.peg.incremental;

public interface IncrementalParser {
    static IncrementalParser create(Grammar grammar);
    static IncrementalParser create(Grammar grammar, ParserConfig config);

    Session initialize(String buffer);
    Session initialize(String buffer, int cursorOffset);
}

public interface Session {
    CstNode root();           // current CST (v1: CST-only; v3+: AST via action replay)
    String text();             // current buffer text
    int cursor();              // current cursor offset
    Stats stats();             // per-session stats, diagnostics

    Session edit(int offset, int oldLen, String newText);
    Session edit(Edit edit);  // record-sugar form

    Session moveCursor(int newOffset);
    Session reparseAll();     // diagnostic escape hatch: force a fresh full parse
}

public record Edit(int offset, int oldLen, String newText) {
    public int newLen() { return newText.length(); }
    public int delta() { return newLen() - oldLen; }
}

public record Stats(
    int reparseCount,          // total edits processed in this lineage
    int fullReparseCount,      // edits that triggered full-reparse fallback
    int lastReparsedRuleOrd,   // rule ordinal reparsed for the last edit
    int lastReparsedNodeCount, // node count of the reparsed subtree
    double lastReparseMs,      // wall time of the last edit
    int cacheSize              // packrat cache size after last edit
) {}
```

### 4.2 Immutability and persistence

- `Session` is **immutable**. Every `edit(...)` / `moveCursor(...)` returns a new `Session`.
- Internally, `SessionImpl` **shares structure** with its predecessor via reference-equality of untouched `CstNode` records (they're already value objects; reuse is free). Only the reparsed subtree and its ancestor spine are new allocations.
- Session lifetime is the caller's responsibility. Holding old sessions is legal (useful for undo/redo) but retains their CST trees in memory. Stats exposes `retainedTreeSize()` for memory-accounting tools.

### 4.3 Usage examples

**Basic editor loop:**

```java
var parser = IncrementalParser.create(javaGrammar);
Session s = parser.initialize(initialBuffer, cursor);

// user types 'x' at current cursor
s = s.edit(s.cursor(), 0, "x");

// user moves cursor (no structural change)
s = s.moveCursor(newCursorOffset);

// user deletes 3 characters
s = s.edit(s.cursor() - 3, 3, "");

CstNode tree = s.root();
render(tree);
```

**Undo via saved session:**

```java
Session before = session;
Session after = session.edit(off, 0, "hello");

// later — user presses undo
session = before;  // no reparse; tree reference restored in O(1)
```

**Batch edit (multi-cursor):**

```java
// Apply edits in reverse offset order so earlier offsets stay stable.
edits.stream()
     .sorted(Comparator.comparingInt(Edit::offset).reversed())
     .reduce(session, Session::edit, (a, b) -> b);
```

### 4.4 Invariants

- **Monotonicity:** for a session S after edits E₁, E₂, …, Eₙ, `S.text()` equals the result of applying E₁…Eₙ to the initial buffer.
- **Parity:** `CstHash(S.root()) == CstHash(IncrementalParser.create(grammar).initialize(S.text()).root())` for every reachable session.
- **Pure edits:** `session.moveCursor(x).text() == session.text()` (cursor movement never mutates buffer or tree).
- **Bounded reparse on no-op:** `session.edit(cursor, 0, "")` is a no-op and MUST NOT trigger reparse.

---

## 5. Internal model

### 5.1 Session state

`SessionImpl` carries:
- `text` — the current buffer as `String`.
- `root` — the current `CstNode`.
- `cursor` — current cursor offset.
- `packratCache` — `(ruleId, offset) -> CstParseResult` map, shared across sessions when untouched.
- `enclosingNode` — `CstNode` reference, the smallest rule-producing node that contains the cursor. Updated on `edit` and `moveCursor`.
- `stats` — cumulative stats.

### 5.2 Enclosing-node pointer

- On `initialize(buffer, cursor)`: after full parse, walk the CST once, find the smallest node whose span contains `cursor`. Store.
- On `moveCursor(newOffset)`: cheap walk from current `enclosingNode`. Walk up until a node containing `newOffset` is found; walk down into that node's children to find the smallest containing descendant. O(depth × depth) worst case; typically O(1) for adjacent moves.
- On `edit`: the reparse updates `enclosingNode` to the new subtree's deepest descendant at the post-edit cursor position.

The enclosing-node pointer is the hot state that makes subsequent same-region edits O(1). Without it, each edit would require a top-down scan (O(tree size)).

### 5.3 Reparse boundary algorithm

```
INPUT:  session S with enclosing-node pointer P, edit E = (offset, oldLen, newText)
OUTPUT: session S' with updated root, cursor, enclosingNode, cache

1. Update buffer:
     text' = S.text[0:offset] + newText + S.text[offset+oldLen:]
     delta = newText.length - oldLen

2. Find smallest containing node:
     N = P
     while N.span is not fully inside [offset, offset+oldLen]:  # edit straddles N
       N = N.parent
     (note: if N has no parent, N = root; fall to full reparse)

3. Walk up to the smallest node whose span fully contains [offset, offset+oldLen]:
     while N.span.start > offset OR N.span.end < offset + oldLen:
       N = N.parent
       if N is null:
         return full-reparse fallback  # edit exceeds root span

4. Remap packrat cache:
     for (ruleId, off) in cache:
       if off < offset:
         keep entry
       elif off in [offset, offset + oldLen]:
         drop entry  # content changed
       else:
         shift entry key to (ruleId, off + delta)

5. Reparse N's rule at N.span.start against text':
     result = parser.parseRuleAt(N.rule, text', N.span.start)

6. Validate reparse:
     expected_end = N.span.end + delta
     if result.isSuccess AND result.endOffset == expected_end:
       splice: new subtree replaces N in its parent's children list
       return S' with updated root, new enclosingNode at cursor, updated cache
     else:
       N = N.parent
       if N is null:
         full reparse fallback
       else:
         goto step 5

7. Full reparse fallback:
     fresh parse of text' with empty cache
     return S' with fresh root, enclosingNode at cursor, fresh cache
```

#### Key details

- **Steps 2–3** locate the minimal enclosing rule node. In practice, the enclosing-node pointer `P` is usually already the answer or one level below it; step 2–3 is O(depth).
- **Step 4** (cache remap) is O(cache size). Simpler alternative for v1: wholesale invalidate. Measure cost-benefit; prefer remap if the cache is typically large and edits are small.
- **Step 5** reparses from a known rule start against the new buffer. This requires a capability currently absent from `peglib`: **parse-a-specific-rule-starting-at-a-given-offset**. See §5.6 for the required API addition.
- **Step 6** may loop up multiple levels in pathological cases (unbalanced `{`, unclosed quote). Worst case reaches root → full reparse. Amortized cost is low.

### 5.4 Cache remap correctness

The packrat cache stores `(ruleId, startOffset) -> CstParseResult` where `CstParseResult` references spans, text slices, and child nodes whose spans are all relative to the original buffer.

For entries with `startOffset < editOffset`: buffer content at offsets `< editOffset` is unchanged. Cached parses starting there that don't extend into `[editOffset, editOffset + oldLen]` are still valid. Entries whose parse extends into or past the edit region must be invalidated — add a secondary check: `entry.endOffset > editOffset → drop`.

For entries with `startOffset >= editOffset + oldLen`: buffer content at offsets `>= editOffset + oldLen` is unchanged but shifted by `delta`. Cached parses at these positions remain correct if we shift their keys (and any internal offset references in their `CstParseResult`) by `delta`. The shift is an offset remap — implementable as a hash map rebuild in O(cache size).

For entries in `[editOffset, editOffset + oldLen]`: dropped unconditionally.

**Subtle correctness note.** Cached `CstParseResult` records may hold `CstNode`s whose own spans are absolute. After shift, those spans need to be rewritten. Two options:
- Rewrite the spans (O(subtree size) per cached entry) — expensive.
- Accept that cache reuse is incompatible with span rewriting and instead drop the cache entirely on edit (simplest; punts performance).

For v1: **drop cache on edit**. Measure. If cache rebuild dominates, upgrade to span-rewriting remap in v2.

### 5.5 Cursor offset update on edit

```
newCursor = if oldCursor < offset then oldCursor
            elif oldCursor < offset + oldLen then offset + newText.length
            else oldCursor + delta
```

i.e., if the cursor was inside the edit region, snap it to the end of the replacement; if it was before the region, leave it alone; if after, shift by delta. Matches common editor behaviour.

### 5.6 Required `peglib-core` API addition

Reparse boundary algorithm step 5 requires **parse a specific rule starting at a given offset**. `peglib` currently exposes only `Parser.parse(String)` and `Parser.parseCst(String)` — whole-buffer entry points.

Propose adding to `peglib-core`:

```java
public interface Parser {
    // existing: parse(String), parseCst(String), ...

    // new — parse a named rule against a substring starting at offset.
    // Returns the resulting CST subtree and the offset where parsing ended.
    Result<PartialParse> parseRuleAt(String ruleName, String input, int offset);
}

public record PartialParse(CstNode node, int endOffset) {}
```

This is the single API addition `peglib-incremental` requires. Implementable in `PegEngine` and in the generated parser (each rule method becomes callable by name → reflection lookup, or a public `Map<String, Supplier<...>>` dispatch table emitted by the generator).

Land this addition in a separate `peglib-core` commit before starting `peglib-incremental` — it's the only coupling point between the modules.

---

## 6. Correctness

### 6.1 Parity oracle

For any session `S` reachable from `initialize(buffer0, cursor0)` via edits `[E₁…Eₙ]`:

```
CstHash(S.root()) == CstHash(IncrementalParser.create(grammar).initialize(S.text()).root())
```

This is the primary correctness gate. Enforced via `IncrementalParityTest` (§7.1).

### 6.2 Invariants

1. **Buffer consistency.** `S.text()` is always the result of applying all edits in lineage order to the initial buffer.
2. **Span well-formedness.** For every node `n` in `S.root()`, `n.span.start < n.span.end ≤ S.text().length()`, and `S.text().substring(n.span.start, n.span.end)` is the concrete source range covered.
3. **Tree depth equivalence.** `S.root()` has the same depth profile as a fresh full-parse of `S.text()`. (Guaranteed by parity since CstHash includes depth structure.)
4. **Enclosing-node validity.** `S.enclosingNode()` is a node in `S.root()` whose span contains `S.cursor()` (or is the root if cursor is at text boundaries).
5. **Cache validity.** Every entry `(ruleId, offset) → result` in `S.packratCache` satisfies: parsing rule `ruleId` against `S.text()` starting at `offset` returns `result`. (Optional to verify in tests; expensive.)

### 6.3 Edge cases to cover

- **Edit at buffer boundary.** `edit(0, 0, "prefix")`, `edit(text.length, 0, "suffix")`. Must not out-of-bounds.
- **Edit that creates a new rule entirely.** Inserting a whole `class { ... }` block. Reparse boundary must walk up to the containing `CompilationUnit` or equivalent.
- **Edit that destroys a rule.** Deleting `{` alone. Previously valid `Block` no longer parses as a Block; parent `Statement` must be reparsed.
- **Edit entirely within trivia.** Typing a space inside whitespace between two rule nodes. Should affect only trivia; CST hash should (depending on trivia handling) be unchanged or trivially updated. v1 scope: full reparse of containing rule; v2 with trivia-aware redistribution.
- **Edit that straddles two rules.** Replacing `; foo = 1; bar =` with `= 2; baz =`. Straddles a statement boundary. Reparse boundary must walk up to the common ancestor.
- **Edit that changes rule nesting depth.** E.g., `a + b` → `(a + b)`. Reparse produces a deeper tree at this spot; parent splice must accept the new shape.
- **Unbalanced brace insertions.** Inserting `{` inside a `Block`. Possibly every enclosing `Block` becomes malformed — reparse boundary expansion walks all the way up, possibly to root → full reparse. Worst case should still be correct.
- **Rapid successive edits.** 100 single-character insertions in 100 ms. No state leak, no cache thrash collapsing correctness.
- **Edit then undo (via session retention).** Restoring an old session is structural — zero reparse. Subsequent edits from the old session fork a new lineage.
- **Back-reference / Capture rules.** `$name<e>` defines a capture; `$name` elsewhere back-references it. An edit inside the capture invalidates the referrer even if the referrer's span is untouched. Conservative rule: **any rule containing a back-reference, or any rule referenced by a back-reference, triggers full-reparse fallback on edit**. Tighten later if profiling shows it matters.

### 6.4 Scope: action execution

v1 ships with **no incremental action execution**. Actions run only during full-reparse fallback. Rationale:

- Actions have arbitrary user-defined side effects. Incremental replay requires either idempotent actions (not generally true) or careful tracking of action effects on session state (major design work).
- Most editor-facing use of peglib lives in CST mode (formatters, linters). Action-producing uses are typically batch (compilers, codegen) and don't need sub-millisecond reparse.

v3 (deferred / possibly skipped) explores action replay — see §9.

---

## 7. Testing strategy

### 7.1 Parity harness — random edit fuzzing

`IncrementalParityTest` (parametrized over the 22-file corpus):

```
For each file F in perf-corpus/:
  buffer = read(F)
  IncrementalParser p = IncrementalParser.create(javaGrammar)
  Session s0 = p.initialize(buffer, 0)

  Session s = s0
  for i in 1..1000:
    edit = randomEdit(s.text())     # biased toward realistic edits
    s = s.edit(edit)
    oracleRoot = PegParser.fromGrammar(javaGrammar).parseCst(s.text()).unwrap()
    assert CstHash(s.root()) == CstHash(oracleRoot), "divergence at edit " + i
```

Random edit distribution (biased toward realistic editor operations):
- 40% single-char insertions (alphanumeric, punctuation)
- 20% single-char deletions
- 15% word insertions (tokens: keywords, identifiers, literals)
- 10% word deletions
- 10% line operations (full-line paste / delete)
- 5% block operations (multi-line paste / delete)

Seed the RNG; failures must be reproducible. On failure, report: edit sequence up to the diverging edit, the divergent node's span, first differing `CstHash` component.

### 7.2 Targeted edge-case suite

`ReparseBoundaryTest` — one test per edge case in §6.3. Hand-crafted buffers, hand-crafted edits, CST-hash equality assertion.

### 7.3 Idempotency

`IdempotencyTest`: for random edit sequences, verify `s.edit(e).edit(inverse(e))` produces a session with `CstHash` equal to `s`. Cursor may differ (edits change cursor); tree must not.

### 7.4 Performance regression gates

`IncrementalBenchmark` (JMH, behind `bench` profile):

- `@Param variant`: `{initialize, singleCharEdit, wordEdit, lineEdit, fullReparse, undoRestore}`.
- Corpus: `FactoryClassGenerator.java.txt` fixture.
- Targets per §2 (single-char < 1 ms median, etc.). CI gates against the targets; a 2× regression fails the build.

### 7.5 Parity across module versions

When `peglib-core` updates (e.g. minor version bump within 0.2.x), re-run `IncrementalParityTest` against the new core. Since `peglib-incremental` depends on core's CST semantics, it must track any deliberate semantic change in core.

---

## 8. Performance targets

Recapped from §2, grounded in the 0.2.2 full-parse baseline (~100 ms for 1,900-LOC fixture, JDK 25):

| Operation | Target median | Stretch | Worst-case |
|---|---|---|---|
| `initialize(buffer)` | ≤ full-parse time (~100 ms) | — | full-parse time |
| `edit(single char)` | < 1 ms | < 0.5 ms | full-reparse time |
| `edit(word, 5–10 chars)` | < 5 ms | < 2 ms | full-reparse time |
| `edit(line, 50–200 chars)` | < 20 ms | < 10 ms | full-reparse time |
| `moveCursor(near)` | < 0.1 ms | < 0.01 ms | O(depth) |
| `reparseAll()` | full-parse time | — | full-parse time |

Stretch targets depend on whether Phase-2 `inlineLocations` / `markResetChildren` optimizations land in `peglib-core` with a measurable effect on the per-rule parse cost. Independent of `peglib-incremental`.

---

## 9. Staging

**v1 — CST-only, wholesale cache invalidation.** Everything in §§4–7. No trivia redistribution. No action execution during incremental reparse. Wholesale cache invalidation on edit (simpler; measure before optimizing).

**v2 — Trivia-aware reparse.** Requires idea #1 (intra-sequence trivia attribution fix in `peglib-core`) to have landed. Trivia redistribution during splice; `TriviaRedistribution` internal helper fills in.

**v2.5 — Cache span-rewriting remap.** If v1 profiling shows cache rebuild dominating at ~100–1000 ms for large files, implement span-rewriting offset remap (per §5.4). Otherwise skip.

**v3 — Action replay (optional, possibly skipped).** Investigate idempotent-action invariants; if a principled replay model emerges, implement. If not, document the limitation and leave action-producing grammars on the full-reparse path.

---

## 10. Risks & unknowns

| Risk | Mitigation |
|---|---|
| Tree-sitter spent multiple engineer-years on incremental parsing. A credible Java PEG implementation underestimates scope. | Scope is narrower than tree-sitter (PEG simpler than GLR, CST-only in v1). Fuzz harness flushes correctness gaps early. If complexity explodes past 4 weeks, re-evaluate before continuing. |
| Silent divergence from full parse. | Parity harness with 1000 edits × 22 files = 22,000 checks per test run. CI gates on zero divergences. Reproducible seeds on failure. |
| Cache invalidation correctness (§5.4). | v1 drops cache on edit — eliminates the subtle bug class entirely. Span-rewriting remap is a v2 optimization with its own proof obligation. |
| Reparse boundary expansion can reach the root in pathological cases, collapsing to full reparse. | This is correct behaviour; measure frequency via `Stats.fullReparseCount`. If > 5% of edits in realistic workloads trigger full reparse, investigate what grammar shapes cause it (candidates: `^` cut operators, deeply nested `Choice`, back-references). |
| Cursor movement logic has subtle corner cases (cursor inside replaced range, cursor at edit boundary). | Enumerated in §5.5. Test matrix in `SessionApiTest`. |
| Back-references (`$name`) invalidate regions outside the edit span. | v1 falls back to full reparse for any grammar containing `BackReference` expressions. Tighten via targeted rule-level tracking in v2+. Document the limitation. |
| Incremental module maintenance creates a second parser implementation that drifts from `peglib-core`. | Parity harness continuously aligns them. Shared `CstNode` / `CstHash` / corpus. Module versioning lockstep during 0.2.x. |
| New required API in `peglib-core` (`parseRuleAt`) is a permanent addition even if `peglib-incremental` is later retired. | Make it a minimally scoped, independently useful method. Document as "partial parse from a named rule" — plausibly useful for testing and grammar debugging even without incremental. |

---

## 11. Out of scope

- Incremental action execution (deferred to v3, likely skipped).
- Incremental error-recovery — v1 uses whatever recovery strategy the underlying parser uses; no special handling during partial reparse.
- AST-level incremental updates. Actions build user-defined output; their incremental semantics aren't peglib's to define.
- Incremental grammar changes. If the grammar itself changes at runtime, a fresh `IncrementalParser` is required.
- `PegEngine` internals refactoring to expose partial-parse hooks beyond the minimal `parseRuleAt` addition.
- Multi-threaded access to a single `Session` instance. Sessions are immutable; sharing read-only sessions across threads is fine, but concurrent `edit` calls on the same session are the caller's problem (each returns a distinct new session).
- Native memory / mmap backing for huge files. v1 assumes `String`-sized buffers.

---

## 12. Acceptance criteria

Definition of done for v1:

1. `peglib-incremental` module builds under default `mvn -pl peglib-incremental install`.
2. `IncrementalParityTest` passes 22,000 checks (22 files × 1000 random edits) under the default seed.
3. `ReparseBoundaryTest` covers every edge case in §6.3 with explicit tests.
4. `IdempotencyTest` passes on random edit sequences.
5. `IncrementalBenchmark` (JMH) produces numbers meeting the §8 targets on the reference JDK / CPU.
6. `mvn test` on the peglib-core module produces the same results as before this module landed — zero regression in core.
7. Public API documented: class-level Javadoc on `IncrementalParser`, `Session`, `Edit`, `Stats`. One working example under `src/test/java/.../examples/`.
8. `CHANGELOG.md` entry under a peglib-incremental section documenting v1 scope + measured targets.
9. README for `peglib-incremental` module with quick-start + scope + limitations (action execution, back-references).
10. The one `peglib-core` API addition (`parseRuleAt`) is separately documented in `peglib-core`'s CHANGELOG with its own scope note.

---

## Appendix A — API usage examples

### A.1 Minimal editor loop

```java
IncrementalParser parser = IncrementalParser.create(grammar);
Session s = parser.initialize(buffer);

while (editor.hasEdit()) {
    Edit e = editor.nextEdit();
    s = s.edit(e);
    editor.render(s.root(), s.stats());
}
```

### A.2 Integration with an LSP-like dispatcher

```java
record DocumentState(Session session, int version) {}

DocumentState onDidOpen(String text) {
    return new DocumentState(parser.initialize(text, 0), 1);
}

DocumentState onDidChange(DocumentState prev, List<Edit> edits) {
    Session s = prev.session;
    for (Edit e : edits) s = s.edit(e);
    return new DocumentState(s, prev.version + 1);
}

List<Diagnostic> onValidate(DocumentState doc) {
    return extractDiagnostics(doc.session.root());
}
```

### A.3 Undo/redo via session retention

```java
Deque<Session> undoStack = new ArrayDeque<>();

Session applyEdit(Session current, Edit e) {
    undoStack.push(current);
    return current.edit(e);
}

Session undo(Session current) {
    return undoStack.isEmpty() ? current : undoStack.pop();
}
```

### A.4 Session forking

```java
Session shared = parser.initialize(buffer);
Session branchA = shared.edit(...);
Session branchB = shared.edit(...);
// shared, branchA, branchB are all independent valid sessions
// branchA and branchB diverge from shared without affecting each other
```

---

## Appendix B — Future work

- **Persistent tree structure with O(log n) sibling navigation.** v1 walks parent pointers (added during construction) for boundary expansion. A true persistent tree (e.g. ropes or finger trees over `children`) would enable faster splice + more efficient session-forking. Significant engineering; not required for v1 targets.
- **Partial action replay.** If users express interest in incremental action output, investigate a pure-function-tagged action model where replay is safe. Research-grade; spec separately if pursued.
- **Persistent packrat cache across edits with span-rewriting remap.** §5.4; likely v2.
- **Web/WASM build of `peglib-incremental`.** Feeds directly into idea #11 (WASM playground). Incremental parsing in the browser-side playground gives live CST updates as the author types — genuinely differentiating. Needs JDK → WASM pipeline (GraalVM native-image + `wasm` target, or a complete rewrite path).
- **Profile-guided reparse boundary selection.** For a given grammar, precompute which rules are "reparse-stable" (internal structure rarely expands boundaries) vs. "reparse-fragile" (edits usually propagate). Use as hints during boundary expansion. Mid-value, mid-complex.

---

## Appendix C — Relationship to other spec items

- **Idea #1 (trivia round-trip)** — prerequisite for v2. v1 works without it but can't promise lossless trivia during incremental splice.
- **Idea #2 (interpreter phase-1 port)** — independent. v1 benefits from a faster full-reparse fallback regardless.
- **Idea #3 (programmatic action attachment)** — independent but worth noting: lambda-attached actions give us more control over replay (well-defined function objects) than inline Java actions (arbitrary compiled code). If action replay (v3) pursues, lambda actions are the likely entry point.
- **Idea #6 + #14 (analyzer + Maven plugin)** — the analyzer's back-reference detection feeds §10's risk mitigation (auto-tagging rules that force full-reparse fallback).
- **Idea #8 (playground)** — direct consumer. A playground with live CST updates as the author types is one of the most visible wins from `peglib-incremental`.
- **Idea #11 (WASM)** — see Appendix B; incremental + WASM is the combined unlock for browser-side live parsing.
