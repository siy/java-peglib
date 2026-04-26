# Release Plan — 0.3.5 + 0.4.0

**Status:** locked 2026-04-25. Starts from `main` at v0.3.4.

Two releases close out the audit backlog and ready the library for IDE-plugin consumption (IntelliJ + VS Code first; Eclipse later).

- **0.3.5** — trivia round-trip + `%recover` wiring. Non-breaking.
- **0.4.0** — API consolidation + test hygiene + incremental v2.5 perf. Breaking.

Subagent convention per repo: `jbct-coder` for all code; `build-runner` for `mvn` invocations; `jbct-reviewer` for parallel focused review. Never invoke other coders.

---

## Release 0.3.5 — "trivia round-trip"

**Goal:** un-`@Disable` `RoundTripTest`, prove `%recover` works end-to-end. Revised estimate: ~5-7 working days (originally 4 — diagnostic on 2026-04-26 revealed three distinct bugs, not one).

### Diagnostic findings (2026-04-26)

Empirical round-trip diagnostic on `DeepGenerics.java` (smallest failing corpus file) showed two failure shapes, not one. Plus the originally-planned third. All three need fixing for byte-equal round-trip:

- **Bug A — pending-trivia snapshot is size-only.** `ParsingContext.savePendingLeadingTrivia()` returns just the list size; `restorePendingLeadingTrivia(int)` only truncates if the buffer grew. Items *consumed* inside a backtracked branch are permanently lost. Fix: change snapshot to a full `List<Trivia>` and restore by replacing buffer contents.
- **Bug B — packrat cache bakes in dynamic leading trivia.** `cacheAt` stores the wrapped node *including* its `leadingTrivia`. Cache hits at the same `(ruleName, position)` return that stale leading regardless of current pending. Result: trivia gets attributed to both an outer wrapper rule AND the cached inner rule (the duplication seen as `rec > src` in the diag). Fix: cache nodes with empty leadingTrivia; reattach current pending on cache hit.
- **Bug C — rule-exit pos-rewind missing.** The original plan item. After a rule body completes, any pending trivia not drained by a child gets dropped. Fix: at rule-exit, if pending is non-empty, rewind `pos` past it and attach to the last child's `trailingTrivia`. Manifests as `rec < src` in the diag (-16, -30, -301 byte cases).

### Phase 1A — Bug A: list-snapshot pending trivia (~0.5 day)

- `ParsingContext.savePendingLeadingTrivia()` → returns `List<Trivia>` snapshot
- `ParsingContext.restorePendingLeadingTrivia(List<Trivia>)` → replaces buffer with snapshot contents
- All call sites in `PegEngine` (Sequence ~1490, Choice ~1538, ZoM ~1587, OoM ~1652, Repetition, Optional, And, Not) updated to pass/receive `List<Trivia>`
- Mirror in `ParserGenerator` emission templates (~4406-4414)
- All 874 existing tests must stay green; baselines unchanged at this phase

### Phase 1B — Bug B: cache-safe leading trivia (~1 day)

