# peglib — handover

**Status:** `main` at `v0.3.4` (audit-driven cleanup). 12 tagged releases shipped in this arc (0.2.3 → 0.3.4). 874 tests passing, 1 skipped. Tree clean.

This document orients a fresh maintainer. If you want to do anything with this repo, read §1–§4 first; everything else is reference.

---

## 1. What peglib is

PEG (Parsing Expression Grammar) parser library for Java 25. Inspired by cpp-peglib. Shipped across five Maven modules; five independent artifacts from a single parent pom.

- **`peglib-core`** — artifactId `org.pragmatica-lite:peglib` — the core parser library. Grammar IR, lexer, parser, engine (`PegEngine`), source generator (`ParserGenerator`), analyzer, diagnostic machinery, runtime actions. Everything downstream depends on this.
- **`peglib-incremental`** — cursor-anchored incremental reparser. Depends on core's `parseRuleAt` API added in 0.3.0.
- **`peglib-formatter`** — Wadler-Lindig pretty-printer framework. Depends on core's CstNode.
- **`peglib-maven-plugin`** — Mojo wrappers: `generate` / `lint` / `check`.
- **`peglib-playground`** — CLI REPL + embedded-HTTP web UI (vanilla JS, no framework). Uber-jar via `maven-shade-plugin`.

Parent pom at repo root; modules are sub-directories.

## 2. Quick start

```bash
# Build everything
mvn install

# Test everything (874 + 1 skipped expected)
mvn test

# Run JMH benchmarks (bench profile)
mvn -Pbench -DskipTests package
java -jar peglib-core/target/benchmarks.jar    # or peglib-incremental/target/...

# Run grammar playground server
cd peglib-playground && mvn -DskipTests package
java -jar target/peglib-playground-*-uber.jar 8080
# then open http://localhost:8080 and paste grammar + input

# Run grammar analyzer CLI
java -cp peglib-core/target/peglib-0.3.4.jar \
     org.pragmatica.peg.analyzer.AnalyzerMain path/to/grammar.peg

# Use Maven plugin on a grammar resource
cd peglib-maven-plugin && mvn install    # installs plugin to ~/.m2
# then in a downstream project:
# <plugin>
#   <groupId>org.pragmatica-lite</groupId>
#   <artifactId>peglib-maven-plugin</artifactId>
#   <version>0.3.4</version>
#   <executions><execution><goals><goal>generate</goal></goals></execution></executions>
#   <configuration>
#     <grammarFile>src/main/resources/my.peg</grammarFile>
#     <packageName>com.example.parser</packageName>
#     <className>MyParser</className>
#   </configuration>
# </plugin>
```

## 3. Primary API surface

Grep anchors for reference. Most users touch only the first group.

### Entry points (public)

| What | Where | Notes |
|---|---|---|
| `PegParser.fromGrammar(grammar)` | `peglib-core/src/main/java/org/pragmatica/peg/PegParser.java` | Returns `Result<Parser>` |
| `PegParser.fromGrammar(grammar, config, actions, source)` | same | Four-arg overload with `ParserConfig`, `Actions`, `GrammarSource` |
| `PegParser.generateCstParser(grammar, pkg, cls)` | same | Returns `Result<String>` — source of a standalone CST parser |
| `PegParser.generateCstParser(..., ErrorReporting)` | same | BASIC / ADVANCED |
| `PegParser.generateCstParser(..., ParserConfig)` | same | Generator-time perf flags |
| `Parser#parseCst(input)` | `peglib-core/.../parser/Parser.java` | CST with trivia |
| `Parser#parseAst(input)` | same | Optimized tree |
| `Parser#parseRuleAt(ruleId, input, offset)` | same | Since 0.3.0, used by `peglib-incremental` |
| `IncrementalParser.create(grammar)` | `peglib-incremental/.../IncrementalParser.java` | Factory |
| `Session#edit(offset, oldLen, newText)` | `peglib-incremental/.../Session.java` | Immutable |
| `Formatter.builder()` | `peglib-formatter/.../Formatter.java` | Fluent DSL |

### Configuration

