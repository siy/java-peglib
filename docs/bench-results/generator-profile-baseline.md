# Generator (Throughput Engine) — Profiling Baseline

**Date:** 2026-05-07
**Branch:** release-0.5.0 (post-Lever-D, commit `1e524e2`)
**Bench:** `Java25ParseBenchmark.parse`, variant `phase1_allStructural` (current best gen-time config)
**Fixture:** `large/FactoryClassGenerator.java.txt` (1900 LOC, 101178 chars)
**JVM:** OpenJDK 25.0.2 (Apple Silicon, Homebrew)
**Tools:** JMH 1.37 + async-profiler 4.4 (`/opt/homebrew/lib/libasyncProfiler.dylib`)

## Headline numbers

| Metric | Value |
|---|---:|
| Mean parse time (avgt, 5 iter / 1 fork) | **76.2 ms** ± 20.2 (CI 99.9%) |
| Throughput | 13 ops/sec |
| **Allocation rate** | **1,884 MB/sec** |
| **Allocated per parse (norm)** | **150 MB/op** |
| GC count over bench | 164 across ~10 seconds → ~16/sec |
| GC time as fraction of bench | **26.6%** |

**Allocation is the dominant lever.** 150 MB allocated to parse a 1900-LOC file = ~80 KB per source line = ~800 bytes per character. 26.6% of bench time spent in GC. Algorithmic / micro-optimization wins are a second-order concern compared to reducing allocation pressure.

## Allocation profile (top sites by sample count, async-profiler `-e alloc`)

| Samples | Class |
|---:|---|
| **3,190** | `pragmatica.lang.Option$Some` — Option-boxing (every `Option.option(x)` call) |
| 3,008 | `Object[]` — generic backing arrays (ArrayList, varargs, etc.) |
| **2,574** | `CstParseResult` — per-parse-attempt result record (allocated even when backtracked) |
| **2,367** | `SourceLocation` — position records (line, column, offset) |
| 2,191 | `byte[]` — string backing |
| 1,096 | `SourceSpan` — span records (lazily reconstructed from int triples since 0.4.3) |
| 1,027 | `Long` — boxing |
| 893 | `ArrayList` — children lists, scratch buffers |
| 868 | `String` — various |
| 622 | `HashMap.Node` — packrat cache entries |
| 587 | `CstNode.NonTerminal` — RETAINED CST nodes |
| 553 | `CstNode.Terminal` — RETAINED CST nodes |
| 174 | `HashMap.Node[]` — packrat cache backing |
| 111 | `Trivia.Whitespace` — trivia records |
| 75 | `CstNode.Token` — RETAINED CST nodes |

**Reading this:**
- Top 4 allocators (Option$Some, Object[], CstParseResult, SourceLocation) account for >50% of allocation samples. None of these are user-visible CST output.
- Retained CST = 1,215 records (NonTerminal + Terminal + Token). Allocation profile suggests roughly 3-5× more CST-shaped intermediate records are allocated and discarded during speculative parsing.
- Packrat cache (HashMap.Node + HashMap.Node[]) = 796 samples — meaningful but not dominant.

## CPU profile (top leaf frames, async-profiler `-e cpu`, application frames only)

JIT compilation activity dominates raw samples (the parser is large and the JIT works hard during the 3-iter warmup). After filtering JVM/JIT internals:

| Samples | Frame |
|---:|---|
| 41 | `java.util.HashMap.resize` — packrat cache resize during fill |
| 36 | `java.lang.String.indexOf` — terminator scanning (already an intrinsic) |
| 27 | `java.util.Arrays.copyOf` — backing array growth |
| 25 | `generated.parse_Stmt` — the most-sampled rule body |
| 11 | `generated.parse_Class.skipWhitespace` |
| 11 | `CstParseResult.success` — result construction |
| 11 | `StringUTF16.compress` — string construction inside the parser |
| 10 | `CstParseResult.<init>` — result-record construction |
| 9 | `Option.some` — Option boxing |
| 9 | `HashMap.putVal` — packrat cache writes |

The CPU picture is consistent with the allocation picture: hot code = result-record construction + Option boxing + packrat HashMap operations. Pure parsing CPU (parse_Stmt, skipWhitespace) is only a small fraction of the sampled time.

## Architectural moves, ranked by data-driven payoff

### Tier 1 — design-level, addresses dominant allocation

**A. Eliminate `Option<ParseResult>` boxing on the parse hot path** (~25-30% allocation reduction)
- Top allocator (3,190 Option$Some samples). Every per-attempt success/failure currently wraps in Option.
- Replace with sentinel-based primitive return: parse method returns `int` (matched-end-offset) or `-1` (no-match), with mutable thread-local scratch state for the actual result data.
- Compatible with CST output — only changes how parse-result-flow is plumbed internally.

