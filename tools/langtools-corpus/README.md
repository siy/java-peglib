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
AGREE_CLEAN           5425
AGREE_REJECT           186
EXCLUDED_ORACLE_OLD     24
FALSE_ACCEPT            21
FALSE_REJECT            10
agreement: 99.45% (5545/5642 scored, 24 excluded)
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

**Unicode escapes are implemented** (JLS 3.3), not waived. `\uXXXX` is substituted before
lexing by `UnicodeEscapes.translate`, so `\u0069f` really is the keyword `if` and `\u000a`
really does end a `//` comment. Token spans are then pushed back onto the ORIGINAL text via
`TokenArray.remapOffsets`, so `reconstruct()` still returns exactly what the user wrote — the
formatter's byte-identical round-trip depends on that and is asserted in
`UnicodeEscapeTranslationTest`. Sources without an escape pay one substring scan and are lexed
unchanged. Two rules that are easy to get wrong: a backslash starts an escape only when
preceded by an EVEN number of backslashes (`\\u0041` is not an `A`), and any number of `u`s
may follow. Note the pre-pass lives in the `Parser` facade, so a generated parser driven
directly from its own `GLexer` does not get it.

**Borderline, disproportionate cost (waived).** `invalid.permits.clause` (needs `sealed` threaded
from a sibling repetition), `invalid.meth.decl.ret.type.req` (constructor-vs-method needs
comparing an identifier to the enclosing class *name*, not a shape), and multi-surrogate char
literals (needs Java's phase-1 `\uXXXX` translation).

**javac's parser is inconsistent on array creation.** JLS 15.10.1 forbids a parameterized
element type, but javac's *parser* rejects only the diamond and explicit-type-argument forms and
defers `new ArrayList<T>[…]` to Attr. The JLS-correct rule is in place and costs exactly three
permanent disagreements — `GenericArrayCreation.java`, `BarNeg1.java`, `BarNeg2.java` — against
six correct rejections. Two traps: `new Class<?>[0]` is **legal** (an unbounded wildcard is
reifiable), and a rule banning all type arguments there breaks 12 real files; and this only
became worth landing once other fixes were in, so measure the combination, not the rule alone.

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

## Per-container class bodies — landed, and why it looked like an engine bug

Interface and record bodies now have their own rules: an interface field requires an
initializer and admits no initializer blocks or constructors (JLS 9.1.4); a record admits no
instance fields and no instance initializer blocks (JLS 8.10.4).

Two things had to be understood first, and the second one cost a wrong diagnosis:

1. **The JEP 512 fallback silently absorbs a malformed record.** When `RecordDecl` fails,
   `OrdinaryUnit`'s `TopLevelMember` alternative re-parses `record R(int x) { int y; }` as a
   *method named `R` returning type `record`*, consuming the whole declaration and reporting
   zero diagnostics with a wrong-shaped CST. Verified by deleting `TopLevelMember`, after which
   the same input correctly reports 10 diagnostics. The `!RecordKW` guard on `TopLevelMember`
   and `Member` closes it, and is JLS-correct: `record` is a restricted identifier that can
   never be a type name. **This masking is general** — any failure inside a top-level type
   declaration can be swallowed this way. It is a live CST-shape hazard for other constructs.

2. **`FormatterCorpusGateTest` failed with a DUPLICATED token** (`expected='public'
   actual='publicpublic'`, 674 → 683 non-trivia tokens), which looks exactly like a
   backtracking bug in `CstArrayBuilder.truncate`. It is not. Two corpus fixtures,
   `MultilineArguments.java` and `MultilineParameters.java`, contained **invalid Java**:

   ```java
   interface ValidRequest { ValidRequest(Object... args) {} }   // constructor in an interface
   ```

   javac's own parser rejects that with `<identifier> expected`; peglib accepted it only
   because interfaces shared `ClassBody`. The correct rule made those files fail to parse, and
   the duplication was panic-mode RECOVERY re-covering the skipped range — a downstream symptom,
   not the cause. Both fixtures instantiate the types with `new ValidRequest(...)`, so they were
   always meant to be classes; changing `interface` to `class` fixes them and all 20 fixtures
   now agree with javac-parse at 100%.

   The lesson: a duplicated token in formatter output means **the parse failed**, not that
   backtracking leaked. Check the diagnostic count before suspecting `truncate`.

**The langtools differential did not catch this** — it stayed at 5406 AGREE_CLEAN throughout,
because the broken files live in peglib's own formatter corpus, not in langtools. Run
`mvn install`, not `mvn -pl peglib-core test`, before committing a structural grammar change,
and consider running `OracleRunner` over `perf-corpus/format-examples` as well:

```bash
java -cp "$(cat /tmp/abscp.txt):/tmp/harness" OracleRunner <grammar> \
    peglib-core/src/test/resources/perf-corpus/format-examples
```

## Non-ASCII identifiers — landed, deliberately at a small corpus cost

`int café = 1;` now parses. This required fixing a real engine bug first.

**The engine bug.** `GrammarLexer` preserves the full escape text inside a character class
(`\x20`, `é`) so `DfaBuilder` can decode it, but `parseCharClassPattern` read only the
single character after the backslash. `[\x20]` became the three members `'x'`, `'2'`, `'0'`.
The failure was disguised: `[\x61-\x7a]` appeared to work because the leftover `'-'` formed the
bogus range `'-'..'x'`, which happens to cover the lowercase letters it was meant to match.
Negated classes had no such luck and matched nothing at all — which is why the first attempt at
this fix broke even `class A { int x = 1; }`. Fixed by `decodeEscapeValueAt` / `escapeEndAt`;
pinned by `CharClassHexEscapeTest`, which asserts the positive and negated forms agree with
their literal equivalents. **On its own the fix is corpus-neutral** — the Java grammar used no
hex escapes until now.

**The identifier change.** The DFA alphabet is 0..255 plus one non-ASCII slot per state, emitted
for `.` and for NEGATED classes (widening the alphabet to full Unicode is explicitly out of
bounds). So the identifier sets are spelled as negations of the ASCII characters an identifier
may not contain, which picks up every non-ASCII codepoint for free.

**This is a deliberate trade, scored −2 on the corpus.** It fixes `SupplementaryJavaID1`,
`SupplementaryJavaID6` and `UncommonParamNames`, and it over-accepts `SupplementaryJavaID2`–`5`,
which use supplementary characters that are NOT valid identifier characters
(`compiler.err.illegal.char`). Distinguishing those needs Unicode character categories, which a
256-way DFA cannot express. The trade was taken because a formatter that cannot parse `café`
is broken for real codebases, whereas one that accepts an emoji as an identifier merely fails
to reject code that does not compile anyway. Corpus parity is a proxy for correctness, not the
goal — do not "fix" this by reverting to ASCII-only identifiers.

## The remaining 37 disagreements, and why

Reviewed file by file. Roughly half are permanent by design; the rest are individually
expensive. **99.34% is close to the practical ceiling; ~19 of the 37 will never close.**

**Permanently waived — not context-free (8 false accepts).** Numeric magnitude
(`int i = 12345678901234567890`, `1e9999`, `1e-9999`), duplicate modifiers
(`private private`), filename vs class name, and `m() { }` (needs comparing the method name to
the enclosing class name). All require semantic state a grammar does not have.

**Permanently waived — deliberate trades (11).** Four `SupplementaryJavaID` files are the cost
of non-ASCII identifier support (see above). Seven false rejects are cases where peglib is
JLS-correct and javac's parser defers to Attr: `new ArrayList<T>[]`, `BarNeg1/2`,
lambda-as-operand, `var<String>`, `var` in a deconstruction pattern, and `package` in a modular
unit. Matching javac on these would make the grammar LESS correct.

**Blocked on one engine change (4).** A bad text-block open delimiter, a bad escape inside a
text block, and two files whose text blocks contain an escaped triple quote. All need
`compileDelimitedBlock` to accept a richer pattern than the fixed delimited-block shape — see
the trap note below.

**javac corner cases, low value (2).** `UncommonParamNames` declares parameters whose names
contain NUL and BEL characters, written as Unicode escapes. That is legal because
`Character.isIdentifierIgnorable` covers control characters. `UnicodeBackslash` is similar.

**Individually tractable, one file each (~12).** `yield(1, 2)` disambiguation (needs to know
whether it is inside a switch expression), qualified `new` with a qualified type name, a bare
qualified `super` with no trailing member, type arguments on a select, `int i = 08` (octal digit
— fiddly numeric surgery with real risk to float literals), `permits` without `sealed`, and
annotations in record patterns (attempted once and reverted: it also rejected a legal annotated
local declaration). Two untriaged: `TextBlockLang`, `BadTypeReference`.

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
- **PEG repetition is POSSESSIVE, and it bites in non-obvious places.** `Modifier* StaticKW`
  can never match. `Primary PostOp* CallOp` can never match. `PostOp`'s member access spells
  its call as `('(' Args? ')')?`, so it swallows `.foo()` whole and nothing downstream can see
  the call. The fixes are a `!Guard` prefix (`(!StaticKW Modifier)* StaticKW`), right-recursion
  (`Chain <- Op Chain / Terminal`), or a variant rule that stops short. Expect to need one of
  these whenever a rule must constrain what comes LAST.
- **`RuleClassifierTest` bounds how many MIXED rules the grammar may have.** A rule that
  references other rules AND contains a character class is MIXED; an inline token-boundary form
  like `< 'this' ![a-zA-Z0-9_$] >` is enough to trigger it. Duplicating such a form into a second
  rule pushes the count up and fails that test. Factor the shared form into its own rule instead
  of raising the bound — that is what `ReceiverParam` exists for.
- **Measure after every single change.** Two changes landed together cost a bisection round;
  the run is 6 seconds, so there is no reason to batch.
