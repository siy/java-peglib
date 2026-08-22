# peglib — Handover

**Last updated:** 2026-08-22 — **0.7.3 SHIPPED**

---

## Session 13 — 0.7.2 (2026-08-18 → 08-21) — SHIPPED

### State at a glance

| | |
|---|---|
| **Ship state** | **SHIPPED.** `org.pragmatica-lite:peglib:0.7.2` on Maven Central with signatures, verified by fetch. Announced 2026-08-21. |
| **Merged / tagged** | PR #44 → `main` at `6fd769b`, CI green; tag `v0.7.2`; GitHub release cut (first since v0.4.3 — practice resumed deliberately) |
| **Build** | `mvn install` — BUILD SUCCESS, lint + format-check on |
| **Tests** | **635** across 5 modules, 0 failures, 0 skips (605 at session start) |
| **Grammar** | `java25.peg` **unchanged**, so the 99.45% javac agreement carries over untouched |
| **Downstream** | `aether/pg-tools` green on 0.7.2 — 811 tests, 0 failures, 34/34 real schema files parsing |

The deploy was clean: deployment `43e982c6` published without the `UnrecognizedPropertyException`
that made 0.7.0 report BUILD FAILURE for a publish that had in fact succeeded. **Keep the
`central-publishing` 0.11.0 pin** — it is doing its job. `autoPublish=true` with
`waitUntil=published` still means no staging gate and immutable artifacts, so a mistake ships the
next version.

### What this release was

0.7.2 is the release where peglib stopped being a Java parser generator that happened to be
general. Every fix below was found by one downstream consumer — `aether/pg-tools`, a 753-line
PostgreSQL grammar — and **none was reachable from `java25.peg`**, which has zero case-insensitive
keyword literals and zero reference-only lexer rules. Eleven defects, plus two tooling fixes.

**Lexing and classification**

- **Identifier fallback works for case-insensitive grammars.** It was gated on a `/cs` key suffix
  reasoning "Java keywords are case-sensitive" — a Java assumption compiled into a general
  library. Keyword containment now folds both sides.
- **A lexer rule may reference another lexer rule.** References are substituted before DFA
  compilation, cycles refused. This is what makes "guard plus named alternatives" expressible.
- **A rule that can match only the empty string is PARSER.** *Always-empty*, not *nullable* —
  `Word <- [a-z]*` is nullable and stays a legitimate lexer rule.
- **`< >` is load-bearing for classification.** A reference-only body spanning more than one token
  is a parser rule unless a token boundary declares otherwise.
- **Trailing character-class lookahead compiles** — `X ![c]` becomes a constraint on the accepting
  state. This is what multi-word lexemes need.
- **Several leading keyword guards** on one rule; a single-keyword rule counts as a literal set.
- **`%parser RuleName`** pins a rule to PARSER, overriding inference.
- **A rule named from `%whitespace` is trivia.** Such alternatives could not compile (no call
  stack in a DFA) and were *silently dropped*, so the standalone rule matched the same text under
  an ordinary kind and the first `--` comment in a file ended the parse. 18 of 34 real files.
- **A lexeme gets one kind, named after the rule that names it.** A second synthetic `INLINE_*`
  kind for the same text left the rule's kind unreachable and the keyword anonymous in the CST.
- **Adoption runs before alias arrays are built.** Interleaving them made the result depend on
  declaration order — `ReservedKeyword` before `CaseKW` broke, after `CreateKW` worked.
- **A guard's own reserved words are no longer offered as identifier fallbacks.** The `*KW`
  rule-name half of the fold was never case-folded, so `ColId` accepted `CREATE` and `SELECT` as
  column names. Latent while those kinds were unreachable; live the moment adoption landed.

**Tooling**

- **`%import` works end to end**, plus all three mojos; **Base64 DFA tables** lifting a ~1100-state
  ceiling (both from session 12).
- **The generator stamp identifies the build, not the version.** A same-version rebuild — the
  normal case for an entire unreleased version — was invisible to consumers, who kept stale
  generated sources and measured a parser that no longer matched the library.

### Open items

1. ~~**A guarded rule whose body names another guarded rule.**~~ **CLOSED 2026-08-22 — diagnosed,
   and the premise was wrong. Do not "fix" this.**

   The mechanism recorded was real: `resolveSkipBody` does refuse the body, because inlining
   `ColId` drags its own `!ReservedKeyword` in and the DFA cannot compile lookahead. What was
   never measured is the *consequence* — a silent fall back to PARSER — and **the PARSER reading
   is correct**. Measured on the canonical shape: `hello` accepted, `partition` rejected by the
   outer guard, `select` and `from` rejected by the inner one. Both guards fire, via token-level
   lookahead instead of the DFA. There is no wrong output to fix.

   The guard-composition change was re-applied and diagnosed. It breaks **36 tests through
   exactly one rule**: `PlainTypeName <- !RestrictedTypeName Identifier` (`java25.peg:247`) is
   this same shape, and promoting it to LEXER makes it out-prioritise `Identifier` (line 322),
   which matches the same text. Every identifier in Java then lexes as `PlainTypeName`,
   `Identifier` goes dead, and `CompilationUnit` fails at offset 0 — hence every failure reading
   `trailing input not consumed`. The classification diff between the two builds is one line.

   Promotion buys nothing and costs the grammar. `NestedGuardRuleTest` now pins the PARSER
   classification with that reasoning attached, so a third attempt fails at the cause rather than
   in 36 downstream assertions.

   What *is* genuinely unavailable is fusion: a guarded rule cannot be absorbed into a larger
   token: `Qualified <- < ColId '.' ColId >` still falls back to PARSER. **The silent half was
   fixed in 0.7.3** — it now reports `grammar.token-boundary-ignored`, and a whole-body `< >`
   over a compilable body is honoured rather than dropped. What remains is the genuine DFA
   limit: a guarded rule's lookahead cannot be inlined into a larger token.
2. ~~**Unreachable-kind detection.**~~ **BUILT 2026-08-22** (alongside `grammar.token-boundary-ignored`) — `grammar.unreachable-kind`, in the
   Analyzer, behind `peglib:lint` / `peglib:check`. It earned itself immediately: it diagnoses
   open item 1 above in one line (`Identifier` dead) where the original took several rounds by
   hand. The design work was all in *not* firing on correct grammars — the naive form reports
   five rules on `java25.peg`, so rules represented by their alias kinds or inlined into another
   lexer rule are excused.
3. ~~**Generated output is not reproducible.**~~ **FIXED 2026-08-22.** Confirmed first by
   measurement — five fresh JVMs, four distinct hashes, all 108 differing lines were `.put(`
   lines — then fixed by replacing `Map.copyOf` with an order-preserving `orderedCopy` across
   every map in `TokenKindAssignment`. Ten runs, one hash. Note the test does NOT generate twice
   and compare: the seed is per-JVM, so that test passes against the bug.
4. **Lookahead unsupported** over anything but a character class (`&(Modifier* ClassKW)`), and
   nested inside a Choice alternative rather than trailing the whole body. This is why `java25.peg`
   still spells its `value` lookahead inline at four use sites — though `%parser` now rescues that
   shape (verified), so switching it is possible and needs only a corpus re-run to justify.

### Where to pick up, in order

Items 1-3 below are **done** (2026-08-22, on `release-0.7.3`). What is left:

1. **Nothing is outstanding.** 0.7.3 shipped 2026-08-22: PR #46 merged at `6688343`, tag
   `v0.7.3`, GitHub release cut, Maven Central deployment `60fe059a` verified by fetching
   concrete artifacts from repo1 — all 6 modules plus sources, javadoc and GPG signatures.

Corpus **re-measured 2026-08-22** rather than inherited — 99.45%, with the failure counts
*identical* to baseline (21 false accepts, 10 false rejects). The +7 `AGREE_CLEAN` is corpus
growth, not a fix. Re-running mattered because 0.7.3 changed the lexer path and the
classification of whole-body token boundaries, even though `java25.peg` itself is untouched.

One practical trap now recorded in `tools/langtools-corpus/README.md`: the `--filter=blob:none`
clone fetches file contents lazily, so the FIRST differential run downloads ~5,700 blobs one at
a time and looks like a hang — >10 min, against 7 s once warm. Warm the checkout before timing.

## Session 14 — 0.7.3 (2026-08-22) — SHIPPED

`release-0.7.3` is branched from `main` at `d548f2a`. Four commits so far:

| | |
|---|---|
| `chore: prepare release 0.7.3` | versions bumped across 6 poms, README, CHANGELOG section |
| `feat: depth-counting scanner for nesting trivia via %nest` | **issue #45** — nested block comments |
| `fix: preserve map order so generated sources are reproducible` | handover item 3 |
| `feat: detect token kinds no input can produce` | handover item 2 — `grammar.unreachable-kind` |
| `docs: close the nested-guard item as diagnosed, not defective` | handover item 1 — closed, not a defect |
| `fix: honour a whole-body token boundary, or report that it could not be` | the `< >` silent-ignore found while diagnosing item 1 |

Tests **696**, 0 failures, 0 skips, lint and format-check on (635 at 0.7.2 ship). Corpus 99.45%, re-measured.

The three open items from session 13 are all resolved — two fixed, one closed as *not a defect*
after diagnosis. See the rewritten item list above; the short version is that item 1's recorded
premise was wrong, and the check built for item 2 is what proved it.

### Things worth not re-learning

- **A review bot caught two defects in work already called verified — one of them unfixable
  after publish.** `NEST_FIRST` emitted a delimiter's first character through the STRING escaper
  inside a CHAR literal, so a `%nest` delimiter starting with an apostrophe produced `'''` and
  the generated lexer would not compile. Central artifacts are immutable; that ships as 0.7.4 or
  not at all. The second was a test of mine asserting nothing — `openDelimiterInsideAContentToken`
  fed input containing no delimiter. **Hold the merge until review settles even when CI is green
  and the branch is unprotected**; verify each finding against the code rather than trusting or
  dismissing it (one of five was a false alarm on a recipe I had actually run correctly).

- **A benchmark with no comparable baseline proves nothing — prefer an identity proof.** The
  stored JMH baselines use a `(variant)` axis; the current suite uses `(fixture)`, so the numbers
  cannot be compared and a green run would have been false comfort. Generating `GLexer`/`GParser`
  from both revisions and diffing is exact and noise-free: GParser came out byte-identical and
  GLexer differed only in `.put` ordering, which settles the hot-path question outright. Reach for
  this whenever the change is expected to be behaviour-preserving.


- **A test can pass against the bug it was written for. This happened FOUR times in one session** —
  three of mine, one downstream. Trailing-lookahead tests asserted "the parse reports
  diagnostics", true whether or not the guard was honoured. Recursion tests asserted grammars were
  refused, but left-recursion detection refused them phases before the guard under test. A
  kind-adoption test passed because an unrelated collision in the fixture masked the defect. The
  fix each time was the same: **assert the mechanism, not a downstream symptom** — "no alias array
  names a kind the map no longer points at", "the fallback set is disjoint from the guard set".
  Mutation-check every regression test, and treat a green mutation run as the test failing.
