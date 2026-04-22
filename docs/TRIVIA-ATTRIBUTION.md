# Trivia Attribution

Status as of release 0.2.4: **attribution threading is in place**, full
round-trip source reconstruction is **deferred**.

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

## Known limitation (deferred)

Trivia matched by a rule body that has no following sibling to attach to
— for example, trailing whitespace between the last element of a rule's
last repetition and the rule's end — is not yet rewound to the preceding
sibling's `trailingTrivia`. No `trailingTrivia` assignment happens on any
non-root node. As a result, the 22-file `RoundTripTest` remains
`@Disabled`: attribution alone does not yet yield byte-for-byte source
reconstruction.

Full round-trip support requires a rule-exit position rewind pass: when a
rule finishes, any pending trivia that the rule captured but did not
attach to a child (i.e. post-last-sibling whitespace) must be rewound out
of the rule's span and attached to the preceding sibling's
`trailingTrivia`. That pass is out of scope for 0.2.4; it will land in a
later release together with re-enabling `RoundTripTest`.

## Baseline stability

Attribution threading does not change any node's `span` start or end
offsets. All trivia that was captured as part of a rule's consumed range
in 0.2.3 is still captured as part of the same range — it is simply now
exposed on a child node's `leadingTrivia` instead of being dropped. The
CST hash baselines under `src/test/resources/perf-corpus-baseline/` and
`src/test/resources/perf-corpus-interpreter-baseline/` therefore remain
valid, and every corpus-parity test suite continues to pass 22/22 without
baseline regeneration.
