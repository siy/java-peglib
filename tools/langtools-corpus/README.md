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
AGREE_CLEAN           5399
AGREE_REJECT           136
EXCLUDED_ORACLE_OLD     24
FALSE_ACCEPT            71
FALSE_REJECT            36
agreement: 98.10% (5535/5642 scored, 24 excluded)
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

## Triage tips — earned, do not skip

- **`trailing input not consumed` reports where the parser STOPPED**, which for a whole-file
  failure is usually the start of the first type declaration, not the construct at fault. Do not
  cluster on that offset. Bisect to a minimal snippet instead.
- **Do not change the shape `'"""' (!'"""' .)* '"""'`.** `DfaBuilder` pattern-matches it to route
  through `compileDelimitedBlock`; the generic DFA cannot handle `Not(Literal)`. Adding a
  text-block opening-delimiter check broke **226 files** — every text block in the corpus.
- **A lexer rule may not reference another rule.** Factoring a repeated character run into its
  own rule gets rejected at `fromGrammar` with `SkippedRuleReferenced`; spell it inline.
- **Measure after every single change.** Two changes landed together cost a bisection round;
  the run is 6 seconds, so there is no reason to batch.
