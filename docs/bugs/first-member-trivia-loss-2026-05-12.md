# Bug: Trivia lost between parent's opening delimiter and its first child

**Discovered:** 2026-05-12
**Affects:** peglib 0.6.0 (and likely earlier; not yet bisected)
**Severity:** Data loss — `///` markdown javadoc and `//` comments silently dropped from CST
**Reporter:** jbct-format integration testing

---

## TL;DR

`TriviaPostPass.rebuildNonTerminal` initialises its scan cursor at `spanStart` (the offset of the parent's opening delimiter, e.g. `{`) and immediately calls `scanWhitespaceFast(input, spanStart, childStart, …)`. Because the character at `spanStart` is the delimiter itself (not whitespace), `scanWhitespaceFast` fails on its first probe and returns an empty list. Any trivia (whitespace + comments) in the gap between the opening delimiter and the first child is silently lost.

Net effect: a class body's first member never receives the `leadingTrivia` from the comments immediately following `{`. Downstream consumers that round-trip CSTs (e.g. formatters) cannot preserve those comments because they are not in the CST.

---

## Symptom

Parsing this Java source via the peglib-generated `Java25Parser`:

```java
public class CommentsExtended {
    /// Field-level markdown javadoc.
    /// Tests B1: field-level docs must round-trip.
    private final int counter;
}
```

The first `ClassMember` (the field) has `leadingTrivia` that contains **only whitespace** — no `LineComment` items. The two `///` lines do not appear in *any* node's `leadingTrivia()` anywhere in the CST.

Compare to:

```java
public class Foo {
    int firstField;
    /// Method doc — this one survives.
    public void method() { }
}
```

Here the `///` doc is correctly attached to the second `ClassMember`'s `leadingTrivia` (verified with debug instrumentation: `LineComment=/// Method doc…` at trivia index 6 of the second `ClassMember`). The first member's docs are lost; subsequent members' docs are preserved.

This matches the documented rule in `docs/TRIVIA-ATTRIBUTION.md:32-42` for *subsequent* members but violates it for the *first* member.

---

## Minimal reproducer

```java
// peglib-core/src/test/java/org/pragmatica/peg/tree/FirstMemberTriviaTest.java (new test)
@Test
void firstMemberReceivesLeadingTrivia() {
    var input = """
        class Foo {
            /// doc on first field
            int x;
        }
        """;
    var grammar = /* load Java25 grammar */;
    var parser = PegParser.fromGrammar(grammar).unwrap();
    var cst = parser.parseCst(input).unwrap();

    // Navigate to the first ClassMember inside the class body
    var firstMember = findFirstClassMember(cst);

    // Currently FAILS: leadingTrivia contains only Whitespace items.
    // Expected: contains a LineComment with text "/// doc on first field"
    assertThat(firstMember.leadingTrivia())
        .anyMatch(t -> t instanceof Trivia.LineComment lc
                       && lc.text().contains("/// doc on first field"));
}
```

A simpler unit-scale reproducer that doesn't need the full Java grammar — any grammar where a rule is `'{' Body '}'` and the input has comments between `{` and the start of `Body` should exhibit the same bug.

---

## Root cause (file:line references)

The bug is the interaction of three pieces in `peglib-core/src/main/java/org/pragmatica/peg/tree/TriviaPostPass.java`:

### 1. `rebuildNonTerminal:409`

```java
int cursor = spanStart;  // line 409
for (int i = 0; i < childCount; i++) {
    var c = children.get(i);
    var rebuilt = rebuildChild(input, c, grammar, lineStarts, cursor, drainForThis);
    cursor = c.span().endOffset();
}
```

`spanStart` is the offset of the parent's first character — for a `class Body` rule producing `{ … }`, that's the offset of `{` itself. Passing `cursor = spanStart` to `rebuildChild` for the first child means the scan range is `[offsetOfOpeningBrace, firstChildStart)` — which includes the brace.

### 2. `rebuildChild:338`

```java
var leading = scanWhitespaceFast(input, prevEnd, childStart, grammar, lineStarts);
```

With `prevEnd = spanStart`, this scans from the opening delimiter's offset forward.

### 3. `scanWhitespaceFast:179-203`

```java
private List<Trivia> scanWhitespaceFast(String input, int from, int to, Grammar g, int[] lineStarts) {
    var captured = new ArrayList<Trivia>();
    int pos = from;
    while (pos < to) {
        int matched = matchExpression(input, pos, ...);   // line 193
        if (matched < 0 || matched == pos) {              // line 194
            break;
        }
        // … add Trivia item, advance pos to matched
    }
    return captured;  // line 202
}
```

`matchExpression` is called on `input[pos]` (the `{` character on the first iteration). The `%whitespace` rule cannot match `{`. `matchExpression` returns `-1`. Line 194's `break` fires immediately. `captured` is empty. Returned: `List.of()`.

The trivia in the gap (the two `///` lines plus surrounding whitespace) is never captured.

---

## Why subsequent members work

For the *second* child onward, `cursor` is set to `c.span().endOffset()` (line 412) — past the previous child's last character. The next gap begins right after the previous child ends. Whitespace will be at `cursor`, `scanWhitespaceFast` matches on the first probe, and the loop captures correctly.

The bug is specifically the **first iteration** of the rebuild loop on a parent whose `spanStart` is its own opening delimiter.

---

## Fix hypothesis

Three viable approaches, in increasing rigour:

### Option 1 — Advance `cursor` past the opening delimiter

In `rebuildNonTerminal`, after determining `spanStart`, if the parent has children whose first child's `spanStart > spanStart` (i.e. there's a gap), skip the parent's first character before scanning. Conceptually:

