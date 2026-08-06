# OpenJDK langtools corpus check

Differences the peglib Java grammar against **javac's own parse phase** over OpenJDK's javac
test suite — ~5,700 files that exercise the language exhaustively, including constructs no
hand-written corpus would think to include.

It is **not** wired into the build: it needs a 36 MB external checkout and a JDK with
`jdk.compiler`. Run it on demand when the grammar changes materially. The in-build gates are
`JavaCoverageProbe` (must-accept), `JavaRejectionProbe` (must-reject) and `ModernJavaSyntaxProbe`
(post-Java-25 syntax); every fix found here should land as a case in one of them so this corpus
does not have to be re-fetched to catch a regression.

## Why javac is the oracle

`JavacTask.parse()` runs the scanner and parser and nothing else — no enter, no attribute, no
flow. Its ERROR diagnostics are therefore exactly the syntax errors, which is the only question
a parser can be held to.

This replaced an earlier heuristic that split the corpus into "positive" and "negative" by
grepping for `@compile/fail`, a sibling `.out` file, or a `compiler.err.` marker. **That split
was hiding real bugs.** A file marked negative because it contains `compiler.err.cant.resolve`
is a *semantic* failure — javac's parser accepts it, and so should we. Measuring only the
"positive" set reported 78 failures when the true count was 155: roughly 77 genuine grammar gaps
were being scored as expected failures. The oracle labels every file uniformly and does not
guess.

Four verdicts:

| verdict | meaning |
|---|---|
| `AGREE_CLEAN` | both accept |
| `AGREE_REJECT` | both reject — the negative set working |
| `FALSE_REJECT` | we error, javac is clean → a grammar or engine gap |
| `FALSE_ACCEPT` | we are clean, javac reports a syntax error → grammar too permissive |
| `EXCLUDED_ORACLE_OLD` | peglib is intentionally ahead of the oracle (see below) |

## Running it

```bash
# 1. Fetch the corpus (36 MB sparse; the full JDK repo is ~1.5 GB)
git clone --depth 1 --filter=blob:none --sparse https://github.com/openjdk/jdk.git /tmp/jdk
cd /tmp/jdk && git sparse-checkout set test/langtools/tools/javac

# 2. Build a classpath (absolute — the harness is run from elsewhere)
cd <peglib>
mvn -q -pl peglib-core dependency:build-classpath \
    -Dmdep.outputFile=/tmp/cp.txt -DincludeScope=runtime
R=$(pwd)
echo "$R/peglib-core/target/classes:$R/peglib-runtime/target/classes:$(cat /tmp/cp.txt)" > /tmp/abscp.txt

# 3. Compile the harnesses
javac -nowarn -cp "$(cat /tmp/abscp.txt)" -d /tmp/harness \
    tools/langtools-corpus/OracleRunner.java tools/langtools-corpus/Snip.java

# 4. Run the differential (~6 s for all 5,666 files)
java -cp "$(cat /tmp/abscp.txt):/tmp/harness" -Dout=/tmp/oracle.tsv \
    OracleRunner peglib-core/src/test/resources/java25.peg /tmp/jdk/test/langtools/tools/javac
```

`Snip.java` probes one file: `java -cp ... Snip <grammar.peg> <file.java>` prints the diagnostic
count, CST node count, and whether `reconstruct()` round-trips byte-identically.

## Baseline

As of 2026-08-06 on `release-0.7.1`:

```
AGREE_CLEAN           5406
AGREE_REJECT           139
EXCLUDED_ORACLE_OLD     24
FALSE_ACCEPT            68
FALSE_REJECT            29
agreement: 98.28% (5545/5642 scored, 24 excluded)
```

Treat a drop below that as a regression. **Re-run after every grammar change** — each
false-accept fix tightens the grammar and can create new false rejects; several candidate fixes
were backed out precisely because the differential showed them net-negative.

## Waivers — cases we deliberately do not chase

Reaching 100% agreement is **not** the goal, because javac's parser enforces things that are not
context-free. Encoding them would make the grammar worse without making it more correct.

**Oracle too old (24 files, auto-excluded).** JEP 401 value classes (`value class X { }`) are a
shipped 0.7.0 feature targeting JDK 28 preview. A javac older than that cannot parse them at any
flag setting. `OracleRunner.oracleTooOld` excludes these and reports them separately. Do not
"fix" them by removing value-class support — that would regress a shipped feature and six
`ModernJavaSyntaxProbe` cases. When the oracle JDK gains value classes, delete the exclusion and
they should become `AGREE_CLEAN` on their own.

**Not context-free (waived).** Numeric range (`int.number.too.large`, `fp.number.too.large`) —
a literal's *magnitude* is not a property of its shape. Duplicate modifiers (`repeated.modifier`)
— a no-duplicates-in-list constraint is combinatorial in PEG. Filename agreement
(`bad.file.name`) — unrelated to the token stream.

