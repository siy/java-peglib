package org.pragmatica.peg.analyzer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.pragmatica.peg.PegParser;
import org.pragmatica.peg.analyzer.Finding.Severity;
import org.pragmatica.peg.grammar.GrammarParser;

import static org.assertj.core.api.Assertions.assertThat;

/** Issue #48. Each finding is paired with the parse that shows it is true, or with a legal near-miss. */
class CharLevelChecksTest {
    private static final Set<String> TAGS = Set.of("grammar.char-class-no-op",
                                                   "grammar.char-class-never-matches",
                                                   "grammar.char-class-whole-token",
                                                   "grammar.whitespace-lookahead",
                                                   "grammar.whitespace-literal");
    private static final String TAIL = "M <- '(' Num ')'\nId <- [a-z]+\nNum <- [0-9]+\n%whitespace <- [ \\t\\r\\n]+\n";

    private static List<Finding> findings(String grammar) {
        return Analyzer.analyze(GrammarParser.parse(grammar).unwrap())
                       .findings()
                       .stream()
                       .filter(f -> TAGS.contains(f.tag()))
                       .toList();
    }

    private static void assertOnly(String grammar, Severity severity, String tag, String rule, int line) {
        assertThat(findings(grammar)).singleElement()
                                     .satisfies(f -> {
                                         assertThat(f.tag()).isEqualTo(tag);
                                         assertThat(f.severity()).isEqualTo(severity);
                                         assertThat(f.ruleName()).isEqualTo(rule);
                                         assertThat(f.line()).isEqualTo(line);
                                     });
    }

    private static boolean accepts(String grammar, String input) {
        return PegParser.fromGrammar(grammar)
                        .unwrap()
                        .parse(input)
                        .isSuccess();
    }

    @Test
    void requiredWhitespaceClassInMixedRule_neverMatches_isAnError() {
        var grammar = "Start <- T ';'\nT <- < ('double'i [ \\t\\r\\n]+ 'precision'i / 'float8'i) > M?\n" + TAIL;

        assertThat(accepts(grammar, "double precision;")).isFalse();
        assertOnly(grammar, Severity.ERROR, "grammar.char-class-never-matches", "T", 2);
    }

    @Test
    void optionalWhitespaceClassInMixedRule_isHarmless_soOnlyAWarning() {
        var grammar = "Start <- T ';'\nT <- 'a' [ \\t]* 'b' M?\n" + TAIL;

        assertThat(accepts(grammar, "a b;")).isTrue();
        assertOnly(grammar, Severity.WARNING, "grammar.char-class-never-matches", "T", 2);
    }

    @Test
    void requiredClassInsideAnOptionalGroup_killsTheGroup_soStaysAnError() {
        var grammar = "Start <- T ';'\nT <- 'a' ([ \\t]+ 'b')? M?\n" + TAIL;

        assertThat(accepts(grammar, "a b;")).isFalse();
        assertOnly(grammar, Severity.ERROR, "grammar.char-class-never-matches", "T", 2);
    }

    @Test
    void sameTextInALexerRule_matches_andIsNotReported() {
        var grammar = "Start <- T ';'\nT <- < 'double'i [ \\t\\r\\n]+ 'precision'i >\n" + TAIL;

        assertThat(accepts(grammar, "double precision;")).isTrue();
        assertThat(findings(grammar)).isEmpty();
    }

    @Test
    void newlineThatIsContentInThisGrammar_isNotReported() {
        var grammar = "Start <- (T [\\n])*\nT <- 'a' M?\nM <- '(' ')'\nNl <- [\\n]\n%whitespace <- [ \\t]+\n";

        assertThat(accepts(grammar, "a\na()\n")).isTrue();
        assertThat(findings(grammar)).isEmpty();
    }

