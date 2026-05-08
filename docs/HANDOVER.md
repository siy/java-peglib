# peglib — handover (post-0.4.0)

**Status:** `main` at `v0.4.0`. 4 tagged releases shipped in this arc (v0.3.5, v0.3.6, v0.4.0, plus retroactive Maven Central publish of v0.2.2). 897 tests passing, 0 skipped. Tree clean.

This document supersedes the prior handover (now historical) — it captures the complete post-0.4.0 state including the trivia round-trip resolution, the API consolidation pass, the v2.5 NO-GO finding, and the deferred lever 1 correctness puzzle.

If you want to do anything with this repo, read §1–§4 first; everything else is reference.

---

## 1. What peglib is

PEG (Parsing Expression Grammar) parser library for Java 25. Inspired by cpp-peglib. Five Maven modules; five independent artifacts from a single parent pom.

- **`peglib-core`** — `org.pragmatica-lite:peglib`. Grammar IR, lexer, parser, engine (`PegEngine`), source generator (`ParserGenerator`), analyzer, diagnostic machinery, runtime actions. Everything downstream depends on this.
- **`peglib-incremental`** — `org.pragmatica-lite:peglib-incremental`. Cursor-anchored incremental reparser. Depends on core's `parseRuleAt` API.
- **`peglib-formatter`** — `org.pragmatica-lite:peglib-formatter`. Wadler-Lindig pretty-printer framework. Depends on core's CstNode.
- **`peglib-maven-plugin`** — `org.pragmatica-lite:peglib-maven-plugin`. Mojo wrappers: `generate` / `lint` / `check`.
- **`peglib-playground`** — `org.pragmatica-lite:peglib-playground`. CLI REPL + embedded-HTTP web UI. Uber-jar via `maven-shade-plugin`.

## 2. Maven Central status

Only **v0.2.1** and **v0.2.2** are on Maven Central as of this handover. The arc from v0.2.3 → v0.4.0 (12 versions) has tagged releases on GitHub but no Maven Central publish.

The release profile is set up correctly (parent `pom.xml`, activated by `-DperformRelease=true`): GPG signing, `central-publishing-maven-plugin` 0.6.0, `autoPublish=true`, `waitUntil=published`. v0.2.2 was published successfully via:

```bash
git checkout v0.2.2
mvn clean deploy -P release -DperformRelease=true
```

The build takes ~7 minutes (most of which is the `waitUntil=published` blocking poll while Sonatype Central propagates).

If the IDE-plugin or any downstream wants newer versions on Central, the same workflow applies for each tag. **Multi-module releases (v0.3.x+) will publish all 5 module artifacts in one shot** — the parent pom orchestrates it.

## 3. Quick start

```bash
# Build everything
mvn install

# Test everything (897 expected, 0 skipped)
mvn test

# Run JMH benchmarks (bench profile)
mvn -Pbench -DskipTests package
java -jar peglib-core/target/benchmarks.jar    # or peglib-incremental/target/...

# Run grammar playground
cd peglib-playground && mvn -DskipTests package
java -jar target/peglib-playground-*-uber.jar 8080
# then open http://localhost:8080

# Run grammar analyzer CLI
java -cp peglib-core/target/peglib-0.4.0.jar \
     org.pragmatica.peg.analyzer.AnalyzerMain path/to/grammar.peg

# Maven Central publish (any tag)
git checkout vX.Y.Z
mvn clean deploy -P release -DperformRelease=true
```

## 4. Test counts at a glance

```
peglib-core         699 passing
peglib-incremental  100 passing
peglib-formatter     66 passing
peglib-maven-plugin   5 passing
peglib-playground    27 passing
────────────────────────
aggregate           897 passing, 0 skipped, 0 failures
```

`RoundTripTest` is enabled and passing 22/22 on the corpus fixtures (re-enabled in 0.3.5 after 5 trivia bugs were resolved).

## 5. Recent releases — what shipped, why

### v0.3.5 (2026-05-01) — trivia round-trip + interpreter `%recover`

The release that earned the test count its current shape. Five distinct trivia-attribution bugs diagnosed empirically and fixed:

