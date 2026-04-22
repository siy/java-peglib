# peglib-formatter

Wadler-style pretty-printer framework for [peglib](../peglib-core) CSTs.

Sibling module to `peglib-core`; depends on it for `CstNode` / `Trivia` /
`Parser`.

## Status

v1 (0.3.3) — **framework ship**. Three deliverables:

- A small **doc algebra** (`text`, `line`, `softline`, `group`, `indent`,
  `concat`, `hardline`) and the Wadler / Lindig "best" renderer.
- A fluent **`Formatter`** that walks a `CstNode` tree, applies user-supplied
  per-rule `FormatterRule` functions, and emits a string at a target line
  width.
- A **`TriviaPolicy`** hook for choosing how trivia (whitespace / comments)
  flows into the output.

The framework is exercised by three demo formatters under
`src/test/java/.../examples/` (JSON, SQL-like SELECT/FROM/WHERE, arithmetic
expressions). Each demo asserts idempotency — `format(format(x)) == format(x)`
— against a small fixture set plus 50–100 fuzzed inputs.

## Quick start

```java
import org.pragmatica.peg.PegParser;
import org.pragmatica.peg.formatter.Formatter;
import org.pragmatica.peg.formatter.TriviaPolicy;

import static org.pragmatica.peg.formatter.Docs.*;

var parser = PegParser.fromGrammar(GRAMMAR).unwrap();
var formatter = new Formatter()
    .defaultIndent(2)
    .maxLineWidth(80)
    .triviaPolicy(TriviaPolicy.DROP_ALL)
    .rule("Block", (ctx, children) ->
        group(text("{"),
              indent(ctx.defaultIndent(), concat(line(), concat(children))),
              line(),
              text("}")));

String input = "{ foo bar }";
String out = parser.parseCst(input)
                   .flatMap(cst -> formatter.format(cst, input))
                   .unwrap();
```

## Doc algebra

| Constructor    | Renders as (flat)      | Renders as (break)             |
| -------------- | ---------------------- | ------------------------------ |
| `text(s)`      | `s`                    | `s`                            |
| `line()`       | single space           | newline + current indent       |
| `softline()`   | empty                  | newline + current indent       |
| `hardline()`   | newline + indent       | newline + indent (always)      |
| `group(d)`     | `d` flat **if it fits**| otherwise `d` in break mode    |
| `indent(n, d)` | `d` (no extra cols)    | `d` with `+n` indent on breaks |
| `concat(a, b)` | `a` then `b`           | `a` then `b`                   |
| `empty()`      | (nothing)              | (nothing)                      |

A `Group` decides flat vs. break by asking "does the entire flat form fit
within the remaining line budget?" via the Wadler / Lindig "best" algorithm
(see `internal/Renderer.java` and the [PRETTY-PRINTING.md](../docs/PRETTY-PRINTING.md)
design notes).

`hardline()` always breaks — useful for forcing line breaks (post-line-comment,
between top-level clauses).

## Trivia policies

Four built-ins, plus the `TriviaPolicy` functional interface for custom logic:

| Policy                  | Behavior                                                              |
| ----------------------- | --------------------------------------------------------------------- |
| `PRESERVE` (default)    | Emit every leading/trailing trivia verbatim                           |
| `STRIP_WHITESPACE`      | Drop whitespace, keep comments                                        |
| `DROP_ALL`              | Drop every trivia (rules supply spacing)                              |
| `NORMALIZE_BLANK_LINES` | Collapse runs of blank lines into at most one; comments preserved     |

## Idempotency property

The framework's **golden correctness gate** is

```
format(parse(format(parse(input)))) == format(parse(input))
```

for any input that successfully parses. Each demo formatter ships with an
idempotency suite (fixed fixtures + 50–100 randomly-generated inputs) that
asserts this property.

## Known limitations (v1)

- **Trivia round-trip is best-effort.** `peglib-core` 0.2.4 shipped trivia
  attribution threading but not the rule-exit position rewind needed for
  byte-for-byte round-trip — `RoundTripTest` stays `@Disabled` at the core
  level. The formatter inherits that gap: `format(parse(x))` may not equal
  `x` byte-for-byte for all inputs. Idempotency on a second pass holds.
- **Demo formatters use `TriviaPolicy.DROP_ALL`.** They drive spacing
  entirely from rules, sidestepping the attribution gap above. Custom
  formatters that rely on `TriviaPolicy.PRESERVE` should expect the same
  caveat.
- **Renderer is single-pass + group-local.** The `fits` probe peeks at the
  surrounding work stack but treats nested groups optimistically (assumes
  they will also fit flat). For pathological grammars where this leads to
  unwanted breaks, restructure the doc tree — there is no backtracking.

## Module layout

```
peglib-formatter/
├── pom.xml
├── src/main/java/org/pragmatica/peg/formatter/
│   ├── Doc.java                # sealed algebra (Text, Line, ..., Group)
│   ├── Docs.java               # static builders
│   ├── FormatContext.java      # node + source + indent + width + trivia policy
│   ├── FormatterRule.java      # functional interface
│   ├── Formatter.java          # fluent builder + walker (+ FormatterError)
│   ├── TriviaPolicy.java       # functional interface + built-ins
│   └── internal/
│       └── Renderer.java       # Wadler/Lindig best algorithm
└── src/test/java/org/pragmatica/peg/formatter/
    ├── DocAlgebraTest.java
    ├── RendererTest.java
    ├── IdempotencyTest.java
    └── examples/
        ├── JsonFormatter.java         + JsonFormatterTest.java
        ├── SqlFormatter.java          + SqlFormatterTest.java
        └── ArithmeticFormatter.java   + ArithmeticFormatterTest.java
```

## Design notes

See [`docs/PRETTY-PRINTING.md`](../docs/PRETTY-PRINTING.md) for the renderer
algorithm, the rule-walker design, and notes on writing your own demo
formatter.

## References

- Philip Wadler, "A Prettier Printer" (1998)
- Christian Lindig, "Strictly Pretty" (2000) — non-backtracking variant used
  here
