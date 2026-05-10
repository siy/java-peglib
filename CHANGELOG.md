# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.6.0] - 2026-05-10

_Work in progress — clean-slate redesign. See [`docs/ARCHITECTURE-0.6.0.md`](docs/ARCHITECTURE-0.6.0.md)._

### Architecture (BREAKING)

Major redesign per nine locked decisions: drop interpreter (generator-only with generate-and-compile); two-phase lex → parse with PEG surface preserved; drop runtime actions (replaced by `Visitor<T>` stub); drop AST type (CST is the only tree); pure flat `int[]` CST data layout; trivia as tokens; thin caching incremental layer; one always-on error recovery mechanism; `ParserConfig` deleted (the grammar IS the configuration). Targets parity-with-or-faster-than javac on Java parsing while emitting strictly more output.

### Performance (Java25 corpus, 12 fixtures, JMH avgt)

**0.6.0 is 8.55× faster than the 0.5.x source-generated parser overall** (5.116 ms → 0.598 ms summed across all 12 format-examples fixtures). Per-fixture speedup uniform at 6.7×-9.4× (structural win, not fixture-specific). SwitchExpressions.java (4201 bytes, the worst-case fixture): 1.221 ms → 0.142 ms.

Implementation phasing (Phase A through F per spec §7) is in progress.

### Added

- v6 lexer foundation: rule classifier (LEXER/PARSER/MIXED), Thompson NFA → subset-construction DFA, TokenArray, GLexer codegen + JDK Compiler API loader. Parallel package `org.pragmatica.peg.v6.*` (0.5.x untouched).
- v6 token granularity: post-DFA keyword resolution (`!Keyword Body` skip-prefix pattern). Java25 corpus ANY_CHAR fallback dropped from 87.9% to 3.20%.
- v6 lexer-rule aliasing: structural literal/literal-choice LEXER rules alias to inline-literal kinds. Restores `Modifier`, `ClassKW`, `EnumKW`, etc. that DFA cannot compile due to `!CharClass` word boundary.
- v6 lexer Choice partial-absorption fallback + KMP-driven delimited-block DFA: handles `StringLit` (regular and triple-quoted) and similar `Literal-Body-Literal` patterns previously dropped from the DFA.
- v6 CST: flat `int[]` data structure (8 ints/node, ~32 bytes vs 80-200 in 0.5.x), sealed `CstNode` views (Branch/Leaf/Error), positional trivia helpers, lazy `IntStream` walk.
- v6 ParseResult + Rust-style Diagnostic.
- v6 parser codegen (GParser): recursive descent over TokenArray, FIRST-set Choice dispatch via switch, panic-mode error recovery with synthetic `_ROOT` wrapper.
- v6 visitor codegen (GVisitor<T>): per-rule visit methods + framework (`visit`, `visitChildren`, `defaultResult`, `aggregateResult`).
- v6 PegParser entry point: `fromGrammar(String)` runs Grammar→Classify→DFA→generate-and-compile-lexer→generate-and-compile-parser; cached in ConcurrentHashMap by grammar text; AtomicLong class-name uniquification; cold ~261-919ms, warm 0-2µs.
- v6 incremental: TokenArray.spliceLex (windowed re-lex with ±1 token expansion), CstArray.findCheckpointAncestor + spliceSubtree, IncrementalParser wrapper.
- JMH benchmarks for v6 parse path (warm-parse + cold-compile) under `peglib-core/src/jmh/java/org/pragmatica/peg/v6/perf/`.

### Changed

- Java25 grammar: `>>` and `>>>` shift operators replaced with parser rules `RShift <- '>' '>'` and `URShift <- '>' '>' '>'`. Prevents lexer from fusing two `>` characters into one shift token, which broke nested generics like `List<Map<String, Integer>>`.
- `CstArrayBuilder.truncate` rewritten as O(dropped-range) bounded scan via `lastChildBefore` undo log, replacing the prior O(surviving-nodes) rebuild. Profile showed truncate at 89.7% of warm-parse CPU; this drop is the dominant contributor to the 8.55× headline speedup.
- `TokenArray.nextNonTrivia` precomputed at construction for O(1) lookup (was linear scan).
- **JBCT 0.25.0 conformance pass on v6** (2026-05-10): all 123 strict-mode lint errors flagged by the upgraded plugin have been eliminated. Defensive `IllegalArgumentException`/`IllegalStateException` guards on internal helpers (`CstArray`, `CstArrayBuilder`, `TokenArray`, `TokenArrayBuilder`, `Diagnostic`, the three `*Generator.generate` entry points, both `*Detector.detect`, `IncrementalParser.edit`, `PegParser.fromGrammar`, `LexerEngine` constructor) dropped — callers within v6 are trusted; JVM array-bounds catches genuine OOB if any. `Result<Void>` on `ParserGenerator` emit visitors converted to `Result<Unit>`. Null returns in `DfaBuilder.{collectAliasLiterals, tryPartialChoice, compileDelimitedBlock}`, `IncrementalParser.tryPartialReparse`, `RuleClassifier.extractLeadingLiteral` converted to `Option<T>`. Reflection wrappers in `LexerCompiler.CompiledLexer.lex` and `ParserCompiler.CompiledParser.{parse, parseRuleFrom, ruleKinds}` rewritten as `Result.lift(..., () -> Method.invoke(...)).unwrap()` so the failure cause is a typed `Cause` rather than an ad-hoc rethrow chain. Hot-path void mutators (`CstArrayBuilder.{endNode, setFlag, truncate}`, `TokenArrayBuilder.append`, `PegParser.clearCache`) and JDK-API-mandated throws (`ClassLoader.findClass`, `OutputStream.close`) carry `@SuppressWarnings("JBCT-RET-01"/"JBCT-EX-01")`. Lint passes with 0 errors, 288 warnings (style suggestions only).

### Fixed

- Java25 corpus: 19/20 fixtures (`format-examples/`) parse cleanly via `PegParser.fromGrammar(java25.peg).parse(...)`; remaining 1 (`Annotations.java`) recovers with diagnostics due to annotation-in-body usage (deferred to a future fix).

### Behavior changes

- `LexerEngine.lex(String)` no longer throws `IllegalArgumentException` on a no-DFA-transition stall. The lexer now emits a single-character synthetic `KIND_WHITESPACE` token at the failing offset and continues, so the entire input remains covered by tokens. The downstream parser surfaces such bytes as a trailing-input diagnostic (the same recovery path already used for valid-but-unexpected tokens). Callers that previously relied on a thrown exception to detect malformed input must now inspect `parseResult.diagnostics()` instead.

### Removed

### Intentional drops (per spec — NOT returning)

- BASIC/ADVANCED `RecoveryStrategy` split: one always-on panic-mode mechanism replaces it. Use `result.diagnostics().isEmpty()` for fail-fast semantics.
- Inline `{ ... }` action blocks in grammar: replaced by `GVisitor<T>` stub class generated per grammar (Phase E.1). Compile-time rejection with migration message.
- `AstNode` type: dropped entirely. Build domain ASTs via `GVisitor<T>` walking the CST.
- Packrat memoization: not needed under tokens-first design. JIT scalar-replacement handles short-lived parse state.

### Deferred (planned for later 0.6.x or 0.7)

