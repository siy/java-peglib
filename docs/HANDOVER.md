# peglib — handover (post-0.4.3 + 0.5.0 spec ready)

**Status:** `main` at `v0.4.3`. 7 tagged releases shipped: v0.3.5, v0.3.6, v0.4.0, v0.4.1, v0.4.2, v0.4.3 + retroactive v0.2.2 Central publish. 897 tests passing, 0 skipped. Tree clean.

**0.5.0 architectural spec ready** at `docs/incremental/ARCHITECTURE-0.5.0.md` with all 5 open questions resolved. `release-0.5.0` branch exists locally with chore commit `1619604` (not pushed). Phase 0 prototype is the next session's first step.

This document captures the complete post-0.4.3 state including: the 26× perf arc from the 0.4.x line, the 0.4.3 SourceSpan structural refactor, the IncrementalSessionBench infrastructure, and the 0.5.0 forward plan.

If you want to do anything with this repo, read §1–§5 first; §6 onwards is historical reference superseded by the 0.5.0 spec.

---

## 1. What peglib is

PEG (Parsing Expression Grammar) parser library for Java 25. Inspired by cpp-peglib. Five Maven modules; five independent artifacts from a single parent pom.

- **`peglib-core`** — `org.pragmatica-lite:peglib`. Grammar IR, lexer, parser, engine (`PegEngine`), source generator (`ParserGenerator`), analyzer, diagnostic machinery, runtime actions. Everything downstream depends on this.
- **`peglib-incremental`** — `org.pragmatica-lite:peglib-incremental`. Cursor-anchored incremental reparser. Depends on core's `parseRuleAt` API.
- **`peglib-formatter`** — `org.pragmatica-lite:peglib-formatter`. Wadler-Lindig pretty-printer framework. Depends on core's CstNode.
- **`peglib-maven-plugin`** — `org.pragmatica-lite:peglib-maven-plugin`. Mojo wrappers: `generate` / `lint` / `check`.
- **`peglib-playground`** — `org.pragmatica-lite:peglib-playground`. CLI REPL + embedded-HTTP web UI. Uber-jar via `maven-shade-plugin`.

## 2. Maven Central status

Published on Central: **v0.2.1**, **v0.2.2**, **v0.4.1**, **v0.4.2**, **v0.4.3**. The intermediate arc v0.2.3 → v0.4.0 (12 versions) has tagged releases on GitHub but no Maven Central publish — publish on demand if downstream needs them.

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
java -cp peglib-core/target/peglib-0.4.3.jar \
     org.pragmatica.peg.analyzer.AnalyzerMain path/to/grammar.peg

# Run incremental editing-session bench (interactive perf measurement)
mvn -pl peglib-incremental -Pbench -DskipTests package
java -cp peglib-incremental/target/benchmarks.jar \
     org.pragmatica.peg.incremental.bench.IncrementalSessionBench

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

### v0.4.1 (2026-05-04) — 3.88× interpreter perf

Flame-graph-driven optimization arc. Three commits, all interpreter-side, no API changes:

- `Grammar.rule` HashMap cache in `PegEngine` — eliminates per-reference O(N) linear scan + stream pipeline (14% CPU + 14% allocations)
- `ParseMode.standard()`/`noWhitespace()` static singletons — eliminate per-call record allocation (4% allocations)
- `LinkedHashSet` for `furthestExpected` dedup in `ParsingContext` — replaces `StringBuilder.indexOf` O(n*m) scan (53% inclusive CPU before fix; 2.12× speedup standalone). Mirrored to generator emission templates.

Measured on 1900-LOC Java fixture: full parse 281 → 72.4 ms (3.88×). Incremental cursor-far edit 322 → 107 ms (3.0×).

### v0.4.2 (2026-05-04) — standalone-parser fix

Single-fix maintenance release. Generated parsers no longer leak FQCN references to peglib runtime — emitted `RuleId` interface no longer extends `org.pragmatica.peg.action.RuleId`, emitted `parseRuleAt` signature uses local `RuleId`, all FQCN occurrences in emission templates eliminated. Generated parsers now compile in projects with no peglib runtime on classpath, matching the documented contract.

### v0.4.3 (2026-05-06) — interactive-editing perf + 0.5.0 spec

Performance release focused on interactive editing. **One breaking change**: `SourceSpan` record components changed from `(SourceLocation start, SourceLocation end)` to six int triples; `start()`/`end()` preserved as lazy methods. Drove the 26% p95 improvement.

Other changes:
- `NodeIndex.build` pre-sizes IdentityHashMap from descendant count — eliminates resize churn (was 22.8% of bench allocations)
- New `IncrementalSessionBench` in `peglib-incremental/src/jmh/java` — durable measurement harness for realistic 1,000-edit interactive sessions, two regimes (cursor-moved-to-edit + cursor-pinned), reports per-class p50/p95/p99/max + frame-budget hit rate + top-10 outlier diagnostics
- JVM tuning recommendation in `peglib-incremental/README.md`: `-Xms2g -Xmx2g -XX:MaxGCPauseMillis=20` reduces p99 by 33% (60ms → 40ms)

