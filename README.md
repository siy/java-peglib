# Peglib

A PEG (Parsing Expression Grammar) parser library for Java, inspired by [cpp-peglib](https://github.com/yhirose/cpp-peglib).

## Features

- **Grammar-driven parsing** - Define parsers using PEG syntax in strings
- **cpp-peglib compatible syntax** - Familiar grammar format for cpp-peglib users
- **Dual tree output** - CST (lossless) for formatting/linting, AST (optimized) for compilers
- **Inline Java actions** - Embed Java code directly in grammar rules
- **Trivia preservation** - Whitespace and comments captured for round-trip transformations
- **Advanced error recovery** - Continue parsing after errors with Rust-style diagnostics
- **Packrat memoization** - O(n) parsing complexity
- **Direct left-recursion** - Warth-style seed-and-grow (0.2.9); see [GRAMMAR-DSL.md](docs/GRAMMAR-DSL.md#left-recursion)
- **Source code generation** - Generate standalone parser Java files
- **Java 25** - Uses latest Java features (records, sealed interfaces, pattern matching)

## Module Layout (0.4.0+)

Peglib is a multi-module Maven reactor. Pick the module you need; transitive deps stay minimal.

```
peglib-parent (pom)
├── peglib-core            # engine, generator, analyzer — the core parser
├── peglib-incremental     # cursor-anchored incremental reparsing (v2 — 0.3.2)
├── peglib-formatter       # Wadler-style pretty-printer framework (v1 — 0.3.3)
├── peglib-maven-plugin    # codegen + analyzer goals for Maven builds
└── peglib-playground      # interactive REPL / web playground
```

The `peglib-core` module directory ships the primary artifact `org.pragmatica-lite:peglib` —
the Maven coordinate is preserved from 0.2.x for downstream compatibility. The other modules
are optional add-ons.

Quick links:
- [`peglib-incremental` README](peglib-incremental/README.md) — incremental reparsing
- [`peglib-formatter` README](peglib-formatter/README.md) — pretty-printer framework
- [`docs/PRETTY-PRINTING.md`](docs/PRETTY-PRINTING.md) — formatter design notes
- [`docs/incremental/ARCHITECTURE-0.5.0.md`](docs/incremental/ARCHITECTURE-0.5.0.md) — incremental parsing architecture (0.5.0)

## Quick Start

### Dependency

```xml
<dependency>
    <groupId>org.pragmatica-lite</groupId>
    <artifactId>peglib</artifactId>
    <version>0.5.0</version>
</dependency>
```

Requires [pragmatica-lite:core](https://github.com/siy/pragmatica-lite) for `Result`/`Option` types.

### Basic Parsing

```java
import org.pragmatica.peg.PegParser;

// Define grammar and create parser
var parser = PegParser.fromGrammar("""
    Number <- < [0-9]+ >
    %whitespace <- [ \\t]*
    """).unwrap();

// Parse to CST (lossless, preserves trivia)
var cst = parser.parseCst("123").unwrap();

// Parse to AST (optimized, no trivia)
var ast = parser.parseAst("123").unwrap();
```

### Parsing with Actions

```java
var calculator = PegParser.fromGrammar("""
    Expr   <- Term (('+' / '-') Term)*
    Term   <- Factor (('*' / '/') Factor)*
    Factor <- Number / '(' Expr ')'
    Number <- < [0-9]+ > { return sv.toInt(); }
    %whitespace <- [ ]*
    """).unwrap();

// Actions transform parsed content into semantic values
Integer result = (Integer) calculator.parse("3 + 5 * 2").unwrap();
// result = 13
```

### Partial parse (0.3.0)

`parseRuleAt` invokes a single rule against a substring of the buffer starting at a
given offset. Intended for cursor-anchored incremental reparsing and grammar-debugging
tooling. See [docs/PARTIAL-PARSE.md](docs/PARTIAL-PARSE.md) for the full API.

```java
record Number() implements org.pragmatica.peg.action.RuleId {}

var parser = PegParser.fromGrammar("""
    Number <- < [0-9]+ >
    %whitespace <- [ \\t]*
    """).unwrap();

var partial = parser.parseRuleAt(Number.class, "  42  ", 2).unwrap();
// partial.endOffset() == 4, partial.node() is the CST for "42"
```

## Grammar Syntax

Peglib uses [PEG](https://bford.info/pub/lang/peg.pdf) syntax compatible with [cpp-peglib](https://github.com/yhirose/cpp-peglib):

### Basic Operators

```peg
# Rule definition
RuleName <- Expression

# Sequence - match e1 then e2
e1 e2

# Ordered choice - try e1, if fails try e2
e1 / e2

# Quantifiers
e*          # Zero or more
e+          # One or more
e?          # Optional
e{3}        # Exactly 3 times
e{2,}       # At least 2 times
e{2,5}      # Between 2 and 5 times

# Lookahead predicates (don't consume input)
&e          # Positive lookahead - succeeds if e matches
!e          # Negative lookahead - succeeds if e doesn't match

# Cut - commits to current choice, prevents backtracking
^           # Cut operator
↑           # Cut operator (alternative syntax)

# Grouping
(e1 e2)     # Group expressions

# Terminals
'literal'   # String literal (single quotes)
"literal"   # String literal (double quotes)
[a-z]       # Character class
[^a-z]      # Negated character class
.           # Any character
```

### Extensions

```peg
# Token boundary - captures matched text as $0
< e >

# Ignore semantic value
~e

# Case-insensitive matching
'text'i
[a-z]i

# Named capture and back-reference
$name<e>    # Capture as 'name'
$name       # Back-reference to captured 'name'
```

### Directives

```peg
# Auto-skip whitespace between tokens
%whitespace <- [ \t\r\n]*
```

Advanced rule-level directives (`%expected`, `%recover`, `%tag`), the
grammar-level `%suggest` directive, and `%import GrammarName.RuleName`
for cross-grammar rule composition (0.2.8) are documented in
[`docs/GRAMMAR-DSL.md`](docs/GRAMMAR-DSL.md) along with the cut-operator
edge cases.

### Inline Actions

Actions are Java code blocks that transform parsed content:

```peg
Number <- < [0-9]+ > { return sv.toInt(); }
Sum <- Number '+' Number { return (Integer)$1 + (Integer)$2; }
Word <- < [a-z]+ > { return $0.toUpperCase(); }
```

## Action API

Inside action blocks, you have access to `SemanticValues sv`:

| Access | Description |
|--------|-------------|
| `sv.token()` or `$0` | Matched text (raw input) |
| `sv.get(0)` or `$1` | First child's semantic value |
| `sv.get(1)` or `$2` | Second child's semantic value |
| `sv.toInt()` | Parse matched text as integer |
| `sv.toDouble()` | Parse matched text as double |
| `sv.size()` | Number of child values |
| `sv.values()` | All child values as List |

Note: `$1`, `$2`, etc. use 1-based indexing (like regex groups), while `sv.get()` uses 0-based.

## Configuration

```java
var parser = PegParser.builder(grammar)
    .packrat(true)                           // Enable memoization (default: true)
    .trivia(true)                            // Collect whitespace/comments (default: true)
    .recovery(RecoveryStrategy.ADVANCED)     // Error recovery mode
    .build()
    .unwrap();
```

## Error Recovery

Peglib provides advanced error recovery with Rust-style diagnostic messages:

```java
var parser = PegParser.builder(grammar)
    .recovery(RecoveryStrategy.ADVANCED)
    .build()
    .unwrap();

var result = parser.parseCstWithDiagnostics("abc, @@@, def");

if (result.hasErrors()) {
    System.out.println(result.formatDiagnostics("input.txt"));
}
```

Output:
```
error: unexpected input
  --> input.txt:1:6
   |
 1 | abc, @@@, def
   |      ^ found '@'
   |
   = help: expected [a-z]+
```

### Recovery Strategies

| Strategy | Behavior |
|----------|----------|
| `NONE` | Fail immediately on first error |
| `BASIC` | Report error with context, stop parsing |
| `ADVANCED` | Continue parsing, collect all errors, insert Error nodes |

See [Error Recovery Documentation](docs/ERROR_RECOVERY.md) for details.

## Trivia Handling

CST nodes preserve whitespace and comments as trivia:

```java
var parser = PegParser.fromGrammar("""
    Expr <- Number '+' Number
    Number <- < [0-9]+ >
    %whitespace <- [ \\t]+
    """).unwrap();

var cst = parser.parseCst("  42 + 7  ").unwrap();

// Access trivia
List<Trivia> leading = cst.leadingTrivia();   // "  " before 42
List<Trivia> trailing = cst.trailingTrivia(); // "  " after 7
```

Trivia types (classified by content: starts with `//` → `LineComment`,
`/*` → `BlockComment`, else `Whitespace`):

- `Trivia.Whitespace` - spaces, tabs, newlines
- `Trivia.LineComment` - `// ...` style
- `Trivia.BlockComment` - `/* ... */` style

Attribution: trivia between sibling elements attaches to the **following
sibling's** `leadingTrivia`. See
[`docs/TRIVIA-ATTRIBUTION.md`](docs/TRIVIA-ATTRIBUTION.md) for the full
rule and 0.2.4 status (round-trip reconstruction deferred).

## Source Code Generation

Generate standalone parser Java files for production use:

```java
Result<String> source = PegParser.generateParser(
    grammarText,
    "com.example.parser",  // package name
    "JsonParser"           // class name
);

// Write to file
Files.writeString(Path.of("JsonParser.java"), source.unwrap());
```

Generated parsers:
- Are self-contained single files
- Only depend on `pragmatica-lite:core`
- Include packrat memoization
- Support trivia collection
- Have type-safe `RuleId` for each grammar rule

### Generated Parser with Advanced Diagnostics

Generate parsers with Rust-style error reporting:

```java
import org.pragmatica.peg.generator.ErrorReporting;

// Generate CST parser with advanced diagnostics
Result<String> source = PegParser.generateCstParser(
    grammarText,
    "com.example.parser",
    "MyParser",
    ErrorReporting.ADVANCED  // Enable Rust-style diagnostics
);
```

| ErrorReporting | Description |
|----------------|-------------|
| `BASIC` | Simple `ParseError(line, column, reason)` - minimal code |
| `ADVANCED` | Full diagnostics with source context, underlines, labels |

When `ADVANCED` is enabled, the generated parser includes:

```java
// Parse with diagnostics
var result = parser.parseWithDiagnostics(input);

if (result.hasErrors()) {
    // Format as Rust-style diagnostics
    System.err.println(result.formatDiagnostics("input.txt"));
}

// Access individual diagnostics
for (var diag : result.diagnostics()) {
    System.out.println(diag.formatSimple()); // file:line:col: severity: message
}
```

Output example:
```
error: expected Number
  --> input.txt:1:5
   |
 1 | 3 + @invalid
   |     ^ found '@'
   |
```

## Grammar Analyzer

Static lint checks for PEG grammars. Detects unreachable rules, ambiguous first-char choices, nullable rules, duplicate literals in choices, `%whitespace` self-cycles, and rules using `BackReference` (forward-compat note for incremental parsing).

Run from code:

```java
import org.pragmatica.peg.analyzer.Analyzer;

// 0.4.0+: GrammarParser.parse already returns a validated Result<Grammar>.
var grammar = GrammarParser.parse(grammarText).unwrap();
var report = Analyzer.analyze(grammar);

if (report.hasErrors()) {
    System.out.print(report.formatRustStyle("grammar.peg"));
    System.exit(1);
}
```

Run from CLI:

```bash
java -cp peglib.jar org.pragmatica.peg.analyzer.AnalyzerMain grammar.peg
```

Exit status: `0` when no errors, `1` when errors found, `2` on I/O or parse failure. Findings are emitted in Rust-`cargo check`-style format (`error[grammar.duplicate-literal]: …`).

See [docs/GRAMMAR-DSL.md](docs/GRAMMAR-DSL.md#analyzer) for the full finding catalog.

## Maven Plugin

The `peglib-maven-plugin` module (separate artifact, sibling to `peglib`) wraps the generator and analyzer for build-time use. Goals:

- `peglib:generate` — generate a standalone parser Java source from a grammar, skip when up-to-date
- `peglib:lint` — run the analyzer, fail build on errors (optionally on warnings)
- `peglib:check` — lint + build the runtime parser + parse a smoke-test input

```xml
<plugin>
    <groupId>org.pragmatica-lite</groupId>
    <artifactId>peglib-maven-plugin</artifactId>
    <version>0.5.0</version>
    <executions>
        <execution>
            <goals>
                <goal>generate</goal>
            </goals>
            <configuration>
                <grammarFile>src/main/peg/MyGrammar.peg</grammarFile>
                <outputDirectory>${project.build.directory}/generated-sources/peg</outputDirectory>
                <packageName>com.example.parser</packageName>
                <className>MyParser</className>
                <errorReporting>BASIC</errorReporting>
            </configuration>
        </execution>
    </executions>
</plugin>
```

## Playground

The `peglib-playground` module (separate artifact, sibling to `peglib`) bundles a
REPL and an embedded web UI for experimenting with grammars interactively.

- **CLI REPL:** `java -cp peglib-playground.jar org.pragmatica.peg.playground.PlaygroundRepl grammar.peg`
- **Web UI:** `java -jar peglib-playground-0.4.2-uber.jar --port 8080` then open
  `http://localhost:8080` — three panes (grammar / input / output) plus controls
  for start rule, CST/AST, trivia, recovery, packrat, and auto-refresh.
- **HTTP API:** `POST /parse` with `{"grammar":"…","input":"…","recovery":"BASIC","packrat":true,"trivia":true}`
  returns `{tree, diagnostics, stats}` JSON.

See [docs/PLAYGROUND.md](docs/PLAYGROUND.md) for the full usage guide.

## Examples

See the [examples](peglib-core/src/test/java/org/pragmatica/peg/examples/) directory:

| Example | Description |
|---------|-------------|
| [CalculatorExample](peglib-core/src/test/java/org/pragmatica/peg/examples/CalculatorExample.java) | Arithmetic with semantic actions |
| [JsonParserExample](peglib-core/src/test/java/org/pragmatica/peg/examples/JsonParserExample.java) | JSON CST parsing |
| [SExpressionExample](peglib-core/src/test/java/org/pragmatica/peg/examples/SExpressionExample.java) | Lisp-like syntax |
| [CsvParserExample](peglib-core/src/test/java/org/pragmatica/peg/examples/CsvParserExample.java) | CSV data format |
| [ErrorRecoveryExample](peglib-core/src/test/java/org/pragmatica/peg/examples/ErrorRecoveryExample.java) | Error recovery patterns |
| [SourceGenerationExample](peglib-core/src/test/java/org/pragmatica/peg/examples/SourceGenerationExample.java) | Standalone parser generation |
| [Java25GrammarExample](peglib-core/src/test/java/org/pragmatica/peg/examples/Java25GrammarExample.java) | Java 25 syntax parsing |

## CST Node Types

```java
public sealed interface CstNode {
    record Terminal(...)    // Leaf node with text
    record NonTerminal(...) // Interior node with children
    record Token(...)       // Result of < > operator
    record Error(...)       // Unparseable region (error recovery)
}
```

## Performance

### Throughput engine (generator) — 0.5.0-candidate

The parser generator output is now branded the **throughput engine** (full-reparse speed) and is distinct from the **incremental engine** (PegEngine + IncrementalSession, optimized for interactive editing). Different optimization targets, different code shapes, no shared code.

Cumulative arc on the 1,900-LOC reference fixture (`FactoryClassGenerator.java.txt`, JMH 1.37 avgT, JDK 25, Apple Silicon, variant `phase1_allStructural_mutableResult_autoSkipPackrat`):

| Stage | Wallclock | Allocation | vs original |
|---|---:|---:|---:|
| Pre-Tier-1 baseline | 76.2 ms | 150 MB | — |
| Post Tier 1 (A + D + F + G + G2/H + selective packrat + DFA Identifier) | 22.6 ms | 75.6 MB | -70% wallclock, -50% bytes |
| Post-rollback wins (trivia int snapshot, ASCII char interning) | **19.12 ms** | **68.02 MB** | **-75% wallclock, -55% bytes** |

A 37k-LOC self-host stress fixture (the Java25 generated parser parsing its own generated source) lands at **832 ms / 1.85 GB** — pre-Tier-1 it OOM'd. vs javac comparison: peglib parses the reference fixture in ~2× of javac wallclock with strictly more output (lossless CST + trivia for formatter and linter use cases).

See [`docs/incremental/THROUGHPUT-ENGINE-TIER1.md`](docs/incremental/THROUGHPUT-ENGINE-TIER1.md) and [`docs/incremental/THROUGHPUT-ENGINE-MOVE-B.md`](docs/incremental/THROUGHPUT-ENGINE-MOVE-B.md) for the full session post-mortem (including 5 reset attempts banked as lessons), and [`CHANGELOG.md`](CHANGELOG.md#050---2026-05-06) for the per-move detail.

### Incremental engine (interactive editing) — 0.5.0-candidate

`peglib-incremental`'s `IncrementalSession` provides cursor-anchored single-edit reparsing. Phase 1 of the 0.5.0 architectural rework (stable node IDs + `LongLongMap` NodeIndex + Path D ancestor-preservation algorithm) ships **1.9× faster median (10.8 ms → 5.6 ms)** and **96.5% of edits within the 16 ms frame budget** vs the 0.4.3 baseline. Lever D (Cursor/Session split) layers on a further -9% median / -53% p99. See [`docs/incremental/PHASE-1-RESULTS.md`](docs/incremental/PHASE-1-RESULTS.md).

### Interpreter (PegEngine) — 0.4.1

On the same 1,900-LOC Java 25 fixture, the interpreter (`PegParser.fromGrammar(...).parseCst(...)`) is **3.88× faster in 0.4.1** than 0.4.0 (281 ms → 72.4 ms). Three flame-graph-driven changes: HashMap rule-lookup cache, singleton `ParseMode` constants, `LinkedHashSet` dedup for the furthest-failure expected-set. See [`docs/bench-results/post-0.4.0/`](docs/bench-results/post-0.4.0/) for raw JMH + JFR data.

### Generator-time perf flags (0.2.2 origin, evolved through 0.5.0)

Flags (all consumed at generation time — no runtime branching in the emitted parser):

| Flag | Phase | Default | Optimization |
|---|---|---|---|
| `fastTrackFailure` | 1 | on | Skip allocation in `trackFailure` when dominated by furthest failure |
| `literalFailureCache` | 1 | on | Per-parser cache of literal-match failure results; loop specialization |
| `charClassFailureCache` | 1 | on | Per-parser cache for char-class failures; bracketed error message |
| `bulkAdvanceLiteral` | 1 | on | Bulk `pos`/`column` update for no-newline literals |
| `skipWhitespaceFastPath` | 1 | on | First-char precheck derived from `%whitespace` rule |
| `reuseEndLocation` | 1 | on | Reuse end-position `SourceLocation` across span + result |
| `choiceDispatch` | 2 | on | First-set `switch(input.charAt(pos))` dispatch for Choice (extended in 0.5.0 to CharClass + Reference + mixed) |
| `inlineLocations` | 2 | on (since 0.5.0) | Inline int locals at rule entry instead of SourceLocation |
| `selectivePackrat` | 2 | on (since 0.5.0) | Skip packrat cache for rules in `packratSkipRules`; auto-derives skip-set when empty via `PackratAnalyzer.autoSkipPackratRules(grammar)` |
| `tokenFastPath` | 2 | on (since 0.5.0) | DFA fast-path scanner for token-shaped rules (`< CharClass + ZeroOrMore<CharClass> >`) |
| `markResetChildren` | 2 | off | Replace children clone+clear+addAll with mark-and-trim |
| `mutableParseResult` | 2 | off (opt-in) | Emit mutable `CstParseResult` with raw nullable fields — eliminates Option boxing |

Default-off flags can be flipped on per-project via a custom `ParserConfig`. See [`docs/PERF-FLAGS.md`](docs/PERF-FLAGS.md) for the per-flag reference and guidance on when to flip, [`docs/archive/PERF-REWORK-SPEC.md`](docs/archive/PERF-REWORK-SPEC.md) for the underlying design (archived; superseded by [`docs/incremental/THROUGHPUT-ENGINE-TIER1.md`](docs/incremental/THROUGHPUT-ENGINE-TIER1.md)), and [`docs/bench-results/java25-parse.json`](docs/bench-results/java25-parse.json) for raw JMH data.

To reproduce benchmarks:

```bash
mvn -Pbench -DskipTests package
java -jar peglib-core/target/benchmarks.jar org.pragmatica.peg.bench.Java25ParseBenchmark
java -jar peglib-incremental/target/benchmarks.jar org.pragmatica.peg.incremental.bench.IncrementalBenchmark
```

See [`docs/BENCHMARKING.md`](docs/BENCHMARKING.md) for the full JMH harness
reference (variants, `@Param` extension, result interpretation).

## Building

```bash
mvn compile    # Compile
mvn test       # Run tests
mvn verify     # Full verification
```

Requires Java 25+. For JMH benchmarks see [`docs/BENCHMARKING.md`](docs/BENCHMARKING.md).

## Recent Releases

Full history in [`CHANGELOG.md`](CHANGELOG.md). Highlights since the 0.2.x line:

| Version | Date | What |
|---|---|---|
| **0.5.0** | 2026-05-08 (candidate) | **Throughput engine** rebrand + Tier 1 perf arc — reference fixture **76.2 ms → 19.12 ms** (-75%) and self-host (37k LOC) **OOM → 832 ms**. Incremental engine Phase 1: stable node IDs + LongLongMap NodeIndex + Path D ancestor-preservation — **1.9× faster median** for cursor-aware edits. **BREAKING:** `CstNode` records gain `long id` as the first record component (equals/hashCode exclude id). New `Cursor` value type split out from `Session`. |
| **0.4.3** | 2026-05-06 | Interactive editing perf: -19% median, -26% p95. **BREAKING:** `SourceSpan` now stores int triples instead of `SourceLocation` refs. |
| **0.4.2** | 2026-05-05 | Generated parsers now truly standalone — emit zero peglib FQCN references in their source. Drop them into a project with no peglib runtime and they compile. |
| **0.4.1** | 2026-05-04 | **3.88× interpreter speedup** + 3.0× incremental cursor-far edit. Three flame-graph-driven fixes; no API change. |
| **0.4.0** | 2026-05-03 | **Breaking** — API consolidation. Multi-module split (`peglib`, `peglib-incremental`, `peglib-formatter`, `peglib-maven-plugin`, `peglib-playground`). Consistent factory naming. Result-typed pipelines at every boundary. Parse-don't-validate `Grammar`. Immutable `FormatterConfig` record. Migration notes in CHANGELOG. |
| **0.3.6** | 2026-05-01 | Generator-side `%recover` per-rule overrides. Both interpreter and source-generated parsers now honor the directive end-to-end. |
| **0.3.5** | 2026-05-01 | Trivia round-trip resolution (5 attribution bugs A–C''). `RoundTripTest` re-enabled (22/22 byte-equal corpus). Interpreter `%recover` directive. |
| **0.3.3** | 2026-04-25 | `peglib-formatter` module — Wadler–Lindig pretty-printer framework. |
| **0.3.2** | 2026-04-23 | `peglib-incremental` v2 — cursor-anchored reparse with edit-anchored boundary detection. |
| **0.3.0** | 2026-04-22 | Multi-module reactor introduced. `parseRuleAt` partial-parse API. Incremental parsing v1. |
| **0.2.9** | 2026-04-22 | Direct left-recursion via Warth-style seed-and-grow. |
| **0.2.8** | 2026-04-21 | `%import GrammarName.RuleName` for cross-grammar rule composition. |
| **0.2.2** | 2026-04-21 | Performance rework — generator-time perf flags in `ParserConfig`. 4.23× speedup on the 1,900-LOC Java 25 fixture. |

## References

- [cpp-peglib](https://github.com/yhirose/cpp-peglib) - C++ PEG library (inspiration)
- [PEG Paper](https://bford.info/pub/lang/peg.pdf) - Bryan Ford's original paper
- [Packrat Parsing](https://bford.info/pub/lang/packrat-icfp02.pdf) - Memoization technique

## License

MIT
