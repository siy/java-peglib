# Peglib — PEG Parser Library for Java

## Project Status

**0.6.3 is the latest shipped release** (Maven Central, 2026-06-07). Work in progress on
`release-0.7.0`, which is **breaking**: the 0.5.x interpreter path and the `peglib-incremental`
artifact are removed, and `org.pragmatica.peg.v6.*` has collapsed into `org.pragmatica.peg.*`.

See `docs/HANDOVER.md` for current state and next steps, `docs/ARCHITECTURE-0.6.0.md` for design.

## Agent Usage

**Preferred, not absolute.** Default to `jbct-coder` for multi-file or open-ended coding, `build-runner`
for `mvn`, `chore-runner` for git/changelog. The point is keeping noisy output out of the main context —
not delegation for its own sake.

Work inline instead when any of these hold:
- the user asks for it inline (this overrides everything below);
- the change is targeted and predictable — a few edits whose result you can verify by reading;
- delegating would cost more than the work itself (see the global "Delegation" rule on setup cost);
- the harness this session restricts spawning agents. A session-level instruction not to spawn agents
  outranks this file.

`mvn` inline is acceptable when the output is filtered to a summary, e.g.
`mvn install -Djbct.skip=true 2>&1 | grep -E "Tests run:|BUILD"`. Unfiltered reactor output is not.

## Architecture (0.6.0)

Nine decisions (per spec §3 — all implemented or documented):

1. Drop the interpreter. Generator-only with generate-and-compile-and-cache.
2. Two-phase lex → parse. PEG surface preserved; backend tokens-first.
3. Drop runtime actions. Generate `GVisitor<T>` stub per grammar.
4. Drop AST type. CST is the only tree.
5. Flat int[] CST (32 bytes/node).
6. Trivia as tokens.
7. Incremental engine as thin caching layer.
8. Error recovery: one always-on mechanism.
9. The grammar IS the configuration. `ParserConfig` deleted.

## Module Layout

```
peglib/
├── peglib-runtime/         25KB; generated parsers depend ONLY on this + pragmatica-lite:core
├── peglib-core/            grammar parser, codegen, analyzers, implementation, IncrementalParser
├── peglib-formatter/       Wadler-Lindig pretty printer on flat CST
├── peglib-maven-plugin/    build-time codegen mojo
└── peglib-playground/      REPL + HTTP UI
```

## Source Files (peglib-core/peglib-runtime)

```
peglib-runtime/src/main/java/org/pragmatica/peg/
├── token/
│   ├── TokenArray.java              flat int[] tokens; spliceLex for incremental
│   ├── TokenArrayBuilder.java
│   └── LexFn.java                   functional lexer adapter
├── cst/
│   ├── CstArray.java                flat int[]; findCheckpointAncestor; spliceSubtree
│   ├── CstArrayBuilder.java         links on endNode (success), not beginNode; truncate pops a link journal
│   ├── CstNode.java                 sealed Branch/Leaf/Error views
│   └── ParseResult.java
└── diagnostic/
    ├── Severity.java
    └── Diagnostic.java              Rust-style format

peglib-core/src/main/java/org/pragmatica/peg/
├── PegParser.java                   entry: fromGrammar(text) → Result<Parser>
├── Parser.java                      facade: parse(input) → ParseResult
├── lexer/
│   ├── RuleClassifier.java          LEXER/PARSER/MIXED + skip-prefix detection
│   ├── Dfa.java                     non-ASCII transition slot
│   ├── DfaBuilder.java              NFA→DFA + inline literals + aliases + delimited-block
│   └── LexerEngine.java
├── analyzer/
│   ├── Analyzer.java                grammar linter behind peglib:lint / peglib:check
│   ├── AnalyzerMain.java            CLI entry point
│   ├── AnalyzerReport.java, Finding.java
│   ├── LeftRecursionDetector.java   rejects at fromGrammar with witness
│   └── LeftRecursionCause.java
├── grammar/                         shared front-end: GrammarParser, GrammarLexer,
│                                    GrammarResolver, Grammar, Expression, Rule, Import
│   └── analysis/LeftRecursionAnalysis.java
├── error/ParseError.java
├── source/                          SourceLocation, SourceSpan
├── generator/
│   ├── LexerGenerator.java          emits GLexer.java
│   ├── ParserGenerator.java         emits GParser.java; boolean control flow
│   ├── VisitorGenerator.java        emits GVisitor.java
│   ├── LexerCompiler.java           JDK Compiler API — compiles IN MEMORY, nothing hits target/
│   └── ParserCompiler.java
└── incremental/
    └── IncrementalParser.java       snapshot/restore; partial reparse via checkpoint
```