Bench numbers (Regime B, cursor-moved-to-edit, 1900-LOC fixture):

| Metric | 0.4.2 | 0.4.3 | Change |
|---|---:|---:|---:|
| Median | 13.3 ms | 10.8 ms | -19% |
| p95 | 30.1 ms | 22.4 ms | -26% |
| p99 | 56.4 ms | 53.3 ms | -5% |
| Max | 101.9 ms | 98.6 ms | -3% |
| % under 16ms (frame budget) | 83% | 91.5% | +8.5pp |

**Failed experiments documented:** SourceLocation interning probe (no wall-time win — proved bytes-allocated ≠ wall-time-impact for short-lived young-gen allocations). ParseResult.Failure singleton probe (same negative result). Incremental NodeIndex update via mutable `evolve()` (correctness regressions in IncrementalParityTest, reverted).

**Architectural spec for 0.5.0** landed at `docs/incremental/ARCHITECTURE-0.5.0.md`. All 5 open questions resolved. `release-0.5.0` branch prepared locally (not pushed). Phase 0 prototype is the next-session entry point.

## 6. Outstanding work — ranked by value-per-effort

### 6.1 Scheduled remote agent (already armed)

Trigger ID `trig_01HhXqsGeHfRoWNNnqM7TLod`, fires **2026-05-08T14:00:00Z**. Runs JMH + async-profiler against the post-0.3.6 baseline, captures flame graphs, posts results as a comment on the open release-0.3.6 PR or as a new issue. View at https://claude.ai/code/routines/trig_01HhXqsGeHfRoWNNnqM7TLod

The agent will report which Tier (per `docs/archive/V2.5-SPIKE.md` § "Alternative levers") the next bottleneck lives in.

**Important context:** the agent assumes 0.3.6 contains the lever 1 fix, which it doesn't. The agent may report numbers worse than the spike's projected 5-15ms (because lever 1 is still not landed). The agent's logic still works as a baseline-capture — just interpret the results in light of "lever 1 not yet shipped."

### 6.2 Lever 1 — incremental perf (DEFERRED — deeper than the spike claimed)

**Status:** the spike doc's "zero correctness risk" claim is **retracted**. See `docs/archive/V2.5-SPIKE.md` "Addendum (post-0.4.0)" for the retraction. Two failed attempts on this lever:

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
- `docs/archive/V2.5-SPIKE.md` — **read this before any incremental perf work** (archived; lever-1 superseded by 0.5.0 architecture)

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

- *(deleted 0.5.0-candidate cleanup)* `docs/RELEASE-PLAN-0.3.5-0.4.0.md` was the plan that drove the 0.3.5→0.4.0 arc. Marked complete through Phase 8 before removal; recover from git history if needed.
- `docs/AUDIT-REPORTS/CONSOLIDATED-BACKLOG.md` — the audit findings that drove 0.3.4 cleanup. Most P3 items shipped in 0.4.0.
- `docs/archive/PERF-REWORK-SPEC.md` — the 0.2.2 perf rework spec; archived/historical.
- `docs/archive/SPEC-incremental-original.md` — the 0.3.0-0.3.2 incremental spec (archived). v2 shipped, v2.5 NO-GO'd by spike.
- `docs/archive/V2.5-SPIKE.md` — the v2.5 NO-GO + lever-1 design + post-0.4.0 retraction (archived). Lever-1 superseded by 0.5.0 architecture.
- `docs/archive/UNSAFE-GENERATOR-SPIKE.md` — the post-0.4.0 unsafe-generator design + status (archived). Infrastructure landed (5 commits in `release-0.4.2` history); behavior conversion deferred to 0.5.0.
- `docs/archive/PHASE-0-RESULTS.md`, `docs/archive/PHASE-1-PROVE-OUT.md` — interim 0.5.0 phase results (archived); final state in `docs/incremental/PHASE-1-RESULTS.md`.
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

### Move B — attempted and abandoned 2026-05-08

The mutable parse-state singleton arc was attempted in this session: 5 incremental commits landed (`88c15f3` foundation → `a86fa97` parse_<rule>→boolean → `23ba500` match helpers → `98f4c11` combinators → `ed95951` predicates/capture/cut/TB), then **policy-driven rollback to `v0.5.0-candidate`**. Branch state preserved.

**Why it failed (well-supported by 5 bench data points):** modern JIT was already scalar-replacing the per-call `CstParseResult` records (raw-nullable fields + immediate-consume call sites are textbook escape-analysis fodder). Replacing them with a heap-bound singleton **defeated** that optimization. Allocation rate dropped (-12.8% cumulative — short of the §9 15% gate) **but wallclock regressed monotonically** (+11.0% cumulative). The trajectory was clear by commit 5: pushing further would have hurt more.

| Stage | Wallclock (ms/op) | Alloc (MB/op) |
|---|---:|---:|
| Baseline (this session start) | 22.6 | 75.6 |
| Commit 3 | 23.97 | 72.1 |
| Commit 4 | 24.71 | 66.3 |
| Commit 5 | 25.09 | 65.96 |

