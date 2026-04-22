package org.pragmatica.peg.error;

import org.junit.jupiter.api.Test;
import org.pragmatica.peg.PegParser;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the {@code %recover '<terminator>'} rule-level directive (0.2.4).
 * Overrides the global {@code ADVANCED} recovery char-set ({@code , ; } ) ] \\n})
 * with a single literal terminator scoped to the rule's body.
 */
class RuleRecoveryTest {

    @Test
    void ruleLevelRecoverAcceptsStringArgument() {
        // Grammar parses successfully with %recover directive — verifies the
        // lexer + parser accept the new syntax without regressions.
        var grammar = """
            Block <- '{' Stmt* '}' %recover "}"
            Stmt <- [a-z]+ ';'
            %whitespace <- [ \\t\\n]*
            """;
        var parser = PegParser.fromGrammar(grammar);
        assertTrue(parser.isSuccess(),
                   "grammar with %recover should compile successfully");
    }

    @Test
    void ruleWithRecoverStillParsesValidInput() {
        var grammar = """
            Block <- '{' Stmt* '}' %recover "}"
            Stmt <- [a-z]+ ';'
            %whitespace <- [ \\t\\n]*
            """;
        var parser = PegParser.fromGrammar(grammar)
                              .unwrap();
        var result = parser.parseCst("{ foo; bar; }");
        assertTrue(result.isSuccess(),
                   "valid block with %recover rule should parse, got: " + result);
    }

    @Test
    void recoverWithMultiCharTerminatorParses() {
        // Multi-char terminator works — directive parses and grammar compiles.
        var grammar = """
            Section <- '<<' Body '>>' %recover ">>"
            Body <- [a-z ]*
            """;
        var parser = PegParser.fromGrammar(grammar);
        assertTrue(parser.isSuccess(),
                   "grammar with multi-char %recover literal should compile");
    }
}