- Per-rule `%recover` sync sets: `%recover` directive parses (Phase #5) and start-rule sync overrides emit, but per-rule recovery within nested parsers is a no-op. Spec §3.8 calls for per-rule.
- MIXED-rule char-level fallback: rules with both parser-rule references and char-level constructs emit no CST nodes for the char-level parts.
- `ParserOptions` class is a stub; `Parser.parse(input, maxDiagnostics)` ignores the cap.
- Block comment classification through DFA: works in lexer engine post-pass, but `'/*' (!'*/' .)* '*/'` inside a Choice alternative isn't routed through `compileDelimitedBlock`. LINE_COMMENT classification works.
- Per-iteration trivia tokens: `%whitespace` ZeroOrMore matches the entire whitespace+comments run as ONE token. Inner-iteration token splitting requires lexer driver changes.
- Named captures + back-references: state TBD by #12 task.

## [0.5.1] - 2026-05-09

_Unreleased — patch cycle following 0.5.0._

### Added

- **Trivia investigation arc — Steps 1-3** (2026-05-09): catalog of context-dependencies in current attribution, 17-test adversarial corpus targeting 7 divergence categories, post-pass prototype validating context-independent attribution. Round-trip preservation confirmed on 21/21 corpus + 9/9 adversarial inputs; 0 text loss across 46,756 nodes; `parseRuleAt` structural parity achievable under post-pass (load-bearing claim for incremental engine Lever B). Findings: [`docs/incremental/TRIVIA-ADVERSARIAL-FINDINGS.md`](docs/incremental/TRIVIA-ADVERSARIAL-FINDINGS.md). Verdict in HANDOVER §11.
- **Trivia rework Step 4 commits 1-4** (production rework, foundational): opt-in `triviaPostPass` flag on `ParserConfig` (default `false` — zero behavior change). When enabled: post-parse hook applies `TriviaPostPass.assignTrivia(input, cst, grammar)` overriding buffer-driven attribution with context-independent re-derivation from `(input, span)` coordinates. `parseRuleAt` honors splice offset (4-arg overload `assignTrivia(input, cst, grammar, leadingScanFrom)`) — body subtree is structurally identical to corresponding subtree of full reparse, removing one of two HANDOVER §6.4 blockers for incremental engine Lever B. Generator-emitted parsers embed an inline post-pass equivalent (preserves standalone-parser invariant).
- **`org.pragmatica.peg.tree.TriviaPostPass`** (new public API): pure function `(input, cst, grammar) → CstNode` that re-derives leading/trailing trivia from coordinates without parse-history dependency. 4-arg overload supports splice scenarios.

### Changed

- `ParsingContext` pending-trivia methods (`appendPendingLeadingTrivia`, `takePendingLeadingTrivia`, `savePendingLeadingTrivia`, `restorePendingLeadingTrivia`) early-out when `config.triviaPostPass()=true` — buffer becomes dead weight under the flag and is skipped (38 call sites in `PegEngine` no-op without source changes).
- **Trivia rework Step 4 commit 5** (`4ed1cf5`): `ParserGenerator` emits no-op buffer methods under flag-ON. Mirror of commit 3 for the generator side — pure CPU optimization; correctness already from commit 4. Generated parsers under flag-ON no longer do dead-work buffer maintenance.

### Fixed

- **Trivia post-pass orphan-trivia placement matches engine's Bug C' compensation** (`612fbea`). Earlier commit 6 attempt revealed text-loss on Java 25 corpus (`void m()` → `voidm()`). Root cause: post-pass attached orphan trivia to wrapper.trailingTrivia, but `CstReconstruct.emit` only emits trailing for last-child of a wrapper — non-last-wrapper-trailing was invisible during reconstruction. Fix mirrors engine's `attachTrailingToTail` — drains orphan trivia into deepest-rightmost-leaf descendant. Plus a generator-semantics adapter: `rebuildNonTerminal` probe-scans for caller-supplied leading and skips it to prevent license-header double-emission on fixtures whose wrappers' spans include their own leading trivia. RoundTripTest 22/22 under flag-ON across all 22 corpus fixtures including `large/FactoryClassGenerator.java.txt`.
- **`triviaPostPass` default flipped to `true`** (`3b372af`). After the fix, the default-flip succeeded with 0 test failures. Two sentinel tests in `TriviaPostPassFlagTest` inverted (DefaultOffNoOp → DefaultOnNoOp). Phase1/Phase2 parity tests insulated (explicitly pass `triviaPostPass=false`). The 8 fixture-pinning tests for the previous (buffer-driven) attribution remain green via explicit-OFF construction. **End-state for 0.5.1: post-pass attribution is the default; legacy buffer-driven attribution is opt-out via `new ParserConfig(..., triviaPostPass=false, ...)`.**
- **Post-pass O(n²) bench regression fixed** (`6675479`). Initial A/B bench after default flip surfaced 22× / 239× wallclock regression on reference / selfhost fixtures. Investigation pinpointed `computeSpan(input, from, to)` re-scanning `[0, from)` from offset 0 on every trivia chunk to count newlines: O(K · N) where K trivia chunks scale with N input chars → O(N²). Empirical exponent reached 1.88 at N=32000. Fix: precompute a line-start table once per `assignTrivia` call (O(N)) + binary search per `computeSpan` (O(log N)). Both runtime `TriviaPostPass.java` and the embedded version emitted by `ParserGenerator.java` updated symmetrically. Post-fix bench: reference 26.87 ms (1.36× legacy 19.78 ms); selfhost 943.6 ms (1.01× legacy 934.7 ms — within noise). 15.6× speedup on reference, 210× on selfhost vs pre-fix.

- **Trivia rework cleanup arc — Cleanups A through F.3** (2026-05-09, commits `763e6e0` through `0b29c78`):
  - **Cleanup A** (`763e6e0`): strip dead buffer call sites in PegEngine + emitted parsers under flag-ON. 38 PegEngine sites + parallel emit sites short-circuited via `if (!triviaPostPass)` guards; consumers use `List.of()` directly.
  - **Cleanup B** (`884c4c8`): TriviaPostPass thread orphan trailing through recursion (replaces O(depth) spine rebuild) + probe-scan moved out of hot path. Selfhost flipped from parity to **-5% under legacy**.
  - **Cleanup D** (`c682eef`): TriviaPostPass skip rebuild when no trivia change needed. Reference -2.6%; selfhost -11.1% (now strictly faster than legacy).
  - **Cleanup F.1** (`ff596be`): StringSpan runtime foundation. New `org.pragmatica.peg.tree.StringSpan` (CharSequence view with lazy String materialization). CstNode.Terminal/Token components changed from `String text` to `StringSpan textSpan`; `.text()` accessor preserved as String API via `toString()`. 30 new StringSpanTest cases.
  - **Cleanup F.2** (`2fec82c`): emitted CstParseResult.text widened from String to CharSequence — structural prerequisite for F.3.
  - **Cleanup F.3** (`0b29c78`): generator emits inline StringSpan + uses it for token boundary capture and match helper text, eliminating per-call substring allocation on the bench's hot path.

  **Final bench numbers (post-F.3, vs legacy buffer-driven path):**

  | Fixture | Buffer-driven | Post-pass (F.3) | Δ |
  |---|---:|---:|---:|
  | Reference (1900 LOC) | 19.08 ms / 70.65 MB | 24.88 ms / 77.13 MB | +30% wallclock / +9% alloc |
  | Selfhost (37k LOC) | 825.6 ms / 1908 MB | **784.7 ms / 1881 MB** | **-5% wallclock / -1.4% alloc** |

  Selfhost — the GC-bound fixture (52% G1 time per profile) — is now meaningfully faster than legacy buffer-driven attribution. Reference fixture remains +30% over legacy (per-NonTerminal scan cost dominates; addressable in future work). The trivia rework + StringSpan arc delivers a real win on the perf-critical workload (selfhost) while preserving correctness across all 22 RoundTripTest fixtures.

### Removed

## [0.5.0] - 2026-05-06

_Work in progress — incremental-native architectural rework. See `docs/incremental/ARCHITECTURE-0.5.0.md`._

### Phase 0 — spike GO verdict (2026-05-07)

Sandbox prototype of Lever A (stable IDs + LongLongMap NodeIndex) lands additively in `peglib-incremental/src/main/java/org/pragmatica/peg/incremental/experimental/`. Existing 897-test suite untouched. Three GO/NO-GO gates green:

- **Identity-preservation invariant** — `IdTreeSplicer` preserves sibling subtree reference equality through splices (spec §8 Q3 gate).
- **Trivia-bearing edits** — calculator grammar with `%whitespace` + comments, three representative edits, incremental update equivalent to full rebuild (spec §8 Q4 gate).
- **Perf** — JMH bench: 38× speedup at 100 nodes, 47× at 1000, 67× at 10000. Well above the 5× gate threshold; consistent with spec §2's projected 300× per-edit reduction. See [`docs/bench-results/phase0-spike-results.md`](docs/bench-results/phase0-spike-results.md) and [`docs/archive/PHASE-0-RESULTS.md`](docs/archive/PHASE-0-RESULTS.md).

### Phase 1 prove-out (2026-05-07)

Path A (offset decoupling from records) — RED. 1.10-1.29× speedup on flat-tree mid-buffer edits; not worth the cross-cutting refactor. See [`docs/bench-results/phase1-spanindex-results.md`](docs/bench-results/phase1-spanindex-results.md).

Path D (stable-id ancestor preservation) — GREEN. **96-604× speedup** on flat-tree mid-buffer edits with absolute time flat across N (~25-40 ns), confirming genuine O(δ) scaling. The fix: TreeSplicer reuses old ancestor IDs across splices; applyIncremental skips ancestor-rewiring as a result. See [`docs/bench-results/path-d-results.md`](docs/bench-results/path-d-results.md) and [`docs/archive/PHASE-1-PROVE-OUT.md`](docs/archive/PHASE-1-PROVE-OUT.md).

### Phase 1 production migration (2026-05-07)

**BREAKING.** `CstNode` records gain `long id` as the first record component on all four variants (`Terminal`, `NonTerminal`, `Token`, `Error`). `equals`/`hashCode` overridden to exclude `id` per spec §7 R1 — structural equality preserved (`RoundTripTest` baselines unaffected). New `org.pragmatica.peg.tree.IdGenerator` interface + `PerSessionCounter` impl; threaded through `ParsingContext`, `PegEngine`, `ParserGenerator` emission templates. Generated parsers now contain inline `IdGenerator` (preserves the v0.4.2 standalone-parser invariant — no FQCN dep on peglib runtime).

`NodeIndex` internals switch from `IdentityHashMap` to `LongLongMap` (hand-rolled linear-probing, promoted from sandbox to `internal/`). Includes a tombstone-saturation fix: resize triggers on `size + tombstones > threshold`; same-capacity rehash drains tombstones without growing the table.

`IncrementalSession.applyIncremental` adopts Path D's optimized algorithm (steps 1 and 3 from spec §2 deleted because ancestor IDs are stable). Step 6 added: refresh `nodesById` for right-of-edit subtrees that `TreeSplicer.shiftAll` deep-copies — those subtrees retain stable IDs but their records carry post-edit shifted spans, so the lookup map needs a refresh to avoid stale-span pivot errors.

Bench numbers vs 0.4.3 baseline on the 1900-LOC Java fixture (Regime B, cursor-moved-to-edit, same RNG seed `0xBEEFCAFE`):

| Metric | 0.4.3 | 0.5.0 (post-1.7) | Change |
|---|---:|---:|---:|
| Median | 10.8 ms | 5.6 ms | **-48% (1.9× faster)** |
| p95 | 22.4 ms | 14.6 ms | **-35%** |
| p99 | 53.3 ms | 138.8 ms | +160% (large-pivot tail) |
| Max | 98.6 ms | 390.8 ms | +297% (large-pivot tail) |
| % under 16 ms | 91.5% | 95.9% | **+4.4 pp** |

Median + p95 + frame-budget hit rate clearly improved. p99/max regressed for large-pivot edits where `TreeSplicer.shiftAll` deep-copies thousands of right-of-edit records — an accepted trade vs Path A's much-larger-scope refactor. Pivot-selection improvements (spec Lever B) deferred to Phase 2.

See [`docs/incremental/PHASE-1-RESULTS.md`](docs/incremental/PHASE-1-RESULTS.md) for full sub-phase summary and bench caveats.

### Phase 2 attempted, rolled back; bench + JBCT cleanup; Lever D Cursor split (2026-05-07)

**Lever B (top-down pivot)** — investigated and deferred. Two empirical iterations both fail `IncrementalTriviaParityTest`:
- "Strict literal-prefix safe-pivot" (per spec §3): correctness-sound but cost 4× perf — only ~30/133 Java25 rules admitted, forcing walk-up to Block/RecordBody pivots.
- "Boundary-touch walk-up": pivot's internal child boundaries still produce trivia divergence.

Root cause: trivia attribution is context-sensitive — Lever B retry blocked on trivia attribution refactor. `SafePivotAnalyzer` + `NodeIndex.smallestEnclosing` preserved as dormant infrastructure. Phase 2 wiring rolled back; Phase 1.7 algorithm restored.

**Bench harness** — `IncrementalSessionBench` now validates each edit post-application; if the resulting buffer is syntactically invalid, the bench rolls back the session and skips the edit. Eliminates the 746-exception buffer-corruption tail that polluted prior bench logs. 41% faster wallclock.

**JBCT cleanup** — `SessionFactory.parseFull` now returns `Result<Session>` instead of throwing `IllegalStateException`. New `SessionError` sealed Cause type. Public `Session.edit` always returns a Session; parse failures surface via new `Session.parseSuccessful()` instead of exceptional control flow. Bench's dead try/catch removed. Aligns with project JBCT mandate (Result for failure-return, no exceptions in business logic).

**Sandbox cleanup** — Phase 0/1 prove-out code (the `experimental/` package + 5 sandbox JMH benches) removed. Production has equivalents (`IdGenerator` in `peglib-core/tree`, `LongLongMap` in `peglib-incremental/internal`). -5463 LOC across 31 files. Git history preserves the journey.

**Lever D — Cursor/Session split** (per spec §5). Cursor state (offset + enclosingNodeId) extracted from `IncrementalSession` record into a new public `Cursor` record. New `EditOutcome(Session, Cursor)` and `ReparseOutcome(Session, Cursor)` records returned by `edit` / `reparseAll`. `Cursor.moveTo(int newOffset, NodeIndex index)` is pure — no Session allocation. `InitialSession(Session, Cursor)` returned by `IncrementalParser.initialize`. Cursor uses Phase 1's stable `long enclosingNodeId` so it survives session churn.

Bench post-Lever-D vs Phase 1.7 baseline (Regime B):

| Metric | Phase 1.7 | Post-Lever-D |
|---|---:|---:|
| Median | 5.5 ms | **5.0 ms** (-9%) |
| p95 | 14.6 ms | **11.2 ms** (-23%) |
| p99 | 192.7 ms | **90.5 ms** (-53%) |
| % under 16 ms | 95.4% | **96.5%** (+1.1 pp) |

Lever C (peglib-rt IR unification per spec §4) is the next-session entry point.

### Throughput engine — Tier 1 perf arc (2026-05-07 → 2026-05-08)

Branding: parser generator output is now the **throughput engine** (full-reparse speed) — distinct from the **incremental engine** (interactive editing). Different optimization targets, different code shapes, no shared code. See [`docs/incremental/THROUGHPUT-ENGINE-TIER1.md`](docs/incremental/THROUGHPUT-ENGINE-TIER1.md) for the spec; [`docs/bench-results/throughput-tier1-results.md`](docs/bench-results/throughput-tier1-results.md) and [`docs/bench-results/generator-profile-baseline.md`](docs/bench-results/generator-profile-baseline.md) for the data.

**Bench fixtures:** Java25ParseBenchmark now has TWO fixtures — `reference` (1900-LOC FactoryClassGenerator.java) and `selfhost` (the Java25 generated parser parsing its OWN 37k-line generated source — pre-Tier-1 OOM'd; now 1 second). Self-host stress test caught E's silent regression that the small bench missed.

**Cumulative bench (Regime: full reparse, JDK 25, JMH 1.37, variant `phase1_allStructural_mutableResult_autoSkipPackrat`):**

| Fixture | Original | Now | Δ |
|---|---:|---:|---:|
| Reference (1900 LOC) | 76.2 ms / 150 MB | **25.1 ms / 82.9 MB** | **-67% wallclock, -45% bytes** |
| Self-host (37k LOC) | OOM | **1035 ms / 2.19 GB** | from impossible to 1 sec |
| Reference gc.count | 205 | 50 | -75% |
| Reference gc.time | 2,844 ms | 354 ms | -87% |

**Moves shipped:**
- **A** — opt-in `mutableParseResult` ParserConfig flag. Emits a mutable `CstParseResult` class with raw nullable fields (no `Option<Object> value` / `Option<String> expected` wrappers) + raw-nullable `furthestFailure` / `furthestExpected` / `pendingFailureRecoveryOverride`. Eliminates 6,088 Option$Some allocation samples per parse → 0.
- **D** — `inlineLocations` flag default-on. Emitted CST construction uses `new SourceSpan(line, col, off, endLine, endCol, endOff)` directly instead of `SourceSpan.sourceSpan(new SourceLocation(...), new SourceLocation(...))`. Two passes: production sweep (cleanup, -63 LOC) + emission templates (the win).
- **D follow-up** — eliminated remaining `location()` callers in emission. SourceLocation samples 3,318 → ~600.
- **F (first-set Choice dispatch)** — extended `ChoiceDispatchAnalyzer` from literal-only to full transitive FIRST-set computation. CharClass + Reference (recursive) + mixed dispatch. 19/64 → 62/64 of Java25's choices now dispatch via `switch (text.charAt(pos))` instead of speculative PEG-ordered evaluation. -20% wallclock on both fixtures.
- **G (JBCT-style method splitting)** — top-level rule Choices extract each alt to `parse_<Rule>_alt<N>(Rule rule)` helper methods; new `Rule` record (static singletons, zero allocation) holds rule metadata. parse_Stmt 27,783 → <3,000 bytes. Most parse methods now JIT-inlinable.
- **G2 + H** — Sequence chunking + nested Choice extraction. Same pattern applied recursively.
- **Selective packrat auto-detection** — `selectivePackrat=true` is now default; `PackratAnalyzer.autoSkipPackratRules(grammar)` derives the skip-set automatically. Leaf-like and single-call-site rules bypass cache. Biggest single win: -38% reference / -14% self-host wallclock; -75% gc.count.

**Move that did NOT ship:**
- **E (packrat as `int[]`-keyed open-addressing map)** — clean on small bench, but **+22% regression on self-host stress test**. Linear probing scales badly at the load factors the 37k-LOC fixture stresses. Reverted. Lesson informed adding self-host as the second JMH fixture.
- **H2 (recursive per-alt extraction in nested Choices)** — bytecode size dropped further but +4-7% wallclock regression. C2 was already inlining post-H; further splitting traded inline-friendly bytecode for call-overhead. Reverted.

**+ DFA fast-path (token-shaped rules)** — `tokenFastPath` flag default-on. Detects rules whose body matches `< CharClass + ZeroOrMore<CharClass> >` (Identifier-shape) and emits a tight inline scanner using pre-computed ASCII bitmasks instead of going through the framework's `matchCharClass` per character. **-9.8% reference / -7.6% self-host wallclock**. On Java25 only `Identifier` matches the spike's pattern; generalizing to `OneOrMore<CharClass>` (whitespace) and mixed Literal+CharClass (NumLit) is the obvious next extension and would compound.

**vs javac comparison:** at the time of writing, peglib's throughput engine parses the 1900-LOC reference fixture in 22.6 ms (vs javac 9 ms = 2.5× of javac) with strictly more output (lossless CST + trivia for formatter+linter use cases). javac produces AST without trivia.

**Items deferred:**
- **Generalized DFA lexer** — extend the spike pattern to `OneOrMore<CharClass>` (whitespace) and mixed Literal+CharClass (NumLit). Each generalization compounds with the Identifier win.
- **ASCII whitespace fast path** — folded into generalized DFA above.
- **Char-class bit-packing** — pre-emit ASCII bitmaps; bitwise test instead of range comparisons. Tactical, ~5-15% on char-class-heavy paths. **Reassess with care after Move B finding (below) — same risk class.**

### Move B attempted, abandoned + post-rollback profile-driven optimization arc (2026-05-08)

Move B (mutable parse-state singleton) was attempted across 5 incremental commits, then rolled back when bench data contradicted the hypothesis. A profile-driven optimization arc on the rolled-back baseline shipped 2 wallclock wins. Full session post-mortem with quantitative data: [`docs/incremental/THROUGHPUT-ENGINE-MOVE-B.md`](docs/incremental/THROUGHPUT-ENGINE-MOVE-B.md) §11 + [`docs/HANDOVER.md`](docs/HANDOVER.md) §11.

**Move B failure (5 commits rolled back):**

Replacing per-call `CstParseResult` allocation with a heap-bound singleton + boolean returns regressed wallclock monotonically while allocation dropped:

| Stage | Wallclock (ms) | Alloc (MB) | Δ wallclock vs original | Δ alloc vs original |
|---|---:|---:|---:|---:|
| Original baseline | 22.6 | 75.6 | — | — |
| Commit 3 (match helpers) | 23.97 | 72.1 | +6.0% | -4.6% |
| Commit 4 (combinators) | 24.71 | 66.3 | +9.3% | -12.3% |
| Commit 5 (predicates/capture/cut/TB) | 25.09 | 66.0 | +11.0% | -12.8% |

**Why:** modern JIT escape analysis was already scalar-replacing the per-call records (raw-nullable fields + immediate consume = textbook EA fodder). The singleton replacement defeated that optimization — heap-bound field, can't be scalarized, source-level aliasing forces the compiler to assume mutation visibility. Net: GC sees fewer survivor objects (alloc-rate metric drops), but optimized hot path is slower per call. Definitively abandoned.

**Post-rollback wins (2 commits past `v0.5.0-candidate`):**

- **Trivia snapshot via int size** (`4763251`) — replaced `List.copyOf(pendingLeadingTrivia)` snapshot + `clear()+addAll()` restore with primitive `int size` snapshot + `subList(snap, size).clear()` restore. Profile evidence: 6.0% reference CPU + 15.4% reference alloc in `Arrays.copyOf` (top alloc caller). Pattern: structural alloc elimination on hot path; JIT cannot elide bulk array copies. **-12.1% reference / -8.2% selfhost wallclock; -6.4% / -7.5% alloc.**
- **ASCII char interning pool** (`38b6a8e`) — eliminated `String.valueOf(c)` per match in `matchCharClassCst` + `matchAnyCst` via pre-computed `ASCII_CHAR_STRINGS[128]` static field. Profile evidence: 5.8% of total reference allocs from per-match fresh 1-char Strings. Pattern: per-call fresh String alloc with no JIT scalar-replacement path. **-3.95% reference / -4.59% selfhost wallclock; -3.76% / -1.38% alloc.**

**Cumulative session wins (from `v0.5.0-candidate` baseline):**

| Fixture | v0.5.0-candidate | After 38b6a8e | Δ wallclock | Δ alloc |
|---|---:|---:|---:|---:|
| Reference (1900 LOC) | 22.66 ms / 75.55 MB | **19.12 ms / 68.02 MB** | **-15.6%** | **-10.0%** |
| Selfhost (37k LOC) | 937 ms / 2.04 GB | **832 ms / 1.85 GB** | **-11.2%** | **-9.3%** |

**Reference fixture is now under 20 ms** — original Move B target (~2× of javac territory) achieved without singleton mutation.

**Resets that did NOT ship (5 candidates evaluated under strict bench gate):**
- **trackFailure dedup via LinkedHashSet** — predicted -6.4% wallclock; reality flat. Lesson: JIT inlines `String.contains` efficiently when the scanned buffer is small; the profile share was call-overhead-dominated.
- **Primitive long-keyed packrat cache** (replace HashMap) — predicted -12.5% CPU; reality selfhost +24.5% wallclock regression. Lesson: JDK HashMap chaining is per-op faster than custom open-addressing + SplitMix64 finalizer; replacing it cost more in latency than was saved in alloc.
- **HashMap initial-capacity hint** from input size — reality reference +3.9% / selfhost +5.3% regression. Lesson: over-sizing hurts cache locality more than it saves resize cost. JDK HashMap's growth schedule is well-tuned.
- **Lazy Token text materialization** (record→class with cache) — reality selfhost +10% regression. Lesson: Java records are JIT-scalar-replaceable in a way mutable classes (even with single cache field) are not. Same family of lesson as Move B.
- **Lazy SourceLocation in trackFailure** — reality flat on both fixtures. Lesson: SourceLocation is a record; JIT readily stack-allocates / dead-code-eliminates around hot-path early returns. Allocation share in profile is NOT predictive of wallclock improvement when JIT/EA already handles the alloc cleanly.

**Refined optimization principle:** target allocations the JIT cannot elide (bulk array copies, per-call fresh objects with no scalar-replacement path). Avoid optimizing patterns the JIT already handles (records, immediately-consumed objects, JDK collection internals, mutable shared state on `this`).

## [0.4.3] - 2026-05-06

Performance — interactive editing focus. 19% faster median, 26% faster p95 on the IncrementalBenchmark editing-session suite. **One breaking change**: SourceSpan record components changed from two SourceLocations to six ints (see migration note).

### Performance (interactive editing on 1,900-line Java fixture)

| Metric | 0.4.2 | 0.4.3 | Change |
|---|---:|---:|---:|
| Median | 13.3 ms | 10.8 ms | -19% |
| p95 | 30.1 ms | 22.4 ms | -26% |
| p99 | 56.4 ms | 53.3 ms | -5% |
| % under 16ms (frame budget) | 83% | 91.5% | +8.5pp |

### Changed (BREAKING)

- **`SourceSpan` stores int triples instead of `SourceLocation` refs.** Component accessors changed from `start()`/`end()` (returning SourceLocation refs) to `startLine()`/`startColumn()`/`startOffset()`/`endLine()`/`endColumn()`/`endOffset()` (auto-generated record component accessors returning ints). The methods `start()` and `end()` are preserved as regular methods that lazily materialize SourceLocation. The factory `SourceSpan.sourceSpan(SourceLocation, SourceLocation)` is preserved. **Migration:** code that pattern-matches `case SourceSpan(SourceLocation s, SourceLocation e)` must be updated to either `case SourceSpan span` + accessor calls, or `case SourceSpan(int sl, int sc, int so, int el, int ec, int eo)`. Code calling `.span().start().line()` should migrate to `.span().startLine()` to skip the SourceLocation materialization. Drove the 26% p95 improvement.

### Performance — internals

- **`PegEngine` interpreter:** no API change. Continued from 0.4.1.
- **`NodeIndex.build` pre-sizes the IdentityHashMap** from a descendant-count pass before populating, eliminating resize churn that was 22.8% of bench allocations. (Commit `fcff78f`)
- **New `IncrementalSessionBench`** in `peglib-incremental/src/jmh/java`: realistic 1,000-edit interactive sessions with cursor-moved-to-edit (Regime B) AND cursor-pinned (Regime A) regimes. Reports per-class median/p95/p99/max + frame-budget hit rate + top-10 outlier diagnostics. Recommended JVM tuning for downstream consumers in `peglib-incremental/README.md`: `-Xms2g -Xmx2g -XX:MaxGCPauseMillis=20` reduces p99 by 33% (60ms → 40ms) — free for downstream. (Commits `716a113`, `77c7c70`)

### Notes for downstream

- **Regenerate generated parsers** with 0.4.3's `ParserGenerator` to pick up the gen-time SourceSpan emission update (also int-based).
- **Architectural rework planned for 0.5.0.** See `docs/incremental/ARCHITECTURE-0.5.0.md` (when published) for the planned overhaul targeting incremental-native data structures.

## [0.4.2] - 2026-05-04

Standalone-parser fix. No API changes for the interpreter / incremental / formatter paths.

### Fixed

- **Generated parsers are now truly standalone.** Previous releases' generated `RuleId` interface declared `extends org.pragmatica.peg.action.RuleId` and the emitted `parseRuleAt` signature used `Class<? extends org.pragmatica.peg.action.RuleId>`. This contradicted the documented contract on `ParserGenerator` ("The generated parser depends only on pragmatica-lite:core") — downstream projects without peglib on their compile classpath could not build the emitted source. The link was vestigial: the generated `withAction(Class<? extends RuleId>, ...)` API uses string-based dispatch (`ruleIdClass.getSimpleName()`) and does not need parent-type linkage. (Commit `b72c97f`)
- **Generated parsers no longer reference any FQCN in emitted source.** Audit removed `org.pragmatica.peg.action.RuleId`, `java.util.ArrayDeque`, and other fully-qualified names from `sb.append(...)` emission templates. Proper imports are now emitted in `generateImports` / `generateCstImports` and simple names are used throughout the emitted source. (Commit `b72c97f`)

### Test changes

- `RuleIdEmissionTest` updated to assert standalone shape (`public sealed interface RuleId {`) and the absence of `org.pragmatica.peg.action.RuleId` in emitted source. The test that previously verified the parent-type link via reflection now verifies the local `RuleId` hierarchy instead.

### Notes for downstream

- **Regenerate parsers** to pick up the standalone fix.
- Generated parsers continue to depend only on `pragmatica-lite:core` (for `Result`, `Option`, `Cause`).
- The `peglib-incremental` module's interpreter-based `parseRuleAt` API is unchanged — that path still uses `org.pragmatica.peg.action.RuleId` (interpreter-only, not generator-emitted).

## [0.4.1] - 2026-05-04

Performance — 3.88× interpreter speedup, 3× incremental edit speedup, no API changes.

### Performance

- **`PegEngine.lookupRule` HashMap cache.** Replaced `Grammar.rule(name)` linear scan + stream pipeline with a `Map<String, Rule>` populated once at engine construction. Eliminates 14% of parse CPU + 14% of allocations on the interpreter hot path. (Commit `26dea5e`)
- **`ParseMode.standard()` and `ParseMode.noWhitespace()` are static singletons.** Previously allocated a fresh record per call (4% of allocations). The records are immutable; no semantic change. (Commit `2e05d44`)
- **`ParsingContext.updateFurthest` uses `LinkedHashSet` for dedup.** Previously stored expected-token messages in a `StringBuilder` and dedup'd via `indexOf` (O(n*m) per backtrack at the deepest position). Replaced by `LinkedHashSet<String>` (O(1) add+contains) with a lazy " or "-joined cache. **2.12× standalone speedup on the interpreter.** (Commit `654a1c6`)
- **Generated parsers emit the LinkedHashSet pattern for `furthestExpected`.** Same fix at the gen-time emission level for `ParserGenerator`'s BASIC error-reporting path. ADVANCED path uses a different `Option<String>` pattern and was deferred. (Commit `cca46c5`)

### Benchmarks (1900-LOC `FactoryClassGenerator.java.txt` fixture, JDK 25, Apple Silicon)

| Benchmark | 0.4.0 | 0.4.1 | Speedup |
|---|---:|---:|---:|
| `Java25ParseBenchmark.parse` interpreter | 281 ms | **72.4 ± 0.7 ms** | **3.88×** |
| `Java25ParseBenchmark.parse` phase1 (generator) | 86 ms | 79.4 ± 2.0 ms | 1.08× |
| `IncrementalBenchmark.run` initialize | 320 ms | 107 ms | 3.0× |
| `IncrementalBenchmark.run` singleCharEdit (Regime A) | 322 ms | **107 ms** | **3.0×** |
| `IncrementalBenchmark.run` wordEdit (Regime B) | 17 ms | 16 ms | ~no change (already small) |

The interpreter (PegEngine) is now FASTER than the generator's phase1 path because the `LinkedHashSet` fix's interpreter wins (+2.12×) outweigh the equivalent fix's generator wins (+1.08×) — the generator was already heavily optimized, so the same fix had less to bite.

### Documentation

- **HANDOVER §6.2 retraction.** The lever-1 incremental-perf "1-2 day fix" framing was wrong. Two failed attempts (12/100 and 31/100 parity regressions). Real fix needs 5-10 days of correctness analysis. Documented two latent bugs: (a) fallback-rule bypass — `tryIncrementalReparse` only checks the chosen pivot, not its ancestors; (b) `reparseAt`'s acceptance check proves length parity but not structural parity.
- **`docs/archive/V2.5-SPIKE.md` addendum.** Retracts the spike doc's "zero correctness risk" claim. Parity was never asserted in the spike's probe — only timing.
- **HANDOVER §6.4 correction.** Tier-1 perf flags (`inlineLocations`, `markResetChildren`, `selectivePackrat`) are generator-only. They do NOT speed up the interpreter path that `IncrementalParser` uses.

### Reverted

- **`dc7b80d` perf: inline location ints (P2.1).** Ported `inlineLocations` from generator to interpreter at rule entry. 0% wall-time impact — failure paths are rare in successful-parse benchmarks under packrat caching, so the failure-path-only optimization had nothing to bite. Reverted as `023b776`.

### Notes for downstream

- No public API changes. Drop-in replacement.
- Generated parsers from 0.3.x and 0.4.0 should be regenerated with 0.4.1's `ParserGenerator` to pick up the gen-time `furthestExpected` `LinkedHashSet` emission.

## [0.4.0] - 2026-05-01

API consolidation + test hygiene. **Breaking.** No incremental v2.5 cache remap (the original 0.4.0 plan item; superseded by `docs/archive/V2.5-SPIKE.md`'s NO-GO recommendation — the actual lever is pivot-selection, not cache invalidation).

### Changed (BREAKING)

- **`Grammar` is parse-don't-validate.** The instance method `Grammar#validate()` and the legacy backwards-compat record constructors (4-arg pre-0.2.4 / 5-arg 0.2.4-0.2.7) are gone. Construct a validated `Grammar` via the new static factory `Grammar.grammar(rules, startRule, whitespace, word, suggestRules, imports)` (and the 4-arg overload `Grammar.grammar(rules, startRule, whitespace, word)`), which returns `Result<Grammar>` and runs the same checks the old `validate()` did (undefined rule references, unsupported indirect left-recursion). `GrammarParser.parse(text)` and `GrammarResolver.resolve(...)` route through the factory, so callers using the public `PegParser` API observe the validation failure earlier in the pipeline. `PegParser.fromGrammar(Grammar, ...)` no longer revalidates — the input is assumed already-validated.
- **`tree` package factory rename.** `SourceLocation.at(line, column, offset)` → `SourceLocation.sourceLocation(...)`; `SourceSpan.of(start, end)` → `SourceSpan.sourceSpan(...)`; `SourceSpan.at(location)` → `SourceSpan.sourceSpan(location)` (overload). Generator emits the new typeName-style factories, so generated parsers from 0.3.x will fail to compile against 0.4.0 runtime — regenerate.
- **`action` package factory rename.** `ActionCompiler.create()` / `ActionCompiler.create(ClassLoader)` → `ActionCompiler.actionCompiler(...)`; `SemanticValues.of(...)` → `SemanticValues.semanticValues(...)`.
- **`parser` package factory rename.** `ParserConfig.of(...)` → `ParserConfig.parserConfig(...)`; `ParseResult.Success.of(...)` → `ParseResult.Success.success(...)`; `ParseResult.Failure.at(...)` → `ParseResult.Failure.failure(...)`; `ParseResult.CutFailure.at(...)` → `ParseResult.CutFailure.cutFailure(...)`. `PegEngine.create(...)` left as-is (domain-named per spec).
- **`grammar` package — test assertion idiom.** `GrammarParserTest` rewritten to use `.onFailure(cause -> fail(cause.message())).onSuccess(grammar -> ...)` in place of `assertTrue(result.isSuccess()); var grammar = result.unwrap();` for happy-path assertions. Failure-path tests (`isFailure()` checks) unchanged.
- **`generator` package factory rename.** `ParserGenerator.create(...)` (4 overloads) → `ParserGenerator.parserGenerator(...)`. Test assertion idiom: `ParserGeneratorTest` rewritten to use `result.onFailure(cause -> fail(cause.message())).unwrap()` in place of `assertTrue(...isSuccess()); var x = result.unwrap();`.
- **`peglib-incremental` package factory rename.** `CstHash.of(...)` → `CstHash.cstHash(...)` (both the incremental `internal/CstHash` and the perf-test reflective `CstHash`); `SessionFactory.create(...)` (2 overloads) → `SessionFactory.sessionFactory(...)`. Public `IncrementalParser.create(...)` retained — it's the user-facing facade and not on the rename list.
- **`peglib-incremental` `SessionImpl` → `IncrementalSession` record.** The package-private `SessionImpl` class implementing `Session` is replaced by a `record IncrementalSession(SessionFactory, String, CstNode, int, CstNode, NodeIndex, Stats) implements Session`. Removes the `Impl` anti-pattern; `Session` remains a public interface (the record's seven components are internal state and don't belong in a public type signature). The dead helper `SessionImpl#debugPathTo(...)` (no callers) was deleted in the same change. Internal-only — no consumer-visible source change.
- **`peglib-playground` package factory rename.** `TraceRecord.of(...)` → `TraceRecord.traceRecord(...)`.
- **`peglib-maven-plugin` Mojo `execute()` methods refactored to `Result` pipelines.** `GenerateMojo`, `LintMojo`, `CheckMojo` now compose failure-prone steps (read grammar, parse `errorReporting`, parse grammar, build parser, smoke-parse) as a `Result` chain and translate the terminal `Result.failure(cause)` into `MojoFailureException(cause.message())` at the Maven boundary. Behavior preserved (same successes, same surfaced messages); a few read-failure paths that previously threw `MojoExecutionException` now consistently throw `MojoFailureException` to keep the boundary single-typed. `@Contract` annotation from JBCT convention is documented in Javadoc on each `execute()` method (no annotation type exists in the project).
- **`peglib-formatter` `Formatter` mutable builder replaced by immutable `FormatterConfig` record + immutable builder.** The previous API used `new Formatter().defaultIndent(...).maxLineWidth(...).triviaPolicy(...).rule(name, fn)` returning the same instance with mutated state. Replaced by `FormatterConfig.builder()` (also exposed as `Formatter.builder()`), where each `defaultIndent` / `maxLineWidth` / `triviaPolicy` / `rule` call returns a *new* `FormatterConfig.Builder`; terminal `.build()` materialises an immutable `FormatterConfig` record. A `Formatter` is then derived from the config via `Formatter.formatter(config)`, and is itself immutable and thread-safe to use concurrently. The public `new Formatter()` constructor is removed. `FormatterConfig` exposes `defaultConfig()`, `builder()`, and `toBuilder()` factory entry points.
- **`PegEngine.createWithoutActions(...)` returns `Result<PegEngine>`.** Previously asymmetric with `PegEngine.create(...)` (threw `IllegalArgumentException` on config validation failure). Now routes through `validateConfig` and surfaces failures as `Result<PegEngine>` so callers compose uniformly. `PegParser.fromGrammarWithoutActions(...)` updated to thread the result. JBCT Phase 6.
- **Action dispatch wrapped in `Result.lift` (`PegEngine#dispatchAction`).** Replaces the previous `try/catch (Exception)` around `action.apply(sv)` in `parseRuleWithActions` with a `Result.lift(t -> new ParseError.ActionError(...), () -> action.apply(sv))` adapter boundary. Action exceptions are still projected into `ParseResult.Failure` with the same `"action error: " + getMessage()` wording; only the JBCT pattern changed. JBCT Phase 6.
- **Playground `parseRequestBody` returns `Result<ParseRequest>`.** The HTTP `/parse` adapter now uses `Result.lift(BadRequest::new, () -> JsonDecoder.decodeObject(body))` to capture the JSON-decoder's `IllegalArgumentException`, with the missing-grammar validation step propagating through `Result.flatMap`. The handler still emits HTTP 400 with the same `{"error":"bad request","detail":...}` payload on any decode/validation failure; only the internal control-flow shape changed. New package-private `BadRequest(String message) implements Cause` carries the message. JBCT Phase 6.
- **`IncrementalSession#tryIncrementalReparse` returns `Option<IncrementalResult>`.** Replaces the previous nullable-`IncrementalResult` return with `Option`. The outward-walk loop now threads `Option<CstNode>` from `index.parentOf(...)` directly instead of `.or((CstNode) null)`, eliminating the three sites of the `(CstNode) null` cast workaround and the related TODO(P3) breadcrumbs. The success branch is extracted to `applyIncremental(...)` and the splice-build into `buildIncrementalResult(...)`. `findBoundaryCandidate(...)` rewritten to thread the same `Option`-walk pattern; root-fallback semantics unchanged. JBCT Phase 7.

### Documentation

- **JBCT-boundary Javadoc on CLI `main(String[])` entry points.** Added boundary documentation comments to `AnalyzerMain.main`, `PlaygroundRepl.main`, `PlaygroundServer.main`, and the dev-only `PackratStatsProbe.main`. Same pattern as Mojo `execute()` (Phase 4): `@Contract` is not a project annotation, so the boundary description lives in Javadoc. JBCT Phase 8.

### Fixed

- _<TBD>_

### Migration guide

- **`Grammar.validate()` callers** — drop the `.flatMap(Grammar::validate)` (or `.validate()`) step. `GrammarParser.parse(text)` already returns a validated `Result<Grammar>` for grammars without `%import` directives; for grammars with imports, `GrammarResolver.resolve(...)` runs validation as part of composition.
- **Direct `new Grammar(...)` callers** — switch to `Grammar.grammar(...).fold(failure -> handleError(failure), grammar -> useIt(grammar))`. The canonical record constructor is still public (Java records require canonical-ctor visibility ≥ class visibility) but should be treated as internal.
- **`SessionFactory.create(Grammar, ParserConfig, boolean)` (incremental)** — now expects an already-validated `Grammar`. Validation failures from `PegEngine.create(...)` are still surfaced as `IllegalArgumentException`, preserving the construction-time contract.
- **`tree` factories** — replace `SourceLocation.at(...)` with `SourceLocation.sourceLocation(...)`, `SourceSpan.of(...)` and `SourceSpan.at(...)` with `SourceSpan.sourceSpan(...)`. Mechanical rename. Generated parsers must be regenerated.
- **`action` factories** — replace `ActionCompiler.create(...)` with `ActionCompiler.actionCompiler(...)` and `SemanticValues.of(...)` with `SemanticValues.semanticValues(...)`. Mechanical rename.
- **`parser` factories** — replace `ParserConfig.of(...)` with `ParserConfig.parserConfig(...)`; `ParseResult.Success.of(...)` with `ParseResult.Success.success(...)`; `ParseResult.Failure.at(...)` with `ParseResult.Failure.failure(...)`; `ParseResult.CutFailure.at(...)` with `ParseResult.CutFailure.cutFailure(...)`. Mechanical rename.
- **`generator` factories** — replace `ParserGenerator.create(...)` with `ParserGenerator.parserGenerator(...)` (all four overloads). Mechanical rename.
- **`incremental` factories** — replace `CstHash.of(...)` with `CstHash.cstHash(...)` and `SessionFactory.create(...)` with `SessionFactory.sessionFactory(...)`. `IncrementalParser.create(...)` remains.
- **`incremental` `SessionImpl` rename** — no migration needed. `SessionImpl` was package-private; the new `IncrementalSession` record is also package-private and implements the same public `Session` interface. Consumers continue to use `Session`; there is no source-visible change.
- **`playground` factories** — replace `TraceRecord.of(...)` with `TraceRecord.traceRecord(...)`.
- **`Formatter` builder** — replace
  ```java
  var formatter = new Formatter()
      .defaultIndent(2)
      .maxLineWidth(80)
      .triviaPolicy(TriviaPolicy.DROP_ALL)
      .rule("Block", (ctx, kids) -> ...);
  ```
  with
  ```java
  var config = Formatter.builder()
      .defaultIndent(2)
      .maxLineWidth(80)
      .triviaPolicy(TriviaPolicy.DROP_ALL)
      .rule("Block", (ctx, kids) -> ...)
      .build();
  var formatter = Formatter.formatter(config);
  ```
  Each `with*`-style mutator on the builder returns a new builder (no shared mutable state); the resulting `FormatterConfig` is an immutable record; the `Formatter` is constructed once from the config and is thread-safe to share. The `Formatter#format(CstNode)` / `format(CstNode, String)` API is unchanged.

## [0.3.6] - 2026-05-01

Generator-side `%recover` per-rule overrides. Non-breaking.

### Fixed

- **Generator: `%recover` per-rule overrides now wired.** Mirrors the interpreter's `pendingFailureRecoveryOverride` mechanism (shipped in 0.3.5). Generator now emits override capture before the rule's `finally` pop so `parseWithRecovery` consults the deepest override after the stack unwinds.

### Known limitations

- **Incremental parser `singleCharEdit` perf** is still ~325 ms/op on the 1,900-LOC fixture. `docs/archive/V2.5-SPIKE.md` documents the diagnosis (the dominant cost is pivot overshoot in `findBoundaryCandidate`, not cache invalidation as v2.5 assumed) and a proposed "lever 1" fix (edit-anchored pivot selection). A naive lever-1 swap was attempted in 0.3.6 development but produced correctness regressions on `IncrementalParityTest` due to subtle interaction with `NodeIndex.contains`'s inclusive-boundary semantics — the smallestContaining lookup can return a node ending exactly at `editStart`, yielding a different pivot than the warm-pointer walk would. A correct fix needs careful boundary semantics work; deferred.

## [0.3.5] - 2026-05-01

Trivia attribution correctness — full byte-equal round-trip on all 22 corpus fixtures — plus `%recover` directive wiring. `RoundTripTest` re-enabled.

### Fixed

- **Bug A — pending-trivia restore on backtrack.** `ParsingContext.savePendingLeadingTrivia()` returned a size-only snapshot and `restorePendingLeadingTrivia(int)` only truncated. Items consumed inside a backtracked branch were permanently lost. Now snapshots/restores the full `List<Trivia>` contents. `PegEngine` call sites and `ParserGenerator` emission templates updated symmetrically.
- **Bug B — cache-hit leading trivia rebuild.** Packrat cache hits returned the cached body result directly without applying any leading-trivia attribution, while cache misses applied `ruleLeading` via `wrapWithRuleName`. The asymmetry produced wrong leading attribution on cache hits. Fixed by rebuilding leading trivia (drain pending + `skipWhitespace` + reattach) on every settled-success cache hit in `parseRule` and `parseRuleWithLeftRecursion`. Generator emits the same logic. Growing-seed hits (left-recursion self-reference) deliberately unchanged.
- **Bug C — generator caches wrapped-with-leading body.** Generator's cache stored the rule's wrapped node *with* leading trivia applied. On subsequent cache hits, `attachLeadingTrivia` short-circuits when current pending is empty, preserving the stale cached leading. Same trivia ended up on multiple wrapper nodes (duplication). Fix: cache an empty-leading wrap, return the actual-leading wrap. Cache hits now apply current pending without inheriting stale state. Interpreter was already correct (caches the body, not the wrap).
- **Bug C' — rule-exit trailing-trivia attribution.** When a rule body's final element is zero-width (empty ZoM/Optional), the inter-element `appendPending(skipWhitespace())` deposit is left in `pendingLeadingTrivia` with no child to claim it. Pending trivia at rule exit is now attached to the last child's `trailingTrivia` (or to the rule node's trailing if it has no children). Pos is *not* rewound — predicate combinators rely on it being past consumed whitespace. Applied symmetrically in `PegEngine` and `ParserGenerator`.
- **Bug C'' — generator Sequence children rollback on element failure.** Generator emitted Sequences using the rule-method's outer `children` list directly. On element failure, the Sequence restored location and pending but NOT children — so partial child additions from earlier elements of the failed Sequence stayed in the parent's children list. Symptom: a successful trailing comma appeared as a child of both the inner ZoM-NT and the outer Sequence (e.g. enum-constant lists). Fix: snapshot `children` at Sequence start; restore on element failure. Interpreter uses a local `children` list per Sequence call so was already correct.
- **`%recover` directive now wired end-to-end (interpreter).** Root cause: the rule-level recovery override was pushed at body entry and popped in the `finally` block — including on failure paths. By the time `parseWithRecovery` (the top-level recovery loop) consulted the override, the stack had been unwound and `skipToRecoveryPoint` fell through to the global default char-set. Fix: capture the failed rule's override into a per-context `pendingFailureRecoveryOverride` field BEFORE the pop, with deepest-wins semantics; consume-and-clear inside `skipToRecoveryPoint`; clear on rule success to prevent backtracked-alternative leakage. New regression test `RecoverDirectiveProofTest` proves override and default produce DISTINCT recovery landing points (uses `:` as override terminator — outside the default char-set — so the discriminator is unambiguous).
- **Note: generated parser `%recover` per-rule overrides remain unthreaded.** `ParserGenerator` only emits the start rule's recovery statically. Per-rule overrides in generated parsers are still not honored. Documented as a known limitation; targeted for 0.3.6.

### Changed

- **CST hash baseline regenerated for `large/FactoryClassGenerator.java.txt`.** The Bug C'' children-rollback fix removes a duplicate trailing comma child in enum-constant lists; that fixture's CST shape legitimately changes. Other 21 fixtures' baselines are unchanged. Committed as a separate baseline-shift commit alongside the Bug C'' fix. Anyone diffing 0.3.4 baselines against 0.3.5 will see the single-fixture shift; this is expected.
- **`RoundTripTest` re-enabled.** All 22 corpus fixtures round-trip byte-equal via the generated parser. The `@Disabled` annotation and pointer comment in the test are removed.
- `docs/TRIVIA-ATTRIBUTION.md` — updated to document the full Bug A/B/C/C'/C'' resolution.
- `docs/RELEASE-PLAN-0.3.5-0.4.0.md` — Phase 1 marked complete; Bug C originally deferred to 0.3.6 was solved in 0.3.5 along with Bug C'/C''. (File deleted in 0.5.0-candidate cleanup; recover from git history if needed.)

### Known limitations

- **Generated-parser `%recover` per-rule overrides.** Interpreter `%recover` is now correct (this release). Generator only emits start-rule recovery statically; non-start rules' overrides are not threaded into emitted parsers. Targeted for 0.3.6.

## [0.3.4] - 2026-04-22

Post-roadmap cleanup release. Two rounds of parallel JBCT review (10 focus-area reviewers + docs-backreference + test-coverage audit, run twice) identified priorities. This release lands the P0 (correctness + security) and P1 (highest-leverage mechanical) fixes; architectural refactors remain tracked as P3 items in `docs/AUDIT-REPORTS/CONSOLIDATED-BACKLOG.md`.

### Fixed

- **Thread-safety data race.** `PegEngine`'s per-engine literal / char-class failure-message caches now use `ConcurrentHashMap`. Previous `HashMap` was unsafe under concurrent access through a shared `Parser` instance — contradicted `IncrementalParser`'s documented thread-safety contract, which is now honored in practice. Upgraded to `computeIfAbsent` in round 2 for atomic populate-on-miss.
- **Playground server hardening.**
  - Path traversal: static-file handler rejects `..` segments, control chars, backslashes, and anything outside a strict allowlist before resolving classpath resources.
  - Request-body size cap: `POST /parse` reads at most 1 MiB; larger requests get HTTP 413 before parsing begins.
  - Security headers on every response: `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`, `Referrer-Policy: no-referrer`, `Cache-Control: no-store`.
  - Locale-safe `toUpperCase(Locale.ROOT)` replaces locale-sensitive uppercasing in `PlaygroundServer` and `PlaygroundRepl` (prevents the Turkish-i bug on `RecoveryStrategy.valueOf`).

### Changed

- `Actions#get(String)` and `Actions#get(Class<? extends RuleId>)` now return `Option<Action>` / `Option<Function<SemanticValues, Object>>` instead of raw nullable types. Callers use `.onPresent(...)` or `.or(...)`. **This is a minor API break**; consumer sites in `PegEngine.mergeActions` and the project's own tests migrated; external consumers need a one-line update.
- `NodeIndex#smallestContaining(int)`, `NodeIndex#smallestContainingFrom(CstNode, int)`, and `NodeIndex#parentOf(CstNode)` now return `Option<CstNode>` — null sentinels removed from the incremental-parser's navigation surface.
- `SessionImpl#reparseAt(...)` and related helpers propagate `Option<CstNode>` end-to-end; the `fold(cause -> null, ...)` anti-pattern that silently swallowed failure is gone.
- `PegEngine.whitespaceFirstChars` and the underlying `FirstCharAnalysis.whitespaceFirstChars(...)` now use Pragmatica `Option` rather than `java.util.Optional`.
- `Result.failure(cause)` → `cause.result()` mechanical rewrite across `peglib-core`, `peglib-incremental`, `peglib-formatter`, `peglib-maven-plugin`, `peglib-playground` main sources (~50 sites). String-literal templates inside `ParserGenerator` that emit generated-parser code remain untouched.
- `PlaygroundEngine.parseWithRecovery` no longer uses the `Option.or((String) null)` anti-pattern — rewritten via `Option.filter(...).fold(...)`.
- `ParsingContext.getCachedAt` replaces hand-rolled null-check with `Option.option(...).map(CacheEntry::result)`.
- Defensive null-checks removed from `JsonEncoder.writeNode` / `writeString` and `ParseTracer.visit` / `countTrivia` / `countNodes` — sealed `CstNode` hierarchy and by-construction non-null children make them dead code.

### Added

- `docs/AUDIT-REPORTS/` directory with four artefacts from the review rounds:
  - `docs-backreference.md` — per-release feature matrix (impl / docs / wiring ✓/✗/partial).
  - `docs-fixups-needed.md` — CHANGELOG drift fix list (resolved in this release).
  - `test-coverage-proof.md` — assertion-strength audit (proven / partial / smoke / missing).
  - `tests-fixups-needed.md` — test gaps identified.
  - `CONSOLIDATED-BACKLOG.md` — P0/P1/P2/P3-DEFERRED findings across 10 JBCT focus areas.
- New tests in `LeftRecursionTest`: asserts left-leaning CST shape on `1+2+3` — a right-recursive workaround would fail this assertion, proving the feature is real.
- New smoke test in `RuleRecoveryTest` for `%recover` directive with a documented follow-up (current implementation produces identical diagnostics on the tested input; full proof test needs deeper %recover wiring audit — tracked as P3).

### Fixed (CHANGELOG drift)

- CHANGELOG entries for 0.2.7, 0.3.2, 0.3.3 contained inaccurate usage snippets (mentioned non-existent `repl` / `server` sub-commands, `IncrementalParser.builder(...)` method, missing `HardLine` / `Docs.hardline()` from the `Doc` enumeration). Corrected to match actual API surface.

### Deferred (P3-DEFERRED — see `docs/AUDIT-REPORTS/CONSOLIDATED-BACKLOG.md`)

Architectural refactors surfaced by the audit but intentionally not shipped in 0.3.4:

- Parse-don't-validate refactor: collapse `Grammar#validate()` into a factory returning `Result<Grammar>`.
- Factory naming: `of()` / `create()` / `at()` → `typeName()` across ~20 public API sites.
- `SessionImpl` → record rename away from `*Impl` anti-pattern.
- Mojo `execute()` decomposition to Result pipelines + `@Contract` boundaries.
- `Formatter` mutable builder → immutable `FormatterConfig` + builder.
- Mass test-idiom rewrite: `assertTrue(result.isSuccess())` → `.onFailure(Assertions::fail).onSuccess(...)` (~1000 sites).
- `@Contract` annotations on all CLI `main` / Maven Mojo boundary methods.
- `parseRequestBody` / HttpHandler IOException propagation → `Result.lift` boundary.
- `PegEngine.createWithoutActions` throw → `Result<PegEngine>` symmetry with `create(...)`.
- Action-dispatch try/catch boundary → `Result.lift` with typed `Cause`.
- Trivia round-trip completion (rule-exit pos-rewind + baseline regen) — still the primary outstanding feature from the 0.2.4 deferral.

### Tests

- Aggregate: **874 passing**, 1 skipped (pre-existing `RoundTripTest`), 0 failures, 0 errors.
- `peglib-core`: 676 (+2 new). `peglib-incremental`: 100. `peglib-formatter`: 66. `peglib-maven-plugin`: 5. `peglib-playground`: 27 (+5 adversarial smoke tests).

### Notes

- This release is mostly internal cleanup + minor API tightening on two low-traffic methods (`Actions#get`, `NodeIndex#parentOf` / `smallestContaining`). The primary public API surface (`PegParser`, `ParserConfig`, `Grammar`, `CstNode`, `IncrementalParser.initialize` / `edit`) is unchanged.
- P3-deferred items are now fully enumerated in-repo, sized, and ready for a future maintenance arc.

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

- **`peglib-incremental` module — v1 implementation** per `docs/archive/SPEC-incremental-original.md`. Cursor-anchored stateful parser that reparses only the subtree affected by an edit, falling back to full reparse when needed. Designed for editor-scale workflows (formatters on save, live diagnostics, LSP backends).
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

- `Parser#parseRuleAt(Class<? extends RuleId> ruleId, String input, int offset)` — partial-parse entry point. Parses a specific rule against input starting at the given offset; returns `Result<PartialParse>` wrapping the resulting CST subtree and its end offset. Implemented by `PegEngine` (interpreter) and by generated parsers via an identity map keyed on the `RuleId` marker classes the generator has been emitting since 0.2.6. This is the API `peglib-incremental` (0.3.1) depends on per `docs/archive/SPEC-incremental-original.md` §5.6.
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
- Phase-2 flag defaults (set per measured win per `docs/archive/PERF-REWORK-SPEC.md` §12.6):
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
