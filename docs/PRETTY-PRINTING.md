# peglib-formatter design notes

These notes accompany [`peglib-formatter`](../peglib-formatter/README.md)'s
v1 implementation (0.3.3). They cover the renderer algorithm, the CST walker
design, and guidance for writing your own formatter on top of the framework.

## 1. Doc algebra

The framework is built around a small, sealed `Doc` algebra:

```
Doc ::= Empty
      | Text(String)            -- literal, no newlines
      | Line                    -- " " in flat mode, "\n + indent" in break mode
      | Softline                -- "" in flat mode, "\n + indent" in break mode
      | HardLine                -- always "\n + indent"; forces enclosing group to break
      | Group(Doc)              -- "prefer flat if it fits"
      | Indent(Int, Doc)        -- adds N columns to current indent for breaks inside
      | Concat(Doc, Doc)        -- sequential composition
```

`Text` is constructively forbidden from containing newlines — line breaks
must come from `Line`, `Softline`, or `HardLine`. `HardLine` exists for two
purposes:

1. The trivia machinery uses it to preserve newlines inside whitespace runs
   and after line comments (since `Text` cannot carry `\n`).
2. User code uses it to force breaks that should never collapse — e.g.
   between top-level SQL clauses (`SELECT` / `FROM` / `WHERE` always on
   separate lines).

`HardLine` propagates upward: if a `Group` transitively contains a
`HardLine` (without an intervening nested `Group`), the renderer puts that
group in break mode unconditionally. Nested groups break the propagation —
each `Group` decides flat vs. break based only on its own contents.

## 2. Renderer (Wadler / Lindig "best")

`internal/Renderer.java` walks the doc tree with an explicit work stack of
`(indent, mode, doc)` frames. At each `Group` it asks: *does the group's
flat form, plus the still-FLAT prefix of the surrounding context, fit
within the remaining columns?* If yes, render the group in `FLAT` mode;
otherwise in `BREAK` mode.

The "fits" probe (`fits(...)`) is bounded — it stops at the first `BREAK`-mode
`Line` / `Softline` it encounters in the surrounding context, because such a
break resets the column. This is Lindig's non-backtracking variant of
Wadler's algorithm: O(n) in doc size, no recursion blow-up.

Two implementation notes:

- **Stack pop order matters.** The work stack pushes `right` then `left`, so
  pop processes `left` first — preserving document order.
- **Surrounding context is consulted lazily.** The probe iterates the
  surrounding stack only after the group's own contents are exhausted.

## 3. Walker + rule lookup

`Formatter#format(CstNode, String)` walks the CST depth-first. For each
node it:

1. recursively formats every child, collecting the resulting `Doc`s;
2. looks up a `FormatterRule` by `node.rule()`;
3. invokes the rule with a `FormatContext` carrying `(node, source,
   defaultIndent, maxLineWidth, triviaPolicy)`;
4. wraps the rule's output with this node's leading + trailing trivia.

Rules see already-formatted child docs; they decide ordering, separators,
and grouping. The `source` argument is passed through so rules can recover
exact source slices via `ctx.nodeText()`.

A common gotcha: **peglib collapses single-child rule dispatches**. A rule
like `Value <- Number / String` does not produce a `Value` NonTerminal
wrapping `Number` — instead the engine emits the `Number` (or its inner
Token) directly with its parent rule name. The demo formatters work around
this by registering the rule on the parent name (e.g. `"Value"`) and
discriminating on the node's concrete type (`Terminal` / `Token` /
`NonTerminal`) and on its first-child terminal text.

## 4. Trivia handling

`TriviaPolicy` is a functional interface; it gets every leaf's leading and
trailing trivia run and returns the trivia the formatter should emit. Four
built-in policies cover the common cases — `PRESERVE`, `STRIP_WHITESPACE`,
`DROP_ALL`, `NORMALIZE_BLANK_LINES`.

The walker emits trivia before/after each node via:

- `Trivia.Whitespace` → split on `\n`: spaces become `Text`, newlines
  become `HardLine`;
- `Trivia.LineComment` → `Text(comment)` then `HardLine`;
- `Trivia.BlockComment` → split on `\n`: text on each line + `HardLine`s.

This preserves comment structure across reformatting. **Caveat:** trivia
round-trip is best-effort in v1 because `peglib-core` does not yet rewind
the rule-exit position to attribute trailing intra-rule trivia to the
following node. See `docs/TRIVIA-ATTRIBUTION.md` and the module README's
"Known limitations" section.

## 5. Idempotency property

The framework's golden correctness gate is

```
format(parse(format(parse(input)))) == format(parse(input))
```

This is the right property because:

- byte-for-byte round-trip (`format(parse(x)) == x`) is too strict — a
  reformatter is allowed to change formatting;
- semantic equivalence is too vague to mechanically test;
- idempotency is mechanically checkable, catches most rule bugs (incorrect
  separator handling, missing children, non-deterministic emission), and
  fails loudly on any non-fixed-point output.

The demo formatters all assert idempotency on fixed fixtures + 50–100
fuzzed inputs each. If a rule produces output that re-parses but then
re-formats to something different, the second-pass diff exposes it.

## 6. Writing your own formatter

Recommended pattern, derived from the three demos:

1. **Probe the CST** for representative inputs first. PEG's collapse
   semantics mean rule names in your grammar do not always match the
   `node.rule()` you see at runtime. A 30-line probe class that prints
   `(rule, kind, text, span)` will save hours.
2. **Register on the names that actually appear** at the formatter
   boundary. For collapsed alternatives, register on the parent rule and
   discriminate by node type + first-terminal text.
3. **Use `TriviaPolicy.DROP_ALL`** initially. Drive spacing from rules.
   Switch to `PRESERVE` only when you want comments through.
4. **Build small, then grow.** Get one rule per node kind right, render
   it, eyeball it. Then add idempotency tests on a handful of fixed
   inputs. Then fuzz.
5. **Recursive list flattening.** Right-recursive PEG list rules
   (`List <- Item (',' Item)*`) produce nested NonTerminals. Flatten them
   with a dedicated `collectListItemsInto` helper rather than relying on
   the framework's child-doc collection (which also descends).
6. **Continuation fragments need fallback rendering.** If a rule fires for
   both the chain top and an internal continuation node (e.g.
   right-recursive Expr/Term in arithmetic), your rule must detect the
   continuation case and emit something that still parses. The arithmetic
   demo's `renderFragment` helper shows the pattern.

## 7. Limitations and future work

- **Trivia round-trip** lands when `peglib-core` completes its trivia
  attribution rewind (post-0.3.3 work; tracked in
  `docs/TRIVIA-ATTRIBUTION.md`).
- **Width-aware backtracking.** The non-backtracking renderer is fast but
  pessimistic on deeply nested groups where an outer flat decision later
  forces an inner group to break. Wadler's original (with backtracking)
  handles this; we ship Lindig's variant for predictability.
- **Source map.** A future `format` overload could return both the string
  and an offset-to-offset map for IDE integrations.
