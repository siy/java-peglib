# Peglib 0.5.0 — Incremental-native Architecture

**Status:** specification (draft) · **Author:** post-0.4.3 retrospective · **Date:** 2026-05-06

---

## 200-word summary

Peglib's 0.4.x incremental parser is grafted on top of an architecture optimized for parse-once correctness. Every persistent perf challenge documented across `docs/archive/V2.5-SPIKE.md`, the lever-1 puzzle in `HANDOVER.md` §6.2, the failed unsafe-generator spike, and the failed `NodeIndex.evolve` attempt is a manifestation of the same mismatch — the data structures fight incremental work.

This spec proposes an **incremental-native** redesign for v0.5.0, gathered into four levers:

- **A. Stable node IDs + persistent tree** — replace JVM identity with explicit per-node IDs. NodeIndex updates O(splicedSize + ancestorDepth) instead of O(N).
- **B. Top-down pivot search** — locate the smallest-enclosing rule by descending from root using span containment. Eliminates the cursor-overshoot ("lever-1") problem.
- **C. IR-based interpreter/generator unification** — both paths execute the same per-Expression code via a tiny `peglib-rt` runtime. Optimizations land once, not twice.
- **D. Cursor as separate value** — `(Session, Cursor)` decoupled. Move-cursor doesn't allocate a Session; undo cheap.

Combined, these address the per-edit fixed-overhead floor that capped 0.4.3 at p99 ≈ 53 ms. Projected 0.5.0 floor: p99 ≤ 16 ms (frame budget) on the 1900-LOC fixture, p99 ≤ 30 ms on a 75k-LOC PgSqlParser fixture. **Estimated effort: 2-3 weeks of focused work, breaking changes, single-version migration.**

---

## 1. Problem statement — why this exists

Peglib's perf arc through 0.4.x has been a study in extracting all the value possible from the current architecture:

| Release | Headline gain | Lever |
|---|---|---|
| 0.4.1 | 3.88× interpreter | HashMap rule lookup, ParseMode singleton, LinkedHashSet furthestExpected (interpreter) |
| 0.4.1 | LinkedHashSet ported to generator emission | P3.2 — same fix, second implementation |
| 0.4.3 | -19% median, -26% p95 on incremental sessions | NodeIndex pre-sizing, SourceSpan int-triple refactor |

We've gone from 281 ms baseline to 10.8 ms median for cursor-aware single-char edits — 26× from baseline, 19% from the immediately prior release. Most of this is real engineering on a parse-once design.

But at every step we've hit the same wall:

1. **The lever-1 cursor-overshoot fix needs 5-10 days of correctness analysis** because the data structure doesn't naturally support edit-anchored pivoting.
2. **The unsafe-generator spike (replacing `CstParseResult` with mutable state)** would force every parse-method to be aware of state-capture discipline — a fragile pattern bolted onto an architecture designed for immutable result records.
3. **Incremental NodeIndex update** failed parity tests in our half-day attempt because the data structure (`IdentityHashMap` keyed by JVM identity) doesn't admit clean incremental update.
4. **Every algorithmic optimization is paid twice** — once in `PegEngine` (interpreter), once in `ParserGenerator` emission templates. P3.1 and P3.2 are an example: same fix, two commits, two implementations.

The 0.4.3 outlier-bench data revealed the next wall directly: **per-edit fixed overhead from `NodeIndex.build` dominates tiny-pivot edits**, and **that overhead is fundamentally O(N) given the current design**. We can't bypass it without changing the architecture.

This spec proposes the change.

---

## 2. Lever A — Stable node IDs + persistent tree

### Problem

`NodeIndex` (in `peglib-incremental`) is a `IdentityHashMap<CstNode, CstNode>` mapping each CST node to its parent. The map is keyed by JVM object identity. Records are immutable; when an edit produces a new ancestor chain via `TreeSplicer.spliceAndShift`, those new records have NEW JVM identities — old map entries are orphaned.

Per the 0.4.3 outlier bench, `NodeIndex.build` walks all 91,000 nodes per edit, even when the spliced subtree is 2 nodes (e.g., a single `Identifier` rule). This is the dominant cost in tail-latency outliers and the floor on per-edit interactive feel.

### Proposal

Each CST node carries a stable `long id` field assigned at construction. NodeIndex keys by ID instead of JVM identity:

