# OpenJDK langtools corpus check

Runs the peglib Java grammar over OpenJDK's own javac test suite. This is the
strongest available correctness signal for the grammar: ~3,500 files that
exercise the language exhaustively, including constructs no hand-written corpus
would think to include.

It is **not** wired into the build — it needs a 36 MB external checkout, so it
is an on-demand check, run when the grammar changes materially.

## Running it

```bash
# 1. Fetch the corpus (36 MB, sparse; the full JDK repo is ~1.5 GB)
git clone --depth 1 --filter=blob:none --sparse https://github.com/openjdk/jdk.git /tmp/jdk
cd /tmp/jdk && git sparse-checkout set test/langtools/tools/javac

# 2. Split positive from negative tests
cd <peglib>
python3 tools/langtools-corpus/classify.py \
    /tmp/jdk/test/langtools/tools/javac /tmp/langtools-lists

# 3. Build a classpath and the harness
mvn -q -pl peglib-core dependency:build-classpath \
    -Dmdep.outputFile=/tmp/cp.txt -DincludeScope=runtime
CP="peglib-core/target/classes:peglib-runtime/target/classes:$(cat /tmp/cp.txt)"
javac -cp "$CP" -d /tmp/harness tools/langtools-corpus/LangtoolsRunner.java

# 4. Run (takes about 1.2 s for the whole positive set)
java -cp "$CP:/tmp/harness" -Dout=/tmp/failures.tsv LangtoolsRunner \
    peglib-core/src/test/resources/java25.peg /tmp/langtools-lists/positive.txt positive
```

## Why the split matters

Of 5,667 files, **2,112 are negative tests** — they contain deliberate syntax
errors and javac is expected to reject them. Measuring against the whole set
produces a meaningless number. `classify.py` marks a file negative if it has an
`@compile/fail` directive, a sibling `.out` golden file, or a `compiler.err.`
marker. That leaves **3,555 positive** files, which are the ones that should
parse with zero diagnostics.

## Baseline

As of 2026-08-06, on the 0.7.1 branch: **3,477 / 3,555 clean (97.8%)**, zero
crashes. Treat a drop below that as a regression.

The run that established this baseline found four real gaps, all now fixed and
gated in `JavaCoverageProbe` so this corpus does not have to be re-fetched to
catch a regression: qualified `super` (`A.super.m()`), array initializer `{,}`,
and type annotations before a wildcard (`List<@Ann ?>`), plus receiver
parameters from an earlier probe.

## Known remaining failures (78)

- **32 — comment-only or empty compilation units.** Legal Java; we reject them.
  This is an engine bug, not a grammar gap: the generated `parseWithRecovery`
  emits "empty input" whenever the token stream is all-trivia, without checking
  whether the start rule can match empty. Java's `CompilationUnit` can. Fixing
  it alone reaches roughly 98.7%.
- **~5 - Unicode escapes.** Java translates backslash-u escapes before lexing, so
  a backslash-u0061 followed by `bc` is the identifier `abc`, and backslash-u000a is a
  real line terminator. Needs a preprocessing stage, not a grammar change.
- **~41 — untriaged**, clustering in `patterns`, `generics`, and
  `annotations/typeAnnotations/newlocations`.

## Triage tip

`trailing input not consumed` reports where the parser **stopped**, which for a
whole-file failure is usually the start of the type declaration — not the
construct at fault. Do not cluster on that offset; it wastes time. Bisect to a
minimal snippet instead. That approach found all four gaps in a single pass.
