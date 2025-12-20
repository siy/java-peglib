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
- **Source code generation** - Generate standalone parser Java files
- **Java 25** - Uses latest Java features (records, sealed interfaces, pattern matching)

## Quick Start

### Dependency

```xml
<dependency>
    <groupId>org.pragmatica-lite</groupId>
    <artifactId>peglib</artifactId>
    <version>0.1.3-SNAPSHOT</version>
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

# Word boundary detection
%word <- [a-zA-Z]+
```

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
    .withPackrat(true)                           // Enable memoization (default: true)
    .withTrivia(true)                            // Collect whitespace/comments (default: true)
    .withErrorRecovery(RecoveryStrategy.ADVANCED) // Error recovery mode
    .build()
    .unwrap();
```

## Error Recovery

Peglib provides advanced error recovery with Rust-style diagnostic messages:

```java
var parser = PegParser.builder(grammar)
    .withErrorRecovery(RecoveryStrategy.ADVANCED)
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

Trivia types:
- `Trivia.Whitespace` - spaces, tabs, newlines
- `Trivia.LineComment` - `// ...` style
- `Trivia.BlockComment` - `/* ... */` style

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

## Building

```bash
mvn compile    # Compile
mvn test       # Run tests (240 tests)
mvn verify     # Full verification
```

Requires Java 25+.

## References

- [cpp-peglib](https://github.com/yhirose/cpp-peglib) - C++ PEG library (inspiration)
- [PEG Paper](https://bford.info/pub/lang/peg.pdf) - Bryan Ford's original paper
- [Packrat Parsing](https://bford.info/pub/lang/packrat-icfp02.pdf) - Memoization technique

## License

MIT
