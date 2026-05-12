# Visitor Pattern Tutorial — peglib 0.6.x

This walkthrough builds a small calculator end-to-end: define a PEG grammar, compile
it with peglib, and evaluate `3 + 5 * 2` to `13` via a `GVisitor<Integer>` subclass.

By the end you'll know:

- How peglib emits a per-grammar `GVisitor<T>` stub (one method per parser rule).
- How `visit`, `visitChildren`, `defaultResult`, and `aggregateResult` fit together.
- How to recurse from a parent rule into a child node via the CST.
- How to handle `Error` nodes left behind by panic-mode recovery.
- A few patterns that show up repeatedly in real visitors (lists, AST building,
  type checking, pretty-printing).

The visitor pattern replaces 0.5.x inline `{ ... }` action blocks. Visitors are
plain Java code in your own files — easier to test, debug, and refactor than
strings embedded in a grammar.

---

## What you'll build

A four-rule arithmetic calculator:

- `Expr` — sum of `Term`s separated by `+` or `-`.
- `Term` — product of `Factor`s separated by `*` or `/`.
- `Factor` — a `Number` or a parenthesised `Expr`.
- `Number` — one or more digits.

Input `3 + 5 * 2` parses to a CST and the visitor evaluates to the integer `13`,
respecting precedence (the grammar layering does the work; the visitor just folds).

---

## Step 1 — Define the grammar

Put this in `src/main/peg/Calc.peg` (or paste it into a string for the tutorial —
both work):

```peg
Expr   <- Term (('+' / '-') Term)*
Term   <- Factor (('*' / '/') Factor)*
Factor <- Number / '(' Expr ')'
Number <- < [0-9]+ >
%whitespace <- [ \t]*
```

The `< ... >` token-boundary markers around `[0-9]+` ensure `Number` produces a leaf
node whose text is exactly the matched digits.

`%whitespace <- [ \t]*` tells the lexer to swallow space and tab characters between
tokens. They live in the resulting `TokenArray` as `KIND_WHITESPACE` trivia tokens,
not as content.

---

## Step 2 — Generate the parser

You have two options. For tutorials and REPLs, use `PegParser.fromGrammar`. For
production builds, use the Maven plugin.

### Option A — runtime compile (tutorial path)

```java
import org.pragmatica.peg.v6.PegParser;
import org.pragmatica.peg.v6.Parser;

String grammar = """
    Expr   <- Term (('+' / '-') Term)*
    Term   <- Factor (('*' / '/') Factor)*
    Factor <- Number / '(' Expr ')'
    Number <- < [0-9]+ >
    %whitespace <- [ \\t]*
    """;

Parser parser = PegParser.fromGrammar(grammar).unwrap();
```

On the first call for a given grammar text, `fromGrammar` runs classify -> DFA build ->
lexer codegen -> parser codegen -> visitor codegen -> JDK Compiler API, then caches the
resulting `Parser` by exact grammar text. Cost is ~100-500 ms cold; subsequent calls
return in sub-millisecond time.

Note: in this runtime-compile path the generated `GVisitor` class is compiled into
peglib's internal classloader. You can subclass it via reflection, or, more
ergonomically, you can do the dispatch yourself (we show both styles below).

### Option B — build-time codegen (production path)

Configure `peglib-maven-plugin` in your `pom.xml`:

```xml
<plugin>
    <groupId>org.pragmatica-lite</groupId>
    <artifactId>peglib-maven-plugin</artifactId>
    <version>0.6.1</version>
    <executions>
        <execution>
            <goals><goal>generate-v6</goal></goals>
            <configuration>
                <grammarFile>src/main/peg/Calc.peg</grammarFile>
                <outputDirectory>${project.build.directory}/generated-sources/peg</outputDirectory>
                <packageName>com.example.calc</packageName>
                <visitorClassName>CalcVisitor</visitorClassName>
            </configuration>
        </execution>
    </executions>
</plugin>
```

After `mvn generate-sources` you'll have three Java files under
`com.example.calc`: `GLexer.java`, `GParser.java`, `CalcVisitor.java`. Both depend
only on `peglib-runtime` + `pragmatica-lite:core`. Subclass `CalcVisitor` directly.

The mojo is implemented by
[`peglib-maven-plugin/.../GenerateV6Mojo.java`](../peglib-maven-plugin/src/main/java/org/pragmatica/peg/maven/GenerateV6Mojo.java).

---

## Step 3 — Generate the visitor skeleton

