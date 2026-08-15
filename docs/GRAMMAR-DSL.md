# Grammar DSL Reference

Reference for advanced / less-obvious PEG grammar constructs in peglib:
the cut operator, rule-level error-diagnostic directives, and the
grammar-level suggestion vocabulary directive. Basic operators (sequence,
choice, quantifiers, lookahead, character classes) are covered in the
[README Grammar Syntax section](../README.md#grammar-syntax) and are not
repeated here.

## Table of contents

1. [Cut operator (`^` / `↑`)](#cut-operator)
2. [Rule-level directives — accepted but inert](#rule-level-directives--accepted-but-inert)
3. [Grammar-level directives](#grammar-level-directives)
   - [`%suggest RuleName`](#suggest)
   - [`%memo RuleName`](#memo)
   - [`%word` (inert)](#word--accepted-but-inert)
   - [`%import Grammar.Rule`](#grammar-composition)
4. [Directive interaction matrix](#directive-interaction-matrix)
5. [Analyzer](#analyzer)
6. [Actions were removed in 0.6.0](#actions-were-removed-in-060)
7. [Grammar composition (`%import`)](#grammar-composition)
8. [Left recursion is rejected](#left-recursion)
9. [Related](#related)

## Cut operator

Syntax: `^` or `↑` (Unicode alternative).

```peg
IfStmt <- 'if' ^ '(' Expr ')' Stmt
```

Once the parser has matched `'if'` and crossed the `^`, it **commits** to
the current choice alternative. If `'('` then fails, the failure does
**not** trigger backtracking to sibling alternatives — it is raised as a
*committed failure*, which propagates up until caught by a rule boundary.

Cut enables two things:

- **Accurate error position.** Without cut, a failure mid-`IfStmt` rewinds
  to the start of the enclosing choice, which usually reports "expected
  one of …" at the wrong offset. With cut, the error is reported at the
  furthest position actually reached.
- **Faster parsing on keyword-discriminated choices.** The emitted parser
  doesn't allocate state to try subsequent alternatives; the generator
  can also fold the prefix into choice-dispatch logic.

### Scope: cut commits only the innermost enclosing choice

```peg
Outer <- A / B / C
A     <- 'a' (X / Y ^ Z)
```

A cut inside the inner `(X / Y ^ Z)` commits that inner choice only. If
the outer choice's `A` fails after the cut fires, the failure is still
*committed* and propagates up — but the outer `Outer` was never
committed, so the failure is caught at `Outer`'s boundary and the parser
falls through to `B`. This matches cpp-peglib behaviour.

Summary: cut commits the tightest enclosing grouping / choice it lives
in. Rule boundaries catch a *committed failure* and convert it into that
rule's regular failure.

### Cut inside repetitions

A cut inside the body of `e*`, `e+`, or `e?` commits the current iteration.
A *committed failure* fired inside an iteration is **not** swallowed as "end of
repetition" — it propagates out of the repetition, exiting it with a
failure instead of a successful partial match. (Early peglib versions
treated the cut as end-of-repetition; that was a bug fixed in 0.1.5.)

Concretely:

```peg
Block <- '{' (Stmt ^ ';')* '}'
```

- `{ foo; bar; }` — all `Stmt ^ ';'` iterations succeed; the repetition
  terminates when `Stmt` fails (no cut yet).
- `{ foo; bar }` — the second iteration matches `bar` as `Stmt`, crosses
  `^`, then `';'` fails. The *committed failure* propagates out of the `*`, the
  repetition does **not** swallow it, and `Block` reports the error at
  the `}` position — not at `{`.

Same applies to `OneOrMore` (`+`), `Optional` (`?`), and bounded
`Repetition` (`e{n,m}`). `Choice` inside a repetition similarly passes
a *committed failure* through.

### How cut is implemented

In the generated parser each `Choice` declares a `cutHit_<label>` boolean.
Crossing a `^` inside an alternative sets it; when that alternative then fails,
the flag suppresses the remaining alternatives and the whole `Choice` fails
instead of backtracking.

Earlier revisions of this document described sealed `ParseResult.Failure` /
`ParseResult.CutFailure` types and a `RecoveryStrategy.ADVANCED` that tagged
cut-triggered diagnostics. Those belong to the 0.5.x interpreter: `ParseResult`
is now the plain record `(CstArray cst, List<Diagnostic> diagnostics)`, there is
one always-on recovery mechanism, and diagnostics carry no tag. Cut's
*observable* behaviour is unchanged — see `CutOperatorTest`.

### When to use cut

Put `^` immediately after the **discriminating token** of an
alternative — the keyword or punctuation that uniquely identifies which
branch of a choice you're on. For keyword-led statements, this is the
keyword. For `@interface`, it's `'@' 'interface'`. For record patterns,
it's `'record'`.

Avoid cutting on tokens shared across alternatives (leads to false
commits). Add a word-boundary lookahead where keywords could prefix
identifiers:

```peg
RecordDecl <- 'record' ![a-zA-Z0-9_$] ^ Identifier ...
```

Without the `![a-zA-Z0-9_$]` guard, the grammar would commit to a record
declaration when seeing `recordResult` as an identifier.

## Rule-level directives — accepted but inert

Three directives may appear on a rule's right-hand side, after the expression
body:

```peg
RuleName <- Expression %expected "label" %recover "}" %tag "error.mine"
```

**They parse, they are stored on `Rule`, and the generator never reads them.**
`Rule` carries `expected()`, `recover()` and `tag()`; `ParserGenerator` does not
reference any of the three. Writing them changes nothing about parsing,
diagnostics, or recovery.

They are documented here as *accepted syntax* so that a grammar carrying them
still loads, and so nobody spends time wondering why they have no effect:

| directive | intended meaning | actual 0.7.x behaviour |
|---|---|---|
| `%expected "label"` | replace the enumerated first-token set in the failure message | inert — the default message is always used |
| `%recover "}"` (rule trailer) | rule-scoped recovery terminator | inert — use the grammar-level form below |
| `%tag "name"` | machine-readable diagnostic tag | inert — `Diagnostic` has no tag component |

`Diagnostic` is `(severity, offset, length, message, expected, found)`. There is
no `tag()` accessor, and the tags earlier revisions of this document described
(`error.unclosed`, `error.expected`, `error.unexpected-input`) are not emitted
anywhere in the engine.

### Use the grammar-level `%recover` instead

Per-rule recovery *is* implemented, but through the grammar-level directive,
which takes a character class and a rule name:

```peg
%recover [;] Stmt
%recover [}] Block
```

That form has been honoured per-rule since 0.6.1 — see
`PerRuleRecoverDirectiveTest` for the dispatch contract and its limits, and
[Error Recovery](ERROR_RECOVERY.md#choosing-recovery-points) for guidance on
picking sync sets.

## Grammar-level directives

These appear at the top level of the grammar, alongside `%whitespace`.

### `%whitespace` shape and per-kind trivia classification

In the 0.6.x tokens-first lexer, each `%whitespace` Choice alternative is
absorbed into the DFA at its own trivia kind, decided **structurally** by the
alternative's leading literal: a whitespace char-class alternative → `WHITESPACE`,
an alternative beginning with `'//'` → `LINE_COMMENT`, with `'/*'` →
`BLOCK_COMMENT`. The doc variants (`///` → `DOC_LINE_COMMENT`, `/**` followed by
non-`/` → `DOC_BLOCK_COMMENT`) cannot be told apart from their regular form by
the grammar alone (both share the same `'//' …` / `'/*' …` alternative), so they
are refined from the matched span text after the structural base kind is set.

As of 0.6.2 **both shapes below produce the same per-kind token stream** — the
folded `(...)*` form no longer collapses a mixed-trivia run into a single
`WHITESPACE` token:

```peg
# Folded form — DfaBuilder.absorbWhitespace absorbs each alternative at its kind:
%whitespace <- ([ \t\r\n] / '//' [^\n]* / '/*' (!'*/' .)* '*/')*

# Split form — equivalent token stream:
%whitespace <- [ \t\r\n]+ / '//' [^\n]* / '/*' (!'*/' .)* '*/'
```

The folded form's outer `*` is dropped during absorption (the lexer's own
maximal-munch loop supplies the repetition), and the whitespace char-class
alternative is absorbed as one-or-more, so the DFA start state never accepts the
empty string — the `"LEXER rule '%whitespace' matches the empty string"` warning
does **not** fire for either shape. The canonical example grammar at
`peglib-core/src/test/resources/java25.peg` uses the folded form.

### `%suggest`

`%suggest RuleName`

Designates `RuleName`'s literal alternatives as a suggestion vocabulary.
When parsing fails on an identifier-like token near the rule's position,
the engine computes Levenshtein distance between the failing token and
each literal in the vocabulary; matches with distance ≤ 2 produce a
`"help: did you mean 'X'?"` note on the emitted diagnostic.

```peg
%suggest Keyword
Keyword <- 'class' / 'interface' / 'enum' / 'record' / 'sealed'
```

Input `clss` produces a diagnostic with note `help: did you mean 'class'?`.

Multiple `%suggest` directives are permitted; their vocabularies combine.
Vocabularies are precomputed once per `ParsingContext` and do not change
between parse attempts (this matters for the incremental parser introduced
in 0.3.0–0.3.2, which forwards the dictionary across reparses without
recomputation).

If no `%suggest` directive is declared, no suggestion logic runs and
error-path cost is unchanged.

### `%memo`

`%memo RuleName` *(0.7.1)*

Marks a rule whose successful parse should be cached at a token position.
When backtracking drops the rule's subtree, the builder salvages it; if the
same rule is then re-parsed at the *same token position*, the salvaged
subtree is spliced back in instead of being parsed again.

```peg
%memo Args
Args <- Item (',' Item)*
```

This is a **single-slot cache, not packrat** — 0.6.0 dropped packrat as
unnecessary under the tokens-first design, and this does not revive it. Only
the most recently completed occurrence is retained.

**When it helps.** Only when the same input is parsed twice at the same
position through *different* enclosing rules, so ordinary choice ordering
cannot avoid the repeat. The motivating case is Java's JLS 14.8
statement-expression restriction: a statement is first tried as
`Postfix (assign-op) Expr` and, when the assignment operator fails, re-parsed
as `Primary CallChain`. Those are different rules, so the shareable unit is
the argument list both paths parse at the same position — hence `%memo Args`
rather than `%memo Postfix`. On the 40k-LOC selfhost fixture this is worth
about 7% of warm parse time.

If a rule is simply reached twice by a Choice trying successive
alternatives, fix the grammar instead — reordering or factoring the common
prefix costs nothing at runtime.

**Semantics.** Replay is observationally identical to re-parsing: the CST,
the diagnostics and `reconstruct()` are unchanged. It is purely an
optimisation, and correspondingly it is *silently disabled* in two cases:

- the grammar uses named captures anywhere (replay would skip capture
  registration, changing back-reference behaviour);
- the named rule is classified LEXER or MIXED rather than PARSER.

Unknown rule names are accepted without error, matching `%checkpoint`. All
three silent cases are reported by the analyzer — see
`grammar.memo-unknown-rule` and `grammar.memo-non-parser-rule` below.

### `%word` — accepted but inert

`%word <- Expression`

Parsed, stored on `Grammar`, and propagated through import composition — but **never
consumed** by the lexer or the generator. In cpp-peglib `%word` supplies the
word-boundary rule used when matching keywords; peglib does not implement that, and
spells word boundaries inline instead:

```peg
RecordDecl <- 'record' ![a-zA-Z0-9_$] Identifier
```

Declaring `%word` changes nothing. The analyzer reports it as
`grammar.inert-directive` so it fails loudly at lint time rather than silently.

## Directive interaction matrix

| Directive | Scope | Honoured | Affects parse outcome | Affects hot path |
|---|---|---|---|---|
| `%expected` (rule trailer) | rule | **no — inert** | no | no |
| `%recover "lit"` (rule trailer) | rule | **no — inert** | no | no |
| `%tag` (rule trailer) | rule | **no — inert** | no | no |
| `%recover [chars] Rule` | grammar | yes | yes — changes where recovery resumes | only on failure path |
| `%whitespace` | grammar | yes | yes — defines trivia | lexer |
| `%word` | grammar | **no — inert** | no | no |
| `%suggest` | grammar | yes | no — adds a note to diagnostics | failure-only Levenshtein scan |
| `%checkpoint` | grammar | yes | no | incremental reparse only |
| `%memo` | grammar | yes | no — output is identical either way | yes — success path, by design |

Only `%memo` deliberately changes success-path *cost*, and it does not change
success-path *output*. Everything else is either failure-path or structural. A
grammar using none of them parses exactly as it would without directives.

## Analyzer

Added in 0.2.5. Static lint checks over a parsed `Grammar` IR — run from
code via `Analyzer.analyze(grammar)` or from the CLI via
`org.pragmatica.peg.analyzer.AnalyzerMain <grammar.peg>`. The
`peglib-maven-plugin` also wraps this as a `peglib:lint` goal.

Each finding has a stable tag for tooling integration. The full catalog:

| Tag | Severity | Description |
|---|---|---|
| `grammar.unreachable-rule` | WARNING | Rule not transitively reachable from the start rule |
| `grammar.ambiguous-choice` | WARNING | Choice alternatives begin with identical literal first char |
| `grammar.nullable-rule` | INFO / WARNING | Rule can match the empty string. Promoted to WARNING when the rule is on a direct left-recursive path (infinite-loop risk) |
| `grammar.duplicate-literal` | ERROR | Literal repeated verbatim within the same `Choice` |
| `grammar.whitespace-cycle` | ERROR | `%whitespace` expression transitively references itself |
| `grammar.has-backreference` | INFO | Rule uses `$name` back-reference — forward-compat note: incremental parsing (since 0.3.2) falls back to full reparse on such rules |
| `grammar.memo-unknown-rule` | WARNING | `%memo` names a rule the grammar does not define — the directive is ignored |
| `grammar.memo-non-parser-rule` | WARNING | `%memo` targets a LEXER or MIXED rule; only PARSER rules are memoised — the directive is ignored |
| `grammar.inert-directive` | WARNING | A directive the front-end accepts but the generator never reads (`%word`, rule-level `%expected` / `%recover` / `%tag`) — declaring it has no effect |

The ambiguous-choice check is conservative: it flags only choices where
*every* alternative has a fixed literal prefix. Rule-reference-prefixed or
char-class-prefixed alternatives are never flagged, since overlap may be
legitimately resolved downstream.

Nullable analysis is a fix-point over the rule map; direct left-recursion
detection walks first-non-predicate elements through transparent wrappers
(`Sequence`, `Group`, `TokenBoundary`, `Ignore`, `Capture`,
`CaptureScope`).

Output format (Rust-`cargo check` style):

```text
warning[grammar.ambiguous-choice]: choice alternatives at positions [0, 1] share first char 'f' (potential ambiguity)
  --> grammar.peg: Start

error[grammar.duplicate-literal]: rule 'Start' has duplicate literal 'foo' in Choice
  --> grammar.peg: Start

analyzer: 1 error, 1 warning, 0 info
```

The CLI exits with status `0` when no errors, `1` when errors found,
`2` on I/O or grammar-parse failure. Warnings/info alone do not fail
the CLI — only `ERROR` findings do.

## Actions were removed in 0.6.0

Earlier versions supported inline `{ ... }` action blocks in the grammar and
programmatic lambda attachment via an `Actions` builder and `RuleId` marker
types. **All of it is gone**, along with `Action`, `SemanticValues`,
`ActionCompiler`, `Actions` and `RuleId`.

CST → domain transformation is now a separate concern. The generator emits a
`GVisitor<T>` stub per grammar with one method per parser rule; subclass it and
override selectively. See [`VISITOR-TUTORIAL.md`](VISITOR-TUTORIAL.md) and the
migration guide's "Pattern: Action-based semantic transform".

<a id="grammar-composition"></a>
## Grammar composition (`%import`)

Added in 0.2.8.

Peglib supports surface-level grammar composition through the
`%import` directive. Use it to reuse rules defined in another `.peg`
grammar without copy-pasting.

### Syntax

```peg
%import Java25.Type
%import Java25.Expression as JavaExpr

MyAnnotation <- '@' Identifier '(' (JavaExpr (',' JavaExpr)*)? ')'
%whitespace <- [ \t\r\n]*
```

- `%import GrammarName.RuleName` — imports the rule, exposed in the
  composed grammar under the name `GrammarName_RuleName`
  (underscore-joined).
- `%import GrammarName.RuleName as LocalName` — imports the rule,
  exposed under `LocalName` (no grammar-name prefix).

### Transitive closure

When `%import G.R` is resolved, the imported rule **plus every rule
reachable from it** is pulled into the composed grammar. Transitive
rules are renamed to `G_OriginalName`; only the explicitly-imported
top-level rule is affected by an `as` alias.

Example: given `Java25.peg` defining `Type → Identifier` and
`Identifier → [a-zA-Z_][a-zA-Z0-9_]*`, a root that does
`%import Java25.Type` will end up with both `Java25_Type` and
`Java25_Identifier` as visible rules in the composed grammar, with
`Java25_Type`'s body internally referencing `Java25_Identifier`.

### Whitespace sharing

V1 is surface-level composition: the **composed grammar has exactly one
`%whitespace` binding — the root's**. Imported grammars' own
`%whitespace` directives are ignored for imported rules when those
rules are embedded in the composed grammar. Users must ensure imported
grammars share a whitespace convention with the root, or explicitly
rewrite imported rules if whitespace semantics differ. Per-rule
whitespace context is deferred to a future release.

### Collision policy

- **Explicit imports** — `%import G.R` whose local name (default
  `G_R`, or the `as` alias) collides with a rule name already defined
  in the root grammar is a **hard error**. Users must provide an
  explicit `as` rename.
- **Transitive imports** — when a transitively-pulled rule's name
  (after prefixing to `G_X`) already exists in the root, **the root
  wins silently**. The imported version is dropped. Users needing both
  can import the inner rule explicitly with a distinct alias:
  `%import G.Identifier as GIdentifier`.

### Cycle detection

Grammar import cycles (`A.peg → B.peg → A.peg`) are a hard error
detected at resolve time. The error message shows the offending import
chain.

### Public API

Pass a `GrammarSource` alongside the grammar text. The transitive import closure is
resolved before generation, so the composed grammar is what gets classified and
compiled:

```java
import org.pragmatica.peg.PegParser;
import org.pragmatica.peg.grammar.GrammarSource;

var source = GrammarSource.inMemory(Map.of(
    "Shared", Files.readString(Path.of("grammars/Shared.peg"))
));
var parser = PegParser.fromGrammar(rootGrammarText, source).unwrap();
```

*(Added in 0.7.2. Before that the directive parsed and `GrammarResolver` existed, but
nothing connected them — `fromGrammar` went straight to classification and the grammar
failed with a misleading "references undefined rule".)*

A grammar declaring no imports behaves identically under either overload. Calling the
single-argument `fromGrammar` on a grammar that *does* declare imports fails with an
actionable message naming the fix rather than reporting a phantom undefined rule.

**Caching.** The parser cache is keyed by grammar text alone, which cannot distinguish
the same root text resolved against two different sources. Results are therefore cached
only when the root grammar declares no imports; a grammar with imports is recompiled on
every call. That is a deliberate trade — a wrong cache hit would hand back a parser
built from someone else's imports.

Built-in `GrammarSource` strategies:

- `GrammarSource.inMemory(Map<String,String>)` — name → text map (tests).
- `GrammarSource.classpath(ClassLoader)` — reads `<name>.peg` from a classloader
  resource root.
- `GrammarSource.classpath()` — uses the current thread's context classloader.
- `GrammarSource.filesystem(Path)` — reads `<name>.peg` from a directory.
- `GrammarSource.chained(a, b, c)` — tries each source in order; first hit wins.
- `GrammarSource.empty()` — causes any `%import` to fail with "grammar not found".

### Through the maven plugin

`generate`, `check` and `lint` all resolve imports from a filesystem source rooted at the
grammar file's own directory, so `%import Shared.Rule` finds `Shared.peg` beside the root
grammar with no configuration. Override with the `importDirectory` parameter
(`peglib.importDirectory`).

<a id="left-recursion"></a>
## Left recursion is rejected

**Both direct and indirect left recursion are rejected at
`PegParser.fromGrammar`**, with a witness naming the offending rule chain.

```peg
Expr <- Expr '+' Term / Term    # rejected
```

```
Grammar contains a left-recursive rule:
  - Rule 'Expr' is left-recursive: Expr → Expr. PEG cannot express left
    recursion; rewrite as right-recursive, e.g. 'A <- B (op B)*' instead
    of 'A <- A op B / B'.
```

Rewrite as a repetition and rebuild associativity in the visitor:

```peg
Expr <- Term ('+' Term)*
Term <- [0-9]+
```

The CST is flat — `Term ('+' Term)*` — so a left-associative fold is a loop
over `cst.children(...)` in your `GVisitor<T>`, which is cheaper than the
parser-level machinery it replaces.

> **History.** 0.2.9 supported *direct* left recursion via Warth-style
> seed-and-grow over the packrat cache. 0.6.0 dropped packrat along with the
> interpreter, and seed-and-grow depended on it: the algorithm needs the cache
> to persist seeds across growth iterations. Rather than reimplement it in the
> generated recursive-descent parser, left recursion became a rejection with a
> rewrite hint. `LeftRecursionDetector` produces the witness; see
> `LeftRecursionTest` and `LeftRecursionDetectorTest`.

### Detection API

`LeftRecursionDetector.detect(grammar)` runs the check directly if you want the
witness without going through `fromGrammar`:

```java
var result = LeftRecursionDetector.detect(grammar);

result.onSuccess(detection -> {
    if (detection.hasErrors()) {
        detection.errors()
                 .forEach(e -> System.err.println(e.message()));
    }
});
```

`LeftRecursionError` carries the offending `ruleName()` and the
`witnessCycle()` that reaches it, and `message()` renders the rewrite hint
shown above.

## Related

- [Error Recovery](ERROR_RECOVERY.md) — the always-on panic-mode mechanism,
  the `Diagnostic` record, and error nodes in the CST
- [Trivia Attribution](archive/TRIVIA-ATTRIBUTION.md) — how whitespace/comments
  are attached to CST nodes (archived)
- [Partial Parse](archive/PARTIAL-PARSE.md) — the 0.3.0 `parseRuleAt` API for
  cursor-anchored partial parsing and incremental reparse (archived)
- [`CHANGELOG.md`](../CHANGELOG.md) — per-release history of grammar-DSL
  additions
