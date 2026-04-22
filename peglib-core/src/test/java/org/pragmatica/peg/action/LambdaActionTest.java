package org.pragmatica.peg.action;

import org.junit.jupiter.api.Test;
import org.pragmatica.peg.PegParser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies programmatic lambda action attachment via the interpreter path
 * ({@link PegParser#fromGrammar(String, org.pragmatica.peg.parser.ParserConfig,
 * Actions)}). Covers the typical calculator and transform patterns from
 * existing {@code CalculatorExample} but sourced from lambdas instead of
 * inline grammar actions.
 */
class LambdaActionTest {

    record Number() implements RuleId {}
    record Sum() implements RuleId {}
    record Word() implements RuleId {}

    @Test
    void interpreter_numberAction_parsesInt() {
        var grammar = """
            Number <- < [0-9]+ >
            """;
        var actions = Actions.empty()
                             .with(Number.class, sv -> sv.toInt());
        var parser = PegParser.fromGrammar(grammar, actions).unwrap();

        assertEquals(42, parser.parse("42").unwrap());
        assertEquals(123, parser.parse("123").unwrap());
    }

    @Test
    void interpreter_additionAction_combinesChildren() {
        var grammar = """
            Sum    <- Number '+' Number
            Number <- < [0-9]+ >
            %whitespace <- [ ]*
            """;
        var actions = Actions.empty()
                             .with(Number.class, sv -> sv.toInt())
                             .with(Sum.class, sv -> (Integer) sv.get(0) + (Integer) sv.get(1));
        var parser = PegParser.fromGrammar(grammar, actions).unwrap();

        assertEquals(5, parser.parse("2 + 3").unwrap());
        assertEquals(100, parser.parse("50 + 50").unwrap());
    }

    @Test
    void interpreter_tokenAction_usesMatchedText() {
        var grammar = """
            Word <- < [a-z]+ >
            """;
        var actions = Actions.empty()
                             .with(Word.class, sv -> sv.token().toUpperCase());
        var parser = PegParser.fromGrammar(grammar, actions).unwrap();

        assertEquals("HELLO", parser.parse("hello").unwrap());
    }

    @Test
    void interpreter_emptyActions_behavesLikeNoActions() {
        var grammar = """
            Number <- < [0-9]+ >
            """;
        var parser = PegParser.fromGrammar(grammar, Actions.empty()).unwrap();

        // No action attached — parse succeeds, returning the CST node (same behaviour
        // as fromGrammar(grammar) without actions).
        assertTrue(parser.parse("42").isSuccess());
    }

    @Test
    void interpreter_partialAttachment_onlyAttachedRuleTransforms() {
        var grammar = """
            Sum    <- Number '+' Number
            Number <- < [0-9]+ >
            %whitespace <- [ ]*
            """;
        // Attach lambda only for Number; Sum returns the CST node / child values.
        var actions = Actions.empty()
                             .with(Number.class, sv -> sv.toInt());
        var parser = PegParser.fromGrammar(grammar, actions).unwrap();

        var result = parser.parse("2 + 3");
        assertTrue(result.isSuccess());
    }
}