**Borderline, disproportionate cost (waived).** `invalid.permits.clause` (needs `sealed` threaded
from a sibling repetition), `invalid.meth.decl.ret.type.req` (constructor-vs-method needs
comparing an identifier to the enclosing class *name*, not a shape), and multi-surrogate char
literals (needs Java's phase-1 `\uXXXX` translation).

**javac's parser is inconsistent here (learned the hard way).** JLS 15.10.1 forbids array
creation with a parameterized element type, but javac's *parser* only rejects some forms and
defers the rest to Attr. A JLS-correct rule scored +6/−3 — a wash — while adding three grammar
rules and making the formatter refuse input javac's parser accepts. Backed out. Note that
`new Class<?>[0]` is **legal**: an unbounded wildcard is reifiable. A rule banning all type
arguments there breaks 12 real files.

The same asymmetry costs 2 files on the restricted-type-name rule (JLS 3.9): we reject `var` in
type-use position, which is JLS-correct, but javac's parser accepts `var<String> m()` and
`case Foo(var(var x, var y))` and defers to Attr. Net +3, so the rule stays and those 2 are
expected disagreements.

The same applies to `BadLambdaPos.java`. JLS 15.27 makes a lambda an alternative of *Expression*,
not something reachable from a Primary operand, so `test((int x)-> { } + (int x)-> { })` is not a
legal expression — javac's parser accepts it and rejects it in Attr. Enforcing the JLS costs that
one pathological negative test and buys correct parsing of every switch guard ending in a bare
identifier (`case Integer i when i == j ->`), which previously produced 35 diagnostics on a
four-line file. Corpus-neutral, unambiguously correct.

## Blocked on an engine bug: per-container class bodies

Interface and record bodies are over-accepted — `interface I { int X; }` (a field with no
initializer), `interface I { { } }`, `record R(int x) { int y; }` (instance field) and
`record R(int x) { { } }` all parse today and should not. Together that is ~8 corpus files and
the largest remaining false-accept cluster. **Both attempts were made, measured as clean wins
(+2 and +4, zero false-reject collateral), and then reverted. Do not simply retry them.**

Two distinct obstacles, in order:

1. **The JEP 512 fallback silently absorbs a malformed record.** When `RecordDecl` fails,
   `OrdinaryUnit`'s `TopLevelMember` alternative re-parses `record R(int x) { int y; }` as a
   *method named `R` returning type `record`*, consuming the whole declaration and reporting zero
   diagnostics with a wrong-shaped CST. Verified by deleting `TopLevelMember`, after which the
   same input correctly reports 10 diagnostics. A `!RecordKW` guard on `TopLevelMember` and
   `Member` fixes the masking (and is JLS-correct: `record` is a restricted identifier that can
   never be a type name). This masking is general — any failure inside a top-level type
   declaration can be swallowed this way, which is a CST-shape hazard beyond records.

2. **The blocker: a failed alternative leaves partial CST content.** With either the interface
   split or the record guard in place, `FormatterCorpusGateTest` fails on
   `MultilineArguments.java` and `MultilineParameters.java` with a DUPLICATED token —
   `expected='public' actual='publicpublic'`, 674 → 683 non-trivia tokens. Adding an alternative
   that fails after consuming modifiers leaves those modifiers in the CST, and the next
   alternative appends them again. Spelling the guard without a group node changes nothing, so
   it is not a grouping artifact. The same footgun is already noted inline at `DimExprs`
   ("lookahead BEFORE consuming '[' prevents java-peglib bug where failed iterations leave
   partial matches"), where it was worked around rather than fixed.

So this is an **engine prerequisite, not a grammar task**: fix truncation-on-alternative-failure
in `CstArrayBuilder`/`ParserGenerator` first, then both body rules land together for ~+6.

Note what caught it: the grammar change passed all 333 `peglib-core` tests. Only the full
reactor build fails, because the round-trip guarantee it breaks lives in `peglib-formatter`.
**Run `mvn install`, not `mvn -pl peglib-core test`, before committing a structural grammar
change.**

## Triage tips — earned, do not skip

- **`trailing input not consumed` reports where the parser STOPPED**, which for a whole-file
  failure is usually the start of the first type declaration, not the construct at fault. Do not
  cluster on that offset. Bisect to a minimal snippet instead.
- **Do not change the shape `'"""' (!'"""' .)* '"""'`.** `DfaBuilder` pattern-matches it to route
  through `compileDelimitedBlock`; the generic DFA cannot handle `Not(Literal)`. Adding a
  text-block opening-delimiter check broke **226 files** — every text block in the corpus.
- **A lexer rule may not reference another rule.** Factoring a repeated character run into its
  own rule gets rejected at `fromGrammar` with `SkippedRuleReferenced`; spell it inline.
- **A lexer rule that is too permissive can desync the whole file.** `NumLit`'s leading-dot
  alternative was `< '.' [0-9_]+ ... >`; since `_` is in `[0-9_]`, `t._field` lexed `._` as one
  2-char token, which beat the 1-char `.` on maximal munch. One bad token turned into
  "trailing input not consumed" for the entire file. When a whole-file failure makes no sense,
  suspect the token stream before the parser rules — the diagnostic's `found=` field names the
  offending token text.
- **Measure after every single change.** Two changes landed together cost a bisection round;
  the run is 6 seconds, so there is no reason to batch.