```java
int cursor = (childCount > 0 && children.get(0).span().startOffset() > spanStart)
             ? spanStart + 1     // skip the opening delimiter
             : spanStart;
```

Simplest. Risk: assumes the parent's first character is always exactly one byte/character of delimiter. Works for `{`, `(`, `[`. Doesn't generalise to multi-character opening tokens (none come to mind in Java25, but the general PEG case could have them).

### Option 2 — Skip ahead to first non-delimiter in `scanWhitespaceFast`

Modify `scanWhitespaceFast` to advance past non-whitespace prefix characters until either reaching `to` or finding a whitespace start. Risk: harder to bound — could consume tokens that should belong to the child.

### Option 3 — Use the parent's first-terminal end as the initial cursor

If the parent has a known opening-delimiter terminal in its first slot, use its `endOffset()` as the starting cursor. Most correct semantically (matches the post-pass's general "scan between consecutive children" approach), and treats the opening terminal as the "previous child" of the first child. Requires knowing which child slot is the delimiter — possibly available from the grammar's rule structure.

**Recommended:** Option 1 for a fast targeted fix; Option 3 for the structurally clean fix once the codebase can support it.

---

## Suggested tests

`peglib-core/src/test/java/org/pragmatica/peg/tree/TriviaPostPassTest.java` covers round-trip reconstruction, corpus fixtures, structural divergence, and adversarial parity. **There is no test that specifically asserts on `leadingTrivia()` of the first child of a delimited container.** Adding:

1. **First-child trivia preservation** — input like `class X { /// doc \n int y; }`; assert first member's leadingTrivia contains the LineComment. This is the direct test for the bug.
2. **Trivia adjacent to `{`** — input with both whitespace and comments immediately after `{`; assert both are captured.
3. **Empty-body container** — input like `class X { /// orphan doc \n }` with NO members; assert the doc attaches somewhere (likely trailing trivia of `{` or leading trivia of `}`, by the attribution rule).
4. **Nested delimiters** — input where a method's `{` is followed by a `//` comment and a statement; assert the first-statement case.

Cases 1 and 2 fail in 0.6.0 today.

---

## Impact

This bug blocks JBCT formatter from re-enabling its `format` goal. See `pragmatica-clone/docs/contributors/jbct-formatter-disabled.md` for the full context. Specifically:

- **B1 ("`///` markdown javadoc deleted")** — root cause is THIS bug for first-member docs. Subsequent-member docs are formatter-side and now have a fix waiting in `pragmatica-clone` PR #213 branch.
- **B2 ("`//` block comments deleted before control-flow")** — likely the same root cause for the **first** statement of a method body (`{` immediately followed by `// comment`). Subsequent-statement cases should already work.

Fixing the parser-side bug here would allow downstream consumers to preserve comments as the source author wrote them. The JBCT formatter's own emit-trivia fix is independently needed and is already drafted in the consumer repo.

---

## Out of scope

Two other bugs documented in `docs/contributors/jbct-formatter-disabled.md` (B3 lambda-chain indentation mangling, B4 if-body single-line collapse) are pure formatter-layer issues, not parser issues. They're being addressed independently in `pragmatica-clone` and do not block on a peglib fix.

---

## How to verify a fix

1. Cherry-pick the new fixture `peglib-core/src/test/resources/.../first_member_trivia.java` (per "Suggested tests" §1).
2. Run the test pre-fix → must FAIL.
3. Apply the fix per "Fix hypothesis" §1 or §3.
4. Re-run → must PASS.
5. Run the full corpus regression suite (`TriviaPostPassTest`, golden tests in `peglib-core`) → no regressions.
6. Optionally: regenerate the `Java25Parser` in `pragmatica-clone/jbct/jbct-parser` and run the JBCT formatter golden tests (`GoldenFormatterTest`) including the new `CommentsExtended.java` fixture being added in PR #213 — verify `///` docs on first members round-trip.
