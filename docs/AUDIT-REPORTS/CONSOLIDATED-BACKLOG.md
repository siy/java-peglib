# Consolidated Audit Backlog — java-peglib cleanup round 1

Aggregated findings from 12 audit agents across 10 JBCT focus areas + docs back-reference + test-coverage proof.

Source reports:
- `docs/AUDIT-REPORTS/docs-backreference.md` (0.2.3–0.3.3 CHANGELOG / impl / docs cross-ref)
- `docs/AUDIT-REPORTS/docs-fixups-needed.md` (doc drift fix-ups)
- `docs/AUDIT-REPORTS/test-coverage-proof.md` (per-feature proof-vs-smoke classification)
- `docs/AUDIT-REPORTS/tests-fixups-needed.md` (concrete test adds to close gaps)
- JBCT focus-area findings (10 areas) — synthesized in this document from cross-code survey

Scope: releases 0.2.3 → 0.3.3. Baseline test totals: peglib-core 674 + 1 skipped, peglib-incremental 100, peglib-maven-plugin 5, peglib-playground 22 = **801 passing, 1 skipped**.

Headline totals: ~67 Critical, ~57 Warning, ~34 Suggestion. Round 1 addresses the items below marked `[APPLIED]`; the rest are carried as `[P3-DEFERRED]` with effort notes.

---

## P0 — Correctness and Security (Round 1)

### P0.1 Thread-safety data race on PegEngine failure caches [APPLIED]

- **Tag:** `[JBCT-thread-safety]`
- **File:** `peglib-core/src/main/java/org/pragmatica/peg/parser/PegEngine.java:56-57`
- **Finding:** `literalFailureMessageCache` / `charClassFailureMessageCache` are `HashMap`. A single `PegEngine` instance can be parsed concurrently (factory produces one `ParsingContext` per call) and these caches are written on every miss — classic data race.
- **Fix:** switch to `ConcurrentHashMap`.
- **Risk if ignored:** silent infinite loop under contention (HashMap resize), lost cache entries, unsafe publication.

### P0.2 Playground server hardening [APPLIED]

- **Tag:** `[JBCT-adapter-boundary]` + `[sec]`
- **Files:**
  - `peglib-playground/src/main/java/org/pragmatica/peg/playground/PlaygroundServer.java`
  - `peglib-playground/src/main/java/org/pragmatica/peg/playground/PlaygroundRepl.java`
- **Findings:**
  1. Path traversal in `handleStatic` — `"/playground" + path` is served untrusted. `..` segments are not rejected.
  2. Request body read with `readAllBytes()` — no body-size cap → unbounded memory allocation.
  3. Missing security headers: `X-Content-Type-Options`, `X-Frame-Options`, `Referrer-Policy`, `Cache-Control`.
  4. `String.toUpperCase()` without Locale → locale-dependent behaviour on Turkish `i`.
- **Fix:** all four items listed in the Round 1 plan.
- **Risk if ignored:** cross-module resource disclosure, DoS by large POST, clickjacking, MIME-sniffing attacks, Turkish-locale parse failures.

### P0.3 CHANGELOG drift for 0.2.7 / 0.3.2 / 0.3.3 [APPLIED]

- **Tag:** `[JBCT-docs]`
- **File:** `CHANGELOG.md`
- **Findings:**
  - 0.2.7: advertised `java -jar …-uber.jar repl <grammar>` / `… server` sub-commands. Uber-jar `mainClass` is `PlaygroundServer`; no dispatcher exists. Real invocation per `docs/PLAYGROUND.md`.
  - 0.3.2: advertised `IncrementalParser.builder(...).triviaFastPathEnabled(true)`. No builder exists. Real API is `IncrementalParser.create(grammar, config, boolean)`.
  - 0.3.3: lists 7 `Doc` records; actually ships 8 (missing `HardLine`). `Docs.hardline()` builder also missing from enumeration.
- **Fix:** re-write the three affected CHANGELOG bullet groups.

---

## P1 — High-Leverage Mechanical Fixes (Round 1)

### P1.1 `Result.failure(cause)` → `cause.result()` [APPLIED]

- **Tag:** `[JBCT-result-factories]`
- **Files:** `peglib-core/src/main/java/org/pragmatica/peg/**` (~50 sites)
  - `PegEngine.java` — 10 sites
  - `GrammarParser.java` — 25 sites
  - `GrammarResolver.java` — 10 sites
  - `Grammar.java` — 2 sites
  - `ActionCompiler.java` — 5 sites
  - `ParserGenerator.java` — 6 real sites at lines 348, 353, 2257, 2262, 2397–2417 (the string-literal `ParseResult.failure("…")` sites emit generated-parser code and are **excluded**)
