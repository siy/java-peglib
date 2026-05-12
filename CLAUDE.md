# Peglib — PEG Parser Library for Java

## Project Status

**0.6.0 shipped to Maven Central** (2026-05-11). Tokens-first lex-then-parse redesign. Generated parsers run within 1.2-1.8× of javac time on real Java 25, 11-12× faster than the 0.5.x source-generated parser. See `docs/HANDOVER.md` for state and `docs/ARCHITECTURE-0.6.0.md` for design.

Current branch: `main` at `v0.6.0`.

## Agent Usage

**MANDATE:** Use ONLY `jbct-coder` agent for ALL coding/fixing in this project. Use `build-runner` for `mvn` invocations. Use `chore-runner` for git/changelog work.

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
├── peglib-core/            grammar parser, codegen, analyzers, v6 implementation
├── peglib-incremental/     IncrementalParser; true partial reparse
├── peglib-formatter/       Wadler-Lindig pretty printer on flat CST
├── peglib-maven-plugin/    build-time codegen mojo
└── peglib-playground/      REPL + HTTP UI
```

## Source Files (v6, in peglib-core/peglib-runtime)

```
peglib-runtime/src/main/java/org/pragmatica/peg/v6/
├── token/
│   ├── TokenArray.java              flat int[] tokens; spliceLex for incremental
│   ├── TokenArrayBuilder.java
│   └── LexFn.java                   functional lexer adapter
├── cst/
│   ├── CstArray.java                flat int[]; findCheckpointAncestor; spliceSubtree
│   ├── CstArrayBuilder.java         truncate uses lastChildBefore undo log (O(dropped) bounded scan)
│   ├── CstNode.java                 sealed Branch/Leaf/Error views
│   └── ParseResult.java
└── diagnostic/
    ├── Severity.java
    └── Diagnostic.java              Rust-style format

peglib-core/src/main/java/org/pragmatica/peg/v6/
├── PegParser.java                   entry: fromGrammar(text) → Result<Parser>
├── Parser.java                      facade: parse(input) → ParseResult
├── lexer/
│   ├── RuleClassifier.java          LEXER/PARSER/MIXED + skip-prefix detection
│   ├── Dfa.java                     non-ASCII transition slot
│   ├── DfaBuilder.java              NFA→DFA + inline literals + aliases + delimited-block
│   └── LexerEngine.java
├── analyzer/
│   ├── LeftRecursionDetector.java   rejects at fromGrammar with witness
│   └── NamedCaptureDetector.java    rejects at fromGrammar (not yet supported at runtime)
├── generator/
│   ├── LexerGenerator.java          emits GLexer.java
│   ├── ParserGenerator.java         emits GParser.java; boolean control flow
│   ├── VisitorGenerator.java        emits GVisitor.java
│   ├── LexerCompiler.java           JDK Compiler API
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
%recover <CharSet> Rule       # per-rule sync set (start-rule applied currently)
%checkpoint Rule              # incremental-reparse boundary
```

**Dropped in 0.6.0**: inline `{ ... }` action blocks (use `GVisitor<T>`); named captures `$name<e>` and back-references `$name` (rejected at `fromGrammar`).

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

Trivia (whitespace, line comments, block comments) lives in `TokenArray` as tokens with kinds 0/1/2:

- `TokenArray.KIND_WHITESPACE`
- `TokenArray.KIND_LINE_COMMENT`
- `TokenArray.KIND_BLOCK_COMMENT`

Access via `cst.leadingTriviaTokens(nodeIdx)` and `trailingTriviaTokens(nodeIdx)`. Round-trip reconstruction: `cst.reconstruct()` concatenates all tokens including trivia.

## Error Recovery

One always-on mechanism (panic-mode synchronization):

1. Parser hits unexpected token
2. Walks forward to sync set (grammar's `%recover` or default `{ ; , } ) ] }`)
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
| `module`, `open`, `opens`, `requires`, `exports`, `provides`, `uses`, `with`, `to` | module declarations | regular code |

In 0.6.0's tokens-first parser, contextual keywords get **Identifier-fallback** at codegen time: where the parser references `Identifier`, it also accepts inline-literal kinds whose text is identifier-shaped and not in the hard-keyword set. See `DfaBuilder.buildIdentifierFallbacks` and `ParserGenerator.emitIdentifierFallback`.

## Build Commands

```bash
mvn install                                                            # full reactor
mvn -pl peglib-core test                                               # core tests only
mvn -pl peglib-core -am -Pbench -DskipTests -Djbct.skip=true package   # build bench jar
cd peglib-core && java -jar target/benchmarks.jar <BenchClass> -wi 3 -i 5 -f 1
```

`-Djbct.skip=true` is required for `mvn install` due to a JBCT 0.25.0 formatter convergence bug on 5 v6 files (lint passes cleanly without skip — the bug is in format-check only). Tracked as upstream.

Async-profiler at `/opt/homebrew/lib/libasyncProfiler.dylib`. Use via JMH `-prof async:libPath=...;event=cpu;output=collapsed;dir=/tmp/profile`.

## Tests

**1440 tests across 7 modules**, 0 failures, 4 pre-existing skips. See `docs/HANDOVER.md` §"State at a glance" for breakdown.

Notable test classes for verification gates:
- `Java25CorpusGateTest` — 20 format-examples fixtures lex round-trip
- `Java25ParserGateTest` — same fixtures parse round-trip
- `FactoryClassGeneratorDiagTest` — real-world 1900-LOC parse (0 diagnostics)
- `IncrementalEditBenchmark` — edit latency p50/p99 in `src/jmh/`
- `Java25LargeFixturesBenchmark` — warm parse vs 0.5.x-gen
- `JavacParseOnlyBenchmark` — vs javac via `JavacTask.parse()`

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

- **Generated parsers depend ONLY on peglib-runtime + pragmatica-lite:core.** If `peglib-core` shows up in generated source imports, the standalone-parser invariant is broken. Grep generated source for verification.

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
