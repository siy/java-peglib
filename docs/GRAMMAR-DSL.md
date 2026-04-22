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
4. [Directive interaction matrix](#directive-interaction-matrix)

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

## Related

- [Error Recovery](ERROR_RECOVERY.md) — recovery strategies, diagnostic
  formatting, `ParseResultWithDiagnostics` API
- [Trivia Attribution](TRIVIA-ATTRIBUTION.md) — how whitespace/comments
  are attached to CST nodes
- [`CHANGELOG.md`](../CHANGELOG.md) — per-release history of grammar-DSL
  additions
