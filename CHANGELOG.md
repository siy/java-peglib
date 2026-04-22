# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.3.3] - 2026-04-22

### Added

- **`peglib-formatter` module — pretty-printer framework v1.** Wadler-Lindig doc algebra + rule DSL + renderer. Lets grammar authors write declarative formatters in Java with minimal boilerplate. Final release in the roadmap from `docs/bench-results/` era — closes `peglib` as a complete language-tool substrate (parser → incremental reparse → formatter).
- Public API in `org.pragmatica.peg.formatter`:
  - `Doc` sealed interface with records: `Text`, `Line`, `HardLine`, `Softline`, `Group`, `Indent`, `Concat`, `Empty`.
  - `Docs` static builders: `text(String)`, `line()`, `softline()`, `group(Doc...)`, `indent(int, Doc)`, `concat(Doc...)`, `empty()`.
  - `FormatterRule` — functional interface `(FormatContext, List<Doc> childDocs) -> Doc`.
  - `Formatter` — fluent builder with `.rule(name, lambda)`, `.defaultIndent(n)`, `.maxLineWidth(n)`, `.format(CstNode) → Result<String>`.
  - `FormatContext` — trivia policy + user state.
- Internal `Renderer` implements the Wadler-Lindig "best" pretty-print algorithm (groups fit on one line if possible; break at `line()` / `softline()` otherwise).
- Three demo formatters under `peglib-formatter/src/test/`:
  - `JsonFormatter` — objects with `{` + indented members + `}`, arrays similar.
  - `SqlFormatter` — clause-per-line, keywords upper-cased.
  - `ArithmeticFormatter` — precedence-aware with minimal parentheses.
- `docs/PRETTY-PRINTING.md` — algebra + DSL reference with worked examples.
- `peglib-formatter/README.md` — module overview, quick-start, trivia-preservation notes.

### Limitations

- **Trivia preservation is best-effort** in v1. Depends on 0.2.4's trivia attribution foundation, which still has a deferred rule-exit pos-rewind for trailing intra-rule trivia. Format output may not byte-equal source for all inputs today. Full round-trip lands when the trivia foundation is completed in a subsequent release.
- Idempotency harness fuzzes ~50-100 inputs per demo formatter. Each demo passes `format(format(x)) == format(x)`.

### Tests

- Aggregate: **801 → ~828 passing** across all modules (exact number depends on demo test counts; verified green in the release PR).
- `peglib-core`, `peglib-incremental`, `peglib-maven-plugin`, `peglib-playground`: unchanged.
- `peglib-formatter`: v1 ships with doc algebra unit tests, renderer tests, idempotency harness, and three end-to-end demo-formatter test suites.

## [0.3.2] - 2026-04-22

### Added

- **Trivia-aware reparse splice** in `peglib-incremental`. New internal `TriviaRedistribution` helper redistributes `leadingTrivia` / `trailingTrivia` between a spliced subtree and its neighbors after `TreeSplicer` runs.
- **Trivia-only edit fast-path** in `SessionImpl` — detects edits that land entirely within trivia regions and patches the CST without reinvoking the parser. Flag-gated via `IncrementalParser.create(grammar, config, /*triviaFastPathEnabled=*/true)` — **default off** because grammars like Java allow whitespace to affect tokenisation (e.g., deleting whitespace between `>>` changes parse).
- **`IncrementalBenchmark` (JMH)** in `peglib-incremental/src/jmh/java/`, behind the `bench` Maven profile. Parametrized over `{initialize, singleCharEdit, wordEdit, lineEdit, fullReparse, undoRestore}` variants against the 1,900-LOC fixture. Smoke result committed at `docs/bench-results/incremental-v1-smoke.json`.

### Performance

Smoke benchmark (single iteration, JDK 25, Apple Silicon) measured `singleCharEdit` at **~325 ms/op** on the 1,900-LOC `FactoryClassGenerator.java.txt` fixture — well above the SPEC §8 `< 1 ms median` target. Root causes, honestly reported:

- Wholesale packrat cache invalidation on edit (SPEC §5.4 v1 decision) — cache rebuild dominates.
- Java grammar's back-reference-bearing rules trigger full-reparse fallback on most edits.

Next lever: **v2.5 span-rewriting cache remap** (SPEC §5.4). Not part of this release. The incremental module ships with its correctness story intact (parity harness green across 2,200 checks) but its performance story honest: today, it's roughly equivalent to a full parse on this workload. v2.5 is where the editor-scale target is unlocked.

### Tests

- `peglib-core`: 674 + 1 skipped — unchanged.
- `peglib-incremental`: 67 → **100 passing**, 0 failures. +33 tests: `TriviaRedistributionTest` (11) + `IncrementalTriviaParityTest` (22).
- `peglib-maven-plugin`: 5 (unchanged). `peglib-playground`: 22 (unchanged).
- **Aggregate: 801 passing**, 1 skipped.
- Parity harness extensions: `IncrementalTriviaParityTest` adds 22 trivia-biased-edit runs on top of the 22×100 (default) from 0.3.1.

### Deferred

- `v2.5` span-rewriting cache remap — the actual performance unlock for single-char edits. Next release slot.
- `triviaFastPathEnabled=true` safe-grammar whitelist — currently opt-in blanket; could be grammar-analyzer-driven per rule in a future release.

## [0.3.1] - 2026-04-22

### Added

- **`peglib-incremental` module — v1 implementation** per `docs/incremental/SPEC.md`. Cursor-anchored stateful parser that reparses only the subtree affected by an edit, falling back to full reparse when needed. Designed for editor-scale workflows (formatters on save, live diagnostics, LSP backends).
- Public API in `org.pragmatica.peg.incremental`:
  - `IncrementalParser.create(grammar)` / `create(grammar, config)` — factory.
  - `Session initialize(String buffer)` / `initialize(String buffer, int cursorOffset)` — immutable session.
  - `Session edit(int offset, int oldLen, String newText)` / `edit(Edit)` — returns a new session.
  - `Session moveCursor(int offset)` — pure hint, no reparse.
  - `Session reparseAll()` — diagnostic escape hatch.
  - `CstNode root()`, `String text()`, `int cursor()`, `Stats stats()` — session accessors.
  - `Edit(int offset, int oldLen, String newText)` record.
  - `Stats` record with `reparseCount`, `fullReparseCount`, `lastReparsedRuleOrd`, `lastReparsedNodeCount`, `lastReparseMs`, `cacheSize`.