- **Finding:** JBCT "Forbidden" table row: `Result.failure(cause)` must be `cause.result()`.
- **Fix:** mechanical replacement; preserve exact cause expressions.

### P1.2 Highest-leverage `null → Option` conversions [APPLIED]

- **Tag:** `[JBCT-null-to-option]`
- **Files:**
  - `peglib-core/.../parser/ParsingContext.java:512-522` — `getCachedAt` hand-rolled null check replaced with `Option.option(…)`.
  - `peglib-core/.../action/Actions.java:60-62` — `get(String)` now returns `Option<Function<SemanticValues, Object>>`; `PegEngine.mergeActions` caller updated.
  - `peglib-incremental/.../internal/NodeIndex.java:78-105` — `smallestContaining` / `smallestContainingFrom` return `Option<CstNode>`; 5 caller sites in `SessionImpl` updated.
  - `peglib-incremental/.../internal/SessionImpl.java:247-255` — `reparseAt` rewritten in monadic style, returns `Option<CstNode>`.
  - `peglib-playground/.../PlaygroundEngine.java:45-49` — `parseWithRecovery` rewritten via `.fold(() -> …, rule -> …)`, eliminating `startRule.or((String) null)`.
- **Finding:** core JBCT rule "Null policy: business logic never returns or checks null".

---

## P2 — Targeted Test Gaps (Round 1)

### P2.1 Left-associativity CST shape proof test [APPLIED]

- **Tag:** `[JBCT-tests]`
- **File:** `peglib-core/src/test/java/org/pragmatica/peg/grammar/LeftRecursionTest.java`
- **Finding (from `test-coverage-proof.md` §0.2.9):** existing tests assert parse succeeds; none prove CST shape is left-leaning.
- **Fix:** add one test that parses `1+2+3` and walks the CST to assert the left subtree is `Expr(1+2)`, right subtree is `Term(3)`.

### P2.2 `%recover` override proof test [APPLIED]

- **Tag:** `[JBCT-tests]`
- **File:** `peglib-core/src/test/java/org/pragmatica/peg/error/RuleRecoveryTest.java`
- **Finding (from `test-coverage-proof.md` §0.2.4 item 1):** `%recover` currently only smoke-tested; no comparative test proves the override shifts the recovery set.
- **Fix:** add one comparative test: with vs. without `%recover "}"`, assert different recovery behaviour (span/diagnostic differences).

---

## P3-DEFERRED — Architectural / Refactor Items

### P3.1 Parse-don't-validate refactor for Grammar [P3-DEFERRED]

- **Tag:** `[JBCT-parse-dont-validate]`
- **Description:** collapse `Grammar.validate()` (`Grammar.java:102+`) into the factory path so `Grammar` instances are always valid by construction. Callers currently receive a `Grammar` before validation succeeds.
- **Affected files:** `Grammar.java`, `PegParser.java`, `GrammarResolver.java`, all 40+ call sites that consume raw `Grammar`.
- **Effort:** 4-6h — surface-level but touches every grammar construction path.

### P3.2 Factory-method naming rename `of()`/`create()`/`at()` → `typeName()` [P3-DEFERRED]

- **Tag:** `[JBCT-factory-naming]`
- **Description:** JBCT rule: factory methods are lowercase-first type name (e.g., `Email.email(raw)`). ~20 public API sites currently use `of`/`create`/`at`.
- **Affected files (spot-check):**
  - `SourceSpan.of`, `SourceLocation.at`, `CstNode.*`, `AstNode.*`, `Diagnostic.error`, `Docs.*`, `ParserConfig.of`, `Edit(…)`, `Stats(…)`, many more.
- **Effort:** 2h code change + API-breaking churn across every downstream module + docs + tests.

### P3.3 `SessionImpl` → `Session` record rename [P3-DEFERRED]

- **Tag:** `[JBCT-impl-suffix]`
- **Description:** JBCT rule: no `*Impl` classes. `SessionImpl` is package-private but still violates naming. Convert to a `record Session(…) implements SessionIface {…}` with the interface renamed.
- **Affected files:** `peglib-incremental/…/internal/SessionImpl.java`, `Session.java`, `SessionFactory.java`, `IncrementalParser.java`, test references.
- **Effort:** 3h.

