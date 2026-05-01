# Trivia Attribution

Status as of release 0.3.5: **full byte-equal round-trip on all 22 corpus
fixtures**. The 0.2.4 attribution threading plus five 0.3.5 fixes (Bug A
through C'') compose to make `reconstruct(parse(source)) == source` byte-for-byte
across the entire perf corpus. `RoundTripTest` is re-enabled.

The five fixes:
- **Bug A** — `ParsingContext` snapshot/restore changed from size-only to
  full `List<Trivia>` so backtracked branches don't permanently lose drained
  pending trivia.
- **Bug B** — Cache-hit path rebuilds leading trivia (drain pending +
  `skipWhitespace` + reattach) so cache hits behave identically to fresh
  parses for trivia attribution.
- **Bug C** — Generator's cache stored the wrapped-with-leading body. The
  short-circuit in `attachLeadingTrivia` then preserved stale leading on
  empty-pending hits, duplicating trivia across nested wrappers. Fix: cache
  an empty-leading version, return the leading-applied version. Interpreter
  was already correct (caches the body, not the wrap).
- **Bug C'** — Trivia consumed by the body's last inter-element
  `skipWhitespace` (e.g. before an empty ZoM/Optional) ends up in pending
  with no claimant. At rule exit, attach it to the last child's
  `trailingTrivia`. Pos is *not* rewound — predicate combinators rely on
  position being past consumed whitespace.
- **Bug C''** — Generator's emitted Sequence used the rule-method's outer
  `children` list. On element failure, location and pending were restored
  but children were not. Partial additions leaked into the parent's tree.
  Fix: snapshot `children` at Sequence start; restore on element failure.
  Interpreter uses a local `children` list per Sequence so was already
  correct.

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
- Trailing trivia consumed inside a rule body (e.g. by inter-element
  `skipWhitespace` before an empty ZoM/Optional) is attached to the last
  child's `trailingTrivia` at rule exit (Bug C').
- Failed Sequence elements roll back the local children list as well as
  location and pending (Bug C'' — generator was missing this).
- `RoundTripTest` is enabled; all 22 perf-corpus fixtures round-trip
  byte-equal via the generated parser.
- Interpreter and generator remain byte-for-byte parity on the corpus
  fixtures.

## Baseline stability

The Bug C'' fix removes a duplicate trailing comma child in enum-constant
lists; that legitimately changes the CST shape for
`large/FactoryClassGenerator.java.txt`. Its CST hash baseline was
regenerated as a separate commit. All other corpus baselines are unchanged
because their fixtures didn't exercise the Bug C'' path.