**Full post-mortem with hypothesis, ruled-out moves, and reoriented optimization directions: [`docs/incremental/THROUGHPUT-ENGINE-MOVE-B.md`](incremental/THROUGHPUT-ENGINE-MOVE-B.md) §11.**

The 5 commits are in reflog (`git reflog show release-0.5.0`) for ~30 days if forensic inspection is needed. Don't re-attempt; new info has retired the approach.

### Reoriented next moves (post-Move-B reassessment)

The Move B failure tells us **allocation rate is no longer a productive target** in the throughput engine. The engine is now allocation-optimized to the point where further alloc reduction has negative ROI on wallclock. Future wins live elsewhere:

- **Profile-driven wallclock work** (NEW, highest-ROI suggestion) — `async-profiler` in CPU mode + flame graphs on the reference fixture. Identify the actual hot CPU work post-Tier-1, target that directly. The Move B failure proved alloc-rate metrics can be misleading; CPU samples are the trustworthy signal now. **EXECUTED THIS SESSION — see "Post-rollback profile-driven optimization arc" below for outcomes.**
- **Char-class bit-packing** — pre-emit ASCII bitmaps; bitwise test instead of range comparisons. **Reassess with care** — same risk class as Move B. Bench wallclock first to confirm range-comparison isn't already JIT-optimized via SIMD/code-motion. Potential ~5-15% on char-class-heavy paths IF measurably hot.
- **Lever B retry** (incremental engine) — gated on trivia attribution rework. SafePivotAnalyzer + NodeIndex.smallestEnclosing live as dormant infrastructure for the eventual retry. Independent of allocation patterns; unaffected by Move B finding.
- **Trivia attribution rework** — context-independent attachment. Unblocks Lever B; comparable scope to Lever C. Independent of allocation; unaffected by Move B finding. **STATUS UPDATED 2026-05-09:** investigated through 3 steps (catalog → adversarial corpus → post-pass prototype) on `release-0.5.1`. Prototype lands at `78c4003`. Verdict: **viable; ~6-10 days to production rework**. See "Trivia investigation arc (2026-05-09)" subsection below.
- **Lever C — IR unification** (spec §4) — multi-week. Eliminates "every fix paid twice" pattern between PegEngine and ParserGenerator emission templates. Reduces 7,440 LOC to ~1,700. Maintainability + complexity reduction primary value, not raw perf.

### Post-rollback profile-driven optimization arc (this session, 2026-05-08)

After Move B was abandoned and rolled back to `v0.5.0-candidate` (`e849b63`), a profile-driven optimization arc executed in the same session. Two profile passes (CPU + alloc × reference + selfhost via async-profiler) identified specific candidates; each candidate landed under a strict bench gate (>3% wallclock OR >5% alloc → ship; >1% wallclock regression → reset).

**Branch now at `38b6a8e`** — 2 commits past `v0.5.0-candidate`:

| Commit | Direction | Reference Δ | Selfhost Δ |
|---|---|---|---|
| `4763251` | trivia snapshot via int size (eliminates `List.copyOf` per backtrack) | **-12.1% wallclock / -6.4% alloc** | **-8.2% wallclock / -7.5% alloc** |
| `38b6a8e` | matchCharClassCst ASCII char interning pool (eliminates `String.valueOf(c)` per match) | **-3.95% wallclock / -3.76% alloc** | **-4.59% wallclock / -1.38% alloc** |

**Cumulative (from `v0.5.0-candidate` baseline, this machine, this session):**

| Fixture | v0.5.0-candidate | After 38b6a8e | Δ wallclock | Δ alloc |
|---|---:|---:|---:|---:|
| Reference (1900 LOC) | 22.66 ms / 75.55 MB | **19.12 ms / 68.02 MB** | **-15.6%** | **-10.0%** |
| Selfhost (37k LOC) | 937 ms / 2.04 GB | **832 ms / 1.85 GB** | **-11.2%** | **-9.3%** |

**Reference fixture is now under 20 ms — the original Move B wallclock target, achieved without singleton mutation.**

### Lesson taxonomy from this session's optimization arc

7 candidates attempted post-rollback. 2 ships, 5 resets. Pattern that emerged:

| Pattern | Result | Reason |
|---|---|---|
| Replace `List.copyOf` (varargs / array copy) with primitive int snapshot | **WIN** | JIT cannot elide bulk array copies — real alloc cost eliminated |
| Replace per-call `String.valueOf(c)` with interned ASCII pool | **WIN** | JIT allocates fresh String per call; no escape-analysis path |
| Replace `String.contains` quadratic scan with `LinkedHashSet` dedup | RESET | JIT inlines String.contains efficiently; call-overhead-dominated |
| Replace `HashMap<Long,_>` with custom open-addressed long-keyed map | RESET | JDK HashMap per-op faster than custom; per-op latency tax exceeds alloc savings |
| Provide `HashMap` initial-capacity hint from input size | RESET | Over-sizing hurts cache locality more than it saves resize cost |
| Convert `Token` record → mutable class with text cache (lazy substring) | RESET | Records are JIT-scalar-replaceable; mutable class with cache field defeats EA |
| Defer `SourceLocation` construction in trackFailure to parse-end | RESET | SourceLocation is a record; JIT readily stack-allocates / dead-code-eliminates |

