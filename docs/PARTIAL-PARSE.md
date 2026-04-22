# Partial parse — `parseRuleAt` (0.3.0)

`Parser.parseRuleAt` invokes a specific grammar rule at a given offset in the
input buffer, returning the produced CST subtree plus the absolute offset where
parsing stopped. Unlike `parseCst`, the matched rule is not required to consume
all remaining input — parsing stops when the rule itself finishes.

The feature is primarily consumed by the `peglib-incremental` module for
cursor-anchored reparsing (see [`docs/incremental/SPEC.md`](incremental/SPEC.md) §5.6)
but is also useful for grammar debugging and testing.

## API

```java
// In org.pragmatica.peg.parser

public interface Parser {
    // ... existing methods ...

    Result<PartialParse> parseRuleAt(Class<? extends RuleId> ruleId,
                                     String input,
                                     int offset);
}

public record PartialParse(CstNode node, int endOffset) {}
```

`RuleId` lives in `org.pragmatica.peg.action`. It's the same marker interface
used by the 0.2.6 programmatic-action API, and — importantly — the exact
interface that generated parsers' nested `sealed interface RuleId` extends.
That means the same `RuleId` class value works against both the interpreter
and a generated parser.

## Resolution rules

The rule name is resolved from `ruleId.name()`. The default implementation
returns `getClass().getSimpleName()`, which matches the sanitized rule-name
convention used by `ParserGenerator`. For custom marker records that override
`name()`, the interpreter instantiates the class (requires a zero-arg
constructor) to invoke the override.

## Semantics

| Input condition                          | Result                                           |
| ---------------------------------------- | ------------------------------------------------ |
| `ruleId` class unknown to the grammar    | `Result.failure(SemanticError)`                  |
| `offset < 0 \|\| offset > input.length()`  | `Result.failure(SemanticError)`                  |
| Rule matches prefix of `input[offset:]`  | `Result.success(PartialParse(node, endOffset))`  |
| Rule fails at `offset`                   | `Result.failure(UnexpectedInput)` from the normal parser error path |

`endOffset` is the absolute offset at which the rule stopped matching. When the
rule (or any nested rule) attaches trailing trivia, that trivia is included in
`endOffset`; the raw matched span is `partial.node().span()`.

The method reuses the packrat cache, trivia capture, and (for the runtime
interpreter) lambda/inline action machinery used by `parseCst`. There is no
state carried between calls — each invocation creates a fresh `ParsingContext`.

## Examples

### Interpreter

```java
record Number() implements RuleId {}

var parser = PegParser.fromGrammar("""
        Number <- < [0-9]+ >
        %whitespace <- [ \\t]*
        """).unwrap();

// offset 0
var p1 = parser.parseRuleAt(Number.class, "42", 0).unwrap();
// p1.endOffset() == 2

// offset 2 in "  42  "
var p2 = parser.parseRuleAt(Number.class, "  42  ", 2).unwrap();
// p2.endOffset() == 4
```

### Generated parser

Generated CST parsers emit a public `parseRuleAt(Class, String, int)` method
that returns the generated parser's own `PartialParse` record. The dispatch
keys are the nested `RuleId` record classes:

```java
var parser = new MyGeneratedParser();
var partial = parser.parseRuleAt(MyGeneratedParser.RuleId.Number.class, input, offset)
                    .unwrap();
```

The generator emits a private `Map<Class<? extends RuleId>, Supplier<CstParseResult>>`
dispatch table populated once at construction, so each call is a constant-time
lookup.

## Limitations

- **Action replay is not incremental.** Actions run every time `parseRuleAt`
  is invoked, the same as any other parse call. Incremental action replay
  (avoiding re-running actions over unchanged subtrees) is out of scope.
- **No error recovery.** `parseRuleAt` uses the standard fail-fast path. If
  you need `ADVANCED` diagnostics over a partial region, use the full
  `parseCstWithDiagnostics` API.

## See also

- [`docs/incremental/SPEC.md`](incremental/SPEC.md) — full incremental reparse spec
- [`GRAMMAR-DSL.md`](GRAMMAR-DSL.md) — grammar syntax reference
- [`RuleId`](../peglib-core/src/main/java/org/pragmatica/peg/action/RuleId.java)
  and
  [`Actions`](../peglib-core/src/main/java/org/pragmatica/peg/action/Actions.java)
  — marker type and programmatic action API