## Grammar Syntax (cpp-peglib compatible)

```peg
RuleName <- Expression

# Operators
e1 e2       # Sequence
e1 / e2     # Ordered choice
e* e+ e?    # Repetition
&e !e       # Lookahead
(e)         # Group
'literal'   # String literal
"literal"   # String literal
[a-z]       # Character class
[^a-z]      # Negated character class
.           # Any character

# Extensions
< e >       # Token boundary (captures matched text)
'text'i     # Case-insensitive literal
[a-z]i      # Case-insensitive character class
e{n,m}      # Bounded repetition
^           # Cut — commits to current Choice alternative

# Directives
%whitespace <- [ \t\r\n]*
%recover <CharSet> Rule       # per-rule sync set (implemented per-rule since 0.6.1)
%checkpoint Rule              # incremental-reparse boundary
```

**Dropped in 0.6.0**: inline `{ ... }` action blocks (use `GVisitor<T>`).

Named captures `$name<e>` and back-references `$name` were dropped in 0.6.0 but **restored in 0.6.1** — they are supported at runtime via `ParserGenerator`'s capture map. `NamedCaptureDetector` no longer exists.

## API Usage

```java
// Basic — generate, compile, cache, parse
var parser = PegParser.fromGrammar(grammarText).unwrap();
ParseResult result = parser.parse(input);

if (result.isSuccess()) {
    CstArray cst = result.cst();
    // walk via cst.children(idx), cst.descendants(idx), or pattern-match cst.viewAt(idx)
}

for (Diagnostic d : result.diagnostics()) {
    System.err.println(d.formatRustStyle("file.java", input));
}
```

## Visitor Pattern (replaces 0.5.x inline actions)

The generator emits `GVisitor<T>` per grammar. One method per parser rule. Users subclass and override selectively.

```java
class TypeChecker extends GVisitor<Type> {
    @Override public Type visitBinaryExpr(CstArray cst, int nodeIdx) {
        Type left = visit(cst, cst.firstChildAt(nodeIdx));
        Type right = visit(cst, cst.lastChildBefore(nodeIdx));
        return resolveBinaryOp(cst.textAt(nodeIdx), left, right);
    }
}
```

## Trivia Handling

Trivia lives in `TokenArray` as tokens. There are **five** reserved kinds, not three —
`FIRST_USER_KIND` is 5, and `isTriviaKind` covers all of them:

- `TokenArray.KIND_WHITESPACE` (0)
- `TokenArray.KIND_LINE_COMMENT` (1)
- `TokenArray.KIND_BLOCK_COMMENT` (2)
- `TokenArray.KIND_DOC_LINE_COMMENT` (3) — added 0.6.1
- `TokenArray.KIND_DOC_BLOCK_COMMENT` (4) — added 0.6.1

Code that switches on trivia kind must handle the doc-comment variants; omitting them is a
silent classification bug, not a compile error.

Access via `cst.leadingTriviaTokens(nodeIdx)` and `trailingTriviaTokens(nodeIdx)`. Round-trip reconstruction: `cst.reconstruct()` concatenates all tokens including trivia.

## Error Recovery

One always-on mechanism (panic-mode synchronization):