- `parseRule` wraps with empty leading before caching; reattaches `ruleLeading` only on the path that returns
- Cache hit path: take cached node (no leading), reattach current `takePendingLeadingTrivia()` as leading
- Same logic in `parseRuleWithLeftRecursion` and `parseRuleWithActions`
- Mirror in generator's emitted rule wrappers (lines ~2454-2570 and LR variant ~2581-2700)
- Existing parity baselines should hold (this phase doesn't change spans, just trivia attribution invariants)

### Phase 1C — Bug C: cache-hit leading-trivia ambiguity (deferred to 0.3.6)

**Originally planned as rule-exit pos-rewind. Empirical diagnostic on 2026-04-26
revealed a different root cause.**

After Bug A and Bug B were committed, only 12 of 22 corpus fixtures round-trip
byte-equal. The remaining 10 fail because the packrat cache stores rule BODY
results whose `leadingTrivia` may be authoritative (from inner-rule wraps
when body is a Reference) or stale (from a backtracked prior context). Bug B's
`attachLeadingTrivia` helper short-circuits on empty current leading, which
preserves the cached attribution — correct when authoritative, wrong when
stale. Always-replacing regresses corpus passes from 12 → 5.

The fix requires distinguishing the two cases. Approach (sketched, not
implemented in 0.3.5):

1. Strip `leadingTrivia` from the body result at cache-store time so the
   cache never carries attribution.
2. On cache hit, recompute the rule-internal whitespace by inspecting
   `input[startPos .. bodyEndPos)` directly (deterministic — same input
   produces same trivia) and use it as the body's leading.
3. Apply the rebuilt outer-leading on top via `wrapWithRuleName`.

This redesign is multi-day work and may require coordinated changes in
both `PegEngine` and `ParserGenerator`. Scheduled for **release 0.3.6**
ahead of the IDE-plugin perf release (0.4.1).

Rule-exit pos-rewind (the originally-named Phase 1C) may still be needed
for some of the 10 remaining failure fixtures (specifically the loss
cases — `CompoundAssignments` -16, `SwitchExpressions` -13, etc). Whether
it is actually needed will become clear once Bug C is resolved.

See `docs/TRIVIA-ATTRIBUTION.md` § "Known limitation" for the diagnostic
data and resolution direction.

### Phase 3 — baseline regeneration (~0.5 day)

`NonTerminal` span end offsets shift across the corpus. Regenerate both baselines via the gated utilities:

- `BaselineGenerator` with `-Dperf.regen=1` → `peglib-core/src/test/resources/perf-corpus-baseline/`
- `InterpreterBaselineGenerator` with `-Dperf.regen=1` → `peglib-core/src/test/resources/perf-corpus-interpreter-baseline/`

Commit the regen as a **separate commit** with explicit "baseline-shift" CHANGELOG entry. Anyone diffing 0.3.4 baselines against 0.3.5 must see this called out.

### Phase 4 — `RoundTripTest` un-disable + verify 22/22 (~0.5 day)

- Remove `@Disabled` annotation and pointer comment
- Verify all 22 corpus files round-trip byte-equal
- Update `docs/TRIVIA-ATTRIBUTION.md` § "Known limitation" — promote it to "resolved in 0.3.5"

### Phase 5 — `%recover` wiring debug (~0.5 day)

The 0.3.4 audit found `{x@@@}` produces identical diagnostics with and without `%recover Block <- '}'`. Two hypotheses (handover §8.2):

1. Override isn't consulted at runtime → wiring bug; fix in `PegEngine` recovery path
2. Test scenario doesn't reach `Block` body recovery → recovery fires at outer `ZeroOrMore`

Diagnose by tracing recovery point selection. Fix whichever fault applies.

### Phase 6 — `%recover` proof test (~0.5 day)

Add a regression test in `peglib-core` that demonstrates measurably different recovery behavior between default and overridden `%recover`. Must fail before Phase 5 fix and pass after.

### Phase 0.3.5 release

Per established 15-step pattern (handover §5). Tag `v0.3.5`. Narrative release notes emphasizing baseline shift + trivia round-trip resolution.

---

## Release 0.4.0 — "API consolidation + perf"

**Goal:** absorb all P3 architectural breaks, finish test-assertion hygiene, ship incremental v2.5 perf for IDE-plugin consumption. Estimated 3 working weeks. **Breaking.**

### Phase 0 — v2.5 spike (1 day, GO/NO-GO gate)

**Before any 0.4.0 implementation work.** Validate `docs/incremental/SPEC.md` §5.4 v2.5 mental model holds against measurements.

Probe scope:
- Instrument cache invalidation cost on the 1,900-LOC fixture under current 0.3.4 incremental
- Quantify how much of the 325 ms/op is wholesale-invalidation vs. back-reference fallback vs. parse work
- Validate that span-rewriting remap can preserve enough cache entries to hit a credible target (proposing **< 10 ms/op**, relaxing SPEC's `< 1 ms`)

**Gate:**
- **GO** → schedule v2.5 in Phase 9. 0.4.0 ships full bundle.
- **NO-GO** → defer v2.5 to 0.4.1 with redesigned approach. 0.4.0 ships breaks + hygiene only. Re-plan v2.5 once probe data is in hand.

Write spike findings to `docs/incremental/V2.5-SPIKE.md` regardless of outcome.

### Phase 1 — parse-don't-validate `Grammar` (~1 day)

- Collapse `Grammar#validate()` into a `Result<Grammar>`-returning factory
- All `Grammar` construction goes through the factory; remove the post-construction validate step
- Update all call sites (lexer/parser, GrammarResolver, Maven plugin, tests)

### Phase 2 — factory rename sweep + test-assertion rewrite (~5 days, bundled)

The big mechanical sweep. Per-package commits within the release branch to keep diffs reviewable.

**Renames** (~20 production sites + every test):
- `of()` → `typeName()` (e.g., `Grammar.of(...)` → `Grammar.grammar(...)`)
- `create()` → `typeName()`
- `at()` → `typeName()`
- Catalogue every site in `docs/AUDIT-REPORTS/CONSOLIDATED-BACKLOG.md` factory-rename entries before starting

**Test-assertion rewrite** (~1000 sites): replace classic `assertTrue(result.isSuccess())` / `.unwrap()` with JBCT idiom that surfaces failure causes. Target pattern (confirm with first PR):

```java
result.onFailureDo(cause -> fail(cause.message()))
      .onSuccessDo(value -> { /* assertions on value */ });
```

Or whichever pattern the first reviewer green-lights as canonical. Bundle per-file with the rename sweep — touching the file once.

**Watch:** parity tests must stay green throughout. After each per-package commit, `mvn test` via `build-runner`.

### Phase 3 — `SessionImpl` → record (~0.5 day)

- File: `peglib-incremental/src/main/java/org/pragmatica/peg/incremental/internal/SessionImpl.java`
- Convert to record. Move state-mutation methods to return new instances.
- Drop `Impl` suffix per JBCT naming.

### Phase 4 — Mojo `execute()` → Result pipelines (~1 day)

- Files: `peglib-maven-plugin/src/main/java/org/pragmatica/peg/maven/{Generate,Lint,Check}Mojo.java`
- Rewrite `execute()` bodies as Result pipelines
- Add `@Contract` annotations on Mojo boundary methods (Maven calls into untyped land)

### Phase 5 — `Formatter` immutable builder (~0.5 day)

- Replace mutable builder with immutable `FormatterConfig` record + small builder
- File: `peglib-formatter/src/main/java/org/pragmatica/peg/formatter/Formatter.java`

### Phase 6 — `Result.lift` boundaries (~1 day)

Three sites flagged in handover §8.10:
- `PegEngine.createWithoutActions` → `Result<PegEngine>` symmetric with `create(...)`
- Action-dispatch try/catch → `Result.lift`
- `parseRequestBody` / HttpHandler in playground → `Result.lift`

### Phase 7 — nullable helpers → `Option` (~0.5 day)

- `tryIncrementalReparse` (peglib-incremental)
- `findBoundaryCandidate` (peglib-incremental)
- Sweep for any other internal nullable returns; convert to `Option`

### Phase 8 — `@Contract` annotations on remaining boundaries (~0.5 day)

- All CLI `main` entry points (`AnalyzerMain`, `PlaygroundRepl`, `PlaygroundServer`)
- Any remaining Mojo boundary methods
- Generator entry points exposed through Maven plugin

### Phase 9 — incremental v2.5 cache remap (~5 days, conditional on Phase 0 GO)

If Phase 0 said GO:
- Implement span-rewriting cache remap per the (validated) `docs/incremental/SPEC.md` §5.4 v2.5 design
- Target: < 10 ms/op single-char edit on 1,900-LOC fixture
- Update `peglib-incremental/src/main/java/org/pragmatica/peg/incremental/internal/SessionImpl.java` (now `Session` record) + cache layer
- Hold off on back-reference rule path — that's a separate, longer effort

### Phase 10 — bench-results commit (~0.5 day)

- Re-run JMH on Phase 9 result
- Commit raw JSON + summary to `peglib-incremental/target/bench-results/` then promote to `docs/bench-results/`
- Update `docs/PERF-FLAGS.md` and `docs/incremental/SPEC.md` with measured numbers

### Phase 0.4.0 release

Per established 15-step pattern. Tag `v0.4.0`. Narrative release notes lead with **breaking changes** + migration guide. Reference the `docs/AUDIT-REPORTS/CONSOLIDATED-BACKLOG.md` cleared P3 items.

---

## Risks + mitigations

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| Phase 0 spike says NO-GO | Medium | High — defers v2.5 perf | Ship 0.4.0 without v2.5; schedule 0.4.1 with redesigned approach. IDE plugin can integrate against 0.4.0 API and pick up 0.4.1 perf later. |
| Phase 1 ZoM infinite loops resurface | Medium | Medium — blocks 0.3.5 | Carry over the partial debugging notes from 0.2.4 attempt; budget extra day; if still stuck, ship `RoundTripTest` un-disabled with a smaller fixture set and document scope reduction. |
| Bundled rename + assertion sweep diff is unreviewable | Low | Medium | Per-package commits within release branch (not per-rename). Reviewer reads each commit isolated. |
| JBCT idiom for assertion rewrite isn't settled | Medium | Low | First PR proposes one canonical pattern; reviewer confirms; rest follows. Don't start the sweep until pattern is locked. |
| API break churn for downstream IDE plugin | High | Low | Plugin isn't built yet — absorb the breaks now while there's no consumer cost. |

---

## Out of scope

- §8.3 actions on left-recursive rules — documented limitation, no real demand
- §8.4 indirect left-recursion — out of scope per 0.2.9 SPEC
- §8.5 back-reference incremental fallback — substantial, separate effort, no consumer asks for it
- §8.7 in-parse playground tracer — current post-parse tracer is good enough for web UI

These remain documented in `docs/AUDIT-REPORTS/CONSOLIDATED-BACKLOG.md` as P3 / deferred. Revisit if a consumer surfaces.

---

## Execution checklist (per release)

- [ ] Branch `release-X.Y.Z` from `main`
- [ ] Bump 6 poms + README dependency snippet
- [ ] CHANGELOG entry added
- [ ] Implementation phases (this doc)
- [ ] `mvn test` green, then `mvn install` for downstream
- [ ] `mvn jbct:format` before push (CI will reject otherwise)
- [ ] PR opened against `main`, CI green
- [ ] Merge, tag, push tag
- [ ] `gh release create` with narrative notes
- [ ] Branch deleted local + remote

---

**Owner:** whoever picks this up. Update phase status as work progresses; mark phases ✓ complete in-place.