### P3.4 Mojo `execute()` decomposition into Result-pipeline [P3-DEFERRED]

- **Tag:** `[JBCT-pattern-decomposition]` + `[JBCT-adapter-boundary]`
- **Description:** Mojos (`GenerateMojo`, `LintMojo`, `CheckMojo`) have multi-statement `execute()` bodies mixing I/O and domain. Refactor each to:
  - adapter boundary: `@Contract` on `execute()` bridging Maven `MojoExecutionException` to `Result` internally.
  - domain pipeline: `Result<…>` chain of validation → load → generate/write/check.
- **Affected files:** `peglib-maven-plugin/src/main/java/…` (3 mojos).
- **Effort:** 4h each × 3.

### P3.5 Formatter mutable builder → immutable FormatterConfig [P3-DEFERRED]

- **Tag:** `[JBCT-immutability]`
- **Description:** `Formatter` is a mutable fluent builder. JBCT rule: prefer immutable records; builder only as adapter to user code.
- **Affected files:** `peglib-formatter/src/main/java/org/pragmatica/peg/formatter/Formatter.java` + every demo formatter test.
- **Effort:** 3h.

### P3.6 Test idiom mass-rewrite `assertTrue(result.isSuccess())` → `.onFailure(Assertions::fail).onSuccess(...)` [P3-DEFERRED]

- **Tag:** `[JBCT-test-idioms]`
- **Description:** JBCT testing guide: the correct idiom is `.onFailure(Assertions::fail).onSuccess(assertions)`. Roughly **1000 sites** across ~60 test files use `assertTrue(result.isSuccess())` / `.unwrap()` patterns.
- **Affected files:** every test file under `*/src/test/java`.
- **Effort:** 12h+ (mostly mechanical, but verifying each error message/assertion preserves intent is non-trivial).

### P3.7 `@Contract` annotation on all boundary methods [P3-DEFERRED]

- **Tag:** `[JBCT-boundary-annotations]`
- **Description:** Mojo `execute()` methods, all 3 CLI `main()` methods, `PlaygroundServer.main`, etc. should carry `@Contract` to mark the SDK boundary. Currently none do.
- **Affected files:** `AnalyzerMain.java`, `PlaygroundRepl.java`, `PlaygroundServer.java`, 3 Mojo classes.
- **Effort:** 1h (annotation adds + pragmatica-lite dep on `core` for the annotation if not already transitive).

### P3.8 Playground ParseTracer engine hooks [P3-DEFERRED]

- **Tag:** `[JBCT-observability]`
- **Description:** Tracer is currently post-parse only (walks finished CST). SPEC mentions in-parse hooks as future work. Cache-hit counters in particular are faked from `walkCst` rather than driven by engine-internal events.
- **Affected files:** `PegEngine.java`, `ParsingContext.java`, `ParseTracer.java`, `playground/ParseTracerTest.java`.
- **Effort:** 6h + benchmark to ensure the hook is zero-cost when tracer is absent.

### P3.9 Grammar-qualified rule shadowing semantics review [P3-DEFERRED]

- **Tag:** `[JBCT-semantics]`
- **Description:** CHANGELOG 0.2.8 documents "root silently shadows transitive imports". Test `GrammarCompositionTest.rootSilentlyShadowsTransitiveImports` asserts this, but the semantic is under-documented: no diagnostic surfaces when shadowing. User-expectation mismatch likely.
- **Affected files:** `GrammarResolver.java`, `GrammarCompositionTest.java`, `docs/GRAMMAR-DSL.md`.
- **Effort:** 3h — primarily docs + an optional `grammar.shadowed-import` analyzer finding.

---

## P3-DEFERRED — Test-Coverage Gaps (from `test-coverage-proof.md`)

Round 1 closes items 1 (partially — via %recover override test) and 10 (partially — via left-associativity proof). Deferred items keep their original tag.

### P3.10 Trivia-attribution direct assertions (0.2.4 item 2) [P3-DEFERRED]

- Direct CST-level test that a trivia run between siblings lands on `children().get(1).leadingTrivia()`.
- **File:** new `TriviaAttributionTest.java` under `peglib-core/src/test/…/tree/`.
- **Effort:** 2h.

### P3.11 `%tag` user-declared-tag on diagnostic (0.2.4 item 3) [P3-DEFERRED]

- Assert that a grammar-declared `%tag "my.custom.tag"` lands in `Diagnostic.tag()` on failure.
- **File:** `DiagnosticTagTest.java` — add one test.
- **Effort:** 1h.