- **Bug A** — `ParsingContext.savePending/restorePending` returned size-only snapshots. Items consumed inside backtracked branches were permanently lost. Now uses full `List<Trivia>` snapshots.
- **Bug B** — Cache-hit path didn't rebuild leading trivia (asymmetric vs cache-miss). Now drains pending + re-skips whitespace + reattaches.
- **Bug C** — Generator cached the wrapped-with-leading body. `attachLeadingTrivia`'s short-circuit then preserved stale leading on cache hits, duplicating trivia across nested wrappers. Fix: cache an empty-leading wrap, return the leading-applied wrap. Interpreter was already correct (caches the body).
- **Bug C'** — Trivia consumed by the body's last inter-element `skipWhitespace` (e.g. before an empty `ZoM`/`Optional`) ended up in pending with no claimant. Now attached to the last child's `trailingTrivia` at rule exit. Pos is *not* rewound — predicate combinators rely on it.
- **Bug C''** — Generator's emitted `Sequence` didn't roll back outer `children` on element failure. Symptom: trailing comma appeared as a child of both the inner ZoM-NT and the outer Sequence. Fix: snapshot `children` at Sequence start, restore on element failure.

Plus the `%recover` interpreter wiring fix: override was popped in `finally` before `parseWithRecovery` consulted it. Captured into a per-context field with deepest-wins semantics.

`large/FactoryClassGenerator.java.txt` baseline regenerated (the Bug C'' fix removes a duplicate trailing comma in enum-constant lists). Other 21 corpus baselines unchanged.

### v0.3.6 (2026-05-01) — generator-side `%recover`

Generator now mirrors the interpreter `%recover` wiring. Generated parsers honor per-rule overrides end-to-end.

A naive **lever 1** incremental-perf fix (edit-anchored pivot in `findBoundaryCandidate`) was attempted in this cycle and reverted — see §6.2.

### v0.4.0 (2026-05-03) — API consolidation + JBCT polish (BREAKING)

16 commits over 8 phases:

- **Phase 1** — `Grammar.of(...)` removed; use `Grammar.grammar(rules, ...)` returning `Result<Grammar>`. Validation runs in the factory.
- **Phase 2** — Factory rename sweep: `of()`/`create()`/`at()` → `typeName()` across 7 packages. `assertTrue(result.isSuccess()) + unwrap()` → `result.onFailureDo(fail).onSuccessDo(...)` JBCT idiom across hundreds of test sites.
- **Phase 3** — `SessionImpl` → `IncrementalSession` record (drops `Impl` anti-pattern; `Session` interface unchanged).
- **Phase 4** — Maven Mojos refactored to `Result` pipelines with JBCT-boundary Javadoc.
- **Phase 5** — `Formatter` mutable builder → `FormatterConfig` immutable record.
- **Phase 6** — `Result.lift` boundaries: `PegEngine.createWithoutActions` returns `Result<PegEngine>`; action dispatch wrapped in `Result.lift`; playground `parseRequestBody` returns `Result<ParseRequest>`.
- **Phase 7** — `Option<T>` for incremental nullables: `tryIncrementalReparse`, `findBoundaryCandidate`. Zero `(CstNode) null` casts remain.
- **Phase 8** — JBCT-boundary Javadoc on CLI mains (`@Contract` is not a project annotation; documented in Javadoc instead).

Migration guide is in `CHANGELOG.md` § [0.4.0].

### v0.2.2 (2026-05-03 retroactive Maven Central publish)

Tagged 2026-04-21 originally. Published to Central on demand because a downstream project depends on it. Released artifact at https://repo1.maven.org/maven2/org/pragmatica-lite/peglib/0.2.2/

## 6. Outstanding work — ranked by value-per-effort

### 6.1 Scheduled remote agent (already armed)

Trigger ID `trig_01HhXqsGeHfRoWNNnqM7TLod`, fires **2026-05-08T14:00:00Z**. Runs JMH + async-profiler against the post-0.3.6 baseline, captures flame graphs, posts results as a comment on the open release-0.3.6 PR or as a new issue. View at https://claude.ai/code/routines/trig_01HhXqsGeHfRoWNNnqM7TLod

The agent will report which Tier (per `docs/incremental/V2.5-SPIKE.md` § "Alternative levers") the next bottleneck lives in.