- **`java25.peg` cannot validate lexer-level changes.** Zero case-insensitive literals, zero
  reference-only lexer rules. Eleven defects were provably unreachable from it. "Measured neutral
  on java25, therefore safe" was asserted this session and was wrong — neutrality on a grammar
  that lacks a shape says nothing about grammars that have it. Use a second grammar.
- **Compiling is not parsing.** `postgres.peg` reached `fromGrammar: SUCCESS` while every single
  statement still failed to parse. Gate on CST node counts.
- **An always-empty alternative at the end of a choice hides every failure.** `postgres.peg`'s
  `EmptyStatement` turned every parse error into a silent zero-token match at offset 0.
- **Making a parser stricter surfaces latent bugs in consumer grammars — budget for it.** Three of
  the migration's failures were pre-existing grammar bugs that only became visible once peglib
  stopped being wrong: `!ReservedKeyword` had never fired at 0.6.0 (so no production accepted
  `CURRENT_TIMESTAMP`); `IsClause` had a bare `NotKW NullKW` alternative, so `DEFAULT true NOT
  NULL` parsed as `DEFAULT (true NOT NULL)` and the column stayed nullable; `$` in the identifier
  class swallowed dollar-quote delimiters. None were peglib defects. They were not merely
  invisible but **inverted** — the broken guard made the grammar look permissive, and the nullable
  column looked like a working schema tool right up to the point it reached generated code.
  **A consumer's tests can be green because the bug and the test agree with each other.**
- **Presence of a statement type is not coverage of it.** The pg-tools corpus contains SELECT
  statements — 6, alongside 60 `CreateTableStmt`, 47 `CreateIndexStmt`, 29 `AlterTableStmt` — and
  has **zero** `TargetElem`, `ColLabel`, `WindowSpec`, `JoinClause`, `CaseExpr`. It was
  structurally blind to every SELECT-side change in this migration. Someone pointing at those six
  SELECTs would reasonably claim SELECT coverage and be wrong. The unit tests covered exactly the
  inverse; both instruments were needed, repeatedly.
- **A measurement taken in a broken state stays wrong until someone re-runs it.** The corpus figure
  above was first reported as "100% DDL, zero SELECT" — measured while 18 of 34 files failed to
  parse, so the seed files contributed nothing, and never re-measured once they parsed. Same
  failure mode as a test written against a bug, one level out.
- **Send a mechanism and a reproduction, not a conclusion.** Every round of the downstream
  collaboration that started with a conclusion cost a cycle — a tag-delimited-block lexer feature
  was proposed twice for what turned out to be two grammar lines. Every round that shipped a diff
  plus a diagnostic did not. The best diagnostics of the session came from downstream: an
  `ALTER`-vs-`CREATE` pair with identical constraint text and opposite outcomes, an
  intersection-must-be-zero invariant, and a `CreateKW`/`CaseKW` declaration-order asymmetry.

### Verification recipes

```bash
mvn install                                    # full reactor, lint + format-check, 696 tests
mvn -q jbct:format                             # before committing new main-source code
```

Corpus agreement (~7 s once the blobs are warm; never vendor the GPLv2 corpus):
see `tools/langtools-corpus/README.md`. **Re-measured live 2026-08-22 at 99.45%** on
`release-0.7.3` — failure counts identical to baseline (21 false accepts, 10 false rejects).
Note the first run after a `--filter=blob:none` clone fetches contents lazily and takes minutes,
not seconds; warm the checkout before timing.

Hot-path verification after any change to `LexerEngine.lex` or generated emit — prefer the
identity proof in `docs/bench-results/0.7.3-java25-parse.md` over a timing comparison. The stored
0.4.x baselines use a `(variant)` axis and are NOT comparable to the current `(fixture)` one, so a
bare benchmark run has nothing to compare against. Generating `GLexer`/`GParser` from both
revisions and diffing is exact and noise-free; 0.7.3 was verified that way (GParser
byte-identical, GLexer differing only in `.put` ordering).

`%memo` semantics after any change to `CstArrayBuilder` replay:
`tools/memo-differential/README.md` — differential vs the same grammar without the directive.

---

## Session 12 — 0.7.2 (2026-08-15 → 08-18) — superseded by session 13

The five defects listed here (`%import`, Base64 DFA tables, case-folded literal keys, the single
inline-literal key definition, and generator-version stamping) all still stand. Its two "open
design items" are addressed above. Its claim that the `/cs` relaxation hangs
`CharClassHexEscapeTest` did not reproduce.

---

## Session 11 — 0.7.1 (2026-08-13 → 08-14) — SHIPPED

### State at a glance

| | |
|---|---|
| **Ship state** | **SHIPPED.** PR #40 merged; tag `v0.7.1` at `98837e2`; live on Maven Central, all 6 artifacts with sources, javadoc and GPG signatures verified on repo1. |
| **Version** | 0.7.1 in all 6 poms; CHANGELOG `[0.7.1] - 2026-08-14` |
| **Build** | `mvn install` (lint + format-check on) — BUILD SUCCESS |
| **Tests** | **576** across 5 modules, 0 failures, 0 skips |
| **Probes** | `JavaCoverageProbe` 93/93, `JavaRejectionProbe` 0/0, `ModernJavaSyntaxProbe` 19/19 |
| **langtools** | **99.45%** (5,614/5,645 scored, 24 excluded) — **re-measured 2026-08-14**, not inherited |
| **Performance** | **+1.2% vs 0.7.0** on selfhost (was +9.6%) after `%memo Args` |
| **Working tree** | clean |

> Release mechanics, since no `RELEASE.md` exists and the old handover pointed at one that never
> did: sources, javadoc and GPG signing all live in the **`release` profile**, and the publish is
> **manual** — no CI job is tag-triggered. The command is `mvn -Prelease deploy`. The pom sets
> `autoPublish=true` with `waitUntil=published`, so there is **no staging gate**: the deploy goes
> straight to Central and artifacts there are immutable. A mistake ships 0.7.2, it does not roll
> back. `central-publishing-maven-plugin` is pinned to 0.11.0 because 0.6.0 reported BUILD FAILURE
> for the 0.7.0 release that had in fact succeeded.

### The blocking decision from session 10 is resolved

Session 10 ended on a three-way choice: ship at −9.6%, revert `StmtExpr`, or memoise. **Option 3
was built and it pays.** `%memo` is a grammar-level directive marking a rule whose successful
parse is salvaged when backtracking drops it and replayed if the same rule is re-parsed at the
same token position. Applied as `%memo Args` — *not* `Postfix`, because the JLS 14.8 re-parse
goes through different rules (`Postfix` vs `Primary CallChain`), so the shareable unit is the
argument list both paths parse at the same position.

Same-day interleaved A/B (JMH, 2 forks x 10 iterations, otherwise-idle machine):

| variant | reference (1.9k LOC) | selfhost (40k LOC) | vs 0.7.0 |
|---|---|---|---|
| 0.7.0 (`139b7ca`) | 2.617 ± 0.044 ms | 66.498 ± 0.661 ms | — |
| 0.7.1 no memo (`e48c48b`) | 3.195 ± 0.038 ms | 72.830 ± 1.666 ms | +9.5% |
| **0.7.1 + `%memo Args`** | **2.821 ± 0.014 ms** | **67.307 ± 0.448 ms** | **+1.2%** |

A second round agreed (+9.5% / +3.2%; the memo figure there carried ±3.9 under a load spike, so
+1.2% is the better estimate and +3.2% the pessimistic bound).

### Evidence the memo is semantics-preserving

Replay is an optimisation, so anything observable is a bug. Checked at three levels:

- **43,433 LOC of real Java** (selfhost + FactoryClassGenerator + 21 perf-corpus files) parsed
  through java25.peg with and without the directive: identical diagnostics, `reconstruct()`,
  node count and full preorder CST signature on every file.
- **Error paths**, which the clean-file check could not reach: 10 inputs malformed *inside* an
  argument list, 16–28 diagnostics each — identical count, identical diagnostic text, identical
  CST. This mattered because a salvaged subtree can contain an `Error` node while replay does
  not re-record diagnostics.
- **Interactions**: `MemoInteractionTest` covers `%memo` alongside a per-rule `%recover` sync set
  and across incremental reparses. Both were previously arguments rather than tests.

All three memo suites were mutation-checked — breaking the replay's position guard turns them
red, so they are not passing vacuously.

### Where to pick up, in order

0.7.1 is shipped; there is no outstanding release work. Whatever comes next starts from a clean
`main` at `98837e2`.

1. **Nothing is blocking.** The deferred perf items under "Still open" are the only known backlog,
   and at +1.2% none of them is worth the risk on its own — revisit only if a future change
   reopens the gap.
2. **If the grammar changes materially, re-run the corpus check** before shipping. It is ~6 s once
   fetched and there is no excuse for quoting an inherited agreement number
   (`tools/langtools-corpus/README.md`).
3. **Consider trimming `docs/`.** Four links pointed at files moved into `docs/archive/` and went
   unnoticed across two releases; `PERF-FLAGS.md` still documents `ParserConfig`, deleted in 0.6.0.
   They are repointed and labelled now, but the underlying rot is that archived design docs are
   still referenced from live ones.

### Things worth not re-learning

- **The corpus check is cheap: ~6 s** once fetched, and the fetch is a 36 MB sparse clone. There
  is no reason to ship an inherited agreement number. Instructions in
  `tools/langtools-corpus/README.md`. **Never vendor the corpus** — it is GPLv2, peglib is MIT.
- **Do not clone repos into a benchmarked machine's temp dir mid-measurement.** Doing exactly
  that triggered Spotlight (`mds` at 44% CPU, load 13) and produced a run with ±43% error bars
  that flatly contradicted a clean run twenty minutes earlier. Check `ps`/`uptime` before
  trusting a surprising delta.
- **JMH benchmark selection is a regex and the shell here is zsh.** `ARGS="Bench -wi 5"` then
  `java -jar b.jar $ARGS` passes ONE argument, because zsh does not word-split unquoted
  expansions. It fails as "No matching benchmarks", exits in under a second, and looks like a
  benchmark that ran.
- **`mvn install`, not `mvn -pl peglib-core test`, before committing.** Also: `jbct:format`
  before committing new main-source code — format-check gates the build and will fail the
  reactor after all tests pass, which reads confusingly.
- **PEG repetition is possessive**, and alternative order is semantic, not just cost.
  Reordering `StmtExpr` for speed silently broke `foo().bar = 1;`. Any reorder needs a corpus
  re-run, not just a test run.
- **Profile before optimising.** Repeatedly this release the profiler contradicted a confident
  hypothesis — the top node-builder was `parseAnnotation`, not the statement rule everyone
  suspected.

---

## Performance — closed for 0.7.1

Three mitigations landed, in this order: **first-token guard** (−9.9%), **link-on-success CST
building** (−8.7%), **`%memo Args`** (−7.4%). Net: +33% regression reduced to +1.2%.

### Still open — deliberately not done

Recorded so they are skipped on purpose rather than forgotten. At +1.2% the remaining upside is
small and each carries real risk:

1. **Extend the first-token guard through references.** It currently fires only for
   literal-rooted rules. A FIRST-set fixpoint over references would cover far more but must
   preserve identifier-fallback for contextual keywords — get it wrong and valid code is
   rejected.
