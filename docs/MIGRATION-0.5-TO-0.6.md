# peglib 0.5.x → 0.6.0 Migration Guide

peglib 0.6.0 is a clean-slate redesign focused on lint and format workloads. The grammar surface is preserved (cpp-peglib syntax); the runtime, API, and CST shape change substantially. This guide walks each breaking change, gives copy-pasteable before/after code, and estimates migration effort per use case.

For the rationale behind every decision listed here, see [`ARCHITECTURE-0.6.0.md`](ARCHITECTURE-0.6.0.md).

---

## What changed at a glance

| Concern | 0.5.x | 0.6.0 |
|---|---|---|
| Parsing engine | Interpreter (`PegEngine`) + optional source generator | Generator-only; `PegParser.fromGrammar(g)` does generate-compile-cache internally |
| Architecture | Per-character PEG | Two-phase: lexer (DFA) → parser (recursive descent over tokens) |
| Tree types | CST + AST | CST only |
| CST data shape | Sealed records (`Terminal`, `NonTerminal`, `Token`, `Error`) | Flat `int[]` (`CstArray`) + thin views (`CstNode.Branch`/`Leaf`/`Error`) |
| Trivia | Buffer-driven attribution + `triviaPostPass` flag | Trivia tokens live in `TokenArray`; positional accessors |
| Actions | Inline `{ ... }` Java blocks compiled at runtime | Removed; per-grammar `GVisitor<T>` stub generated |
| Error recovery | `RecoveryStrategy` enum (NONE/BASIC/ADVANCED), `ErrorReporting` enum at gen-time | One always-on panic-mode mechanism; diagnostics always present |
| Configuration | `ParserConfig` record (~17 fields) | Grammar directives + single optional `maxDiagnostics` parameter |
| Incremental engine | `IncrementalSession` + stable `long` IDs + `LongLongMap` + `Cursor` | `IncrementalParser` thin wrapper; token index serves as identity |
| Entry point | `org.pragmatica.peg.PegParser` | `org.pragmatica.peg.PegParser` (the `.v6` implementation package collapsed into `org.pragmatica.peg` in 0.7.0) |
| Result type | `Result<CstNode>`, `Result<AstNode>`, `Result<Object>`, `ParseResultWithDiagnostics` | Single `ParseResult(CstArray cst, List<Diagnostic> diagnostics)` |

---

## Breaking API changes per package

### `org.pragmatica.peg`

**`PegParser`** — entry point preserved by name; signatures changed.

Removed:
- `fromGrammar(String, ParserConfig)`, `fromGrammar(Grammar)`, `fromGrammar(Grammar, ParserConfig)` and all overloads taking `Actions` or `GrammarSource`
- `fromGrammarWithoutActions(...)`
- `generateParser(...)`, `generateCstParser(...)` (all overloads)
- `PegParser.builder(...)` and the nested `Builder` class

New:
- `PegParser.fromGrammar(String grammarText)` — runs classify → DFA → generate-and-compile-lexer → generate-and-compile-parser; result cached by exact grammar text

The 0.5.x `Builder` knobs (`packrat`, `recovery`, `trivia`, `triviaPostPass`) are all gone. Packrat was dropped entirely — the tokens-first design made it unnecessary — recovery is always on, trivia is always captured, and the post-pass mechanism no longer exists.

### `org.pragmatica.peg.parser`

Removed entirely:
- `PegEngine` (the interpreter; ~1900 LOC)
- `Parser` interface — replaced by the concrete `org.pragmatica.peg.Parser` class returned by `PegParser.fromGrammar`
- `ParserConfig` record and all 17 fields (packratEnabled, recoveryStrategy, captureTrivia, fastTrackFailure, literalFailureCache, charClassFailureCache, bulkAdvanceLiteral, skipWhitespaceFastPath, reuseEndLocation, choiceDispatch, markResetChildren, inlineLocations, selectivePackrat, packratSkipRules, mutableParseResult, tokenFastPath, triviaPostPass)
- `ParsingContext` (mutable parsing state with packrat cache)
- `ParseResult` sealed types (`Success`, `Failure`, `CutFailure`, `PredicateSuccess`, `Ignored`)
- `ParseResultWithDiagnostics`
- `ParseMode` (standard / withActions / noWhitespace)