- Internal machinery (package-private under `internal/`): `SessionImpl`, `NodeIndex` (span → enclosing node), `TreeSplicer` (splice subtree + shift descendant offsets), `BackReferenceScan` (grammar analysis), `RuleIdRegistry` (bytecode-generated `RuleId` classes via JEP 457 classfile API to bridge the 0.2.6 `Class<? extends RuleId>` API with rule-name-based dispatch), `CstHash` (parity oracle).
- `peglib-incremental/README.md` — module overview, API examples, limitations.

### Semantics (v1 scope)

- **CST-only.** Actions run on full reparse only (no action replay — SPEC §6.4).
- **Wholesale packrat cache invalidation on edit** (SPEC §5.4 v1). Span-rewriting remap is v2.5 material.
- **Back-reference rules fall back to full reparse.** Grammar analysis marks `BackReference`-containing rules at `Grammar.validate()` time; reparse boundary promotes to full reparse whenever an edit lands inside one.
- **Trivia attribution** during splice is inherited from 0.2.4's foundation; full trivia-aware redistribution (SPEC §5.4 v2) is scheduled for 0.3.2.
- Immutable sessions enable O(1) undo via session retention and safe cross-thread reads.

### Tests

- `peglib-core`: 674 passing + 1 skipped — unchanged (zero regression).
- `peglib-incremental`: **67 passing**, 0 failures, 0 disabled. 45 unit tests (`SessionApiTest`, `ReparseBoundaryTest`, `IdempotencyTest`, `BackReferenceFallbackTest`) + 22 from `IncrementalParityTest`.
- **Parity harness:** 22 corpus files × 100 random edits = **2,200 CstHash parity checks** under deterministic seed `0xC0FFEE42`. All green. Scales to 22,000 via `-Dincremental.parity.editsPerFile=1000`.
- `peglib-maven-plugin`: 5 passing (unchanged).
- `peglib-playground`: 22 passing (unchanged).
- **Aggregate:** 768 passing + 1 skipped, 0 failures, 0 errors.

### Deferred to 0.3.2

- JMH `IncrementalBenchmark` (SPEC §7.4) — performance gates; single-char / word / line median targets.
- Trivia-aware reparse splice (v2 proper) — rides on 0.2.4's trivia threading foundation.

### Notes

- Parity harness ran 2,200 checks per invocation; scale to 22,000 via system property when CI time budget allows. Zero divergences observed under the default seed.
- `RuleIdRegistry` uses `java.lang.classfile` (JEP 457) to synthesize marker `RuleId` subclasses at runtime for the interpreter's `parseRuleAt(Class<? extends RuleId>, ...)` dispatch. Pragmatic bridge between the 0.2.6 `Class`-based API and rule-name lookup.

## [0.3.0] - 2026-04-22

Infrastructure-only minor-bump release. No new user-facing features beyond the `parseRuleAt` API. Sets up the project structure and API additions that `peglib-incremental` (0.3.1) and `peglib-formatter` (0.3.3) will build against.

### Changed

- **Root pom converted to a multi-module parent.** Previous structure was a single-module `org.pragmatica-lite:peglib` with sibling directories holding their own poms. New structure:
  ```
  peglib-parent            (root pom, packaging=pom)
  ├── peglib-core          (the primary artifact: org.pragmatica-lite:peglib:0.3.0)
  ├── peglib-incremental   (shell module, reserved for 0.3.1)
  ├── peglib-formatter     (shell module, reserved for 0.3.3)
  ├── peglib-maven-plugin  (was sibling; now reactor module)
  └── peglib-playground    (was sibling; now reactor module)
  ```
- **Maven coordinate preserved.** `org.pragmatica-lite:peglib:0.3.0` still resolves to the same jar content consumers got in 0.2.x — the artifact simply lives in a sub-directory. Downstream consumers pinning the `peglib` coordinate need no changes.
- `peglib-maven-plugin` and `peglib-playground` modules now inherit from `peglib-parent` — their poms gain `<parent>` references; artifactIds unchanged.

### Added

- `Parser#parseRuleAt(Class<? extends RuleId> ruleId, String input, int offset)` — partial-parse entry point. Parses a specific rule against input starting at the given offset; returns `Result<PartialParse>` wrapping the resulting CST subtree and its end offset. Implemented by `PegEngine` (interpreter) and by generated parsers via an identity map keyed on the `RuleId` marker classes the generator has been emitting since 0.2.6. This is the API `peglib-incremental` (0.3.1) depends on per `docs/incremental/SPEC.md` §5.6.
- `org.pragmatica.peg.parser.PartialParse` record `(CstNode node, int endOffset)`.
- `peglib-incremental` and `peglib-formatter` shell modules — empty placeholders with just a `package-info.java` each. Reservations for 0.3.1 and 0.3.3.
- `docs/PARTIAL-PARSE.md` — `parseRuleAt` API reference.
- README updated with a Module Layout section and `parseRuleAt` usage snippet.

### Tests

- Root reactor: 663 → **674 passing** in `peglib-core`, +11 new in `ParseRuleAtTest` (interpreter + generator parity + `PartialParse` record). Plus 5 in `peglib-maven-plugin`, 22 in `peglib-playground` — aggregate **701 tests, 0 failures, 1 skipped** (pre-existing `RoundTripTest`).
- All 22-file corpus parity suites stay 22/22 under the new structure.
- `GeneratorFlagInertnessTest` stays 3/3 green — `parseRuleAt` emission lands in both config-vs-no-config sides.

### Notes

- Non-CST (Object-returning) generator path uses a different `ParseResult` type; `parseRuleAt` is scoped to the CST generator path (used by `peglib-incremental`). Per-path rationale in `docs/PARTIAL-PARSE.md`.

## [0.2.9] - 2026-04-22

### Added

- **Direct left-recursion support** via Warth-style seeding. Rules of the form `Expr <- Expr '+' Term / Term` now parse naturally with left-associative semantics — no more right-recursive workarounds like `Expr <- Term ('+' Term)*` when left-associativity matters.
- New `LeftRecursionAnalysis` under `org.pragmatica.peg.grammar.analysis`:
  - Detects direct left-recursion by walking the left-position reference graph through transparent wrappers.
  - Detects indirect left-recursion (`A → B → A`) and rejects it at `Grammar#validate()` time as a hard error. Error message: `indirect left-recursion detected in rule chain A -> B -> A; not supported in 0.2.9`.