The visitor codegen is implemented at
[`peglib-core/src/main/java/org/pragmatica/peg/v6/generator/VisitorGenerator.java`](../peglib-core/src/main/java/org/pragmatica/peg/v6/generator/VisitorGenerator.java).
For the calculator grammar it emits roughly:

```java
package com.example.calc;

import org.pragmatica.peg.v6.cst.CstArray;

public abstract class CalcVisitor<T> {

    protected static final int RULE_Expr_KIND   = 0;
    protected static final int RULE_Term_KIND   = 1;
    protected static final int RULE_Factor_KIND = 2;
    // Number is classified LEXER (it bottoms out at [0-9]+) and is not a parser rule,
    // so it does not get its own RULE_Number_KIND constant or visitNumber stub.
    // Number text is reachable from the Factor CST node via cst.children(idx).

    public T visit(CstArray cst, int nodeIdx) {
        int kind = cst.kindAt(nodeIdx);
        return switch (kind) {
            case RULE_Expr_KIND   -> visitExpr(cst, nodeIdx);
            case RULE_Term_KIND   -> visitTerm(cst, nodeIdx);
            case RULE_Factor_KIND -> visitFactor(cst, nodeIdx);
            default               -> defaultResult();
        };
    }

    protected T visitChildren(CstArray cst, int nodeIdx) {
        T agg = defaultResult();
        var iter = cst.children(nodeIdx).iterator();
        while (iter.hasNext()) {
            int child = iter.next();
            T childResult = visit(cst, child);
            agg = aggregateResult(agg, childResult);
        }
        return agg;
    }

    protected T defaultResult() { return null; }

    protected T aggregateResult(T agg, T next) { return next; }

    public T visitExpr(CstArray cst, int nodeIdx)   { return visitChildren(cst, nodeIdx); }
    public T visitTerm(CstArray cst, int nodeIdx)   { return visitChildren(cst, nodeIdx); }
    public T visitFactor(CstArray cst, int nodeIdx) { return visitChildren(cst, nodeIdx); }
}
```

Key points:

- One `visit<RuleName>` method per **parser rule** (LEXER rules — those that bottom
  out at literals and character classes — don't get one; their text is read directly
  from the parent CST node).
- `RULE_<Name>_KIND` constants match the `kindAt(idx)` integers stored in the CST,
  guaranteed by the codegen sharing kind allocation with `ParserGenerator`.
- The default `visit<Rule>` body delegates to `visitChildren`, which folds child
  results via `aggregateResult` (rightmost-wins by default).
- `defaultResult()` returns `null` by default — override to return a zero value for
  your domain.

---

## Step 4 — Implement the methods

If you're on the **build-time codegen** path (Option B), you have a real
`com.example.calc.CalcVisitor` Java class — just extend it. If you're on the
**runtime-compile** path, do the dispatch directly. Both shapes are shown.

### 4a. Build-time path — extend the generated class

```java
package com.example.calc;

import org.pragmatica.peg.v6.cst.CstArray;

class CalcEval extends CalcVisitor<Integer> {
    @Override
    protected Integer defaultResult() { return 0; }

    @Override
    public Integer visitExpr(CstArray cst, int nodeIdx) {
        // Expr := Term (('+' / '-') Term)*
        // Children alternate: Term, '+'/'-', Term, '+'/'-', Term, ...
        var children = cst.children(nodeIdx).toArray();
        int total = visit(cst, children[0]);
        for (int i = 1; i + 1 < children.length; i += 2) {
            String op = cst.textAt(children[i]).toString();
            int rhs = visit(cst, children[i + 1]);
            total = op.equals("+") ? total + rhs : total - rhs;
        }
        return total;
    }

    @Override
    public Integer visitTerm(CstArray cst, int nodeIdx) {
        var children = cst.children(nodeIdx).toArray();
        int total = visit(cst, children[0]);
        for (int i = 1; i + 1 < children.length; i += 2) {
            String op = cst.textAt(children[i]).toString();
            int rhs = visit(cst, children[i + 1]);
            total = op.equals("*") ? total * rhs : total / rhs;
        }
        return total;
    }

    @Override
    public Integer visitFactor(CstArray cst, int nodeIdx) {
        // Factor := Number / '(' Expr ')'
        // Number child is a leaf with digit text; Expr child is a Branch.
        var first = cst.firstChildAt(nodeIdx);
        // If it's the Number alternative, the only child holds the digits.
        // If it's the '(' Expr ')' alternative, the second child is the Expr.
        for (var it = cst.children(nodeIdx).iterator(); it.hasNext(); ) {
            int child = it.nextInt();
            int kind = cst.kindAt(child);
            if (kind == RULE_Expr_KIND) {
                return visit(cst, child);
            }
        }
        // Fall through: pure Number factor. The Number's text is reachable from
        // the Factor node's own span (the only content under it is the digits).
        return Integer.parseInt(cst.textAt(first).toString().trim());
    }
}
```

