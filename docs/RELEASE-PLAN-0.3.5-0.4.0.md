# Release Plan — 0.3.5 + 0.4.0

**Status:** locked 2026-04-25. Starts from `main` at v0.3.4.

Two releases close out the audit backlog and ready the library for IDE-plugin consumption (IntelliJ + VS Code first; Eclipse later).

- **0.3.5** — trivia round-trip + `%recover` wiring. Non-breaking.
- **0.4.0** — API consolidation + test hygiene + incremental v2.5 perf. Breaking.

Subagent convention per repo: `jbct-coder` for all code; `build-runner` for `mvn` invocations; `jbct-reviewer` for parallel focused review. Never invoke other coders.

---

## Release 0.3.5 — "trivia round-trip" — ✅ SHIPPED 2026-05-01

**Final status:** all 22 perf-corpus fixtures round-trip byte-equal via the
generated parser. `RoundTripTest` re-enabled. Five distinct bugs (Bug A
through Bug C'') were required, three discovered after the initial plan was
written. `%recover` directive wired end-to-end on the interpreter side
(generator-side per-rule overrides remain deferred to 0.3.6).

**Goal:** un-`@Disable` `RoundTripTest`, prove `%recover` works end-to-end. Revised estimate: ~5-7 working days (originally 4 — diagnostic on 2026-04-26 revealed three distinct bugs, not one — and a follow-up diagnostic on 2026-04-30 revealed two more, all fixed in 0.3.5).

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

### Phase 1C — Bug C: cache-hit leading-trivia ambiguity (✅ shipped)

Empirical diagnostic isolated the duplication to the **generator** (interpreter
was already correct). The generator's cache stored the wrapped-with-leading
body, so subsequent cache hits with empty pending preserved stale leading
through `attachLeadingTrivia`'s short-circuit, attaching trivia at multiple
nesting levels.

Fix: cache an empty-leading wrap, return the actual-leading wrap. Cache
hits now apply current pending without inheriting stale state. Interpreter
unchanged — already cached the body, not the wrap.

After this fix, generator dropped from 12/22 → 5/22 round-trip pass
because pre-existing trivia gaps (previously masked by Bug C duplication
accidentally compensating for them) became visible.

### Phase 1C' — Bug C': rule-exit trailing-trivia attribution (✅ shipped)

The visible loss cases revealed by Bug C: trivia consumed by the body's
last inter-element `skipWhitespace` (e.g. before a zero-width tail like
empty ZoM/Optional) ends up in `pendingLeadingTrivia` with no child to
claim it. Originally planned as rule-exit pos-rewind, but rewinding pos
broke predicate combinators (`!isPredicate(element)` skip-no-whitespace
semantics).

Fix: at rule-exit success path, attach pending trivia to the last child's
`trailingTrivia` (or to the rule node's trailing if children empty).
**Pos is not rewound.** Applied symmetrically in `PegEngine` and
`ParserGenerator`.

After this fix, **interpreter reached 22/22**. Generator reached 21/22 —
the remaining `FactoryClassGenerator.java.txt` fixture had a duplicate
trailing comma exposed by Bug C''.

### Phase 1C'' — Bug C'': generator Sequence children rollback (✅ shipped)

Generator emitted Sequences using the rule-method's outer `children` list
directly. On element failure, location and pending were restored — but
children were not, so partial child additions from earlier elements of
the failed Sequence stayed in the parent's tree. Symptom: the trailing
comma in enum-constant lists appeared as a child of both the inner
ZoM-NT and the outer Sequence.

Fix: snapshot `children` at Sequence start; restore on element failure
(both cut-failure and regular-failure branches). Interpreter uses a
local `children` list per `parseSequenceWithMode` call so was already
correct.

After this fix, **generator reached 22/22**.

See `docs/TRIVIA-ATTRIBUTION.md` for the full attribution model.

### Phase 3 — baseline regeneration (✅ shipped)

Only `large/FactoryClassGenerator.java.txt` shifted (the Bug C'' children-rollback fix removes a duplicate trailing comma in enum lists). Other 21 corpus baselines unchanged. Regenerated via `BaselineGeneratorRunner` (`-Dperf.regen=1`); committed as `9ac3307` with explicit "baseline-shift" CHANGELOG callout.

### Phase 4 — `RoundTripTest` un-disable + verify 22/22 (✅ shipped)

`@Disabled` removed; 22/22 corpus files round-trip byte-equal. `docs/TRIVIA-ATTRIBUTION.md` updated.

### Phase 5 — `%recover` wiring debug (✅ shipped)

Root cause identified: rule-level recovery override was popped in `parseRule`'s `finally` block before `parseWithRecovery` consulted it. Fix: capture the failed rule's override into a per-context `pendingFailureRecoveryOverride` field BEFORE the pop, with deepest-wins semantics. Committed as `ca2ac9f`.

### Phase 6 — `%recover` proof test (✅ shipped)

`RecoverDirectiveProofTest` (interpreter-side) committed alongside Phase 5 fix. Uses `:` as override terminator (outside default char-set) so override-vs-default discriminator is unambiguous. Pre-fix the test fails; post-fix passes.

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