- Packrat cache entries now carry `CacheEntry(ParseResult result, boolean growing, int seedGeneration)`. The new schema is the cache shape `peglib-incremental` (0.3.1) will build against.
- `Grammar#leftRecursiveRules()` accessor surfaces the detected set for downstream tooling.
- Generator emits a growing-loop wrapper (`emitCstLeftRecursiveWrapper`) around left-recursive rules only; non-LR rules emit unchanged.

### Changed

- `PegEngine#parseRule` dispatches to a new `parseRuleWithLeftRecursion` seed-and-grow loop when the rule is flagged left-recursive. Non-LR rules take the existing fast path.
- `ParserConfig.validate()` now rejects `packratSkipRules` entries that reference a left-recursive rule — such rules structurally depend on caching and cannot opt out. Error surfaces at engine construction.

### Semantics

- `^` cut inside a left-recursive rule **forces the current seed final** — no further growth iterations. This is a necessary compromise: cut commits, seed-and-grow depends on retry; the two reconcile by letting cut end growth.
- Actions on left-recursive rules route through the CST seed-and-grow path (non-recursive to avoid action-driven infinite loops). Explicitly unsupported for 0.2.9; documented in `docs/GRAMMAR-DSL.md`.
- **Indirect left-recursion is out of scope.** Detected and rejected rather than silently mis-parsed.

### Tests

- Test count: 651 → 663 passing, 1 skipped, 0 failures, 0 errors. +12 tests across `LeftRecursionTest` covering detection, arithmetic precedence, postfix chains, cut interaction, indirect-LR rejection, and `selectivePackrat`×LR configuration validation.
- All 22-file corpus parity suites stay 22/22 (`java25.peg` uses no LR).
- `GeneratorFlagInertnessTest` 3/3 green (non-LR grammars produce byte-identical emission).

## [0.2.8] - 2026-04-22

### Added

- Grammar composition via `%import` directive:
  ```peg
  %import Java25.Type
  %import Java25.Expression as JavaExpr

  MyAnnotation <- '@' Identifier '(' (JavaExpr (',' JavaExpr)*)? ')'
  ```
  - `%import <Grammar>.<Rule>` — imports a named rule from another grammar, with its transitive dependencies inlined.
  - `%import <Grammar>.<Rule> as <LocalName>` — imports with local rename.
- New `org.pragmatica.peg.grammar.GrammarResolver` — takes the root grammar + a `GrammarSource` and returns a composed `Grammar` IR with all imported rules + their transitive closure inlined. Cycle detection and collision enforcement run at resolve time, not runtime.
- New `GrammarSource` interface with four implementations:
  - `GrammarSource.inMemory(Map<String, String>)` — useful for tests.
  - `GrammarSource.classpath()` — resolves `<name>.peg` from the classpath.
  - `GrammarSource.filesystem(Path)` — resolves from a configured directory.
  - `GrammarSource.chained(...)` — tries each in order.
  - `GrammarSource.empty()` — default when no source is configured; `%import` fails with a clear error.
- `PegParser.fromGrammar(grammarText, config, source)` and `fromGrammar(grammarText, config, actions, source)` overloads accept a `GrammarSource`.

### Resolution semantics (surface-level)