New: `org.pragmatica.peg.cst.ParseResult` is a record with two components — `CstArray cst` and `List<Diagnostic> diagnostics`. There is no per-call configuration record; `Parser.parse(String)` and `Parser.parse(String, int maxDiagnostics)` are the only two methods. The cap is honoured: `0` records no diagnostics, a negative value means no cap.

### `org.pragmatica.peg.action`

Removed entirely:
- `Action` (functional interface)
- `SemanticValues` (`$0` / `$N` plumbing)
- `ActionCompiler` (JDK Compiler API integration)
- `Actions` (immutable lambda-attachment builder)
- `RuleId` (type-safe rule identification)

There is no replacement at the action layer. CST → domain transformation is now a separate concern, performed by user code via the per-grammar generated `GVisitor<T>` stub. See "Pattern: Action-based semantic transform" below.

### `org.pragmatica.peg.source`

Removed:
- `AstNode` sealed interface and all variants (`Literal`, `Identifier`, `BinaryOp`, etc.)
- `CstNode.Terminal`, `CstNode.NonTerminal`, `CstNode.Token`, `CstNode.Error` as data-bearing records
- `Trivia` sealed interface (`Whitespace`, `LineComment`, `BlockComment`)
- `StringSpan` (CharSequence view with lazy String materialization)
- `TriviaPostPass` (entire 689-LOC class plus the embedded version generated parsers carried)
- `IdGenerator` and `PerSessionCounter`
- `SourceLocation`, `SourceSpan` records

New: `org.pragmatica.peg.cst`:
- `CstArray` — flat `int[]` data structure (8 ints per node, ~32 bytes)
- `CstNode` sealed interface with `Branch`, `Leaf`, `Error` view variants. Views carry only `(int index, CstArray array)` and delegate every accessor to the array.
- `ParseResult` record
- `CstArrayBuilder` — internal-facing builder used by generated parsers

Trivia kinds (whitespace / line comment / block comment) are now token kinds in `TokenArray`. `TokenArray.isTrivia(i)` returns true for any of them.

### `org.pragmatica.peg.error`

Removed:
- `RecoveryStrategy` enum (NONE / BASIC / ADVANCED)
- `ParseError` sealed interface
- The 0.5.x `Diagnostic` record (with `severity`, `message`, `line`, `column`, `expected`, `found`, `helpText`)

New: `org.pragmatica.peg.diagnostic.Diagnostic` is a record with `(severity, offset, length, message, expected, found)`. The Rust-style formatter is preserved as `Diagnostic.formatRustStyle(filename, input)`. The `Severity` enum is `org.pragmatica.peg.diagnostic.Severity`.

### `org.pragmatica.peg.generator`

Removed:
- `ParserGenerator` (single-file emission with embedded engine)
- `ErrorReporting` enum (BASIC / ADVANCED)
- All static `generateParser(...)` / `generateCstParser(...)` entry points on `PegParser`

The 0.6.0 generator emits three artifacts per grammar (`GLexer`, `GParser`, `GVisitor`) and is invoked through `PegParser.fromGrammar` rather than by user code directly. Build-time generation through `peglib-maven-plugin` works — the `generate` goal (renamed from `generate-v6` in 0.7.0) is covered by `MojoIntegrationTest`.

### `peglib-incremental`

Removed:
- `IncrementalSession`, `EditOutcome`, `ReparseOutcome`, `Cursor`
- `TreeSplicer`, `NodeIndex`, `LongLongMap`, `IdGenerator`, `SafePivotAnalyzer`
- Stable `long id` field on `CstNode` records (no replacement; node index in the array IS the identity)

New: `org.pragmatica.peg.incremental.IncrementalParser` — single class, ~150 LOC, holds latest `(input, tokens, cst, diagnostics)` and exposes `edit(offset, oldLen, newText)`.

---

## Common code transformations

### Pattern: Basic CST parsing

Before (0.5.x):
```java
import org.pragmatica.peg.PegParser;
import org.pragmatica.peg.source.CstNode;
import org.pragmatica.lang.Result;

var parser = PegParser.fromGrammar("""
    Number <- < [0-9]+ >
    %whitespace <- [ \\t]*
    """).unwrap();

Result<CstNode> result = parser.parseCst("42");
CstNode cst = result.unwrap();
```