- `ParserConfig` — 14-field record; runtime flags (packrat / recovery / trivia) + 10 generator-time perf flags. Default `DEFAULT` is production-safe. Use `ParserConfig.of(packrat, recovery, trivia)` for the three-arg shortcut.
- `RecoveryStrategy` — `NONE` / `BASIC` / `ADVANCED` on `ParserConfig`. ADVANCED produces Rust-style diagnostics.
- `ErrorReporting` — `BASIC` / `ADVANCED` on `generateCstParser`. Controls emitted-parser diagnostic surface.

### Grammar DSL

Full reference: `docs/GRAMMAR-DSL.md`. Additions beyond cpp-peglib:
- `%import Grammar.Rule [as Local]` — composition (0.2.8)
- `%expected "label"` — rule-level failure-message override (0.2.4)
- `%recover <terminator>` — rule-level ADVANCED-recovery override (0.2.4; see limitation in §8)
- `%suggest RuleName` — grammar-level Levenshtein suggestion vocabulary (0.2.4)
- `%tag "error.xyz"` — machine-readable diagnostic tag (0.2.4)
- Direct left-recursion (Warth seeding) since 0.2.9

Grammar files live as classpath resources (e.g., `peglib-core/src/test/resources/java25.peg`).

## 4. Test suite at a glance

```
peglib-core         676 passing + 1 skipped (RoundTripTest — see §8)
peglib-incremental  100
peglib-formatter     66
peglib-maven-plugin   5
peglib-playground    27
────────────────────────
aggregate           874 + 1 skipped, 0 failures
```

**Key test classes to know:**

- `CorpusParityTest` — 22 corpus files × `CstHash` equality against committed baselines. This is the **primary correctness oracle** for generator changes. If you break parity, you're done.
- `Phase1ParityTest`, `Phase1InterpreterParityTest` — same idea, phase-1 perf flags on.
- `Phase2*ParityTest` — a matrix of phase-2 flag combos.
- `Phase1InterpreterParityTest` — interpreter-side variant (separate baseline dir).
- `GeneratorFlagInertnessTest` — 3 tests asserting generator output is byte-identical between no-config and `ParserConfig.DEFAULT`. Catches accidental leaks when you add flags.
- `IncrementalParityTest` — 22 files × 100 edits × `CstHash` oracle. Scales to 22,000 via `-Dincremental.parity.editsPerFile=1000`.
- `RoundTripTest` — `@Disabled`. See §8.

