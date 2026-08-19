# Peglib

A PEG (Parsing Expression Grammar) parser library for Java. Tokens-first lex-then-parse
architecture, flat int[] CST, visitor pattern, true incremental reparse.

Maven Central: `org.pragmatica-lite:peglib:0.7.2`

Migrating from 0.5.x? See [`docs/MIGRATION-0.5-TO-0.6.md`](docs/MIGRATION-0.5-TO-0.6.md).
Design rationale: [`docs/ARCHITECTURE-0.6.0.md`](docs/ARCHITECTURE-0.6.0.md).

---

## What it does

- Compile a PEG grammar text into a Java parser that produces a CST plus diagnostics.
- The CST is a flat `int[]` (32 bytes/node), lossless — trivia is preserved as tokens.
- Parsing runs at parity-class speed with `javac` on real-world Java 25 code.
- True partial reparse via `%checkpoint` directives; sub-millisecond p50 edits.
- Visitor pattern (`GVisitor<T>`) for CST -> domain transforms.
- Always-on panic-mode error recovery; Rust-style diagnostics.

---

## Quick start

### Dependency

```xml
<dependency>
    <groupId>org.pragmatica-lite</groupId>
    <artifactId>peglib</artifactId>
    <version>0.7.2</version>
</dependency>
```

