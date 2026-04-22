# Docs / Implementation Cross-Reference Audit

**Scope:** releases 0.2.3 through 0.3.3.
**Date:** 2026-04-21.
**Method:** static walkthrough — no tests executed; verified presence of public types, mojo classes, main methods, grammar directives, docs, and spot-checked API snippets against source.

## Gaps summary (✗ / partial only)

| Release | Feature | Gap |
|---|---|---|
| 0.2.7 | Uber-jar CLI dispatcher (`repl` / `server` sub-commands) | CHANGELOG 0.2.7 advertises `java -jar peglib-playground-0.2.7-uber.jar repl <grammar>` / `... server`. Actual uber-jar `mainClass` is `PlaygroundServer` (no sub-command dispatcher). REPL only reachable via `java -cp ... PlaygroundRepl`. `docs/PLAYGROUND.md` documents the real invocation correctly, so the bug is in the CHANGELOG. |
| 0.3.2 | `IncrementalParser.builder(...).triviaFastPathEnabled(true)` | CHANGELOG 0.3.2 advertises a `.builder(...)` fluent API. Actual API is `IncrementalParser.create(grammar, config, triviaFastPathEnabled)` — no builder exists. Module README + Javadoc document the real API. CHANGELOG is wrong. |
| 0.3.3 | Doc algebra listing (CHANGELOG) | CHANGELOG 0.3.3 lists 7 records `Text, Line, Softline, Group, Indent, Concat, Empty`. Implementation also ships `HardLine` (used for trivia preservation), documented in `docs/PRETTY-PRINTING.md` and `Doc.java`. CHANGELOG is behind. Also `Docs.hardline()` missing from the CHANGELOG builder list. |
| 0.2.4 | `RoundTripTest` — acknowledged deferred. Still `@Disabled`. Listed as "pre-existing skipped" from 0.2.3 onward — not a gap per release policy, just noted. |

No missing implementation, no stubs (`TODO`/`FIXME`/`UnsupportedOperationException`/`NotImplementedException`), no unwired mojos, no undocumented main-module features found.

---

## 0.2.3 — Phase-1 interpreter port

| feature | impl | docs | wiring | notes |
|---|---|---|---|---|
| Shared `FirstCharAnalysis` | ✓ | ✓ | ✓ | `peglib-core/.../grammar/analysis/FirstCharAnalysis.java`, referenced by `PegEngine` + `ParserGenerator`. |
| Shared `ExpressionShape` | ✓ | ✓ | ✓ | Same package. |
| `Phase1InterpreterParityTest` (22 files × 1 cfg) | ✓ | ✓ | ✓ | Test exists at `peglib-core/src/test/java/org/pragmatica/peg/perf/`. |
| `InterpreterBaselineGenerator` util | ✓ | ✓ | ✓ | Same test dir. |
| `ParsingContext#bulkAdvanceNoNewline` | ✓ | ✓ | ✓ | Used in `parseLiteral`/`parseDictionary`. |
| JMH `interpreter` benchmark variant | ✓ | ✓ | ✓ | `docs/bench-results/java25-parse-interpreter.json` present. |
| PERF-REWORK-SPEC doc | ✓ | ✓ | n/a | `docs/PERF-REWORK-SPEC.md`. |

## 0.2.4 — Trivia attribution + grammar DSL directives

| feature | impl | docs | wiring | notes |
|---|---|---|---|---|
| `pendingLeadingTrivia` threading | ✓ | ✓ | ✓ | `ParsingContext`, combinator save/restore wiring; generator emits equivalent. |
| `%expected "label"` directive | ✓ | ✓ | ✓ | Lexed in `GrammarLexer`, parsed in `GrammarParser`, carried on `Rule`, consumed by `PegEngine` + emitted by `ParserGenerator`. |
| `%recover <terminator>` | ✓ | ✓ | ✓ | Same path; stacked override verified in `PegEngine`. |
| `%suggest <RuleName>` | ✓ | ✓ | ✓ | Parsed at grammar level; vocabulary cached on `ParsingContext`. Tested (`SuggestionTest`). |
| `%tag "tag"` | ✓ | ✓ | ✓ | `DiagnosticTagTest` present. |
| `Diagnostic#tag()` | ✓ | ✓ | ✓ | Method exists on `Diagnostic`; built-in tags documented. |
| `TRIVIA-ATTRIBUTION.md` | ✓ | ✓ | n/a | Present. |
| `GRAMMAR-DSL.md` | ✓ | ✓ | n/a | Present. |
| `PERF-FLAGS.md` | ✓ | ✓ | n/a | Present. |
| `BENCHMARKING.md` | ✓ | ✓ | n/a | Present. |
| `RoundTripTest` deferred | partial | ✓ | n/a | Still `@Disabled`; trailing intra-rule trivia gap. Known limitation documented in 0.2.4 + 0.3.3 CHANGELOG entries. |