```java
public sealed interface CstNode {
    long id();
    SourceSpan span();
    String rule();
    List<Trivia> leadingTrivia();
    List<Trivia> trailingTrivia();

    record Terminal(long id, SourceSpan span, String rule, String text,
                    List<Trivia> leadingTrivia, List<Trivia> trailingTrivia) implements CstNode {}
    record NonTerminal(long id, SourceSpan span, String rule, List<CstNode> children,
                       List<Trivia> leadingTrivia, List<Trivia> trailingTrivia) implements CstNode {}
    record Token(long id, SourceSpan span, String rule, String text,
                 List<Trivia> leadingTrivia, List<Trivia> trailingTrivia) implements CstNode {}
    record Error(long id, SourceSpan span, String rule, String text,
                 List<Trivia> leadingTrivia, List<Trivia> trailingTrivia) implements CstNode {}
}
```

ID assignment is abstracted behind an interface so the strategy can evolve independently of the rest of the architecture:

```java
public interface IdGenerator {
    long next();
}
```

`ParsingContext` (and `IncrementalSession` for cross-edit allocations) holds an `IdGenerator` reference. Each node-construction call invokes `idGen.next()`. The first implementation is a simple per-Session counter:

```java
final class PerSessionCounter implements IdGenerator {
    private long next = 0;
    @Override public long next() { return next++; }
}
```

Per-Session counter is the v0.5.0 default — simplest, no synchronization, sufficient for incremental edit chains within a single Session. The interface lets us swap in a global `AtomicLong`-backed counter later (for cross-Session node tracking, useful for IDE plugins that retain references across save/restore cycles) or a content-hash-based "stable identity" generator without touching the rest of the data structure.