**Refined principle:** allocation share in a profile is NOT predictive of wallclock improvement when JIT escape analysis already handles the alloc. Successful patterns target allocations the JIT cannot elide:
- Bulk array copies (`Arrays.copyOf`, `ArrayList.toArray`)
- Per-call freshly-allocated objects with no scalar replacement path (e.g. `String.valueOf(c)` materializes a fresh char[] backing)

Failed patterns target allocations the JIT already optimizes:
- Records (compact, scalar-replaceable, value-class-like)
- Immediately-consumed objects in a single method's scope
- JDK collection internals (HashMap is heavily JIT-optimized)
- Mutable shared state on `this` (defeats EA — Move B's lesson)

### What's now also ruled out (this session)

- Per-call `CstParseResult` elimination via singleton (Move B — confirmed; rolled back)
- `String.contains` micro-optimization in trackFailure
- Custom long-keyed packrat cache map
- HashMap initial-capacity sizing hints
- Lazy Token text materialization (record→class transition)
- Lazy SourceLocation construction in trackFailure

### What's still viable

- **Char-class bit-packing** — REASSESS WITH CARE per same risk class. Bench wallclock first.
- **Lever B retry** (incremental engine) — independent of allocation patterns
- **Trivia attribution rework** — independent
- **Lever C IR unification** — multi-week, maintainability-first
- **Re-profile post-`38b6a8e`** — the profile has shifted again; new candidates may emerge or the profile may be flat (no clear next move). Run a third profile pass if pursuing further wallclock work.

### Trivia investigation arc (2026-05-09, on release-0.5.1)

After 0.5.0 ship + tag move + branch creation for 0.5.1 patch cycle, a 3-step investigation arc explored the long-standing trivia attribution issue.

**Background.** Current attribution couples to parse history via the `pendingLeadingTrivia` buffer + 30+ save/restore sites. RoundTripTest 22/22 green on corpus, but partial-reparse (`parseRuleAt`) attributes trivia differently than full reparse — blocking Lever B (incremental engine smaller-pivot optimization). HANDOVER §6.4 cited this as one of two blockers for Lever B.

**Step 1 — context-dependency catalog.** Investigation-only mapping of attribution code in PegEngine + ParserGenerator. Findings:

- Current attribution is *almost* a function of `(input, rule, span)` — buffer machinery is navigational, not algorithmic
- Bonus latent issue: generator uses size-only `restorePendingLeading(int)` snapshot (`ParserGenerator.java:6768-6776`) vs interpreter's full-list `List.copyOf` (Bug A fix). Asymmetric. Currently passes RoundTripTest by luck — relies on `%whitespace` re-skip after rollback as load-bearing invariant.
- Bug C' "orphan trivia → deepest leaf" is bookkeeping-context only; could attach to wrapper.trailing instead with same byte-equality.
- Cost estimate: 6-10 days for production rework, comparable to one release cycle.

**Step 2 — adversarial test corpus** (`test: trivia adversarial corpus + findings` `d2cc6be`). 17 tests across 7 divergence-target classes + 1 fuzz test. Findings:

- 0 definite bugs
- 2 latent bugs (generator size-only restore "robust-by-luck"; Optional CutFailure pending non-restore — unobservable through CST)
- 3 robust-by-design (Bug C' nested cases, Predicate symmetry, %whitespace purity)

Suite at `peglib-core/src/test/java/org/pragmatica/peg/perf/TriviaAdversarialTest.java`. Findings: [`docs/incremental/TRIVIA-ADVERSARIAL-FINDINGS.md`](incremental/TRIVIA-ADVERSARIAL-FINDINGS.md).

**Step 3 — post-pass prototype** (`feat: TriviaPostPass — context-independent trivia attribution prototype` `78c4003`). New `org.pragmatica.peg.tree.TriviaPostPass` re-derives leading/trailing for every CST node from `(input, span)` without parse-history dependency. Validation: `TriviaPostPassTest` (16 tests, all passing). Findings:

- **Round-trip preservation: 21/21 corpus + 9/9 adversarial inputs byte-equal**
- **Total trivia text preserved: 0 text loss across 46,756 nodes inspected**
- Structural divergence is slot-shifts only (6.4% of nodes; all balanced — leading shifts in both directions across NonTerminals; trailing shrinks only on NonTerminals; no Bug-C' shape in this corpus)
- **`parseRuleAt` structural parity ACHIEVED under postPass** — context-loss disappears. Load-bearing claim for Lever B.

**Verdict.** Production rework is viable. Recommended path for next session:

1. **Decide leading-attachment policy at sibling boundaries** — engine "carries leading forward through pending"; postPass "attaches leading from previous-sibling-end coordinate". Both coherent; pick one and document.
2. **Add hash-based parity gate against generated parser on the large 1900-LOC fixture** (Step 3 catalog excluded it for runtime).
3. **Production rework, multi-commit parity-gated** (per Move B lessons): wire `triviaPostPass=true` flag into `PegParser`, replace 30+ buffer save/restore sites with empty-leading emission + post-pass call. ~6-10 days. Each commit green at TriviaPostPassTest + RoundTripTest + adversarial suite.

After production rework: orthogonal Lever B blockers remain (fallback-rule bypass per §6.2 root cause #1, safe-pivot concept). Trivia rework alone removes ONE of two HANDOVER-cited blockers.

### Step 4 production rework — commits 1-4 landed (2026-05-09)

After Step 3 verdict, the production rework arc executed in the same session. Branch `release-0.5.1` advanced from `51144a6` to `dcd146f` (4 commits + 1 docs commit).

| Commit | Description |
|---|---|
| `318f5cf` | feat: triviaPostPass flag wires TriviaPostPass as post-parse hook (Step 4 commit 1) |
| `d7b3496` | feat: TriviaPostPass splice-offset overload for parseRuleAt subtree leading (Step 4 commit 2) |
| `605327b` | perf: ParsingContext buffer ops no-op under triviaPostPass flag (Step 4 commit 3) |
| `dcd146f` | feat: ParserGenerator emits embedded TriviaPostPass under flag-ON (Step 4 commit 4) |

**Functional state:** the trivia rework is COMPLETE through commit 4. Under `triviaPostPass=true`:

- **Interpreter** (`PegEngine`): buffer ops no-op via `ParsingContext` early-outs (38 call sites neutralized without source changes); post-pass produces correct attribution. Optimal — no wasted CPU.
- **Generator** (`ParserGenerator`): generated parsers embed an inline `TriviaPostPass` (preserves standalone-parser invariant — generated parsers depend only on `pragmatica-lite:core`); `parseCst` calls the embedded post-pass after the body parse. Correct attribution; **suboptimal CPU** because buffer machinery still runs and is overwritten (commit 5 territory).
- **`parseRuleAt`** honors splice offset (4-arg overload `assignTrivia(input, cst, grammar, leadingScanFrom)`); body subtree structurally identical to corresponding subtree of full reparse — Lever B unblocker validated in tests.

**Test coverage:** 768 peglib-core tests + 1 pre-existing skip. `TriviaPostPassFlagTest` (12 tests across 4 nested classes), `TriviaPostPassSpliceOffsetTest` (7 tests), `GeneratedParserTriviaPostPassTest` (8 tests), `TriviaPostPassTest` (16 tests), `TriviaAdversarialTest` (16 enabled). All green under both flag-OFF (default) and flag-ON.

**Default behavior (flag-OFF) unchanged** — every pre-existing test passes byte-for-byte identically. Adoption is opt-in via `PegParser.builder(grammar).triviaPostPass(true).build()` or constructing `ParserConfig` with the flag set.

### Step 4 COMPLETE — all commits 1-7 shipped including default flip

(historical note: commit 6 was attempted, reverted, and successfully retried after commit 7 fixed the underlying bug — see commits 6 → 7 → 6-redo sequence below)

### Step 4 commit 5 landed; commit 6 attempted and reverted (BUG DISCOVERED — later fixed in commit 7)

**Commit 5 (`4ed1cf5`)** — ParserGenerator emits no-op buffer methods under flag-ON. Pure CPU optimization parallel to commit 3 for the interpreter. Tests stay at 768 + 1 skip. Generated parser under flag-ON is now lean (no dead buffer work) AND correct (post-pass produces attribution).

**Commit 6 attempted — REVERTED. Real bug surfaced in post-pass implementation.**

Flipping `triviaPostPass` default to `true` triggered **20 RoundTripTest failures on the Java 25 corpus** — *not* slot-shifts but actual trivia text LOSS:

- `void allCompoundAssignments` → `voidallCompoundAssignments` (lost whitespace between tokens)
- `int r;` → `intr;`
- `int b = 0;` → `intb = 0;`
- Reconstructed lengths 1-2059 chars shorter than originals across 20 of 22 RoundTripTest fixtures (`Annotations.java`, `BlankLines.java`, `ChainAlignment.java`, `ClassLiterals.java`, `Comments.java`, `CompoundAssignments.java`, `Enums.java`, `ExhaustiveSwitchPatterns.java`, `Imports.java`, `KeywordPrefixedIdentifiers.java`, `Lambdas.java`, `LineWrapping.java`, `MultilineArguments.java`, `MultilineParameters.java`, `Records.java`, `SwitchExpressions.java`, `TernaryOperators.java`, `TextBlocks.java`, `large/FactoryClassGenerator.java.txt`, `flow-format-examples/BlankLineRules.java`)

This contradicts the post-pass spec doc claim: *"Total trivia text is preserved (round-trip reconstruction is byte-equal); only the leading/trailing slot in which a given trivia chunk lives can differ."* The bug is REAL TEXT LOSS on production-realistic Java 25 inputs, not just attribution permutation.

**Test coverage gap that masked the bug:**
- Step 3 prototype's `TriviaPostPassTest` round-trip claim ("21/21 corpus") used the **smaller fixture set under perf-corpus**, EXCLUDING the `/large/` directory which has the realistic 1900-LOC `FactoryClassGenerator.java.txt`.
- `TriviaAdversarialTest` (16 tests) uses small focused grammars — JSON-like, not full Java 25.
- `GeneratedParserTriviaPostPassTest` (8 tests) uses 5 small grammars (parity, leading, trailing, comment, unchanged).
- The full `RoundTripTest` corpus (22 Java 25 fixtures) was never run under flag-ON until commit 6 attempt.

**Bug location:** The generator path is materially affected. `GeneratedJava25Parser.ensureLoaded()` calls `PegParser.generateCstParser(...)` with no explicit config → picks up `ParserConfig.DEFAULT` → flag-flip flips the generator's emission. Whether the interpreter-only path has the same bug is unverified (could be tested by setting flag-ON on Phase1ParityTest variants).

**Next-session work needed (BEFORE default flip can be reattempted):**

1. **Reproduce the bug in isolation** — create a focused test case that fails under flag-ON. Minimal Java 25 sub-fixture; goal is a 5-line input that loses trivia text under flag-ON.
2. **Diagnose the embedded post-pass implementation** in the generated parser. `ParserGenerator.java`'s `embedded TriviaPostPass` (added in commit 4, ~617 lines) emits an inline post-pass. The bug likely lives there — possibly:
   - Whitespace re-scan logic in the embedded post-pass mismatches the parser's own `skipWhitespace` semantics
   - The embedded post-pass's coordinate-walking misses whitespace between tokens (specifically `(prev_node_end, this_node_start)` where the gap is a token-boundary rather than a sibling-boundary)
   - Cache-hit interaction (Bug B fix is in the parser; the embedded post-pass might re-derive trivia for nodes whose body came from cache and lose context)
3. **Fix the embedded post-pass** OR extend `TriviaPostPass` (runtime) similarly. Re-run RoundTripTest under flag-ON; should be 22/22.
4. **Run full coverage under flag-ON** before reattempting commit 6: every existing test class set to flag-ON, measure failures, fix incrementally. NOT a default-flip until the bug is gone.
5. **Bench impact study** (deferred from original plan — still relevant after bug fix).

**What's still valuable from Steps 1-5:**
- The flag is functional for SMALL grammars + adversarial inputs (validated)
- `parseRuleAt` splice-offset overload is correct and unblocks Lever B's trivia-context-loss problem in principle
- Buffer no-op infrastructure in both interpreter and generator is clean and ready
- Commits 1-5 are SHIPPABLE; the flag is opt-in only

After bug fix + commit 6, Lever B can be retried: `parseRuleAt` + post-pass produces structurally identical subtrees, removing the trivia-context-loss blocker. The orthogonal fallback-rule-bypass blocker (§6.2 root cause #1) still requires separate work.

### Step 4 commit 7 — bug fix shipped (`612fbea`)

After the diagnosis identified two distinct bugs in the post-pass implementation, commit 7 fixed both:

1. **Bug C' drain compensation** — TriviaPostPass.rebuildNonTerminal now mirrors engine's `attachTrailingToTail`: orphan trivia drains into deepest-rightmost-leaf descendant instead of being placed on the wrapper's trailingTrivia. `CstReconstruct.emit` correctly emits leaf trailings on every walk path; the wrapper-trailing was invisible for non-last-wrapper-children. ~70 lines in `TriviaPostPass.java` runtime + ~30 lines in the embedded version emitted by `ParserGenerator.java`.

2. **Generator-semantics cursor adjustment** — separately discovered: the generated parser's `wrapWithRuleName` produces wrappers whose span INCLUDES their own leading trivia (`startOffset` captured BEFORE `skipWhitespace`). The post-pass's child-walk at first-child time was double-emitting the wrapper's leading bytes via the first child's leading scan. Fix: `rebuildNonTerminal` probe-scans `[spanStart, spanStart+leadingLen)` and, if it matches the caller-supplied leading exactly, advances the cursor past it. Symmetric to existing `rebuildRoot` adapter; only kicks in for generator-semantics CSTs.

Validation: RoundTripTest 22/22 under flag-ON (was 2/22 pre-fix). All 768 peglib-core tests + 1 skip green at flag-OFF default.

**Honest scope note (from agent report):** two bugs were fixed under one commit message. Could have split into 7a/7b, but both touch the same `rebuildNonTerminal` method and same root cause family (post-pass not mirroring engine span/trivia coupling). The cursor-adjustment hunk is cleanly separable in the diff if retrospective splitting is desired.

### Step 4 commit 6 — default flip succeeded (`3b372af`)

After commit 7 fixed the bugs, commit 6 was retried: `ParserConfig.DEFAULT.triviaPostPass` and the `parserConfig(...)` factory both flipped from `false` to `true`. Two sentinel tests in `TriviaPostPassFlagTest` inverted (DefaultOffNoOp → DefaultOnNoOp; assert-false → assert-true).

**Result: 0 test failures, 0 errors, 0 surprises.** No Tier-A/B/C cascade. The 8 `Phase{1,2}*ParityTest` files (which explicitly pass `triviaPostPass=false`) provide the legacy-attribution regression net. Other tests in the project use the default and are bit-for-bit identical under post-pass attribution thanks to the fix.

**End-state for 0.5.1:** post-pass attribution is the default; legacy buffer-driven attribution is opt-out via explicit `new ParserConfig(..., triviaPostPass=false, ...)`. The trivia issue that motivated this multi-session investigation is **closed**: attribution is now context-independent, parseRuleAt produces structurally identical subtrees to full-reparse (Lever B trivia-context-loss blocker is RESOLVED), and the buffer machinery still functions for backward-compat.

**Lever B retry:** the trivia-context-loss blocker is gone. The orthogonal fallback-rule-bypass blocker (§6.2 root cause #1) still requires separate work — that's the next-session entry point if pursuing incremental engine optimization.

### Bench impact study (2026-05-09) — DONE; one bug fixed in flight

After Step 4 default flip, A/B bench (reference + selfhost, JMH 5/3, gc profile) revealed a critical regression:

| Fixture | Buffer-driven | Post-pass (default) | Δ% |
|---|---:|---:|---:|
| Reference (1900 LOC) | 19.78 ms | **418 ms** | +2,013% |
| Selfhost (37k LOC) | 934.7 ms | **198,679 ms** | +21,162% |

Investigation: `computeSpan(input, from, to)` re-scanned `[0, from)` from offset 0 on every trivia chunk → O(K · N) → O(N²). Empirical exponent 1.88 at N=32000. RoundTripTest's correctness gate didn't catch this because tests run to completion without time limits.

**Fix shipped** at `6675479` — line-start table approach. O(N) one-shot precompute + O(log N) binary search per chunk. Both runtime and emitted versions updated.

Post-fix bench:

| Fixture | Buffer-driven | Post-pass (post-fix) | Δ% |
|---|---:|---:|---:|
| Reference (1900 LOC) | 19.78 ms | 26.87 ms | +35.8% |
| Selfhost (37k LOC) | 934.7 ms | 943.6 ms | +0.95% (within noise) |

Reference is 36% slower than legacy; selfhost is at parity. The post-pass overhead is real on small inputs (one tree-walk) but amortizes for large inputs where buffer save/restore dominated. **Acceptable as default**; opt-out remains available via explicit `ParserConfig` constructor.

Allocation rate is essentially flat (within ~16% of legacy on selfhost; line-start table + per-call int[2] tuples cost some bytes but no longer drive wallclock).

**Lessons banked:**
- Correctness tests should NOT be the only guard against perf regressions. RoundTripTest passed all 22 fixtures under flag-ON before the bench revealed the problem.
- Bench A/B mandatory before any default-changing commit.
- The diagnosis approach (read code → identify suspected hotspot → empirical timing harness → confirm with magnitude analysis) generalized well: the smoking gun was found in 30 minutes; the fix was 30 LOC.

### Outstanding work (post-Step-4 + bench-fix)

- **Lever B retry** — fallback-rule-bypass blocker work (orthogonal to trivia, separately scoped). The trivia-context-loss blocker is now fully RESOLVED.
- **Lever C IR unification** — multi-week, maintainability-first.
- **Tighten reference fixture overhead** — post-pass is 36% slower on small inputs. If important, address H1 (`attachTrailingToTail` spine rebuild) and/or H4 (probe-scan in rebuildNonTerminal). Probably not worth chasing; selfhost (the actual perf-critical workload) is at parity.

### Items superseded by this session's work

- §6.2 lever-1 puzzle: dissolved by Path D's stable-id algorithm.
- §6.4 unsafe-generator work: out of scope; 0.5.0 design doesn't need it.

Do NOT pursue further allocation reduction in the 0.4.x interpreter — old guidance still holds.

**Updated post-Move-B (2026-05-08):** Do NOT pursue further allocation reduction in the **generated parser** either. The Move B failure proved alloc-rate is no longer a productive target — JIT escape analysis is already doing aggressive scalar replacement on per-call records. Future perf work should be CPU-profile-driven, not alloc-profile-driven.

---

**Last updated:** 2026-05-09, end of Step 4 trivia rework + cleanup arc A→F.3 + StringSpan + Cleanup G + Lever B retry attempt + skip-postPass deferral.

### Skip postPass for full parses — deferred (2026-05-09)

After Cleanup G abandoned the +30% reference gap as structural, considered the cleaner architectural fix: under flag-ON, run the buffer machinery for full parses (`parseCst`) and run postPass only for `parseRuleAt`. That preserves both the buffer's free-attribution-during-parse property AND the postPass's structural-parity-for-splice property.

**Cost-benefit assessment (deferred for that reason):**

- **Implementation cost:** several days. Cleanup A's call-site short-circuit (38 sites in PegEngine + parallel emit sites) needs to be REVERSED selectively for full-parse path. Generator must emit two parse-paths (full vs partial). Adds a `parseMode` parameter / context flag throughout. Bench-gated to validate buffer path didn't regress since Cleanup A.
- **Workloads that would benefit:**
  - IDE plugin uses `parseRuleAt` (incremental), not `parseCst` — **no benefit** (postPass is mandatory for splice parity)
  - Self-host workload — already at parity / faster than legacy — **no benefit**
  - One-shot CLI / Maven plugin parsing — 25 ms vs 19 ms is **academic** at sub-frame budget
- **Workloads that would NOT benefit:** the perf-critical paths.

The +30% reference gap is therefore academic for current workloads. Documented as available-if-needed; not pursued.

### Lever B retry — attempted, FAILED on bench (2026-05-09)

After the trivia rework removed one of two HANDOVER §6.2 blockers (trivia-context-loss), retried Lever B by wiring the dormant `SafePivotAnalyzer.safePivotRules(Grammar)` into `tryIncrementalReparse`. Gate: only accept rules in safe-pivot set; walk outward otherwise.

**Bench result (IncrementalSessionBench, 1000 edits, Regime B):**

| Metric | Pre-Lever-B | Post-gate |
|---|---:|---:|
| Median | 5.0 ms | **21.9 ms (+338%)** |
| p95 | 11.2 ms | 214.8 ms |
| % under 16ms | 96.5% | **43.6%** |

Reverted. HEAD unchanged at `46d8a05`.

**Root cause of failure:** SafePivotAnalyzer's "unambiguous literal prefix" criterion is too conservative for Java 25 grammar. Most rules start with character classes (`Identifier <- [a-zA-Z_] ...`) or rule references (`Type <- ClassType / ...`), which the analyzer marks unsafe. Walk-up-find-safe-ancestor strategy lands at root for ~56% of edits → forces full reparse. Parity is preserved but perf is catastrophically worse.

**What this implies:**
- The existing "OLD walk-up + length check" passes IncrementalParityTest in current form, even without the safe-pivot gate. The §6.2 hypothetical bug ("smallestEnclosing descent strategy bypasses ancestors") doesn't actually fire in the current walk-up algorithm.
- Lever B retry would need: (a) a smarter analyzer that includes rules with disjoint first-sets (not just literal prefixes), or (b) on-accept structural validation instead of static analysis gate.
- Both are non-trivial (a few days each) and bench-gated.
- Until then, the current incremental engine's length-check-only acceptance is the de facto safe state. Median 5.0 ms / p95 11.2 ms is already excellent.

**Defer Lever B retry indefinitely.** The trivia work was the real blocker; the perf is already good enough that strengthening the gate hasn't been worth the analysis investment.

### Cleanup G — reference-fixture tightening attempted, abandoned (2026-05-09)

Two micro-optimizations attempted under bench gate (≥3% wallclock OR ≥5% alloc on reference); both REVERTED on evidence:

- **G.1 — first-char prefilter on scanWhitespace.** Hypothesis: ~50% of scan calls return empty; precheck `input.charAt(prevEnd)` against whitespace-first-set to skip alloc. Reality: parser positions are always at token-content boundaries (skipWhitespace already advanced), so scan calls have either real whitespace or `from == to`. Existing `from >= to` short-circuit already catches the empty case; new prefilter rejected ~0 calls. Bench: alloc Δ +0.2%, wallclock noise. Reverted.

- **G.2 — alloc tightening on non-empty scan path.** Three changes bundled: lineColAt returns long instead of int[2]; scanWhitespaceFast returns `List.of(single)` for size-1 chunks; combine() short-circuits empty. The combine change was already in place at `76498bf`. Other two micro-saves total ~32 KB out of 77 MB per-op (<0.05%). Bench: alloc Δ -0.4%, wallclock noise. Reverted.

**Verdict: the +30% reference-fixture gap is structural, not micro-fixable.** Cost lives in:
- Spine rebuild on divergence (List.copyOf of children, NonTerminal record construction)
- Recursive scanWhitespace per node boundary
- Per-chunk Trivia node classification

Closing the gap requires structural redesigns (NOT "tightening"):
1. Skip the post-pass entirely when CST already has correct trivia attribution (gate via engine-side flag)
2. Persistent immutable lists with structural sharing for trivia lists
3. Lazy SourceSpan materialization

Each is its own spec. Selfhost (perf-critical) is already faster than legacy (-5%); the reference fixture's small inputs make the post-pass overhead a higher % of total but the absolute cost (~7 ms) is bounded.

 Branch `release-0.5.1` at `0b29c78` — 20 commits past 0.5.1 base. peglib-core 805 tests + 1 pre-existing skipped, all green at the new default (post-pass + StringSpan). **Trivia rework + cleanup state: SHIPPED + bench-validated.** Cumulative selfhost wallclock now **-5% under legacy** (was at parity); reference fixture +30% over legacy (per-NonTerminal scan dominates; future work). 30 new StringSpanTest cases + StringSpan added as `org.pragmatica.peg.tree.StringSpan` (CharSequence view with lazy String materialization). Move B post-mortem: [`docs/incremental/THROUGHPUT-ENGINE-MOVE-B.md`](incremental/THROUGHPUT-ENGINE-MOVE-B.md) §11. Next session: Lever B retry (fallback-rule-bypass blocker remains; trivia-context-loss blocker is RESOLVED) OR Lever C IR unification OR address reference-fixture per-NonTerminal scan cost (cache scanWhitespace, skip NonTerminals with full-coverage children, batch gap-scans).