After (0.6.0):
```java
import org.pragmatica.peg.PegParser;
import org.pragmatica.peg.cst.CstArray;
import org.pragmatica.peg.cst.ParseResult;

var parser = PegParser.fromGrammar("""
    Number <- < [0-9]+ >
    %whitespace <- [ \\t]*
    """).unwrap();

ParseResult result = parser.parse("42");
CstArray cst = result.cst();
if (!result.isSuccess()) {
    result.diagnostics().forEach(d ->
        System.err.println(d.formatRustStyle("input", "42")));
}
```

### Pattern: Walking CST with sealed pattern matching

Before (0.5.x):
```java
import org.pragmatica.peg.source.CstNode;

void walk(CstNode node) {
    switch (node) {
        case CstNode.Terminal t -> System.out.println("term: " + t.text());
        case CstNode.NonTerminal nt -> {
            System.out.println("rule: " + nt.ruleName());
            nt.children().forEach(this::walk);
        }
        case CstNode.Token tok -> System.out.println("token: " + tok.text());
        case CstNode.Error err -> System.out.println("error: " + err.text());
    }
}
```

After (0.6.0) — view-based pattern matching:
```java
import org.pragmatica.peg.cst.CstArray;
import org.pragmatica.peg.cst.CstNode;

void walk(CstArray cst, int idx) {
    switch (cst.viewAt(idx)) {
        case CstNode.Branch b -> {
            System.out.println("rule: " + b.kindName());
            b.children().forEach(child -> walk(cst, child));
        }
        case CstNode.Leaf l -> System.out.println("leaf: " + l.text());
        case CstNode.Error e -> System.out.println("error: " + e.text());
    }
}

walk(cst, cst.rootIndex());
```

After (0.6.0) — direct-array hot path:
```java
import org.pragmatica.peg.cst.CstArray;

void walk(CstArray cst, int idx) {
    if (cst.isError(idx)) {
        System.out.println("error: " + cst.textAt(idx));
        return;
    }
    int first = cst.firstChildAt(idx);
    if (first == CstArray.NO_NODE) {
        System.out.println("leaf: " + cst.textAt(idx));
        return;
    }
    System.out.println("rule: " + cst.kindNameAt(idx));
    cst.children(idx).forEach(child -> walk(cst, child));
}
```

### Pattern: Field deconstruction on CST records

Before (0.5.x) — record deconstruction worked:
```java
switch (node) {
    case CstNode.Terminal(long id, String text, SourceSpan span, ...) -> ...
}
```

After (0.6.0) — deconstruction breaks because views don't carry data. Use accessors:
```java
switch (cst.viewAt(idx)) {
    case CstNode.Leaf l -> {
        var text = l.text();
        var start = l.spanStart();
        var end = l.spanEnd();
        // ...
    }
    // ...
}
```

`text()` still returns `CharSequence` (not `String`); call `.toString()` if you need a `String`.

### Pattern: Action-based semantic transform

Before (0.5.x) — inline Java actions:
```java
var calculator = PegParser.fromGrammar("""
    Sum    <- Number '+' Number  { return (Integer)$1 + (Integer)$2; }
    Number <- < [0-9]+ >          { return sv.toInt(); }
    %whitespace <- [ ]*
    """).unwrap();

Object result = calculator.parse("3 + 5").unwrap();  // Integer 8
```

After (0.6.0) — `{ ... }` action blocks are rejected at gen time. Transform the CST with a generated `GVisitor<T>` subclass:
```java
import org.pragmatica.peg.PegParser;
import org.pragmatica.peg.cst.CstArray;
// generated per grammar at build time:
import com.example.gen.GVisitor;

var parser = PegParser.fromGrammar("""
    Sum    <- Number '+' Number
    Number <- < [0-9]+ >
    %whitespace <- [ ]*
    """).unwrap();

var visitor = new GVisitor<Integer>() {
    @Override public Integer visitNumber(CstArray cst, int nodeIdx) {
        return Integer.parseInt(cst.textAt(nodeIdx).toString().trim());
    }
    @Override public Integer visitSum(CstArray cst, int nodeIdx) {
        var children = cst.children(nodeIdx).toArray();
        return visit(cst, children[0]) + visit(cst, children[2]);
    }
};

var result = parser.parse("3 + 5");
Integer total = visitor.visit(result.cst(), result.cst().rootIndex());
```

