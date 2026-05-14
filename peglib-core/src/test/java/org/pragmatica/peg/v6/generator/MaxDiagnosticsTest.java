package org.pragmatica.peg.v6.generator;

import org.pragmatica.peg.v6.PegParser;
import org.pragmatica.peg.v6.cst.ParseResult;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 0.6.1 — Item G — diagnostic cap is honored by {@link
 * org.pragmatica.peg.v6.Parser#parse(String, int)}.
 *
 * <p>Grammar matches a sequence of {@code "let" Identifier ";"} statements. The
 * bad input {@code "let ; let ; ..."} (no identifier between {@code let} and
 * {@code ;}) yields one recovery diagnostic per bad statement (the panic-mode
 * sync set defaults to {@code ; , } ) ] }}, so each {@code ;} acts as a sync
 * boundary).
 *
 * <p>Semantics under test:
 * <ul>
 *   <li>{@code maxDiagnostics == 0}: zero diagnostics recorded.</li>
 *   <li>{@code maxDiagnostics == 1}: exactly one.</li>
 *   <li>{@code maxDiagnostics == 5}: exactly five, against the no-cap baseline.</li>
 *   <li>{@code maxDiagnostics &gt; actualCount}: returns actualCount.</li>
 *   <li>No-cap path ({@link
 *       org.pragmatica.peg.v6.Parser#parse(String)}) unchanged.</li>
 * </ul>
 */
class MaxDiagnosticsTest {
    private static final String GRAMMAR = """
        Start <- Stmt+
        Stmt <- "let" Identifier ";"
        Identifier <- [a-z]+
        %whitespace <- [ \\t\\n]*
        """;

    // Six bad statements: each "let ; " has no identifier, fails at ';',
    // recovery walks to that ';' (sync token), emits one Error + one
    // diagnostic, then the loop iterates on the next "let".
    private static final String BAD_INPUT_6 =
        "let ; let ; let ; let ; let ; let ;";

    // Two bad statements followed by a valid one. Used to verify
    // maxDiagnostics > actualCount returns actualCount.
    private static final String BAD_INPUT_2 =
        "let ; let ; let foo;";

    private static org.pragmatica.peg.v6.Parser parser;

    @BeforeAll
    static void setup() {
        parser = PegParser.fromGrammar(GRAMMAR).unwrap();
    }

    @Test
    void noCap_baseline_recordsAllDiagnostics() {
        ParseResult result = parser.parse(BAD_INPUT_6);
        // Sanity check on the baseline. Six bad statements yield at least
        // six diagnostics — exact count depends on how recovery resumes
        // after the final ';' and whether trailing-input flags fire.
        assertThat(result.diagnostics().size()).isGreaterThanOrEqualTo(6);
    }

    @Test
    void cap5_capsAtFive() {
        int baseline = parser.parse(BAD_INPUT_6).diagnostics().size();
        // Sanity: baseline must exceed the cap for this case to be meaningful.
        assertThat(baseline).isGreaterThan(5);

        ParseResult result = parser.parse(BAD_INPUT_6, 5);
        assertThat(result.diagnostics()).hasSize(5);
    }

    @Test
    void cap0_recordsZeroDiagnostics() {
        ParseResult result = parser.parse(BAD_INPUT_6, 0);
        assertThat(result.diagnostics()).isEmpty();
        // The parse still completes (the recovery loop's position advance is
        // unconditional); the only thing the cap suppresses is the record.
        assertThat(result.cst().nodeCount()).isGreaterThan(0);
    }

    @Test
    void cap1_recordsExactlyOneDiagnostic() {
        ParseResult result = parser.parse(BAD_INPUT_6, 1);
        assertThat(result.diagnostics()).hasSize(1);
    }

    @Test
    void capGreaterThanActual_returnsActual() {
        int actual = parser.parse(BAD_INPUT_2).diagnostics().size();
        // Sanity: BAD_INPUT_2 produces at least two diagnostics so the test
        // is meaningful, but fewer than 10 so cap=10 is the over-cap case.
        assertThat(actual).isGreaterThanOrEqualTo(2);
        assertThat(actual).isLessThan(10);

        ParseResult result = parser.parse(BAD_INPUT_2, 10);
        assertThat(result.diagnostics()).hasSize(actual);
    }
}
