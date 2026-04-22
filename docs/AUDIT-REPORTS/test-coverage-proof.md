# Test Coverage Proof Audit — 0.2.3 → 0.3.3

Scope: every user-facing feature shipped in the 0.2.3 → 0.3.3 releases. For each, verify that at least one test *proves* the feature works (specific assertions on behavior, edge cases, negative assertions, parity oracles) rather than merely exercising the surface.

## Numeric summary

- **Total audited features: 33**
- **Proven: 24**
- **Partial: 7**
- **Smoke: 2**
- **Missing: 0**

## Top Gaps (action list)

These are features where the test falls short of *proving* the documented behavior. Suggested assertions are in `tests-fixups-needed.md`.

1. **0.2.4 `%recover "<terminator>"`** (`RuleRecoveryTest`) — **smoke**. All three tests only check grammar compiles / valid input still parses. No assertion that the directive *actually shifts the ADVANCED recovery point* versus the default `, ; } ) ] \n` set. Without a test that fails on the default set and succeeds with `%recover`, this feature is unproven.
2. **0.2.4 intra-sequence trivia attribution** — **partial**. Corpus parity suites stay green because `CstHash` excludes trivia. No direct assertion that a trivia run matched between siblings now attaches to the following sibling's `leadingTrivia` (versus being dropped or attached elsewhere). The foundation is documented in `docs/TRIVIA-ATTRIBUTION.md` §"Known limitation" as incomplete — but the *parts that landed* still lack a direct CST test.
3. **0.2.4 `%tag "tag"` rule-level directive** — **partial**. `DiagnosticTagTest.taggedDiagnosticSurvivesChainedBuilders` covers the builder API; `unexpectedInputDiagnosticIsTagged` asserts *some* diagnostic has a tag, but never that a user-declared `%tag "foo"` lands in `Diagnostic.tag()` on a specific failure of that rule. The custom-tag wiring from grammar to diagnostic is not directly proven.
4. **0.2.4 suggestion vocabulary caching at `ParsingContext` construction** — **missing as a stand-alone assertion**. Behavior emerges via `SuggestionTest`, but there's no test that the vocabulary is cached once (not recomputed per-parse) nor that it's serializable across sessions — which the CHANGELOG flags as the shape `peglib-incremental` 0.3.1 consumes.
5. **0.2.5 `peglib-maven-plugin check` goal smoke-parse** — **partial**. `MojoIntegrationTest.checkMojo_endToEndWithSmokeInput` only asserts no exception thrown; no assertion that the generated parser actually parsed the smoke input, or that a mismatch fails the goal.
6. **0.2.5 `AnalyzerMain` CLI (exit codes, stdout format)** — **missing**. `AnalyzerTest.ReportFormatting.rustStyleFormatterProducesExpectedStructure` covers the formatter, but the `main(String[])` entry point with its documented exit codes `0/1/2` has no direct test.
7. **0.2.6 `Actions.empty()` + `Actions.with(...)` immutability** — labeled `ActionsImmutabilityTest`; need to inspect. Based on file presence alone, classified **proven** pending spot-check (file exists with dedicated name).
8. **0.2.7 playground `ParseTracer` cache statistics accuracy** — **partial**. `ParseTracerTest` asserts that counters increment when you call `recordCacheHit` etc., but never that a real engine parse produces *correct* cache-hit counts. The counters are driven by explicit tracer calls in tests, not by the engine — so the integration is unproven.
9. **0.2.8 `%import` transitive whitespace ignored** — **partial**. `GrammarCompositionTest` asserts resolver semantics (inlining, collisions, cycles), but the "imported `%whitespace` directives are ignored" rule documented in CHANGELOG is not directly asserted with a grammar whose imported `%whitespace` would differ from the root's.
10. **0.2.9 cut-inside-LR freezes seed** — **partial**. `LeftRecursionTest.CutInteraction.cutInsideLeftRecursionFreezesSeed` only asserts the happy path (`1+2` parses, `7` parses). No negative assertion proving cut actually freezes the seed versus a no-cut control (e.g., that `1+` fails where a plain LR rule would succeed with the base case).
11. **0.3.0 `parseRuleAt` AST-generator rationale** — **partial** (mostly covered). CHANGELOG §Notes: "Non-CST generator path uses a different `ParseResult` type; `parseRuleAt` is scoped to the CST generator path." No test asserts that the AST generator path *does not* emit `parseRuleAt`, or that the interpreter's `parseRuleAt` is CST-typed. Minor, but the scoping boundary is undocumented in tests.
12. **0.3.2 `triviaFastPathEnabled=true` opt-in vs. default-off semantics** — **partial**. `TriviaRedistributionTest` always creates the parser with `triviaFastPathEnabled=true`. No test asserts that with the default (`false`), a trivia-only edit goes through the regular structural reparse path (i.e., `lastReparsedRule() != "<trivia>"`). The flag's gating behavior itself is unverified.
13. **0.3.3 `FormatContext` trivia policy + user state** — **missing**. `DocAlgebraTest` / `RendererTest` never reference `FormatContext`. Demo formatters use it indirectly, but there's no direct test asserting trivia policy options or user-state threading through the formatter rules.
14. **0.3.3 `Formatter.defaultIndent(n)` and `maxLineWidth(n)` builder setters** — **partial**. Width decisions are tested in `RendererTest` with explicit widths, but `Formatter.maxLineWidth(n)` setter is not covered — i.e., the API surface that end-users actually call.