### 4b. Runtime-compile path — dispatch directly

When you don't have a build-time-generated class to subclass, do the kind dispatch
yourself. You still have `cst.kindNameAt(idx)` to identify rules by name.

```java
import org.pragmatica.peg.v6.PegParser;
import org.pragmatica.peg.v6.cst.CstArray;

class CalcEvalDirect {
    int eval(CstArray cst, int idx) {
        return switch (cst.kindNameAt(idx)) {
            case "Expr"   -> foldExpr(cst, idx, "+", "-");
            case "Term"   -> foldExpr(cst, idx, "*", "/");
            case "Factor" -> evalFactor(cst, idx);
            default       -> Integer.parseInt(cst.textAt(idx).toString().trim());
        };
    }

    private int foldExpr(CstArray cst, int idx, String plus, String minus) {
        var ch = cst.children(idx).toArray();
        int total = eval(cst, ch[0]);
        for (int i = 1; i + 1 < ch.length; i += 2) {
            String op = cst.textAt(ch[i]).toString();
            int rhs   = eval(cst, ch[i + 1]);
            total = op.equals(plus) ? total + rhs
                  : op.equals(minus) ? total - rhs
                  : plus.equals("+") ? total + rhs
                  : total * rhs;
        }
        return total;
    }

    private int evalFactor(CstArray cst, int idx) {
        for (var it = cst.children(idx).iterator(); it.hasNext(); ) {
            int child = it.nextInt();
            if ("Expr".equals(cst.kindNameAt(child))) return eval(cst, child);
        }
        return Integer.parseInt(cst.textAt(idx).toString().trim());
    }
}
```

For tight inner loops, prefer the integer kind constants over `kindNameAt` (string
comparison). Cache the parser's rule-kind map once at startup via
`parser.ruleKinds()`.

---

## Step 5 — Run it

End-to-end:

```java
import org.pragmatica.peg.v6.PegParser;
import org.pragmatica.peg.v6.Parser;
import org.pragmatica.peg.v6.cst.ParseResult;

public class CalcMain {
    public static void main(String[] args) {
        String grammar = """
            Expr   <- Term (('+' / '-') Term)*
            Term   <- Factor (('*' / '/') Factor)*
            Factor <- Number / '(' Expr ')'
            Number <- < [0-9]+ >
            %whitespace <- [ \\t]*
            """;

        Parser parser = PegParser.fromGrammar(grammar).unwrap();
        ParseResult result = parser.parse("3 + 5 * 2");

        if (!result.isSuccess()) {
            result.diagnostics().forEach(d ->
                System.err.println(d.formatRustStyle("input", "3 + 5 * 2")));
            return;
        }

        var cst   = result.cst();
        var eval  = new CalcEvalDirect();
        int total = eval.eval(cst, cst.rootIndex());
        System.out.println(total); // 13
    }
}
```

The `Expr -> Term -> Factor -> Number` layering encodes precedence: the visitor
just folds the children left-to-right at each level, so `3 + 5 * 2` evaluates as
`3 + (5 * 2)` for free.

---

## Beyond calculator — handling Error nodes

Recovery is always on in 0.6.x. When the parser hits an unexpected token, it skips
forward to the next sync-set token, emits an `Error` node covering the skipped
range, records a `Diagnostic`, and resumes. Your visitor needs to handle those
`Error` nodes — otherwise it can crash on the partial CST.

Three reasonable strategies:

### Strategy A — propagate failure via `Option<T>`

```java
import org.pragmatica.lang.Option;

class SafeEval extends CalcVisitor<Option<Integer>> {
    @Override protected Option<Integer> defaultResult() { return Option.empty(); }

    @Override public Option<Integer> visitExpr(CstArray cst, int idx) {
        if (cst.isError(idx)) return Option.empty();
        return foldChildren(cst, idx);
    }
    // ... visitTerm, visitFactor analogous
}
```

### Strategy B — collect a diagnostic and return a sentinel

```java
class TolerantEval extends CalcVisitor<Integer> {
    final List<String> errors = new ArrayList<>();

    @Override public Integer visitFactor(CstArray cst, int idx) {
        if (cst.isError(idx)) {
            errors.add("unparseable factor at offset " + cst.spanStart(idx));
            return 0;
        }
        return super.visitFactor(cst, idx);
    }
}
```

