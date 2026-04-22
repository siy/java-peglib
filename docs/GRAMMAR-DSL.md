# Grammar DSL Reference

Reference for advanced / less-obvious PEG grammar constructs in peglib:
the cut operator, rule-level error-diagnostic directives, and the
grammar-level suggestion vocabulary directive. Basic operators (sequence,
choice, quantifiers, lookahead, character classes) are covered in the
[README Grammar Syntax section](../README.md#grammar-syntax) and are not
repeated here.

## Table of contents

1. [Cut operator (`^` / `↑`)](#cut-operator)
2. [Rule-level directives](#rule-level-directives)
   - [`%expected "label"`](#expected)
   - [`%recover '<terminator>'`](#recover)
   - [`%tag "name"`](#tag)
3. [Grammar-level directives](#grammar-level-directives)
   - [`%suggest RuleName`](#suggest)
   - [`%import Grammar.Rule`](#import)
4. [Directive interaction matrix](#directive-interaction-matrix)
5. [Analyzer](#analyzer)
6. [Programmatic action attachment (lambda actions)](#programmatic-action-attachment)
7. [Grammar composition (`%import`)](#grammar-composition)

## Cut operator

Syntax: `^` or `↑` (Unicode alternative).

```peg
IfStmt <- 'if' ^ '(' Expr ')' Stmt
```

Once the parser has matched `'if'` and crossed the `^`, it **commits** to
the current choice alternative. If `'('` then fails, the failure does
**not** trigger backtracking to sibling alternatives — it is raised as a
`CutFailure`, which propagates up until caught by a rule boundary.

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
the outer choice's `A` fails after the cut fires, the failure is still a
`CutFailure` and propagates up — but the outer `Outer` was never
committed, so the failure is caught at `Outer`'s boundary and the parser
falls through to `B`. This matches cpp-peglib behaviour.

Summary: cut commits the tightest enclosing grouping / choice it lives
in. Rule boundaries catch `CutFailure` and convert it into that rule's
regular failure.

### Cut inside repetitions

A cut inside the body of `e*`, `e+`, or `e?` commits the current iteration.
A `CutFailure` fired inside an iteration is **not** swallowed as "end of
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
  `^`, then `';'` fails. `CutFailure` propagates out of the `*`, the
  repetition does **not** swallow it, and `Block` reports the error at
  the `}` position — not at `{`.

Same applies to `OneOrMore` (`+`), `Optional` (`?`), and bounded
`Repetition` (`e{n,m}`). `Choice` inside a repetition similarly passes
`CutFailure` through.

### CutFailure vs regular Failure

Internal to the parser:

| Failure type | Caused by | Effect |
|---|---|---|
| `ParseResult.Failure` | An alternative didn't match | Backtracks to try next alternative |
| `ParseResult.CutFailure` | A rule passed `^` then failed | Propagates through enclosing combinators; caught at rule boundary; converted to `Failure` for the containing rule |

Error recovery (`RecoveryStrategy.ADVANCED`) tags recovery diagnostics
triggered by a `CutFailure` with `error.unclosed` (vs `error.unexpected-input`
for non-cut recovery), making it programmatically distinguishable whether
a recovery point was hit after a committed rule or during ordinary
backtracking.

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

## Rule-level directives

Three directives may appear on a rule's right-hand side, after the
expression body and before any action / error-message trailer:

```peg
RuleName <- Expression %expected "label" %recover "}" %tag "error.mine"
```

All three are optional and order-independent among themselves. They are
**additive**: any rule that doesn't use them produces output byte-identical
to pre-0.2.4 parsers.

### `%expected`

`%expected "semantic label"`

Replaces the engine's default "expected one of X, Y, Z, …" token join
with a single semantic phrase when this rule fails.

```peg
Statement <- Expr ';' %expected "statement"
```

On failure, the diagnostic carries message `expected statement` and tag
`error.expected`. Without `%expected`, the diagnostic would enumerate
the raw first-token set of the rule's alternatives (often a long
punctuation list).

Useful on rules whose first-token set is large or uninformative (e.g. a
top-level `Statement` or `Expression` rule).

### `%recover`

`%recover "<literal-terminator>"`

Overrides the default ADVANCED recovery char-set (`,`, `;`, `}`, `)`,
`]`, `\n`) with a single string literal scoped to this rule's body. On
recovery, the parser scans forward to the first occurrence of the
terminator, emits an `Error` node spanning the skipped text, and resumes
after the terminator.

```peg
Block <- '{' Stmt* '}' %recover "}"
Stmt  <- [a-z]+ ';'
```

For `{ foo; @@@; }`, the default recovery would stop at the first `;`
inside the block (producing noise). With `%recover "}"`, recovery
rewinds the enclosing block as one unit. Terminators can be multi-char
(e.g. `%recover ">>"`).

Diagnostics emitted by `%recover` carry tag `error.unclosed`.

### `%tag`

`%tag "tag.name"`

Attaches a machine-readable tag to diagnostics produced by this rule's
failures, overriding any built-in tag (`error.expected` /
`error.unclosed` / `error.unexpected-input`).

```peg
JsonString <- '"' [^"]* '"' %tag "json.string"
```

Callers read it via `diagnostic.tag()` — useful for IDE categorization,
quick-fix dispatch, and localization. Tags do not appear in the Rust-style
formatted output; they are purely machine-readable.

## Grammar-level directives

These appear at the top level of the grammar, alongside `%whitespace`.

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
between parse attempts (this matters for the planned incremental parser
in 0.3.1, which forwards the dictionary across reparses without
recomputation).

If no `%suggest` directive is declared, no suggestion logic runs and
error-path cost is unchanged.

## Directive interaction matrix

| Directive | Scope | Sets tag | Affects parse outcome | Affects hot path |
|---|---|---|---|---|
| `%expected` | rule | `error.expected` (unless `%tag` overrides) | no — only diagnostic message | no |
| `%recover` | rule | `error.unclosed` (unless `%tag` overrides) | yes — changes where recovery resumes | only on failure path |
| `%tag` | rule | explicit value | no | no |
| `%suggest` | grammar | (adds note to diagnostics) | no | failure-only Levenshtein scan |

None of the four affects the success path. All four are opt-in: a grammar
that uses none of them produces output byte-identical to pre-0.2.4
parsers, per the `Phase1ParityTest` / `CorpusParityTest` suites.

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
| `grammar.has-backreference` | INFO | Rule uses `$name` back-reference — forward-compat note: incremental parsing (planned for 0.3.1) falls back to full reparse on such rules |

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

## Programmatic action attachment

*Since 0.2.6.*

Actions can be attached to rules **programmatically** — via Java lambdas
keyed by a type-safe `RuleId` class — without modifying the grammar file.
The existing inline `{ ... }` actions still work; the two mechanisms
coexist.

### API shape

The library ships two small types under `org.pragmatica.peg.action`:

- `RuleId` — a marker interface. A rule identifier is an implementing
  record whose simple class name matches the rule name.
- `Actions` — an immutable, composable map from `RuleId` classes to
  `Function<SemanticValues, Object>` lambdas. Every mutation returns a
  new instance; the original is untouched.

```java
import org.pragmatica.peg.action.Actions;
import org.pragmatica.peg.action.RuleId;

record Number() implements RuleId {}
record Sum() implements RuleId {}

var actions = Actions.empty()
    .with(Number.class, sv -> sv.toInt())
    .with(Sum.class,    sv -> (Integer) sv.get(0) + (Integer) sv.get(1));

var grammar = """
    Sum    <- Number '+' Number
    Number <- < [0-9]+ >
    %whitespace <- [ ]*
    """;

var parser = PegParser.fromGrammar(grammar, actions).unwrap();
parser.parse("2 + 3").unwrap();   // → 5
```

`PegParser.fromGrammar(String, Actions)` and
`PegParser.fromGrammar(String, ParserConfig, Actions)` thread the
attachments through to the interpreter.

### Lambda vs inline action precedence

When a rule has **both** an inline action in the grammar and a lambda
attached via `Actions`, the **lambda wins**. The inline action is still
parsed — it just isn't executed for that rule. This lets callers override
specific rules without rewriting the grammar.

```java
// Grammar says: return sv.toInt(); Lambda says: multiply by 10.
var grammar = "Number <- < [0-9]+ > { return sv.toInt(); }";
var actions = Actions.empty().with(Number.class, sv -> sv.toInt() * 10);
PegParser.fromGrammar(grammar, actions).unwrap().parse("42");  // → 420
```

Rules without a lambda attachment fall back to their inline action (if
any), or return the CST node as before.

### Generated parsers

The standalone parsers emitted by
`PegParser.generateParser(...)` / `PegParser.generateCstParser(...)`
expose a nested `sealed interface RuleId extends
org.pragmatica.peg.action.RuleId` with one parameter-less record per
grammar rule. AST-path parsers additionally expose a `withAction` fluent
setter and a nested `SemanticValues` record, so the same lambda override
mechanism works against a pre-compiled parser:

```java
// Using the generated parser as a plain class.
var parser = new MyGeneratedParser()
    .withAction(MyGeneratedParser.RuleId.Number.class, sv -> sv.toInt() * 10);
parser.parse("42");
```

### Forward-compatibility with `parseRuleAt` (0.3.0)

The `RuleId` shape is deliberately minimal — parameter-less marker
records whose identity is the class itself — so that the upcoming
`parseRuleAt(Class<? extends RuleId>, String input, int offset)` entry
point can dispatch on the same types without a migration. Callers who
adopt lambda actions on 0.2.6 will not need to rewrite `RuleId` usages
when `parseRuleAt` lands.

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

### RuleId emission

Imported rules participate in the composed grammar's `RuleId` sealed
interface emission (see the lambda-actions section above):

- Root rule `MyAnnotation` → `RuleId.MyAnnotation`
- `%import Java25.Type` → `RuleId.Java25Type` (underscore-stripped
  after sanitization)
- `%import Java25.Expression as JavaExpr` → `RuleId.JavaExpr`

The grammar-qualified prefix preserves source-grammar provenance for
unaliased imports; `as` renames let callers pick an arbitrary local
name.

### Public API

Parsers that declare `%import` directives must be built through the
three-arg `PegParser.fromGrammar` overload that accepts a
`GrammarSource`:

```java
import org.pragmatica.peg.PegParser;
import org.pragmatica.peg.grammar.GrammarSource;
import org.pragmatica.peg.parser.ParserConfig;

var source = GrammarSource.inMemory(java.util.Map.of(
    "Java25", java.nio.file.Files.readString(java.nio.file.Path.of("grammars/Java25.peg"))
));
var parser = PegParser.fromGrammar(rootGrammarText, ParserConfig.DEFAULT, source).unwrap();
```

Built-in `GrammarSource` strategies:

- `GrammarSource.inMemory(Map<String,String>)` — name → text map (tests).
- `GrammarSource.classpath(ClassLoader)` — reads `<name>.peg` from a
  classloader resource root.
- `GrammarSource.classpath()` — uses the current thread's context
  classloader.
- `GrammarSource.filesystem(Path)` — reads `<name>.peg` from a
  configured directory.
- `GrammarSource.chained(a, b, c)` — tries each source in order; first
  hit wins.
- `GrammarSource.empty()` — the default; causes any `%import` to fail
  with a "grammar not found" error.

If your root grammar declares no `%import` directives, you can keep
using the two-arg `fromGrammar` overload — no behaviour change.

## Related

- [Error Recovery](ERROR_RECOVERY.md) — recovery strategies, diagnostic
  formatting, `ParseResultWithDiagnostics` API
- [Trivia Attribution](TRIVIA-ATTRIBUTION.md) — how whitespace/comments
  are attached to CST nodes
- [`CHANGELOG.md`](../CHANGELOG.md) — per-release history of grammar-DSL
  additions
