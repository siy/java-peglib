package org.pragmatica.peg.action;

import org.junit.jupiter.api.Test;
import org.pragmatica.peg.PegParser;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * When a rule declares both an inline grammar action and a lambda attachment,
 * the lambda wins. This test locks in that precedence — flipping it would be
 * a source-breaking change for 0.2.6 consumers.
 */
class LambdaVsInlineActionTest {

    record Number() implements RuleId {}

    @Test
    void lambdaOverridesInlineAction_interpreter() {
        // Inline action would return sv.toInt() — int 42.
        // Lambda attached for Number: multiply by 10 so we can tell them apart.
        var grammar = """
            Number <- < [0-9]+ > { return sv.toInt(); }
            """;
        var actions = Actions.empty()
                             .with(Number.class, sv -> sv.toInt() * 10);
        var parser = PegParser.fromGrammar(grammar, actions).unwrap();

        assertEquals(420, parser.parse("42").unwrap());
    }

    @Test
    void absentLambda_fallsBackToInlineAction() {
        // Lambda not attached for this rule — inline action runs.
        var grammar = """
            Number <- < [0-9]+ > { return sv.toInt(); }
            """;
        var parser = PegParser.fromGrammar(grammar, Actions.empty()).unwrap();

        assertEquals(42, parser.parse("42").unwrap());
    }
}
