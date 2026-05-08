# Performance Flags Reference

Peglib's `ParserConfig` carries twelve boolean performance flags consumed at
**generator time**. They select which helper variant `ParserGenerator`
emits; they do **not** produce runtime `if (flag)` branches in the
generated output. A given `(grammar, ParserConfig)` pair produces
deterministic, byte-identical Java source.

> **0.5.0 update:** `inlineLocations`, `selectivePackrat`, and `tokenFastPath`
> are now default `true`. `mutableParseResult` was added as an opt-in flag
> (default `false`) — see [`THROUGHPUT-ENGINE-MOVE-B.md`](incremental/THROUGHPUT-ENGINE-MOVE-B.md)
> for why the singleton-mutation extension on top of it was abandoned.

For the design rationale behind each flag — including the patches
originally prototyped and the measured wins — see
[`PERF-REWORK-SPEC.md`](PERF-REWORK-SPEC.md) §§6–7. This document is a
tabular quick-reference; the spec is the source of truth.

## Scope

- **CST emission only** (`ParserGenerator.generateCst(...)`). The
  action-bearing non-CST path (`ParserGenerator.generate(...)`) is
  unaffected by all ten flags.
- Flags do **not** apply to the interpreter (`PegEngine`). The
  interpreter unconditionally runs all six phase-1 optimizations as of
  0.2.3 (see [CHANGELOG 0.2.3](../CHANGELOG.md#023---2026-04-22)); the
  flag-gated dual-path model is a generator-only concept.

## Flags

### Phase 1 — localized helper rewrites (all default `true`)

Ported from a validated offline prototype; small, bounded changes inside
`matchLiteralCst` / `matchCharClassCst` / `trackFailure` /
`skipWhitespace` / success-path helpers. Combined measured win on the
1,900-LOC Java 25 fixture: 1.70× over unflagged baseline.

| Flag | Default | Optimization |
|---|---|---|
| `fastTrackFailure` | `true` | Skip `SourceLocation` allocation in `trackFailure` when current `pos` is dominated by the furthest-seen failure offset (~90% of calls during backtracking). Equality case handled without allocation. |
| `literalFailureCache` | `true` | Per-parser-instance `HashMap<String, CstParseResult>` caches literal-match failure results. Eliminates `CstParseResult` / `Option` allocation on the failure path after warmup. Also emits two specialized match loops (one per `caseInsensitive` value) so JIT can hoist the check. |
| `charClassFailureCache` | `true` | Analogous per-instance cache keyed on `negated ? "^" + pattern : pattern`. Cached entries carry the bracketed expected label (`"[...]"` / `"[^...]"`). Unifies previous split "character" / "character class" / bracketed labels to the bracketed form. |
| `bulkAdvanceLiteral` | `true` | On successful literal match with no `\n` in the text, bulk-update `pos` and `column` rather than calling `advance()` per char. Falls back to the per-char loop when `\n` is present. |
| `skipWhitespaceFastPath` | `true` | First-char precheck derived at generator time from the grammar's `%whitespace` rule. Returns shared empty `List.of()` when the current char can't start trivia; avoids `ArrayList<Trivia>` setup and mini-PEG loop on the common case. |
| `reuseEndLocation` | `true` | Allocate the end-position `SourceLocation` once per successful terminal match and reuse it for both the span end and the `CstParseResult.endLocation`. |

### Phase 2 — structural rewrites

Bigger-impact but bigger-risk structural changes. Individually gated so
any regression can be bisected to a single flag.

| Flag | Default | Optimization |
|---|---|---|
| `choiceDispatch` | `true` | First-set `switch (input.charAt(pos))` dispatch for Choice. Originally literal-prefix only (0.2.2); **extended in 0.5.0** to full transitive FIRST-set computation covering CharClass + Reference (recursive) + mixed dispatch. On Java25, 62/64 of choices now dispatch via switch. **-20% wallclock on both fixtures vs phase-1 baseline.** |
| `markResetChildren` | `false` | Replace `children` clone + `clear` + `addAll` in `Choice` with mark-and-trim (`children.subList(mark, size()).clear()`). O(1) mark; O(delta) reset. No statistically significant individual win on the reference JVM. |
| `inlineLocations` | `true` (since 0.5.0) | Per-rule `int startOffset` / `startLine` / `startColumn` locals; materialize `SourceLocation` only at span boundaries. Default flipped on in 0.5.0 after the emission-template sweep that eliminated SourceLocation/SourceSpan allocations on the rule-entry path. |
| `selectivePackrat` | `true` (since 0.5.0) | Skip packrat cache for rules listed in `packratSkipRules`. **0.5.0 semantics:** when `packratSkipRules` is empty, the generator auto-derives the skip-set via `PackratAnalyzer.autoSkipPackratRules(grammar)` — leaf-like rules and single-call-site rules without quantifiers bypass the cache; left-recursive rules are excluded. Pass a non-empty explicit set to override. **Biggest single win in the 0.5.0 throughput-engine arc: -38% reference / -14% self-host wallclock; -75% gc.count.** |
| `tokenFastPath` | `true` (since 0.5.0) | DFA fast-path scanner for token-shaped rules: detects `< CharClass + ZeroOrMore<CharClass> >` (Identifier-shape) and emits a tight inline scanner with pre-computed ASCII bitmasks instead of going through `matchCharClass` per character. **-9.8% reference / -7.6% self-host wallclock.** |
| `mutableParseResult` | `false` (opt-in) | Emits a mutable `CstParseResult` class with raw nullable fields (no `Option<Object> value` / `Option<String> expected` wrappers) plus raw-nullable `furthestFailure` / `furthestExpected` / `pendingFailureRecoveryOverride`. Eliminates Option boxing on every result construction. The Move B extension that replaced per-call records with a heap-bound singleton was attempted and abandoned; see [`THROUGHPUT-ENGINE-MOVE-B.md`](incremental/THROUGHPUT-ENGINE-MOVE-B.md) for the post-mortem. |

### `packratSkipRules`

`Set<String>` — unsanitized grammar rule names whose emitted CST method
should skip packrat cache lookups and stores. Only consulted when
`selectivePackrat` is `true`. Treated as **immutable** by the generator;
callers constructing it from a mutable source should wrap with
`Set.copyOf(...)` to prevent external mutation from changing generated
output. Default: `Set.of()`.

## When to flip defaults

As of 0.5.0 most optimizations default on. The two remaining default-off /
opt-in flags (`markResetChildren`, `mutableParseResult`) live behind a custom
`ParserConfig`:

```java
var config = new ParserConfig(
    true,                         // packratEnabled
    RecoveryStrategy.BASIC,       // recoveryStrategy
    true,                         // captureTrivia
    true, true, true, true, true, true,  // phase-1 (keep on)
    true,                         // choiceDispatch (keep on)
    true,                         // markResetChildren (flip on if measured)
    true,                         // inlineLocations (default on since 0.5.0)
    true,                         // selectivePackrat (default on since 0.5.0)
    Set.of(),                     // packratSkipRules — leave empty to auto-derive
    true,                         // mutableParseResult (opt-in; -8.5% wallclock per Move A spike)
    true);                        // tokenFastPath (default on since 0.5.0)

Result<String> source = PegParser.generateCstParser(
    grammarText, "com.example", "MyParser",
    ErrorReporting.BASIC, config);
```

Guidance:

- **`markResetChildren`** — try on grammars with many alternatives per choice
  or with span-heavy rules. Re-benchmark against your workload; the reference
  fixture shows no individual win. Still default off.
- **`selectivePackrat`** — defaults on with auto-derivation. To override the
  auto-derived skip-set, pass an explicit non-empty `packratSkipRules`.
  Gather per-rule cache hit ratios with `PackratStatsProbe` at
  `peglib-core/src/jmh/java/org/pragmatica/peg/bench/PackratStatsProbe.java`
  if you want to compare. Rules with `hits/puts < 0.1` are candidates. Only
  sound for rules that are not left-recursive.
- **`mutableParseResult`** — opt-in for users targeting the throughput engine's
  full perf envelope. Eliminates Option boxing on every parse-method result.
  The Move B singleton extension on top of this flag was attempted and
  abandoned (modern JIT escape analysis already scalar-replaces the per-call
  records); see [`THROUGHPUT-ENGINE-MOVE-B.md`](incremental/THROUGHPUT-ENGINE-MOVE-B.md).

## Measured reference numbers

Reference workload: `src/test/resources/perf-corpus/large/FactoryClassGenerator.java.txt`
(1,900 LOC real-world Java 25). JMH 1.37, average-time mode, 3 warmup × 2s,
5 measurement × 2s, 2 forks, JDK 25.0.2, Apple Silicon. Raw data:
[`docs/bench-results/java25-parse.json`](bench-results/java25-parse.json),
[`docs/bench-results/java25-parse.log`](bench-results/java25-parse.log).

| Variant | ms/op (± CI) | vs `none` | vs `phase1` |
|---|---|---|---|
| `none` — all flags off | 425.6 ± 62.2 | 1.00× | 0.59× |
| `phase1` — phase-1 on, phase-2 off | 250.2 ± 31.5 | 1.70× | 1.00× |
| `phase1 + choiceDispatch` (`DEFAULT`) | **100.7 ± 32.6** | **4.23×** | **2.49×** |
| `phase1 + markResetChildren` | 290.3 ± 51.7 | 1.47× | 0.86× (noise) |
| `phase1 + inlineLocations` | 307.6 ± 47.9 | 1.38× | 0.81× (noise) |
| `phase1 + all structural` | 116.5 ± 20.8 | 3.65× | 2.15× |
| `phase1 + all structural + selectivePackrat` | 110.7 ± 32.6 | 3.85× | 2.26× |

To run the full matrix on your own workload or JVM, see
[`BENCHMARKING.md`](BENCHMARKING.md).

## Parity guarantees

Every permutation of the ten flags produces a CST byte-identical to the
all-off baseline on the full 22-file corpus. Enforced by:

- `CorpusParityTest` — all-off → all-phase-1-on parity
- `Phase1ParityTest` — individual phase-1 flag parity
- `Phase2ChoiceDispatchParityTest`, `Phase2MarkResetChildrenParityTest`,
  `Phase2InlineLocationsParityTest`, `Phase2SelectivePackratParityTest`,
  plus combined-flag parity variants
- `GeneratorFlagInertnessTest` — no emitted change when a flag is toggled
  on a grammar the flag doesn't apply to

If a parity test fails on your grammar, file an issue with the grammar
and the flag combination that diverges; the promise is strict CST
identity, not approximate.
