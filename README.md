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

## Module Layout (0.3.0)

Peglib is a multi-module Maven reactor. Pick the module you need; transitive deps stay minimal.

```
peglib-parent (pom)
├── peglib-core            # engine, generator, analyzer — the core parser
├── peglib-incremental     # cursor-anchored incremental reparsing (shell; spec-driven)
├── peglib-formatter       # grammar-driven source formatter (shell)
├── peglib-maven-plugin    # codegen + analyzer goals for Maven builds
└── peglib-playground      # interactive REPL / web playground
```

The artifact id `peglib` is reserved for the parent pom; production consumers depend on
`peglib-core`. The other modules are optional add-ons.

## Quick Start

### Dependency

```xml
<dependency>
    <groupId>org.pragmatica-lite</groupId>
    <artifactId>peglib-core</artifactId>
    <version>0.3.0</version>
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

var grammar = GrammarParser.parse(grammarText)
                           .flatMap(Grammar::validate)
                           .unwrap();
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
    <version>0.3.0</version>
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
- **Web UI:** `java -jar peglib-playground-0.2.7-uber.jar --port 8080` then open
  `http://localhost:8080` — three panes (grammar / input / output) plus controls
  for start rule, CST/AST, trivia, recovery, packrat, and auto-refresh.
- **HTTP API:** `POST /parse` with `{"grammar":"…","input":"…","recovery":"BASIC","packrat":true,"trivia":true}`
  returns `{tree, diagnostics, stats}` JSON.

See [docs/PLAYGROUND.md](docs/PLAYGROUND.md) for the full usage guide.

## Examples

See the [examples](src/test/java/org/pragmatica/peg/examples/) directory:

| Example | Description |
|---------|-------------|
| [CalculatorExample](src/test/java/org/pragmatica/peg/examples/CalculatorExample.java) | Arithmetic with semantic actions |
| [JsonParserExample](src/test/java/org/pragmatica/peg/examples/JsonParserExample.java) | JSON CST parsing |
| [SExpressionExample](src/test/java/org/pragmatica/peg/examples/SExpressionExample.java) | Lisp-like syntax |
| [CsvParserExample](src/test/java/org/pragmatica/peg/examples/CsvParserExample.java) | CSV data format |
| [ErrorRecoveryExample](src/test/java/org/pragmatica/peg/examples/ErrorRecoveryExample.java) | Error recovery patterns |
| [SourceGenerationExample](src/test/java/org/pragmatica/peg/examples/SourceGenerationExample.java) | Standalone parser generation |
| [Java25GrammarExample](src/test/java/org/pragmatica/peg/examples/Java25GrammarExample.java) | Java 25 syntax parsing |

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

As of 0.2.2 the generated CST parser emits tuned helper variants driven by generator-time flags in `ParserConfig`. On a 1,900-LOC Java 25 fixture (`FactoryClassGenerator.java`), the new `ParserConfig.DEFAULT` yields a **4.23× speedup** over the pre-0.2.2 baseline (JMH 1.37 avgT, JDK 25.0.2, Apple Silicon; raw data in [`docs/bench-results/`](docs/bench-results/)).

Flags (all consumed at generation time — no runtime branching in the emitted parser):

| Flag | Phase | Default | Optimization |
|---|---|---|---|
| `fastTrackFailure` | 1 | on | Skip allocation in `trackFailure` when dominated by furthest failure |
| `literalFailureCache` | 1 | on | Per-parser cache of literal-match failure results; loop specialization |
| `charClassFailureCache` | 1 | on | Per-parser cache for char-class failures; bracketed error message |
| `bulkAdvanceLiteral` | 1 | on | Bulk `pos`/`column` update for no-newline literals |
| `skipWhitespaceFastPath` | 1 | on | First-char precheck derived from `%whitespace` rule |
| `reuseEndLocation` | 1 | on | Reuse end-position `SourceLocation` across span + result |
| `choiceDispatch` | 2 | on | `switch(input.charAt(pos))` dispatch for literal-prefixed Choice |
| `markResetChildren` | 2 | off | Replace children clone+clear+addAll with mark-and-trim |
| `inlineLocations` | 2 | off | Inline int locals at rule entry instead of SourceLocation |
| `selectivePackrat` | 2 | off | Skip packrat cache for rules in `packratSkipRules` |

The three default-off flags can be flipped on per-project via a custom `ParserConfig`. See [`docs/PERF-FLAGS.md`](docs/PERF-FLAGS.md) for the per-flag reference and guidance on when to flip, [`docs/PERF-REWORK-SPEC.md`](docs/PERF-REWORK-SPEC.md) for the underlying design, and [`docs/bench-results/java25-parse.json`](docs/bench-results/java25-parse.json) for raw JMH data.

To reproduce benchmarks:

```bash
mvn -Pbench -DskipTests package
java -jar target/benchmarks.jar org.pragmatica.peg.bench.Java25ParseBenchmark
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

## References

- [cpp-peglib](https://github.com/yhirose/cpp-peglib) - C++ PEG library (inspiration)
- [PEG Paper](https://bford.info/pub/lang/peg.pdf) - Bryan Ford's original paper
- [Packrat Parsing](https://bford.info/pub/lang/packrat-icfp02.pdf) - Memoization technique

## License

MIT