2. **More `%memo` sites.** `Args` was chosen from a profile. A fresh post-memo profile would say
   whether anything else repeats; nothing suggests it does.
3. **Defer `beginNode`'s payload writes to `endNode`.** Mostly superseded by link-on-success
   (beginNode self time is ~3.6%); would widen the endNode signature and reorder setFlag in the
   splice path.

**Do NOT retry the `truncate` link-skip.** It was measured (82.380 → 84.644 ms, nominally worse)
and reverted. `truncate`'s cost is the backward walk, not the link repair: the loop still reads
`nodes[i * NODE_STRIDE]` for every dropped node, so memory traffic is identical.

### Reproducing the A/B

The bench jar resolves fixtures relative to CWD, so run from `peglib-core/`. Build a baseline by
cloning rather than stashing — a stash across a parallel agent dispatch has lost work here before:

```bash
mvn -pl peglib-core -am -Pbench -DskipTests package
git clone . /tmp/peglib-base -b main    # or any baseline ref
cd peglib-core && java -jar target/benchmarks.jar Java25LargeFixturesBenchmark -wi 5 -i 10 -f 2
```

Interleave variants (A,B,A,B) rather than running each to completion — machine drift over a
10-minute window is larger than the effect being measured.

### Remaining corpus gap

31 disagreements: 10 wrongly rejected (all "trailing input not consumed"), 21 wrongly accepted.
**19 are permanent by design** — not context-free, deliberate trades, or cases where peglib is
JLS-correct and javac defers to Attr. Each is dispositioned in `tools/langtools-corpus/README.md`.
Practical ceiling is ~99.66%; the last stretch chases javac's corners, not correctness.

---

## Session 10 — 0.7.1 (2026-08-06 → 08-09) — superseded by session 11

Kept for the reasoning behind the guard and link-on-success, both of which still ship.

### What landed

**Methodology — the number you inherited was wrong.** The corpus is now differenced against
**javac's own parse phase** (`JavacTask.parse()`), in both directions, over all 5,666 files. The
previous "97.8% over 3,555 positive tests" used a heuristic that split the corpus by grepping for
`@compile/fail` markers; that scored roughly 77 genuine grammar gaps as expected failures, so it
could not have found more than half of them. True baseline was 95.55%. The heuristic
(`classify.py`) is deleted. Tooling and the full disposition of every remaining disagreement live
in `tools/langtools-corpus/`.

| | baseline | now |
|---|---|---|
| we wrongly reject | 155 | **10** |
| we wrongly accept | 73 | **21** |
| agreement | 95.55% | **99.45%** |

**Engine fixes** (not just grammar): nullable start rules accept empty and comment-only
compilation units; trailing input after a partial parse is reported instead of silently
re-parsed as a second document; Unicode escape translation (JLS 3.3) with token spans remapped
onto the original text so round-trip survives; hex escapes decoded inside character classes;
opt-in escape-aware delimited blocks; a first-token guard that skips node allocation for
rules that cannot match; and link-on-success CST building (2026-08-09) that makes
backtracking rollback O(completed-and-dropped) instead of O(dropped).

**Grammar**: ~40 acceptance fixes and ~25 over-permissiveness fixes. Enumerated in the CHANGELOG.

**Gates that were not running.** Surefire matched only `**/*Test.java` and `**/*Example.java`, so
`JavaCoverageProbe` and `ModernJavaSyntaxProbe` were compiled but **never executed** — a grammar
regression would have reached the field with a green build. Fixed. `JavaRejectionProbe` is new:
the mirror gate for what must be REJECTED, every case paired with a legal near-miss, because the
cheap way to pass a rejection test is to over-tighten until valid code breaks too.

**Also**: CI unpinned from the stale `25-ea` to `25` GA; two formatter fixtures that contained
invalid Java (a constructor inside an `interface`) corrected; `IdentifierFallbackTest` corrected
after checking javac — it asserted that a bare `yield()` call parses, which javac rejects.

### Licensing, and other things worth not re-learning

- **Licensing: never vendor the corpus.** It is GPLv2 (only 38 of 4,261 headers carry the
  Classpath Exception); peglib is MIT. Fetching at run time and reading is fine; committing
  files is not. All 240 substantive probe snippets were checked as literal substrings against
  all 5,667 corpus files — none matches verbatim, and it should stay that way. Details in
  `tools/langtools-corpus/README.md`.
- **A duplicated token in formatter output means the PARSE failed**, not that backtracking
  leaked. Check the diagnostic count before suspecting `CstArrayBuilder.truncate`.
- **The link-on-success contract:** a node is invisible until `endNode` links it, and every
  surviving node must be ended exactly once, on its success path. Ending twice double-links.
  The generated parser, `parseWithRecovery`'s synthetic root, both recovery-error emitters and
  `CstArray.spliceSubtree` were each verified against this, not assumed.

---

## Session 9 — 0.7.0 (2026-08-01 → 08-05) — SHIPPED

### State at a glance

| | |
|---|---|
| **Ship state** | **SHIPPED.** PR #38 merged; tag `v0.7.0` at `57ad0d4`; live on Maven Central, deployment `d19659b0-cd3b-4748-b0a8-87fdc5f9a79b`, 6 artifacts, all GPG-signed. |
| **Build** | `mvn install` — **no `-Djbct.skip=true`** — BUILD SUCCESS |
| **Tests** | **528 across 5 modules**, 0 failures, 0 errors, 0 skips |
| **JBCT** | **0 hard errors**, 0 unformatted files, ~709 warnings (not gated) |
| **Reactor** | 5 modules — `peglib-incremental` was deleted |
| **Working tree** | clean |

### What landed

1. **The 0.5.x legacy path is gone.** 146 files, ~38,900 lines: the `PegEngine` interpreter,
   `action`, 0.5.x `generator`, recursive `tree`, the whole `peglib-incremental` module, and
   `GenerateMojo`. Shared infrastructure survived: `peg.grammar`, `LeftRecursionAnalysis`,
   `ParseError`, the span types, and the `Doc`/`Docs`/`Renderer` algebra.
2. **`org.pragmatica.peg.v6.*` collapsed into `org.pragmatica.peg.*`**, `V6`-prefixed class
   names lost the marker, `peg.tree` → `peg.source`, and the mojo goal `generate-v6` → `generate`.
3. **JBCT plugin and `pragmatica-lite:core` both to 1.0.0-rc2**, plugin moved to the parent pom,
   `skip=false`, all five modules linted. **The core bump broke nothing** — 528 tests passed
   unchanged across a major version jump.
4. **All 105 JBCT hard errors resolved.** Refactored where possible; suppressed only where a
   platform contract forbids the alternative (Maven `AbstractMojo`, `main`, `HttpHandler`,
   `Result.lift` adapter bodies), each with a written reason.
5. **`JsonDecoder` deleted in favour of `org.pragmatica-lite:jackson`** — 274 lines removed.
6. **JEP 401 value classes**, plus `outer.new`, annotated type params, hex floats.
7. **CST-shape sanity gate** added to `Java25ParserGateTest`.
8. **JEP 512 compact source files** — `void main() { }` with no enclosing class now parses.
   `TypeDecl` stays the first alternative of `OrdinaryUnit`, verified by corpus node counts
   being byte-identical before and after (884/1040/1904/586/605/833/135/447).

9. **JBCT warning policy decided and encoded.** Hot path (`peglib-runtime` + `LexerEngine`)
   carries class-level `@SuppressWarnings({"JBCT-PAT-01", "JBCT-UTIL-02"})` with the reasoning
   inline; policy written up in CLAUDE.md. 694 → 657 warnings, none gating the build.

`ModernJavaSyntaxProbe` now reports **19/19**; no known grammar gaps remain.

### Where to pick up — ordered

