# `%memo` differential

Proves that the `%memo` directive is an optimisation and nothing more.

`%memo` marks a rule whose successful parse is salvaged when backtracking drops it and replayed
if the same rule is re-parsed at the same token position. Replay must be **observationally
identical** to re-parsing — same CST, same diagnostics, same `reconstruct()`. Anything else is a
bug, and the unit tests can only cover shapes small enough to write by hand.

These two harnesses check the property against real input by differencing the grammar against
**itself with every `%memo` line stripped**. Same parser, same input, directive on vs off.

Not wired into the build: they need a built `peglib-core` on the classpath and take seconds to
run. Use them when the memo set changes, when `CstArrayBuilder`'s replay path changes, or before
a release that touches either.

## What each covers

| | input | catches |
|---|---|---|
| `MemoDiff` | every `.java` / `.java.txt` under a module's `src/test/resources` | divergence on valid code, at scale — the 40k-LOC selfhost fixture included |
| `MemoErrorDiff` | 10 built-in snippets, each malformed *inside* an argument list | divergence when a salvaged subtree contains an `Error` node from panic-mode recovery |

The split matters. A corpus of valid files never produces an `Error` node, so `MemoDiff` alone
cannot reach the case where replay re-splices error nodes without re-recording their diagnostics.
`MemoErrorDiff` also compares diagnostic **text**, not just count, since a preserved count with a
shifted span would otherwise pass.

Both compare: diagnostic count, success flag, `reconstruct()`, node count, and the full preorder
`(kind, firstToken, lastToken)` signature.

## Running

```bash
# from the repo root, with peglib-core built (mvn install)
mvn -q -pl peglib-core dependency:build-classpath \
    -Dmdep.outputFile=/tmp/cp.txt -DincludeScope=runtime
R=$(pwd)
CP="$R/peglib-core/target/classes:$R/peglib-runtime/target/classes:$(cat /tmp/cp.txt)"

java -cp "$CP" tools/memo-differential/MemoDiff.java      "$R/peglib-core"
java -cp "$CP" tools/memo-differential/MemoErrorDiff.java "$R/peglib-core"
```

Both exit non-zero on divergence, so they drop straight into a script. Exit code 2 means the
grammar declares no `%memo` at all — nothing to differentiate.

Expected output as of 0.7.1 (`%memo Args` in `java25.peg`):

```
files checked : 28
total LOC     : 45113
mismatches    : 0
RESULT: IDENTICAL
```

```
cases: 10  divergent: 0
RESULT: IDENTICAL ON ERROR PATHS
```

## A caveat on what this proves

Passing means replay did not change the observable result **on these inputs**. It is evidence,
not a proof of the general property. The in-build gates carry the standing guarantees:
`MemoReplayParserTest` (replay vs plain on a minimal grammar), `MemoInteractionTest` (`%memo`
against per-rule `%recover` and incremental reparse) and `CstArrayBuilderMemoTest` (the salvage
and rebase mechanics directly). All three are mutation-checked — breaking the replay position
guard in `CstArrayBuilder` turns them red.
