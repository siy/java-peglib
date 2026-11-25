package org.pragmatica.peg.generator;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.pragmatica.peg.PegParser;
import org.pragmatica.peg.cst.ParseResult;
import org.pragmatica.peg.diagnostic.Diagnostic;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Issue #47 — a diagnostic is the furthest failure, read from one token, and one mistake is reported
 * once. The start rule is nullable and recovery restarts token by token, which is the combination
 * that used to report every token of the input from 1:1.
 */
class FurthestFailureReportingTest {
    private static final String SCRIPT = """
            Input <- ';'* (Stmt (';'+ Stmt)*)? ';'*
            Stmt  <- 'make' Name '(' Col (',' Col)* ')'
            Col   <- Name Type
            Type  <- 'int' / 'text'
            Name  <- [a-z]+
            %whitespace <- [ \\t\\r\\n]+
            """;

    private static ParseResult parse(String grammar, String input) {
        return PegParser.fromGrammar(grammar)
                        .unwrap()
                        .parse(input);
    }

    private static void assertSite(Diagnostic diagnostic, String input, String token) {
        assertThat(diagnostic.offset()).isEqualTo(input.indexOf(token));
        assertThat(diagnostic.length()).isEqualTo(token.length());
        assertThat(diagnostic.found()).isEqualTo(token);
    }

    @Test
    void nullableStartRule_reportsTheFailingToken_notTheStart() {
        var input = "make t (a int, b bogus)";
        var first = parse(SCRIPT, input).diagnostics().getFirst();

        assertSite(first, input, "bogus");
        assertThat(first.message()).isEqualTo("syntax error");
    }

    @Test
    void laterStatementOfAScript_reportsTheFailingToken_notTheStatementStart() {
        var input = "make t (a int); make u (b bogus)";

        assertSite(parse(SCRIPT, input).diagnostics().getFirst(), input, "bogus");
    }

    @Test
    void sitesStrictlyAdvance_soRecoveryDoesNotRepeatAMistake() {
        var offsets = parse(SCRIPT, "make t (a int, b bogus)").diagnostics()
                                                              .stream()
                                                              .map(Diagnostic::offset)
                                                              .toList();

        assertThat(offsets).isSorted().doesNotHaveDuplicates();
        assertThat(offsets.getFirst()).isEqualTo(17);
    }

    @Test
    void twoMistakesInSeparateStatements_areBothReported() {
        var input = "make t (a bogus); make u (b wrong)";
        var found = parse(SCRIPT, input).diagnostics()
                                        .stream()
                                        .map(Diagnostic::found)
                                        .toList();

        assertThat(found).contains("bogus", "wrong");
    }

    @Test
    void nothingBeyondTheStopPoint_staysTrailingInput() {
        var input = "make t (a int) extra more";
        var diagnostics = parse(SCRIPT, input).diagnostics();

        assertSite(diagnostics.getFirst(), input, "extra");
        assertThat(diagnostics.getFirst().message()).isEqualTo("trailing input not consumed");
    }

    @Test
    void nonNullableStartRule_readsFoundAndLengthFromTheReportedToken() {
        var grammar = "Start <- 'x' [0-9] M? ';'\nM <- '(' ')'\nId <- [a-z]+\n%whitespace <- [ ]+\n";

        assertSite(parse(grammar, "x y;").diagnostics().getFirst(), "x y;", "y");
    }

    @Test
    void failureAtEndOfInput_isReportedThere() {
        var input = "make t (a int";
        var diagnostics = parse(SCRIPT, input).diagnostics();

        assertThat(diagnostics).hasSize(1);
        assertThat(diagnostics.getFirst().offset()).isEqualTo(input.length());
        assertThat(diagnostics.getFirst().found()).isEqualTo("<end-of-input>");
    }

    @Test
    void failureInsideANegativeLookahead_isNotReported() {
        // The lookahead reaches '(' before failing; that is the predicate succeeding, not a mistake.
        var grammar = """
                Start <- !(Num Num Id) Num Rest?
                Rest  <- '(' Id ')'
                Id    <- [a-z]+
                Num   <- [0-9]+
                %whitespace <- [ ]+
                """;

        assertSite(parse(grammar, "1 2 (").diagnostics().getFirst(), "1 2 (", "2");
    }

    @Test
    void diagnosticCapOfZero_recordsNothing_andValidInputIsClean() {
        var parser = PegParser.fromGrammar(SCRIPT).unwrap();

        assertThat(parser.parse("make t (a bogus)", 0).diagnostics()).isEqualTo(List.of());
        assertThat(parser.parse("make t (a int);").diagnostics()).isEmpty();
        assertThat(parser.parse("").diagnostics()).isEmpty();
    }
}
