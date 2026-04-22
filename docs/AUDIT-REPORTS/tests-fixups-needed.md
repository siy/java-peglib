# Test Fixups Needed — 0.2.3 → 0.3.3 Audit

Actionable list of new tests / assertions to add to close gaps identified in `test-coverage-proof.md`. Each bullet names the target test class (existing or new) and the specific assertion that would *prove* rather than *smoke-test* the feature.

## 0.2.3

- **`ParsingContextTest.bulkAdvanceNoNewline_advancesPosAndColumnWithoutLineChange`** — construct a `ParsingContext` at a known position, call `bulkAdvanceNoNewline(5)`, assert `pos == old+5`, `column == oldColumn+5`, `line == oldLine` (unchanged).
- **`ParsingContextTest.bulkAdvanceNoNewline_rejectsWhenTextContainsNewline`** — behaviour contract: either throws or caller guards; pin down the invariant that no-newline precondition holds.

## 0.2.4

- **`TriviaAttributionTest.triviaBetweenSiblingsAttachesToFollowingSibling`** (new file) — grammar `Pair <- A B; A <- 'a'; B <- 'b'; %whitespace <- [ ]*`, parse `"a  b"`, assert: `pair.children().get(1).leadingTrivia()` contains the 2 space entries, while `pair.children().get(0).trailingTrivia()` is empty. Directly proves the 0.2.4 attribution rule.
- **`TriviaAttributionTest.triviaAttributionRestoredAfterBacktrackFailure`** — grammar with Choice where first branch fails mid-sequence; assert trivia state is saved/restored (no trivia leakage across backtracks).
- **`RuleRecoveryTest.recoverDirectiveChangesRecoveryPoint`** — grammar with `Block <- '{' Stmt* '}' %recover '}'`, recovery=ADVANCED, input has a broken statement. Without `%recover`, parser recovers at `;`/`\n`; with `%recover "}"`, it must skip past `;` and recover at `}`. Assert the diagnostic span covers past a `;`.
- **`RuleRecoveryTest.recoverDirectiveScopesToRule`** — two rules, one with `%recover "}"`, one without. Assert the override is scoped (outside rule still uses default recovery set).
- **`DiagnosticTagTest.userDeclaredTagAppearsOnRuleFailure`** — grammar with `Rule <- 'x' %tag "my.custom.tag"`, parse a failing input, assert at least one diagnostic has `tag() == Option.some("my.custom.tag")`.
- **`SuggestionVocabularyCacheTest.vocabularyComputedOnceAcrossParses`** (new) — Use a `ParsingContext` introspection or a counting hook / reflected internal state to prove the vocabulary is not recomputed on each parse. If no instrumentation hook exists, at minimum assert that multiple parses with `%suggest` remain functional (regression guard for caching).

## 0.2.5

- **`AnalyzerMainTest.cleanGrammarExitsZero`** (new) — invoke `AnalyzerMain.main(new String[]{path})` with a clean grammar via `SecurityManager`-replacement or a wrapper that captures `System.exit`; assert exit code `0`.
- **`AnalyzerMainTest.errorGrammarExitsOne`** — duplicate-literal grammar → exit `1`.
- **`AnalyzerMainTest.noArgsExitsTwo`** — assert usage exit code `2`.
- **`MojoIntegrationTest.checkMojo_smokeInputMismatchFailsBuild`** — write a grammar `Start <- 'hello'`, set `smokeInput = "world"`, assert `MojoFailureException` is thrown. Today's test only asserts the happy path doesn't throw.

## 0.2.6

- (Covered; only gap is classroom-level spot-check of `ActionsImmutabilityTest` / `LambdaVsInlineActionTest`. Recommend: open and verify they assert `with` returns new instance with prior instance untouched, and verify inline-vs-lambda test asserts divergent outputs.)

## 0.2.7

- **`ParseTracerIntegrationTest.realParseProducesExpectedCacheCounts`** (new) — parse a grammar that has a memoizable rule at two call sites (so a cache hit is guaranteed). Invoke the tracer's CST walk, assert `cacheHits > 0` without any explicit `recordCacheHit` calls in test code. Today's `ParseTracerTest` only asserts counters after manual `record*` calls — it doesn't prove the engine integration.
- **`PlaygroundReplTest.parseInvalidInput_printsFailAndNotOk`** — tighten the `containsAnyOf("FAIL", "error")` loose check: assert explicitly that `OK` is NOT in output and the failure marker is.