The visitor stub is generated alongside the lexer and parser; users override only the rules they care about. Default behavior visits children and aggregates via `aggregateResult(T agg, T next)` (rightmost-wins by default).

### Pattern: AST output

Before (0.5.x):
```java
Result<AstNode> ast = parser.parseAst("42");
```

After (0.6.0) — AST type removed. Either walk the CST directly, or build your own AST in a `GVisitor`:
```java
sealed interface MyAst {
    record IntLit(int value) implements MyAst {}
    record Add(MyAst l, MyAst r) implements MyAst {}
}

var visitor = new GVisitor<MyAst>() {
    @Override public MyAst visitNumber(CstArray cst, int idx) {
        return new MyAst.IntLit(Integer.parseInt(cst.textAt(idx).toString().trim()));
    }
    @Override public MyAst visitSum(CstArray cst, int idx) {
        var children = cst.children(idx).toArray();
        return new MyAst.Add(visit(cst, children[0]), visit(cst, children[2]));
    }
};
```

Wrapper-rule collapse (the bulk of what AST conversion did in 0.5.x) is roughly 20 lines of visitor code: in `visitChildren`, return the single child's value when there's exactly one child of interest.

### Pattern: Error recovery

Before (0.5.x) — strategy enum + separate API:
```java
import org.pragmatica.peg.error.RecoveryStrategy;

var parser = PegParser.builder(grammar)
    .recovery(RecoveryStrategy.ADVANCED)
    .build()
    .unwrap();

var result = parser.parseCstWithDiagnostics(input);
if (result.hasErrors()) {
    System.out.println(result.formatDiagnostics("input.txt"));
}
if (result.hasNode()) {
    CstNode cst = result.node();
}
```

After (0.6.0) — diagnostics are always present:
```java
import org.pragmatica.peg.PegParser;

var parser = PegParser.fromGrammar(grammar).unwrap();
var result = parser.parse(input);

result.diagnostics().forEach(d ->
    System.err.println(d.formatRustStyle("input.txt", input)));

CstArray cst = result.cst();      // always present, may contain Error-flagged nodes
boolean ok = result.isSuccess();   // diagnostics().isEmpty()
```

For a fail-fast use case (the old `RecoveryStrategy.NONE`):
```java
var result = parser.parse(input);
if (!result.isSuccess()) {
    throw new IllegalArgumentException(result.diagnostics().getFirst().message());
}
```

### Pattern: ParserConfig usage

Before (0.5.x):
```java
import org.pragmatica.peg.parser.ParserConfig;
import org.pragmatica.peg.error.RecoveryStrategy;

var config = new ParserConfig(
    /* packratEnabled */     true,
    /* recoveryStrategy */   RecoveryStrategy.ADVANCED,
    /* captureTrivia */      true,
    /* fastTrackFailure */   true,
    /* literalFailureCache*/ true,
    /* ... 12 more fields */
);
var parser = PegParser.fromGrammar(grammar, config).unwrap();
```

After (0.6.0) — no config record. Trivia capture and recovery are always-on runtime behaviour; everything else moved to gen-time analysis:
```java
var parser = PegParser.fromGrammar(grammar).unwrap();
var result = parser.parse(input);

// Or with a diagnostic cap (honoured: 0 records none, negative means no cap):
var capped = parser.parse(input, /* maxDiagnostics */ 100);
```

If you previously customised `ParserConfig.recoveryStrategy = RecoveryStrategy.ADVANCED` you don't need to do anything; that's the only mode now. If you previously set `recoveryStrategy = NONE` to fail fast, do the equivalent at the call site by checking `result.isSuccess()`.

### Pattern: Trivia inspection