**B. Mutable parse state replacing per-attempt `CstParseResult` records** (~20-25% allocation reduction)
- 2nd-largest allocator (2,574 CstParseResult samples). Every parse attempt — successful or backtracked — allocates one of these.
- Generator emits a parse method that returns primitive end-offset; on success, mutates a thread-local state struct (current rule's children, trivia accumulator, etc.). On backtrack, the state is rolled back via a checkpoint mechanism.
- This is essentially the "unsafe-generator" idea from HANDOVER §6.4 — but resurrected with a clear single-purpose justification (throughput) and the data to back it.

**C. Lazy CST construction** (~15-20% allocation reduction; CST output identical)
- Currently CstNode records are allocated as we parse; speculative-branch nodes get GC'd on backtrack.
- Change: parser emits a compact **trace** during parsing (16-byte records: rule-id, start, end, child-count). CST materialization is a post-parse pass over the committed trace.
- Backtracking cost shifts from "allocate-and-discard CstNode" to "rewind trace position pointer" — much cheaper.
- Trace-walking pass also opens a clean home for trivia attribution rework (currently embedded in PegEngine + emission templates).

**D. Pack SourceLocation/SourceSpan into primitives** (~15-20% allocation reduction)
- 3,463 combined samples (SourceLocation + SourceSpan). Every `node.span().start()` accessor currently allocates a fresh SourceLocation.
- Store spans as `long[]` aligned by node-id (or as packed-long fields on CstNode records), eliminating the lazy reconstruction path.
- Already partially started in 0.4.3 (SourceSpan as int triples) — finish the job by removing SourceLocation as a record entirely; expose primitive-returning accessors.

### Tier 2 — algorithmic, packrat-focused

**E. Replace HashMap packrat with `long[][]` indexed by `(ruleId, pos)`** (~5-10% CPU + reduced HashMap allocation)
- 41 + 9 CPU samples on HashMap operations; 622 + 174 allocation samples on HashMap nodes/buckets.
- Direct array access; no hashing, no resize. Combined with selective memoization (only memoize rules with profile-confirmed re-entry rate > 1).
- Pre-emit packrat array sizes from grammar analysis: `(numRules × textLen)` long entries.

### Tier 3 — algorithmic, dispatch-focused

**F. First-set Choice dispatch** (10-30% CPU on Java-shaped grammars)
- Existing `choiceDispatch` flag in ParserConfig — investigate what it does already; may be a partial implementation.
- Static FIRST-set analysis at gen-time. For `a / b / c`, emit `switch (text.charAt(pos))` to skip alternatives whose FIRST excludes the current char.

**G. DFA-based lexer for token rules** (2-3× on lex-heavy paths)
- Detect token rules at gen-time; compile to DFA; emit DFA scanner alongside parser.
- Bigger architectural lift (~2-3 weeks); evaluate after Tier 1 lands.

## Recommended sequence

The data points clearly to **allocation-reduction first**, not algorithmic micro-opts. Suggested order:

1. **Investigate the existing `choiceDispatch` flag** — quick read of ParserConfig + ParserGenerator emission. If it's already first-set dispatch, F is partially done; if not, that's a near-term tactical win.
2. **Tier 1 combined**: A + B + C + D land as a coordinated pass. Together they target the top 4 allocators (Option$Some, CstParseResult, SourceLocation, Object[]). Goal: reduce 150 MB/op to <30 MB/op (5× reduction).
3. **Re-profile** after Tier 1 to confirm gains and identify next bottleneck.
4. **Tier 2 (E)** if profile shows packrat still dominant.
5. **Tier 3 (F, G)** based on remaining hot paths.

## Caveats

- **JIT warmup**: 3 warmup iterations may be insufficient; CPU profile shows JIT compilation activity dominating sample count. Production runs (not bench) will see fewer compile samples and proportionally more parse samples.
- **150 MB/op figure includes all allocations** — JIT compilation, JMH harness, GC structures. Application allocation rate is somewhat lower; the relative breakdown remains directionally accurate.
- **Sample-based profiling**: async-profiler samples; not exhaustive accounting. Top-N rankings are reliable; absolute byte counts in the table are sample counts, not bytes.
- **`phase1_allStructural` is already the best of 7 gen-time variants.** The "none" baseline (all gen-time flags off) would show worse numbers. Worth running for a comparison if we want to verify Phase 1 flags' contribution.

## Files

- Flame graphs (HTML, viewable in browser):
  - `/tmp/peglib-profile/.../flame-cpu-{forward,reverse}.html`
  - `/tmp/peglib-profile/.../flame-alloc-{forward,reverse}.html`
- Collapsed text profiles for analysis:
  - `/tmp/peglib-profile-collapsed/.../collapsed-alloc.csv` (~67 MB)
  - `/tmp/peglib-profile-cpu/.../collapsed-cpu.csv`

These artifacts are **not committed** — `/tmp` only. Re-run via the commands at the top of this doc.