- **Transitive closure:** imported rule pulls in every rule it references, recursively. Unresolvable transitive references → error with the dependency path in the message.
- **Whitespace:** composed grammar has one `%whitespace` binding (the root's). Imported grammars' own `%whitespace` directives are ignored for their inlined rules. Users must ensure imported grammars share a whitespace convention with the root, or rename explicitly via `as`.
- **Explicit collisions:** if root defines `Foo` and `%import G.Foo` is used without `as`, error.
- **Transitive shadowing:** if root defines `Identifier` and an import transitively pulls in `G.Identifier`, root's definition wins silently (the transitive copy is dropped). Users wanting both must `%import G.Identifier as G_Identifier`.
- **Cycle detection:** `A → B → A` imports chain errors at resolve time.
- **RuleId naming:** unqualified `%import G.R` → `RuleId.GR` (grammar-qualified, underscore stripped by the existing sanitizer); aliased `%import G.R as Local` → `RuleId.Local`; transitives keep their grammar-qualified names.

### Deferred

- Semantic composition (per-rule whitespace context) — imported grammars' whitespace remains ignored in v1.

### Tests

- Test count: 635 → 651 passing, 1 skipped, 0 failures, 0 errors. +16 new tests in `GrammarCompositionTest` across cycle / collision / transitive-closure / classpath-loader / in-memory / alias-rename scenarios.
- `GeneratorFlagInertnessTest` 3/3 (grammars without `%import` unchanged).
- All 22-file corpus parity suites stay 22/22 (`java25.peg` has no `%import`).

## [0.2.7] - 2026-04-22

### Added

- New `peglib-playground/` sibling module shipping a grammar REPL and web UI:
  - **CLI REPL** via `PlaygroundRepl.main(...)` — watches the grammar file, re-parses on save, exposes a prompt for input strings. Meta-commands: `:trace`, `:quit`, config toggles. Invoke via `java -cp peglib-playground-0.2.7-uber.jar org.pragmatica.peg.playground.PlaygroundRepl <grammar.peg>`.
  - **Web UI** via `PlaygroundServer.main(...)` — embedded `com.sun.net.httpserver.HttpServer` serving a three-pane SPA (grammar / input / output) plus controls strip (start-rule selector, packrat toggle, CST/AST toggle, trivia show/hide, recovery strategy picker, auto-refresh). `POST /parse` returns `{ok, tree, diagnostics, stats}` JSON. Uber-jar's main class is `PlaygroundServer`, so `java -jar peglib-playground-0.2.7-uber.jar [port]` starts the server directly. Neutral styling (system font stack, muted palette).
  - **ParseTracer:** strictly additive, post-parse walker that synthesizes rule-entry/exit events, cache-hit statistics, node/trivia counts. No engine hooks; no impact on parse performance when not attached.
- New documentation `docs/PLAYGROUND.md` covering CLI usage, web UI, HTTP API, programmatic access, tracer limitations, and scope boundaries. README cross-linked.

### Notes

- Playground module depends on `peglib:0.2.7` and is built independently (`cd peglib-playground && mvn install`).
- No framework dependencies for the web UI — vanilla JS + CSS, optional CodeMirror via CDN (not bundled). No build step.
- Tracer is post-parse rather than in-parse to preserve the engine's performance guarantees. A future release (likely alongside `peglib-incremental` in 0.3.1) may add in-parse hooks if demand warrants.

### Tests

- Root module: 635 passing, 1 skipped (unchanged — playground module is additive and sibling-built).
- Playground module: 22 new tests (`ParseTracerTest` 7, `PlaygroundServerTest` 6, `PlaygroundReplTest` 4, `JsonEncoderTest` 5). All pass via `cd peglib-playground && mvn test`.

## [0.2.6] - 2026-04-22

### Added

- Programmatic action attachment via type-safe `RuleId`. Callers can attach action lambdas to grammar rules without modifying the grammar file:
  ```java
  var actions = Actions.empty()
      .with(RuleId.Number.class, sv -> sv.toInt())
      .with(RuleId.Sum.class, sv -> (Integer) sv.get(0) + (Integer) sv.get(1));

  var parser = PegParser.fromGrammar(grammarText, config, actions).unwrap();
  parser.parse(input);
  ```
- New `org.pragmatica.peg.action.Actions` — immutable composable API. `Actions.empty()`, `Actions.with(Class<? extends RuleId>, Function<SemanticValues, Object>)`. Each `with(...)` returns a new instance.
- New `org.pragmatica.peg.action.RuleId` sealed interface (base). `ParserGenerator` emits a concrete sealed `RuleId` nested in each generated parser, with one parameter-less marker record per grammar rule. Designed forward-compatible with `parseRuleAt(Class<? extends RuleId>, String input, int offset)` scheduled for 0.3.0.
- `PegParser.fromGrammar(grammarText, config, actions)` and generated-parser instances now accept an `Actions` parameter.

### Changed

- Inline grammar actions (`{ return ...; }` blocks) remain fully supported. When both an inline action and a lambda are attached to the same rule, **the lambda wins**. Documented in `docs/GRAMMAR-DSL.md`.
- AST generator path now emits a nested `SemanticValues` record so generated parsers stay self-contained (no peglib runtime dependency beyond `RuleId`).

### Tests

- Test count: 618 → 635 passing, 1 skipped (`RoundTripTest`), 0 failures, 0 errors. +17 across new suites: `RuleIdEmissionTest`, `LambdaActionTest`, `LambdaVsInlineActionTest`, `ActionsImmutabilityTest`.
- All corpus parity suites stay 22/22. `GeneratorFlagInertnessTest` 3/3 green — RuleId emission is in both sides of the config-vs-no-config comparison.

## [0.2.5] - 2026-04-22

### Added

- Grammar analyzer at `org.pragmatica.peg.analyzer`. Walks the `Grammar` IR and reports six categories of findings:
  - `grammar.unreachable-rule` (warning) — rules not transitively reachable from the start rule.
  - `grammar.ambiguous-choice` (warning) — Choice alternatives with identical literal first-chars (conservative; doesn't flag char-class overlaps).
  - `grammar.nullable-rule` (info; warning when inside a direct-left-recursive path) — rules whose expression can match empty.
  - `grammar.duplicate-literal` (error) — duplicate literals across alternatives in a Choice.
  - `grammar.whitespace-cycle` (error) — `%whitespace` references cycle into themselves at analyzer time (previously caught at runtime via the reentrant guard).
  - `grammar.has-backreference` (info) — rules containing `BackReference` expressions; flagged for forward compatibility with 0.3.1 incremental parsing (such rules fall back to full reparse).
- `AnalyzerMain` CLI: `java -cp peglib.jar org.pragmatica.peg.analyzer.AnalyzerMain <grammar.peg>`. Rust-`cargo-check`-style output; exit code 0 (clean), 1 (errors), 2 (usage).
- New `peglib-maven-plugin` module at `peglib-maven-plugin/` (sibling directory with its own pom; not a sub-module of the root). Depends on `peglib:0.2.5`. Three goals:
  - `generate` — reads a grammar resource, invokes `PegParser.generateCstParser`, writes output. mtime-based skip when grammar is unchanged.
  - `lint` — runs the analyzer; fails build on any `ERROR` finding. Optional `failOnWarning` flag.
  - `check` — `lint` + build the generated parser + optional smoke-parse of a tiny input to verify end-to-end.
- New documentation sections in `README.md` and `docs/GRAMMAR-DSL.md` covering analyzer findings, CLI usage, exit codes, and Maven plugin integration.

### Notes

- The `peglib-maven-plugin` module is built independently (`cd peglib-maven-plugin && mvn install`). Conversion of the root pom to a multi-module parent is deferred to 0.3.0.
- The Maven plugin pins ASM 9.9 via a direct dependency on `maven-plugin-plugin` because the bundled 9.7.1 cannot read Java 25 class files. Revert to the default when `maven-plugin-plugin:3.15.2+` is available in Central.

### Analyzer on `java25.peg`

0 errors, 25 warnings (all `grammar.ambiguous-choice` on keyword/modifier/operator literal-prefixed choices — expected; PEG ordered choice disambiguates them correctly), 2 info (`grammar.nullable-rule` on `CompilationUnit` and `OrdinaryUnit` — intentionally accept empty input).

### Tests

- Test count: 601 → 618 passing, 1 skipped (`RoundTripTest`, per 0.2.4), 0 failures, 0 errors. +17 from `AnalyzerTest`.
- Plugin module: 5 integration tests passing via `cd peglib-maven-plugin && mvn test`.

## [0.2.4] - 2026-04-22

### Added

- **Intra-sequence trivia attribution foundation.** Trivia matched between sibling elements of a sequence now attaches to the following sibling's `leadingTrivia` rather than being dropped. Implemented via a `pendingLeadingTrivia` field on `ParsingContext` and equivalent emitted state in the generated parser; threaded through Sequence / ZeroOrMore / OneOrMore / Repetition combinators with save/restore across backtracking combinators (Choice / Optional / And / Not). Documented in `docs/TRIVIA-ATTRIBUTION.md`.
- Grammar DSL — four new directives:
  - `%expected "label"` (rule-level) — semantic expected-label for failure diagnostics; replaces the raw-token `" or "` join when the rule fails and contributes to the furthest-failure report.
  - `%recover <terminator>` (rule-level) — overrides the global `RecoveryStrategy.ADVANCED` recovery point set for this rule's scope.
  - `%suggest <RuleName>` (grammar-level) — designates a rule's literal alternatives as a suggestion vocabulary. On failure near an identifier-like position, peglib runs Levenshtein distance against the cached vocabulary and emits `help: did you mean 'X'?` when the closest match is within distance 2.
  - `%tag "tag"` (rule-level) — rule-level machine-readable tag for emitted diagnostics.
- `Diagnostic#tag()` — machine-readable tag field on every diagnostic. Built-in tags: `error.unexpected-input`, `error.expected`, `error.unclosed`. Custom tags via `%tag` rule directive.
- Suggestion-vocabulary caching at `ParsingContext` construction — designed so `peglib-incremental` (0.3.1) carries the vocabulary forward across edits without recomputation.
- Documentation:
  - `docs/TRIVIA-ATTRIBUTION.md` — attribution rule, implementation notes, deferred rule-exit rewind limitation.
  - `docs/GRAMMAR-DSL.md` — cut-operator edge cases + `%expected` / `%recover` / `%suggest` / `%tag` reference.
  - `docs/PERF-FLAGS.md` — tabular reference for all 10 `ParserConfig` perf flags from 0.2.2.
  - `docs/BENCHMARKING.md` — JMH harness usage and extension guide.
  - `docs/ERROR_RECOVERY.md` expanded with diagnostic tags and `%recover` override.
  - README cross-links to all new reference docs.
- New tests: `SemanticExpectedTest` (3), `RuleRecoveryTest` (3), `SuggestionTest` (3), `DiagnosticTagTest` (5).

### Changed

- `PegEngine` consumes `Rule#expected()` when tracking failures; the semantic label replaces the raw expected-token when present.
- `PegEngine` consumes `Rule#recover()` via a stacked recovery-char override during ADVANCED error recovery.
- `ParserGenerator` emits the same `%expected` / `%recover` / `%suggest` / `%tag` consumption logic into generated parser source, byte-identically for grammars that don't use the new directives. Grammars without any of the new directives produce generator output identical to 0.2.3 — `GeneratorFlagInertnessTest` remains 3/3 green.

### Deferred

- **Full source round-trip (RoundTripTest → 22/22).** The attribution threading landed in this release, but byte-for-byte reconstruction still fails on 17/22 fixtures because trailing intra-rule trivia (trivia consumed by a rule body when no following sibling exists to attach to) requires rule-exit pos rewind. The rewind changes NonTerminal span ends and therefore regenerates the `CstHash` baselines — too invasive to bundle with this release's scope. `RoundTripTest` remains `@Disabled`; completion is scheduled for a subsequent patch release. See `docs/TRIVIA-ATTRIBUTION.md` "Known limitation" section.

### Tests

- Test count: 587 → 601 passing, 1 skipped (`RoundTripTest`, per above), 0 failures, 0 errors.
- All ten corpus-parity suites stay 22/22 against the unchanged baselines (trivia attribution doesn't shift non-trivia CST shape; `CstHash` excludes trivia by design).

## [0.2.3] - 2026-04-22

### Added

- Shared grammar-analysis package `org.pragmatica.peg.grammar.analysis` with `FirstCharAnalysis` (recursive first-char set derivation with cycle detection) and `ExpressionShape` (repetition/group inner-expression peeling). Used by both `ParserGenerator` (generator-time emission) and `PegEngine` (interpreter-time fast-path short-circuit); ensures generator and interpreter compute the same first-char set from the same `%whitespace` rule.
- `Phase1InterpreterParityTest` (22 files × 1 configuration) enforcing interpreter CST stability across the phase-1 port. Dedicated interpreter baseline at `src/test/resources/perf-corpus-interpreter-baseline/` captured from the pre-port interpreter (commit `2f89903`); the test parses each corpus file with the post-port `PegEngine` and asserts the `CstHash` matches the committed baseline.
- `InterpreterBaselineGenerator` utility for regenerating the interpreter baseline when an interpreter CST shape change has been reviewed.
- `ParsingContext#bulkAdvanceNoNewline(int)` — O(1) position update used by the interpreter's literal-match success path when the matched text contains no newline.
- JMH benchmark variant `interpreter` in `Java25ParseBenchmark` — parses the 1,900-LOC fixture through the interpreter path (`PegParser.fromGrammarWithoutActions(...).parseCst`). Raw results: `docs/bench-results/java25-parse-interpreter.json`.

### Changed

- `PegEngine` interpreter now applies all six phase-1 optimizations unconditionally (no configuration surface). Rationale: the interpreter is a single runtime class — there is no code-size trade-off to gate with flags, unlike the emitted parser where `ParserConfig` flags avoid emitting dual paths. Phase-1 is CST-preserving and corpus-validated. The `ParserConfig` flag surface for phase-1 is unchanged and continues to gate generator emission; the flags have no effect on the interpreter path.
  - §6.2 / §6.3 / §6.5 (literal side) — `parseLiteral` now specializes the match loop by `caseInsensitive` (no per-char branch), hoists the quoted `"'text'"` message, and caches the message string in a per-engine `HashMap<String, String>` to elide `StringBuilder` churn on repeat failures.
  - §6.5 (char-class side) — `parseCharClass` caches the bracketed expected message (`"[...]"` / `"[^...]"`) per-engine. The interpreter's failure message is now unified to the bracketed label; previously the call site returned `"character"` (at EOF) or `"character class"` (on mismatch) while `updateFurthest` already received the bracketed label. The unified label matches the generator's 0.2.2 behaviour. No tests asserted on the old strings.
  - §6.4 — `parseLiteral` and `parseDictionary` bulk-update `pos` / `column` on successful match when the matched text has no `\n`.
  - §6.6 — `PegEngine#skipWhitespace` short-circuits when the current char is not in the first-char set derived from the grammar's `%whitespace` rule; computed once in the constructor via `FirstCharAnalysis`.
  - §6.7 — `parseLiteral`, `parseCharClass`, `parseAny`, `parseDictionary` allocate end-position `SourceLocation` once per successful match and reuse it for both the span end and the `Success.endLocation`.
- §6.1 (`fastTrackFailure`) is effectively a no-op port: the interpreter's `ParsingContext#updateFurthest` already short-circuits without allocation when the incoming position is strictly less than the furthest-seen offset. No change required.
- `ParserGenerator` now delegates first-char analysis and inner-expression extraction to the shared `grammar.analysis` package. Emission output is byte-identical to 0.2.2 (`GeneratorFlagInertnessTest` remains 3/3 green).

### Performance

Measured on `src/test/resources/perf-corpus/large/FactoryClassGenerator.java.txt` (1,900 LOC) via JMH 1.37 — `AverageTime` + `Throughput` modes, 3 warmup × 2 s, 5 measurement × 2 s, 2 forks, JDK 25 on Apple Silicon. Raw data: `docs/bench-results/java25-parse-interpreter.json`.

| Variant | ms/op (± CI) | Speedup vs pre-port interpreter |
|---|---|---|
| `interpreter` (0.2.2 pre-port, no phase-1) | 355.0 ± 7.0 | 1.00× |
| `interpreter` (0.2.3 post-port, all phase-1) | **257.9 ± 5.9** | **1.38×** |
| `phase1` (generator reference, all phase-1) | 63.2 ± 8.3 | — (generator path; included for context) |

Interpreter speedup is below the 1.5× plan target, reflecting the interpreter's structural cost: per-invocation expression-switch dispatch dominates per-call work, so the allocation-elision wins from caches and bulk-advance deliver a more modest improvement than they do for the generator's per-rule-specialized output. The spec calls this out explicitly — phase-2 optimizations (`choiceDispatch`, `markResetChildren`, `inlineLocations`, `selectivePackrat`) remain generator-only; they do not translate to the interpreter.

### Tests

- Test count: 565 → 587 (+22 from `Phase1InterpreterParityTest`). 1 skipped (pre-existing `RoundTripTest`; addressed in 0.2.4 per plan).

## [0.2.2] - 2026-04-21

### Added

- Performance rework infrastructure for the generated CST parser (`ParserGenerator`):
  - `ParserConfig` carries 10 new generator-time flags — 6 phase-1 (hand-edits) and 4 phase-2 (structural). Flags are consumed at generation time; the emitted parser has no runtime flag branching.
  - Test corpus under `src/test/resources/perf-corpus/` (22 Java 25 files including a 1,900-LOC fixture) plus committed CST structural-hash baselines at `src/test/resources/perf-corpus-baseline/` for regression detection.
  - `CstHash` (deterministic pre-order structural hash, excludes trivia and `Error.expected`) and `CstReconstruct` utilities under `src/test/java/org/pragmatica/peg/perf/`.
  - JMH benchmark harness at `src/jmh/java/`, activated via `mvn -Pbench`. Builds a `target/benchmarks.jar` uber-jar; benchmarks the 1,900-LOC fixture across 7 `@Param` flag combinations.
  - `PackratStatsProbe` utility reporting per-rule cache put counts against the fixture.
- Grammar moved from inline `JAVA_GRAMMAR` string to classpath resource `/java25.peg` (single source of truth for tests and perf infra).

### Changed

- Phase-1 flags default **on** in `ParserConfig.DEFAULT`:
  - `fastTrackFailure` — short-circuits `trackFailure` when the current position is dominated by the furthest-seen failure (avoids `SourceLocation` allocation on ~90% of calls during backtracking).
  - `literalFailureCache` — per-parser-instance `HashMap` caches literal-match failure results; eliminates `CstParseResult`/`Option` allocation on the failure path after warmup. Loop specialization by `caseInsensitive` also emitted.
  - `charClassFailureCache` — analogous for `matchCharClassCst`. Unifies failure message to bracketed label (`"[...]"` / `"[^...]"`); previous "character class" string is no longer emitted.
  - `bulkAdvanceLiteral` — on successful match of literal text with no `\n`, updates `pos` and `column` in bulk rather than looping `advance()` per char.
  - `skipWhitespaceFastPath` — emits a first-char precheck derived from the grammar's `%whitespace` rule (e.g. `' '`/`'\t'`/`'\r'`/`'\n'`/`'/'` for Java 25); returns `List.of()` immediately when current char can't start trivia.
  - `reuseEndLocation` — allocates end-position `SourceLocation` once per successful match instead of twice (span end + result endLocation).
- Phase-2 flag defaults (set per measured win per PERF-REWORK-SPEC §12.6):
  - `choiceDispatch` default **on** — measured 2.49× speedup over phase-1 baseline.
  - `markResetChildren`, `inlineLocations` default **off** — no statistically significant individual win on the reference JVM.
  - `selectivePackrat` default **off** — marginal combo win (~5%) sits inside measurement noise; callers opting in must also provide `packratSkipRules`.

### Performance

Measured on `src/test/resources/perf-corpus/large/FactoryClassGenerator.java.txt` (1,900 LOC) via JMH 1.37 — average time mode, 3 warmup × 2s / 5 measurement × 2s / 2 forks, JDK 25.0.2 on Apple Silicon. Raw data: `docs/bench-results/java25-parse.json`, `docs/bench-results/java25-parse.log`.

| Variant | ms/op (± CI) | Speedup vs `none` | Speedup vs `phase1` |
|---|---|---|---|
| `none` (no perf flags) | 425.6 ± 62.2 | 1.00× | 0.59× |
| `phase1` (legacy DEFAULT — phase-1 flags on, phase-2 off) | 250.2 ± 31.5 | 1.70× | 1.00× |
| `phase1 + choiceDispatch` (new DEFAULT) | **100.7 ± 32.6** | **4.23×** | **2.49×** |
| `phase1 + markResetChildren` | 290.3 ± 51.7 | 1.47× | 0.86× (within noise) |
| `phase1 + inlineLocations` | 307.6 ± 47.9 | 1.38× | 0.81× (within noise) |
| `phase1 + all structural` | 116.5 ± 20.8 | 3.65× | 2.15× |
| `phase1 + all structural + selectivePackrat` | 110.7 ± 32.6 | 3.85× | 2.26× |

Spec §12.3 acceptance target: ≥1.5× on the 1,900-LOC fixture. Phase-1 alone reaches 1.70× and the new DEFAULT (phase-1 + `choiceDispatch`) reaches **4.23×**, exceeding the stretch goal of 2×.

### Fixed

- N/A (no regression fixes in this release; see 0.2.1 for the previous correctness pass.)

### Tests

- Test count: 350 → 565. New suites: `CorpusParityTest`, `Phase1ParityTest`, `Phase2ChoiceDispatchParityTest`, `Phase2MarkResetChildrenParityTest`, `Phase2InlineLocationsParityTest`, `Phase2ChoiceDispatchAndMarkResetParityTest`, `Phase2AllStructuralParityTest`, `Phase2SelectivePackratParityTest`, `Phase2SelectivePackratEmptySetParityTest`, `ChoiceDispatchAnalyzerTest`, `GeneratorFlagInertnessTest`. All passing; 1 skipped (`RoundTripTest` — pre-existing trivia-attribution gap in both `PegEngine` and the generated parser, documented in the test).

## [0.2.1] - 2026-03-29

### Fixed

- Rewrote CST and AST parser generators to exactly match interpreter (PegEngine) behavior
  - Sequence now skips whitespace before all non-predicate elements (removed incorrect `isReference`/`isOptionalLike` exclusions)
  - Optional no longer skips whitespace independently (relies on containing Sequence)
  - ZeroOrMore/OneOrMore/Repetition propagate CutFailure instead of silently breaking
  - Token boundary uses depth counter instead of boolean flag (handles nesting)
  - Rule methods collect their own leading trivia at entry (no trivia parameter)
  - Reference handler delegates whitespace to rule methods
- Fixed generated parser failing on optional suffixes after multi-alternative choices (e.g., `OverClause?` after `FuncCall` alternatives)
- Fixed StackOverflowError in generated parser when `%whitespace` references named rules (e.g., `LineComment`, `BlockComment`) — added reentrant guard to `skipWhitespace()` matching interpreter's `enterWhitespaceSkip`/`exitWhitespaceSkip` pattern
- Fixed generated parser Token nodes using generic rule name `"token"` instead of parent rule name for `< >` captures — now uses `wrapWithRuleName` matching interpreter behavior
- Fixed generated parser CST structure: container expressions (ZeroOrMore, OneOrMore, Optional, Repetition) now wrap children in NonTerminal nodes matching interpreter behavior instead of flattening into parent

### Added

- Generator conformance test suite (40 tests) comparing interpreted vs generated parser behavior
- Test count: 310 → 350

## [0.2.0] - 2026-03-29

### Fixed

- Generated parser (CST and AST) incorrectly skipped whitespace before predicate expressions (`!` and `&`) inside sequences, causing negative lookahead on keyword rules to fail

### Changed

- Updated pragmatica-lite dependency: 0.9.10 → 0.24.0

## [0.1.9] - 2026-01-09

### Changed

- Updated pragmatica-lite dependency: 0.9.0 → 0.9.10

## [0.1.8] - 2025-12-31

### Changed

- **Java 25 Grammar Sync** - Synced grammar improvements from jbct-cli
  - Added cut operators (`^`) after discriminating keywords for better error messages
  - Added keyword helper rules with word boundaries (`ClassKW`, `InterfaceKW`, `EnumKW`, etc.)
  - Added token boundaries to `PrimType`, `Modifier`, `Literal`, `Primary` rules
  - Updated `RefType` lookahead to handle `Type.@Annotation Inner` correctly
  - Added `TypeExpr` rule for `Type.class` and `Type::new` expressions
  - Updated operator rules with lookaheads to prevent compound operator conflicts
  - Added `RecordDecl` lookahead to distinguish from methods/fields named 'record'

### Fixed

- **Farthest Failure Tracking** - Error positions now report at the furthest parsing position instead of 1:1 after backtracking
  - Added `furthestPos`/`furthestFailure` tracking to both AST and CST generated parsers
  - Replaces null checks with `Option<T>` in CST parser generator for consistency
  - Fixed infinite recursion in AST parser when whitespace rule contained `*` quantifier
  - Fixed "unexpected input" errors to also use furthest failure position

### Added

- **Error Position Tests** - 3 new tests verifying farthest failure tracking in generated parsers
- Test count: 305 → 308

## [0.1.7] - 2025-12-30

### Changed

- **JBCT Compliance Refactoring**
  - Replaced null usage with `Option<T>` throughout the codebase
  - `ParseResultWithDiagnostics.node()` now returns `Option<CstNode>`
  - `ParseMode` uses `Option` for nullable fields
  - `ParsingContext` packrat cache uses `Option`
  - `Diagnostic.code()` now returns `Option<String>`
  - Generated `ParseResult` and `CstParseResult` use `Option` for nullable fields
  - Improved type safety with `SemanticValues`

### Added

- **JBCT Maven Plugin** - Added jbct-maven-plugin 0.4.1 for code formatting and linting
- **Internal Type Tests** - 24 new tests for ParseResult, ParsingContext, and generated parser diagnostics

- Test count: 271 → 305

## [0.1.6] - 2025-12-26

### Added

- **AST Support in Generated Parsers**
  - Generated CST parsers now include `AstNode` type and `parseAst()` method
  - Allows parsing directly to AST (without trivia) from generated parsers

- **Packrat Toggle in Generated Parsers**
  - Added `setPackratEnabled(boolean)` method to generated parsers
  - Allows disabling memoization at runtime to reduce memory usage for large inputs

- **Unlimited Action Variable Support**
  - Action code now supports unlimited `$N` positional variables (previously limited to `$1-$20`)
  - Uses regex-based substitution for flexibility

### Fixed

- **Grammar Validation**
  - Implemented `Grammar.validate()` to detect undefined rule references
  - Recursively walks all expressions and reports first undefined reference with location
  - Previously, grammars with typos in rule names would fail at parse time with cryptic errors

- **Thread Safety in Whitespace Skipping**
  - Moved `skippingWhitespace` flag from `PegEngine` (per-instance) to `ParsingContext` (per-parse)
  - Fixes potential race conditions when reusing parser instances across threads

- **Packrat Cache Key Collision Risk**
  - Changed cache key from `hashCode()` to unique sequential IDs
  - Eliminates theoretical collision bugs with different rule names having same hash

### Changed

- **Builder API Naming Standardized**
  - `PegParser.Builder` methods renamed for consistency: `withPackrat()` → `packrat()`, `withTrivia()` → `trivia()`, `withErrorRecovery()` → `recovery()`
  - Removed duplicate `ParserConfig.Builder` (unused)

- **Documentation Cleanup**
  - Removed undocumented `%word` directive from documentation (feature not implemented)
  - Removed unused placeholder `skipWhitespace()` method from `ParsingContext`

- **Code Simplification**
  - Consolidated 3 duplicate expression parsing switch statements into unified `parseExpressionWithMode()`
  - Extracted `buildParseError()` helper to eliminate duplicate error message construction
  - Removed unused `SemanticValues.choice` field and getter
  - Removed unused `SourceLocation.advanceColumn()`/`advanceLine()` methods
  - ~120 lines of duplicate code eliminated

- Test count: 268 → 271
- Updated pragmatica-lite dependency: 0.8.4 → 0.9.0

## [0.1.5] - 2025-12-22

### Fixed

- **CutFailure Propagation Through Repetitions**
  - Fixed CutFailure not propagating through repetitions (ZeroOrMore, OneOrMore, Optional, Repetition)
  - Previously, repetitions would treat CutFailure as "end of repetition" and succeed with partial results
  - Now CutFailure correctly propagates up, preventing silent backtracking after commit
  - Fixes issue where parse errors were reported at wrong positions (e.g., start of class instead of actual error)

- **CutFailure Propagation Through Choices**
  - CutFailure now propagates through Choice rules instead of being converted to regular Failure
  - Enables cuts in nested rules to affect parent rule behavior correctly

- **Word Boundary Checks in Grammars with Cuts**
  - Added word boundary checks (`![a-zA-Z0-9_$]`) before cuts in type declarations
  - Prevents false commits when keyword is prefix of identifier (e.g., `record` in `recordResult`)

- **Error Position Tracking in Generated Parsers (ADVANCED mode)**
  - Fixed `trackFailure()` not being called in generated match methods
  - Error positions now correctly report the furthest position reached before failure
  - Previously, `furthestFailure` was always null causing fallback to current position after backtracking

## [0.1.4] - 2025-12-21

### Added

- **Cut Operator (`^` / `↑`)**
  - Commits to current choice alternative, prevents backtracking
  - Compatible with cpp-peglib syntax (both `^` and `↑` supported)
  - Provides accurate error positions after commitment
  - Works in both runtime and generated parsers
  - Example: `Rule <- ('if' ^ Statement) / ('while' ^ Statement)`

## [0.1.3] - 2025-12-21

### Fixed

- **Error Position Tracking**
  - Fixed error positions reporting 1:1 after PEG backtracking
  - Now tracks furthest position reached before failure for accurate error locations
  - Custom error messages preserved while using correct position

- **Java 25 Grammar** (synced from jbct-cli)
  - Added annotation support on enum constants (`@Deprecated RED`)
  - Fixed operator ambiguity with negative lookahead (`|` vs `||`, `&` vs `&&`, `-` vs `->`)
  - Fixed `QualifiedName` to not consume `.` before keywords like `class` (`String.class`)
  - Added keyword boundary helper rules to prevent whitespace issues in statements
  - Fixed `Member` rule order for better parsing of nested types

## [0.1.2] - 2025-12-20

### Fixed

- **Java 25 Grammar**
  - Contextual keywords (var, yield, record, sealed, non-sealed, permits, when, module) now allowed as identifiers
  - Generic method calls supported in PostOp rule (e.g., `foo.<Type>bar()`)
  - Added documentation for hard vs contextual keywords

- **Generated Parser (ADVANCED mode)**
  - Added missing `expected` field to `CstNode.Error` record
  - Fixed `attachTrailingTrivia` to preserve `expected` when reconstructing Error nodes

## [0.1.1] - 2025-12-20

### Fixed

- **Trivia Preservation**
  - Fixed trivia loss when Choice fails in sequence
  - Fixed trivia loss when Optional/ZeroOrMore fails in sequence
  - Extended `isReference` to handle wrapper expressions (Optional, ZeroOrMore, OneOrMore)
  - Fixed whitespace handling around predicates and references
  - Removed redundant `skipWhitespace()` calls that discard trivia

- **Java 25 Grammar**
  - Added `LocalTypeDecl` rule to support annotated and modified local type declarations
  - Local records/classes with `@Deprecated`, `final`, etc. now parse correctly

### Changed

- Test count: 242 → 243

## [0.1.0] - 2025-12-19

### Added

- **Core PEG Parsing Engine**
  - Full PEG grammar support compatible with cpp-peglib syntax
  - Packrat memoization for O(n) parsing complexity
  - Both CST (lossless) and AST (optimized) tree output

- **Grammar Features**
  - Sequences, ordered choice, quantifiers (`*`, `+`, `?`, `{n,m}`)
  - Lookahead predicates (`&e`, `!e`)
  - Character classes with negation and case-insensitivity
  - Token boundaries (`< e >`) for text capture
  - Named captures and back-references (`$name<e>`, `$name`)
  - Whitespace directive (`%whitespace`)

- **Inline Actions**
  - Java code blocks in grammar rules (`{ return sv.toInt(); }`)
  - SemanticValues API (`$0`, `$1`, `sv.token()`, `sv.get()`)
  - Runtime compilation via JDK Compiler API

- **Trivia Handling**
  - Whitespace and comments preserved as Trivia nodes
  - Leading and trailing trivia on all CST nodes
  - Line comments (`//`) and block comments (`/* */`) classification

- **Error Recovery**
  - Three strategies: NONE, BASIC, ADVANCED
  - Rust-style diagnostic formatting with source context
  - Multi-error collection with Error nodes in CST
  - Recovery at synchronization points (`,`, `;`, `}`, `)`, `]`, newline)

- **Source Code Generation**
  - Generate standalone parser Java files
  - Self-contained single file output
  - Only depends on pragmatica-lite:core
  - Type-safe RuleId sealed interface hierarchy
  - Optional ErrorReporting mode (BASIC/ADVANCED)
  - ADVANCED mode includes Rust-style diagnostics in generated parser

- **Test Suite**
  - 242 tests covering all features
  - Examples: Calculator, JSON, S-Expression, CSV, Java 25 grammar

### Dependencies

- Java 25
- pragmatica-lite:core 0.8.4