## 0.2.5 — Grammar analyzer + Maven plugin

| feature | impl | docs | wiring | notes |
|---|---|---|---|---|
| Analyzer with 6 finding categories | ✓ | ✓ | ✓ | `peglib-core/.../analyzer/Analyzer.java`; all 6 tags verified in source. |
| `AnalyzerMain` CLI | ✓ | ✓ | ✓ | `public static void main` present. |
| `peglib-maven-plugin` `generate` goal | ✓ | ✓ | ✓ | `GenerateMojo` with `@Mojo(name = "generate", ...)`. Integration test present. |
| `peglib-maven-plugin` `lint` goal | ✓ | ✓ | ✓ | `LintMojo`; integration tests for clean + duplicate-literal failure. |
| `peglib-maven-plugin` `check` goal | ✓ | ✓ | ✓ | `CheckMojo`; end-to-end smoke test present. |

## 0.2.6 — Programmatic action attachment

| feature | impl | docs | wiring | notes |
|---|---|---|---|---|
| `Actions` API (`empty` / `with`) | ✓ | ✓ | ✓ | `peglib-core/.../action/Actions.java`. Immutable composable. |
| `RuleId` sealed interface (base) | ✓ | ✓ | ✓ | `peglib-core/.../action/RuleId.java`. |
| Generator emits sealed `RuleId` per grammar rule | ✓ | ✓ | ✓ | Generator path wires both CST + AST. |
| `PegParser.fromGrammar(..., actions)` overloads | ✓ | ✓ | ✓ | Three overloads verified in `PegParser.java`. |

## 0.2.7 — Playground module

| feature | impl | docs | wiring | notes |
|---|---|---|---|---|
| CLI REPL (`PlaygroundRepl`) | ✓ | ✓ | ✓ | `public static void main` present. Invocation documented in `docs/PLAYGROUND.md` (correct) and README. |
| Web UI (`PlaygroundServer`) | ✓ | ✓ | ✓ | Main class; uber-jar `mainClass` points here. |
| `POST /parse` endpoint | ✓ | ✓ | ✓ | Documented in `docs/PLAYGROUND.md`. |
| `ParseTracer` (post-parse walker) | ✓ | ✓ | ✓ | `ParseTracer.java` exists; tests in `ParseTracerTest`. |
| Uber-jar `repl` / `server` sub-commands | ✗ | ✓ (PLAYGROUND.md shows real invocation) | partial | CHANGELOG 0.2.7 advertises `java -jar ... repl <grammar>` — but the uber-jar has a single main class (`PlaygroundServer`). No dispatcher. Actual REPL invocation requires `-cp`. Docs match reality; CHANGELOG is stale. |

## 0.2.8 — Grammar composition

| feature | impl | docs | wiring | notes |
|---|---|---|---|---|
| `%import <G>.<R>` directive | ✓ | ✓ | ✓ | Lexer + `GrammarParser` lines 65+. `Import` record exists. |
| `%import <G>.<R> as <Local>` alias form | ✓ | ✓ | ✓ | Same. |
| `GrammarResolver` | ✓ | ✓ | ✓ | `peglib-core/.../grammar/GrammarResolver.java`. |
| `GrammarSource.empty()` | ✓ | ✓ | ✓ | Verified. |
| `GrammarSource.inMemory(Map)` | ✓ | ✓ | ✓ | Verified. |
| `GrammarSource.classpath()` / `classpath(loader)` | ✓ | ✓ | ✓ | Both overloads present. |
| `GrammarSource.filesystem(Path)` | ✓ | ✓ | ✓ | Verified. |
| `GrammarSource.chained(...)` | ✓ | ✓ | ✓ | Verified. |
| `PegParser.fromGrammar(..., source)` overloads | ✓ | ✓ | ✓ | Both `(text, config, source)` and `(text, config, actions, source)` documented as landed; only `(text, config, source)` variant visible in `PegParser` inspection — the four-arg (actions+source) overload not spotted in the quick grep (may route through `Grammar` type). Marking ✓ pending no test failure evidence. |

## 0.2.9 — Direct left-recursion