### P3.12 Suggestion vocabulary caching proof (0.2.4 item 4) [P3-DEFERRED]

- Introspection or counting hook to prove vocab is computed once.
- **File:** new `SuggestionVocabularyCacheTest.java`.
- **Effort:** 3h (needs small instrumentation hook).

### P3.13 `check` goal smoke-parse mismatch test (0.2.5 item 5) [P3-DEFERRED]

- Assert mismatched smoke input fails the Mojo goal.
- **File:** `MojoIntegrationTest.java`.
- **Effort:** 30m.

### P3.14 `AnalyzerMain` CLI exit-code contract (0.2.5 item 6) [P3-DEFERRED]

- Exit-code 0/1/2 tests using `ProcessBuilder` or stub-wrapped `main`.
- **File:** new `AnalyzerMainTest.java`.
- **Effort:** 2h.

### P3.15 ParseTracer real-parse cache-hit counts (0.2.7 item 8) [P3-DEFERRED]

- Requires P3.8 engine hooks; defer together.

### P3.16 Composed-grammar CST equivalence with hand-inlined (0.2.8 item 9-related) [P3-DEFERRED]

- Tighten `composedCstMatchesHandInlinedEquivalent` with `CstHash` equality.
- **Effort:** 30m.

### P3.17 Imported `%whitespace` ignored (0.2.8 item 9) [P3-DEFERRED]

- Grammar where imported `%whitespace` would differ; assert root wins.
- **Effort:** 1h.

### P3.18 Cut-in-LR seed-freeze control test (0.2.9 item 10) [P3-DEFERRED]

- Comparative test cut vs. no-cut to prove freeze fires.
- **Effort:** 2h.

### P3.19 `parseRuleAt` AST-path scoping assertion (0.3.0 item 11) [P3-DEFERRED]

- Assert AST-path generator does NOT emit `parseRuleAt(Class<…>`.
- **Effort:** 30m.

### P3.20 `triviaFastPathEnabled` default-off test (0.3.2 item 12) [P3-DEFERRED]

- Assert default-off behaviour: trivia-only edit goes through structural path.
- **Effort:** 30m.

### P3.21 `FormatContext` direct tests (0.3.3 item 13) [P3-DEFERRED]

- New `FormatContextTest.java` covering trivia policy + user state threading.
- **Effort:** 2h.

### P3.22 `Formatter.defaultIndent` / `maxLineWidth` direct setter coverage (0.3.3 item 14) [P3-DEFERRED]

- New `FormatterBuilderTest.java`.
- **Effort:** 1h.

### P3.23 `ParsingContext.bulkAdvanceNoNewline` direct test (0.2.3) [P3-DEFERRED]

- Assert pos/column advance without line change.
- **File:** `ParsingContextTest.java`.
- **Effort:** 30m.

---

## P3-DEFERRED — JBCT Focus-Area Findings (synthesis)

Aggregated findings by JBCT area, not individually expanded. Each row summarises scope.

| Area | File set | Finding count | Effort |
|---|---|---|---|
| `[JBCT-four-return-kinds]` | Mojo + CLI mains — `void` returns wrapping internal `Result`s | ~6 | 2h |
| `[JBCT-sealed-causes]` | `ParseError` has `@SuppressWarnings("JBCT-SEAL-01")` — need to replace with proper sealed-hierarchy extension or enum grouping | 1 | 2h |
| `[JBCT-single-pattern]` | `SessionImpl.edit` mixes sequencer + condition + fork-join — split | 3 | 4h |
| `[JBCT-lambda-rules]` | multi-statement lambdas in `PegEngine.parseWithRecovery`, `ParserGenerator.emitCstBody` | 8 | 3h |
| `[JBCT-no-nested-error-channels]` | not observed — no `Promise<Result<T>>` anywhere (no `Promise` in peglib) | 0 | — |
| `[JBCT-no-business-exceptions]` | IAE in `SessionImpl.edit`, `moveCursor` — convert to `Result.failure` or move to boundary | 3 | 2h |
| `[JBCT-validated-constructors]` | `Edit` / `Stats` / `PartialParse` / `ParserConfig` — some public constructors skip validation | 5 | 3h |
| `[JBCT-adapter-boundary]` | `ActionCompiler` runs JDK compiler API without `lift` at boundary | 4 | 3h |
| `[JBCT-tests-vs-prod-idioms]` | `.unwrap()` used in tests — should be `.onFailure(Assertions::fail).onSuccess(...)` | ~1000 | 12h+ (see P3.6) |
| `[JBCT-docs]` | missing `@Contract` / `@TerminalOperation` on all boundary methods | ~12 | 1h |

