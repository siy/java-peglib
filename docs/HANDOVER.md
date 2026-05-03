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

### 6.2 Lever 1 — incremental perf (deferred from 0.3.6)

**The trap:** a naive swap of `findBoundaryCandidate`'s starting point from `enclosingNode` to `index.smallestContaining(editStart)` produces correctness regressions on `IncrementalParityTest` (12/100 failures observed, all CST-hash mismatches).

**Why it fails:** `NodeIndex.contains(node, offset)` uses `<=` on BOTH ends:

```java
public static boolean contains(CstNode node, int offset) {
    var span = node.span();
    return offset >= span.start().offset() && offset <= span.end().offset();
}
```

So a node ending exactly at `editStart` "contains" the edit point. `descendTo` walks down picking the first child that contains the offset — at boundaries, this picks the *left* sibling. The walk-up from there finds a smaller (more local) ancestor than the warm-pointer walk would have. Reparse at that smaller pivot succeeds (filter `span.end == expectedEnd` passes), but the resulting CST differs from a full reparse — typically because the splice operation places the edit on the wrong side of the boundary, or because trivia attribution at the parent-level differs.

**The data:**
- Failing edits include trivia insertions (`\t`, ` `, `\n`) at offsets that land exactly between tokens, AND deletions (5-char, 1-char) over similar boundary positions.
- Hashes differ in the low bits (e.g. `-7458559862016095436` vs `-7458559862044724587`) — small subtree divergence, not wholly-wrong reparse.

**Approach for the proper fix:**
1. Decide whether `NodeIndex.contains` should be half-open (exclusive end) — preferred. Audit every caller; some may rely on the current inclusive semantics.
2. Alternatively, use `smallestContainingFrom(enclosingNode, editStart)` which climbs+descends from the warm pointer (preserving the "warm pointer near edit" optimization) but uses the edit position rather than cursor position as the descent target.
3. After the fix lands: rerun `IncrementalParityTest` (must stay 100/100) AND `IncrementalTriviaParityTest` (must stay 22/22) AND check `singleCharEdit` median drops to the 5-15 ms band predicted by the spike.

**Estimated effort:** 1-2 focused days. Highest-value remaining item — unblocks the IDE plugin.

**Spike doc to read first:** `docs/incremental/V2.5-SPIKE.md`. Confirms NO-GO on the SPEC §5.4 v2.5 cache remap (the dominant cost is pivot overshoot, not cache invalidation) and ranks alternative levers.

### 6.3 Maven Central backfill

The arc from v0.2.3 → v0.4.0 is unpublished. If downstream consumers want any of those versions, publish them on demand — the workflow is identical to what was done for v0.2.2. Don't blanket-publish; wait for explicit demand to avoid versioning churn.

### 6.4 Other levers (per spike doc)

If the 2026-05-08 perf agent's flame graph identifies a Tier-1/2/3 hot spot, follow the spike's ranking:

- **Tier 1**: Phase-2 perf flags from 0.2.2 spec (`inlineLocations`, `markResetChildren`, `selectivePackrat`) — already designed, just need flipping if measurements warrant.
- **Tier 2**: Subtree reuse on stable spans (the actually-clever incremental fix), streaming/window-bounded parsing, rule-level failure caching.
- **Tier 3**: ASCII-whitespace fast path, allocation reduction, char[] vs String.

These are conjectural until the agent's flame graph lands.

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
- **`NodeIndex.contains` is half-inclusive on BOTH ends** (line 149 of NodeIndex.java). This is the lever 1 trap. If you change pivot selection, audit boundary semantics first.
- **`autoPublish=true` + `waitUntil=published` makes Maven Central deploys irreversible.** Once the build's `[INFO] Uploaded bundle successfully` line lands, you cannot un-publish. Be sure before running `mvn deploy -P release`.
- **`Grammar` is a public record.** Its canonical constructor cannot have narrower visibility than the record itself. The `grammar(...)` factory is the documented entry; the constructor is `internal/library use only` per Javadoc but technically still accessible.
- **`peglib-incremental` and `peglib-formatter` cross-reference `peglib-core` types.** When renaming/refactoring core types, search across all 5 modules — not just the test resources.
- **The `%import` flow in `GrammarParser` skips factory validation for grammars with imports** (validation runs after composition in `GrammarResolver`). Don't simplify this — every `GrammarCompositionTest` case relies on it.

## 10. Where to find historical context

- `docs/RELEASE-PLAN-0.3.5-0.4.0.md` — the plan that drove this arc, marked complete through Phase 8.
- `docs/AUDIT-REPORTS/CONSOLIDATED-BACKLOG.md` — the audit findings that drove 0.3.4 cleanup. Most P3 items shipped in 0.4.0; check what's left.
- `docs/PERF-REWORK-SPEC.md` — the 0.2.2 perf rework spec; historical.
- `docs/incremental/SPEC.md` — the 0.3.0-0.3.2 incremental spec. v2 shipped, v2.5 NO-GO'd by spike.
- `docs/bench-results/` — committed JMH JSON from each perf-touching release.

## 11. Recommended next session

1. **Read `docs/incremental/V2.5-SPIKE.md` and `peglib-incremental/.../NodeIndex.java`** — necessary context for lever 1.
2. **Wait for the 2026-05-08 scheduled agent's flame graph** — informs whether lever 1 is still the top priority.
3. **Implement lever 1 properly** — the boundary-semantics fix in `NodeIndex.contains` first, then the pivot-selection swap. Verify with `IncrementalParityTest` (must stay 100/100) and `IncrementalTriviaParityTest` (must stay 22/22).
4. **Ship v0.4.1** — non-breaking. Single commit lever-1 fix + bench-results commit. Per the established release pattern.

If lever 1 turns out to be more involved than 1-2 days, escalate. Don't sink a week into it without consulting; the alternative levers (Phase-2 perf flags, subtree reuse, etc.) may be a faster win depending on what the flame graph shows.

---

**Last updated:** 2026-05-03, after v0.4.0 release + retroactive v0.2.2 Central publish. Handover from the 0.3.5→0.4.0 arc.