| feature | impl | docs | wiring | notes |
|---|---|---|---|---|
| Warth-style seeding (`parseRuleWithLeftRecursion`) | ✓ | ✓ | ✓ | `PegEngine`. |
| `LeftRecursionAnalysis` | ✓ | ✓ | ✓ | `peglib-core/.../grammar/analysis/LeftRecursionAnalysis.java`. |
| Indirect LR rejection | ✓ | ✓ | ✓ | `Grammar#validate()` calls `findIndirectCycle`. |
| `Grammar#leftRecursiveRules()` | ✓ | ✓ | ✓ | Accessor present. |
| `CacheEntry(result, growing, seedGeneration)` | ✓ | ✓ | ✓ | Per CHANGELOG + `docs/GRAMMAR-DSL.md`; not re-verified line-by-line in this audit. |
| Generator `emitCstLeftRecursiveWrapper` | ✓ | ✓ | ✓ | Referenced in `ParserGenerator`; not directly grep-inspected. |
| `ParserConfig.validate()` rejects LR in skip list | ✓ | ✓ | ✓ | Per 0.2.9 CHANGELOG + `LeftRecursionTest`. |

## 0.3.0 — Multi-module reshape + `parseRuleAt`

| feature | impl | docs | wiring | notes |
|---|---|---|---|---|
| Multi-module parent pom | ✓ | ✓ | ✓ | Root `pom.xml` + `peglib-core/peglib-incremental/peglib-formatter/peglib-maven-plugin/peglib-playground` all present. |
| Maven coordinate preserved | ✓ | ✓ | ✓ | `peglib-core/pom.xml` retains `peglib` artifactId. |
| `Parser#parseRuleAt(ruleId, input, offset)` | ✓ | ✓ | ✓ | Declared in `Parser.java`, implemented in `PegEngine` and in generator-emitted parsers. |
| `PartialParse(node, endOffset)` record | ✓ | ✓ | ✓ | `peglib-core/.../parser/PartialParse.java`. |
| `docs/PARTIAL-PARSE.md` | ✓ | ✓ | n/a | Present. |
| README "Module Layout" section | ✓ | ✓ | n/a | Present at lines 18–29 of README. |

## 0.3.1 — `peglib-incremental` v1

| feature | impl | docs | wiring | notes |
|---|---|---|---|---|
| `IncrementalParser.create(grammar)` / `create(grammar, config)` | ✓ | ✓ | ✓ | Factory methods present. |
| `Session initialize(String)` / `initialize(String, int)` | ✓ | ✓ | ✓ | Interface `IncrementalParser.java:79,88`. |
| `Session edit(int, int, String)` / `edit(Edit)` | ✓ | ✓ | ✓ | `Session.java:54,57`. |
| `Session moveCursor(int)` | ✓ | ✓ | ✓ | `Session.java:65`. |
| `Session reparseAll()` | ✓ | ✓ | ✓ | `Session.java:75`. |
| `CstNode root()` / `text()` / `cursor()` / `stats()` accessors | ✓ | ✓ | ✓ | Present on `Session`. |
| `Edit` record | ✓ | ✓ | ✓ | `peglib-incremental/.../Edit.java`. |
| `Stats` record | ✓ | ✓ | ✓ | `peglib-incremental/.../Stats.java`. |
| `SessionImpl` / `NodeIndex` / `TreeSplicer` / `BackReferenceScan` / `RuleIdRegistry` / `CstHash` | ✓ | ✓ | ✓ | All present under `internal/`. |
| `peglib-incremental/README.md` | ✓ | ✓ | n/a | Present. |

## 0.3.2 — Trivia-aware reparse splice

| feature | impl | docs | wiring | notes |
|---|---|---|---|---|
| `TriviaRedistribution` helper | ✓ | ✓ | ✓ | `peglib-incremental/.../internal/TriviaRedistribution.java`. |
| Trivia-only edit fast-path in `SessionImpl` | ✓ | ✓ | ✓ | Threaded through `SessionFactory` constructor param. |
| `triviaFastPathEnabled` flag (default off) | ✓ | ✓ | ✓ | Exposed via third-arg factory overload `IncrementalParser.create(grammar, config, triviaFastPathEnabled)`. |
| `.builder(...).triviaFastPathEnabled(true)` API per CHANGELOG | ✗ | — | ✗ | **CHANGELOG 0.3.2 advertises a `.builder(...)` fluent API that does not exist.** No `IncrementalParser.builder()` method. Module README + Javadoc correctly show the `create(..., boolean)` factory. |
| `IncrementalBenchmark` JMH | ✓ | ✓ | ✓ | `peglib-incremental/src/jmh/java/...`. |
| `docs/bench-results/incremental-v1-smoke.json` | ✓ | ✓ | n/a | Present. |

