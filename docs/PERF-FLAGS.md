# Performance Flags Reference

Peglib's `ParserConfig` carries ten boolean performance flags consumed at
**generator time**. They select which helper variant `ParserGenerator`
emits; they do **not** produce runtime `if (flag)` branches in the
generated output. A given `(grammar, ParserConfig)` pair produces
deterministic, byte-identical Java source.

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
| `choiceDispatch` | `true` | For `Choice` whose alternatives are all literal-prefixed, emit a `switch (input.charAt(pos))` dispatch that narrows to the subset of alternatives starting with the current char. Falls through to the general backtracking chain for alternatives without a fixed prefix. **Default flipped on in 0.2.2 based on a 2.49× measured speedup over the phase-1 baseline.** |
| `markResetChildren` | `false` | Replace `children` clone + `clear` + `addAll` in `Choice` with mark-and-trim (`children.subList(mark, size()).clear()`). O(1) mark; O(delta) reset. No statistically significant individual win on the reference JVM. |
| `inlineLocations` | `false` | Introduce per-rule `int startOffset` / `startLine` / `startColumn` locals and materialize `SourceLocation` only at span boundaries. No statistically significant individual win on the reference JVM. |
| `selectivePackrat` | `false` | Skip packrat cache for rules listed in `packratSkipRules`. Marginal combo win (~5%) sits inside measurement noise on the reference workload. Callers must supply `packratSkipRules` or the flag is a no-op. |

### `packratSkipRules`

`Set<String>` — unsanitized grammar rule names whose emitted CST method
should skip packrat cache lookups and stores. Only consulted when
`selectivePackrat` is `true`. Treated as **immutable** by the generator;
callers constructing it from a mutable source should wrap with
`Set.copyOf(...)` to prevent external mutation from changing generated
output. Default: `Set.of()`.

## When to flip defaults

All three default-off flags live behind a custom `ParserConfig`:

```java
var config = new ParserConfig(
    true,                         // packratEnabled
    RecoveryStrategy.BASIC,       // recoveryStrategy
    true,                         // captureTrivia
    true, true, true, true, true, true,  // phase-1 (keep on)
    true,                         // choiceDispatch (keep on)
    true,                         // markResetChildren (flip on)
    true,                         // inlineLocations (flip on)
    true,                         // selectivePackrat (flip on)
    Set.of("Identifier", "QualifiedName", "Type"));  // skip set

Result<String> source = PegParser.generateCstParser(
    grammarText, "com.example", "MyParser",
    ErrorReporting.BASIC, config);
```

Guidance:

- **`markResetChildren` / `inlineLocations`** — try on grammars with many
  alternatives per choice or with span-heavy rules. Re-benchmark against
  your workload; the reference fixture shows no individual win.
- **`selectivePackrat`** — gather per-rule cache hit ratios first
  (`PackratStatsProbe` at `src/jmh/java/org/pragmatica/peg/bench/PackratStatsProbe.java`).
  Rules with `hits/puts < 0.1` are candidates. Only sound for rules
  that are not left-recursive.

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