NodeIndex becomes a `LongLongMap` (e.g., Eclipse Collections' MutableLongLongMap, or hand-rolled long → long array hash):

```java
public final class NodeIndex {
    private final CstNode root;
    private final LongLongMap parents;          // childId → parentId
    private final LongObjectMap<CstNode> nodes; // id → node
    ...
}
```

### Incremental update algorithm

When `applyIncremental` is called:

1. Walk `oldPath` (root → oldPivot) and remove their entries (those records are unreachable in the new tree). O(depth).
2. Walk the new spliced subtree's interior. For each node, `parents.put(id, parentId)`. O(splicedSize).
3. Walk the new ancestor chain top-down. For each new ancestor A, iterate `A.children()` and `parents.put(child.id, A.id)`. Identity-shared children retain their internal entries from the old map (their IDs are the same; their internal parent mappings are still correct). O(depth × branchingFactor).

**Total cost: O(splicedSize + depth × branchingFactor)** — typically 100-300 operations vs the 91,000 we walk today. ~300× per-edit reduction.

### Subtree identity check (the key invariant)

When walking the new ancestor chain at step 3, we encounter children that are EITHER:
- Identity-shared with the old tree (same ID present in old `parents` map) — their internal subtree is unchanged
- New (not in old `parents` map) — their subtree needs full enumeration

We DETECT identity-sharing by ID lookup: `if (oldParents.containsKey(child.id))`. This is the magic that makes incremental update O(deltaSize) instead of O(N).

The check works because record sharing in Java means "if `oldNonTerminal.children()[i] == newNonTerminal.children()[i]`, they share the SAME instance" — and our ID is part of that shared instance.

### Memory impact

- Per-node: +8 bytes (long ID). For 91k nodes, ~728 KB extra. Acceptable.
- NodeIndex: switches from `IdentityHashMap<CstNode, CstNode>` to `LongLongMap`. Smaller per-entry overhead (16 bytes vs ~48), and primitive comparisons. Net memory similar; lookup faster.

### API impact

- Public API change: `CstNode.id()` is new. Pattern-match callers like `case Terminal(SourceSpan span, String rule, ...)` must be updated to include the leading `long id`.
- No-pattern-match callers (most users) only see a new accessor method. Source-compatible.

---

## 3. Lever B — Top-down pivot search

### Problem

`IncrementalSession.findBoundaryCandidate` walks UP from the cursor's `enclosingNode` pointer until the span engulfs the edit. When the cursor is far from the edit, the walk overshoots — it reaches a common ancestor of cursor and edit, which can be near-root (`ClassBody`).

The `lever-1` correctness puzzle (HANDOVER §6.2) is the result of trying to fix this without changing the data structure. Two attempts have failed:
1. Naive swap: `index.smallestContaining(editStart)` + walk-up — 12/100 parity failures.
2. Edit-aware boundary semantics (`smallestEnclosing(editStart, editEnd)`) — 31/100 parity failures + a fallback-rule bypass bug.

The root cause of both failures: the existing algorithm walks UP because the `IdentityHashMap`-based parent index makes that O(1) per step, while walking DOWN from root requires re-checking spans at each level. The walk-up shortcut exists to avoid the descent cost.

### Proposal

With Lever A's `LongObjectMap<CstNode> nodes`, descent from root is O(depth × averageBranchingFactor) — typically 100-200 operations on the Java grammar. That's cheap. Replace the warm-pointer walk-up with a top-down search:

```java
public Option<CstNode> smallestEnclosing(int editStart, int editEnd) {
    var current = root;
    while (current instanceof CstNode.NonTerminal nt) {
        var next = pickStrictlyContainingChild(nt, editStart, editEnd);
        if (next == null) {
            break;
        }
        current = next;
    }
    return Option.some(current);
}

private CstNode pickStrictlyContainingChild(NonTerminal nt, int editStart, int editEnd) {
    for (var child : nt.children()) {
        var span = child.span();
        if (span.startOffset() <= editStart && editEnd <= span.endOffset()) {
            return child;
        }
    }
    return null;
}
```

This replaces `findBoundaryCandidate` entirely. The cursor's `enclosingNode` becomes purely a UI affordance (where the editor cursor IS), not part of the pivot algorithm.

### Safe-pivot integration

Per HANDOVER §6.2, lever-1 also needs a "safe-pivot" concept: only descend through rules whose `parseRuleAt` output is provably parity-correct with full reparse. Static analysis at grammar-load time produces a `Set<String> safePivotRules` (rules starting with unambiguous terminals — Block `{`, Stmt mixed delim, etc.).

In the descent: at each level, the chosen child is only accepted as the pivot if its `rule()` is in `safePivotRules`. Otherwise we stop at the deepest safe ancestor.

The fallback-rule bypass bug (HANDOVER §6.2) becomes naturally avoidable: rules in `BackReferenceScan.unsafeRules` are NEVER in `safePivotRules`. The descent stops above them.

### Result

Lever-1 dissolves into a 30-line method. The 5-10 day correctness puzzle becomes a 1-day implementation + bench. Cursor moves are decoupled from pivot selection (Lever D below).

---

## 4. Lever C — IR-based interpreter/generator unification

### Problem

`PegEngine` (interpreter) and `ParserGenerator` (emits source) implement the SAME parsing semantics in completely different code shapes. We saw this directly in 0.4.1's perf arc:

- **HashMap rule lookup**: P1.1 needed in `PegEngine` only. Generator already resolves rule references at gen-time (free).
- **Singleton ParseMode**: P1.2 needed in `PegEngine` only. Generator doesn't use `ParseMode` at all.
- **LinkedHashSet furthestExpected**: P3.1 in `ParsingContext` (interpreter), P3.2 in `ParserGenerator` emission templates. Two commits, two implementations of the same data-structure swap.

Every algorithmic optimization is paid twice. The two paths drift.

### Proposal

Unify at the typed Expression IR level (the existing `peglib-core/grammar/Expression.java` sealed interface).

**Step 1 — extract a `peglib-rt` micro-module.** Contains:
- `SourceLocation`, `SourceSpan`, `CstNode` (with IDs from Lever A)
- `Trivia` types
- A small `ParseRuntime` with: literal-match, char-class-match, packrat cache, trivia management, furthest-failure tracking
- Per-Expression `parse_*` static methods (one per Expression type — `parseLiteral`, `parseSequence`, `parseChoice`, etc.)

**Step 2 — `PegEngine` becomes a thin orchestrator.** Walks the Expression IR, dispatches to the matching `parse_*` method in `ParseRuntime`. ~200 LOC instead of ~2.3k.

**Step 3 — `ParserGenerator` emits source that ALSO calls `ParseRuntime.parse_*` methods.** Generated parsers gain a single dependency: `org.pragmatica-lite:peglib-rt:0.5.0` (target ~50 KB jar). The "standalone parser" claim becomes "parser depends on `peglib-rt` only" — still drop-in for projects that include the rt jar.

**Optimizations land once.** When we improve `parseLiteral`'s failure path, both the interpreter AND generated parsers benefit immediately.

**Generator-specific optimizations** (e.g., gen-time rule reference resolution to direct method calls, gen-time choice dispatch via switch) are still emitted as wrappers around the rt calls. The rt is not the only optimization layer; it's the BASELINE.

### Tradeoffs

**Cost:** generated parsers gain a runtime dep (peglib-rt). Some users want zero dependencies — for them, the generator can OPTIONALLY emit the rt classes inline as inner classes (a "fat" mode) at the cost of larger generated source.

**Benefit:** the duplication tax goes away. An optimization or bug fix in the parsing algorithm needs ONE commit, applied to ONE place.

**Migration:** existing generated parsers from 0.4.x continue to work — they have the algorithm inlined. New 0.5.x generated parsers reference `peglib-rt`. Both can coexist.

---

## 5. Lever D — Cursor as separate value

### Problem

`IncrementalSession` records (currently): `(SessionFactory, String text, CstNode root, int cursor, CstNode enclosingNode, NodeIndex index, Stats)`.

Three concerns mixed:
- Tree state: `text`, `root`, `index`, `Stats`
- Cursor state: `cursor`, `enclosingNode`
- Configuration: `factory`

Every cursor move allocates a new Session (because Session is immutable and cursor is part of it). For interactive editing where editors call `moveCursor` before each `edit`, that's TWO allocations per keystroke (move + edit). Old sessions retained for undo grow memory linearly.

### Proposal

Split:

```java
public record Session(
    SessionFactory factory,
    String text,
    CstNode root,
    NodeIndex index,
    Stats stats
) {
    public EditOutcome edit(Cursor cursor, Edit edit) { ... }
    public ReparseOutcome reparseAll(Cursor cursor) { ... }
}

public record Cursor(int offset, long enclosingNodeId) {
    public Cursor moveTo(int newOffset, NodeIndex index) { ... }  // pure, no Session alloc
}

public record EditOutcome(Session newSession, Cursor newCursor) {}
public record ReparseOutcome(Session newSession, Cursor newCursor) {}
```

Editor code:
```java
var (session, cursor) = (initialSession, initialCursor);
while (editorHasInput()) {
    var input = editorReadInput();
    cursor = cursor.moveTo(input.cursorOffset(), session.index());  // cheap — Cursor record alloc
    var outcome = session.edit(cursor, input.edit());
    session = outcome.newSession();
    cursor = outcome.newCursor();
}
```

### Tradeoffs

- **Public API change.** External users of `Session` see a new method shape. Migration: rename `moveCursor` → cursor side; `edit(cursor, edit)` instead of just `edit(edit)`.
- **Undo/redo cleaner.** Save `(Session, Cursor)` snapshots. Restore both. Cursor is small (16 bytes); doesn't drag a Session if cursor moved between snapshots.
- **Move-cursor cost: zero Session allocation.** Cursor is 16 bytes; moveTo creates a new Cursor without touching the Session.

### Combined with Lever A's IDs

`Cursor` holds `long enclosingNodeId` instead of `CstNode enclosingNode`. The CST node itself is looked up via `index.nodes.get(id)` when needed. This means a Cursor can outlive the Session it was created with — useful for undo across multiple edits.

---

## 6. Migration path / phasing

The four levers are highly interdependent. Tackling them as one big-bang refactor risks the agent-cutoff failure pattern we saw repeatedly in 0.4.x. Tackling them in isolation is hard because Lever A (IDs) underpins Levers B and D.

Proposed phasing:

**Phase 0 — Spec and prototype branch (1 week)**
- Create `release-0.5.0-spike` from main
- Implement Lever A on a small grammar (calculator) end-to-end — proves the ID mechanism
- Bench Lever A vs current — confirms incremental NodeIndex update wins on synthetic edits
- GO/NO-GO decision

**Phase 1 — Lever A (IDs + indexing) on full Java grammar (1 week)**
- Add `long id` to all CstNode records
- Migrate all internal pattern-match callers
- Switch `NodeIndex` to `LongLongMap`
- Verify parity tests (897 + IncrementalParityTest 22 × 100)
- Bench against 0.4.3 baseline — should see major improvement on outliers

**Phase 2 — Lever B (top-down pivot) on top of Lever A (3 days)**
- Replace `findBoundaryCandidate` with top-down descent
- Add safe-pivot detection in grammar analysis
- Verify parity holds (this is where 0.4.x lever-1 failed; we expect Lever A to make it work)

**Phase 3 — Lever D (Cursor split) (3 days)**
- Refactor `Session` to remove cursor fields
- Introduce `Cursor` record
- Update `IncrementalParser.initialize` API to return `(Session, Cursor)`
- Update bench harness

**Phase 4 — Lever C (IR unification) (1 week)**
- Extract `peglib-rt` module
- Move per-Expression parse logic into `ParseRuntime`
- Refactor `PegEngine` to delegate
- Refactor `ParserGenerator` to emit `ParseRuntime` calls
- Verify all 897 tests + RoundTripTest still pass

**Phase 5 — Release (1 week)**
- Migration guide for downstream
- CHANGELOG with full breaking-change list
- 0.5.0 release per HANDOVER §7.3

**Total: 4-5 weeks elapsed; 2-3 weeks of focused engineering work.**

---

## 7. Risk register

### R1 — Lever A's ID mechanism could break record-equality assumptions (HIGH likelihood, MEDIUM impact)

Java records use ALL components in `equals()` and `hashCode()`. Adding `id` means two structurally-identical CstNodes from different parses are no longer equal — they have different IDs. Code that relies on `equals()` for CST comparison (e.g., the `RoundTripTest` corpus baselines) would break.

**Mitigation:** Override `equals` and `hashCode` to EXCLUDE `id`. Two nodes are equal iff their structural content matches; ID is metadata, not identity. Document the choice; verify with `RoundTripTest`.

### R2 — `peglib-rt` becomes a versioning headache (MEDIUM likelihood, MEDIUM impact)

If `peglib-rt` is released independently, generated parsers from peglib 0.5.x might require peglib-rt 0.5.x but be deployed with peglib-rt 0.5.y. Compatibility matrix grows.

**Mitigation:** Lock-step versioning — peglib-rt's version always matches peglib's. CI checks that generated parsers compile against the matching peglib-rt. Document the lock-step convention loudly.

### R3 — Lever B's safe-pivot heuristic excludes too many rules (MEDIUM likelihood, HIGH impact)

If `safePivotRules` is too restrictive (e.g., only Block, Stmt qualify), most edits walk up to a near-root pivot — defeats the purpose. Conversely, too permissive and we hit the trivia-attribution bugs that HANDOVER §6.2 documented.

**Mitigation:** Phase 2 includes empirical validation: run `IncrementalParityTest` with the new pivot algorithm; measure what % of edits land in a non-root safe pivot. If <80%, refine the heuristic. Ship-block on this metric.

### R4 — Phase 4 (Lever C) cascades through the entire test suite (MEDIUM likelihood, HIGH impact)

Refactoring `PegEngine` to delegate to `ParseRuntime` touches the algorithm core. Subtle behavioral differences (trivia attribution timing, packrat cache layout, error reporting format) could surface as test regressions.

**Mitigation:** Phase 4 runs the FULL test suite + parity tests + RoundTripTest as the gate. Do NOT advance to Phase 5 with any failures. If a behavioral difference is intentional and improves the design, document it as a 0.5.0 breaking change in CHANGELOG.

### R5 — Total scope creep beyond 4 weeks (HIGH likelihood, MEDIUM impact)

Architectural refactors notoriously balloon. The 4 levers are interdependent enough that a problem in one cascades.

**Mitigation:** Phase 0 is explicitly a GO/NO-GO gate. If the prototype shows Lever A's incremental update isn't materially faster, abandon and ship 0.4.x improvements instead. Don't sunk-cost into multi-week work.

### R6 — Downstream breaking-change pain (LOW likelihood, MEDIUM impact)

CstNode pattern-match callers in user code break (Lever A). Session API users break (Lever D). Generated parsers from 0.4.x continue to work but new generation depends on peglib-rt (Lever C).

**Mitigation:** Single major-version bump (0.5.0). Comprehensive migration guide. Deprecation period: keep 0.4.x branch alive for security patches if needed; no backports.

---

## 8. Open questions to resolve in Phase 0

1. **ID generation strategy.** RESOLVED — abstracted behind an `IdGenerator` interface (per spec §2). First implementation: per-Session counter. Future strategies (global counter for cross-Session tracking, content-hash for stable identity) can swap in without touching the data structure.

2. **`LongLongMap` library choice.** RESOLVED — abstracted behind a `LongLongMap` interface (mirrors the IdGenerator pattern). First implementation: hand-rolled linear-probing open-addressing on `long[]` arrays (~200 LOC, well-understood, low risk). Suffices for typical load factors with proper pre-sizing from descendant count. **Future swap target: Funnel hashing** per Farach-Colton, Krapivin, Kuszmaul, *"Optimal Bounds for Open Addressing Without Reordering"* (2025) — O(log² δ⁻¹) worst-case expected probe complexity, disproving Yao's 1985 conjecture. Justified for swap when profiling shows probe-sequence cost dominating in high-load scenarios (very large grammars, or NodeIndex pre-sizing inaccurate). Cite the paper in the implementation comment when the swap happens.

3. **Subtree identity check semantics.** RESOLVED — Phase 0 includes an explicit identity-preservation invariant test as a hard gate. Test asserts: after `TreeSplicer.spliceAndShift(...)`, every sibling subtree of the splice path satisfies reference equality (`siblingRoot == oldSiblingRoot`) with the corresponding pre-edit subtree. If the invariant holds, the incremental NodeIndex update is genuine O(splicedSize + ancestorDepth). If TreeSplicer is found to break sibling identity, fixing it is part of Phase 0's scope — likely small, since Java record sharing is the natural pattern; any non-sharing is probably accidental cascade from SourceSpan rebuild paths.

4. **Trivia attribution interaction.** RESOLVED — Phase 0 prototype's regression suite includes trivia-bearing edits as a hard gate: `%whitespace <- [ \t]+ / Comment` directive enabled in the calculator grammar; representative edits include (a) insert blank line before a method/rule, (b) delete a comment between two statements, (c) insert a comment inside an expression. For each: assert NodeIndex incremental update produces same result as full rebuild, AND sibling subtree refs are preserved (per Question 3). If `TriviaRedistribution.normalizeSplicedTrivia` is found to allocate new records for siblings (breaking identity), the incremental gain is smaller than projected — needs to be known before Phases 1-5.

5. **`peglib-rt` API surface.** RESOLVED — minimum surface. Public types: `SourceLocation`, `SourceSpan`, `CstNode` (sealed), `Trivia` (sealed), `LongLongMap`, `IdGenerator`. Per-Expression parse helpers (`parseLiteral`, `parseChoice`, `parseSequence`, etc.) live in an internal `ParseRuntime` accessible to the interpreter and generator-emitted code via package access, not as public API. Target jar size ~50 KB. No commitment to "hand-written parsers using peglib primitives" — that use case has no current demand and additive expansion in 0.5.x is non-breaking if it surfaces.

---

## 9. Summary table — current vs 0.5.0 state

| Concern | 0.4.3 today | 0.5.0 target |
|---|---|---|
| Node identity | JVM reference | Stable `long id` |
| NodeIndex update cost | O(N) full rebuild per edit | O(splicedSize + depth) incremental |
| Pivot algorithm | Walk up from cursor's spine | Top-down descent from root |
| Lever-1 status | Multi-day correctness puzzle | Resolved by descent + safe-pivot |
| Interpreter/generator divergence | Two implementations of same algorithm | Single shared `peglib-rt` |
| Cursor lifecycle | Coupled to Session record | Separate Cursor value |
| Move-cursor allocation | New Session record | New Cursor record (16 bytes) |
| Per-edit fixed overhead | ~10 ms (NodeIndex.build dominant) | ~1-2 ms (incremental update) |
| Projected p99 (1900-LOC fixture) | 53 ms | ≤ 16 ms |
| Projected p99 (75k-LOC PgSql fixture) | unmeasured (likely 200+ ms) | ≤ 30 ms |
| API stability | 0.4.x line stable | Single bump at 0.5.0 |

---

## 10. Recommended next actions

1. **Land 0.4.3** with current scope (3.88×→26× total perf arc, see CHANGELOG `[0.4.3]`).
2. **Create `release-0.5.0-spike`** branch from main post-0.4.3 merge.
3. **Phase 0 prototype** — Lever A on calculator grammar end-to-end. ~1 week.
4. **GO/NO-GO** based on Phase 0 bench data.
5. If GO: execute Phases 1-5 over 4-5 weeks.
6. If NO-GO: revisit individual levers tactically; the spec stays as a future reference.

---

**Last updated:** 2026-05-06 by post-0.4.3 retrospective + architectural session.