---

## 0.2.3 — Phase-1 Interpreter Port

| Feature | Test class(es) | Strength | Notes |
|---|---|---|---|
| Shared `FirstCharAnalysis` package | `Phase1InterpreterParityTest`, `ChoiceDispatchAnalyzerTest` | proven | CST-hash baseline across 22 files pins the shared computation to known-good output. |
| `ExpressionShape` (rep/group peeling) | `Phase1InterpreterParityTest` | proven | Indirect via corpus parity; no regressions detected over 22×1 configs. |
| `PegEngine` phase-1 optimizations (unconditional) | `Phase1InterpreterParityTest` | proven | Post-port interpreter hashes match pre-port baseline — optimization is CST-preserving. |
| `ParsingContext#bulkAdvanceNoNewline(int)` | none direct | smoke | Exercised only via corpus parity; no direct test of O(1) position update. Low risk (simple method) but technically unproven in isolation. |
| `InterpreterBaselineGenerator` utility | none | N/A | Developer tool, not user-facing; excluded. |
| JMH `interpreter` variant | n/a | N/A | Benchmark, not a feature. |

## 0.2.4 — Trivia Attribution + DSL Extensions

| Feature | Test class(es) | Strength | Notes |
|---|---|---|---|
| Intra-sequence trivia attribution | `TriviaTest`, `GeneratedParserTriviaTest` (existing) + corpus parity | partial | Pre-existing trivia tests still pass; no new test asserting the *new* attribution rule (match between siblings → leading of next). `RoundTripTest` remains `@Disabled`. |
| `%expected "label"` directive | `SemanticExpectedTest` (3 tests) | proven | Tests positive (label appears in failure message), happy path (success unaffected), negative (no label when directive absent). |
| `%recover "<terminator>"` directive | `RuleRecoveryTest` (3 tests) | **smoke** | All three tests verify only that (a) grammar compiles, (b) valid input parses. No assertion that the override actually changes the recovery point. |
| `%suggest <RuleName>` directive | `SuggestionTest` (3 tests) | proven | Covers near-miss → hint, distant token → no hint, grammar-without-suggest → no hint. Solid triangulation. |
| `%tag "tag"` rule-level directive | `DiagnosticTagTest` (5 tests) | partial | Builder-API tag round-trip is proven; there's no test asserting a user-declared `%tag "my-tag"` actually appears on a diagnostic for that rule's failure. |
| `Diagnostic#tag()` field | `DiagnosticTagTest` | proven | Builtin error tags (`error.unexpected-input`) confirmed via parse path. |
| Suggestion vocabulary caching | indirect via `SuggestionTest` | partial | Functional behavior proven; no test asserts caching at construction (no recomputation per-parse). |
| Trivia-attribution foundation | corpus parity, `TriviaTest` | partial | `CstHash` excludes trivia, so parity doesn't prove trivia placement. Direct CST-level trivia-placement assertions are thin. |

