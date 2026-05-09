# peglib — Handover (post-0.5.1 ship, starting 0.6.0)

**Last updated:** 2026-05-10, immediately after 0.5.1 ship + 0.6.0 spec lock + branch creation.

This handover is the entry point for the next session. It is self-contained: read this, then `docs/ARCHITECTURE-0.6.0.md`, and you can start Phase A.

---

## 0. State at a glance

| | |
|---|---|
| **Latest release** | 0.5.1 (live on Maven Central — `org.pragmatica-lite:peglib:0.5.1` and 4 sibling modules) |
| **Latest tag** | `v0.5.1` at SHA `1898409` (annotated) |
| **`main` HEAD** | `6929c73` — last commit is the 0.6.0 architecture spec |
| **Active branch** | `release-0.6.0` at `2fe3c76` (NOT pushed; pom versions bumped, CHANGELOG entry added, ready for Phase A) |
| **0.6.0 spec** | `docs/ARCHITECTURE-0.6.0.md` (846 lines, 9 locked decisions, 6-week phasing) |
| **Working tree** | clean |
| **Test counts at ship** | peglib-core 805 + 1 skip; full reactor 1028 + 1 skip; all green |

---

## 1. What 0.5.1 shipped (briefly)

Cumulative across the post-Move-B + trivia-rework + StringSpan + Cleanup A-G arcs:

- **Trivia rework:** `triviaPostPass=true` is the new default. Context-independent attribution by post-pass. Long-standing trivia bugs (5 historical + Step 4 era) closed.
- **StringSpan:** new public type `org.pragmatica.peg.tree.StringSpan` for lazy substring materialization. CstNode.Terminal/Token internals migrated to `StringSpan textSpan`; `.text(): String` accessor preserved via lazy materialization.
- **Perf:** selfhost (37k LOC) -5% under legacy buffer-driven path (the perf-critical workload is faster). Reference (1900 LOC) +30% over legacy (intrinsic post-pass overhead; bounded; no real workload affected).
- **Lever B for incremental engine:** trivia-context-loss blocker resolved. Fallback-rule-bypass blocker remains separately scoped.

Full post-mortem of every arc is in git history. Notable docs:
- `docs/incremental/THROUGHPUT-ENGINE-MOVE-B.md` §11 — Move B failure post-mortem (lessons about JIT escape analysis vs allocation-rate metrics)
- `docs/incremental/TRIVIA-ADVERSARIAL-FINDINGS.md` — adversarial test corpus
- `CHANGELOG.md` [0.5.1] — release notes

---

## 2. What 0.6.0 is — 30-second read

**Clean-slate redesign of peglib for CST-only, lint+format use cases. Breaking changes acceptable.**

Nine locked decisions from the spec discussion (all confirmed by user):

1. **Drop the interpreter** (`PegEngine`). Generator-only. `PegParser.fromGrammar(g).parse(input)` does generate-compile-cache under the hood.
2. **Two-phase: lex → parse.** PEG grammar surface preserved; backend uses analysis-driven lex-then-parse. Per-rule char-level fallback for edge cases.
3. **Drop runtime actions.** Generate a `Visitor<T>` stub per grammar; users implement selectively for CST → domain transforms.
4. **Drop AST type.** CST is the only tree. Wrapper-collapse becomes user code via Visitor.
5. **Pure flat node array.** CST data lives in `int[]`; views over the array replace records in the data path. ~32 bytes/node vs ~80-200 today.
6. **Trivia as tokens.** Whitespace/comments live in the token array with `kind = WHITESPACE / LINE_COMMENT / BLOCK_COMMENT`. Trivia attribution problem dissolves.
7. **Incremental as a thin caching layer.** Checkpoint boundaries via grammar `%checkpoint` directive; auto-detect default + explicit override.
8. **Error recovery: one always-on mechanism.** Panic-mode synchronization to `%recover` sets. `List<Diagnostic>` always present (empty = success).
9. **The grammar IS the configuration.** `ParserConfig` deleted. One runtime parameter (`maxDiagnostics`).

**Estimated outcomes (per spec):**
- Code: ~40-50% LOC reduction across peglib-core + ancillary modules
- Performance: reference ≤10ms (vs 24.88ms in 0.5.1), selfhost ≤250ms (vs 784.7ms) — parity-with-or-faster-than javac on Java parsing
- Bug surface: 5 historical trivia bugs become impossible by construction