Total P3-DEFERRED items carried forward: **~45** (22 explicit entries + ~23 aggregated JBCT focus-area findings).

---

## Round 2 residuals

Surfaced while completing the round-2 sibling fixes (`NodeIndex.parentOf` → `Option`, `Actions.get(Class)` → `Option`, `computeIfAbsent` on failure caches, `whitespaceFirstChars` → Pragmatica `Option`). Not in scope for round 2; captured here for future rounds.

- **[P3-DEFERRED]** `PegEngine.createWithoutActions` throws `IllegalArgumentException` on config failure — should return `Result<PegEngine>` for symmetry with `create(...)`. File: `peglib-core/src/main/java/org/pragmatica/peg/parser/PegEngine.java:164`. Tag: `[JBCT-no-business-exceptions]`.
- **[P3-DEFERRED]** PegEngine action dispatch uses try/catch around lambda invocation (~line 905) — should use `Result.lift` at the boundary to convert exceptions from user action code into typed `Cause`s. File: `peglib-core/src/main/java/org/pragmatica/peg/parser/PegEngine.java:905`. Tag: `[JBCT-adapter-boundary]`.
- **[P3-DEFERRED]** Playground `parseRequestBody` throws `IllegalArgumentException` on malformed JSON — should return `Result<ParseRequest>`. File: `peglib-playground/src/main/java/org/pragmatica/peg/playground/PlaygroundServer.java`. Tag: `[JBCT-no-business-exceptions]`.
- **[P3-DEFERRED]** Playground `HttpHandler` methods propagate `IOException` — the HTTP-boundary adapter should use `Result.lift` to convert into a typed `Cause` and translate to an HTTP error response at the edge. File: `peglib-playground/src/main/java/org/pragmatica/peg/playground/PlaygroundServer.java`. Tag: `[JBCT-adapter-boundary]`.
- **[P3-DEFERRED]** `SessionImpl.tryIncrementalReparse` returns `null` to signal fall-back to full reparse — should return `Option<IncrementalResult>`. File: `peglib-incremental/src/main/java/org/pragmatica/peg/incremental/internal/SessionImpl.java:194`. Tag: `[JBCT-no-null]`.
- **[P3-DEFERRED]** `SessionImpl.findBoundaryCandidate` uses a null-terminated outward walk; callers likewise use `.or(null)` bridges (marked `TODO(P3)`) after the round-2 `parentOf → Option` flip. Convert to `Stream.iterate(start, Objects::nonNull, index::parentOf)` or a recursive helper that returns `Option<CstNode>`. Files: `peglib-incremental/src/main/java/org/pragmatica/peg/incremental/internal/SessionImpl.java:194, 231`. Tag: `[JBCT-no-null]` + `[JBCT-lambda-rules]`.
- **[P3-DEFERRED]** `SessionImpl.edit` throws `IllegalArgumentException` for null/out-of-range edits — should return `Result<Session>`. File: `peglib-incremental/src/main/java/org/pragmatica/peg/incremental/internal/SessionImpl.java:80`. Tag: `[JBCT-no-business-exceptions]`.
- **[P3-DEFERRED]** `ParsingContext.recoveryOverrideStack.peek()` returns nullable — should wrap with `Option.option(...)` at read sites. File: `peglib-core/src/main/java/org/pragmatica/peg/parser/ParsingContext.java`. Tag: `[JBCT-no-null]`.
- **[P3-DEFERRED]** `ParsingContext` uses an anonymous `LinkedHashMap` subclass to implement a bounded cache (`removeEldestEntry` override) — extract to a named `BoundedLinkedHashMap<K,V>` class for testability and for consistency with other cache types in the codebase. File: `peglib-core/src/main/java/org/pragmatica/peg/parser/ParsingContext.java`. Tag: `[JBCT-validated-constructors]`.

---

## Round 1 Summary

- **Commits planned:** 8 on `cleanup-round-1` branch.
- **Fixes applied:** P0.1, P0.2, P0.3, P1.1 (~50 sites), P1.2 (4 subsystems), P2.1, P2.2.
- **Baseline tests:** 801 passing + 1 skipped → expected **803 passing + 1 skipped** after round 1.
- **Deferred:** 45 items captured here.
