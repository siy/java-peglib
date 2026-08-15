# Error Recovery in Peglib

## Table of Contents

1. [Overview](#overview)
2. [How recovery works](#how-recovery-works)
3. [Basic usage](#basic-usage)
4. [The Diagnostic record](#the-diagnostic-record)
5. [Error nodes in the CST](#error-nodes-in-the-cst)
6. [Rust-style formatting](#rust-style-formatting)
7. [Choosing recovery points](#choosing-recovery-points)
8. [IDE integration](#ide-integration)
9. [Best practices](#best-practices)

---

## Overview

A parser that stops at the first syntax error is useless to an editor. Peglib always recovers:
a parse produces **both** a CST and a list of diagnostics, and it never throws on malformed
input.

Since 0.6.0 there is exactly **one** recovery mechanism, always on. The 0.5.x
`RecoveryStrategy` enum (`NONE` / `BASIC` / `ADVANCED`) and the builder knobs that selected it
are gone — there is nothing to configure. If you want fail-fast behaviour, check
`result.isSuccess()` at the call site.

---

## How recovery works

Panic-mode synchronization:

1. The parser hits a token it cannot match.
2. It walks forward to the next token in the **sync set** — the rule's `%recover` set if it
   declares one, otherwise the default `;` `,` `}` `)` `]`.
3. It emits an `Error` node covering the skipped range.
4. It records a `Diagnostic`.
5. It resumes parsing from the sync point.

Because the `Error` node spans the skipped text, `cst.reconstruct()` still returns the original
input byte-for-byte even for a file that failed to parse. The formatter depends on this.

---

## Basic usage

```java
var parser = PegParser.fromGrammar(grammarText).unwrap();
ParseResult result = parser.parse(input);

if (result.isSuccess()) {
    process(result.cst());
}

for (Diagnostic d : result.diagnostics()) {
    System.err.println(d.formatRustStyle("file.java", input));
}
```

`ParseResult` is a record with two components:

```java
public record ParseResult(CstArray cst, List<Diagnostic> diagnostics) {
    public boolean isSuccess();   // no ERROR-severity diagnostics
    public boolean hasErrors();
}
```

`diagnostics()` is always present — empty means a clean parse. `cst()` is always present too,
including on a failed parse, where it carries `Error` nodes.

### Capping diagnostics

A badly broken file can produce thousands of cascading diagnostics. The second `parse` overload
caps them:

```java
var capped = parser.parse(input, 100);   // stop recording after 100
var none   = parser.parse(input, 0);     // record none; CST still built
var all    = parser.parse(input, -1);    // negative means no cap
```

The cap bounds only *recording*. Parsing and recovery run to completion either way, so the CST
is identical.

---

## The Diagnostic record

```java
public record Diagnostic(Severity severity,
                         int offset,
                         int length,
                         String message,
                         String expected,
                         String found) {
    public static Diagnostic error(int offset, int length, String message);
    public static Diagnostic error(int offset, int length, String message,
                                   String expected, String found);
    public String formatRustStyle(String filename, String input);
}
```

Positions are **byte offsets into the input**, not line/column pairs. The 0.5.x `Diagnostic`
carried a `SourceSpan` with `line()` / `column()` accessors; those types are gone. Offsets are
cheaper to produce and lossless, and line/column is a presentation concern — see
[IDE integration](#ide-integration) for the conversion.

`Severity` is `ERROR`, `WARNING` or `INFO`. Only `ERROR` makes `isSuccess()` false.

`expected` and `found` are optional context used by the Rust-style formatter; the two-argument
`error(...)` factory leaves them empty.

---

## Error nodes in the CST

`CstNode` is a sealed interface with three views:

```java
switch (cst.viewAt(idx)) {
    case CstNode.Branch b -> descend(b);
    case CstNode.Leaf l   -> emit(l);
    case CstNode.Error e  -> reportSkipped(e);
}
```

Views are flyweights — `(int index, CstArray array)` — so matching on them allocates nothing
meaningful and every accessor delegates to the flat array.

To find error nodes without pattern matching, `cst.isError(idx)` is a direct predicate:

```java
// every error node in the tree
IntStream errors = cst.descendants(cst.rootIndex())
                      .filter(cst::isError);

// the text each one swallowed
errors.forEach(idx -> System.out.println(cst.textAt(idx)));
```

An `Error` node is a normal node otherwise: it has a token span, participates in
`children()` / `descendants()`, and contributes its original text to `reconstruct()`.

---

## Rust-style formatting

```java
System.err.println(d.formatRustStyle("Example.java", input));
```

```
error: expected ';'
 --> Example.java:3:18
  |
3 |     int x = 42
  |               ^ expected ';', found '}'
  |
```

The formatter derives line and column from the offset against the input you pass, so the
`input` argument must be the **same string that was parsed** — passing a different revision
produces correct-looking output pointing at the wrong place.

---

## Choosing recovery points

The default sync set (`;` `,` `}` `)` `]`) suits C-family languages. Override it per rule:

```peg
%recover [;] Stmt
%recover [}] Block
```

Declare the set at the boundary you want to resume at. A sync set that is too broad skips past
useful structure; too narrow and the parser runs to end-of-file looking for it.

Recovery fires at the top-level loop and dispatches on the kind of the rule whose failure was
**deepest by offset**, so the sync set that applies is the one on the innermost failing rule —
not necessarily the rule you were thinking about when you wrote the directive. See
`PerRuleRecoverDirectiveTest` for the exact dispatch contract and its limits.

---

## IDE integration

### Converting offsets to line/column

Diagnostics carry offsets; LSP wants zero-based line/character. Precompute line starts once per
document rather than scanning per diagnostic:

```java
static int[] lineStarts(String input) {
    var starts = new ArrayList<Integer>();
    starts.add(0);
    for (var i = 0; i < input.length(); i++) {
        if (input.charAt(i) == '\n') {
            starts.add(i + 1);
        }
    }
    return starts.stream().mapToInt(Integer::intValue).toArray();
}

static int[] lineCol(int[] starts, int offset) {
    var lo = Arrays.binarySearch(starts, offset);
    var line = lo >= 0 ? lo : -lo - 2;
    return new int[] {line, offset - starts[line]};
}
```

### Publishing diagnostics

```java
var starts = lineStarts(input);

List<LspDiagnostic> lsp =
    result.diagnostics()
          .stream()
          .map(d -> {
              var from = lineCol(starts, d.offset());
              var to   = lineCol(starts, d.offset() + d.length());
              return new LspDiagnostic(from[0], from[1], to[0], to[1],
                                       severityOf(d.severity()), d.message());
          })
          .toList();
```

### Highlighting broken regions

Error nodes give you the ranges an editor should grey out or underline, independent of the
diagnostics list:

```java
record Range(int start, int end) {}

List<Range> brokenRanges(CstArray cst) {
    return cst.descendants(cst.rootIndex())
              .filter(cst::isError)
              .mapToObj(idx -> new Range(cst.spanStart(idx), cst.spanEnd(idx)))
              .toList();
}
```

---

## Best practices

**Check `isSuccess()`, don't count diagnostics.** A parse can record `WARNING` or `INFO`
diagnostics and still be a complete, usable tree. `isSuccess()` is false only for `ERROR`.

**Use the CST even when the parse failed.** That is the point of recovery. A file with one bad
statement still yields a tree whose other statements are correct, which is what completion and
navigation need.

**Cap diagnostics for machine consumers.** A UI showing 4,000 cascading errors is worse than one
showing 100. Cap at the call site rather than filtering afterwards — capping stops the recording
work.

**Declare `%recover` at real boundaries.** Statement and block terminators make good sync points
because they are unambiguous and frequent. Sync sets built from operator characters tend to
resume mid-expression and cascade.

**Do not treat a duplicated token in output as a recovery bug.** If reconstructed text contains a
token twice, the parse failed and an `Error` node covered the region — check the diagnostic count
before suspecting the CST builder.