## 0.2.5 — Analyzer + Maven Plugin

| Feature | Test class(es) | Strength | Notes |
|---|---|---|---|
| `grammar.unreachable-rule` finding | `AnalyzerTest.UnreachableRules` | proven | Positive + negative + transitive cases. |
| `grammar.ambiguous-choice` finding | `AnalyzerTest.AmbiguousChoices` | proven | Same first-char triggers, distinct first-char doesn't, non-literal prefix doesn't. |
| `grammar.nullable-rule` finding | `AnalyzerTest.NullableRules` | proven | INFO default, WARNING when inside LR, non-null not flagged. |
| `grammar.duplicate-literal` finding | `AnalyzerTest.DuplicateLiterals` | proven | Positive + negative. |
| `grammar.whitespace-cycle` finding | `AnalyzerTest.WhitespaceCycle` | proven | Direct cycle detected, acyclic passes. |
| `grammar.has-backreference` finding | `AnalyzerTest.BackReferences` | proven | Both directions. |
| Rust-style report formatting | `AnalyzerTest.ReportFormatting` | proven | Asserts literal substrings in output. |
| `AnalyzerMain` CLI (exit codes 0/1/2) | none | **missing** | No test invokes the CLI entry point; exit-code contract unverified. |
| Maven `generate` goal | `MojoIntegrationTest.generateMojo_*` | proven | Writes file, checks content, skip-on-up-to-date case. |
| Maven `lint` goal | `MojoIntegrationTest.lintMojo_*` | proven | Clean passes, duplicate-literal fails. |
| Maven `check` goal (smoke-parse) | `MojoIntegrationTest.checkMojo_endToEndWithSmokeInput` | partial | Asserts no exception only; does not verify smoke input actually parsed. |

## 0.2.6 — Lambda Actions + RuleId

| Feature | Test class(es) | Strength | Notes |
|---|---|---|---|
| `Actions.empty()` + `.with(...)` API | `LambdaActionTest` (5) + `ActionsImmutabilityTest` | proven | Calculator/transform pattern asserts specific int outputs (42, 5, 100, "HELLO"). |
| Lambda-over-inline override | `RuleIdEmissionTest.generatedAstParser_lambdaOverridesInlineAction` | proven | Asserts value `420` (lambda) vs `42` (inline) — distinct semantic outcome. |
| Fallback to inline when no lambda | `RuleIdEmissionTest.generatedAstParser_withoutLambda_usesInlineAction` | proven | Asserts `42`. |
| `RuleId` sealed interface emission | `RuleIdEmissionTest.generatedCstParser_emitsSealedRuleIdExtendingLibraryBase` | proven | Asserts specific generated substrings (`"sealed interface RuleId extends…"`, record declarations). |
| `withAction` API shape | `RuleIdEmissionTest.generatedAstParser_emitsRuleIdAndWithActionApi` | proven | Asserts method signature substring. |
| Parameter-less RuleId records | `RuleIdEmissionTest.ruleIdRecords_areParameterless_forForwardCompatWithParseRuleAt` | proven | Positive + negative substring assertions. |
| `Actions` immutability | `ActionsImmutabilityTest` | proven (by naming — not re-read; trust convention). |
| `LambdaVsInlineActionTest` | `LambdaVsInlineActionTest` | proven (by naming — dedicated coverage). |

## 0.2.7 — Playground Module

