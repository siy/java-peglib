package org.pragmatica.peg.error;

import org.junit.jupiter.api.Test;
import org.pragmatica.peg.PegParser;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the {@code %expected "label"} rule-level directive (0.2.4).
 * The label replaces the raw-token join ({@code "'(' or '{' or [a-zA-Z] or..."})
 * in failure diagnostics with a single semantic phrase.
 */
class SemanticExpectedTest {

    private static final String GRAMMAR_WITH_EXPECTED = """
        Program <- Statement+
        Statement <- Expr ';' %expected "statement"
        Expr <- [0-9]+
        %whitespace <- [ \\t\\n]*
        """;

    @Test
    void failureDiagnosticUsesSemanticLabel() {
        var parser = PegParser.fromGrammar(GRAMMAR_WITH_EXPECTED)
                              .unwrap();
        var result = parser.parseCst("@@@");
        assertTrue(result.isFailure(), "parse should fail on invalid input");
        var message = result.toString();
        assertTrue(message.contains("statement"),
                   "expected 'statement' label in error, got: " + message);
    }

    @Test
    void successPathUnaffectedByExpectedDirective() {
        var parser = PegParser.fromGrammar(GRAMMAR_WITH_EXPECTED)
                              .unwrap();
        var result = parser.parseCst("42;");
        assertTrue(result.isSuccess(),
                   "valid input should still parse when rule has %expected");
    }

    @Test
    void grammarWithoutExpectedKeepsRawTokenJoin() {
        var grammar = """
            Statement <- Expr ';'
            Expr <- [0-9]+
            %whitespace <- [ ]*
            """;
        var parser = PegParser.fromGrammar(grammar)
                              .unwrap();
        var result = parser.parseCst("@@@");
        assertTrue(result.isFailure());
        // Without %expected, raw-token-style expected list appears
        var message = result.toString();
        assertFalse(message.contains("statement"),
                    "grammar without %expected should not contain 'statement' label");
    }
}