Before (0.5.x):
```java
import org.pragmatica.peg.source.CstNode;
import org.pragmatica.peg.source.Trivia;

CstNode node = parser.parseCst("  42  ").unwrap();
List<Trivia> leading = node.leadingTrivia();
List<Trivia> trailing = node.trailingTrivia();
for (Trivia t : leading) {
    switch (t) {
        case Trivia.Whitespace ws -> ...
        case Trivia.LineComment lc -> ...
        case Trivia.BlockComment bc -> ...
    }
}
```

After (0.6.0) — trivia is in the token array; access positionally:
```java
ParseResult result = parser.parse("  42  ");
CstArray cst = result.cst();
int rootIdx = cst.rootIndex();

CharSequence leadingText = cst.leadingTriviaText(rootIdx);
CharSequence trailingText = cst.trailingTriviaText(rootIdx);

// Token-level filtering (kind-aware):
var tokens = cst.tokens();
cst.leadingTriviaTokens(rootIdx).forEach(tokIdx -> {
    int kind = tokens.kindAt(tokIdx);
    CharSequence text = tokens.textAt(tokIdx);
    // ... dispatch on kind
});
```

Token kinds for trivia are grammar-specific (assigned by the lexer DFA); `TokenArray.isTrivia(i)` returns true for any of them.

### Pattern: Incremental parsing

Before (0.5.x):
```java
import org.pragmatica.peg.incremental.IncrementalParser;
import org.pragmatica.peg.incremental.IncrementalSession;

var initial = IncrementalParser.initialize(parser, "int x = 1;");
IncrementalSession session = initial.session();
Cursor cursor = initial.cursor();

EditOutcome outcome = session.edit(/* offset */ 8, /* oldLen */ 1, "42");
```

After (0.6.0):
```java
import org.pragmatica.peg.incremental.IncrementalParser;

var inc = new IncrementalParser(parser, "int x = 1;");
ParseResult result = inc.edit(/* offset */ 8, /* oldLen */ 1, "42");
CstArray current = inc.current();
```

The cursor abstraction is gone (offsets are sufficient); stable `long` IDs are gone (token index serves as identity); `IncrementalSession` is gone (the parser holds its own state).

True partial reparse landed in 0.6.1. `edit` re-lexes a window and reparses only the affected `%checkpoint` subtree, falling back to a full reparse when no checkpoint ancestor covers the edit. `IncrementalParser.partialReparseCount()` / `fullReparseCount()` expose which path ran.

---

## Grammar file changes

In nearly all cases: **none**. Grammar files written for 0.5.x parse cleanly under 0.6.0. Specifically these constructs are unchanged:

- `Rule <- Expression` definitions
- All operators: ` ` (sequence), `/` (choice), `*`, `+`, `?`, `&`, `!`, `(...)`, `'lit'`, `"lit"`, `[a-z]`, `[^a-z]`, `.`
- Extensions: `< e >` (token boundary), `~e` (ignore), `'text'i` (case-insensitive), `e{n,m}` (bounded repetition), `$name<e>` / `$name` (named capture / back-reference)
- Cut operator: `^` and `↑` (wired into the generator in 0.6.1; commits to the current Choice alternative)
- Directives: `%whitespace <- ...`, `%recover [chars] Rule`, `%checkpoint Rule`, `%suggest Rule`, `%memo Rule` (all parsed and honoured; `%memo` added in 0.7.1)

The one breaking change is **inline Java action blocks**:

```peg
# 0.5.x — supported
Number <- < [0-9]+ > { return sv.toInt(); }
Sum    <- Number '+' Number { return (Integer)$1 + (Integer)$2; }

# 0.6.0 — `{ ... }` blocks rejected by the generator with a migration message
# pointing to the GVisitor<T> pattern documented above.
```

If your grammar uses action blocks: strip them and move the logic into a generated `GVisitor<T>` subclass.

The new directive added in 0.6.0 is `%checkpoint <Rule>` — declares a rule as an incremental-reparse boundary. Unused outside `IncrementalParser`. Defaulted; explicit declaration is optional.

---

## Performance expectations

Cold compile:
- First call to `PegParser.fromGrammar(g)` for a given grammar text runs the full generate-compile pipeline. Expect a one-time cost in the **hundreds of milliseconds** to roughly **a second** depending on grammar size. Cached afterwards.
- Subsequent `fromGrammar(g)` with the same text: lookup-only, sub-millisecond.
- `peglib-maven-plugin` supports build-time generation via the `generate` goal; production deployments ship pre-compiled `.class` and skip compilation entirely.

