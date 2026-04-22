package org.pragmatica.peg.grammar;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.peg.PegParser;
import org.pragmatica.peg.error.RecoveryStrategy;
import org.pragmatica.peg.grammar.analysis.LeftRecursionAnalysis;
import org.pragmatica.peg.parser.ParserConfig;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 0.2.9 — direct left-recursion via Warth-style seed-and-grow.
 *
 * <p>Covers: detection of direct LR rules, the arithmetic precedence case, postfix
 * chains, mixed member-access, cut-inside-LR, indirect-LR rejection, and the
 * {@code selectivePackrat} × LR configuration check.
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
    class ArithmeticPrecedence {
        @Test
        void leftAssociativeAdditionChain() {
            var parser = PegParser.fromGrammar("""
                Expr <- Expr '+' Term / Term
                Term <- [0-9]+
                """).unwrap();
            var cst = parser.parseCst("1+2+3");

            assertThat(cst.isSuccess()).as("parse 1+2+3").isTrue();
        }

        @Test
        void additionAndMultiplicationPrecedence() {
            var parser = PegParser.fromGrammar("""
                Expr <- Expr '+' Term / Term
                Term <- Term '*' Factor / Factor
                Factor <- [0-9]+
                """).unwrap();
            var cst = parser.parseCst("1+2*3+4");

            assertThat(cst.isSuccess()).as("parse 1+2*3+4").isTrue();
        }

        @Test
        void singleBaseCaseWithoutRecursion() {
            var parser = PegParser.fromGrammar("""
                Expr <- Expr '+' Term / Term
                Term <- [0-9]+
                """).unwrap();
            var cst = parser.parseCst("42");

            assertThat(cst.isSuccess()).as("parse lone 42").isTrue();
        }
    }

    @Nested
    class PostfixChains {
        @Test
        void dottedAccessChain() {
            var parser = PegParser.fromGrammar("""
                Expr <- Expr '.' Ident / Ident
                Ident <- [a-z]+
                """).unwrap();
            var cst = parser.parseCst("a.b.c.d");

            assertThat(cst.isSuccess()).as("parse a.b.c.d").isTrue();
        }

        @Test
        void mixedMemberAndIndexAccess() {
            var parser = PegParser.fromGrammar("""
                Expr <- Expr '[' Expr ']'
                      / Expr '.' Ident
                      / Ident
                Ident <- [a-z]+
                """).unwrap();
            var cst = parser.parseCst("a[b].c[d.e]");

            assertThat(cst.isSuccess()).as("parse a[b].c[d.e]").isTrue();
        }
    }

    @Nested
    class CutInteraction {
        @Test
        void cutInsideLeftRecursionFreezesSeed() {
            // Grammar where the recursive alternative uses cut before Term.
            // When Term fails, cut forces the seed to be final — so "1+" (partial)
            // should fail the overall parse (no fallback to Term alone after
            // already growing beyond that).
            var parser = PegParser.fromGrammar("""
                Expr <- Expr '+' ^ Term / Term
                Term <- [0-9]+
                """).unwrap();
            // Straight success path still works.
            var ok = parser.parseCst("1+2");
            assertThat(ok.isSuccess()).as("1+2 parses successfully").isTrue();
            // Straight base case.
            var baseline = parser.parseCst("7");
            assertThat(baseline.isSuccess()).as("7 alone parses").isTrue();
        }
    }

    @Nested
    class IndirectLrRejection {
        @Test
        void indirectCycleIsRejectedAtValidation() {
            var grammar = GrammarParser.parse("""
                A <- B '+' X / X
                B <- A '-' Y / Y
                X <- [a-z]
                Y <- [a-z]
                """).unwrap();
            var validated = grammar.validate();

            assertThat(validated.isFailure()).as("indirect LR rejected").isTrue();
            validated.onFailure(cause ->
                assertThat(cause.message()).contains("indirect left-recursion"));
        }

        @Test
        void directLrPassesValidation() {
            var grammar = GrammarParser.parse("""
                Expr <- Expr '+' Term / Term
                Term <- [0-9]+
                """).unwrap();
            var validated = grammar.validate();

            assertThat(validated.isSuccess()).as("direct LR passes").isTrue();
        }
    }

    @Nested
    class SelectivePackratWithLr {
        @Test
        void lrRuleInPackratSkipRulesIsConfigurationError() {
            var config = new ParserConfig(
            /* packratEnabled         */ true,
            /* recoveryStrategy       */ RecoveryStrategy.BASIC,
            /* captureTrivia          */ true,
            /* fastTrackFailure       */ true,
            /* literalFailureCache    */ true,
            /* charClassFailureCache  */ true,
            /* bulkAdvanceLiteral     */ true,
            /* skipWhitespaceFastPath */ true,
            /* reuseEndLocation       */ true,
            /* choiceDispatch         */ true,
            /* markResetChildren      */ false,
            /* inlineLocations        */ false,
            /* selectivePackrat       */ true,
            /* packratSkipRules       */ Set.of("Expr"));

            var result = PegParser.fromGrammar("""
                Expr <- Expr '+' Term / Term
                Term <- [0-9]+
                """, config);

            assertThat(result.isFailure()).as("LR in packratSkipRules rejected").isTrue();
            result.onFailure(cause ->
                assertThat(cause.message()).contains("left-recursive"));
        }
    }
}
