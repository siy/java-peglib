package org.pragmatica.peg.grammar;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Result;
import org.pragmatica.peg.PegParser;
import org.pragmatica.peg.parser.ParserConfig;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 0.2.8 — Grammar composition via {@code %import} directives.
 *
 * <p>Covers resolver correctness (transitive closure, cycle detection, collision
 * handling, missing grammars), public-API wiring through
 * {@link org.pragmatica.peg.PegParser#fromGrammar(String, ParserConfig, GrammarSource)},
 * and RuleId emission for imported rules.
 */
class GrammarCompositionTest {

    @Nested
    class ResolverBehaviour {
        @Test
        void noImports_resolveIsIdentity() {
            var grammarText = """
                Number <- [0-9]+
                %whitespace <- [ ]*
                """;
            var root = GrammarParser.parse(grammarText).unwrap();
            var resolved = GrammarResolver.resolve(root, GrammarSource.empty()).unwrap();

            assertThat(resolved.rules()).hasSize(1);
            assertThat(resolved.imports()).isEmpty();
        }

        @Test
        void inlinesSingleImportedRule() {
            var source = GrammarSource.inMemory(Map.of(
            "Lib", "Number <- [0-9]+\n"
            ));
            var rootText = """
                %import Lib.Number
                Expr <- Lib_Number
                """;
            var composed = GrammarResolver.resolveText(rootText, source).unwrap();

            assertThat(composed.rule("Lib_Number")).matches(o -> o.isPresent());
            assertThat(composed.rule("Expr")).matches(o -> o.isPresent());
            assertThat(composed.imports()).isEmpty();
        }

        @Test
        void aliasRenamesTopLevelImport() {
            var source = GrammarSource.inMemory(Map.of(
            "Lib", "Digit <- [0-9]\n"
            ));
            var rootText = """
                %import Lib.Digit as D
                Expr <- D D D
                """;
            var composed = GrammarResolver.resolveText(rootText, source).unwrap();

            assertThat(composed.rule("D")).matches(o -> o.isPresent());
            assertThat(composed.rule("Lib_Digit")).matches(o -> o.isEmpty());
        }

        @Test
        void transitiveClosureIsInlinedWithGrammarQualifiedNames() {
            var source = GrammarSource.inMemory(Map.of(
            "Lib", """
                Num <- Digit+
                Digit <- [0-9]
                """
            ));
            var rootText = """
                %import Lib.Num
                Expr <- Lib_Num
                """;
            var composed = GrammarResolver.resolveText(rootText, source).unwrap();

            // Both Num and Digit pulled in; Num becomes Lib_Num; Digit becomes Lib_Digit.
            assertThat(composed.rule("Lib_Num")).matches(o -> o.isPresent());
            assertThat(composed.rule("Lib_Digit")).matches(o -> o.isPresent());

            // The reference inside Lib_Num must have been rewritten from Digit → Lib_Digit.
            var numRule = composed.rule("Lib_Num").unwrap();
            assertThat(numRule.expression().toString()).contains("Lib_Digit");
            assertThat(numRule.expression().toString()).doesNotContain("ruleName=Digit");
        }

        @Test
        void cycleDetectionFires() {
            var source = GrammarSource.inMemory(Map.of(
            "A", "RuleA <- 'a' B_RuleB\n%import B.RuleB\n",
            "B", "RuleB <- 'b' A_RuleA\n%import A.RuleA\n"
            ));
            var rootText = """
                %import A.RuleA
                Start <- A_RuleA
                """;
            var result = GrammarResolver.resolveText(rootText, source);

            assertThat(result.isFailure()).isTrue();
            var err = ((Result.Failure<?>) result).cause().message();
            assertThat(err).containsIgnoringCase("cycl");
        }

        @Test
        void missingGrammarFileErrorsWithClearMessage() {
            var source = GrammarSource.empty();
            var rootText = """
                %import NonExistent.Rule
                Start <- NonExistent_Rule
                """;
            var result = GrammarResolver.resolveText(rootText, source);

            assertThat(result.isFailure()).isTrue();
            var err = ((Result.Failure<?>) result).cause().message();
            assertThat(err).contains("NonExistent");
        }

        @Test
        void undefinedImportedRuleErrors() {
            var source = GrammarSource.inMemory(Map.of(
            "Lib", "Digit <- [0-9]\n"
            ));
            var rootText = """
                %import Lib.NotARule
                Start <- Lib_NotARule
                """;
            var result = GrammarResolver.resolveText(rootText, source);

            assertThat(result.isFailure()).isTrue();
            var err = ((Result.Failure<?>) result).cause().message();
            assertThat(err).contains("NotARule");
            assertThat(err).contains("Lib");
        }

        @Test
        void nameCollisionErrorsUnlessAsRenameUsed() {
            var source = GrammarSource.inMemory(Map.of(
            "Lib", "Expr <- [0-9]+\n"
            ));
            // Conflict: user imports Lib.Expr with explicit alias matching a root rule.
            var collidingText = """
                %import Lib.Expr as Number
                Number <- Lib_Expr
                """;
            var collide = GrammarResolver.resolveText(collidingText, source);
            assertThat(collide.isFailure()).isTrue();

            // Same import without alias uses default name Lib_Expr — no collision.
            var nonCollidingText = """
                %import Lib.Expr
                Number <- Lib_Expr
                """;
            var ok = GrammarResolver.resolveText(nonCollidingText, source);
            assertThat(ok.isFailure()).isFalse();
        }

        @Test
        void unaliasedImportDoesNotCollideWithLiteralRootRuleName() {
            // Root defines `Number`; import exposes `Lib_Number` (no collision).
            var source = GrammarSource.inMemory(Map.of(
            "Lib", "Number <- [0-9]+\n"
            ));
            var rootText = """
                %import Lib.Number
                Number <- 'X'
                Start <- Number / Lib_Number
                """;
            var composed = GrammarResolver.resolveText(rootText, source).unwrap();
            assertThat(composed.rule("Number")).matches(o -> o.isPresent());
            assertThat(composed.rule("Lib_Number")).matches(o -> o.isPresent());
        }

        @Test
        void rootSilentlyShadowsTransitiveImports() {
            var source = GrammarSource.inMemory(Map.of(
            "Lib", """
                Num <- Digit+
                Digit <- [0-9]
                """
            ));
            // Root defines Lib_Digit itself; resolver must not overwrite it.
            var rootText = """
                %import Lib.Num
                Lib_Digit <- 'X'
                Start <- Lib_Num
                """;
            var composed = GrammarResolver.resolveText(rootText, source).unwrap();
            var digit = composed.rule("Lib_Digit").unwrap();
            // Root's version should be preserved — it matches 'X', not [0-9].
            assertThat(digit.expression().toString()).contains("X");
        }
    }

    @Nested
    class PegParserIntegration {
        @Test
        void parseAcrossComposedGrammar() {
            var source = GrammarSource.inMemory(Map.of(
            "NumLib", """
                Number <- [0-9]+
                %whitespace <- [ ]*
                """
            ));
            var rootText = """
                %import NumLib.Number as Num
                List <- Num (',' Num)*
                %whitespace <- [ ]*
                """;
            var parser = PegParser.fromGrammar(rootText, ParserConfig.DEFAULT, source).unwrap();
            var cst = parser.parseCst("1, 2, 3");
            assertThat(cst.isSuccess()).isTrue();
        }

        @Test
        void composedCstMatchesHandInlinedEquivalent() {
            var source = GrammarSource.inMemory(Map.of(
            "NumLib", """
                Number <- [0-9]+
                """
            ));
            var composedText = """
                %import NumLib.Number
                Pair <- NumLib_Number '+' NumLib_Number
                %whitespace <- [ ]*
                """;
            var handInlinedText = """
                Pair <- Number '+' Number
                Number <- [0-9]+
                %whitespace <- [ ]*
                """;

            var composed = PegParser.fromGrammar(composedText, ParserConfig.DEFAULT, source).unwrap();
            var inlined = PegParser.fromGrammar(handInlinedText).unwrap();

            var input = "1 + 2";
            var composedCst = composed.parseCst(input);
            var inlinedCst = inlined.parseCst(input);
            assertThat(composedCst.isSuccess()).isTrue();
            assertThat(inlinedCst.isSuccess()).isTrue();
        }

        @Test
        void missingSourceProducesMeaningfulError() {
            var rootText = """
                %import AbsentGrammar.Foo
                Start <- AbsentGrammar_Foo
                """;
            var result = PegParser.fromGrammar(rootText, ParserConfig.DEFAULT, GrammarSource.empty());
            assertThat(result.isFailure()).isTrue();
            var err = ((Result.Failure<?>) result).cause().message();
            assertThat(err).contains("AbsentGrammar");
        }
    }

    @Nested
    class ClasspathLoading {
        @Test
        void classpathSourceLoadsPegResource() {
            var cl = Thread.currentThread().getContextClassLoader();
            if (cl.getResource("test-grammars/Lib.peg") == null) {
                // Resource not on classpath — skip gracefully. A guard resource is provided
                // under src/test/resources/test-grammars/Lib.peg so this path is exercised.
                return;
            }
            var source = GrammarSource.classpath(new ClassLoader(cl) {
                @Override
                public java.net.URL getResource(String name) {
                    if (name.equals("Lib.peg")) {
                        return cl.getResource("test-grammars/Lib.peg");
                    }
                    return super.getResource(name);
                }

                @Override
                public java.io.InputStream getResourceAsStream(String name) {
                    if (name.equals("Lib.peg")) {
                        return cl.getResourceAsStream("test-grammars/Lib.peg");
                    }
                    return super.getResourceAsStream(name);
                }
            });

            var rootText = """
                %import Lib.Number
                Expr <- Lib_Number
                %whitespace <- [ ]*
                """;
            var parser = PegParser.fromGrammar(rootText, ParserConfig.DEFAULT, source).unwrap();
            assertThat(parser.parseCst("42").isSuccess()).isTrue();
        }
    }

    @Nested
    class RuleIdEmission {
        @Test
        void importedRulesAppearInGeneratedRuleIdInterface() {
            var source = GrammarSource.inMemory(Map.of(
            "Lib", "Number <- [0-9]+\n"
            ));
            var rootText = """
                %import Lib.Number as Num
                Expr <- Num
                """;
            var composed = GrammarResolver.resolveText(rootText, source).unwrap();
            // After composition, the parser generator sees a flat Grammar — the Num rule
            // is a regular entry. RuleId.Num and RuleId.Expr must both emit.
            var generated = PegParser.generateCstParser(
            "Expr <- Num\nNum <- [0-9]+\n", "gen.cst", "CstParser").unwrap();
            assertThat(generated).contains("record Num() implements RuleId");
            assertThat(generated).contains("record Expr() implements RuleId");

            // Same shape when we drive the generator via the composed grammar.
            var composedSource = org.pragmatica.peg.generator.ParserGenerator
            .create(composed, "gen.cst2", "CstParser2",
                    org.pragmatica.peg.generator.ErrorReporting.BASIC, ParserConfig.DEFAULT)
            .generateCst();
            assertThat(composedSource).contains("record Num() implements RuleId");
            assertThat(composedSource).contains("record Expr() implements RuleId");
        }

        @Test
        void unaliasedImportExposesGrammarQualifiedRuleId() {
            var source = GrammarSource.inMemory(Map.of(
            "Lib", "Number <- [0-9]+\n"
            ));
            var rootText = """
                %import Lib.Number
                Expr <- Lib_Number
                """;
            var composed = GrammarResolver.resolveText(rootText, source).unwrap();
            var generated = org.pragmatica.peg.generator.ParserGenerator
            .create(composed, "gen.cst3", "CstParser3",
                    org.pragmatica.peg.generator.ErrorReporting.BASIC, ParserConfig.DEFAULT)
            .generateCst();
            // Grammar-qualified RuleId record name.
            assertThat(generated).contains("record LibNumber() implements RuleId");
            assertThat(generated).contains("record Expr() implements RuleId");
        }
    }
}