### Strategy C — let `ParseResult.diagnostics()` carry the errors

Don't run the visitor at all when `!result.isSuccess()`. Use the `diagnostics()`
list for user-facing error display; the partial CST is for tooling that needs to
inspect a broken file (IDE syntax-highlighting, formatters).

Pick one consistently for your domain. Mixing the three within one walk produces
hard-to-debug behavior.

---

## Patterns that keep recurring

### Folding lists from `ZeroOrMore` / `OneOrMore`

PEG `e (op e)*` shows up everywhere (sum, product, comma-separated args). The
children of the resulting CST node alternate value-op-value-op-..., starting and
ending with a value. The two-step iterator pattern from `visitExpr` above
generalises: index 0 is the seed, indices `(1,2), (3,4), ...` are op-value pairs.

For a list of statements (`Stmt*`), children are all of the same shape:

```java
@Override public Void visitBlock(CstArray cst, int idx) {
    cst.children(idx).forEach(child -> visit(cst, child));
    return null;
}
```

### Building an AST

The visitor's `T` becomes your AST type. Sealed interfaces give you exhaustive
pattern matching downstream:

```java
sealed interface Ast {
    record Lit(int v)                  implements Ast {}
    record Add(Ast l, Ast r)           implements Ast {}
    record Mul(Ast l, Ast r)           implements Ast {}
}

class Builder extends CalcVisitor<Ast> {
    @Override public Ast visitExpr(CstArray cst, int idx) {
        var ch = cst.children(idx).toArray();
        Ast acc = visit(cst, ch[0]);
        for (int i = 1; i + 1 < ch.length; i += 2) {
            String op = cst.textAt(ch[i]).toString();
            Ast rhs   = visit(cst, ch[i + 1]);
            acc = op.equals("+") ? new Ast.Add(acc, rhs) : /* ... */ acc;
        }
        return acc;
    }
}
```

The wrapper-rule collapse that 0.5.x `parseAst()` did automatically becomes a
one-liner: when a parent rule has exactly one child of interest, return
`visit(cst, child)`.

### Type checking

The visitor returns whatever your domain type system uses. A typical method
checks its children's types, validates an operation, returns the result type:

```java
class TypeChecker extends GVisitor<Type> {
    @Override public Type visitBinaryExpr(CstArray cst, int idx) {
        Type l = visit(cst, cst.firstChildAt(idx));
        Type r = visit(cst, cst.lastChildBefore(idx));
        String op = cst.textAt(idx).toString();
        return checkBinaryOp(op, l, r); // returns Type or adds a diagnostic
    }
}
```

If a check fails, accumulate diagnostics on a field and return a `Type.Error`
sentinel so downstream code keeps walking instead of NPE-ing.

### Pretty-printing via descendants

For lossless round-trip output (formatter-style), iterate over `cst.descendants`
or directly walk the underlying `TokenArray`:

```java
StringBuilder sb = new StringBuilder();
for (int i = 0; i < cst.tokens().count(); i++) {
    sb.append(cst.tokens().textAt(i));
}
String roundTripped = sb.toString();
// equals input byte-for-byte when parse succeeded; see CstArray.reconstruct()
```

For transformations (rename a variable, add a parenthesis), walk the CST
emitting either the original token text or your transformed version per node.

---

## Further reading

- [`docs/MIGRATION-0.5-TO-0.6.md`](MIGRATION-0.5-TO-0.6.md) — how 0.5.x actions
  map to the visitor pattern, plus broader API changes.
- [`docs/ARCHITECTURE-0.6.0.md`](ARCHITECTURE-0.6.0.md) §3.3 — design rationale
  for dropping actions in favor of `GVisitor<T>`.
- [`peglib-core/src/main/java/org/pragmatica/peg/v6/generator/VisitorGenerator.java`](../peglib-core/src/main/java/org/pragmatica/peg/v6/generator/VisitorGenerator.java)
  — the codegen source. Read it once; it's short.
- [`peglib-core/src/main/java/org/pragmatica/peg/v6/cst/CstArray.java`](../peglib-core/src/main/java/org/pragmatica/peg/v6/cst/CstArray.java)
  — full CST API surface: `children`, `descendants`, `viewAt`, `kindAt`,
  `kindNameAt`, `firstChildAt`, `nextSiblingAt`, `textAt`, `spanStart`,
  `spanEnd`, `leadingTriviaTokens`, `trailingTriviaTokens`.