    @Test
    void classInPinnedParserRule_isDropped_isAnError() {
        var grammar = "Start <- T ';'\nT <- 'x' [0-9] M?\n%parser T\n" + TAIL;

        assertThat(accepts(grammar, "x;")).isTrue();
        assertThat(accepts(grammar, "x 1;")).isFalse();
        assertOnly(grammar, Severity.ERROR, "grammar.char-class-no-op", "T", 2);
    }

    @Test
    void optionalWhitespaceClassInPinnedParserRule_isHarmless_soOnlyAWarning() {
        var grammar = "Start <- T ';'\nT <- Id [ \\t]*\n%parser T\n" + TAIL;

        assertThat(accepts(grammar, "a ;")).isTrue();
        assertOnly(grammar, Severity.WARNING, "grammar.char-class-never-matches", "T", 2);
    }

    /** The elided class always succeeds, so the alternatives after it are never tried. */
    @Test
    void whitespaceClassBeforeAnotherAlternativeInPinnedParserRule_killsIt_soIsAnError() {
        var grammar = "Start <- T ';'\nT <- Id ([ \\t] / Num)?\n%parser T\n" + TAIL;

        assertThat(accepts(grammar, "a 1;")).isFalse();
        assertOnly(grammar, Severity.ERROR, "grammar.char-class-no-op", "T", 2);
    }

    @Test
    void whitespaceClassAsLastAlternativeInPinnedParserRule_isHarmless_soOnlyAWarning() {
        var grammar = "Start <- T ';'\nT <- Id (Num / [ \\t])?\n%parser T\n" + TAIL;

        assertThat(accepts(grammar, "a 1;")).isTrue();
        assertThat(accepts(grammar, "a ;")).isTrue();
        assertOnly(grammar, Severity.WARNING, "grammar.char-class-never-matches", "T", 2);
    }

    /** A MIXED class that cannot match fails, so the next alternative is tried as written. */
    @Test
    void optionalWhitespaceClassBeforeAnotherAlternativeInMixedRule_isHarmless_soOnlyAWarning() {
        var grammar = "Start <- T ';'\nT <- Id ([ \\t] / Num)? M?\n" + TAIL;

        assertThat(accepts(grammar, "a 1;")).isTrue();
        assertOnly(grammar, Severity.WARNING, "grammar.char-class-never-matches", "T", 2);
    }

    @Test
    void triviaStartingAfterANullableElement_isWhitespaceToo() {
        var grammar = "Start <- T ';'\nT <- 'a' ![\\n] 'b' M?\nM <- '(' Num ')'\nNum <- [0-9]+\n%whitespace <- [ \\t]* [\\r\\n]+\n";

        assertThat(accepts(grammar, "a\nb;")).isTrue();
        assertOnly(grammar, Severity.WARNING, "grammar.whitespace-lookahead", "T", 2);
    }

    @Test
    void triviaAfterARequiredElement_cannotStartIt_soIsNotReported() {
        var grammar = "Start <- T ';'\nT <- 'a' ![\\n] 'b' M?\nM <- '(' Num ')'\nNum <- [0-9]+\n%whitespace <- [ \\t]+ [\\r\\n]*\n";

        assertThat(findings(grammar)).isEmpty();
    }

    @Test
    void optionalContentClassInPinnedParserRule_dropsWhatItShouldAccept_soStaysAnError() {
        var grammar = "Start <- T ';'\nT <- Id [0-9]*\n%parser T\n" + TAIL;

        assertThat(accepts(grammar, "a 1;")).isFalse();
        assertOnly(grammar, Severity.ERROR, "grammar.char-class-no-op", "T", 2);
    }

    @Test
    void mixedClassOverALongToken_consumesAllOfIt_isAWarning() {
        var grammar = "Start <- T ';'\nT <- 'x' [0-9] M?\n" + TAIL;

        assertThat(accepts(grammar, "x 12345;")).isTrue();
        assertOnly(grammar, Severity.WARNING, "grammar.char-class-whole-token", "T", 2);
    }

