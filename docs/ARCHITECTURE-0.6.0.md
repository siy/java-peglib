# peglib 0.6.0 — Architecture Spec

**Status:** design — locked decisions from 2026-05-09 session. Implementation pending.
**Author:** session at HEAD `f1eb332` (post-0.5.1 ship).
**Predecessor:** `docs/incremental/ARCHITECTURE-0.5.0.md` (Path D stable IDs); 0.5.x cumulative arcs (HANDOVER §11).

---

## 0. TL;DR

0.6.0 is a **clean-slate redesign** of peglib targeting CST-only parsing for **lint and format** workloads. Breaking changes are accepted; minor-version bump signals it.

Nine architectural decisions, locked:

1. **Drop the interpreter** (`PegEngine`). Generator-only path. `PegParser.fromGrammar(g).parse(input)` does generate-compile-cache under the hood.
2. **Two-phase: lex → parse.** PEG grammar surface preserved; backend uses analysis-driven lex-then-parse with per-rule char-level fallback for edge cases.
3. **Drop runtime action support.** Generate a `Visitor<T>` stub per grammar; users implement selectively for CST → domain transforms.
4. **Drop AST output.** CST is the only tree.
5. **Flat node array.** CST data lives in `int[]`; views replace records in the data path.
6. **Trivia as tokens.** Whitespace and comments live in the token array; positional API + ergonomic helpers.
7. **Incremental as a thin caching layer.** Checkpoint boundaries via grammar directive (auto-detect default + explicit override).
8. **Error recovery: one always-on mechanism.** Panic-mode synchronization, Rust-style diagnostics, `List<Diagnostic>` always present (empty = success).
9. **The grammar is the configuration.** `ParserConfig` deleted. One runtime parameter (`maxErrors`).

**Estimated scope:** ~6 weeks for one engineer. **Estimated perf:** selfhost 800ms → ~150-300ms (comparable to javac). **LOC reduction:** ~40-50% across peglib-core + ancillary modules.

---

## 1. Motivation

### What's wrong with the 0.5.x architecture

Five sessions of incremental optimization arcs (HANDOVER §11) consistently confirmed the same pattern: **wins on structural changes**; **resets on micro-tweaks**. The successful structural changes (DFA fast-path for token rules, FIRST-set Choice dispatch, JBCT-style method splits, ASCII char interning, StringSpan) were all **partial moves toward what tokens-first recursive descent would achieve directly**.

Concrete cost of the dual implementation (interpreter + generator):
- Every fix is paid twice (Move B post-mortem; trivia rework)
- 5 historical trivia-attribution bugs, all derived from buffer-driven context-sensitivity
- StringSpan implementation duplicated runtime + emitted versions
- Lever B incremental optimization blocked twice on context-loss issues that token-streams resolve by construction

Performance gap with javac (parse-only): **~10×** on selfhost workload. The gap is per-character work; modern parsers lex first.

### Why now

- 0.5.0 published; 0.5.1 publishing today. Foundational features stable.
- Trivia rework arc (Step 4 + Cleanup A-G) explored the limits of evolutionary improvement. Diminishing returns confirmed.
- The use cases targeted (lint + format) are now well-understood and don't need many of the features the current architecture supports.
- Breaking changes are acceptable at this stage of the project.

### Workload assumptions (binding for 0.6.0)