## 0.2.8

- **`GrammarCompositionTest.composedCstMatchesHandInlinedEquivalent` (tighten)** — replace the current "both succeed" assertion with `CstHash.of(composedCst.unwrap()) == CstHash.of(inlinedCst.unwrap())` so equivalence is structurally pinned, not just that both parse.
- **`GrammarCompositionTest.importedWhitespaceDirectiveIsIgnored`** (new) — root grammar has `%whitespace <- [ ]*` (spaces only), imported grammar has `%whitespace <- [ \t\n]*`. Parse `"a\tb"` using imported rule. Assert failure (root whitespace wins) — proving the CHANGELOG claim.

## 0.2.9

- **`LeftRecursionTest.leftAssociativityProvenByCstShape`** — parse `1+2+3` with LR grammar `Expr <- Expr '+' Term / Term`, assert the CST structure is `((1+2)+3)` not `(1+(2+3))` by walking children: the leftmost-deep `Expr` child should itself be an `Expr` (recursion on left), containing `1` and `2`; the top-level right operand should be `Term` containing `3`.
- **`LeftRecursionTest.cutInLrFreezesSeedProvenByControl`** — two parsers: one with cut `Expr <- Expr '+' ^ Term / Term`, one without (`Expr <- Expr '+' Term / Term`). Input `"1+"` (trailing plus). Assert cut-version fails parse outright (seed frozen after cut — no fallback to base case), while non-cut version succeeds parsing `"1"` and leaving `"+"` as unconsumed. This is the only way to prove freeze is actually firing.
- **`LeftRecursionTest.postfixChainPreservesLeftAssociativeCstShape`** — same principle for `a.b.c` member access.

## 0.3.0

- **`ParseRuleAtTest.astGeneratorDoesNotEmitParseRuleAt`** — generate AST parser via `PegParser.generateParser(...)`, assert the source does NOT contain `parseRuleAt(Class` — pins the CHANGELOG §Notes scope boundary.
- **`ParseRuleAtTest.interpreterParseRuleAtReturnsCstNotAst`** — assert the returned `PartialParse.node()` is a `CstNode` even if the grammar has inline actions that would produce AST / Object values under `parse(...)`.

## 0.3.1

- **`RuleIdRegistryTest.synthesizedClassExtendsRuleId`** (new, package-private internal test) — invoke the registry directly, assert the generated class is assignable to `RuleId.class`, is a subclass per SPEC, and has the correct simple name matching the grammar rule.

## 0.3.2

- **`TriviaRedistributionTest.defaultFastPathOff_triviaEditTakesStructuralPath`** — construct `IncrementalParser.create(grammar)` (i.e., no `triviaFastPathEnabled(true)`), apply a trivia-only edit, assert `stats().lastReparsedRule() != "<trivia>"`. Today all tests set the flag on explicitly — default behavior is unverified.
- **`TriviaRedistributionTest.fastPathPreservesTriviaPlacement`** — after a fast-path edit, walk the CST and assert the surrounding nodes' `leadingTrivia` / `trailingTrivia` reflect the new whitespace content (not just that the hash matches the oracle — which is trivia-excluded).

## 0.3.3

- **`FormatContextTest`** (new) — directly test `FormatContext`:
  - constructing with/without trivia policy;
  - threading user state through a chain of rule invocations and asserting state is preserved/mutated as designed.
- **`FormatterBuilderTest`** (new) — test `Formatter.builder(...)`, `.defaultIndent(n)`, `.maxLineWidth(n)`, `.rule(name, lambda)` directly (not via demos):
  - assert `.maxLineWidth(5)` causes a doc that would fit at width 80 to break instead;
  - assert `.defaultIndent(4)` vs `.defaultIndent(2)` produces different indentation on the same doc;
  - assert duplicate `.rule(name, …)` either overwrites or rejects — pin the contract.
- **`FormatterTriviaPreservationTest`** — given input with comments, format once, assert comments survive. (CHANGELOG notes trivia preservation is best-effort in v1 — test what *does* work to pin regression surface.)
