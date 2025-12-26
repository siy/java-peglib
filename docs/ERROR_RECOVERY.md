# Error Recovery in Peglib

Peglib provides advanced error recovery capabilities inspired by modern compiler design, with Rust-style diagnostic formatting for user-friendly error messages.

## Table of Contents

1. [Overview](#overview)
2. [Recovery Strategies](#recovery-strategies)
3. [Basic Usage](#basic-usage)
4. [Diagnostic API](#diagnostic-api)
5. [Error Nodes in CST](#error-nodes-in-cst)
6. [Rust-Style Formatting](#rust-style-formatting)
7. [Recovery Points](#recovery-points)
8. [IDE Integration](#ide-integration)
9. [Best Practices](#best-practices)
10. [Examples](#examples)

## Overview

Error recovery allows parsers to continue after encountering syntax errors, collecting multiple diagnostics in a single pass. This is essential for:

- **IDEs and editors**: Show all errors at once, not just the first one
- **Linters**: Report comprehensive issues without multiple runs
- **Compilers**: Provide better user experience with batch error reporting
- **Language servers**: Support LSP diagnostics protocol

## Recovery Strategies

Peglib supports three recovery strategies:

### `RecoveryStrategy.NONE`

```java
var parser = PegParser.builder(grammar)
    .recovery(RecoveryStrategy.NONE)
    .build()
    .unwrap();
```

- Fails immediately on first error
- Fastest option when you only need success/failure
- Use for: validation, quick checks

### `RecoveryStrategy.BASIC`

```java
var parser = PegParser.builder(grammar)
    .recovery(RecoveryStrategy.BASIC)
    .build()
    .unwrap();
```

- Reports error with context information
- Stops parsing after first error
- Use for: simple error messages, command-line tools

### `RecoveryStrategy.ADVANCED`

```java
var parser = PegParser.builder(grammar)
    .recovery(RecoveryStrategy.ADVANCED)
    .build()
    .unwrap();
```

- Continues parsing after errors
- Collects all diagnostics
- Inserts `Error` nodes in CST for unparseable regions
- Use for: IDEs, linters, language servers

## Basic Usage

### Parsing with Diagnostics

```java
var parser = PegParser.builder(grammar)
    .recovery(RecoveryStrategy.ADVANCED)
    .build()
    .unwrap();

// Parse and get diagnostics
ParseResultWithDiagnostics result = parser.parseCstWithDiagnostics(input);

// Check for errors
if (result.hasErrors()) {
    // Print Rust-style formatted errors
    System.out.println(result.formatDiagnostics("input.txt"));
}

// Access partial CST (may contain Error nodes)
if (result.hasNode()) {
    CstNode cst = result.node();
    // Process the tree...
}

// Get error statistics
int errors = result.errorCount();
int warnings = result.warningCount();
```

### ParseResultWithDiagnostics API

```java
public record ParseResultWithDiagnostics(
    CstNode node,              // Parsed CST (may contain Error nodes)
    List<Diagnostic> diagnostics,  // All collected diagnostics
    String source              // Original source text
) {
    // Check if parsing succeeded without errors
    boolean isSuccess();

    // Check if there were any errors
    boolean hasErrors();

    // Check if a CST was produced (even partial)
    boolean hasNode();

    // Format all diagnostics in Rust style
    String formatDiagnostics(String filename);

    // Get counts
    int errorCount();
    int warningCount();
}
```

## Diagnostic API

### Creating Diagnostics

```java
// Simple error
var error = Diagnostic.error("unexpected token", span);

// Error with code (like Rust's E0001)
var error = Diagnostic.error("E0001", "type mismatch", span);

// Warning
var warning = Diagnostic.warning("unused variable", span);

// With additional context
var diagnostic = Diagnostic.error("undefined variable", span)
    .withLabel("'foo' not found in scope")
    .withHelp("did you mean 'bar'?")
    .withNote("variables must be declared before use");

// Multiple labels for complex errors
var diagnostic = Diagnostic.error("type mismatch", span)
    .withLabel("expected 'int'")
    .withSecondaryLabel(otherSpan, "found 'string'");
```

### Diagnostic Record

```java
public record Diagnostic(
    Severity severity,     // ERROR, WARNING, INFO, HINT
    String code,           // Optional error code (e.g., "E0001")
    String message,        // Primary error message
    SourceSpan span,       // Location in source
    List<Label> labels,    // Additional labeled spans
    List<String> notes     // Help text, suggestions
) {
    // Factory methods
    static Diagnostic error(String message, SourceSpan span);
    static Diagnostic error(String code, String message, SourceSpan span);
    static Diagnostic warning(String message, SourceSpan span);

    // Builders
    Diagnostic withLabel(String message);
    Diagnostic withSecondaryLabel(SourceSpan span, String message);
    Diagnostic withNote(String note);
    Diagnostic withHelp(String help);

    // Formatting
    String format(String source, String filename);
    String formatSimple();  // One-line format
}
```

### Severity Levels

```java
public enum Severity {
    ERROR,   // Parsing failed at this point
    WARNING, // Suspicious but valid syntax
    INFO,    // Informational message
    HINT     // Suggestion for improvement
}
```

## Error Nodes in CST

When using `ADVANCED` recovery, the CST may contain `Error` nodes:

```java
public sealed interface CstNode {
    // ... other node types ...

    record Error(
        SourceSpan span,       // Location of error
        String skippedText,    // The unparseable input
        String expected,       // What parser expected
        List<Trivia> leadingTrivia,
        List<Trivia> trailingTrivia
    ) implements CstNode {
        @Override
        public String rule() {
            return "<error>";
        }
    }
}
```

### Processing Error Nodes

```java
void processNode(CstNode node) {
    switch (node) {
        case CstNode.Terminal t -> handleTerminal(t);
        case CstNode.NonTerminal nt -> {
            for (var child : nt.children()) {
                processNode(child);
            }
        }
        case CstNode.Token tok -> handleToken(tok);
        case CstNode.Error err -> {
            System.err.println("Error at " + err.span() +
                ": expected " + err.expected() +
                ", found: " + err.skippedText());
        }
    }
}
```

### Finding Error Nodes

```java
List<CstNode.Error> findAllErrors(CstNode node) {
    var errors = new ArrayList<CstNode.Error>();
    findErrorsRecursive(node, errors);
    return errors;
}

void findErrorsRecursive(CstNode node, List<CstNode.Error> errors) {
    if (node instanceof CstNode.Error err) {
        errors.add(err);
    } else if (node instanceof CstNode.NonTerminal nt) {
        for (var child : nt.children()) {
            findErrorsRecursive(child, errors);
        }
    }
}
```

## Rust-Style Formatting

Diagnostics format like Rust compiler errors:

```
error: unexpected token
  --> input.txt:3:15
   |
 3 |     let x = @invalid;
   |             ^^^^^^^^ expected expression
   |
   = help: expressions can start with identifiers, literals, or '('
```

### Format Components

1. **Header**: `error[E0001]: message`
   - Severity (error/warning/info/hint)
   - Optional error code in brackets
   - Primary message

2. **Location**: `--> filename:line:column`

3. **Source Context**: Shows the relevant line(s) with line numbers

4. **Underlines**:
   - `^^^^^` for primary label
   - `-----` for secondary labels

5. **Notes**: `= help:` or `= note:` suggestions

### Multi-line Errors

For errors spanning multiple lines:

```
error: unclosed string literal
  --> input.txt:5:10
   |
 5 |     msg = "hello
   |           ^^^^^^^
 6 |     world
   |     ^^^^^
 7 |     ";
   |     ^^ string literal spans 3 lines
   |
   = help: use \n for newlines or close the string
```

## Recovery Points

The parser recovers at these synchronization points:

| Character | Common Use |
|-----------|------------|
| `,` | List separators |
| `;` | Statement terminators |
| `}` | Block endings |
| `)` | Parenthesis close |
| `]` | Bracket close |
| `\n` | Line breaks |

When an error is encountered:
1. Parser records the diagnostic
2. Skips input until a recovery point
3. Creates an `Error` node for skipped content
4. Resumes parsing after the recovery point

## IDE Integration

### Language Server Protocol (LSP)

Convert diagnostics to LSP format:

```java
// LSP uses 0-based line/column numbers
record LspDiagnostic(
    int startLine, int startChar,
    int endLine, int endChar,
    int severity,  // 1=Error, 2=Warning, 3=Info, 4=Hint
    String message,
    String source   // "peglib"
) {}

List<LspDiagnostic> toLsp(ParseResultWithDiagnostics result) {
    return result.diagnostics().stream()
        .map(d -> new LspDiagnostic(
            d.span().start().line() - 1,
            d.span().start().column() - 1,
            d.span().end().line() - 1,
            d.span().end().column() - 1,
            switch (d.severity()) {
                case ERROR -> 1;
                case WARNING -> 2;
                case INFO -> 3;
                case HINT -> 4;
            },
            d.message(),
            "peglib"
        ))
        .toList();
}
```

### Syntax Highlighting

Extract error ranges for editor highlighting:

```java
record ErrorRange(int startOffset, int endOffset) {}

List<ErrorRange> getErrorRanges(ParseResultWithDiagnostics result) {
    return result.diagnostics().stream()
        .map(d -> new ErrorRange(
            d.span().start().offset(),
            d.span().end().offset()
        ))
        .toList();
}
```

### Quick Fixes

Use diagnostic context for code actions:

```java
record QuickFix(String title, String replacement, SourceSpan span) {}

List<QuickFix> suggestFixes(Diagnostic d) {
    var fixes = new ArrayList<QuickFix>();

    // Example: suggest fixes based on notes
    for (var note : d.notes()) {
        if (note.startsWith("help: did you mean")) {
            var suggestion = extractSuggestion(note);
            fixes.add(new QuickFix(
                "Replace with '" + suggestion + "'",
                suggestion,
                d.span()
            ));
        }
    }

    return fixes;
}
```

## Best Practices

### 1. Choose the Right Strategy

```java
// For validation (fast fail)
.recovery(RecoveryStrategy.NONE)

// For CLI tools (single error)
.recovery(RecoveryStrategy.BASIC)

// For IDEs/editors (all errors)
.recovery(RecoveryStrategy.ADVANCED)
```

### 2. Provide Helpful Messages

```java
// Bad: generic message
Diagnostic.error("syntax error", span);

// Good: specific message with context
Diagnostic.error("expected closing parenthesis", span)
    .withLabel("opened here")
    .withSecondaryLabel(openParenSpan, "unmatched '('")
    .withHelp("add ')' to close the expression");
```

### 3. Use Error Codes for Documentation

```java
// Enable users to look up detailed explanations
Diagnostic.error("E0123", "type mismatch in assignment", span)
    .withNote("see https://docs.example.com/errors/E0123");
```

### 4. Handle Partial Results Gracefully

```java
var result = parser.parseCstWithDiagnostics(input);

if (result.isSuccess()) {
    // Perfect parse - process normally
    process(result.node());
} else if (result.hasNode()) {
    // Partial parse - process what we got
    processPartial(result.node());
    reportErrors(result.diagnostics());
} else {
    // Complete failure
    reportErrors(result.diagnostics());
}
```

### 5. Limit Error Cascades

When one error causes many follow-on errors, consider limiting output:

```java
var diagnostics = result.diagnostics();
int maxErrors = 10;

if (diagnostics.size() > maxErrors) {
    diagnostics.stream()
        .limit(maxErrors)
        .forEach(d -> System.out.println(d.format(source, filename)));
    System.out.printf("... and %d more errors%n",
        diagnostics.size() - maxErrors);
}
```

## Examples

### Simple List Parser

```java
var grammar = """
    List <- Item (',' Item)*
    Item <- < [a-z]+ >
    %whitespace <- [ ]*
    """;

var parser = PegParser.builder(grammar)
    .recovery(RecoveryStrategy.ADVANCED)
    .build()
    .unwrap();

var input = "abc, 123, def, @@@, ghi";
var result = parser.parseCstWithDiagnostics(input);

System.out.println(result.formatDiagnostics("list.txt"));
// error: unexpected input
//   --> list.txt:1:6
//    |
//  1 | abc, 123, def, @@@, ghi
//    |      ^ found '1'
//    |
//    = help: expected [a-z]+
//
// error: unexpected input
//   --> list.txt:1:16
//    |
//  1 | abc, 123, def, @@@, ghi
//    |                ^ found '@'
//    |
//    = help: expected [a-z]+
```

### JSON Parser with Recovery

```java
var grammar = """
    JSON <- Value
    Value <- Object / Array / String / Number / Bool / Null
    Object <- '{' (Pair (',' Pair)*)? '}'
    Pair <- String ':' Value
    Array <- '[' (Value (',' Value)*)? ']'
    String <- '"' < [^"]* > '"'
    Number <- < '-'? [0-9]+ ('.' [0-9]+)? >
    Bool <- 'true' / 'false'
    Null <- 'null'
    %whitespace <- [ \\t\\n\\r]*
    """;

var parser = PegParser.builder(grammar)
    .recovery(RecoveryStrategy.ADVANCED)
    .build()
    .unwrap();

var input = """
    {
        "name": "test"
        "value": 123
    }
    """;

var result = parser.parseCstWithDiagnostics(input);
System.out.println(result.formatDiagnostics("data.json"));
```

### Expression Parser

```java
var grammar = """
    Expr <- Term (('+' / '-') Term)*
    Term <- Factor (('*' / '/') Factor)*
    Factor <- Number / '(' Expr ')' / Identifier
    Number <- < [0-9]+ >
    Identifier <- < [a-zA-Z_][a-zA-Z0-9_]* >
    %whitespace <- [ \\t]*
    """;

var parser = PegParser.builder(grammar)
    .recovery(RecoveryStrategy.ADVANCED)
    .build()
    .unwrap();

// Missing operand
var result = parser.parseCstWithDiagnostics("1 + + 2");
System.out.println(result.formatDiagnostics("expr.txt"));
```

See `ErrorRecoveryExample.java` for more comprehensive examples.