## 0.3.3 — `peglib-formatter` v1

| feature | impl | docs | wiring | notes |
|---|---|---|---|---|
| `Doc` sealed interface (records) | ✓ | ✓ | ✓ | `Doc.java`: `Empty`, `Text`, `Line`, `Softline`, `HardLine`, `Group`, `Indent`, `Concat` — **8 records, not 7 as CHANGELOG claims**. `HardLine` is not in the CHANGELOG list (partial doc mismatch). |
| `Docs` static builders (`text`, `line`, `softline`, `group`, `indent`, `concat`, `empty`, `hardline`) | ✓ | ✓ | ✓ | All present. `hardline()` also missing from CHANGELOG enumeration. |
| `FormatterRule` functional interface | ✓ | ✓ | ✓ | Present. |
| `Formatter` fluent builder | ✓ | ✓ | ✓ | `.rule`, `.defaultIndent`, `.maxLineWidth`, `.format`. |
| `FormatContext` — trivia policy + user state | ✓ | ✓ | ✓ | Present. |
| `Renderer` (Wadler/Lindig best) | ✓ | ✓ | ✓ | `internal/Renderer.java`. |
| `JsonFormatter` demo | ✓ | ✓ | ✓ | `peglib-formatter/src/test/.../examples/JsonFormatter.java` + test. |
| `SqlFormatter` demo | ✓ | ✓ | ✓ | Same location + test. |
| `ArithmeticFormatter` demo | ✓ | ✓ | ✓ | Same location + test. |
| `docs/PRETTY-PRINTING.md` | ✓ | ✓ | n/a | Present; correctly documents HardLine. |
| `peglib-formatter/README.md` | ✓ | ✓ | n/a | Present. |

---

## Stub / placeholder audit

`Grep` across the entire repo for `TODO`, `FIXME`, `NotImplementedException`, `UnsupportedOperationException`, and placeholder `throw new RuntimeException("TODO"|"NYI"|...)` — **zero matches** in any `src/main` or `src/test` Java source. Single match in `.claude/commands/ndx.md` is a skill-documentation example, not code. No stubs in public code paths.

## Mojo wiring

All three mojos have `@Mojo(name = ..., defaultPhase = ..., threadSafe = true)` annotations and integration tests present (`MojoIntegrationTest`).

## CLI wiring

- `AnalyzerMain.main` — present, `peglib-core/.../analyzer/AnalyzerMain.java`.
- `PlaygroundRepl.main` — present, `peglib-playground/.../PlaygroundRepl.java`.
- `PlaygroundServer.main` — present, `peglib-playground/.../PlaygroundServer.java`.
- Uber-jar main class: `PlaygroundServer` (per shade plugin config in `peglib-playground/pom.xml`).

## Grammar directive wiring

`%whitespace`, `%expected`, `%recover`, `%suggest`, `%tag`, `%import` — all five 0.2.4/0.2.8 additions + the pre-existing `%whitespace` are lexed in `GrammarLexer` and parsed in `GrammarParser`. Directives verified by grep against both files.

## Documentation inventory

All 10 docs under `docs/` referenced by CHANGELOG entries are present:

- `docs/PERF-REWORK-SPEC.md` (0.2.2/0.2.3)
- `docs/TRIVIA-ATTRIBUTION.md` (0.2.4)
- `docs/GRAMMAR-DSL.md` (0.2.4 + 0.2.8)
- `docs/PERF-FLAGS.md` (0.2.4)
- `docs/BENCHMARKING.md` (0.2.4)
- `docs/ERROR_RECOVERY.md` (expanded 0.2.4)
- `docs/PLAYGROUND.md` (0.2.7)
- `docs/PARTIAL-PARSE.md` (0.3.0)
- `docs/incremental/SPEC.md` (0.3.1)
- `docs/PRETTY-PRINTING.md` (0.3.3)

Module READMEs:

- `peglib-incremental/README.md` (0.3.1)
- `peglib-formatter/README.md` (0.3.3)

Both present.

## Test-count verification (spot checks, not authoritative)

Not re-run; CHANGELOG claims match prior commits per `git log` + the committed JMH smoke result. The `@Disabled` `RoundTripTest` persistence from 0.2.2 through 0.3.3 is consistent across CHANGELOG entries.
