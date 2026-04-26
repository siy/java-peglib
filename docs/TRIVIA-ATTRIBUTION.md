# Trivia Attribution

Status as of release 0.3.5: **attribution threading is in place** (0.2.4),
**pending-trivia backtrack restore is correct** (0.3.5 Bug A), and **cache-hit
leading rebuild is correct for non-empty pending** (0.3.5 Bug B). Full
byte-for-byte round-trip is empirically achieved for 12 of 22 corpus fixtures;
the remaining 10 hit Bug C (see "Known limitation" below) and await 0.3.6.

## Attribution rule

Trivia (whitespace and comments) matched by `%whitespace` between sibling
sequence elements attaches to the **following sibling's** `leadingTrivia`.
Equivalently: every CST node that owns a `leadingTrivia` list claims the
trivia captured immediately before it in the enclosing rule body. A rule's
own leading whitespace (the `skipWhitespace()` call at rule entry) is
concatenated onto whatever trivia the caller deposited and is owned by the
rule wrapper node, not by any child. This replaces the 0.2.3 behaviour,
where trivia matched between siblings was silently discarded because
`skipWhitespace()` was called solely for its position-advance side-effect.

## Implementation

Both the interpreter (`PegEngine`) and the generated parser carry a
per-parse `pendingLeadingTrivia` buffer on the parsing context. Four
operations manipulate it:

- `appendPending(captured)` — extend the buffer with captured trivia.
  Called at every combinator-level inter-element `skipWhitespace` site
  (Sequence between elements; ZeroOrMore, OneOrMore, and Repetition between
  iterations). Captured trivia for that slot is deposited into the buffer
  instead of being dropped.
- `takePendingLeading()` — drain the buffer and return it as a `List<Trivia>`.
  Called at every node-construction site that owns a `leadingTrivia` list:
  the `matchLiteralCst` / `matchCharClassCst` / `matchAnyCst` /
  `matchDictionaryCst` terminals, the `TokenBoundary` node, `BackReference`
  (via `matchLiteralCst`), and the rule-entry wrapper in
  `parse_<Rule>()` / `PegEngine.parseRule`.
- `savePendingLeading()` / `restorePendingLeading(snapshot)` — record and
  restore the buffer size. Every backtracking combinator brackets its
  attempts with save/restore pairs so that trivia captured inside a failed
  branch never leaks to the next branch:
  - `Choice` snapshots at entry; each failed alternative restores; the
    "all alternatives failed" epilogue also restores.
  - `Optional` restores on both `CutFailure` and regular failure.
  - `And` / `Not` always restore (predicates consume neither position nor
    trivia state).
  - `ZeroOrMore`, `OneOrMore`, `Repetition` snapshot per iteration and
    restore when the iteration fails or makes no progress (matching the
    enclosing `restoreLocation(beforeLoc)` rewind).
  - `Sequence` snapshots at entry and restores on element failure (matching
    the enclosing `restoreLocation(seqStart)` rewind).

A rule that fails re-deposits the trivia its caller had left in the buffer
at entry, so enclosing backtracking combinators see the same buffer state
they were going to restore to.

## What works today

- In-sequence trivia (whitespace and comments) between sibling leaf nodes
  is preserved on the following sibling's `leadingTrivia`, matching the
  attribution rule above.
- Trivia preceding a referenced sub-rule attaches to that sub-rule's
  wrapper node, concatenated onto the sub-rule's own leading whitespace.
- All 565 pre-existing trivia, parity, and correctness tests stay green;
  587 tests total, 1 skipped (`RoundTripTest`).
- Interpreter and generator remain byte-for-byte parity on the corpus
  fixtures.

## Known limitation (deferred to 0.3.6)

### Bug C — cache-hit leading-trivia ambiguity

After 0.3.5's Bug A and Bug B fixes, the round-trip diagnostic on the 22
corpus fixtures shows:
- 12 fixtures pass byte-equal.
- 10 fixtures fail (deltas range from +9 to -13 bytes), split between
  duplication (rec > src) and loss (rec < src).

Empirical investigation (2026-04-26) traced the failures to packrat cache
hit semantics:

The cache stores rule BODY results (not the wrapped result). A body result's
`leadingTrivia` may be:
- Empty — when the rule body is a `Sequence` (Sequence wraps with
  `leading=List.of()`), or a leaf with empty pending at construction.
- Non-empty — when the rule body is a `Reference` to an inner rule that
  was wrapped with non-empty `ruleLeading` from its own first parse.

Bug B's cache-hit fix rebuilds `ruleLeading` from the current parse context
(drain `pending` + `skipWhitespace`) and applies it via `attachLeadingTrivia`.
That helper short-circuits when the rebuilt list is empty:

```java
private CstNode attachLeadingTrivia(CstNode node, List<Trivia> leadingTrivia) {
    if (leadingTrivia.isEmpty()) {
        return node;  // <-- preserves whatever cached.leading was
    }
    ...
}
```

When the rebuilt leading is empty (current `pending` empty AND `skipWhitespace`
returns empty) but the cached body's leading is non-empty (Reference body
case), the cached attribution is preserved. This is sometimes correct
(when the cached attribution is "authoritative" for that position) and
sometimes wrong (when it is "stale" from a different prior context).

Trying to always-replace (instead of short-circuiting) drops the corpus pass
count from 12 → 5 because the authoritative cases lose their attribution.

### Resolution direction

The proper fix likely requires stripping leading at cache-store time AND
capturing the rule-internal whitespace from the input text on hit (since
`startPos` and `bodyEndPos` are known, the trivia between them can be
recomputed deterministically). This is a multi-day redesign of the
cache-hit path and is scheduled for **release 0.3.6**.

Until then, `RoundTripTest` stays `@Disabled`. Consumers requiring
byte-equal round-trip should hold off on the formatter and incremental v2
features until 0.3.6 ships.

### Original 0.2.4 limitation (resolved by 0.3.5 Bug A+B)

The original limitation documented at 0.2.4 release time was "trailing
intra-rule trivia is dropped because no rule-exit pos-rewind exists." That
analysis was incomplete — empirical diagnostic in 0.3.5 showed the
dominant failure mode is the cache-hit ambiguity above (Bug C), not
rule-exit rewind. Bugs A and B substantially improved attribution: 12 of
22 fixtures now round-trip exactly without any rule-exit rewind work.

A genuine rule-exit pos-rewind may still be needed for some of the
remaining 10 fixtures (specifically the loss cases), but solving Bug C
first will reveal which fixtures truly need rewind versus which were
purely cache-attribution artifacts.

## Baseline stability

Attribution threading does not change any node's `span` start or end
offsets. All trivia that was captured as part of a rule's consumed range
in 0.2.3 is still captured as part of the same range — it is simply now
exposed on a child node's `leadingTrivia` instead of being dropped. The
CST hash baselines under `src/test/resources/perf-corpus-baseline/` and
`src/test/resources/perf-corpus-interpreter-baseline/` therefore remain
valid, and every corpus-parity test suite continues to pass 22/22 without
baseline regeneration.