Warm parse:
- The 0.6.0 architecture targeted **parity with or faster than javac** on Java parsing. The 0.5.1 baseline (785 ms selfhost / 25 ms reference) carried the per-character interpreter penalty; the lex-then-parse design removes most of it.
- **That target was met.** As of 0.7.1 the 40k-LOC selfhost fixture parses in ~67 ms warm. The
  Phase-C-era caveat about a 5-13× regression on small fixtures is obsolete — it described an
  intermediate state, and the optimisation work it referred to (first-token guard,
  link-on-success CST building, `%memo`) has since landed. Current figures live in the
  CHANGELOG; re-measure rather than quoting any number in this guide.

Memory:
- 0.5.x CST nodes: ~80-200 bytes each (record header + List allocations + per-trivia-list storage)
- 0.6.0 CST nodes: ~32 bytes each (8 ints, packed into a single shared `int[]`)
- Reference fixture (1900 LOC) memory budget per spec: ≤ 30 MB (vs 77 MB in 0.5.1)

Incremental edit median:
- 0.5.0/0.5.1 baseline: ~5 ms median (Regime B); 0.6.0 target: ≤ 3 ms once partial reparse (D.1.2) lands. Today's `IncrementalParser` does windowed re-lex + full reparse so edit cost ≈ full parse cost.

---

## Estimated migration effort by use case

| Use case | Effort | Notes |
|---|---|---|
| **Linter** | Trivial – Low | Walk `CstArray` filtering by `kindNameAt`; emit `Diagnostic`. The hot path is direct-array access, no view allocation. |
| **Formatter** | Medium | CST traversal logic translates from sealed-record dispatch to `cst.viewAt(idx)`-based dispatch (or direct array). Trivia emission rewires to token-positional accessors. `peglib-formatter` is being migrated in parallel as a reference. |
| **IDE plugin** | Medium – High | Incremental engine API changes; cursor abstraction gone; node identity is now token-index-based. Until D.1.2 lands, edits pay full-reparse cost (still well under typing budget for small files). |
| **Compiler / interpreter** | High | Inline actions are gone; everything that was a `{ ... }` block becomes Visitor code. AST type removed, so user defines its own. The Visitor pattern is mechanical to write but verbose for grammars with many rules. |
| **Tooling using generated standalone parser** | Medium | The single self-contained Java file output of 0.5.x is now three files (`GLexer`, `GParser`, `GVisitor`). These depend ONLY on `peglib-runtime` (`CstArray`, `TokenArray`, `Diagnostic`, `ParseResult`) plus `pragmatica-lite:core` and `java.*` — never on `peglib-core`. The `peglib-maven-plugin` generate mojo is complete. |

Rough rule of thumb: a 0.5.x consumer that only used CST output and never wrote action blocks migrates in **a few hours**. A consumer relying on actions or AST output should budget **one to a few days**, dominated by writing the equivalent visitor.

---

## Known limitations in 0.6.0 — all since resolved

This section listed what 0.6.0 shipped unfinished. **Every item has landed**; it is kept as
history so anyone reading an old copy of this guide can see what changed and when. If you are
migrating today, none of these are limitations.

| 0.6.0 limitation | resolved in |
|---|---|
| Cut operator (`^` / `↑`) parsed but treated as a no-op | 0.6.1 — see `CutOperatorTest` |
| MIXED-rule char-level fallback no-op | 0.6.1 — see `MixedRuleFallbackTest` |
| Per-rule `%recover` sync sets (start-rule only) | 0.6.1 — see `PerRuleRecoverDirectiveTest` |
| `maxDiagnostics` accepted but ignored; `ParserOptions` stub | 0.6.1 — see `MaxDiagnosticsTest`; `ParserOptions` was deleted rather than extended |
| `IncrementalParser.edit` re-lexed but ran the full parser | 0.6.1 — real partial reparse via checkpoint ancestors |
| Block comments inside a Choice not routed through `compileDelimitedBlock` | 0.6.0 ship — asymmetric `compileDelimitedBlock` in `DfaBuilder` |
| `%whitespace` ZeroOrMore coalesced the whole run into one token | 0.6.2 — per-kind trivia tokens |
| Named captures and back-references dropped | 0.6.1 — restored; see `NamedCaptureRuntimeTest` |
| `Annotations.java` fixture recovered with diagnostics | 0.6.0 ship |
| Entry point under `org.pragmatica.peg` with a `.v6` implementation package | 0.7.0 — `org.pragmatica.peg.v6.*` collapsed into `org.pragmatica.peg.*` |