**Important context:** the agent assumes 0.3.6 contains the lever 1 fix, which it doesn't. The agent may report numbers worse than the spike's projected 5-15ms (because lever 1 is still not landed). The agent's logic still works as a baseline-capture — just interpret the results in light of "lever 1 not yet shipped."

### 6.2 Lever 1 — incremental perf (DEFERRED — deeper than the spike claimed)

**Status:** the spike doc's "zero correctness risk" claim is **retracted**. See `docs/incremental/V2.5-SPIKE.md` "Addendum (post-0.4.0)" for the retraction. Two failed attempts on this lever:

| Attempt | Approach | Failures | Stash |
|---|---|---|---|
| 0.3.6 cycle | `findBoundaryCandidate` start = `index.smallestContaining(editStart)` | 12/100 parity | reverted, no stash |
| post-0.4.0 (this handover) | `findBoundaryCandidate` = new `NodeIndex.smallestEnclosing(editStart, editEnd)` with edit-aware boundary semantics | **31/100 parity** + 1 fallback test | `stash@{0}: lever-1-attempt-incorrect` |

**Two distinct root causes diagnosed (both are design-level, not implementation bugs):**

1. **Fallback-rule bypass.** `tryIncrementalReparse` only checks the chosen *pivot* against `factory.fallbackRules()`. It does NOT check ancestors. The OLD walk-up algorithm accidentally avoided this because the cursor's spine always passed through the fallback ancestor first. Any descent-from-root strategy can pick a pivot INSIDE a fallback rule, bypassing the safety check entirely. Exposed by `BackReferenceFallbackTest.edit_triggers_full_reparse`. **Latent bug** — predates lever-1 work and would surface for any `moveCursor`-based usage too.