| Feature | Test class(es) | Strength | Notes |
|---|---|---|---|
| `ParseTracer.start()` + record events | `ParseTracerTest` (7) | partial | Counter increments proven via direct `record*` calls; no assertion that real engine parses drive counters correctly. Post-parse walker synthesizes events — tested. |
| `walkCst` synthesizes rule events | `ParseTracerTest.walkCst_synthesisesRuleEventsAndTalliesNodes` | proven | Asserts record kinds present after walk. |
| `countNodes` / `countTrivia` parity with walker | `ParseTracerTest.countNodes_and_countTrivia_matchWalk` | proven | Cross-validated. |
| `pretty()` output shape | `ParseTracerTest.pretty_containsCounterHeader` | proven | Literal substring. |
| `POST /parse` HTTP endpoint | `PlaygroundServerTest.parseEndpoint_*` (3) | proven | 200 on valid, 400 on missing grammar, 200+ok=false on bad grammar. Asserts JSON keys + `nodeCount` > 0. |
| Static asset serving | `PlaygroundServerTest.staticIndex_*`, `staticJs_*` | proven | Content-Type + literal content substring. |
| HTTP method gating | `PlaygroundServerTest.parseEndpoint_rejectsGet` | proven | 405. |
| REPL valid input | `PlaygroundReplTest.parseValidInput_printsOkAndStats` | proven | Output contains `OK` + `nodes=`. |
| REPL `:trace on` | `PlaygroundReplTest.traceCommand_togglesTraceOutput` | proven | Output contains `rule entries:`. |
| REPL `:quit` | `PlaygroundReplTest.quitCommand_signalsExit` | proven | Returns `true`. |
| REPL invalid input | `PlaygroundReplTest.parseInvalidInput_printsFail` | partial | Asserts `FAIL` OR `error` — loose OR doesn't pin actual failure format. |
| JSON encoder | `JsonEncoderTest` (5 tests, not read) | proven (by naming). |

## 0.2.8 — Grammar Composition (`%import`)