0.7.0 is shipped and its follow-up (PR #39, publishing-plugin bump) is merged. `release-0.7.1`
is branched from `main` at `139b7ca` with versions bumped and an empty CHANGELOG section; it
carries no changes of its own yet.

1. **Nothing is outstanding for 0.7.0.** The grammar has no known gaps — `ModernJavaSyntaxProbe`
   19/19 and `JavaCoverageProbe` 40/40, both asserting.

2. **Candidates for 0.7.1**, none urgent:
   - `JsonEncoder` could follow `JsonDecoder` onto `JsonMapper.writeAsString`. Deferred because
     its output shape is what `playground.js` renders and it has zero lint errors.
   - CI pins `java-version: '25-ea'`. It still resolves, but Java 25 went GA in September 2025,
     so the early-access label is stale and will eventually break. Unrelated to any release.
   - ~657 JBCT warnings remain by design; see the warning policy in CLAUDE.md before touching
     any of them.

### Things worth not re-learning

- **A contextual keyword needing lookahead cannot live in a named rule.** `RuleClassifier` types
  any rule whose body references only lexer rules as LEXER, and lexer rules may not reference
  other rules — `fromGrammar` rejects it with `SkippedRuleReferenced`. Two attempts
  (`DeclModifier`, then `ValueMod`) both failed before the lookahead was spelled inline inside
  the PARSER rules. The 0.6.2 guard did its job: loud failure, not a silent dead token kind.
- **`value` is the highest-risk contextual keyword yet** — far more common as an ordinary
  identifier than `record` or `sealed`. It is confined to declaration-modifier position for
  exactly that reason; `LocalVar` / `Param` / `Catch` / `Resource` keep plain `Modifier*`.
- **The JMH benchmarks resolve the grammar relative to CWD** and must be run from `peglib-core/`.
  From the repo root every iteration fails with `NoSuchFileException` and JMH still reports a
  clean-looking empty result table.
- **`IncrementalEditBenchmark` on `selfhost` cannot resolve differences below ~15%** on this
  machine. An unchanged jar measured 20944 µs and 23254 µs on consecutive runs. Do not read a
  single-run delta there as a regression — re-run before believing it.
- **The 0.5.x A/B benchmarks were deleted with the legacy path.** The historical "11-12× faster
  than 0.5.x-gen" figure is no longer reproducible in-tree. Treat it as dated, not live.
- **A published release can report BUILD FAILURE.** See item 1. Check
  `repo1.maven.org/maven2/org/pragmatica-lite/<artifact>/<v>/<artifact>-<v>.jar` returns 200;
  the `<release>` field in `maven-metadata.xml` is also authoritative. Do not re-run a deploy
  on a failure without checking first — the bundle may already be published.
- **Most JBCT warnings are cold-path, not hot-path.** `DfaBuilder` (127) and `ParserGenerator`
  (113) dominate the count but run once per grammar at `fromGrammar` time — they never touch a
  warm parse. Only `peglib-runtime` + `LexerEngine` are per-token/per-node. Do not conflate
  "most warnings" with "performance critical".
- **The keep-set during a large delete is easy to over-scope.** `grammar/analysis/` was kept
  whole; only `LeftRecursionAnalysis` was live. `ExpressionShape` and `FirstCharAnalysis` (264
  LOC) survived a full session and were removed later — after one of them had been pointlessly
  refactored. Verify liveness per file, not per package.

---

## Session 8 — 0.6.3 (2026-06-07)

### State at a glance

| | |
|---|---|
| **Release** | **v0.6.3** — PR #37 merged → `main` at `f025a22`, tagged, published |
| **Maven Central** | deployment `8980d093-7621-44ec-8a74-642234248f2d`, auto-published, 7 artifacts; deploy 6:45 min (normal queue) |
| **Tests** | **1424 passing** across 7 modules, 0 failures, **0 skips** (first zero-skip state) |

### What landed in session 8 (0.6.3 patch items)

1. **Legacy `PegEngine` cut-failure symmetry** — `Optional`, `ZeroOrMore`, `OneOrMore`, and bounded `{n,m}` repetition all swallowed the pending-trivia snapshot on the `CutFailure` path (regular failure restored it). All four now restore symmetrically. Formerly `@Disabled` cut test re-enabled + 4 new combinator tests. Legacy interpreter only — no v6 hot-path impact, no bench needed.
2. **`LexerGeneratorTest.parity_triviaClassification_lineAndBlockComments` re-enabled** — both skip reasons (block-comment-in-Choice routing, whitespace-run coalescing) were already resolved by the 0.6.2 folded-`%whitespace` fix; passed as written.
3. **0.6.2 deploy-time "anomaly" closed as non-issue** — the 24:56 was Maven Central's publish-queue wait attributed to the last reactor module; 0.6.3 deployed in 6:45 with identical config, confirming.

### Remaining backlog (unchanged, all 0.7+/external)

- Token pool / arena (H) · lexer modes (I) — 0.7 features
- JBCT plugin bump (K) — blocked on upstream formatter fix (>0.25.0); `-Djbct.skip=true` still required
- jbct v6 API migration — jbct repo
- Lexer-rule arbitrary lookahead ("Phase B") — only `literal+ (!literal)*` shape supported (0.6.2 inline expansion); other shapes fail loudly at `fromGrammar`
- Stale note: project CLAUDE.md still says named captures are rejected at `fromGrammar` — restored in 0.6.1; fix on next CLAUDE.md touch

---

## Session 7 — 0.6.2 (2026-06-06)

### State at a glance

| | |
|---|---|
| **Release** | **v0.6.2** on `release-0.6.2` (patch) |
| **Tests** | **1420 passing** across 7 modules, 0 failures, 2 pre-existing skips |
| **Java25 corpus** | 20/20 clean parse |
| **Selfhost (40K LOC)** | **0 diagnostics**, 1.26M CST nodes |
| **Real-world Java** | FactoryClassGenerator (1900 LOC): 0 diagnostics |
| **JMH** | reference 2.68 ms, selfhost 71.9 ms — flat vs 0.6.1 |

### What landed in session 7 (0.6.2 patch items)

1. **Shift operators (`<<`/`>>`/`>>>`) in field/local-var initializer context** — fixed. Root cause was NOT the 0.6.1 rollback hypothesis (`Type`/`Relational`/`TypeArgs` `<` rollback); it was a **dead token kind from a lexer rule silently skipped by the DFA**. Fix: inline expansion of DFA-skipped lexer rules of the `literal+ (!literal)*` shape, plus a loud `SkippedRuleReferenced` guard at `fromGrammar`. Both `Java25SelfHostDiagTest` assertions re-enabled; selfhost fixture (40K LOC) now parses with **0 diagnostics**, 1,261,302 CST nodes.
2. **Per-iteration `%whitespace` tokenization** — folded `%whitespace <- (...)*` now emits per-kind trivia tokens via per-alternative DFA absorption. The c4169b6 canonical-grammar split workaround is **REVERTED** in `java25.peg`; the empty-match warning is gone for the folded form; 2 previously-disabled trivia tests re-enabled.

Skips moved 4 → 2 (remaining: `LexerGeneratorTest` parity 1, `TriviaAdversarialTest$OptionalCutFailurePending` 1).

### Ship state

- **PR #36 merged** → `main` at `5c8126d`, tagged `v0.6.2` (2026-06-06).
- **Deployed to Maven Central**, deployment `8ac8bbfd-51ec-4cc6-ab4e-25f596320c75`, auto-published — all 7 artifacts live at `repo1.maven.org/maven2/org/pragmatica-lite/*/0.6.2/`.
- **Deploy-time note (investigated, resolved — no action)**: 0.6.2 deploy total 24:56 min looked like a `peglib-playground` problem (24:06 in reactor summary) but the log shows playground's own work finished in seconds; the time was `central-publishing-maven-plugin`'s `Waiting until Deployment ... is published` poll — Maven Central's server-side publish queue — attributed to the last reactor module. 0.6.1 took ~4-5 min through the same mechanism. Keep `waitUntil=published` (sync confirmation is worth the wait on a rare operation).
- **Remaining backlog unchanged**: jbct v6 API migration (jbct repo), token pool (H), lexer modes (I), JBCT plugin bump (K), upstream JBCT formatter convergence bug (`-Djbct.skip=true` still required).

---

## SESSION 6 SUMMARY — 0.6.1 SHIP

### State at a glance

| | |
|---|---|
| **Release** | **v0.6.1** releasing to Maven Central (2026-05-14) |
| **Branch** | `release-0.6.1` — 12 commits ahead of `main` at `9e2a6fe` |
| **Local artifacts** | `~/.m2/repository/org/pragmatica-lite/peglib*/0.6.1` |
| **Working tree** | clean |
| **Tests** | **1440 passing** across 7 modules, 0 failures, 4 pre-existing skips |
| **Java25 corpus** | 20/20 clean parse |
| **Real-world Java** | FactoryClassGenerator (1900 LOC): 0 diagnostics |
| **Selfhost gate** | `Java25SelfHostDiagTest` dumps diagnostics; 2 assertions @Disabled pending shift-in-FieldDecl fix (0.6.2) |

### What landed in session 6 (0.6.1 patch items)

1. **A — Doc-comment trivia kinds**: `KIND_DOC_LINE_COMMENT` (3) and `KIND_DOC_BLOCK_COMMENT` (4) added to `TokenArray`. `FIRST_USER_KIND` shifted to 5. Post-DFA classification extended in `LexerEngine` and `LexerGenerator` for `///` and `/**` prefixes.
2. **B — Per-rule `%recover` sync sets**: `ParserGenerator` emits one `SYNC_<Rule>` int[] per rule with explicit recovery; `lastFailedRuleKind` field routes each failure to its rule's sync set via `syncForRule(int)`.
3. **C — `%checkpoint` grammar directive**: `GrammarParser` recognizes `%checkpoint RuleName`; stored in `Grammar.checkpointRules()`; `IncrementalParser` consumes it (falls back to `DEFAULT_CHECKPOINT_RULES` if empty). Canonical `java25.peg` declares `Stmt`, `MethodDecl`, `TypeDecl` as checkpoints.
4. **D — Named captures + back-references runtime**: `$name<expr>` and `$name` implemented in `ParserGenerator` via `Map<String, long[]> captures` + `ArrayDeque<Map> captureScopeStack`. Source-span equality. `NamedCaptureDetector` rejection removed from `PegParser.fromGrammar`.
5. **E — MIXED-rule char-level fallback**: `CharClass` and `Any` in MIXED rules emit a token-level proxy using `input.charAt(tokens.startAt(pos))`. `EmitContext` threads `RuleKind`.
6. **F — Selfhost gate**: `Java25SelfHostDiagTest` added. Shift-in-FieldDecl bug identified and deferred to 0.6.2 (parsing FieldDecl/LocalVar init with `<` shift ops fails at `CompilationUnit` level; root cause: `Type`/`Relational`/`TypeArgs` `<` rollback).
7. **G — `maxDiagnostics` wired through**: `parse(String, int)` cap honored in generated recovery loop; `parseCappedMethod` exposed via reflection in `ParserCompiler`.
8. **J — README + visitor tutorial**: README rewritten (564 → 367 lines, 0.6.x-only, concrete examples). `docs/VISITOR-TUTORIAL.md` added (489-line calculator walkthrough).

### Bonus fixes (not in original scope)

- **`TriviaPostPass.rebuildNonTerminal` first-member trivia loss** (0.5.x legacy): cursor now advances past non-whitespace prefix before scanning for leading trivia. Bug report in `docs/bugs/first-member-trivia-loss-2026-05-12.md`.
- **Lexer empty-match warning softened**: names the offending rule, clarifies the lexer will not throw (emits synthetic 1-char WHITESPACE on stall).
- **Canonical `java25.peg` `%whitespace` split**: changed from folded `(...)*` to flat alternatives so v6 emits per-kind trivia tokens (LINE_COMMENT, DOC_LINE_COMMENT). Folded form coalesced the entire run into a single WHITESPACE-kind token. See `docs/GRAMMAR-DSL.md` for guidance.
- **jbct investigation**: jbct ships a self-contained 26K-line `Java25Parser.java` (0.5.x era); no peglib Maven dependency. The `%whitespace` grammar fix and TriviaPostPass fix were confirmed beneficial after jbct adopted the split `%whitespace` pattern (`CommentsExtended.java`: LINE_COMMENT=9, DOC_LINE_COMMENT=8 in histogram vs all-zero before).

### Known limitations (carrying forward)

**Intentional drops** (per spec §3 — NOT returning):
- BASIC/ADVANCED recovery split; inline `{...}` action blocks; `AstNode` type; packrat memoization

**Deferred to 0.6.2 or 0.7**:
- **Shift-in-FieldDecl bug** (0.6.2 target): shift operators in field/local-var init context fail at `CompilationUnit` level. Hypothesis: `Type`/`Relational`/`TypeArgs` `<` literal rollback corrupts state. 2 `Java25SelfHostDiagTest` assertions `@Disabled` pending fix. ✅ RESOLVED in 0.6.2
- **Per-iteration `%whitespace` tokenization** (Item A harder part): ZeroOrMore loop itself should drive per-iteration token emission rather than relying on grammar split. Deferred. ✅ RESOLVED in 0.6.2
- **jbct v6 API migration** (0.6.2): jbct `46ac5e993` applied the `%whitespace` grammar split fix; full peglib v6 API migration remains. (split workaround obsolete since 0.6.2 — folded form now emits per-kind trivia; full jbct v6 API migration still pending)
- **JBCT `<skip>true</skip>`** in `peglib-core/pom.xml`: formatter convergence bug on 5 v6 files. Lint passes cleanly; tracking upstream.
- **Items H (token pool), I (lexer modes), K (JBCT plugin bump)**: 0.7+ backlog.

### Quick-start for next session

1. **Read this file**, then `docs/ARCHITECTURE-0.6.0.md` if going architectural.
2. **Read `CLAUDE.md`** — banked lessons (parser-domain + collaboration notes).
3. **Verify**: `mvn install -Djbct.skip=true` → 1420 tests pass.
4. **0.6.2 target**: fix shift-in-FieldDecl bug (see `Java25SelfHostDiagTest` disabled assertions), then re-enable the gate. (done in 0.6.2)
5. **Agents**: `jbct-coder` for ALL coding, `build-runner` for `mvn`, `chore-runner` for git/changelog.

---

## SESSION 5 SUMMARY — 0.6.0 LIVE

### State at a glance

| | |
|---|---|
| **Release** | **v0.6.0** live on Maven Central (deployed 2026-05-11) |
| **Branch** | `main` at `3518df0` |
| **Tag** | `v0.6.0` at `94ef675` (PR #34 merge commit) |
| **Local artifacts** | `~/.m2/repository/org/pragmatica-lite/peglib*/0.6.0` |
| **Working tree** | clean |
| **Tests** | **1440 passing** across 7 modules, 0 failures, 4 pre-existing skips |
| **Java25 corpus** | 20/20 clean parse |
| **Real-world Java** | FactoryClassGenerator (1900 LOC, JBCT generator): 0 diagnostics |
| **Perf vs 0.5.x-gen** | **11-12× faster, 9-13× less memory** |
| **Perf vs javac parse-only** | **1.20-1.83× of javac time** (same category) |
| **Incremental edits** | sub-ms p50 (~700µs), p99 ~1.5ms |

### What landed in session 5 (post-rc, ship + polish)

1. **JBCT plugin bumped** 0.4.1 → 0.25.0 (fixed parser crash on `DfaBuilder.java`)
2. **v6 JBCT-0.25.0 conformance refactor**: 123 lint errors → 0 (throws → `Result`, nulls → `Option`, `Result<Void>` → `Result<Unit>`, hot-path void mutators retained with `@SuppressWarnings("JBCT-RET-01")`)
3. **peglib-runtime module extracted** (25KB jar): standalone-parser invariant met. Generated parsers depend ONLY on peglib-runtime + pragmatica-lite:core.
4. **Formatter corpus validation**: 20/20 round-trip; found and fixed 2 real bugs (multi-line leaf text crashed `Doc.Text`; inline-literal tokens dropped by `Docs.concat`)
5. **Bounded-scan truncate** (CstArrayBuilder): 24-48× hot-path speedup
6. **DFA Unicode support**: non-ASCII chars in comments/strings/identifiers now work
7. **Asymmetric delimited blocks**: `'/*' (!'*/' .)* '*/'` inside Choice now routes through `compileDelimitedBlock`
8. **Contextual-keyword Identifier fallback**: `open`/`module`/`record`/`yield`/etc. accept as identifiers
9. **FactoryClassGenerator real-world parse**: 13,529 → 0 diagnostics
10. **java25.peg fixes**: `>>` split to single `>` tokens (nested generics), `Annotation*` on `var` decls
11. **CHANGELOG, MIGRATION guide, README** updated for 0.6.0
12. **PR #34 merged**, tagged `v0.6.0`, deployed to Maven Central (deployment `d0511073-6ec1-4e4e-89ce-415f949e8516`)
13. **CLAUDE.md refreshed**: banked lessons + collaboration notes; stale 0.5.x content removed

### Known limitations (carrying forward — none ship-blocking)

**Intentional drops** (per spec §3 — NOT returning):
- BASIC/ADVANCED recovery split (one always-on mechanism)
- Inline `{...}` action blocks (replaced by Visitor pattern)
- `AstNode` type (CST only)
- Packrat memoization (tokens-first design)

**Deferred for 0.6.x patches or 0.7**:
- Per-rule `%recover` sync sets (start-rule only currently)
- MIXED-rule char-level fallback (no-op; affects 5 Java25 rules)
- `ParserOptions.maxDiagnostics` (stub; no current callers)
- Per-iteration trivia tokens for `%whitespace` ZeroOrMore (currently coalesces into single token)
- Named captures + back-references runtime (rejects at `fromGrammar` with helpful error)
- JBCT `<skip>true</skip>` in `peglib-core/pom.xml` (lint passes cleanly; only the upstream formatter has a convergence bug on 5 v6 files)
- True partial parse on `selfhost` (40K LOC) fixture (parses with some diagnostics — grammar gaps in specific Java patterns) (resolved: 0 diagnostics as of 0.6.2)

### Possible next-session targets

In order of likely value:

1. **0.6.1 patch (no rush)**: address any user-reported bugs from Maven Central uptake. Fix the JBCT format-stuck-files upstream issue once JBCT 0.26+ ships.
2. **Per-rule `%recover` sync sets**: spec §3.8 calls for per-rule recovery; currently only start-rule. Real value for IDE integration.
3. **MIXED-rule char-level fallback** in generated parser: closes the 5 deferred Java25 rules. Improves CST shape for rare edge-case patterns.
4. **Selfhost fixture grammar gaps**: bisect the remaining diagnostics in `Java25SelfHost-v51.java.txt` to identify and fix the specific Java patterns the grammar misses.
5. **`ParserOptions` wired through**: actually honor `maxDiagnostics` cap. Small, mechanical.
6. **Documentation arc**: README polish (currently brief mention of 0.6.0), tutorial-style docs for the visitor pattern, real-world examples beyond the test corpus.
7. **0.7 architectural moves** (open):
   - Better contextual keyword handling (lexer modes?)
   - Token-array pool / arena for memory-sensitive workloads (close the 3× allocation gap vs javac)
   - Generic improvements to `IncrementalParser` (e.g., snapshot stack for undo/redo support)

### Quick-start for next session

1. **Read this file**, then `docs/ARCHITECTURE-0.6.0.md` if going architectural.
2. **Read `CLAUDE.md`** — refreshed with banked lessons (parser-domain + collaboration notes). Honor the rules; they were earned.
3. **Verify current state**: `mvn install` → 1440 tests pass.
4. **Pick a target** from the list above, or whatever surfaces from user reports.
5. **Useful agents**: `jbct-coder` for ALL coding, `build-runner` for `mvn`, `chore-runner` for git/changelog work.

### Critical lessons banked (DON'T RE-LEARN)

These were earned over 5 sessions of work. They live in `CLAUDE.md` now but worth restating:

- **Bisection-first on parser bugs.** Theorize never. Em-dash bug took 6 bisect rounds; 3 prior theory-hypotheses were all wrong.
- **Profile-first for perf claims.** Mental models of JIT'd Java hot paths are systematically wrong. `CstArrayBuilder.truncate` was 75% of CPU — not on any pre-profile theory list.
- **CST shape sanity in phase gates.** N LOC ≈ N/3 to N CST nodes for this grammar. 11 nodes/fixture for 1900-LOC files was a false positive that hid the empty-CompilationUnit issue for two sessions.
- **Curated fixtures prove not-broken, not complete.** Real-world Java input must be tested early.
- **Contextual keywords need identifier-fallback** in tokens-first PEG. Known design risk; still hit it.
- **Parallel agents + uncommitted impactful changes = stash collision risk.** Commit checkpoints before parallel dispatch.
- **DFA alphabet 0..256 + per-state non-ASCII slot.** Don't extend alphabet to full Unicode.
- **Generated parsers depend ONLY on peglib-runtime + pragmatica-lite:core.** Standalone-parser invariant.

---

## SESSION 4 SUMMARY — 0.6.0 ship-ready (preserved as pre-merge snapshot)

### State at a glance

| | |
|---|---|
| **Active branch** | `release-0.6.0` at `4a2799d`, tagged `v0.6.0-candidate` |
| **Tests** | **1440 passing** across 7 modules, 0 failures, 4 pre-existing skips |
| **Java25 corpus** | 20/20 clean parse |
| **Real-world Java** | FactoryClassGenerator.java (1900 LOC, JBCT generator): 0 diagnostics |
| **vs 0.5.x-gen** | **11-12× faster** |
| **vs javac parse-only** | **1.20-1.83× of javac** (same category) |
| **Incremental** | Sub-ms p50, p99 ~1.5ms |
| **Working tree** | clean |

### What shipped across sessions 2-4

Phase A-F per spec §7 — all implemented or documented as known limitations:

- **Phase A**: Lexer foundation — DFA construction, TokenArray, GLexer codegen, Java25 corpus byte-equal
- **Phase B**: Parser — flat CST, ParseResult+Diagnostic, ParserGenerator, panic recovery, %recover directive, Cut operator, lexer-rule aliasing, StringLit/delimited blocks
- **Phase C**: PegParser API with generate-compile-cache
- **Phase D**: Incremental engine — TokenArray.spliceLex, CstArray.findCheckpointAncestor + spliceSubtree, IncrementalParser, true partial reparse
- **Phase E.1**: GVisitor stub generation
- **Phase E.2-3**: peglib-formatter + peglib-maven-plugin + peglib-playground migrations (parallel package)
- **Phase F**: Bench validated, migration guide written

### Critical fixes landed in session 4

1. **Bounded-scan truncate** (CstArrayBuilder): 24-48× speedup on the hot path — eliminated the 75% CPU dominant cost
2. **JBCT 0.25.0 v6 conformance**: 0 lint errors after refactor (throws → Result, nulls → Option, void mutators @SuppressWarnings)
3. **peglib-runtime module**: standalone-parser invariant met; 25KB jar
4. **Formatter corpus validation**: 20/20 round-trip; 2 bugs found and fixed
5. **DFA Unicode handling**: non-ASCII chars in comments/strings now work
6. **Asymmetric delimited blocks**: block comments inside Choice now route through compileDelimitedBlock
7. **Identifier fallback**: contextual keywords (open/module/record/yield) accepted as identifiers
8. **java25 grammar**: `>>` split into single `>` tokens (nested generics), Annotation* on var decls

### Known limitations (intentional or deferred)

**Intentional drops** (per spec §3 — NOT returning):
- BASIC/ADVANCED recovery split (one always-on mechanism)
- Inline `{...}` action blocks (replaced by Visitor)
- AstNode type (CST only)
- Packrat memoization (tokens-first design)

**Deferred for 0.6.x or 0.7**:
- Per-rule `%recover` sync sets (start-rule only currently)
- MIXED-rule char-level fallback (no-op)
- `ParserOptions.maxDiagnostics` (stub)
- Per-iteration trivia tokens for `%whitespace` ZeroOrMore
- Named captures + back-references runtime (rejected at fromGrammar with clear error)
- JBCT `<skip>true</skip>` due to upstream formatter convergence bug on 5 v6 files (lint itself passes cleanly)

### Release recommendation

Ship as **0.6.0 stable** (not rc). Validation is strong:
- All architectural goals met
- Apples-to-apples javac comparison shows competitive performance
- Tests + corpus + real-world fixtures all clean
- Migration guide ready (`docs/MIGRATION-0.5-TO-0.6.md`)

PR will be open against `main` for review.

---

## ARCHITECTURAL DECISIONS (for next session)

See §11 below for the 9-decision summary. See `docs/ARCHITECTURE-0.6.0.md` for the full spec.

---

## SESSION 2 SUMMARY (2026-05-10)

### State at a glance

| | |
|---|---|
| **Active branch** | `release-0.6.0` (NOT yet committed beyond `c60a610`; all v6 work in untracked files) |
| **Working tree** | All v6 code in NEW files under `peglib-core/src/{main,test}/java/org/pragmatica/peg/**`; **0.5.x untouched** (parallel-package strategy succeeded) |
| **Test count** | peglib-core: **1019 + 1 skip, all green** (up from 805 baseline; +214 new in v6 packages) |
| **Java25 corpus** | **12/20 fixtures parse cleanly**; 8 still recover with diagnostics (grammar/parser quality issues — see §3) |
| **Cold compile** | 261-919ms (under spec target 600ms when JVM warm) |
| **Warm parse** | 4ms total for 41KB Java25 corpus across 20 fixtures |

### Sub-tasks completed (17)

**Phase A — Lexer foundation (5/5)**
- A.1: RuleClassifier (LEXER/PARSER/MIXED + skip-prefix detection)
- A.2: TokenArray + TokenArrayBuilder (flat int[])
- A.3: Dfa + DfaBuilder (Thompson NFA → subset construction)
- A.4: LexerEngine + LexerGenerator + LexerCompiler
- A.5: Java25 corpus byte-equal gate (20/20 lex round-trip)

**Phase B — Parser (5/5 + B.5/B.6 partial)**
- B.0: Token granularity via post-DFA keyword resolution (87.9% → 3.20% ANY_CHAR)
- B.1: CstArray + CstArrayBuilder + CstNode (32 bytes/node vs 80-200 in 0.5.x)
- B.2: ParseResult + Diagnostic (Rust-style format)
- B.3: ParserGenerator + ParserCompiler (272KB Java25 parser)
- B.4: Panic-mode error recovery
- B.5: Lexer-rule aliasing to inline literals (1/20 → 4/20 corpus clean)
- B.6: StringLit lexer fix (Choice partial-absorption + delimited-block KMP DFA) — 4/20 → 12/20 clean

**Phase C — User API (1/1 + gate)**
- C.1: PegParser.fromGrammar() + Parser facade with generate-compile-cache (ConcurrentHashMap, AtomicLong class-name uniquification)
- C gate: All 20 corpus fixtures round-trip via PegParser

**Phase D — Incremental engine (simple-first, 3/3)**
- D.0: TokenArray.spliceLex (full re-lex; D.0.1 deferred for windowed splice)
- D.1: CstArray.findCheckpointAncestor (CstArray.spliceSubtree deferred to D.1.1)
- D.2: IncrementalParser wrapper class (full reparse on each edit; infrastructure for true incremental in place)

**Phase E — Visitor (1/many)**
- E.1: VisitorGenerator (GVisitor<T> stub class generation per grammar)

### Code surface added

```
peglib-core/src/main/java/org/pragmatica/peg/
├── PegParser.java                          (entry point + cache)
├── Parser.java                             (facade)
├── token/
│   ├── TokenArray.java                     (+ spliceLex method)
│   └── TokenArrayBuilder.java
├── lexer/
│   ├── RuleKind.java
│   ├── RuleClassifier.java                 (+ skip-prefix detection for !Keyword Body)
│   ├── Dfa.java
│   ├── DfaBuilder.java                     (NFA→DFA, inline literals, keyword resolution, aliasing, delimited-block, ANY_CHAR fallback)
│   └── LexerEngine.java                    (post-DFA keyword resolution)
├── cst/
│   ├── CstArray.java                       (+ findCheckpointAncestor)
│   ├── CstArrayBuilder.java                (+ truncate)
│   ├── CstNode.java                        (sealed Branch/Leaf/Error views)
│   └── ParseResult.java
├── diagnostic/
│   ├── Severity.java
│   └── Diagnostic.java                     (formatRustStyle)
├── generator/
│   ├── LexerGenerator.java
│   ├── LexerCompiler.java                  (JDK Compiler API)
│   ├── ParserGenerator.java                (recursive descent over tokens; alias matching; full-consumption + recovery loop with synthetic _ROOT)
│   ├── ParserCompiler.java
│   └── VisitorGenerator.java
└── incremental/
    └── IncrementalParser.java              (simple-first wrapper)
```

Tests in `peglib-core/src/test/java/org/pragmatica/peg/**` (29 test classes, 214 tests).

---

## 1. The 12/20 vs 8/20 corpus situation

After all of Session 2's lexer/parser work, **12/20 Java25 corpus fixtures** parse cleanly (no diagnostics, full CST). The remaining 8 fail with the **same outer pattern**: `trailing input not consumed, expected=end of input, found=public/class`.

The shared OUTER pattern reflects the new "must consume all input" parser invariant added in Session 2. The INNER causes vary per fixture:

| Fixture | Likely inner cause |
|---|---|
| Annotations.java | Annotation usage in body (`@SuppressWarnings("unused")` on parameters/returns) |
| ChainAlignment.java, Lambdas.java, LineWrapping.java, MultilineArguments.java, MultilineParameters.java | Parameterized types in field/method declarations: `Result<String>`, `Function<String, String>` — `<`/`>` ambiguity vs comparison |
| ClassLiterals.java | Triple-slash JavaDoc `///` + parameterized type-use `Class<?>` |
| DeepGenerics.java | Bounded type parameters with wildcards/intersection: `<T extends Comparable<? super T> & Cloneable>` |

**Diagnostic strategy** for the next session: bisect each fixture, identify the smallest input that triggers the failure, fix the responsible grammar/parser rule. Each fix is small and targeted; the overall effort is bounded but spread across multiple sessions.

The **`>>` tokenization issue** (B.6.2 attempted) was bisected but the fix didn't land in this session — `>>` is currently lexed as a single shift operator instead of two `>` tokens, which breaks nested generics. This is a **good first target** for next session.

### What 12/20 clean buys us

- Parser PROVES correct on real Java for the cases it handles
- CST shape is meaningful (BlankLines.java: 1040 nodes, SwitchExpressions.java: 2802 nodes — vs 8-12 nodes/fixture before B.5/B.6)
- Generated parser source is 272KB, compiles in ~550ms
- Round-trip byte-equal works for all 20 fixtures (cst.reconstruct() = input bytes)

---

## 2. Phases not started

### Phase D optimization (D.0.1, D.1.1)
- D.0.1: Windowed re-lex in TokenArray.spliceLex (currently full re-lex)
- D.1.1: CstArray.spliceSubtree + checkpoint-driven partial reparse in IncrementalParser

Infrastructure is in place; just wire up the windowing.

### Phase E.2-5 (formatter, maven plugin, playground)
- peglib-formatter rewrite on flat-array CST
- peglib-maven-plugin update for new emission
- peglib-playground migration to generate-and-compile path

These are 0.5.x module rewrites — significant work; defer until Phase B.6.x lands more fixtures.

### Phase F (bench, polish, ship)
- Bench A/B vs 0.5.1 for parser warm-path
- CHANGELOG + migration guide
- Release pipeline

Don't run until at least 18/20 fixtures parse cleanly.

---

## 3. Outstanding bugs / known limitations

### Deferred (planned for later 0.6.x or 0.7)

1. **Per-rule `%recover` sync sets** — `%recover` directive parses (Phase #5) and start-rule sync overrides emit, but per-rule recovery within nested parsers is a no-op. Spec §3.8 calls for per-rule.
2. **MIXED-rule char-level fallback** — rules with both parser-rule references and char-level constructs emit no CST nodes for the char-level parts.
3. **`ParserOptions` class** is a stub; `Parser.parse(input, maxDiagnostics)` ignores the cap.
4. **Block comment classification through DFA** — works in lexer engine post-pass, but `'/*' (!'*/' .)* '*/'` inside a Choice alternative isn't routed through `compileDelimitedBlock`. LINE_COMMENT classification works.
5. **Per-iteration trivia tokens** — `%whitespace` ZeroOrMore matches the entire whitespace+comments run as ONE token. Inner-iteration token splitting requires lexer driver changes.
6. **Named captures + back-references** — state TBD by #12 task.
7. **JBCT-SEAL-01 lint warnings** on a few v6 files (cosmetic; sealed interfaces with single-variant nesting).
8. **v6 JBCT 0.25.0 plugin: `<skip>true</skip>` pinned** — the JBCT-conformance refactor (May 2026) eliminated all 123 strict-mode lint errors flagged by 0.25.0 (0 errors, 287 cosmetic warnings). The pass also covered the source emitted by `ParserGenerator`/`LexerGenerator`: the generated parser/lexer no longer emit `throw new IllegalArgumentException(...)` for null/range checks or unknown rule kinds — those paths route through the existing recovery branch (synthetic `Error` node + `Diagnostic`). However 6 files (`TokenArray`, `IncrementalParser`, `DfaBuilder`, `ParserCompiler`, `LexerCompiler`, `ParserGenerator`) hit unstable cases in the 0.25.0 formatter where `jbct:format` does not converge: each pass either inserts 4-7 blank lines between `} else` and `{`, or oscillates around a `if ( foo) { return ...;}` single-line shape. Skip remains true until either the formatter is fixed upstream or those files are hand-formatted; the *lint refactor itself is complete and shippable*. To re-verify lint: flip `<skip>false</skip>` in `peglib-core/pom.xml` and run `mvn -pl peglib-core jbct:check` — expect 6 format issues, 0 lint errors. Reference work item: tracking the format bug upstream.
9. **`IncrementalParser` does full reparse on every edit** — correct but unoptimized (O(n) per edit).

### Intentional drops (per spec — NOT returning)

- BASIC/ADVANCED `RecoveryStrategy` split: one always-on panic-mode mechanism replaces it. Use `result.diagnostics().isEmpty()` for fail-fast semantics.
- Inline `{ ... }` action blocks in grammar: replaced by `GVisitor<T>` stub class generated per grammar (Phase E.1). Compile-time rejection with migration message.
- `AstNode` type: dropped entirely. Build domain ASTs via `GVisitor<T>` walking the CST.
- Packrat memoization: not needed under tokens-first design. JIT scalar-replacement handles short-lived parse state.

---

## 4. Important architectural decisions made in Session 2

1. **Parallel-package strategy** worked perfectly — 0.5.x untouched, v6.* additive.
2. **AtomicLong counter** for generated class-name uniquification (cache-by-grammar-text + counter for class names).
3. **Parser uses ParseException internally** for control flow; never thrown to caller (per spec).
4. **Synthetic `_ROOT`** wrapper node always exists in CST — needed for full-consumption check + multi-attempt recovery.
5. **Inline-literal extraction** from PARSER rules + **alias map** for LEXER rules whose body is literal/literal-choice — sidesteps need for full DFA support of `!Reference` and `!CharClass`.
6. **Post-DFA keyword resolution** lifts identifier-shaped tokens to keyword kinds. Standard "lex identifier, then check keyword table" pattern.
7. **Delimited-block KMP DFA** handles `'"""' (!'"""' .)* '"""'` patterns (string literals, block comments) without needing `Not` in DFA.

---

## 5. Files NOT to commit until reviewed

- All v6 packages (`org.pragmatica.peg.*` and tests) — large surface area; want spec-compliance review first
- `peglib-core/src/test/resources/java25.peg` was NOT modified in Session 2
- No 0.5.x source files modified

---

## 6. Quick-reference: where to start next session

1. **Read this file** (you're here).
2. **Check current state**: `mvn -pl peglib-core test -q` should show 1019/1019 green.
3. **Pick a target**:
   - **Best ROI**: Phase B.6.2 — fix `>>` tokenization or other grammar issues to get more corpus fixtures clean
   - **Architectural**: Phase D.1.1 — wire up true incremental parsing
   - **Polish**: Phase E.2 — peglib-formatter migration
   - **Final stretch**: Phase F — bench against 0.5.1
4. **Helper agents**:
   - Use `jbct-coder` for ALL coding (per CLAUDE.md mandate)
   - Use `build-runner` for all `mvn` invocations (keep verbose output out of context)
   - Use `Explore` for read-only investigation

---

# ORIGINAL SESSION-1 HANDOVER (2026-05-09, kept for reference)

**Last updated:** 2026-05-10, immediately after 0.5.1 ship + 0.6.0 spec lock + branch creation.

This handover is the entry point for the next session. It is self-contained: read this, then `docs/ARCHITECTURE-0.6.0.md`, and you can start Phase A.

---

## 0. State at a glance

| | |
|---|---|
| **Latest release** | 0.5.1 (live on Maven Central — `org.pragmatica-lite:peglib:0.5.1` and 4 sibling modules) |
| **Latest tag** | `v0.5.1` at SHA `1898409` (annotated) |
| **`main` HEAD** | `6929c73` — last commit is the 0.6.0 architecture spec |
| **Active branch** | `release-0.6.0` at `2fe3c76` (NOT pushed; pom versions bumped, CHANGELOG entry added, ready for Phase A) |
| **0.6.0 spec** | `docs/ARCHITECTURE-0.6.0.md` (846 lines, 9 locked decisions, 6-week phasing) |
| **Working tree** | clean |
| **Test counts at ship** | peglib-core 805 + 1 skip; full reactor 1028 + 1 skip; all green |

---

## 1. What 0.5.1 shipped (briefly)

Cumulative across the post-Move-B + trivia-rework + StringSpan + Cleanup A-G arcs:

- **Trivia rework:** `triviaPostPass=true` is the new default. Context-independent attribution by post-pass. Long-standing trivia bugs (5 historical + Step 4 era) closed.
- **StringSpan:** new public type `org.pragmatica.peg.source.StringSpan` for lazy substring materialization. CstNode.Terminal/Token internals migrated to `StringSpan textSpan`; `.text(): String` accessor preserved via lazy materialization.
- **Perf:** selfhost (37k LOC) -5% under legacy buffer-driven path (the perf-critical workload is faster). Reference (1900 LOC) +30% over legacy (intrinsic post-pass overhead; bounded; no real workload affected).
- **Lever B for incremental engine:** trivia-context-loss blocker resolved. Fallback-rule-bypass blocker remains separately scoped.

Full post-mortem of every arc is in git history. Notable docs:
- `docs/incremental/THROUGHPUT-ENGINE-MOVE-B.md` §11 — Move B failure post-mortem (lessons about JIT escape analysis vs allocation-rate metrics)
- `docs/incremental/TRIVIA-ADVERSARIAL-FINDINGS.md` — adversarial test corpus
- `CHANGELOG.md` [0.5.1] — release notes

---

## 2. What 0.6.0 is — 30-second read

**Clean-slate redesign of peglib for CST-only, lint+format use cases. Breaking changes acceptable.**

Nine locked decisions from the spec discussion (all confirmed by user):

1. **Drop the interpreter** (`PegEngine`). Generator-only. `PegParser.fromGrammar(g).parse(input)` does generate-compile-cache under the hood.
2. **Two-phase: lex → parse.** PEG grammar surface preserved; backend uses analysis-driven lex-then-parse. Per-rule char-level fallback for edge cases.
3. **Drop runtime actions.** Generate a `Visitor<T>` stub per grammar; users implement selectively for CST → domain transforms.
4. **Drop AST type.** CST is the only tree. Wrapper-collapse becomes user code via Visitor.
5. **Pure flat node array.** CST data lives in `int[]`; views over the array replace records in the data path. ~32 bytes/node vs ~80-200 today.
6. **Trivia as tokens.** Whitespace/comments live in the token array with `kind = WHITESPACE / LINE_COMMENT / BLOCK_COMMENT`. Trivia attribution problem dissolves.
7. **Incremental as a thin caching layer.** Checkpoint boundaries via grammar `%checkpoint` directive; auto-detect default + explicit override.
8. **Error recovery: one always-on mechanism.** Panic-mode synchronization to `%recover` sets. `List<Diagnostic>` always present (empty = success).
9. **The grammar IS the configuration.** `ParserConfig` deleted. One runtime parameter (`maxDiagnostics`).

**Estimated outcomes (per spec):**
- Code: ~40-50% LOC reduction across peglib-core + ancillary modules
- Performance: reference ≤10ms (vs 24.88ms in 0.5.1), selfhost ≤250ms (vs 784.7ms) — parity-with-or-faster-than javac on Java parsing
- Bug surface: 5 historical trivia bugs become impossible by construction

**Full spec:** `docs/ARCHITECTURE-0.6.0.md` (846 lines). Sections most useful for implementers: §3 (decisions in detail), §4 (module structure), §5 (concrete API signatures), §7 (phasing).

---

## 3. Where to start: Phase A

Per spec §7, Phase A is the lexer foundation. ~1 week. Critical-path: every subsequent phase depends on TokenArray being correct.

### 3.1 Phase A scope

**In scope:**
- Augment `Grammar` IR with rule classification (lexer-rule vs parser-rule)
- Implement DFA construction from lexer-rules
- Implement `TokenArray` data structure
- Generate `GLexer.java` per grammar
- Lex Java25 corpus into TokenArray byte-equal to a hand-written reference

**Not in scope (Phase A):**
- Anything in the parser path (Phase B)
- The user-facing API (Phase C)
- Incremental (Phase D)
- Visitor stubs (Phase E)

### 3.2 First concrete actions

1. **Read the spec sections that matter for Phase A:**
   - §3.2 (lex-then-parse design + classification rules)
   - §3.6 (trivia-as-tokens — what the lexer must emit)
   - §5 (concrete TokenArray API signatures)
   - §7 (phasing; understand the gate condition)

2. **Inspect existing related code in peglib-core:**
   - `org.pragmatica.peg.generator.ChoiceDispatchAnalyzer` — existing FIRST-set analyzer; precedent for grammar-level analysis
   - `org.pragmatica.peg.generator.PackratAnalyzer` — another existing analyzer
   - The DFA fast-path code in `ParserGenerator` (added in 0.5.0; specifically the `tokenFastPath` flag emission) — small DFA precedent for token-shaped rules
   - `Grammar` IR in `org.pragmatica.peg.grammar.*`

3. **Design the rule classifier.** Walk every rule's expression tree; classify per spec §3.2 criteria. Output a `Map<String, RuleKind>` where `RuleKind = LEXER | PARSER | MIXED`. MIXED rules emit a compile-time warning.

4. **Design the DFA construction.** Take all LEXER-classified rules + their expressions; build a single DFA that recognizes any of them, with longest-match + first-match-wins for tie-breaking. Standard NFA → DFA construction (Thompson + subset). Output: a state-transition table representable as `int[][] transitions` plus `int[] acceptStates`.

5. **Design the TokenArray.** Per spec §5, packed `int[] starts`, `int[] ends`, `byte[] kinds`. Add a `kindNameTable: String[]` for diagnostics. Implement `nextNonTrivia(int from)`, `kindAt`, `startAt`, `endAt`, `textAt`, `isTrivia`.

6. **Generate `GLexer.java`.** Emit a class with the DFA as static int[] tables, a `lex(String input)` method that walks the input, applies DFA transitions, emits tokens into a TokenArray. Self-contained per the standalone-parser invariant.

7. **Phase A gate validation:**
   - Pick a representative Java25 corpus fixture (say `peglib-core/src/test/resources/perf-corpus/Imports.java` — small, comment-heavy)
   - Lex it via the new GLexer
   - Manually verify the token sequence matches the input byte-by-byte under round-trip (`for each token, append input.substring(start, end) → equals input`)
   - Lex all 22 corpus fixtures; same round-trip check passes

### 3.3 Don't worry about (Phase A)

- Performance tuning the lexer. DFA tables are already cache-friendly; make it correct first
- The generator-and-compile-cache pipeline (Phase C)
- How `PegParser.fromGrammar()` will look (Phase C)
- AST removal, action removal (separate cleanups, Phase F)
- Migrating existing tests (the 0.5.x test suite stays intact during 0.6.0 dev; new tests are written for the new code path; we don't delete the old code paths until late in the cycle)

### 3.4 Suggested approach: parallel branch

The 0.6.0 implementation is a clean-slate rebuild that doesn't need to live in `peglib-core` at first. Suggestion:

- Create a NEW package `org.pragmatica.peg.*` (or similar) inside `peglib-core` for the 0.6.0 code
- Existing 0.5.x packages (`org.pragmatica.peg.parser.*`, `org.pragmatica.peg.action.*`, etc.) stay UNTOUCHED until late in the cycle
- This means tests of both old and new can coexist; bench can compare directly; rollback is trivial if something goes wrong
- Late-cycle (Phase F): delete old packages once 0.6.0 is fully validated

This is the same pattern that worked for the trivia-rework arc: dormant infrastructure → wire-in → validate → cleanup. Don't break working code while building new code.

---

## 4. Critical lessons banked across 0.5.x arcs (do not re-learn)

These are session-tested findings that the 0.6.0 implementer should internalize before writing code.

### 4.1 Allocation rate is NOT a perf target

**Move B post-mortem (HANDOVER history):** replacing per-call `CstParseResult` allocations with a singleton mutator regressed wallclock 11% while alloc-rate dropped 13%. Modern JIT escape analysis already scalar-replaces short-lived per-call records; replacing them with heap-bound state DEFEATS that optimization.

**Apply for 0.6.0:** target wallclock, not alloc-rate. Bench A/B every change. The flat-array CST (Idea 5) is correct because it eliminates allocations the JIT CANNOT scalar-replace (the trees-of-records survive past method scopes).

### 4.2 Successful pattern vs failed pattern

**Empirical pattern from 0.5.x optimization arcs:**

| Pattern | Result |
|---|---|
| Replace `List.copyOf` (varargs / array copy) with primitive int snapshot | WIN |
| Replace per-call `String.valueOf(c)` with interned ASCII pool | WIN |
| Replace bulk substring with StringSpan view | WIN (selfhost) |
| Replace `String.contains` quadratic scan with `LinkedHashSet` dedup | RESET (call-overhead-dominated) |
| Replace `HashMap<Long,_>` with custom open-addressed long-keyed map | RESET (JDK HashMap is hot-path-faster) |
| Provide `HashMap` initial-capacity hint | RESET (over-sizing hurts cache locality) |
| Convert record → mutable class with cache field | RESET (records are EA-friendly; mutable classes aren't) |
| Defer record allocation via primitive int tracking | RESET when the record was already JIT-eliminable |

**Apply for 0.6.0:** when in doubt about whether an optimization will work, look at whether it eliminates allocations the JIT CANNOT elide. Bulk array copies, per-call fresh objects, large records that escape — these are real targets. Records consumed immediately within method scope, JDK collection internals, single-cache-field mutable classes — these the JIT already handles.

### 4.3 Bench gate every change

**Cleanup G arc lesson:** even with sound theoretical reasoning, a "this should win" hypothesis can be wrong. Cleanup G.1 (whitespace prefilter) and G.2 (alloc tightening) both passed code review and tests but failed bench gates with 0% / 0% improvement. The bench is the only honest signal.

**Apply for 0.6.0:** every commit that claims a perf improvement is bench-gated. Ship the win or revert; never ship "neutral" change with implementation cost. Phase gates in the spec follow this pattern (each phase has a gate condition).

### 4.4 Correctness tests are NOT perf gates

**Step 4 commit 6 → 7 lesson:** RoundTripTest passed 22/22 under flag-ON before bench revealed the post-pass had O(n²) wallclock complexity. Correctness tests checked output validity but had no time bound. The bench is the only honest signal for perf.

**Apply for 0.6.0:** every default-changing or hot-path change runs bench A/B before commit. Add wallclock assertions to perf-sensitive tests if needed (with generous bounds; goal is "this took milliseconds, not seconds").

### 4.5 Static analysis can be too conservative

**Lever B retry lesson:** `SafePivotAnalyzer.safePivotRules(Grammar)` uses a strict "unambiguous literal prefix" criterion. For Java25 grammar (where most rules start with character classes or rule references), the analyzer marks ~80% of rules unsafe. Walking up to find a safe ancestor lands at root → forces full reparse. Median 5ms → 21.9ms regression.

**Apply for 0.6.0:** when designing the `%checkpoint` auto-detector (per Idea 7), test against the Java25 grammar EARLY. If the criterion is too restrictive, adjust before committing the implementation. Validate with `IncrementalSessionBench` not just parity tests.

### 4.6 The post-pass approach was a partial answer to the wrong question

**Trivia rework reflection:** the entire 0.5.1 trivia rework arc (Step 4 commits 1-7 + Cleanup A-G) was solving a problem that simply doesn't exist under tokens-first design. Trivia tokens are at known positions in the token array; the attribution problem dissolves. ~3500 LOC of 0.5.1 code becomes deletable.

**Apply for 0.6.0:** when designing the lexer (Phase A), make trivia-as-tokens the foundation. Don't accidentally re-introduce buffer state for trivia attribution.

---

## 5. Things already tried and decided — don't relitigate

### 5.1 Singleton mutable parse-state (Move B)

Attempted across 5 commits in 0.5.0 cycle. Definitively reverted. Hard-coded into HANDOVER history as the canonical "JIT escape analysis already handles this" example. Don't bring it back as a 0.6.0 optimization.

### 5.2 SafePivotAnalyzer literal-prefix gate

Attempted in this session. Catastrophic regression on Java25. Don't wire SafePivotAnalyzer into 0.6.0 incremental as-is. Either redesign the criterion (tracking grammar first-sets more cleverly) or use the simpler `%checkpoint` directive approach (the 0.6.0 plan).

### 5.3 Pattern-matching ergonomics on records

User explicitly preferred performance over pattern-matching ergonomics. CstNode views (Idea 5 option A) are the path. Don't try to keep records "for ergonomics" — query-style API is the design choice.

### 5.4 BASIC vs ADVANCED error recovery

Collapsed to one mechanism (always-on, panic-mode). Don't reintroduce the split.

### 5.5 Action support

Dropped (Idea 3). Visitor pattern (option C.2 from spec discussion) replaces it. Don't reintroduce inline `{ ... }` action blocks; let the grammar parser reject them with a migration message.

### 5.6 AST type

Dropped (Idea 4). User builds their own AST via Visitor. Don't add an "AST" output mode to the 0.6.0 generator.

---

## 6. Open questions for the implementer

Things that COULD be resolved differently during implementation but are bounded:

### 6.1 Visitor stub: pre-order or post-order?

Spec §3.3 doesn't pin which traversal order the default `visitChildren()` uses. ANTLR is post-order by default. Roslyn is pre-order with explicit `Visit` calls. For most lints, doesn't matter. Recommendation: pre-order (visitor sees node before its children), since linters often want to terminate early on certain nodes.

### 6.2 CharSequence vs String for token text

Spec §5 has `CharSequence textAt(int i)`. Most consumers will call `.toString()`. The CharSequence return type allows lazy materialization but adds API friction. Decide based on benchmarks; if 95% of consumers `.toString()` immediately, just return `String`.

### 6.3 Lexer DFA representation: arrays vs switch

DFAs can be represented as `int[][] transitions` (data-driven) or as a giant `switch` statement in generated code (code-driven). For Java25 grammar (~50 token kinds, ~200 DFA states), data-driven is more compact and easier to verify. For tiny grammars (~5 token kinds), switch may JIT better. Recommendation: data-driven default; profile and switch if it's a hot frame.

### 6.4 ParseResult immutability

Spec defines `ParseResult` as a final class with public final fields. Could be a record. Records work for value-style access but lose extensibility. For 0.6.0, records are fine — the API is locked.

### 6.5 Generator output: one file or three?

Spec §3.2 implies three files (GLexer, GParser, GVisitor). Could combine into one for the standalone-parser invariant. Recommendation: three files for IDE friendliness, all in same package; the maven-plugin picks them all up; users importing the parser get a clean package.

### 6.6 0.5.x deprecation timing

Spec §8 lists the breaking changes. Question: do we ship a `0.5.x → 0.6.0` migration tool? Recommendation: skip; the migration is mechanical (delete code, replace API calls). Users don't need automation; they need a clear migration guide. Write `docs/MIGRATION-0.5-TO-0.6.md` during Phase F.

---

## 7. Bench targets for 0.6.0

Reference machine: same Apple Silicon used for 0.5.x bench session. Numbers from spec §10:

| Workload | 0.5.1 | 0.6.0 target | Stretch |
|---|---:|---:|---:|
| Reference parse (1900 LOC) | 24.88 ms / 77 MB | **≤ 10 ms / ≤ 30 MB** | ≤ 6 ms |
| Selfhost parse (37k LOC) | 784.7 ms / 1881 MB | **≤ 250 ms / ≤ 600 MB** | ≤ 150 ms |
| Incremental edit median (Regime B) | 5.0 ms | **≤ 3 ms** | ≤ 1 ms |
| First-call (cold compile) | n/a | **≤ 600 ms** (one-time) | — |

For comparison: measured javac parse-only for the 1900-LOC reference is ≈2.2 ms; peglib's warm reference parse (≈2.68 ms as of 0.6.2) is ≈1.2× javac while emitting strictly more output.

---

## 8. Repository structure pointers

Where things live (post-0.5.1):

```
peglib/
├── pom.xml                          (parent; version 0.6.0 on release-0.6.0 branch)
├── README.md
├── CHANGELOG.md                     ([0.6.0] entry exists; sections empty)
├── CLAUDE.md                        (project mandate; jbct-coder usage rule)
├── docs/
│   ├── ARCHITECTURE-0.6.0.md        ★ The spec. Read this first.
│   ├── HANDOVER.md                  (this file)
│   ├── BENCHMARKING.md
│   ├── ERROR_RECOVERY.md
│   ├── GRAMMAR-DSL.md
│   ├── PARTIAL-PARSE.md
│   ├── PERF-FLAGS.md                (mostly relevant for 0.5.x; will become stale in 0.6.0)
│   ├── PLAYGROUND.md
│   ├── PRETTY-PRINTING.md
│   ├── TRIVIA-ATTRIBUTION.md        (will become historical in 0.6.0)
│   ├── archive/                     (historical specs)
│   ├── bench-results/               (bench history)
│   └── incremental/
│       ├── ARCHITECTURE-0.5.0.md    (Lever D stable IDs; predecessor)
│       ├── PHASE-1-RESULTS.md
│       ├── THROUGHPUT-ENGINE-MOVE-B.md  (Move B post-mortem; canonical lessons)
│       ├── THROUGHPUT-ENGINE-TIER1.md
│       └── TRIVIA-ADVERSARIAL-FINDINGS.md
├── peglib-core/                     (core parser library; 0.5.1 implementation)
├── peglib-incremental/              (incremental engine; 0.5.x implementation)
├── peglib-formatter/                (Wadler-Lindig pretty printer)
├── peglib-maven-plugin/             (build-time codegen)
└── peglib-playground/               (REPL + HTTP UI)
```

For 0.6.0 work, the proposal in §3.4 is to add a NEW package `org.pragmatica.peg.*` inside `peglib-core` rather than rewriting in place. This lets old and new coexist during the cycle.

---

## 9. Tooling

- **Project mandate (CLAUDE.md):** use `jbct-coder` agent for all coding; `build-runner` for `mvn` invocations
- **Build:** `mvn install` (full reactor; ~35-40s); `mvn test -pl peglib-core` for fast iteration
- **Bench (throughput):** `mvn -pl peglib-core -am -Pbench -DskipTests package` then `java -jar peglib-core/target/benchmarks.jar Java25ParseBenchmark.parse -p variant=... -p fixture=reference,selfhost ...`
- **Bench (incremental):** `mvn -pl peglib-incremental -am -Pbench -DskipTests package` then `java -cp ... org.pragmatica.peg.incremental.bench.IncrementalSessionBench` (note: this benchmark uses a custom main, not JMH directly)
- **Profile:** async-profiler at `/opt/homebrew/lib/libasyncProfiler.dylib`. JMH integration via `-prof async:libPath=...;event=cpu` or `event=alloc`.
- **Maven Central deploy:** `mvn clean deploy -P release -DperformRelease=true` from main on a tag commit. Takes ~4-5 min. GPG signing via gpg-agent.
- **GH workflow:** `gh pr create` / `gh pr merge --admin --merge` for release PRs
- **CI:** GitHub Actions; `build` job runs full `mvn install`; checks must pass before merge

---

## 10. Session-end checklist for the next session

When the next session ends, the next-next session needs the same kind of handover. Before ending:

- [ ] Update this `docs/HANDOVER.md` with current state + what's next
- [ ] Update `CHANGELOG.md` `[0.6.0]` section with entries for what shipped this session
- [ ] If a phase gate was passed: note the gate-met evidence in the phase-completion log
- [ ] If a decision was made that contradicts the spec: update `docs/ARCHITECTURE-0.6.0.md` with the new decision + rationale
- [ ] Verify working tree is clean OR has a clear in-progress note in HANDOVER
- [ ] Push to `origin/release-0.6.0` (or wherever active work lives)

---

## 11. Quick-reference: the 9 decisions from the 0.6.0 spec

For when you don't want to re-read the full 846-line spec:

1. Drop interpreter; generator-only with generate-and-compile (cached per grammar)
2. Two-phase lex → parse; PEG surface preserved; analysis-driven backend
3. Drop runtime actions; emit `Visitor<T>` stub per grammar
4. Drop AST type; CST is the only tree
5. Pure flat node array; views over int[]; no records in CST data path
6. Trivia as tokens (kind=WHITESPACE/COMMENT in token array); positional + helpers
7. Incremental as thin caching layer; auto-detect + `%checkpoint` directive
8. Error recovery: one always-on mechanism; panic-mode; List<Diagnostic> always present
9. The grammar IS the configuration; ParserConfig deleted; one runtime parameter (maxDiagnostics)

---

**Welcome to 0.6.0. Read the spec, then start Phase A. Good luck.**