2. **`reparseAt` length-parity ≠ structural-parity.** The acceptance check `partial.node.span.end == expectedEnd` only proves the parsed *length* is right. The internal CST structure can still differ from full reparse due to trivia attribution (Bug C' from 0.3.5 was about exactly this) and other context-sensitive parsing decisions. Small pivots are particularly vulnerable because they don't have surrounding-sibling context that full reparse provides. The OLD algorithm's parity-correctness in `IncrementalParityTest` is an **artifact of the test harness**: cursor is initialized at offset 0 and never moves, so walk-up always reaches a near-root pivot for interior edits, making `reparseAt` essentially a full reparse on a large subtree.

**Why the spike missed this:** the probe measured timing in Regime A and Regime B, and recommended Regime B's algorithm based on the 12.7× speedup. **Parity was never asserted in either regime.** The spike's "zero correctness risk" claim was an architectural assumption, not an empirical finding.

**What a real fix needs (estimated 5–10 days, mostly correctness analysis, not coding):**

- A "safe-pivot" concept — a per-grammar marker on rules whose `parseRuleAt` output provably matches full-reparse for any input. Likely an opt-in attribute on rule definitions (e.g., a `%incremental` directive) that the IDE plugin's grammar would explicitly mark on Block, Stmt, Args, etc.
- Ancestor-aware fallback check — walk up from the chosen pivot through `parents` and reject if any ancestor is in `fallbackRules`.
- Strengthened acceptance check — currently length-only; needs structural validation. Options: (a) compare reparsed subtree against an oracle reparse (defeats the purpose), (b) restrict pivot selection to safe rules and trust them.
- Trivia-context carryover into `parseRuleAt` so reparse-in-isolation can attribute leading trivia like full reparse would.
- **Wider parity coverage**: `IncrementalParityTest` must run with `moveCursor` interleaved before any incremental perf work is validated. Without this, any algorithm that picks small pivots looks correct in tests but is broken in production.

**Recommended posture:** wait for the 2026-05-08 perf agent's flame graph (§6.1) before committing further effort. The bottleneck may not be where the spike said it was. If it confirms pivot overshoot, prioritize the wider parity coverage first — that's the prerequisite for any future attempt.

**Forensic record:** `git stash show -p stash@{0}` shows the post-0.4.0 attempt — `NodeIndex.smallestEnclosing` + JBCT formatter pass on 8 ancillary files. The `smallestEnclosing` predicate itself is correct for boundary disambiguation; the failure is in the surrounding architecture, not the predicate.

### 6.3 Maven Central backfill

The arc from v0.2.3 → v0.4.0 is unpublished. If downstream consumers want any of those versions, publish them on demand — the workflow is identical to what was done for v0.2.2. Don't blanket-publish; wait for explicit demand to avoid versioning churn.

### 6.4 Other levers (per spike doc) — **with corrections**

If the 2026-05-08 perf agent's flame graph identifies a Tier-1/2/3 hot spot, the spike's ranking applies, but **the spike's Tier-1 claim is wrong** for the IDE plugin's path:

- **Tier 1**: `inlineLocations`, `markResetChildren`, `selectivePackrat` from the 0.2.2 spec.
  - **Correction (audited 2026-05-03):** these are **generator-only flags**. The interpreter (`PegEngine`) does NOT honor `inlineLocations` or `markResetChildren` — grep confirms zero references in `PegEngine.java`. `selectivePackrat` is partially honored by `PegEngine` for LR-rule validation only; the cache-skip optimization itself is generator-only.
  - **Consequence:** flipping these flags speeds up users of `PegParser.generateParser(...)` only. The IDE plugin uses `IncrementalParser.create(...)` → `PegParser.fromGrammar(...)` → `PegEngine` (interpreter), so flag-flips do nothing for it.
  - **What CAN help:** port the optimizations themselves (`SourceLocation` allocation elision, mark-and-trim child restore) from `ParserGenerator` emission templates into `PegEngine` runtime methods. The generator path proves they work; porting is mechanical but bounded (~3–5 days, parity-validated by the existing test suite since the interpreter is what the parity test exercises).
- **Tier 2**: Subtree reuse on stable spans (the actually-clever incremental fix), streaming/window-bounded parsing, rule-level failure caching. Same `parseRuleAt` correctness puzzle as lever 1 — needs the safe-pivot infrastructure first.
- **Tier 3**: ASCII-whitespace fast path, allocation reduction, char[] vs String. Pure interpreter-level work, no incremental-correctness implications.

These are conjectural until the agent's flame graph lands. **Do not act on Tier-1 as the spike doc described it** — the flags do not affect runtime parser behavior.

## 7. Conventions you'll need

### 7.1 Subagent usage (project mandate)

Per `CLAUDE.md` (project-level): **only `jbct-coder` for substantive coding tasks**. `build-runner` for `mvn` invocations to keep verbose output out of the main thread context. `Explore` and `general-purpose` for read-only investigation. Never use other coder agents.

`jbct-coder` agents sometimes refuse the assignment citing "I'm not jbct-coder" in their first message. Send a follow-up reminding them they ARE jbct-coder; the `subagent_type="jbct-coder"` at invocation IS the delegation.

### 7.2 JBCT formatter CI tax

Every commit must pass `mvn jbct:check` in CI. Run `mvn -pl peglib-core jbct:format` (or `mvn jbct:format` from any module that has the plugin) before pushing to avoid the "format failed in CI" round-trip. Most 0.3.x and 0.4.0 commits had at least one `chore: jbct:format` follow-up commit because someone (usually me) skipped this. Don't.

The `jbct-maven-plugin` pins ASM 9.9 explicitly because `maven-plugin-plugin:3.15.1`'s bundled 9.7.1 can't read Java 25 class files. Revert once `3.15.2+` ships.

### 7.3 Release workflow (15-step, established)

Used for every tag in this arc:

1. `git checkout -b release-X.Y.Z`
2. Bump 6 poms + README dependency snippet
3. Add `## [X.Y.Z] - YYYY-MM-DD` CHANGELOG entry
4. `git commit -m "chore: prepare release X.Y.Z"` — first commit on the release branch
5. Implementation phases (delegate to `jbct-coder`; `mvn test` after each)
6. Final `docs:` commit if CHANGELOG needs follow-up
7. `git push -u origin release-X.Y.Z`
8. `gh pr create --base main --head release-X.Y.Z --title "..." --body "..."`
9. Wait for `build` CI check. CodeRabbit runs in parallel — can be skipped if it stalls.
10. `gh pr merge <N> --merge`
11. `git checkout main && git pull`
12. `git tag -a vX.Y.Z -m "Release X.Y.Z"`
13. `git push origin vX.Y.Z`
14. `gh release create vX.Y.Z --title "..." --notes "..."` — narrative notes (not a CHANGELOG copy)
15. `git branch -d release-X.Y.Z && git push origin --delete release-X.Y.Z`

### 7.4 Baseline-shift convention

When a fix legitimately changes CST output, regenerate baselines as a **separate commit** with explicit "baseline-shift" CHANGELOG callout. v0.3.5 did this for `large/FactoryClassGenerator.java.txt`. Don't conflate baseline regen with the fix that drove it.

Two baseline directories:
- `peglib-core/src/test/resources/perf-corpus-baseline/` — generator-side hashes
- `peglib-core/src/test/resources/perf-corpus-interpreter-baseline/` — interpreter-side hashes

Regen via:
- `mvn -Dperf.regen=1 -Dtest=BaselineGeneratorRunner test` — generator
- `java -cp ... org.pragmatica.peg.perf.InterpreterBaselineGenerator` — interpreter

## 8. Key files map (current)

### Core engine
- `peglib-core/src/main/java/org/pragmatica/peg/PegParser.java` — public entry
- `peglib-core/src/main/java/org/pragmatica/peg/parser/Parser.java` — interface
- `peglib-core/src/main/java/org/pragmatica/peg/parser/PegEngine.java` — interpreter (~2.3k LOC after Bug A-C'' work)
- `peglib-core/src/main/java/org/pragmatica/peg/parser/ParsingContext.java` — parse state + packrat cache

### Generator
- `peglib-core/src/main/java/org/pragmatica/peg/generator/ParserGenerator.java` — emission templates (~4.4k LOC after generator-side trivia work)

### Incremental
- `peglib-incremental/src/main/java/org/pragmatica/peg/incremental/internal/IncrementalSession.java` — record (was `SessionImpl` pre-0.4.0)
- `peglib-incremental/src/main/java/org/pragmatica/peg/incremental/internal/NodeIndex.java` — **read this before touching lever 1**; the `contains` semantics are the trap
- `peglib-incremental/src/main/java/org/pragmatica/peg/incremental/internal/TreeSplicer.java`

### Trivia + recovery
- `docs/TRIVIA-ATTRIBUTION.md` — full attribution model + Bug A-C'' resolutions
- `peglib-core/src/test/java/org/pragmatica/peg/perf/RoundTripTest.java` — 22/22 byte-equal corpus oracle (re-enabled in 0.3.5)
- `peglib-core/src/test/java/org/pragmatica/peg/error/RecoverDirectiveProofTest.java` — interpreter `%recover` proof
- `peglib-core/src/test/java/org/pragmatica/peg/error/GeneratedParserRecoverDirectiveTest.java` — generator `%recover` proof

### Bench
- `peglib-core/src/jmh/java/org/pragmatica/peg/bench/Java25ParseBenchmark.java`
- `peglib-incremental/src/jmh/java/org/pragmatica/peg/incremental/bench/IncrementalBenchmark.java`
- `docs/incremental/V2.5-SPIKE.md` — **read this before any incremental perf work**

## 9. Things that are easy to get wrong

- **`mvn jbct:format` before every push.** CI rejects unformatted commits. Skipping costs you a round-trip.
- **Don't merge release PRs without confirming `build` CI passes.** CodeRabbit can stall; skip it if needed. Merge only when `build` is green.
- **Baseline shifts need a separate commit.** Don't bundle into the fix that drove them.
- **`NodeIndex.contains` is half-inclusive on BOTH ends** (line 149 of NodeIndex.java) — intentional for cursor APIs (an editor cursor at a boundary feels inside the adjacent node). Six callers rely on this. **Do NOT change `contains` globally** to "fix" pivot selection. The post-0.4.0 lever-1 attempt added a separate `smallestEnclosing(start, end)` for edit anchoring; it is correctness-incomplete (see §6.2) but the *predicate* design — half-open right end on insertion, inclusive on modification — is sound and reusable.
- **`tryIncrementalReparse` only checks the chosen pivot for `fallbackRules` membership, NOT its ancestors.** Latent bug: any descent-from-root pivot strategy can pick a node inside a fallback rule and bypass the safety check. The OLD walk-up-from-cursor algorithm hides this because cursor's spine always passes through the fallback ancestor first. Address before any lever-1 retry.
- **`reparseAt`'s acceptance check (`partial.node.span.end == expectedEnd`) only proves length parity, NOT structural parity.** Trivia attribution and other context-sensitive parsing decisions can diverge between a small-pivot reparse and a full reparse with matching length. The current `IncrementalParityTest` doesn't catch this because it never moves the cursor, so pivots are always near-root.
- **`autoPublish=true` + `waitUntil=published` makes Maven Central deploys irreversible.** Once the build's `[INFO] Uploaded bundle successfully` line lands, you cannot un-publish. Be sure before running `mvn deploy -P release`.
- **`Grammar` is a public record.** Its canonical constructor cannot have narrower visibility than the record itself. The `grammar(...)` factory is the documented entry; the constructor is `internal/library use only` per Javadoc but technically still accessible.
- **`peglib-incremental` and `peglib-formatter` cross-reference `peglib-core` types.** When renaming/refactoring core types, search across all 5 modules — not just the test resources.
- **The `%import` flow in `GrammarParser` skips factory validation for grammars with imports** (validation runs after composition in `GrammarResolver`). Don't simplify this — every `GrammarCompositionTest` case relies on it.

## 10. Where to find historical context

- `docs/RELEASE-PLAN-0.3.5-0.4.0.md` — the plan that drove the 0.3.5→0.4.0 arc, marked complete through Phase 8.
- `docs/AUDIT-REPORTS/CONSOLIDATED-BACKLOG.md` — the audit findings that drove 0.3.4 cleanup. Most P3 items shipped in 0.4.0.
- `docs/PERF-REWORK-SPEC.md` — the 0.2.2 perf rework spec; historical.
- `docs/incremental/SPEC.md` — the 0.3.0-0.3.2 incremental spec. v2 shipped, v2.5 NO-GO'd by spike.
- `docs/incremental/V2.5-SPIKE.md` — the v2.5 NO-GO + lever-1 design + post-0.4.0 retraction. Lever-1 superseded by 0.5.0 architecture.
- `docs/incremental/UNSAFE-GENERATOR-SPIKE.md` — the post-0.4.0 unsafe-generator design + status. Infrastructure landed (5 commits in `release-0.4.2` history); behavior conversion deferred to 0.5.0.
- `docs/incremental/ARCHITECTURE-0.5.0.md` — **forward-looking** architectural spec for the 0.5.0 incremental-native rework. Read this before touching incremental perf or correctness.
- `docs/bench-results/` — committed JMH JSON from each perf-touching release.

## 11. Recommended next session

**Branch pushed at `fd278fe`. Tag `v0.5.0-candidate` at HEAD.** 922 tests green. The full 0.5.0 arc shipped this session: incremental engine (Phase 1 Path D + Lever D Cursor split) + throughput engine (Tier 1 partial: A, D, F, G, G2+H, selective packrat, DFA fast-path).

### Headline cumulative numbers

**Incremental engine** (`IncrementalSessionBench`, Regime B cursor-moved-to-edit, 1900-LOC fixture):

| Metric | 0.4.3 | 0.5.0 | Δ |
|---|---:|---:|---:|
| Median | 10.8 ms | **5.0 ms** | **-54%** |
| p95 | 22.4 ms | **11.2 ms** | **-50%** |
| p99 | 53.3 ms | 90.5 ms | +70% (large-pivot tail) |
| % under 16ms | 91.5% | **96.5%** | **+5pp** |

**Throughput engine** (`Java25ParseBenchmark`, variant `phase1_allStructural_mutableResult_autoSkipPackrat`):

| Fixture | Original | 0.5.0 | Δ vs original | vs javac |
|---|---:|---:|---:|---:|
| Reference (1900 LOC) | 76.2 ms / 150 MB | **22.6 ms / 75.6 MB** | **-70% wallclock, -50% bytes** | 2.5× of javac (9 ms) |
| Self-host (37k LOC) | OOM | **956 ms / 2.04 GB** | from impossible to 1 sec | n/a |
| GC time (reference) | 2,844 ms | 354 ms | **-87%** | — |

### Branch state at `fd278fe` — 40 commits past `1619604` chore

**Phase 0 — sandbox spike (later cleaned up):** `d00eaa1` … `a8c6efe`
**Phase 1 — incremental engine production:** `8f844eb` Path A RED → `8b27dd6` Path D GREEN → `2443779` CstNode long id → `39e11f9` NodeIndex LongLongMap + Path D applyIncremental → `65a719f` nodesById refresh → `43baaf8` results doc
**Phase 2 attempted, rolled back:** `e038e4f` Lever B literal-prefix cost 4× — reverted
**Bench/JBCT cleanup:** `0ea98af` bench post-edit validation → `4ad5824` parseFull→Result
**Sandbox cleanup + Lever D:** `5275d86` -5463 LOC → `4f06046` Cursor split (p99 -53%)
**Throughput engine Tier 1:**
- `0ed2dcd` A spike — Option boxing eliminated (`mutableParseResult` flag)
- `fedc389` A coverage extension — wallclock -12% vs original
- `478b89b` D production sweep (cleanup)
- `5b2b6a1` D emission templates — `inlineLocations` default-on
- `2ad2674` E packrat int-keyed → reverted at `8f844eb`-style sequence
- `8f844eb`-equivalent: `9e9414a` E revert (regressed self-host 22%)
- `2a8cefc` self-host bench fixture added
- `7fdd5e8` D2 — eliminate remaining location() callers
- `dd1f150` F — FIRST-set Choice dispatch (62/64 choices, -20% both fixtures)
- `eec8ba3` G — JBCT-style method splits (parse_Stmt 27k→3k)
- `59be764` G2+H — Sequence chunks + nested Choice extraction (-5/-8%)
- `ca2dcfe` Selective packrat auto-detect (**biggest single win: -38% reference, -14% self-host**)
- `0ea0765` DFA fast-path Identifier-shape rules (-10% reference)
- `fd278fe` Tier 1 results + DFA javac comparison + CHANGELOG

### What's reverted (and why — pattern)

E (int-keyed packrat): regressed self-host 22% — linear probing scales badly at large load factors.
H2 (recursive nested-Choice per-alt): +4-7% slower — call overhead exceeded JIT inline benefit.
DFA generalization (whitespace + NumLit): neutral — low-volume rules don't pay off.

**Pattern:** high-volume single-target wins (A, F, selective packrat, Identifier fast-path) deliver big. Broad generalizations don't.

### Next session: Move B — mutable parse-state singleton

The last big tractable allocation lever. Targets 5,264 CstParseResult per self-host parse. **Detailed spec at [`docs/incremental/THROUGHPUT-ENGINE-MOVE-B.md`](incremental/THROUGHPUT-ENGINE-MOVE-B.md)** with:

- 4-6 incremental commit plan (each parity-gated)
- Specific blockers from prior decline (LR seed-and-grow, packrat aliasing, ~80 emission sites, cross-call read discipline)
- Risk register + decision gates per commit
- Validation procedure per commit
- Reference numbers + B target (≤ 20 ms reference / ~2× of javac)

**Key constraint:** prior agent declined B as too large for one autonomous pass and proposed multi-commit incremental delivery. Honor this — don't try to land B in a single agent run. Each commit lands green or reverts.

### Other tractable next moves (post-B)

- **Char-class bit-packing** — pre-emit ASCII bitmaps; bitwise test instead of range comparisons. ~5-15% on char-class-heavy paths.
- **Lever B retry** (incremental engine) — gated on trivia attribution rework. SafePivotAnalyzer + NodeIndex.smallestEnclosing live as dormant infrastructure for the eventual retry.
- **Trivia attribution rework** — context-independent attachment. Unblocks Lever B; comparable scope to Lever C.
- **Lever C — IR unification** (spec §4) — multi-week. Eliminates "every fix paid twice" pattern between PegEngine and ParserGenerator emission templates. Reduces 7,440 LOC to ~1,700.

### Items superseded by this session's work

- §6.2 lever-1 puzzle: dissolved by Path D's stable-id algorithm.
- §6.4 unsafe-generator work: out of scope; 0.5.0 design doesn't need it.

Do NOT pursue further allocation reduction in the 0.4.x interpreter — old guidance still holds.

---

**Last updated:** 2026-05-08, end of throughput engine Tier 1 arc + DFA fast-path spike. Branch at `fd278fe`, pushed to origin with tag `v0.5.0-candidate`. Next-session entry point: [`docs/incremental/THROUGHPUT-ENGINE-MOVE-B.md`](incremental/THROUGHPUT-ENGINE-MOVE-B.md) for Move B (mutable parse-state singleton).