    @Test
    void mixedClassOverSingleCharacterTokens_isNotReported() {
        var grammar = "Start <- T ';'\nT <- [+\\-]? Num\nAdd <- '+' / '-'\n" + TAIL;

        assertThat(accepts(grammar, "-5;")).isTrue();
        assertThat(findings(grammar)).isEmpty();
    }

    @Test
    void mixedClassWhoseCharacterAlsoStartsALongerToken_isAWarning() {
        var grammar = "Start <- T ';' / '->' ';'\nT <- [+\\-]? Num\nAdd <- '+' / '-'\n" + TAIL;

        assertThat(accepts(grammar, "-> 5;")).isTrue();
        assertOnly(grammar, Severity.WARNING, "grammar.char-class-whole-token", "T", 2);
    }

    @Test
    void negatedClass_isJudgedByWhatItMatches_notByWhatItLists() {
        var grammar = "Start <- T ';'\nT <- [^;] M?\n" + TAIL;

        assertThat(accepts(grammar, "abc;")).isTrue();
        assertOnly(grammar, Severity.WARNING, "grammar.char-class-whole-token", "T", 2);
    }

    @Test
    void whitespaceLookaheads_alwaysPass_areWarnings() {
        var not = "Start <- T ';'\nT <- 'a' ![ \\t] 'b' M?\n" + TAIL;
        var and = "Start <- T ';'\nT <- 'a' &[ \\t] M\n" + TAIL;

        assertThat(accepts(not, "a b;")).isTrue();
        assertThat(accepts(and, "a(1);")).isTrue();
        assertOnly(not, Severity.WARNING, "grammar.whitespace-lookahead", "T", 2);
        assertOnly(and, Severity.WARNING, "grammar.whitespace-lookahead", "T", 2);
    }

    @Test
    void wordBoundaryAndEndOfInputGuards_areExempt() {
        assertThat(findings("Start <- T ';' !.\nT <- 'var' ![a-zA-Z0-9_$] M?\n" + TAIL)).isEmpty();
        assertThat(findings("Start <- T ';'\nT <- 'var' &[^a-zA-Z0-9_$] M?\n" + TAIL)).isEmpty();
    }

    @Test
    void whitespaceLiteral_takesTheTextFromTrivia_isAWarning() {
        var grammar = "Start <- T ';'\nT <- 'a' ' ' 'b' M? / Id Num\n" + TAIL;

        assertThat(accepts(grammar, "x  1;")).isTrue();
        assertThat(accepts(grammar, "x 1;")).isFalse();
        assertOnly(grammar, Severity.WARNING, "grammar.whitespace-literal", "T", 2);
    }

    @Test
    void whitespaceLiteralThatIsNotTriviaHere_isNotReported() {
        assertThat(findings("Start <- (T '\\n')*\nT <- 'a' M?\nM <- '(' ')'\n%whitespace <- [ \\t]+\n")).isEmpty();
    }

    @Test
    void commentOpenerIsNotMistakenForWhitespace() {
        var grammar = "Start <- T '/' T ';'\nT <- 'a' M?\nM <- '(' ')'\n%whitespace <- ([ \\t]+ / '//' [^\\n]*)*\n";

        assertThat(findings(grammar)).isEmpty();
    }

    @Test
    void java25_hasNoSuchFinding() throws Exception {
        assertThat(findings(Files.readString(Path.of("src/test/resources/java25.peg")))).isEmpty();
    }

    @Test
    void findingOnAnImportedRule_hasNoLine() {
        var composed = GrammarParser.parse("Start <- T ';'\nT <- 'x' [0-9] M?\n" + TAIL).unwrap();
        var root = GrammarParser.parse("Start <- 'q' ';'\n").unwrap();

        assertThat(Analyzer.analyze(composed, root).findings()).filteredOn(f -> "T".equals(f.ruleName()))
                                                              .isNotEmpty()
                                                              .allMatch(f -> f.line() == Finding.NO_LINE);
    }
}