**Full spec:** `docs/ARCHITECTURE-0.6.0.md` (846 lines). Sections most useful for implementers: §3 (decisions in detail), §4 (module structure), §5 (concrete API signatures), §7 (phasing).

---

## 3. Where to start: Phase A

Per spec §7, Phase A is the lexer foundation. ~1 week. Critical-path: every subsequent phase depends on TokenArray being correct.

### 3.1 Phase A scope

**In scope:**
- Augment `Grammar` IR with rule classification (lexer-rule vs parser-rule)
- Implement DFA construction from lexer-rules
- Implement `TokenArray` data structure
- Generate `GLexer.java` per grammar
- Lex Java25 corpus into TokenArray byte-equal to a hand-written reference

**Not in scope (Phase A):**
- Anything in the parser path (Phase B)
- The user-facing API (Phase C)
- Incremental (Phase D)
- Visitor stubs (Phase E)

### 3.2 First concrete actions

1. **Read the spec sections that matter for Phase A:**
   - §3.2 (lex-then-parse design + classification rules)
   - §3.6 (trivia-as-tokens — what the lexer must emit)
   - §5 (concrete TokenArray API signatures)
   - §7 (phasing; understand the gate condition)

2. **Inspect existing related code in peglib-core:**
   - `org.pragmatica.peg.generator.ChoiceDispatchAnalyzer` — existing FIRST-set analyzer; precedent for grammar-level analysis
   - `org.pragmatica.peg.generator.PackratAnalyzer` — another existing analyzer
   - The DFA fast-path code in `ParserGenerator` (added in 0.5.0; specifically the `tokenFastPath` flag emission) — small DFA precedent for token-shaped rules
   - `Grammar` IR in `org.pragmatica.peg.grammar.*`

3. **Design the rule classifier.** Walk every rule's expression tree; classify per spec §3.2 criteria. Output a `Map<String, RuleKind>` where `RuleKind = LEXER | PARSER | MIXED`. MIXED rules emit a compile-time warning.

4. **Design the DFA construction.** Take all LEXER-classified rules + their expressions; build a single DFA that recognizes any of them, with longest-match + first-match-wins for tie-breaking. Standard NFA → DFA construction (Thompson + subset). Output: a state-transition table representable as `int[][] transitions` plus `int[] acceptStates`.

5. **Design the TokenArray.** Per spec §5, packed `int[] starts`, `int[] ends`, `byte[] kinds`. Add a `kindNameTable: String[]` for diagnostics. Implement `nextNonTrivia(int from)`, `kindAt`, `startAt`, `endAt`, `textAt`, `isTrivia`.

6. **Generate `GLexer.java`.** Emit a class with the DFA as static int[] tables, a `lex(String input)` method that walks the input, applies DFA transitions, emits tokens into a TokenArray. Self-contained per the standalone-parser invariant.

7. **Phase A gate validation:**
   - Pick a representative Java25 corpus fixture (say `peglib-core/src/test/resources/perf-corpus/Imports.java` — small, comment-heavy)
   - Lex it via the new GLexer
   - Manually verify the token sequence matches the input byte-by-byte under round-trip (`for each token, append input.substring(start, end) → equals input`)
   - Lex all 22 corpus fixtures; same round-trip check passes

### 3.3 Don't worry about (Phase A)

