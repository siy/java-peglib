# Trivia Adversarial Corpus — Step 2 Findings

Step 2 of the trivia attribution context-dependency investigation.
Tests at `peglib-core/src/test/java/org/pragmatica/peg/perf/TriviaAdversarialTest.java`.
17 tests added — 16 enabled passing, 1 `@Disabled`. Production code untouched.

## Bottom line

| Severity         | Count | Items                                                |
| ---------------- | ----- | ---------------------------------------------------- |
| Definite bug     | 0     |                                                      |
| Latent bug       | 2     | Bug 1 (gen size-only restore), Bug 2 (Optional cut)  |
| Robust-by-luck   | 2     | Cache hit parity, Choice backtrack same-position     |
| Robust-by-design | 3     | Bug C' nested, Predicate symmetry, %whitespace pure  |

**Did Target #1 surface as a definite bug?** No. Two probes
(`generator_choiceBacktrackAfterDrain_parityProbe`,
`generator_choiceBacktrackDifferentStructure_parityProbe`) confirm
interpreter and generator produce byte-identical trivia text. The
size-only restore IS structurally weaker than `List.copyOf` snapshot
(`ParserGenerator.java:6768-6776` vs `ParsingContext.java:526-528`), but
the second alternative re-runs `skipWhitespace` at the rolled-back
position and rebuilds pending equivalently. Bug 1 reclassified as
**robust-by-luck**.

## Per target

### #1 Generator size-only restore  (3 tests passing)
- Interpreter and generator agree on backtracking-choice trivia.
- Code-level asymmetry is real; black-box impact is masked.

### #2 Bug C' nested boundary  (4 tests passing)
- Empty-ZoM tail with multi-sibling prefix — orphan WS attached.
- Doubly-nested empty ZoM, comment+empty-ZoM, empty Optional tail.
- All round-trip. Robust-by-design. Pinned as regression net.

### #3 parseRuleAt context-loss  (3 tests passing)
- Documents narrowing of wrapper.leadingTrivia from fresh-context entry.
- Item span text from parseRuleAt equals full-parse Item span text.
- Behaviour as documented; tests pin current contract.

### #4 Cache hit/miss parity  (2 tests passing)
- Two call sites, predicate-seeded cache hit — both produce correct
  leading. Robust-by-luck (relies on Target #7).

### #5 Predicate symmetry  (2 tests passing)
- And/Not save+restore via `savePendingLeadingTrivia` (full snapshot).
- Robust-by-design.

### #6 Optional CutFailure  (1 test `@Disabled`)
- `parseOptionalWithMode` line 1936 returns CutFailure without restoring
  `entryPendingSnapshot`.
- **Bug 2 latent.** No black-box surface — when cut fires the outer
  parse fails and pending is unobservable through the returned CST.
  Observability needs instrumented `ParsingContext` or upstream
  recoverable cut-failure-from-Optional.
- Fix is trivial: restore snapshot at line 1937. Risk-free even masked.

### #7 %whitespace purity  (1 test passing)
- Sub-rule-using whitespace; cache hit reattach matches cache miss.
- Robust-by-design for the tested fixture. Bug-B correctness *requires*
  whitespace purity. Step 3 should formalise as grammar-validation rule.

### Fuzz  (1 test passing)
- 100 random nested-pair inputs. parseRuleAt subtree span text equals
  full-parse subtree span text for every Value/Pair node visited.
- No new mismatches beyond Target #3's documented context-loss.

## Reproducers

**Bug 1 (latent in generator):**
```
Start <- Triple / Pair
Pair <- 'x' 'y'
Triple <- 'x' 'y' 'z'
%whitespace <- [ ]+
```
Input `"x y"`. Generator's `restorePendingLeading(int)` (line 6772)
truncates only — drained items not recovered. Masked because the second
alt re-runs skipWhitespace.

**Bug 2 (Optional CutFailure):** `PegEngine.java:1936`. Add
`ctx.restorePendingLeadingTrivia(entryPendingSnapshot);` before
`return result;` on line 1937.

## Suggested next direction

1. Fix Bug 2 — one-line change at `PegEngine.java:1937`.
2. Step 3 design: treat the size-only-vs-list-snapshot asymmetry as a
   semantic invariant relying on `skipWhitespace` re-skip after
   rollback. Document this in `docs/TRIVIA-ATTRIBUTION.md` rather than
   "fix" the generator.
3. Search for a grammar where Bug 1 manifests black-box (a Choice whose
   second alt skips the WS-bearing region entirely). If none exists,
   that confirms size-only path is sufficient.
