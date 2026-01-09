# Peglib - PEG Parser Library for Java

## Project Status: FEATURE COMPLETE

## Agent Usage

**IMPORTANT:** Use ONLY `jbct-coder` agent for ALL coding and fixing tasks in this project.

## Overview

Java implementation of PEG (Parsing Expression Grammar) parser inspired by [cpp-peglib](https://github.com/yhirose/cpp-peglib).

## Key Design Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Package | `org.pragmatica.peg` | Clean namespace |
| Artifact ID | `peglib` | Matches cpp-peglib naming |
| Java version | 25 | Latest features |
| Grammar syntax | cpp-peglib compatible | Familiar to users |
| Actions | Inline Java in `{ }` blocks | More readable than external attachment |
| Tree output | Both CST and AST | CST for formatting/linting, AST for compilers |
| Whitespace/comments | Grouped as Trivia nodes | Convenient for tooling |
| Error recovery | Configurable (basic/advanced) | Flexibility for different use cases |
| Runtime dependency | `pragmatica-lite:core` 0.9.0 | Result/Option/Promise types |

## Compilation Modes

1. **Runtime compilation** - JDK compiler API, full Java support in actions
2. **Source generation** - Single self-contained Java file, depends on pragmatica-lite:core only

## Source Files

```
src/main/java/org/pragmatica/peg/
├── PegParser.java              # Main entry point
├── grammar/
│   ├── Expression.java         # PEG expression types (sealed interface)
│   ├── Rule.java               # Grammar rule record
│   ├── Grammar.java            # Complete grammar record
│   ├── GrammarToken.java       # Lexer token types
│   ├── GrammarLexer.java       # Tokenizer for PEG grammar
│   └── GrammarParser.java      # Recursive descent parser
├── parser/
│   ├── Parser.java             # Parser interface
│   ├── ParserConfig.java       # Configuration record
│   ├── ParsingContext.java     # Mutable parsing state with packrat cache
│   ├── ParseResult.java        # Parse result types (sealed)
│   ├── ParseResultWithDiagnostics.java # Result with error recovery diagnostics
│   ├── ParseMode.java          # Parsing mode (standard, withActions, noWhitespace)
│   └── PegEngine.java          # PEG parsing engine with action execution
├── tree/
│   ├── SourceLocation.java     # Position in source (line, column, offset)
│   ├── SourceSpan.java         # Range in source (start, end)
│   ├── Trivia.java             # Whitespace/comments (sealed interface)
│   ├── CstNode.java            # Concrete syntax tree (sealed interface)
│   └── AstNode.java            # Abstract syntax tree (sealed interface)
├── action/
│   ├── Action.java             # Functional interface for actions
│   ├── SemanticValues.java     # Values passed to actions ($0, $1, etc.)
│   └── ActionCompiler.java     # JDK Compiler API based action compiler
├── generator/
│   ├── ParserGenerator.java    # Generates standalone parser source
│   └── ErrorReporting.java     # BASIC/ADVANCED enum for generated parser diagnostics
└── error/
    ├── ParseError.java         # Parse errors (sealed interface extends Cause)
    ├── RecoveryStrategy.java   # Error recovery enum (NONE, BASIC, ADVANCED)
    └── Diagnostic.java         # Rich error type for Rust-style messages

src/test/java/org/pragmatica/peg/
├── PegParserTest.java          # 50 tests (parsing + actions + cut operator)
├── EdgeCaseTest.java           # 24 tests (edge cases)
├── TriviaTest.java             # 13 tests (trivia handling)
├── GeneratedParserTriviaTest.java # 6 tests (generated parser trivia)
├── ErrorRecoveryTest.java      # 8 tests (error recovery + diagnostics)
├── parser/
│   ├── ParseResultTest.java    # 10 tests (ParseResult internal types)
│   └── ParsingContextTest.java # 8 tests (packrat cache)
├── grammar/
│   └── GrammarParserTest.java  # 17 tests for grammar parser
├── generator/
│   ├── ParserGeneratorTest.java # 18 tests for source generation
│   └── GeneratedParserDiagnosticsTest.java # 6 tests (ADVANCED diagnostics)
└── examples/
    ├── ErrorRecoveryExample.java # 12 tests - error recovery patterns
    ├── CalculatorExample.java   # 6 tests - arithmetic with actions
    ├── JsonParserExample.java   # 11 tests - JSON CST parsing
    ├── SExpressionExample.java  # 11 tests - Lisp-like syntax
    ├── CsvParserExample.java    # 8 tests - CSV data format
    ├── SourceGenerationExample.java # 11 tests - standalone parser
    ├── CutOperatorRegressionTest.java # 16 tests - cut operator regression tests
    └── Java25GrammarExample.java # 60 tests - Java 25 syntax
```

## Grammar Syntax (cpp-peglib compatible)

```peg
# Rule definition
RuleName <- Expression

# Operators
e1 e2       # Sequence
e1 / e2     # Ordered choice (prioritized)
e*          # Zero or more
e+          # One or more
e?          # Optional
&e          # Positive lookahead
!e          # Negative lookahead
(e)         # Grouping
'literal'   # String literal
"literal"   # String literal
[a-z]       # Character class
[^a-z]      # Negated character class
.           # Any character

# Extensions
< e >       # Token boundary (captures matched text)
~e          # Ignore semantic value
'text'i     # Case-insensitive literal
[a-z]i      # Case-insensitive character class
e{n}        # Exactly n repetitions
e{n,}       # At least n repetitions
e{n,m}      # Between n and m repetitions
$name<e>    # Named capture
$name       # Back-reference
^           # Cut - commits to current choice, prevents backtracking
↑           # Cut (alternative syntax)

# Directives
%whitespace <- [ \t\r\n]*    # Auto-skip whitespace

# Inline actions (Java)
Number <- < [0-9]+ > { return sv.toInt(); }
Sum <- Number '+' Number { return (Integer)$1 + (Integer)$2; }
```

## Implementation Progress

### Completed
- [x] Project scaffolded with `jbct init`
- [x] pom.xml updated for Java 25, pragmatica-lite 0.9.0
- [x] Core types implemented
- [x] Grammar parser (bootstrap) implemented
- [x] Parsing engine with packrat memoization
- [x] Action compilation (runtime) - JDK Compiler API
- [x] Source generator - standalone parser Java file
- [x] Trivia handling (whitespace/comments) for lossless CST
- [x] Advanced error recovery with Rust-style diagnostics
- [x] Generated parser ErrorReporting (BASIC/ADVANCED) for optional Rust-style diagnostics
- [x] Cut operator (^/↑) - commits to current choice, prevents backtracking
- [x] 308 passing tests

### Remaining Work
- [ ] Performance optimization
- [ ] Lambda action attachment (lowest priority) - attach actions programmatically using type-safe RuleId:
  ```java
  parser.rule(RuleId.Number.class).action(sv -> sv.toInt());
  parser.rule(RuleId.Sum.class).action(sv -> (Integer)sv.get(0) + (Integer)sv.get(1));
  ```

## API Usage

```java
// Basic usage - CST/AST parsing
var parser = PegParser.fromGrammar("""
    Number <- < [0-9]+ >
    %whitespace <- [ \\t]*
    """).unwrap();

Result<CstNode> cst = parser.parseCst("123");      // Lossless tree
Result<AstNode> ast = parser.parseAst("123");      // Optimized tree

// Parsing with actions - returns semantic values
var calculator = PegParser.fromGrammar("""
    Sum <- Number '+' Number { return (Integer)$1 + (Integer)$2; }
    Number <- < [0-9]+ > { return sv.toInt(); }
    %whitespace <- [ ]*
    """).unwrap();

Result<Object> result = calculator.parse("3 + 5");  // Returns 8

// Configuration
var parser = PegParser.builder(grammar)
    .packrat(true)
    .trivia(true)
    .build()
    .unwrap();

// Source generation - standalone parser
Result<String> source = PegParser.generateParser(
    grammarText,
    "com.example.parser",
    "MyParser"
);
// Write source to file, compile with javac

// Source generation with ADVANCED error reporting (Rust-style diagnostics)
import org.pragmatica.peg.generator.ErrorReporting;

Result<String> source = PegParser.generateCstParser(
    grammarText,
    "com.example.parser",
    "MyParser",
    ErrorReporting.ADVANCED  // Enables parseWithDiagnostics() method
);
```

## Action API

Actions have access to `SemanticValues sv`:
- `sv.token()` / `$0` - matched text (the raw input that was parsed)
- `sv.get(0)` / `$1` - first child's semantic value
- `sv.get(1)` / `$2` - second child's semantic value
- `sv.toInt()` - parse matched text as integer
- `sv.toDouble()` - parse as double
- `sv.size()` - number of child values
- `sv.values()` - all child values as List

Note: `$1`, `$2`, etc. use 1-based indexing (like regex groups), while `sv.get()` uses 0-based indexing.

## Trivia Handling

Both runtime and generated parsers support trivia (whitespace/comments) collection for lossless CST:

### Design
- Each match of the INNER whitespace expression creates one Trivia item
- For `%whitespace <- [ \t]+`, inner is `[ \t]`, so each char is separate trivia
- For `%whitespace <- ([ \t]+ / Comment)+`, inner is `[ \t]+ / Comment`, so each run is one trivia

### Trivia Types (sealed)
- `Trivia.Whitespace` - spaces, tabs, newlines
- `Trivia.LineComment` - `// ...` style comments
- `Trivia.BlockComment` - `/* ... */` style comments

### Access
```java
CstNode node = parser.parseCst("  42  ").unwrap();
List<Trivia> leading = node.leadingTrivia();   // Before node
List<Trivia> trailing = node.trailingTrivia(); // After node (at EOF)
```

### Classification
Trivia is classified based on content:
- Starts with `//` → LineComment
- Starts with `/*` → BlockComment
- Otherwise → Whitespace

## Error Recovery

Advanced error recovery with Rust-style diagnostic messages.

### Recovery Strategies
- `NONE` - Fail immediately on first error
- `BASIC` - Report error with context but stop parsing
- `ADVANCED` - Continue parsing, collect multiple errors, insert Error nodes

### API Usage
```java
var parser = PegParser.builder(grammar)
    .recovery(RecoveryStrategy.ADVANCED)
    .build()
    .unwrap();

// Parse with diagnostics
var result = parser.parseCstWithDiagnostics(input);

if (result.hasErrors()) {
    // Rust-style formatted output
    System.out.println(result.formatDiagnostics("input.txt"));
}

// CST may contain Error nodes for unparseable regions
if (result.hasNode()) {
    CstNode cst = result.node();
}
```

### Diagnostic Output (Rust-style)
```
error: unexpected input
  --> input.txt:1:6
   |
 1 | abc, @@@, def
   |      ^ found '@'
   |
   = help: expected [a-z]+
```

### Recovery Points
Parser recovers at: `,`, `;`, `}`, `)`, `]`, newline

## Test Coverage (308 tests)

### Grammar Parser Tests (17 tests)
- Simple rules, actions, sequences, choices
- Lookahead predicates, repetition operators
- Token boundaries, whitespace directive
- Case-insensitive matching, named captures
- Grammar validation (undefined rule references)

### Parsing Engine Tests (29 tests)
- Literals, character classes, negated classes
- Any character, sequences, choices
- Zero-or-more, one-or-more, optional
- Bounded repetition, lookahead predicates
- Token boundaries, rule references
- Whitespace skipping, case-insensitive
- Cut operator (^/↑) - prevents backtracking after commit

### Action Tests (6 tests)
- Simple action returning integer
- Actions with child values (sum)
- Token text transformation
- List building
- No action returns CST node

### Generator Tests (18 tests)
- Simple literal generates valid Java
- Whitespace handling
- Action code inclusion
- All quantifiers generate loops
- Only depends on pragmatica-lite
- ErrorReporting.BASIC mode (no diagnostics)
- ErrorReporting.ADVANCED mode (Rust-style diagnostics)
- parseWithDiagnostics() method generation

### Internal Type Tests (24 tests)
- **ParseResultTest** (10 tests): CutFailure, PredicateSuccess, Ignored, semantic values
- **ParsingContextTest** (8 tests): Packrat cache hit/miss, cache disabled behavior
- **GeneratedParserDiagnosticsTest** (6 tests): ADVANCED diagnostics in generated parsers

### Example Tests (135 tests)
- **ErrorRecovery** (12 tests): Recovery strategies, diagnostic formatting, CST error nodes
- **Calculator** (6 tests): Number parsing, addition, multiplication, boolean/double types
- **JSON** (11 tests): CST parsing of JSON values, objects, arrays, nested structures
- **S-Expression** (11 tests): Lisp-like syntax, nested lists, atoms, symbols
- **CSV** (8 tests): Field parsing, empty fields, spaces preserved
- **Source Generation** (11 tests): Standalone parser generation, all operators
- **CutOperatorRegression** (16 tests): Cut operator regression tests
- **Java25Grammar** (60 tests): Full Java 25 syntax including modules, var, patterns, text blocks

### Trivia Tests (19 tests)
- **TriviaTest** (13 tests): Runtime trivia - leading, trailing, mixed, comments
- **GeneratedParserTriviaTest** (6 tests): Generated parser trivia consistency

### Error Recovery Tests (8 tests)
- Rust-style diagnostic formatting
- Multiple error collection
- Fragment recovery (valid/invalid/valid parsing)
- Error nodes with skipped text
- Recovery strategies (NONE, BASIC, ADVANCED)

### Edge Case Tests (24 tests)
- Null actions, token boundaries, complex grammars
- Recursive parsing, repetition, predicates
- Character classes, unicode, ignore operator

## Type Summary

### Expression Types (sealed)
- `Literal`, `CharClass`, `Any`, `Reference`
- `Sequence`, `Choice`
- `ZeroOrMore`, `OneOrMore`, `Optional`, `Repetition`
- `And`, `Not`
- `TokenBoundary`, `Ignore`, `Capture`, `BackReference`
- `Cut`, `Group`

### CstNode Types (sealed)
- `Terminal` - leaf with text
- `NonTerminal` - interior with children
- `Token` - result of < > operator
- `Error` - unparseable region (error recovery)

### ParseResult Types (sealed)
- `Success` - matched with node and optional semantic value
- `Failure` - no match at position
- `CutFailure` - failure after cut, prevents trying alternatives
- `PredicateSuccess` - predicate matched (no consumption)
- `Ignored` - matched but no node

## Java 25 Grammar: Contextual Keywords

Java has two types of reserved words:

### Hard Keywords (always reserved)
These can NEVER be used as identifiers:
```
abstract, assert, boolean, break, byte, case, catch, char, class, const,
continue, default, do, double, else, enum, extends, false, final, finally,
float, for, goto, if, implements, import, instanceof, int, interface, long,
native, new, null, package, private, protected, public, return, short,
static, strictfp, super, switch, synchronized, this, throw, throws,
transient, true, try, void, volatile, while
```

### Contextual Keywords (context-dependent)
These are only reserved in specific syntactic positions and CAN be used as identifiers elsewhere:

| Keyword | Reserved Context | Can be identifier in |
|---------|-----------------|---------------------|
| `var` | Local variable type inference | Method/field names, parameters |
| `yield` | Switch expression | Method/field names, parameters |
| `record` | Type declaration | Method/field names, parameters |
| `sealed`, `non-sealed` | Class modifier | Method/field names, parameters |
| `permits` | Sealed class clause | Method/field names, parameters |
| `when` | Pattern guard | Method/field names, parameters |
| `module`, `open`, `opens`, `requires`, `exports`, `provides`, `uses`, `with`, `to` | Module declarations | Regular code |

### Grammar Design
The `Keyword` rule should only include hard keywords. Contextual keywords are matched by their specific rules (e.g., `PermitsClause <- 'permits' TypeList`) and fall through to `Identifier` elsewhere. PEG's ordered choice handles this naturally.

## References

- [cpp-peglib](https://github.com/yhirose/cpp-peglib) - Reference implementation
- [PEG Paper](https://bford.info/pub/lang/peg.pdf) - Bryan Ford's original paper
- [Packrat Parsing](https://bford.info/pub/lang/packrat-icfp02.pdf) - Memoization technique

## Build Commands

```bash
mvn compile          # Compile
mvn test             # Run tests (308 passing)
mvn verify           # Full verification
```