- Performance tuning the lexer. DFA tables are already cache-friendly; make it correct first
- The generator-and-compile-cache pipeline (Phase C)
- How `PegParser.fromGrammar()` will look (Phase C)
- AST removal, action removal (separate cleanups, Phase F)
- Migrating existing tests (the 0.5.x test suite stays intact during 0.6.0 dev; new tests are written for the new code path; we don't delete the old code paths until late in the cycle)

### 3.4 Suggested approach: parallel branch

The 0.6.0 implementation is a clean-slate rebuild that doesn't need to live in `peglib-core` at first. Suggestion:

- Create a NEW package `org.pragmatica.peg.v6.*` (or similar) inside `peglib-core` for the 0.6.0 code
- Existing 0.5.x packages (`org.pragmatica.peg.parser.*`, `org.pragmatica.peg.action.*`, etc.) stay UNTOUCHED until late in the cycle
- This means tests of both old and new can coexist; bench can compare directly; rollback is trivial if something goes wrong
- Late-cycle (Phase F): delete old packages once 0.6.0 is fully validated

This is the same pattern that worked for the trivia-rework arc: dormant infrastructure → wire-in → validate → cleanup. Don't break working code while building new code.

---

## 4. Critical lessons banked across 0.5.x arcs (do not re-learn)

These are session-tested findings that the 0.6.0 implementer should internalize before writing code.

### 4.1 Allocation rate is NOT a perf target

**Move B post-mortem (HANDOVER history):** replacing per-call `CstParseResult` allocations with a singleton mutator regressed wallclock 11% while alloc-rate dropped 13%. Modern JIT escape analysis already scalar-replaces short-lived per-call records; replacing them with heap-bound state DEFEATS that optimization.

**Apply for 0.6.0:** target wallclock, not alloc-rate. Bench A/B every change. The flat-array CST (Idea 5) is correct because it eliminates allocations the JIT CANNOT scalar-replace (the trees-of-records survive past method scopes).

### 4.2 Successful pattern vs failed pattern

**Empirical pattern from 0.5.x optimization arcs:**

| Pattern | Result |
|---|---|
| Replace `List.copyOf` (varargs / array copy) with primitive int snapshot | WIN |
| Replace per-call `String.valueOf(c)` with interned ASCII pool | WIN |
| Replace bulk substring with StringSpan view | WIN (selfhost) |
| Replace `String.contains` quadratic scan with `LinkedHashSet` dedup | RESET (call-overhead-dominated) |
| Replace `HashMap<Long,_>` with custom open-addressed long-keyed map | RESET (JDK HashMap is hot-path-faster) |
| Provide `HashMap` initial-capacity hint | RESET (over-sizing hurts cache locality) |
| Convert record → mutable class with cache field | RESET (records are EA-friendly; mutable classes aren't) |
| Defer record allocation via primitive int tracking | RESET when the record was already JIT-eliminable |

**Apply for 0.6.0:** when in doubt about whether an optimization will work, look at whether it eliminates allocations the JIT CANNOT elide. Bulk array copies, per-call fresh objects, large records that escape — these are real targets. Records consumed immediately within method scope, JDK collection internals, single-cache-field mutable classes — these the JIT already handles.

### 4.3 Bench gate every change

**Cleanup G arc lesson:** even with sound theoretical reasoning, a "this should win" hypothesis can be wrong. Cleanup G.1 (whitespace prefilter) and G.2 (alloc tightening) both passed code review and tests but failed bench gates with 0% / 0% improvement. The bench is the only honest signal.

**Apply for 0.6.0:** every commit that claims a perf improvement is bench-gated. Ship the win or revert; never ship "neutral" change with implementation cost. Phase gates in the spec follow this pattern (each phase has a gate condition).

### 4.4 Correctness tests are NOT perf gates

**Step 4 commit 6 → 7 lesson:** RoundTripTest passed 22/22 under flag-ON before bench revealed the post-pass had O(n²) wallclock complexity. Correctness tests checked output validity but had no time bound. The bench is the only honest signal for perf.

**Apply for 0.6.0:** every default-changing or hot-path change runs bench A/B before commit. Add wallclock assertions to perf-sensitive tests if needed (with generous bounds; goal is "this took milliseconds, not seconds").

### 4.5 Static analysis can be too conservative

**Lever B retry lesson:** `SafePivotAnalyzer.safePivotRules(Grammar)` uses a strict "unambiguous literal prefix" criterion. For Java25 grammar (where most rules start with character classes or rule references), the analyzer marks ~80% of rules unsafe. Walking up to find a safe ancestor lands at root → forces full reparse. Median 5ms → 21.9ms regression.

**Apply for 0.6.0:** when designing the `%checkpoint` auto-detector (per Idea 7), test against the Java25 grammar EARLY. If the criterion is too restrictive, adjust before committing the implementation. Validate with `IncrementalSessionBench` not just parity tests.

### 4.6 The post-pass approach was a partial answer to the wrong question

**Trivia rework reflection:** the entire 0.5.1 trivia rework arc (Step 4 commits 1-7 + Cleanup A-G) was solving a problem that simply doesn't exist under tokens-first design. Trivia tokens are at known positions in the token array; the attribution problem dissolves. ~3500 LOC of 0.5.1 code becomes deletable.

**Apply for 0.6.0:** when designing the lexer (Phase A), make trivia-as-tokens the foundation. Don't accidentally re-introduce buffer state for trivia attribution.

---

## 5. Things already tried and decided — don't relitigate

### 5.1 Singleton mutable parse-state (Move B)

Attempted across 5 commits in 0.5.0 cycle. Definitively reverted. Hard-coded into HANDOVER history as the canonical "JIT escape analysis already handles this" example. Don't bring it back as a 0.6.0 optimization.

### 5.2 SafePivotAnalyzer literal-prefix gate

Attempted in this session. Catastrophic regression on Java25. Don't wire SafePivotAnalyzer into 0.6.0 incremental as-is. Either redesign the criterion (tracking grammar first-sets more cleverly) or use the simpler `%checkpoint` directive approach (the 0.6.0 plan).

### 5.3 Pattern-matching ergonomics on records

User explicitly preferred performance over pattern-matching ergonomics. CstNode views (Idea 5 option A) are the path. Don't try to keep records "for ergonomics" — query-style API is the design choice.

### 5.4 BASIC vs ADVANCED error recovery

Collapsed to one mechanism (always-on, panic-mode). Don't reintroduce the split.

### 5.5 Action support

Dropped (Idea 3). Visitor pattern (option C.2 from spec discussion) replaces it. Don't reintroduce inline `{ ... }` action blocks; let the grammar parser reject them with a migration message.

### 5.6 AST type

Dropped (Idea 4). User builds their own AST via Visitor. Don't add an "AST" output mode to the 0.6.0 generator.

---

## 6. Open questions for the implementer

Things that COULD be resolved differently during implementation but are bounded:

### 6.1 Visitor stub: pre-order or post-order?

Spec §3.3 doesn't pin which traversal order the default `visitChildren()` uses. ANTLR is post-order by default. Roslyn is pre-order with explicit `Visit` calls. For most lints, doesn't matter. Recommendation: pre-order (visitor sees node before its children), since linters often want to terminate early on certain nodes.

### 6.2 CharSequence vs String for token text

Spec §5 has `CharSequence textAt(int i)`. Most consumers will call `.toString()`. The CharSequence return type allows lazy materialization but adds API friction. Decide based on benchmarks; if 95% of consumers `.toString()` immediately, just return `String`.

### 6.3 Lexer DFA representation: arrays vs switch

DFAs can be represented as `int[][] transitions` (data-driven) or as a giant `switch` statement in generated code (code-driven). For Java25 grammar (~50 token kinds, ~200 DFA states), data-driven is more compact and easier to verify. For tiny grammars (~5 token kinds), switch may JIT better. Recommendation: data-driven default; profile and switch if it's a hot frame.

### 6.4 ParseResult immutability

Spec defines `ParseResult` as a final class with public final fields. Could be a record. Records work for value-style access but lose extensibility. For 0.6.0, records are fine — the API is locked.

### 6.5 Generator output: one file or three?

Spec §3.2 implies three files (GLexer, GParser, GVisitor). Could combine into one for the standalone-parser invariant. Recommendation: three files for IDE friendliness, all in same package; the maven-plugin picks them all up; users importing the parser get a clean package.

### 6.6 0.5.x deprecation timing

Spec §8 lists the breaking changes. Question: do we ship a `0.5.x → 0.6.0` migration tool? Recommendation: skip; the migration is mechanical (delete code, replace API calls). Users don't need automation; they need a clear migration guide. Write `docs/MIGRATION-0.5-TO-0.6.md` during Phase F.

---

## 7. Bench targets for 0.6.0

Reference machine: same Apple Silicon used for 0.5.x bench session. Numbers from spec §10:

| Workload | 0.5.1 | 0.6.0 target | Stretch |
|---|---:|---:|---:|
| Reference parse (1900 LOC) | 24.88 ms / 77 MB | **≤ 10 ms / ≤ 30 MB** | ≤ 6 ms |
| Selfhost parse (37k LOC) | 784.7 ms / 1881 MB | **≤ 250 ms / ≤ 600 MB** | ≤ 150 ms |
| Incremental edit median (Regime B) | 5.0 ms | **≤ 3 ms** | ≤ 1 ms |
| First-call (cold compile) | n/a | **≤ 600 ms** (one-time) | — |

For comparison: javac parses 1900-LOC Java in ~9 ms (parse-only). 0.6.0 reference target of ~10 ms = parity with javac while emitting strictly more output.

---

## 8. Repository structure pointers

Where things live (post-0.5.1):

```
peglib/
├── pom.xml                          (parent; version 0.6.0 on release-0.6.0 branch)
├── README.md
├── CHANGELOG.md                     ([0.6.0] entry exists; sections empty)
├── CLAUDE.md                        (project mandate; jbct-coder usage rule)
├── docs/
│   ├── ARCHITECTURE-0.6.0.md        ★ The spec. Read this first.
│   ├── HANDOVER.md                  (this file)
│   ├── BENCHMARKING.md
│   ├── ERROR_RECOVERY.md
│   ├── GRAMMAR-DSL.md
│   ├── PARTIAL-PARSE.md
│   ├── PERF-FLAGS.md                (mostly relevant for 0.5.x; will become stale in 0.6.0)
│   ├── PLAYGROUND.md
│   ├── PRETTY-PRINTING.md
│   ├── TRIVIA-ATTRIBUTION.md        (will become historical in 0.6.0)
│   ├── archive/                     (historical specs)
│   ├── bench-results/               (bench history)
│   └── incremental/
│       ├── ARCHITECTURE-0.5.0.md    (Lever D stable IDs; predecessor)
│       ├── PHASE-1-RESULTS.md
│       ├── THROUGHPUT-ENGINE-MOVE-B.md  (Move B post-mortem; canonical lessons)
│       ├── THROUGHPUT-ENGINE-TIER1.md
│       └── TRIVIA-ADVERSARIAL-FINDINGS.md
├── peglib-core/                     (core parser library; 0.5.1 implementation)
├── peglib-incremental/              (incremental engine; 0.5.x implementation)
├── peglib-formatter/                (Wadler-Lindig pretty printer)
├── peglib-maven-plugin/             (build-time codegen)
└── peglib-playground/               (REPL + HTTP UI)
```

For 0.6.0 work, the proposal in §3.4 is to add a NEW package `org.pragmatica.peg.v6.*` inside `peglib-core` rather than rewriting in place. This lets old and new coexist during the cycle.

---

## 9. Tooling

- **Project mandate (CLAUDE.md):** use `jbct-coder` agent for all coding; `build-runner` for `mvn` invocations
- **Build:** `mvn install` (full reactor; ~35-40s); `mvn test -pl peglib-core` for fast iteration
- **Bench (throughput):** `mvn -pl peglib-core -am -Pbench -DskipTests package` then `java -jar peglib-core/target/benchmarks.jar Java25ParseBenchmark.parse -p variant=... -p fixture=reference,selfhost ...`
- **Bench (incremental):** `mvn -pl peglib-incremental -am -Pbench -DskipTests package` then `java -cp ... org.pragmatica.peg.incremental.bench.IncrementalSessionBench` (note: this benchmark uses a custom main, not JMH directly)
- **Profile:** async-profiler at `/opt/homebrew/lib/libasyncProfiler.dylib`. JMH integration via `-prof async:libPath=...;event=cpu` or `event=alloc`.
- **Maven Central deploy:** `mvn clean deploy -P release -DperformRelease=true` from main on a tag commit. Takes ~4-5 min. GPG signing via gpg-agent.
- **GH workflow:** `gh pr create` / `gh pr merge --admin --merge` for release PRs
- **CI:** GitHub Actions; `build` job runs full `mvn install`; checks must pass before merge

---

## 10. Session-end checklist for the next session

When the next session ends, the next-next session needs the same kind of handover. Before ending:

- [ ] Update this `docs/HANDOVER.md` with current state + what's next
- [ ] Update `CHANGELOG.md` `[0.6.0]` section with entries for what shipped this session
- [ ] If a phase gate was passed: note the gate-met evidence in the phase-completion log
- [ ] If a decision was made that contradicts the spec: update `docs/ARCHITECTURE-0.6.0.md` with the new decision + rationale
- [ ] Verify working tree is clean OR has a clear in-progress note in HANDOVER
- [ ] Push to `origin/release-0.6.0` (or wherever active work lives)

---

## 11. Quick-reference: the 9 decisions from the 0.6.0 spec

For when you don't want to re-read the full 846-line spec:

1. Drop interpreter; generator-only with generate-and-compile (cached per grammar)
2. Two-phase lex → parse; PEG surface preserved; analysis-driven backend
3. Drop runtime actions; emit `Visitor<T>` stub per grammar
4. Drop AST type; CST is the only tree
5. Pure flat node array; views over int[]; no records in CST data path
6. Trivia as tokens (kind=WHITESPACE/COMMENT in token array); positional + helpers
7. Incremental as thin caching layer; auto-detect + `%checkpoint` directive
8. Error recovery: one always-on mechanism; panic-mode; List<Diagnostic> always present
9. The grammar IS the configuration; ParserConfig deleted; one runtime parameter (maxDiagnostics)

---

**Welcome to 0.6.0. Read the spec, then start Phase A. Good luck.**
