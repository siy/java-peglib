# peglib-incremental

Cursor-anchored incremental CST reparsing for [peglib](../peglib-core). Sibling
module; zero source changes to `peglib-core` beyond the
`Parser#parseRuleAt(Class<? extends RuleId>, String, int)` API that landed in
0.3.0.

See [`docs/incremental/SPEC.md`](../docs/incremental/SPEC.md) for the full
design — this README is the quick-start.

## Status

v1 (0.3.1) — **correctness-first ship**. CST-only incremental reparsing, with
wholesale packrat-cache invalidation on every edit and a full-reparse
fallback for back-reference-bearing rules.

v2 (0.3.2) — **trivia-aware reparse splice** (SPEC §5.4 v2). Structural
splice preserves the spliced subtree's leading-trivia attribution (carried
from `parseRuleAt` per 0.2.4 attribution semantics). Opt-in
**trivia-only fast-path** rewrites edits whose range is entirely contained
in a single trivia run without re-invoking the parser; off by default
because it's only safe for grammars where in-trivia edits cannot change
adjacent-token tokenisation (Java 25 does NOT qualify — `>>` vs `> >`
parse differently). Enable via
`IncrementalParser.create(grammar, config, /*triviaFastPathEnabled*/ true)`.
New in 0.3.2: JMH harness under the `bench` profile (see `src/jmh/`) for
measuring per-edit latency against SPEC §8 targets.

**Carried limitation:** 0.2.4 shipped trivia attribution threading but NOT
the rule-exit position rewind needed for byte-for-byte round-trip on all
inputs — `RoundTripTest` stays `@Disabled` at the `peglib-core` level.
Incremental v2 does not fix that; it ensures the splice does the right
thing with 0.2.4's attribution semantics.

## Quick start

```java
import org.pragmatica.peg.grammar.GrammarParser;
import org.pragmatica.peg.incremental.*;

var grammar = GrammarParser.parse(grammarText).unwrap();
IncrementalParser parser = IncrementalParser.create(grammar);

Session s = parser.initialize(initialBuffer, /* cursor */ 0);

// user types 'x' at cursor
s = s.edit(s.cursor(), 0, "x");

// user deletes three chars before cursor
s = s.edit(s.cursor() - 3, 3, "");

// user moves cursor — no reparse
s = s.moveCursor(newOffset);

render(s.root());              // current CST
var stats = s.stats();         // reparseCount, fullReparseCount, lastReparseMs()
```

Sessions are immutable: every mutating call returns a new `Session`. Old
sessions remain valid references for undo stacks or snapshots.

## v1 scope and limitations

| Feature | Status | Notes |
|---|---|---|
| CST reparse | shipped | parity-fuzzed against full reparse on the 22-file perf corpus |
| Back-ref grammars | fallback | rules containing `$name` trigger full reparse on every edit (SPEC §6.3 / §10) |
| Action replay | **not supported** | actions run only on full-reparse fallback (SPEC §6.4) |
| Trivia redistribution | **shipped in 0.3.2** (opt-in fast-path) | `TriviaRedistribution` helper + opt-in flag; safe for grammars that don't re-tokenise on whitespace |
| Packrat cache carry-over | **dropped on edit** (v1 simple) | v2.5 may add span-rewriting remap |
| Multi-threaded edits | not supported | sessions are immutable but concurrent `edit()` on the same session is the caller's concern |

## Design decisions

Decisions made inline during v1 implementation (per the design-questions list
in the 0.3.1 brief):

1. **Session immutability via reference-shared CstNodes.** `Session` is a
   record-ish value object. On edit, the splice rewrites only the ancestor
   spine of the reparsed subtree (SPEC §4.2) and shifts any sibling CST nodes
   that start at or after the edit end by `delta`. Untouched subtrees before
   the edit retain reference identity — no copy, no allocation. Parent
   pointers are kept in a per-session `IdentityHashMap` (`NodeIndex`)
   rebuilt after every edit.
2. **Edit atomicity.** The public API is one edit at a time
   (`Session#edit(Edit)`). Batch semantics are a caller-level fold — see
   `SPEC §4.3` "Batch edit" example. No multi-edit atomic primitive in v1.
3. **Back-reference detection at grammar-parse time.** `BackReferenceScan`
   walks the expression tree once inside `SessionFactory.create(...)` and
   caches the transitively-unsafe rule-name set. Per-edit overhead is a
   single `Set#contains` check against the pivot rule's name.

## Correctness model

The oracle for every test is a fresh full parse of the post-edit buffer:
`CstHash(session.root()) == CstHash(PegParser.fromGrammar(grammar).parseCst(session.text()).unwrap())`.
See SPEC §6.1.

`CstHash` intentionally excludes trivia (whitespace/comments): v1 guarantees
structural parity of the rule-node spine, not trivia-character assignment.
See SPEC §4.4 and the v2 trivia-redistribution roadmap item.

## Tests

- `IncrementalParityTest` — 22 corpus files × N random edits per file,
  default N=100 (2,200 checks), configurable via
  `-Dincremental.parity.editsPerFile=N` (SPEC §7.1 target is N=1000 / 22,000
  checks; raise at CI).
- `IncrementalTriviaParityTest` (0.3.2) — same 22-file corpus, random edits
  biased toward whitespace regions (insert/delete/replace inside existing
  trivia runs; run deletes). Default 50 edits/file (1,100 checks),
  configurable via `-Dincremental.parity.trivia.editsPerFile=N`.
- `TriviaRedistributionTest` (0.3.2) — hand-crafted edits exercising the
  trivia-only fast-path: insert/delete/replace inside whitespace, edits
  spanning trivia + token (expected fallthrough to structural), repeated
  trivia mutations.
- `ReparseBoundaryTest` — SPEC §6.3 edge-case matrix.
- `IdempotencyTest` — SPEC §7.3 inverse-edit parity.
- `SessionApiTest` — SPEC §4.4 API invariants.
- `BackReferenceFallbackTest` — SPEC §10 mitigation.
- `examples/BasicEditorLoopExample` — executable editor-loop illustration.

### Running the parity fuzz at full scale

```
mvn -pl peglib-incremental test -Dtest=IncrementalParityTest -Dincremental.parity.editsPerFile=1000
```

## Runtime expectations

v1 is **correctness-gated**, not performance-gated. The SPEC §8 targets
(sub-1 ms single-char, sub-5 ms word) are aspirational for v1 — the
wholesale cache invalidation on every edit means many edits will reparse a
larger subtree than strictly necessary, and very deep edits can reach the
root and fall back to a full reparse. Measure via
`session.stats().lastReparseMs()` in your workload; the reparse-boundary
algorithm will be tightened in v2.5 (span-rewriting cache remap) if
profiling indicates it pays back.

JMH benchmark (SPEC §7.4) lands in 0.3.2. Run with:

```
cd peglib-incremental
mvn -Pbench -DskipTests package
java -jar target/benchmarks.jar org.pragmatica.peg.incremental.bench.IncrementalBenchmark
```

Variants: `initialize`, `singleCharEdit`, `wordEdit`, `lineEdit`,
`fullReparse`, `undoRestore`. Smoke-run results are at
`docs/bench-results/incremental-v1-smoke.json`; a full matrix is deferred
to a later release cycle when CI time allows. Early single-iteration
numbers on JDK 25 / Apple Silicon for `singleCharEdit` land well above the
SPEC §8 target (~325 ms/op on first measurement, vs. sub-1 ms target),
driven by v1's wholesale cache invalidation and the Java grammar's
back-reference-rule full-reparse fallback policy. SPEC §5.4 v2.5
(span-rewriting cache remap) is the next lever.