**Corpus location:**
- Inputs: `peglib-core/src/test/resources/perf-corpus/` — 22 Java 25 files (format-examples, flow-format-examples, large/FactoryClassGenerator.java.txt).
- Generator baselines: `peglib-core/src/test/resources/perf-corpus-baseline/` — `.hash` + `.ruleHits.txt` per file + `ruleCoverage.txt`.
- Interpreter baselines: `peglib-core/src/test/resources/perf-corpus-interpreter-baseline/` — captured in 0.2.3.
- Regenerate via `BaselineGenerator`/`InterpreterBaselineGenerator` utilities (see their classes; they're gated by system properties).

**Anything you change that touches the CST must keep all parity suites green against unchanged baselines.** Changing baselines is a deliberate act that should be done in its own commit and called out explicitly. Don't conflate.

## 5. Release workflow (how every tag so far got cut)

Established pattern across 12 releases, from a `release-X.Y.Z` branch:

1. `git checkout -b release-X.Y.Z`
2. Bump version in all 6 poms (`pom.xml`, `peglib-*/pom.xml`) and `README.md` dependency snippet
3. Add `## [X.Y.Z] - YYYY-MM-DD` CHANGELOG entry
4. `git commit -m "chore: prepare release X.Y.Z"` — this is the FIRST commit on the release branch
5. Do the actual work in subsequent commits (delegate to jbct-coder subagents; `mvn test` after each)
6. Final commit: `docs: CHANGELOG for X.Y.Z (summary)` if CHANGELOG entry wasn't finalized at step 3
7. `git push -u origin release-X.Y.Z`
8. `gh pr create --base main --head release-X.Y.Z --title "..." --body "..."`
9. Wait for the `build` CI check. CodeRabbit runs in parallel — you can skip it if CodeRabbit stalls (user has done this before).
10. `gh pr merge <N> --merge` (preserves branch commit via merge commit)
11. `git checkout main && git pull`
12. `git tag -a vX.Y.Z -m "Release X.Y.Z"`
13. `git push origin vX.Y.Z`
14. `gh release create vX.Y.Z --title "..." --notes "..."` — narrative release notes (not the CHANGELOG copy; human-facing)
15. `git branch -d release-X.Y.Z && git push origin --delete release-X.Y.Z`

**CI gotchas:**
- The JBCT maven plugin (`jbct:check`) runs on every build and rejects unformatted code. Your local commits often pass your editor but fail CI if you skipped `mvn jbct:format` locally. Every recent release had at least one `chore: apply JBCT formatter` follow-up commit for this reason. Just run `mvn jbct:format` before pushing.
- Maven plugin pom pins ASM 9.9 explicitly because `maven-plugin-plugin:3.15.1`'s bundled 9.7.1 can't read Java 25 class files. Revert once `3.15.2+` ships.
- Playground uber-jar via `maven-shade-plugin` — beware `finalName` + `shadedArtifactAttached=true` conflict (fixed in 0.2.7 by using `shadedClassifierName=uber`).

## 6. Design documents (reference)

All under `docs/`:

- `PERF-REWORK-SPEC.md` — the 700-line spec driving 0.2.2 perf work. Historical; features shipped.
- `incremental/SPEC.md` — 559-line spec driving 0.3.1 incremental parser. Historical; v1 shipped, v2 shipped, v2.5 deferred.
- `TRIVIA-ATTRIBUTION.md` — current attribution rule + the documented gap for 0.2.4 trivia deferral.
- `GRAMMAR-DSL.md` — full grammar-syntax reference including all extensions.
- `PERF-FLAGS.md` — table of all 10 `ParserConfig` perf flags.
- `BENCHMARKING.md` — how to run JMH.
- `ERROR_RECOVERY.md` — recovery strategies + diagnostic tags.
- `PARTIAL-PARSE.md` — `parseRuleAt` API reference.
- `PLAYGROUND.md` — playground usage.
- `PRETTY-PRINTING.md` — formatter doc algebra + DSL.
- `AUDIT-REPORTS/` — see §9.
- `bench-results/` — committed raw JMH JSON + probe output from each perf-touching release.

## 7. Key files map (cheatsheet)

### Core engine

- `peglib-core/src/main/java/org/pragmatica/peg/PegParser.java` — public entry point
- `peglib-core/src/main/java/org/pragmatica/peg/parser/Parser.java` — interface
- `peglib-core/src/main/java/org/pragmatica/peg/parser/PegEngine.java` — interpreter (large; ~2k LOC)
- `peglib-core/src/main/java/org/pragmatica/peg/parser/ParsingContext.java` — parse state + packrat cache
- `peglib-core/src/main/java/org/pragmatica/peg/parser/ParserConfig.java` — config record

### Grammar IR

- `peglib-core/src/main/java/org/pragmatica/peg/grammar/Grammar.java` — top-level IR
- `peglib-core/src/main/java/org/pragmatica/peg/grammar/Rule.java` — rule record
- `peglib-core/src/main/java/org/pragmatica/peg/grammar/Expression.java` — sealed interface with all ops
- `peglib-core/src/main/java/org/pragmatica/peg/grammar/GrammarLexer.java`
- `peglib-core/src/main/java/org/pragmatica/peg/grammar/GrammarParser.java`
- `peglib-core/src/main/java/org/pragmatica/peg/grammar/GrammarResolver.java` — `%import` resolution
- `peglib-core/src/main/java/org/pragmatica/peg/grammar/GrammarSource.java` — loaders
- `peglib-core/src/main/java/org/pragmatica/peg/grammar/analysis/` — shared first-char + left-recursion analysis

### CST

- `peglib-core/src/main/java/org/pragmatica/peg/tree/CstNode.java` — sealed interface: Terminal / NonTerminal / Token / Error
- `peglib-core/src/main/java/org/pragmatica/peg/tree/Trivia.java` — sealed: Whitespace / LineComment / BlockComment
- `peglib-core/src/main/java/org/pragmatica/peg/tree/SourceSpan.java`, `SourceLocation.java`

### Code generator

- `peglib-core/src/main/java/org/pragmatica/peg/generator/ParserGenerator.java` — ~4k LOC of emission templates. The beast. Touched by nearly every release.
- `peglib-core/src/main/java/org/pragmatica/peg/generator/ChoiceDispatchAnalyzer.java` — classifies Choice alternatives for phase-2 `choiceDispatch`
- `peglib-core/src/main/java/org/pragmatica/peg/generator/ErrorReporting.java`

### Error recovery

- `peglib-core/src/main/java/org/pragmatica/peg/error/Diagnostic.java`
- `peglib-core/src/main/java/org/pragmatica/peg/error/ParseError.java` — sealed cause hierarchy
- `peglib-core/src/main/java/org/pragmatica/peg/error/RecoveryStrategy.java`

### Analyzer

- `peglib-core/src/main/java/org/pragmatica/peg/analyzer/Analyzer.java` — 6 check implementations
- `peglib-core/src/main/java/org/pragmatica/peg/analyzer/AnalyzerMain.java` — CLI
- `peglib-core/src/main/java/org/pragmatica/peg/analyzer/Finding.java`, `AnalyzerReport.java`

### Actions

- `peglib-core/src/main/java/org/pragmatica/peg/action/Action.java` — SAM interface
- `peglib-core/src/main/java/org/pragmatica/peg/action/Actions.java` — immutable composable map (0.2.6)
- `peglib-core/src/main/java/org/pragmatica/peg/action/RuleId.java` — sealed base (generator emits per-rule records)
- `peglib-core/src/main/java/org/pragmatica/peg/action/ActionCompiler.java` — JDK compiler API path for inline actions
- `peglib-core/src/main/java/org/pragmatica/peg/action/SemanticValues.java`

### Incremental

- `peglib-incremental/src/main/java/org/pragmatica/peg/incremental/IncrementalParser.java`
- `peglib-incremental/src/main/java/org/pragmatica/peg/incremental/Session.java`
- `peglib-incremental/src/main/java/org/pragmatica/peg/incremental/Edit.java`, `Stats.java`
- `peglib-incremental/src/main/java/org/pragmatica/peg/incremental/internal/SessionImpl.java` — state + edit algorithm
- `peglib-incremental/src/main/java/org/pragmatica/peg/incremental/internal/NodeIndex.java` — span → node lookup
- `peglib-incremental/src/main/java/org/pragmatica/peg/incremental/internal/TreeSplicer.java`
- `peglib-incremental/src/main/java/org/pragmatica/peg/incremental/internal/TriviaRedistribution.java` — 0.3.2
- `peglib-incremental/src/main/java/org/pragmatica/peg/incremental/internal/BackReferenceScan.java`
- `peglib-incremental/src/main/java/org/pragmatica/peg/incremental/internal/RuleIdRegistry.java` — runtime-generated marker classes via JEP 457 classfile API

### Formatter

- `peglib-formatter/src/main/java/org/pragmatica/peg/formatter/Doc.java` — sealed algebra
- `peglib-formatter/src/main/java/org/pragmatica/peg/formatter/Docs.java` — builders
- `peglib-formatter/src/main/java/org/pragmatica/peg/formatter/Formatter.java` — fluent builder
- `peglib-formatter/src/main/java/org/pragmatica/peg/formatter/FormatContext.java`
- `peglib-formatter/src/main/java/org/pragmatica/peg/formatter/internal/Renderer.java` — Wadler-Lindig "best" algorithm

### Playground

- `peglib-playground/src/main/java/org/pragmatica/peg/playground/PlaygroundServer.java` — HTTP + SPA serving
- `peglib-playground/src/main/java/org/pragmatica/peg/playground/PlaygroundRepl.java` — CLI
- `peglib-playground/src/main/java/org/pragmatica/peg/playground/ParseTracer.java` — post-parse CST walker
- `peglib-playground/src/main/resources/playground/` — `index.html`, `playground.css`, `playground.js`

### Maven plugin

- `peglib-maven-plugin/src/main/java/org/pragmatica/peg/maven/{Generate,Lint,Check}Mojo.java`

### Bench

- `peglib-core/src/jmh/java/org/pragmatica/peg/bench/Java25ParseBenchmark.java`
- `peglib-core/src/jmh/java/org/pragmatica/peg/bench/PackratStatsProbe.java`
- `peglib-incremental/src/jmh/java/org/pragmatica/peg/incremental/bench/IncrementalBenchmark.java`

## 8. Known limitations & quirks

Ordered by how likely you are to hit them.

### 8.1 `RoundTripTest` is disabled — trivia round-trip incomplete

**This is the #1 outstanding item.** 0.2.4 shipped *trivia attribution threading* (trivia between sibling sequence elements now attaches to the following sibling's `leadingTrivia` — which was previously dropped at 6 sites in `PegEngine` + 4 in `ParserGenerator`). But it did NOT ship the *rule-exit position rewind* that would complete byte-for-byte source reconstruction.

Result: `reconstruct(parseCst(input))` is NOT equal to `input` for most non-trivial grammars because **trailing intra-rule trivia** (trivia consumed inside a rule body when no following sibling exists to pick it up) still gets dropped. `RoundTripTest` stays `@Disabled` with a pointer to `docs/TRIVIA-ATTRIBUTION.md` § "Known limitation".

**Why it matters:** `peglib-formatter` (0.3.3) can't promise trivia-preserving formatting until this lands. `peglib-incremental` v2 (0.3.2) can't offer byte-equal round-trip after edits.

**How to fix:** implement rule-exit pos-rewind in both `PegEngine` (6 sites around 0.2.4 era) and `ParserGenerator` rule emission. Known impact: `NonTerminal` span end offsets shift, which invalidates `CstHash` baselines. Work:
1. Implement the rewind.
2. Regenerate both baselines (`perf-corpus-baseline/` and `perf-corpus-interpreter-baseline/`) via `BaselineGenerator` + `InterpreterBaselineGenerator` with `-Dperf.regen=1`.
3. Handle the 3 infinite-loop edge cases in ZoM iterations that surfaced during the 0.2.4 attempt (didn't fully debug).
4. Un-`@Disabled` `RoundTripTest`; verify 22/22.
5. Release as 0.3.5.

Estimated effort: 2-3 focused days.

### 8.2 `%recover` directive is smoke-only

0.2.4 landed the grammar DSL parsing and `Rule` IR plumbing, but testing in 0.3.4 showed that the directive doesn't visibly shift recovery behavior on the tested input — both overridden and default grammars produce identical diagnostics for `{x@@@}`. Either:
- The override isn't actually consulted at runtime (wiring bug), or
- The test scenario doesn't exercise the path where `%recover` takes effect (likely: recovery fires at top-level `ZeroOrMore` of `Block*`, never entering the `Block` rule body where the override applies).

Tracked as P3 in `docs/AUDIT-REPORTS/CONSOLIDATED-BACKLOG.md`. Fix requires a test input that reaches Block-body recovery + verification that the recovery point actually shifts.

### 8.3 Actions on left-recursive rules

0.2.9 left-recursive rules route through the CST seed-and-grow path (non-recursive to avoid action-driven infinite loops). If a user attaches an action to a left-recursive rule, it runs on the final seed but the semantics around intermediate iterations are unspecified. Documented in `docs/GRAMMAR-DSL.md`.

### 8.4 Indirect left-recursion rejected

0.2.9 supports *direct* left-recursion only. Grammars with `A → B → A` fail `Grammar#validate()` with a clear error. Full Warth seeding with cycle detection is non-trivial; out of scope.

### 8.5 Back-reference rules force full-reparse in incremental

0.3.1 design choice. `BackReferenceScan` marks rules containing `Expression.BackReference` at grammar-validate time; any edit landing inside one falls back to full reparse. The analyzer (`grammar.has-backreference` finding) surfaces these rules so you know what's affected.

### 8.6 Incremental performance is honest, not ambitious

0.3.1/0.3.2 shipped correctness but not the editor-scale perf target. Measured `singleCharEdit` ≈ 325 ms/op on the 1,900-LOC fixture vs. the SPEC's `< 1 ms` target. Root causes:
- Wholesale packrat cache invalidation on edit (v1 decision per SPEC §5.4).
- Back-reference fallback fires on the Java grammar.

SPEC §5.4 v2.5 (span-rewriting cache remap) is the next lever. Substantial work; not started.

### 8.7 Playground tracer is post-parse, not in-parse

`ParseTracer` walks the CST after the parse completes and synthesizes rule-entry events. Real rule entries/exits during parsing aren't instrumented. Good enough for the web UI's use case; not suitable for deep grammar debugging.

### 8.8 JBCT formatter CI tax

Every release had at least one `chore: apply JBCT formatter` commit fixing style drift CI caught. Run `mvn jbct:format` locally before pushing to save a round-trip.

### 8.9 Peglib's own test assertions don't follow JBCT's `Result.onFailure(...)` idiom

Identified in the 0.3.4 audit (P3-deferred — mass rewrite of ~1000 sites). Tests use classic `assertTrue(result.isSuccess())` / `.unwrap()` which discards failure causes. Works but doesn't honor the idiom. Large mechanical fix.

### 8.10 Architectural P3-deferred items

The 0.3.4 audit catalogued these; see `docs/AUDIT-REPORTS/CONSOLIDATED-BACKLOG.md` for file/line pointers:

- Parse-don't-validate: collapse `Grammar#validate()` into a factory returning `Result<Grammar>`.
- Factory naming: `of()` / `create()` / `at()` → `typeName()` across ~20 public API sites. API break.
- `SessionImpl` → record rename (the `*Impl` anti-pattern).
- Mojo `execute()` → Result pipelines + `@Contract` boundary.
- `Formatter` mutable builder → immutable `FormatterConfig` + builder.
- `@Contract` annotations on all CLI `main` + Mojo boundary methods.
- `parseRequestBody` / HttpHandler → `Result.lift` boundary.
- `PegEngine.createWithoutActions` → `Result<PegEngine>` symmetry with `create(...)`.
- Action-dispatch try/catch boundary → `Result.lift`.
- Various internal nullable helpers (`tryIncrementalReparse`, `findBoundaryCandidate`) → `Option`.

## 9. Audit artifacts

`docs/AUDIT-REPORTS/` contains:

- `CONSOLIDATED-BACKLOG.md` — all ~150 findings from the 0.3.4 audit rounds, tagged P0/P1/P2/P3.
- `docs-backreference.md` — per-release impl/docs/wiring matrix (✓ / ✗ / partial for every feature).
- `test-coverage-proof.md` — assertion-strength audit (proven / partial / smoke / missing per feature).
- `docs-fixups-needed.md`, `tests-fixups-needed.md` — actionable items. Most resolved in 0.3.4; remainder is P3.

If you pick up any P3 work, update the BACKLOG entry to reflect status.

## 10. Recommended next work (if asked)

Ordered by value-per-effort, not risk:

1. **Finish trivia round-trip (§8.1).** Highest impact. Unblocks formatter + incremental v2. ~2-3 days.
2. **`%recover` directive real proof (§8.2).** Small, but the 0.3.4 test showed the directive may not be wired correctly end-to-end. ~1 day to fix + verify.
3. **v2.5 span-rewriting cache remap in incremental (§8.6).** Unlocks editor-scale perf. ~1 week, non-trivial.
4. **Parse-don't-validate on Grammar (§8.10).** Architectural cleanup. API break. ~2 days.
5. **JBCT test-assertion mass rewrite (§8.9).** Hygiene. Mechanical. ~3 days of tedious work.
6. Everything else in §8.10 (each ~0.5-2 days).

## 11. How the releases were built (subagent-driven)

This repo was bootstrapped and evolved via heavy subagent delegation. Convention used in this arc:

- **`jbct-coder` subagent** for all substantive coding. Project's `CLAUDE.md` says use only this agent. If a fresh agent refuses the task citing "I'm not jbct-coder", send a follow-up message telling them they ARE jbct-coder — this identity-confusion loop happened 3-4 times in this arc. The `subagent_type="jbct-coder"` at invocation IS the delegation; re-reading CLAUDE.md mid-task tricks them.
- **`build-runner` subagent** for `mvn test` / `mvn install`. Keeps Maven's verbose output out of main context.
- **`jbct-reviewer`** for parallel focused reviews via the `/jbct-review` skill. Spawns 10 in parallel, one per focus area.

The plan file that sequenced this arc is at `/Users/sergiyyevtushenko/.claude/plans/idempotent-giggling-blanket.md` — out-of-repo but useful historical context.

## 12. ndx (optional)

`ndx` is installed in this project (`.ndx/` exists). `/ndx` slash command gives full reference. Useful for cross-referencing files to the 25 tracked sessions that produced this arc. Not load-bearing; skip if you don't use ndx.

---

**Last updated:** 2026-04-24, after v0.3.4 release and audit cleanup. Handover from the agent-driven development arc to whoever picks this up next.