1. Parser hits unexpected token
2. Walks forward to sync set (grammar's `%recover`, or the default `DEFAULT_SYNC_LITERALS` = `;` `,` `}` `)` `]`)
3. Emits `Error` node covering skipped range
4. Records `Diagnostic`
5. Resumes parsing

`ParseResult.diagnostics()` is always present (empty = success).

## Java 25 Contextual Keywords (IMPORTANT)

Java has hard and contextual keywords. The grammar's `Keyword` rule should list only **hard** keywords:

```
class, interface, package, import, public, private, return, if, else, while, ...
```

Contextual keywords are matched by specific rules and **fall through to Identifier elsewhere**:

| Keyword | Reserved Context | Identifier elsewhere |
|---|---|---|
| `var` | local type inference | method/field names |
| `yield` | switch expression | method/field names |
| `record` | type declaration | method/field names |
| `sealed`, `non-sealed` | class modifier | method/field names |
| `permits` | sealed class | method/field names |
| `when` | pattern guard | method/field names |
| `value` | **type declaration only** (`value class`, `value record`, `abstract value class`, `sealed abstract value class`) | everywhere else — and it is the single most common identifier of any contextual keyword Java has added (`var value = 3`, `int value;`, `value.foo()`) |
| `module`, `open`, `opens`, `requires`, `exports`, `provides`, `uses`, `with`, `to` | module declarations | regular code |

In 0.6.0's tokens-first parser, contextual keywords get **Identifier-fallback** at codegen time: where the parser references `Identifier`, it also accepts inline-literal kinds whose text is identifier-shaped and not in the hard-keyword set. See `DfaBuilder.buildIdentifierFallbacks` and `ParserGenerator.emitIdentifierFallback`.

**A contextual keyword whose disambiguation needs lookahead cannot live in a named rule.** `RuleClassifier` types any rule whose body references only lexer rules as LEXER, and a lexer rule may not reference another rule — `fromGrammar` then rejects it with `SkippedRuleReferenced`. Spell the lookahead inline inside the PARSER rule that needs it. This is why `value` appears as the literal group

```peg
(Modifier / ValueKW &(Modifier* (ClassKW / RecordKW)))*
```

at its four use sites rather than as a tidy `DeclModifier` rule. Both a `DeclModifier <- Modifier / ValueMod` rule and a `ValueMod <- ValueKW &(...)` rule were tried first; each was rejected by the guard.

## JBCT warning policy

Warnings do **not** gate the build (only hard errors do). ~690 remain, and that is intentional —
they are not a backlog. Triage them by asking two questions in order.

**1. Is the rule perf-relevant, or style?**

| Rule | Nature |
|---|---|
| `JBCT-PAT-01` (raw loop → functional iteration) | touches iteration on hot code |
| `JBCT-UTIL-02` (`Verify.Is::negative` etc.) | replaces a primitive compare with a predicate ref |
| `JBCT-STATIC-01` (static-import `Result.success()`) | style only |
| `JBCT-SEQ-01` (chain longer than 5 steps) | readability only |
| `JBCT-VO-01` (record wants a factory) | style only |

The style rules are safe to fix anywhere. Only `PAT-01` / `UTIL-02` interact with the parse path.

**2. Is the code hot, or cold?**

- **Hot — runs per token / per node, on every parse.** `peglib-runtime` in its entirety
  (`CstArray`, `CstArrayBuilder`, `TokenArray`, `TokenArrayBuilder`) plus `LexerEngine`.
  These carry a class-level `@SuppressWarnings({"JBCT-PAT-01", "JBCT-UTIL-02"})` with the
  reasoning inline. **Do not "clean these up".**
- **Cold — runs once per grammar, at `fromGrammar` time.** `DfaBuilder` (127 warnings),
  `ParserGenerator` (113), `GrammarParser` (51), `LexerGenerator` (31), `RuleClassifier`,
  `GrammarResolver`, `Analyzer`. These dominate the count but are *not* on the parse path —
  they run once and their cost shows up only in cold-compile (~200 ms), never in warm parse.
  Fixing them is optional and low-value, not forbidden.

**The argument for leaving the hot loops alone is risk, not a benchmark.** No one has measured
whether streams are slower there, and the JIT frequently handles them well — asserting otherwise
would be exactly the mental-model error the "profile-first, theorize never" rule warns about.
The real case is that it is a large mechanical rewrite of the most correctness-critical code in
the project for no user-visible benefit. If anyone does attempt it, bench it
(`Java25ParseBenchmark`, `Java25LargeFixturesBenchmark`, run from `peglib-core/`) instead of
assuming a direction.

## Build Commands

```bash
mvn install                                          # full reactor, lint + format-check included
mvn -pl peglib-core test                             # core tests only
mvn -pl peglib-core -am -Pbench -DskipTests package   # build bench jar
cd peglib-core && java -jar target/benchmarks.jar <BenchClass> -wi 3 -i 5 -f 1
```

The benchmarks resolve `src/test/resources/java25.peg` **relative to the working directory**, so
they must be run from `peglib-core/` — from the repo root every iteration fails with
`NoSuchFileException`.

`-Djbct.skip=true` is no longer required. The 0.25.0 formatter convergence bug is fixed in
1.0.0-rc2 (two consecutive `jbct:format` passes are byte-identical), so `mvn install` runs clean
with lint and format-check enabled. The flag still works as an escape hatch via the `jbct.skip`
property, which defaults to `false` in the parent pom.

Async-profiler at `/opt/homebrew/lib/libasyncProfiler.dylib`. Use via JMH `-prof async:libPath=...;event=cpu;output=collapsed;dir=/tmp/profile`.

## Tests

**551 tests across 5 modules**, 0 failures, 0 skips. The count dropped from 1445 in 0.6.3 because
the 0.5.x interpreter and its parity suites were deleted, not because coverage was lost.

Notable test classes for verification gates:
- `JavaCoverageProbe` / `JavaRejectionProbe` / `ModernJavaSyntaxProbe` — the accept/reject
  gates for the Java grammar. **`peglib-core/pom.xml` must keep `**/*Probe.java` in the
  surefire `<includes>`**: before 0.7.1 surefire matched only `*Test.java`, so these were
  compiled and never executed, and a grammar regression would have shipped green
- `Java25CorpusGateTest` — 20 format-examples fixtures lex round-trip
- `Java25ParserGateTest` — same fixtures parse round-trip, **plus CST-shape sanity**
  (`nodeCount >= LOC/3`), which is the gate that catches an empty-CompilationUnit collapse
  that byte-equal reconstruction alone would pass
- `FactoryClassGeneratorDiagTest` — real-world 1900-LOC parse (0 diagnostics)
- `Java25BisectTest` — minimal-snippet bisection helper for grammar triage
- `IncrementalEditBenchmark` — edit latency p50/p99 in `src/jmh/`
- `Java25LargeFixturesBenchmark` — warm parse on reference + selfhost fixtures
- `JavacParseOnlyBenchmark` — vs javac via `JavacTask.parse()`

The 0.5.x A/B comparison benchmarks were deleted with the legacy path, so the historical
"11-12× faster than 0.5.x-gen" figure can no longer be reproduced in-tree. Treat it as a
dated claim, not a live measurement.

---

# Banked Lessons

## Parser-domain rules

- **Bisection-first on parser bugs.** When a real-world file produces N diagnostics, write a bisect that narrows to a minimal failing input. Theorizing about likely causes wastes more time than running a 10-line bisect. (from 0.6.0 ship: 13,529 diagnostics on FactoryClassGenerator narrowed to one em-dash via 6 bisect rounds; the prior 3 theoretical hypotheses were all wrong.)

- **CST shape sanity is part of phase gates.** N LOC of source code should produce roughly N/3 to N CST nodes for this grammar. Order of magnitude shallower means parser is matching empty alternatives and bailing. "20/20 corpus round-trip" with 11 nodes/fixture is a false positive. (from 0.6.0 ship: the empty-CompilationUnit issue went undetected for two sessions because round-trip-via-tokens passed.)

- **Validate against real-world Java input early.** Curated test fixtures prove not-broken; they don't prove complete. Test against an actual codebase (e.g., a real JBCT slice generator) before declaring a parsing phase done. (from 0.6.0 ship: 20/20 curated corpus passed cleanly; FactoryClassGenerator surfaced contextual-keyword + Unicode + delimited-block bugs the corpus never exercised.)

- **For any perf claim: profile-first, theorize never.** Run async-profiler before optimizing. Mental models of hot paths in JIT'd Java are systematically wrong. (from 0.6.0 ship: pre-profile, I hypothesized JIT/allocation/method-dispatch; profile said 75% in one method (`CstArrayBuilder.truncate`) with one specific O(N) bug.)

- **Re-run JMH bench after every hot-path change.** Specifically anything touching `CstArrayBuilder`, `TokenArray.spliceLex`, `LexerEngine.lex`, or generated parser emit. Small changes matter. (from 0.6.0 ship: bounded-scan truncate was 24-48× on Records/SwitchExpressions; small.)

- **Contextual keywords in tokens-first PEG: MUST have explicit Identifier-fallback.** Java's `var`/`yield`/`record`/`sealed`/`permits`/`when`/`module`/`open`/etc. appear as inline literals in grammar rules but must accept Identifier-shaped tokens at codegen. This is a known design risk noted in CLAUDE.md but easy to forget. (from 0.6.0 ship: this gap caused ~13K diagnostics on FactoryClassGenerator until `buildIdentifierFallbacks` + `emitIdentifierFallback` were wired.)

- **DFA alphabet is 0..255 + per-state non-ASCII transition slot.** Don't try to extend alphabet to full Unicode — too expensive. For `.` (Any) and negated `CharClass`, emit a separate non-ASCII edge; the driver checks it when `ch >= 256`. (from 0.6.0 ship: line/block comments and strings broke on em-dash until the non-ASCII slot landed.)

- **Generated parsers depend ONLY on peglib-runtime + pragmatica-lite:core.** Verify against this
  explicit allow-list — generated `GLexer`/`GParser`/`GVisitor` source may import *only*:
  `org.pragmatica.peg.token.*`, `org.pragmatica.peg.cst.*`, `org.pragmatica.peg.diagnostic.*`
  (all three live in peglib-runtime), plus `org.pragmatica.lang.*` and `java.*`.
  Anything else — notably `org.pragmatica.peg.generator.*`, `.lexer.*`, `.grammar.*`, `.analyzer.*`,
  `.incremental.*` — means the standalone-parser invariant is broken.

  **The old "grep for peglib-core" heuristic no longer works.** Before 0.7.0 the split was visible in
  the package name (`peg.v6.generator` vs `peg.v6.token`); after collapsing `v6` away, core and runtime
  share the `org.pragmatica.peg` root and are distinguishable only by subpackage. Check the allow-list,
  not the module name.

  **You cannot grep `target/` for this.** `LexerCompiler` and `ParserCompiler` compile generated source
  in memory via the JDK Compiler API — nothing is written to disk. To verify, invoke
  `LexerGenerator` / `ParserGenerator` / `VisitorGenerator` directly against a real grammar and inspect
  the returned source.

- **Block comments inside Choice need explicit routing through `compileDelimitedBlock`.** The `'/*' (!'*/' .)* '*/'` pattern won't match correctly otherwise — the DFA can't handle `Not(Literal)` inside Choice alternatives. (from 0.6.0 ship: this is why block comments in `%whitespace` failed to lex until asymmetric `compileDelimitedBlock` was added.)

## Process rules

- **Commit checkpoints before dispatching parallel agents that touch crossing scopes.** If working tree has uncommitted impactful changes, parallel agents will collide on git state via stash — and stash-popping can lose work silently. (from 0.6.0 ship: the parallel #1 + #9 dispatch lost the Annotations.java fix into a stash that was nearly dropped.)

- **Spot-check destructive agent claims.** When an agent reports "removed N tests" or "dropped N validations", read 2-3 of them before accepting. Most are correct; the occasional wrong removal is hard to catch later. (from 0.6.0 ship: cleanup agent removed 37 validation-only tests as part of JBCT refactor; mostly fine but a spot-check would have been cheap.)

- **Phase gates must include shape sanity, not just functional pass.** A round-trip-via-tokens gate that succeeds when the parser produced 11 nodes/fixture is broken; the gate definition needed CST-node-count sanity.

## Collaboration Notes

Direct tips for the user (Sergiy) when working with Claude on this project. Banked from prior sessions where these patterns either saved time or cost it.

- **When Claude says "looks done" without showing build-runner output, push back.** Specifically: "show me the surefire summary." Cheap insurance against shipping wrong numbers. (banked from: bench-finished-mid-fixture incident where Claude reported "looks done" but only 1 of 8 JMH combinations had finished.)

- **When Claude offers A or B after you've already decided, override with the call.** Don't accept hedging — it costs cycles. Auto mode pushes Claude toward action; reinforce it with directness. (banked from: multiple "would you like A or B?" moments where "go with X" cut a 20-min cycle to 5 min.)

- **When a gate result looks suspiciously good, ask for a real-world fixture check before celebrating.** "20/20 clean parse + sub-ms incremental + 8.55× faster than 0.5.x" sounds great but each piece needs validation against non-curated input. (banked from: the 0.6.0 architectural promise was met on curated corpus but real-world Java files needed two more sessions of fixes.)

- **When Claude quotes a number from HANDOVER, ARCHITECTURE-0.6.0.md, or spec as authoritative, ask "verify it currently."** Static docs go stale within a session; live measurement doesn't. (banked from: the "javac parses 1900-LOC in ~9 ms" figure from HANDOVER was outdated; actual javac was 2.24 ms. The wrong figure nearly shipped as a real comparison claim.)

- **For 0.6.0, the "Visitor pattern" replaces inline actions.** When porting 0.5.x code that used `{ ... }` action blocks, generate `GVisitor.java` via the maven plugin and implement `visit<Rule>` methods. (banked from: this transition wasn't obvious to users; document it more prominently in user-facing migration guide.)

## ndx

`ndx` is available in this project. Use `/ndx` for full CLI reference.

Key commands: `ndx recall search "query"` (hybrid search), `ndx recall wake` (context), `ndx xref drawer <file>` (cross-ref).

Skills: `/ndx-recall-classify`, `/ndx-recall-score`, `/ndx-recall-dedupe`, `/ndx-recall-contradict`, `/ndx-recall-summarize`, `/ndx-recall-handover`.

## References

- [cpp-peglib](https://github.com/yhirose/cpp-peglib) — surface grammar syntax reference
- [PEG Paper](https://bford.info/pub/lang/peg.pdf) — Bryan Ford's original
- [Packrat Parsing](https://bford.info/pub/lang/packrat-icfp02.pdf) — historical context (0.6.0 doesn't use packrat)
- [tree-sitter](https://tree-sitter.github.io/tree-sitter/) — architectural analog for flat-array CST + incremental