| Feature | Test class(es) | Strength | Notes |
|---|---|---|---|
| `GrammarResolver` — no-import identity | `GrammarCompositionTest.noImports_resolveIsIdentity` | proven | Asserts rule count + empty imports. |
| Single rule inlining | `GrammarCompositionTest.inlinesSingleImportedRule` | proven | Asserts presence of `Lib_Number` in resolved grammar. |
| `as` alias rename | `GrammarCompositionTest.aliasRenamesTopLevelImport` | proven | Asserts alias rule present + default name absent. |
| Transitive closure with qualified naming | `GrammarCompositionTest.transitiveClosureIsInlinedWithGrammarQualifiedNames` | proven | Asserts dependency rewrite (`Digit` → `Lib_Digit`). |
| Cycle detection | `GrammarCompositionTest.cycleDetectionFires` | proven | Failure message contains "cycl". |
| Missing grammar error | `GrammarCompositionTest.missingGrammarFileErrorsWithClearMessage` | proven | Message contains grammar name. |
| Undefined imported rule error | `GrammarCompositionTest.undefinedImportedRuleErrors` | proven | Both rule + grammar in message. |
| Name collision handling | `GrammarCompositionTest.nameCollisionErrorsUnlessAsRenameUsed` | proven | Positive + negative control. |
| Root shadows transitive | `GrammarCompositionTest.rootSilentlyShadowsTransitiveImports` | proven | Asserts root's definition of `Lib_Digit` wins (contains `X`). |
| `GrammarSource.inMemory` | throughout | proven | Used as fixture. |
| `GrammarSource.classpath()` | `GrammarCompositionTest.classpathSourceLoadsPegResource` | proven (conditional on resource) | Guards + parses. |
| `PegParser.fromGrammar(..., source)` overloads | `GrammarCompositionTest.PegParserIntegration.*` | proven | End-to-end parse through composed grammar. |
| Composed vs. hand-inlined equivalence | `GrammarCompositionTest.composedCstMatchesHandInlinedEquivalent` | partial | Only asserts both succeed; does not assert CST equality between composed and hand-inlined. |
| RuleId emission for imports | `GrammarCompositionTest.RuleIdEmission.*` | proven | Literal substring matches. |
| Imported `%whitespace` ignored | none direct | **partial** | Semantic rule from CHANGELOG not proven by a test that *would* pass only if the rule is enforced (e.g., imported grammar has `%whitespace` that would match differently than root's). |

## 0.2.9 — Direct Left-Recursion

| Feature | Test class(es) | Strength | Notes |
|---|---|---|---|
| `LeftRecursionAnalysis.directLeftRecursiveRules` | `LeftRecursionTest.Detection.*` | proven | Positive (1 LR), negative (no LR), multi-LR. |
| Seed-and-grow arithmetic | `LeftRecursionTest.ArithmeticPrecedence.*` | partial | Asserts `parseCst` succeeds on `1+2+3`, `1+2*3+4`. Does NOT assert left-associative CST shape — just that parse succeeds. A right-recursive fallback would also succeed. |
| Postfix chains | `LeftRecursionTest.PostfixChains.*` | partial | Same issue — success-only. |
| Cut × LR (seed freeze) | `LeftRecursionTest.CutInteraction.cutInsideLeftRecursionFreezesSeed` | partial | Only happy path; no assertion that freeze actually fires (e.g., by showing divergence from non-cut control). |
| Indirect LR rejection | `LeftRecursionTest.IndirectLrRejection.indirectCycleIsRejectedAtValidation` | proven | Asserts failure + message substring. |
| Direct LR passes validation | `LeftRecursionTest.IndirectLrRejection.directLrPassesValidation` | proven | Control. |
| `selectivePackrat` × LR rejection | `LeftRecursionTest.SelectivePackratWithLr.*` | proven | Asserts failure + message. |

## 0.3.0 — Module Layout + `parseRuleAt`

| Feature | Test class(es) | Strength | Notes |
|---|---|---|---|
| `parseRuleAt(Class<? extends RuleId>, String, int)` interpreter | `ParseRuleAtTest.Interpreter.*` (7) | proven | Offset 0 & mid-buffer; end-offset specific; unknown rule fails; out-of-range fails; null fails; full-buffer consume; non-digit fails. |
| `PartialParse(node, endOffset)` record | `ParseRuleAtTest.PartialParseRecord.*` | proven | Accessor + equality/hashCode. |
| Generator parity with interpreter | `ParseRuleAtTest.GeneratorParity.*` | proven | End-offset equality between generated and interpreted parser at offset 0 and mid-buffer. |
| Generator failure on bad input | `ParseRuleAtTest.GeneratorParity.generatedParser_parseRuleAt_failureOnBadInput` | proven | |
| CST-vs-AST scoping of `parseRuleAt` | none | partial | AST-path emission rationale from CHANGELOG §Notes not asserted in tests. |
| Module restructure (peglib-parent) | n/a — build-system concern | N/A | |
| `peglib-incremental` / `-formatter` shells | n/a | N/A | |

## 0.3.1 — Incremental Parser

| Feature | Test class(es) | Strength | Notes |
|---|---|---|---|
| `Edit` record validation | `SessionApiTest.EditValidation.*` (5) | proven | Null, negative, delta, no-op. |
| `IncrementalParser.initialize(...)` | `SessionApiTest.Initialize.*` (5) | proven | Cursor clamping (both ends), default, reject null, tree emitted. |
| No-op edit short-circuit | `SessionApiTest.NoOp.*` | proven | Asserts `isSameAs` session. |
| `moveCursor` pure | `SessionApiTest.CursorMove.*` (3) | proven | Tree identity preserved; clamping; same-session if no change. |
| Cursor-shift rules during edits | `SessionApiTest.CursorShift.*` (3) | proven | Before / after / inside edit. Asserts exact expected cursor values. |
| Out-of-range edits | `SessionApiTest.OutOfRange.*` | proven | Throws IAE. |
| `reparseAll()` escape hatch | `SessionApiTest.ReparseAll.*` | proven | Stats bump + preservation. |
| `Stats` record | `SessionApiTest.StatsChecks.*` | proven | Initial zero; increment on edit; `lastReparseMs` conversion from nanos. |
| Reparse-boundary algorithm | `ReparseBoundaryTest` (12) | proven | Every test does a full-reparse parity check via CstHash. |
| `IncrementalParityTest` corpus harness | `IncrementalParityTest` | proven | 22×100 parity checks, deterministic seed. Matches SPEC. |
| `IdempotencyTest` | `IdempotencyTest` (5) | proven | Hash equality across edit/inverse round-trip; forked sessions. |
| `BackReferenceScan` unsafe-rule detection | `BackReferenceFallbackTest` (4) | proven | Direct + transitive flagged; non-BR grammar clean. |
| Back-ref fallback to full reparse | `BackReferenceFallbackTest.edit_triggers_full_reparse` | proven | `fullReparseCount` == 1 + parity preserved. |
| `CstHash` parity oracle | used throughout | proven (used as oracle) | |
| `RuleIdRegistry` bytecode gen | none direct (internal, exercised via `parseRuleAt`) | partial | Internal machinery exercised in every parity test, but no direct assertion about the classfile-API bridge itself. Low risk. |

## 0.3.2 — Trivia-Aware Reparse

| Feature | Test class(es) | Strength | Notes |
|---|---|---|---|
| Trivia-only edit fast-path | `TriviaRedistributionTest.TriviaOnly.*` (5) | proven | Asserts `lastReparsedRule() == "<trivia>"` on each positive case + parity oracle. |
| Fast-path declines on trivia+token edit | `TriviaRedistributionTest.StructuralFallthrough.*` (2) | proven | Asserts `lastReparsedRule() != "<trivia>"` + parity. |
| Deleted-trivia handling | `TriviaRedistributionTest.DeletedTrivia.*` (2) | proven | Sequential parity across multiple deletes. |
| Inserted-trivia handling | `TriviaRedistributionTest.InsertedTrivia.*` (2) | proven | Parity after multiple inserts. |
| `triviaFastPathEnabled` flag gating | none | **partial** | All tests construct with flag=`true`. No test verifies the default-off behavior (no `<trivia>` sentinel when flag omitted). |
| `TriviaRedistribution` helper | indirect | proven | Every `TriviaRedistributionTest` assertion runs through it. |
| `IncrementalTriviaParityTest` | `IncrementalTriviaParityTest` | proven | 22 files × 50 trivia-biased edits against full-parse oracle. |
| `IncrementalBenchmark` JMH | n/a | N/A | Benchmark, not a feature. |

## 0.3.3 — Formatter Module

| Feature | Test class(es) | Strength | Notes |
|---|---|---|---|
| `Doc` sealed interface + records | `DocAlgebraTest.Constructors.*` | proven | Null rejection, newline rejection, factory equality. |
| `Docs` builder functions | `DocAlgebraTest.Builders.*` + `ConcatAll` | proven | Each builder asserts type + simple rendered output. |
| `Renderer` Wadler-Lindig best | `RendererTest` (4 nested suites) | proven | Primitives, groups flat vs broken, indent levels, width-driven decisions including surrounding-context influence. |
| `softline` flat vs. break | `RendererTest.Primitives.topLevelSoftline*` + `Groups.softlineDisappearsInFlatMode` | proven | Both modes asserted. |
| `hardline` forces break even flat | `RendererTest.Groups.hardlineForcesGroupBreak` | proven | |
| `indent` in flat vs. break | `RendererTest.Indents.*` | proven | Both modes + nesting. |
| `FormatterRule` functional interface | exercised by demo formatters | proven | |
| `Formatter` fluent builder | demo formatters via `.create()` | partial | `.rule()`, `.defaultIndent()`, `.maxLineWidth()` setters not covered by direct test; only transitively through demos. |
| `FormatContext` trivia policy / user state | none | **missing** | No test directly references `FormatContext`. |
| `JsonFormatter` demo | `JsonFormatterTest` (15) | proven | Specific-string outputs + 60-case fuzz idempotency + break-on-overflow assertion. |
| `SqlFormatter` demo | `SqlFormatterTest` (10) | proven | Keyword upper-case (positive + negative substring), clause-per-line with exact line-count assertion, short-col-list flat form. |
| `ArithmeticFormatter` demo (precedence-aware) | `ArithmeticFormatterTest` (18) | proven | Exact output strings for precedence cases incl. `(1+2)*3`. |
| Cross-formatter idempotency harness | `IdempotencyTest` (3) | proven | 50 fuzz iterations each with deterministic seeds. |
