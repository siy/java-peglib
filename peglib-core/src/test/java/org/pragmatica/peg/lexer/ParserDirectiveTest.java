package org.pragmatica.peg.lexer;

import org.junit.jupiter.api.Test;
import org.pragmatica.peg.PegParser;
import org.pragmatica.peg.grammar.GrammarParser;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 0.7.2 — {@code %parser RuleName} pins a rule to PARSER, overriding classification inference.
 *
 * <p>Classification is inferred from body shape, and the inference cannot distinguish a rule that
 * IS a token from one that merely names tokens. A reference-only body spanning a single token
 * reads as lexical — which is right for an alias and wrong for a rule like
 * {@code ColLabel <- ColId / ReservedKeyword}, whose whole purpose is to choose between token
 * kinds at parse time. Inferred LEXER, it collides with {@code ColId} for the same input and is
 * either out-prioritised or rejected outright.
 *
 * <p>The author knows which side of the split the rule belongs on. This lets them say so.
 */
class ParserDirectiveTest {

    /**
     * Mirrors the shape that motivated the directive: {@code Name} is reference-only (like
     * PostgreSQL's {@code ColId}), so it classifies LEXER, and {@code Label} — naming only lexer
     * rules and spanning one token — is dragged lexical with it. Give {@code Name} an inline
     * character class instead and it becomes PARSER on its own, the cascade never happens, and
     * this test silently stops testing anything.
     */
    private static final String BODY = """
        Doc      <- Label ';'
        Label    <- Name / Reserved
        Name     <- !Reserved (Quoted / Plain)
        Quoted   <- '"' < [a-z]+ > '"'
        Plain    <- < [a-z]+ >
        Reserved <- ('select' / 'from') ![a-z0-9_]
        %whitespace <- [ \\t\\r\\n]*
        """;

    @Test
    void withoutDirective_referenceOnlyChoiceIsInferredLexer() {
        // The baseline the directive exists to override. If inference ever handles this shape,
        // revisit this test rather than delete it — the directive would still be the way an
        // author states intent, but the motivating failure would be gone.
        var grammar = GrammarParser.parse(BODY)
                                   .unwrap();

        assertThat(RuleClassifier.classify(grammar)
                                 .unwrap()
                                 .kinds())
        .containsEntry("Label", RuleKind.LEXER);
        assertThat(PegParser.fromGrammar(BODY).isSuccess())
        .withFailMessage("un-pinned, the lexical reading must fail to compile")
        .isFalse();
    }

    @Test
    void withDirective_ruleIsPinnedToParser() {
        var grammar = GrammarParser.parse(BODY + "%parser Label\n")
                                   .unwrap();

        assertThat(grammar.parserRules())
        .containsExactly("Label");
        assertThat(RuleClassifier.classify(grammar)
                                 .unwrap()
                                 .kinds())
        .containsEntry("Label", RuleKind.PARSER);
    }

    @Test
    void withDirective_grammarCompilesAndBothAlternativesMatch() {
        var parser = PegParser.fromGrammar(BODY + "%parser Label\n")
                              .unwrap();

        assertThat(parser.parse("abc;")
                         .diagnostics())
        .withFailMessage("a plain identifier must match the pinned rule")
        .isEmpty();
        assertThat(parser.parse("select;")
                         .diagnostics())
        .withFailMessage("a reserved word must match the pinned rule's second alternative")
        .isEmpty();
    }

    /** The pin must survive skip-prefix detection, which force-promotes rules to LEXER. */
    @Test
    void pinBeatsSkipPrefixPromotion() {
        var grammar = GrammarParser.parse("""
            Doc      <- Guarded ';'
            Guarded  <- !Reserved < [a-z]+ >
            Reserved <- ('select' / 'from') ![a-z0-9_]
            %whitespace <- [ \\t\\r\\n]*
            %parser Guarded
            """).unwrap();
        var classification = RuleClassifier.classify(grammar)
                                           .unwrap();

        assertThat(classification.kinds())
        .containsEntry("Guarded", RuleKind.PARSER);
        assertThat(classification.keywordSkip())
        .doesNotContainKey("Guarded");
    }

    /** A pin naming a rule that does not exist is inert, not fatal. */
    @Test
    void pinForUnknownRuleIsHarmless() {
        assertThat(PegParser.fromGrammar(BODY + "%parser Label\n%parser NoSuchRule\n").isSuccess())
        .isTrue();
    }
}