- **Linter use case:** walks CST looking for patterns; reports diagnostics with source positions. Most lints are token-stream-level (don't need full subtree shape).
- **Formatter use case:** walks CST emitting output, preserving trivia. Round-trip byte-equality is the correctness gate.
- **IDE plugin use case:** incremental reparse on edits; sub-frame-budget latency on small edits; full reparse acceptable for cold start.
- **Out of scope for 0.6.0:** runtime action execution; AST-shaped tree output; compiler-style semantic analysis; multi-language grammar mixing.

---

## 2. Goals and non-goals

### Goals

- **Performance.** Lex-then-parse architecture; selfhost workload ≤ 300 ms on the reference machine (vs 785 ms in 0.5.1).
- **Simplicity.** Single parsing path; flat data layout; one config knob.
- **Correctness.** Round-trip byte-equality preserved across all 22 corpus fixtures + adversarial inputs.
- **Friendliness.** PEG mental model preserved at the surface. Users still write cpp-peglib-style grammars.
- **Standalone-parser invariant.** Generated parsers depend only on `pragmatica-lite:core`.

### Non-goals

- **Backward compatibility.** 0.6.0 is a breaking release. APIs change; downstream migrates.
- **Runtime grammar interpretation.** No interpreter mode for production parsers. Dev-mode tooling can still compile-and-run on the fly.
- **Action support.** Replaced by Visitor pattern (compile-time, user-implemented).
- **AST type.** Replaced by Visitor + user's own AST.
- **Generic tree library.** CST is parser-output-shaped, not a general-purpose tree type.
- **Sub-millisecond cold-start parsing.** Generate-compile-cache adds ~100-500 ms first-parse cost; subsequent parses are fast.

---

## 3. The nine decisions

### 3.1 Drop the interpreter; generator-only with generate-and-compile

**Rationale.** Two implementations of every feature is the central complexity tax. The generator path produces faster parsers AND is what real consumers ship. The interpreter's only legitimate use is grammar development (REPL, playground), which can use the same compile-and-load mechanism with friendlier error reporting.

**Concrete design.**

```java
public final class PegParser {
    public static Result<Parser> fromGrammar(String grammarText) {
        return GrammarParser.parse(grammarText)
            .flatMap(grammar -> ParserGenerator.generate(grammar)
                .flatMap(source -> JavaCompiler.compileToClass(source))
                .map(Parser::new));
    }
}

// Cached per-grammar:
private static final Map<String, Parser> COMPILED_CACHE = ...;
```

- First call to `fromGrammar(g)` for a given grammar text: generate (~10ms) + compile (~100-500ms). Cached.
- Subsequent calls: lookup from cache.
- Production deployments pre-compile at build time via `peglib-maven-plugin` and ship the generated `.class` directly — zero compilation overhead at runtime.
- Dev-mode (REPL, playground) uses the same generate-and-compile path; cache lifetime is process scope.

**Removed.**
- `org.pragmatica.peg.parser.PegEngine` (entire class, ~1900 LOC)
- Tests covering the interpreter-only behavior (~200 LOC)
- The "interpreter is also a parser" framing in docs

**Preserved.**
- `PegParser` factory class as the user-facing API
- Standalone-parser invariant (generated parser file has only `pragmatica-lite:core` dep)
- `peglib-maven-plugin` workflow (generate at build time)

### 3.2 Two-phase parsing: lex → parse

**Rationale.** Per-character PEG work is the single largest perf gap vs modern parsers. Token-stream recursive descent runs 5-10× faster than char-stream PEG. Trivia attribution problems dissolve when whitespace is just tokens with kind=WHITESPACE. Backtracking becomes a primitive `pos = saved` reset.

**PEG surface preserved.** Grammar authors write the same cpp-peglib syntax. The backend analyzer classifies each rule as **lexer-rule** (token producer) or **parser-rule** (combinator over tokens):

- **Lexer-rule criterion:** rule's expression bottoms out at literals + character classes + repetition + ordered choice between same-kind alternatives, with no parser-rule references. Compiled into the DFA.
- **Parser-rule criterion:** any rule that combines other rule references or uses tokens-first operators. Compiles to recursive descent.
- **Mixed-mode rules:** a rule that uses `.` or character-level lookahead while also calling other rules → emits a per-rule char-level fallback path. Compile-time warning suggests refactoring.

**Concrete artifacts.**

```
Generated for grammar G:
  GLexer.java    - DFA state machine; method `TokenArray lex(String input)`
  GParser.java   - recursive descent over tokens; method `CstArray parse(TokenArray tokens)`
  GVisitor.java  - per-rule visitor stub (Idea 3)
```

`TokenArray` shape:
```java
public final class TokenArray {
    private final String input;
    private final int[] starts;   // byte offset
    private final int[] ends;
    private final byte[] kinds;   // 0..127 token types; short[] for grammars > 128
    private final int count;
    
    public int kindAt(int i);
    public int startAt(int i);
    public int endAt(int i);
    public CharSequence textAt(int i);  // input.subSequence(start, end)
    public boolean isTrivia(int i);     // kind in {WHITESPACE, LINE_COMMENT, BLOCK_COMMENT}
    public int nextNonTrivia(int from);
}
```

**Tradeoffs.** Some grammars expressible in pure PEG don't naturally split (rare in practice; Java25 grammar splits cleanly). Char-level fallback exists for those.

### 3.3 Drop actions; emit `Visitor<T>` stub per grammar

**Rationale.** Runtime actions add ~1000 LOC, JDK Compiler API integration, semantic-value plumbing through every parse method. Lint/format use cases don't fire actions during parse. Visitor pattern (Roslyn / ANTLR / tree-sitter style) is the standard hand-off for CST → domain AST transformation.

**Generated visitor shape (per grammar):**

```java
public abstract class GVisitor<T> {
    // One method per parser-rule. Default: walk children, return null.
    public T visitExpr(CstArray cst, int nodeIdx) { return visitChildren(cst, nodeIdx); }
    public T visitTerm(CstArray cst, int nodeIdx) { return visitChildren(cst, nodeIdx); }
    // ... one per parser-rule
    
    // Walk helpers:
    protected T visitChildren(CstArray cst, int nodeIdx) { ... }
    protected T defaultResult() { return null; }
    protected T aggregateResult(T agg, T next) { return next; }
    public T visit(CstArray cst, int nodeIdx) { /* dispatches on kind */ }
}
```

User implements selectively:

```java
class TypeChecker extends GVisitor<Type> {
    @Override public Type visitBinaryExpr(CstArray cst, int nodeIdx) {
        Type left = visit(cst, cst.firstChild(nodeIdx));
        Type right = visit(cst, cst.lastChild(nodeIdx));
        return resolveBinaryOp(cst.opAt(nodeIdx), left, right);
    }
}
```

**Removed.**
- `org.pragmatica.peg.action` package (Action, SemanticValues, ActionCompiler, ~800 LOC)
- Action attachment APIs on PegParser.Builder
- `$0/$N` semantic value plumbing
- Per-rule action execution code in generator emit
- Examples that exercised actions (CalculatorExample, ErrorRecoveryExample) — migrated to visitor pattern

**Preserved.** Grammar files retain compatible syntax for future re-introduction; `{ ... }` blocks are rejected at gen-time with a migration message in 0.6.0.

### 3.4 Drop AST; CST is the only tree

**Rationale.** AST is "CST minus trivia AND minus single-child wrapper rules." For lint/format, CST is the right shape. Users wanting an AST-shape build their own via Visitor; wrapper-collapse is 20 lines of visitor code.

**Removed.**
- `org.pragmatica.peg.tree.AstNode` package (~400 LOC)
- `parseAst()` API
- AST conversion code in generator emit
- AST-related tests

### 3.5 Flat node array; views replace records in CST data path

**Rationale.** Records carry headers + List allocations + per-trivia-list storage. ~70 MB for the 1900-LOC reference parse in 0.5.1. Tree-sitter-style flat arrays use ~32 bytes/node vs ~80-200 today. Cache-friendly walks. Zero per-node alloc on parse hot path.

**Concrete shape.**

```java
public final class CstArray {
    private final String input;
    private final TokenArray tokens;       // retained from lexer
    private final int[] nodes;             // 8 ints per node, packed:
                                           //   [0] parent index (-1 root)
                                           //   [1] kind (rule index)
                                           //   [2] firstToken (index into tokens)
                                           //   [3] lastToken
                                           //   [4] firstChild (-1 leaf)
                                           //   [5] nextSibling (-1 last)
                                           //   [6] flagsBits (ERROR, etc.)
                                           //   [7] reserved
    private final int nodeCount;
    private final String[] ruleTable;      // kind → rule name
    
    public int kindAt(int i)        { return nodes[i*8 + 1]; }
    public int firstTokenAt(int i)  { return nodes[i*8 + 2]; }
    public int lastTokenAt(int i)   { return nodes[i*8 + 3]; }
    public int firstChildAt(int i)  { return nodes[i*8 + 4]; }
    public int nextSiblingAt(int i) { return nodes[i*8 + 5]; }
    public int parentAt(int i)      { return nodes[i*8]; }
    public boolean isError(int i)   { return (nodes[i*8 + 6] & ERROR_FLAG) != 0; }
    
    public int spanStart(int i)     { return tokens.startAt(firstTokenAt(i)); }
    public int spanEnd(int i)       { return tokens.endAt(lastTokenAt(i)); }
    public CharSequence textAt(int i) { return input.subSequence(spanStart(i), spanEnd(i)); }
    
    // Trivia (Idea 6):
    public int leadingTriviaStart(int i)  { ... }
    public int trailingTriviaEnd(int i)   { ... }
    
    // Walk:
    public Iterator<Integer> children(int i);
    public Iterator<Integer> descendants(int i);
}
```

**Public API for sealed-pattern lovers (kept thin):**

```java
public sealed interface CstNode permits CstNode.Branch, CstNode.Leaf, CstNode.Error {
    int index();      // index into the underlying CstArray
    CstArray array();
    // No fields stored on CstNode itself — view over the array.
}
```

Users wanting `case CstNode.Branch n -> ...` syntax can; users wanting tight walk performance use the array API directly.

**Removed.**
- `record CstNode.NonTerminal(...)`, `record CstNode.Terminal(...)`, `record CstNode.Token(...)`, `record CstNode.Error(...)` as data-bearing records
- StringSpan as a CstNode component (still useful for token text materialization; reused as `CharSequence textAt(int i)` return type, internally an int-pair view)
- Long stable IDs as record components (token index serves as identity for incremental, per 3.7)

### 3.6 Trivia as tokens; positional API + ergonomic helpers

**Rationale.** Once the lexer emits trivia as tokens with `kind = WHITESPACE / LINE_COMMENT / BLOCK_COMMENT`, the entire trivia-attribution problem dissolves. There's nothing to attribute — trivia tokens are at known positions in the token array. Formatters walk the array; lints filter by kind.

**Eliminates outright (~3500 LOC + 5 historical bugs):**
- `pendingLeadingTrivia` buffer + 4 accessor methods + 38 callers
- TriviaPostPass.java (689 LOC, runtime + emitted) — ENTIRE 0.5.1 trivia rework reverts to "this was never necessary"
- Trivia attribution test infrastructure (TriviaPostPassTest, TriviaPostPassFlagTest, TriviaPostPassSpliceOffsetTest, TriviaAdversarialTest, ~1900 LOC)
- Bug A through C''' historical fixes
- Cleanup A buffer-call short-circuit, Cleanup B drainExtra threading, Cleanup D rebuild-skip
- `triviaPostPass` flag and all related machinery

**API:**

Positional foundation (the actual data):
```java
public TokenArray tokens();                                  // raw token stream
public IntStream triviaTokens(int from, int to);            // tokens in range filtered by kind
```

Ergonomic helpers on CstArray:
```java
public IntStream leadingTriviaTokens(int nodeIdx);          // tokens before firstToken
public IntStream trailingTriviaTokens(int nodeIdx);         // tokens after lastToken before next sibling
public CharSequence leadingTriviaText(int nodeIdx);         // concatenated text
public CharSequence trailingTriviaText(int nodeIdx);
```

Both API styles are inlinable; no perf delta.

### 3.7 Incremental engine as a thin caching layer

**Rationale.** Today's incremental engine (peglib-incremental, ~3500 LOC) carries TreeSplicer + LongLongMap + Cursor + IdGenerator + parseRuleAt. Under tokens-first + flat-array, all this collapses dramatically.

**Concrete shape.**

```java
public final class IncrementalParser {
    private final Parser parser;
    private final Set<String> checkpointRules;  // grammar.checkpoints() ∪ user override
    private TokenArray tokens;
    private CstArray cst;
    
    public IncrementalParser(Parser parser, String input);
    
    public void edit(int offset, int oldLen, String newText) {
        // 1. Token-stream splice: re-lex affected byte range
        TokenArray newTokens = tokens.spliceLex(parser.lexer(), offset, oldLen, newText);
        
        // 2. Find smallest enclosing checkpoint
        int checkpointNode = cst.findCheckpointAncestor(offset, checkpointRules);
        if (checkpointNode == -1) {
            // No checkpoint encloses; full reparse
            tokens = newTokens;
            cst = parser.parseTokens(newTokens);
            return;
        }
        
        // 3. Partial parse from checkpoint's first token
        int firstToken = cst.firstTokenAt(checkpointNode);
        ParseResult subtree = parser.parseRuleFrom(newTokens, firstToken, cst.kindAt(checkpointNode));
        
        // 4. Array splice
        tokens = newTokens;
        cst = cst.spliceSubtree(checkpointNode, subtree.cst, ...);
    }
    
    public CstArray current() { return cst; }
    public List<Diagnostic> diagnostics();
}
```

**Checkpoint declaration.**

Grammar directive `%checkpoint` (or `%incremental`) on rules:

```peg
%checkpoint Stmt
%checkpoint MethodDecl
%checkpoint TypeDecl

Stmt <- IfStmt / WhileStmt / Block / ExprStmt
```

Auto-detection default: rules that (a) are not in `BackReferenceScan.unsafeRules`, (b) have no left-recursion entanglement with non-checkpoint ancestors, (c) appear at "natural boundary" depth in the grammar tree (heuristic: rules referenced from top-level, statement-level, or declaration-level rules).

Explicit directive overrides auto-detection.

**Eliminates.**
- `TreeSplicer.shiftAll` deep-copying records → `int[] System.arraycopy` on the array tail
- `LongLongMap` from stable IDs to nodes → token index IS the identity
- `Cursor` (offset + enclosingNodeId) → just an offset
- `IdGenerator` + per-CstNode `long id` → index-based identity
- `parseRuleAt` API + safe-pivot analyzer → "parse from token N as rule R" primitive
- Trivia redistribution machinery → trivia tokens are in the array

**Estimated module size:**
- Today: ~3500 LOC + 195 tests
- 0.6.0: ~600-1000 LOC + ~80 tests

### 3.8 Error recovery: one always-on mechanism

**Rationale.** Today's BASIC/ADVANCED split is rarely useful; ADVANCED is the desired behavior for lint/format; users wanting fail-fast check `diagnostics.isEmpty()` at app level. One mechanism (panic-mode synchronization) handles all cases.

**Algorithm (under tokens-first):**

1. Parser hits an unexpected token kind for the current rule
2. Look up the active sync set (grammar's `%recover` directive or default `{; , } ) ] newline}`)
3. Walk forward through tokens until kind is in sync set
4. Emit `Error` node (kind = ERROR_KIND) covering the skipped range
5. Advance past sync token; resume parsing

No buffer state to restore; no recovery override stack mutation; just advance the token index.

**API:**

```java
public final class ParseResult {
    public final CstArray cst;                    // always present
    public final List<Diagnostic> diagnostics;    // empty list if success
    
    public boolean isSuccess() { return diagnostics.isEmpty(); }
}

public final class Diagnostic {
    public final Severity severity;
    public final int offset;
    public final int length;
    public final String message;
    public final String expected;        // what we wanted
    public final String found;           // what we got
    
    public String formatRustStyle(String filename, String input);
}
```

**Removed.**
- `RecoveryStrategy` enum (NONE/BASIC/ADVANCED)
- `ErrorReporting` enum at gen-time
- Dual emission paths in ParserGenerator
- Two diagnostic formats (BASIC plain + ADVANCED Rust-style)

**Preserved.**
- `%recover` directive for per-rule sync set overrides
- Rust-style diagnostic format
- Multiple errors collected per parse
- `CstNode.Error` (now: `kind=ERROR_KIND` flag bit on flat array nodes)

### 3.9 The grammar is the configuration

**Rationale.** Most of today's 11 `ParserConfig` fields are gen-time decisions or made obsolete by other 0.6.0 decisions. The grammar itself describes the language AND the parsing behavior.

**Removed entirely.**

```java
// 0.5.x — gone in 0.6.0:
public record ParserConfig(
    boolean packratEnabled,        // auto-detected for left-recursive rules
    RecoveryStrategy recoveryStrategy,  // always ADVANCED
    boolean captureTrivia,         // trivia is always in token array
    boolean choiceDispatch,        // always emit FIRST-set switch
    boolean markResetChildren,     // no children list under flat array
    boolean inlineLocations,       // offsets are int; no SourceLocation records
    boolean selectivePackrat,      // auto-detected for left-recursive only
    Set<String> packratSkipRules,  // gone with above
    boolean mutableParseResult,    // doesn't apply to flat array
    boolean tokenFastPath,         // entire lexer IS the token fast path
    boolean triviaPostPass         // no post-pass
) {}
```

**0.6.0 API:**

```java
Parser parser = PegParser.fromGrammar(grammarText).unwrap();

// One parse method, with optional max diagnostics:
ParseResult result = parser.parse(input);
ParseResult capped = parser.parse(input, /*maxErrors*/ 100);

// Walk:
result.cst.walk(visitor);
result.diagnostics.forEach(d -> System.err.println(d.formatRustStyle("file.java", input)));
```

**Grammar directives** are the configuration:
- `%whitespace <- ...` — lexer skip rule
- `%recover <CharSet> Rule` — per-rule sync set override
- `%checkpoint Rule` — incremental parsing checkpoint boundary
- (Future) `%priority Rule` — left-recursion handling hint

---

## 4. Module structure

### 0.5.x layout (reference)

```
peglib/                           (parent pom)
├── peglib-core/                  (~7-8k LOC: PegEngine + ParserGenerator + actions + tree types)
├── peglib-incremental/           (~3500 LOC: TreeSplicer + NodeIndex + Cursor + IncrementalSession)
├── peglib-formatter/             (Wadler-Lindig pretty printer)
├── peglib-maven-plugin/          (generate / lint / check mojos)
└── peglib-playground/            (REPL + HTTP UI)
```

### 0.6.0 layout (proposed)

```
peglib/                           (parent pom)
├── peglib-core/                  (~3-4k LOC: GrammarParser + ParserGenerator + JavaCompile + flat CST)
├── peglib-incremental/           (~600-1000 LOC: token-splice + array-splice caching layer)
├── peglib-formatter/             (Wadler-Lindig; rewritten on flat-array CST)
├── peglib-maven-plugin/          (generate-only; lint/check via formatter dep)
└── peglib-playground/            (REPL + HTTP UI on generate-and-compile path)
```

### Module-by-module changes

**peglib-core:**
- DROP: `org.pragmatica.peg.parser.PegEngine` (interpreter)
- DROP: `org.pragmatica.peg.action.*` (action support)
- DROP: `org.pragmatica.peg.tree.AstNode.*` (AST type)
- DROP: `org.pragmatica.peg.tree.TriviaPostPass.*` (post-pass machinery)
- DROP: `org.pragmatica.peg.tree.StringSpan.*` (subsumed by token-array text access)
- DROP: `org.pragmatica.peg.parser.ParserConfig.*` (no config record)
- KEEP: `org.pragmatica.peg.grammar.*` (grammar IR + lexer + parser, mostly unchanged)
- ADD: `org.pragmatica.peg.lexer.*` (lexer DFA construction + emission)
- ADD: `org.pragmatica.peg.cst.*` (CstArray, ParseResult, view types)
- ADD: `org.pragmatica.peg.driver.*` (PegParser, JavaCompile, Cache)
- REWRITE: `org.pragmatica.peg.generator.ParserGenerator` (emit lexer + parser + visitor stub from one Grammar input)

**peglib-incremental:**
- DROP: `TreeSplicer`, `NodeIndex`, `LongLongMap`, `Cursor`, `IdGenerator`, `IncrementalSession`, `SafePivotAnalyzer`
- DROP: long stable IDs on CstNode (no longer needed)
- ADD: `IncrementalParser` (the thin wrapper described in §3.7)
- ADD: `TokenArray.spliceLex()` operation (probably lives on the token type, not in incremental)

**peglib-formatter:**
- REWRITE walks to use flat-array CST
- Trivia walks now use token-array filtering (Idea 6 helpers)

**peglib-maven-plugin:**
- `generate` mojo: produces single Java file + visitor stub file
- `lint` and `check` mojos: light-touch — most lint logic lives in user code via the visitor

**peglib-playground:**
- Move from "interpret on the fly" to "generate-and-compile in-memory"
- Adds ~100-500 ms startup cost; acceptable for dev tool

---

## 5. API surface (concrete signatures)

### Public API summary

```java
package org.pragmatica.peg;

public final class PegParser {
    public static Result<Parser> fromGrammar(String grammarText);
    public static Result<Parser> fromGrammar(String grammarText, ParserOptions opts);
}

public final class Parser {
    public ParseResult parse(String input);
    public ParseResult parse(String input, int maxDiagnostics);
    public Lexer lexer();   // exposed for incremental
    public Grammar grammar();
}

public final class ParserOptions {
    // For now, this is empty or near-empty. Reserved for future extension.
    // Possible fields: cache strategy, compilation hooks. Not part of 0.6.0 launch scope.
}

public final class ParseResult {
    public final CstArray cst;
    public final List<Diagnostic> diagnostics;
    public boolean isSuccess();
}

public final class CstArray {
    // Query API
    public int rootIndex();
    public int kindAt(int nodeIdx);
    public int firstChildAt(int nodeIdx);
    public int nextSiblingAt(int nodeIdx);
    public int parentAt(int nodeIdx);
    public int spanStart(int nodeIdx);
    public int spanEnd(int nodeIdx);
    public CharSequence textAt(int nodeIdx);
    public boolean isError(int nodeIdx);
    
    // Trivia
    public IntStream leadingTriviaTokens(int nodeIdx);
    public IntStream trailingTriviaTokens(int nodeIdx);
    public CharSequence leadingTriviaText(int nodeIdx);
    public CharSequence trailingTriviaText(int nodeIdx);
    
    // Walk
    public Iterator<Integer> descendants(int nodeIdx);
    public <T> T walk(GVisitor<T> visitor);
    
    // Token access
    public TokenArray tokens();
    public int firstTokenAt(int nodeIdx);
    public int lastTokenAt(int nodeIdx);
    
    // Pattern-matching API (thin views)
    public CstNode viewAt(int nodeIdx);
    
    // Reconstruction
    public String reconstruct();   // round-trip via tokens
}

public sealed interface CstNode permits CstNode.Branch, CstNode.Leaf, CstNode.Error {
    int index();
    CstArray array();
    int kind();
    int spanStart();
    int spanEnd();
    CharSequence text();
    
    record Branch(int index, CstArray array) implements CstNode { ... }
    record Leaf(int index, CstArray array) implements CstNode { ... }
    record Error(int index, CstArray array) implements CstNode { ... }
}

public final class TokenArray {
    public int count();
    public int kindAt(int i);
    public int startAt(int i);
    public int endAt(int i);
    public CharSequence textAt(int i);
    public boolean isTrivia(int i);
    public int nextNonTrivia(int from);
    public TokenArray spliceLex(Lexer lexer, int offset, int oldLen, String newText);
}

public final class Diagnostic {
    public final Severity severity;
    public final int offset;
    public final int length;
    public final String message;
    public final String expected;
    public final String found;
    
    public String formatRustStyle(String filename, String input);
}
```

### Generated artifacts (per grammar G)

```java
// G_Lexer.java
public final class GLexer {
    public TokenArray lex(String input);
    public TokenArray spliceLex(TokenArray prev, int offset, int oldLen, String newText);
}

// G_Parser.java
public final class GParser {
    public CstArray parse(TokenArray tokens);
    public ParseResult parseRuleFrom(TokenArray tokens, int firstToken, int ruleKind);
}

// G_Visitor.java
public abstract class GVisitor<T> {
    public T visitFoo(CstArray cst, int nodeIdx) { return visitChildren(cst, nodeIdx); }
    public T visitBar(CstArray cst, int nodeIdx) { return visitChildren(cst, nodeIdx); }
    // ... one per parser-rule
    
    protected T visitChildren(CstArray cst, int nodeIdx);
    public T visit(CstArray cst, int nodeIdx);   // dispatches on kind
}
```

---

## 6. Grammar directives

### Existing (preserved)

- `%whitespace <- ...` — defines lexer skip rule
- `%recover <CharSet> Rule` — per-rule synchronization set for error recovery

### New in 0.6.0

- `%checkpoint Rule` (or `%incremental Rule`) — declares a rule as an incremental-reparse boundary

### Removed

- (None of today's directives are removed; new ones added)

---

## 7. Implementation phasing

The redesign is large but parallelizable. Recommended order:

### Phase A (week 1): grammar IR + lexer

- Augment `Grammar` IR with rule classification (lexer-rule vs parser-rule)
- Implement DFA construction from lexer-rules
- Implement `TokenArray` data structure
- Generate `GLexer.java` for sample grammars
- **Gate:** lex Java25 corpus into TokenArray byte-equal to a hand-written reference

### Phase B (week 2): parser code generation

- Implement `CstArray` data structure + view types
- Generator emits parser code that walks TokenArray, emits int[] CstArray
- FIRST-set Choice dispatch (already implemented; carry over) emits as primitive `switch (tokens.kindAt(pos))`
- Recovery via panic-mode sync to grammar-declared `%recover` sets
- **Gate:** parse Java25 reference fixture; CST byte-equal to round-trip-reconstructed input

### Phase C (week 3): user-facing API

- Implement `PegParser.fromGrammar()` with generate-and-compile-and-cache
- Implement `ParseResult`, `Diagnostic`, `CstNode` view types
- Migrate `peglib-formatter` to flat-array CST
- **Gate:** existing 22 corpus fixtures all round-trip byte-equal under 0.6.0 path

### Phase D (week 4): incremental engine

- Implement `TokenArray.spliceLex()`
- Implement `IncrementalParser` thin wrapper
- Implement `CstArray.findCheckpointAncestor()` and `spliceSubtree()`
- `%checkpoint` directive in grammar parser
- **Gate:** existing IncrementalParityTest passes; bench median ≤ 5 ms (parity with 0.5.x baseline)

### Phase E (week 5): visitor stub + tooling

- Generator emits `GVisitor.java` stub per grammar
- Update `peglib-maven-plugin` for new emission
- Migrate `peglib-playground` to generate-and-compile path
- **Gate:** end-to-end CalculatorExample, JsonParserExample work via Visitor pattern

### Phase F (week 6): bench, polish, ship

- Bench A/B vs 0.5.1: target reference ≤ 10ms, selfhost ≤ 250ms
- Migration guide for downstream consumers
- CHANGELOG + HANDOVER for 0.6.0
- Release pipeline (PR → merge → tag → Maven Central)

---

## 8. Migration from 0.5.x

### Breaking changes (acknowledged)

For users of `peglib-core`:
- `org.pragmatica.peg.parser.PegEngine` removed → use `PegParser.fromGrammar(g).parse(input)` (generate-and-compile under the hood)
- `parseAst()` removed → use Visitor pattern on CST
- `org.pragmatica.peg.action.*` removed → use Visitor pattern on CST
- `CstNode` records changed to views → users with `case CstNode.Terminal t -> t.text()` mostly migrate via accessor (`t.text()` still returns CharSequence); pattern matching with `case Terminal(String text, ...)` deconstruction breaks
- `ParserConfig` removed → grammar directives + optional `maxDiagnostics` parameter
- `RecoveryStrategy.NONE` removed → check `result.diagnostics().isEmpty()` at app level
- StringSpan removed → text comes from `CstArray.textAt(i)` (returns CharSequence)
- AstNode removed entirely

For users of `peglib-incremental`:
- `IncrementalSession` API changed → `IncrementalParser` (simpler shape per §3.7)
- Stable `long id` on CstNode removed → token index serves as identity

For users of `peglib-formatter`:
- API mostly preserved (Wadler-Lindig walks); internal traversals rewritten on flat array

### Migration guide structure

A `docs/MIGRATION-0.5-TO-0.6.md` file will be written during Phase F covering:
- API changes per public class
- Common code transformations (before/after pairs)
- Grammar file changes required (mostly: nothing; grammars that used `{...}` action blocks need refactoring)
- Performance expectations
- Estimated migration effort per use case

---

## 9. Risks and mitigations

### R1 — generate-and-compile latency for casual users

**Risk:** First call to `PegParser.fromGrammar(g)` adds 100-500ms compilation overhead. Casual users (CLI tools, REPL sessions) may notice.

**Mitigation:**
- Cache compiled parsers by grammar text hash
- `peglib-maven-plugin` workflow generates at build time; users ship pre-compiled `.class` and skip the compile cost entirely
- Document the trade-off in user-facing docs

### R2 — lexer-rule classification edge cases

**Risk:** Some grammars have rules that don't cleanly classify as lexer-rule vs parser-rule (rare in practice but possible).

**Mitigation:**
- Per-rule char-level fallback path (already designed in §3.2)
- Compile-time warning suggests refactoring with concrete grammar transformations
- Java25 corpus passes cleanly (verified during Phase A spike)

### R3 — round-trip byte-equality regression

**Risk:** Lexer/parser rewrite changes CST shape in subtle ways; corpus fixtures might shift.

**Mitigation:**
- 22 corpus fixture round-trip is the load-bearing assertion at every phase gate
- Adversarial corpus (TriviaAdversarialTest from 0.5.1) carries forward as regression net
- Diff-based fixture validation in CI

### R4 — incremental parity regression

**Risk:** Lever B has been a recurring source of context-loss correctness issues. The redesign changes the algorithm fundamentally; might surface new bugs.

**Mitigation:**
- IncrementalParityTest from 0.5.x carries forward as regression net
- New checkpoint-rule mechanism is explicit (user-controllable) so unsafe pivots can't be picked silently
- Bench A/B at Phase D gate; revert any commit that regresses median or parity

### R5 — six-week scope expansion

**Risk:** Software estimates are bad; six weeks could become three months.

**Mitigation:**
- Phase gates are independent — can ship Phase A-C as 0.6.0-alpha, B-E later
- Existing 0.5.1 architecture is fully shippable; no pressure to rush
- Phase B (parser codegen) is the critical-path; parallelize Phases C, D, E once B lands

---

## 10. Bench targets

Reference machine: same Apple Silicon used for 0.5.x bench session.

| Workload | 0.5.1 | 0.6.0 target | Stretch |
|---|---:|---:|---:|
| Reference parse (1900 LOC) | 24.88 ms / 77 MB | **≤ 10 ms / ≤ 30 MB** | ≤ 6 ms |
| Selfhost parse (37k LOC) | 784.7 ms / 1881 MB | **≤ 250 ms / ≤ 600 MB** | ≤ 150 ms |
| Incremental edit median (Regime B) | 5.0 ms | **≤ 3 ms** | ≤ 1 ms |
| First-call (cold compile) | n/a | **≤ 600 ms** (one-time) | — |
| Subsequent parse (warm cache) | n/a | reference parse target | — |

For comparison: javac parses 1900-LOC Java in ~9 ms (parse-only mode). 0.6.0 target of ~10 ms for reference fixture brings peglib to **parity-with-or-faster-than javac** on Java parsing — while emitting strictly more output (CST + trivia tokens, vs javac's parse-only AST).

---

## 11. Open questions / TBD

These are deferred to implementation; spec authors should resolve them in the appropriate phase:

- **Parallel phases.** Can Phase A (lexer) and Phase B (parser codegen) be developed in parallel? Likely yes; Phase B can mock the TokenArray API.
- **Visitor return type generic.** `GVisitor<T>` — how does `defaultResult()` interact with primitive return types? Standard ANTLR-style approach: `T` is always reference-typed; primitives wrap.
- **Text materialization caching.** `CstArray.textAt(i)` returns `CharSequence`. Should it cache materialized Strings? (Per Cleanup F.3 lessons: probably let the consumer cache.)
- **Pattern-matching ergonomics.** `case CstNode.Branch n` works but loses field access. Should `Branch` have getters that delegate to the array? (Yes; cheap; preserves ergonomics.)
- **Maven Central artifact migration.** 0.5.x artifacts continue to exist on Central. 0.6.0 ships as new versions. Cross-version compatibility is NOT promised; documented in CHANGELOG.

---

## 12. References

### This-session findings (informing the spec)

- `docs/HANDOVER.md` §11 — full optimization arc summary (Move B + trivia rework + Cleanup A-G + Lever B retry)
- `docs/incremental/THROUGHPUT-ENGINE-MOVE-B.md` §11 — the failed singleton-mutator attempt; foundational lesson for "structural change vs micro-optimization"
- `docs/incremental/TRIVIA-ADVERSARIAL-FINDINGS.md` — adversarial test corpus identifying context-dependencies in 0.5.x trivia attribution
- `docs/incremental/THROUGHPUT-ENGINE-TIER1.md` — performance-arc spec for 0.5.0; pattern of "high-volume single targets win big; broad generalizations don't"
- `docs/CHANGELOG.md` [0.5.1] — published version this spec succeeds

### External references

- tree-sitter (https://tree-sitter.github.io/tree-sitter/) — flat-array CST; incremental parsing; the closest architectural analog
- Roslyn `SyntaxNode` / `SyntaxVisitor<T>` — visitor pattern reference
- swift-syntax — visitor + token streams
- javac `TreeVisitor` — visitor pattern
- cpp-peglib — the surface grammar syntax to preserve

---

**End of spec. Ready for Phase A implementation.**
