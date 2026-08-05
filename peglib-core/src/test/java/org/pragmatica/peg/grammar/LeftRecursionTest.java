package org.pragmatica.peg.grammar;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.peg.grammar.analysis.LeftRecursionAnalysis;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Grammar-level left-recursion analysis and validation.
 *
 * <p>Covers {@link LeftRecursionAnalysis} detection of direct left-recursive
 * rules and {@link GrammarParser} rejection of indirect left-recursion at
 * construction time. Direct-LR <em>parsing</em> (0.2.9 Warth-style
 * seed-and-grow) no longer exists: the 0.6.x tokens-first engine rejects every
 * left-recursive grammar at {@code PegParser.fromGrammar} via
 * {@code LeftRecursionDetector}, so what remains testable here is the
 * grammar-level analysis these two layers are built on.
 */
class LeftRecursionTest {

    @Nested
    class Detection {
        @Test
        void detectsDirectLeftRecursiveRule() {
            var grammar = GrammarParser.parse("""
                Expr <- Expr '+' Term / Term
                Term <- [0-9]+
                """).unwrap();
            var lr = LeftRecursionAnalysis.directLeftRecursiveRules(grammar);

            assertThat(lr).containsExactly("Expr");
        }

        @Test
        void nonLeftRecursiveRuleNotFlagged() {
            var grammar = GrammarParser.parse("""
                Expr <- Term ('+' Term)*
                Term <- [0-9]+
                """).unwrap();
            var lr = LeftRecursionAnalysis.directLeftRecursiveRules(grammar);

            assertThat(lr).isEmpty();
        }

        @Test
        void multipleDirectLrRulesDetected() {
            var grammar = GrammarParser.parse("""
                Expr <- Expr '+' Term / Term
                Term <- Term '*' Factor / Factor
                Factor <- [0-9]+
                """).unwrap();
            var lr = LeftRecursionAnalysis.directLeftRecursiveRules(grammar);

            assertThat(lr).containsExactlyInAnyOrder("Expr", "Term");
        }
    }

    @Nested
    class IndirectLrRejection {
        @Test
        void indirectCycleIsRejectedAtValidation() {
            // 0.4.0 — validation is done by the Grammar.grammar(...) factory at
            // construction; GrammarParser.parse(...) surfaces validation failure
            // directly.
            var validated = GrammarParser.parse("""
                A <- B '+' X / X
                B <- A '-' Y / Y
                X <- [a-z]
                Y <- [a-z]
                """);

            assertThat(validated.isFailure()).as("indirect LR rejected").isTrue();
            validated.onFailure(cause ->
                assertThat(cause.message()).contains("indirect left-recursion"));
        }

        @Test
        void directLrPassesValidation() {
            var validated = GrammarParser.parse("""
                Expr <- Expr '+' Term / Term
                Term <- [0-9]+
                """);

            assertThat(validated.isSuccess()).as("direct LR passes").isTrue();
        }
    }
}