### Intentional drops (per spec — NOT returning)

- BASIC/ADVANCED `RecoveryStrategy` split: one always-on panic-mode mechanism replaces it. Use `result.diagnostics().isEmpty()` for fail-fast semantics.
- Inline `{ ... }` action blocks in grammar: replaced by a `GVisitor<T>` stub class generated per grammar. Compile-time rejection with migration message.
- `AstNode` type: dropped entirely. Build domain ASTs via `GVisitor<T>` walking the CST.
- Packrat memoization: not needed under tokens-first design. JIT scalar-replacement handles short-lived parse state.

What is fully wired today:
- `PegParser.fromGrammar` generate-compile-cache pipeline
- Two-phase lex → parse with DFA-driven lexer
- Flat `CstArray` with view types
- Trivia tokens with positional accessors
- Panic-mode recovery with Rust-style diagnostics
- `IncrementalParser` (windowed re-lex; full reparse)
- 19/20 of the `format-examples/` Java25 corpus parses cleanly via the new pipeline

---

## Quick reference: import substitutions

| 0.5.x import | 0.6.0 import |
|---|---|
| `org.pragmatica.peg.PegParser` | `org.pragmatica.peg.PegParser` |
| `org.pragmatica.peg.parser.Parser` | `org.pragmatica.peg.Parser` |
| `org.pragmatica.peg.parser.ParserConfig` | _(removed)_ |
| `org.pragmatica.peg.parser.ParseResultWithDiagnostics` | `org.pragmatica.peg.cst.ParseResult` |
| `org.pragmatica.peg.source.CstNode` | `org.pragmatica.peg.cst.CstNode` (views over `CstArray`) |
| `org.pragmatica.peg.source.CstNode.Terminal` | `CstNode.Leaf` |
| `org.pragmatica.peg.source.CstNode.NonTerminal` | `CstNode.Branch` |
| `org.pragmatica.peg.source.CstNode.Token` | `CstNode.Leaf` (token boundary kept on the node's `firstToken`/`lastToken`) |
| `org.pragmatica.peg.source.CstNode.Error` | `CstNode.Error` |
| `org.pragmatica.peg.source.AstNode` | _(removed; use Visitor)_ |
| `org.pragmatica.peg.source.Trivia` | _(removed; trivia is in `TokenArray`)_ |
| `org.pragmatica.peg.source.StringSpan` | _(removed; `CstArray.textAt(i)` returns `CharSequence`)_ |
| `org.pragmatica.peg.source.SourceSpan` / `SourceLocation` | _(removed; `int` offsets via `spanStart` / `spanEnd`)_ |
| `org.pragmatica.peg.action.*` | _(removed; use generated `GVisitor<T>`)_ |
| `org.pragmatica.peg.error.Diagnostic` | `org.pragmatica.peg.diagnostic.Diagnostic` |
| `org.pragmatica.peg.error.RecoveryStrategy` | _(removed; recovery is always on)_ |
| `org.pragmatica.peg.generator.ErrorReporting` | _(removed; diagnostics always available)_ |
| `org.pragmatica.peg.generator.ParserGenerator` | _(internal; invoked via `PegParser.fromGrammar`)_ |
| `org.pragmatica.peg.incremental.IncrementalSession` | `org.pragmatica.peg.incremental.IncrementalParser` |
| `org.pragmatica.peg.incremental.Cursor` | _(removed; offsets only)_ |

---

## See also

- [`ARCHITECTURE-0.6.0.md`](ARCHITECTURE-0.6.0.md) — design rationale, the nine locked decisions, phasing plan
- [`CHANGELOG.md`](../CHANGELOG.md) §0.6.0 — implementation log
- [`HANDOVER.md`](HANDOVER.md) — current session state and the road to GA