Requires Java 25+ and [`pragmatica-lite:core`](https://github.com/siy/pragmatica-lite)
for `Result` / `Option` types (transitive).

If you only consume a generated parser, depend on `peglib-runtime` (25 KB) instead of
`peglib` — the runtime is enough to walk a `CstArray` and read diagnostics.

### Parse some text

```java
import org.pragmatica.peg.PegParser;
import org.pragmatica.peg.cst.CstArray;
import org.pragmatica.peg.cst.ParseResult;

var parser = PegParser.fromGrammar("""
    Start  <- '#' Number
    Number <- [0-9]+
    %whitespace <- [ \\t]*
    """).unwrap();

ParseResult result = parser.parse("#42");

if (!result.isSuccess()) {
    result.diagnostics().forEach(d ->
        System.err.println(d.formatRustStyle("input", "#42")));
}

CstArray cst = result.cst();
System.out.println(cst.textAt(cst.rootIndex())); // -> "#42"
```

`fromGrammar` runs grammar parse -> rule classification -> DFA build -> lexer codegen ->
parser codegen -> JDK Compiler API. The compiled parser is cached per exact grammar text;
first call to a given grammar is on the order of 100-500 ms, subsequent calls are
sub-millisecond.

### Walk the CST

```java
import org.pragmatica.peg.cst.CstArray;
import org.pragmatica.peg.cst.CstNode;

void walk(CstArray cst, int idx) {
    switch (cst.viewAt(idx)) {
        case CstNode.Branch b -> {
            System.out.println("rule:  " + b.kindName());
            b.children().forEach(child -> walk(cst, child));
        }
        case CstNode.Leaf l   -> System.out.println("leaf:  " + l.text());
        case CstNode.Error e  -> System.out.println("error: " + e.text());
    }
}

walk(cst, cst.rootIndex());
```

For the hot path, the direct array API skips view allocation:

```java
void walkFast(CstArray cst, int idx) {
    if (cst.isError(idx)) { /* error */ return; }
    int first = cst.firstChildAt(idx);
    if (first == CstArray.NO_NODE) { /* leaf  */ return; }
    cst.children(idx).forEach(child -> walkFast(cst, child));
}
```

### Domain transform via visitor

Per grammar, the generator emits an abstract `GVisitor<T>` with one
`visit<RuleName>(CstArray cst, int nodeIdx)` method per parser rule. Override only what
you need; default behavior walks children.

```java
class Eval extends GVisitor<Integer> {
    @Override public Integer visitNumber(CstArray cst, int nodeIdx) {
        return Integer.parseInt(cst.textAt(nodeIdx).toString().trim());
    }
}

Integer total = new Eval().visit(cst, cst.rootIndex());
```

See [`docs/VISITOR-TUTORIAL.md`](docs/VISITOR-TUTORIAL.md) for the end-to-end walkthrough
(grammar -> generated visitor -> evaluator).

---

## Grammar syntax

The surface is [cpp-peglib](https://github.com/yhirose/cpp-peglib)-compatible PEG.

### Operators

```peg
RuleName <- Expression       # rule definition

e1 e2                        # sequence
e1 / e2                      # ordered choice
e*  e+  e?                   # zero/one or more, optional
e{3}  e{2,}  e{2,5}          # bounded repetition
&e  !e                       # positive / negative lookahead
(e1 e2)                      # grouping
^                            # cut: commits to current Choice alternative

'literal'   "literal"        # string literal
[a-z]   [^a-z]               # character class, negated class
.                            # any character
'text'i   [a-z]i             # case-insensitive

< e >                        # token boundary: captures the matched span, and
                             # declares a reference-only rule to be ONE token
```

### Directives

```peg
%whitespace <- [ \t\r\n]*    # lexer skip rule (whitespace + comments)
%recover <CharSet> Rule      # per-rule synchronization set for error recovery
%checkpoint Rule             # incremental-reparse boundary
%suggest Rule "message"      # diagnostic hint for parse failures
%memo Rule                   # cache a rule re-parsed at the same position (0.7.1)
%import Grammar.Rule         # compose grammars; pass a GrammarSource (0.7.2)
%parser Rule                 # pin a rule to PARSER, overriding classification (0.7.2)
```

See [`docs/GRAMMAR-DSL.md`](docs/GRAMMAR-DSL.md) for the full reference.

### Dropped in 0.6.x (was 0.5.x)

- **Inline `{ ... }` action blocks** — rejected at `fromGrammar`. Use `GVisitor<T>`
  instead; see the visitor tutorial.
- **AST type (`AstNode`, `parseAst`)** — gone. CST is the only tree; build your own
  AST in a visitor if you want one.

Named captures (`$name<e>`) and back-references (`$name`) — restored in 0.6.1 with
source-span equality semantics (matching 0.5.x). `$(...)` capture-scope isolates
captures within its scope.

---

## Trivia handling

Trivia (whitespace, line comments, block comments, doc comments) lives in the
`TokenArray` next to content tokens, classified by kind:

| Constant | Kind |
|---|---|
| `TokenArray.KIND_WHITESPACE` | spaces, tabs, newlines |
| `TokenArray.KIND_LINE_COMMENT` | `// ...` |
| `TokenArray.KIND_BLOCK_COMMENT` | `/* ... */` |
| `TokenArray.KIND_DOC_LINE_COMMENT` | `/// ...` (0.6.1) |
| `TokenArray.KIND_DOC_BLOCK_COMMENT` | `/** ... */` (0.6.1) |

Per-node access:

```java
int idx = cst.rootIndex();
CharSequence lead = cst.leadingTriviaText(idx);
CharSequence trail = cst.trailingTriviaText(idx);
cst.leadingTriviaTokens(idx).forEach(tokIdx -> {
    int kind = cst.tokens().kindAt(tokIdx);
    // dispatch on kind (whitespace vs comment vs doc-comment)
});
```

`cst.reconstruct()` concatenates every token's text in order; for a successful parse
this equals the original input byte-for-byte (the round-trip invariant).

---

## Error recovery

There is one error-recovery mechanism, always on: panic-mode synchronization. When the
parser hits an unexpected token, it walks forward to the next token in the active sync
set, emits an `Error` node covering the skipped range, records a `Diagnostic`, and
resumes.

The default sync set is `{ ; , } ) ] }`. Override per-rule with the `%recover` directive
in the grammar.

```java
ParseResult result = parser.parse(input);
result.diagnostics().forEach(d ->
    System.err.println(d.formatRustStyle("file.java", input)));

// fail-fast semantics (no special API needed):
if (!result.isSuccess()) {
    throw new IllegalArgumentException(result.diagnostics().getFirst().message());
}
```

The `formatRustStyle` output mirrors `cargo check`:

```
error: expected Number
  --> input:1:5
   |
 1 | 3 + @invalid
   |     ^ found '@'
   |
```

Cap the number of diagnostics with the two-arg overload:

```java
ParseResult capped = parser.parse(input, /* maxDiagnostics */ 100);
```

---

## Incremental parsing

`peglib-core` provides `IncrementalParser` — a stateful wrapper that re-lexes
only the affected window on each edit and reparses only the smallest enclosing
checkpoint subtree.

```java
import org.pragmatica.peg.incremental.IncrementalParser;

var inc = new IncrementalParser(parser, "int x = 1;");
ParseResult after = inc.edit(/* offset */ 8, /* oldLen */ 1, "42");
// inc.current() == after.cst()
```

Checkpoint boundaries come from the grammar: declare them with `%checkpoint RuleName`.
When no `%checkpoint` directives are present, a sensible default set is used
(`Stmt`, `Statement`, `MethodDecl`, `TypeDecl`, `ClassMember`, `Block`).

Edits inside a checkpoint subtree take the partial-reparse path (sub-millisecond p50);
edits that span checkpoints fall back to full reparse.

---

## Module layout

| Module | Purpose |
|---|---|
| `peglib-runtime` | 25 KB; the only dep generated parsers need (plus pragmatica-lite:core) |
| `peglib` (`peglib-core`) | grammar parser, codegen, analyzers, `PegParser.fromGrammar`, `IncrementalParser` |
| `peglib-formatter` | Wadler-Lindig pretty printer over `CstArray` |
| `peglib-maven-plugin` | build-time codegen mojo (`generate`) |
| `peglib-playground` | REPL + HTTP UI for experimenting with grammars |

---

## Build-time codegen (Maven plugin)

For production, generate the lexer, parser, and visitor at build time and ship
pre-compiled classes — no `fromGrammar` cost at runtime:

```xml
<plugin>
    <groupId>org.pragmatica-lite</groupId>
    <artifactId>peglib-maven-plugin</artifactId>
    <version>0.7.2</version>
    <executions>
        <execution>
            <goals><goal>generate</goal></goals>
            <configuration>
                <grammarFile>src/main/peg/MyGrammar.peg</grammarFile>
                <outputDirectory>${project.build.directory}/generated-sources/peg</outputDirectory>
                <packageName>com.example.parser</packageName>
            </configuration>
        </execution>
    </executions>
</plugin>
```

Defaults emit `GLexer.java`, `GParser.java`, `GVisitor.java` under the configured
package. Generated sources depend ONLY on `peglib-runtime` + `pragmatica-lite:core`.

### Goals

| Goal | Phase | Does |
|---|---|---|
| `generate` | `generate-sources` | Emits lexer, parser and visitor sources |
| `lint` | `validate` | Runs the grammar analyzer; reports findings |
| `check` | `verify` | Builds the parser and optionally parses a smoke input |

### Parameters

All are settable as properties (`-Dpeglib.<name>=…`).

| Parameter | Goals | Default | Purpose |
|---|---|---|---|
| `grammarFile` | all | *(required)* | The `.peg` file |
| `outputDirectory` | `generate` | *(required)* | Where sources are written |
| `packageName` | `generate` | *(required)* | Package for the emitted classes |
| `lexerClassName` | `generate` | `GLexer` | Emitted lexer name |
| `parserClassName` | `generate` | `GParser` | Emitted parser name |
| `visitorClassName` | `generate` | `GVisitor` | Emitted visitor name |
| `importDirectory` | all | grammar file's own directory | Where `%import` looks for `<Name>.peg` |
| `failOnWarning` | `lint`, `check` | `false` | Treat analyzer warnings as build failures |
| `smokeInput` | `check` | *(none)* | Input parsed to prove the grammar works end to end |

`%import` resolves against `importDirectory`, so a grammar importing `Shared.Rule`
finds `Shared.peg` beside it with no configuration.

---

## Performance

- Parity-class with `javac` parse-only on real Java 25 source (1.2x-1.8x of javac
  wallclock on 1900-LOC and 40k-LOC fixtures, while emitting full CST + trivia +
  diagnostics that javac doesn't expose).
- Roughly 12x faster than the 0.5.x source-generated parser.
- Memory: ~32 bytes per CST node (flat `int[]`), ~10x less than 0.5.x record-based CST.
- Incremental edit p50 sub-millisecond when the edit lies inside a `%checkpoint` subtree.

Concrete numbers shift with each release; see [`CHANGELOG.md`](CHANGELOG.md) and
[`docs/BENCHMARKING.md`](docs/BENCHMARKING.md) for the reproduction harness and current
data.

---

## Build

```bash
mvn install -Djbct.skip=true
```

`-Djbct.skip=true` works around a JBCT 0.25.0 formatter-convergence issue on a few
files; lint itself passes cleanly.

Run tests for a single module:

```bash
mvn -pl peglib-core test -Djbct.skip=true
```

JMH benchmark harness reference: [`docs/BENCHMARKING.md`](docs/BENCHMARKING.md).

---

## Recent releases

Full history in [`CHANGELOG.md`](CHANGELOG.md).

| Version | Date | What |
|---|---|---|
| **0.7.2** | 2026-08-19 | Grammars that are not Java-shaped. Identifier fallback works for case-insensitive keywords; a lexer rule may reference another lexer rule, end in a character-class lookahead, or carry several leading keyword guards; `%parser` pins classification the inference gets wrong; `< >` decides whether a reference-only rule is one token. Plus `%import` end to end, Base64 DFA tables (lifting a ~1100-state ceiling), case-folded literal keys, and generator-version stamping. 628 tests. |
| **0.7.1** | 2026-08-14 | Java grammar validated against javac's own parse phase over OpenJDK's langtools suite: agreement 95.55% → **99.45%** (5,614/5,645). Engine fixes: nullable start rules, trailing-input reporting, Unicode escape translation (JLS 3.3), hex escapes in character classes. New `%memo` directive plus a first-token guard and link-on-success CST building, holding parse throughput within ~1% of 0.7.0 despite the JLS 14.8 statement-expression restriction. The `*Probe` gates now actually execute. 576 tests. |
| **0.7.0** | 2026-08-05 | **Breaking.** 0.5.x interpreter path removed (146 files, ~38,900 lines) along with the `peglib-incremental` artifact; `org.pragmatica.peg.v6.*` collapsed to `org.pragmatica.peg.*`; maven goal `generate-v6` → `generate`; `pragmatica-lite:core` → 1.0.0-rc2. Adds JEP 401 value classes, `outer.new`, annotated type parameters, hex float literals, and a CST-shape gate. 528 tests, zero JBCT errors. |
| **0.6.3** | 2026-06-07 | Patch release. Legacy interpreter cut-failure symmetry: `Optional`/`ZeroOrMore`/`OneOrMore`/bounded repetition now restore the pending-trivia snapshot on `CutFailure`. Test suite reaches zero skips (1424 tests). |
| **0.6.2** | 2026-06-06 | Patch release. Shift operators (`<<`/`>>`/`>>>`) in field/local-var initializer context fixed via inline expansion of DFA-skipped lexer rules; loud `SkippedRuleReferenced` guard. Per-iteration `%whitespace` tokenization (folded form emits per-kind trivia; grammar-split workaround reverted). Selfhost fixture now parses with 0 diagnostics. |
| **0.6.1** | 2026-05-12 | Patch release. Doc-comment trivia kinds (`KIND_DOC_LINE_COMMENT`, `KIND_DOC_BLOCK_COMMENT`), per-rule `%recover` runtime, `%checkpoint` directive parsing, named captures and back-references restored, `MIXED`-rule char-level fallback, diagnostic cap honored. |
| **0.6.0** | 2026-05-11 | Clean-slate redesign. Tokens-first lex-then-parse, flat `int[]` CST, visitor pattern, always-on recovery, true partial reparse. ~12x faster than 0.5.x; parity-class with `javac`. **BREAKING** — see [migration guide](docs/MIGRATION-0.5-TO-0.6.md). |
| **0.5.1** | 2026-05-08 | Final 0.5.x — selfhost stability and minor fixes. |
| **0.5.0** | 2026-05-06 | Throughput engine Tier 1 — reference fixture 76.2 ms -> 19.12 ms. Incremental engine Phase 1 — 1.9x faster median. |
| **0.4.3** | 2026-05-06 | Interactive editing perf -19% median. |
| **0.4.1** | 2026-05-04 | 3.88x interpreter speedup; 3.0x incremental cursor-far edit. |
| **0.4.0** | 2026-05-03 | Multi-module split. API consolidation; consistent factory naming. |
| **0.3.6** | 2026-05-01 | Generator-side `%recover` per-rule overrides. |

---

## References

- [cpp-peglib](https://github.com/yhirose/cpp-peglib) — surface grammar syntax reference
- [PEG paper](https://bford.info/pub/lang/peg.pdf) — Bryan Ford's original
- [tree-sitter](https://tree-sitter.github.io/tree-sitter/) — architectural analog for
  flat-array CST + incremental parsing

---

## License

MIT
