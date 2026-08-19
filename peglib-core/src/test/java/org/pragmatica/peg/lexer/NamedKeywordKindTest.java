package org.pragmatica.peg.lexer;

import org.junit.jupiter.api.Test;
import org.pragmatica.peg.grammar.GrammarParser;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 0.7.2 — a lexeme gets ONE kind, named after the rule that names it.
 *
 * <p>A rule whose body is a single literal already owns a kind. When the same literal also appears
 * inline in a parser rule, a second synthetic {@code INLINE_*} kind was allocated for the same
 * text; the inline kind won (inline literals outrank user rules) and the rule's kind was left
 * unreachable. Parsing survived — the generator matches the alias — but the token was anonymous:
 * the CST reported a bare literal where the grammar had named a rule, so every consumer keying on
 * the rule name saw nothing.
 *
 * <p>Reported downstream as 66 colliding keyword rules across 72 consumer call sites.
 */
class NamedKeywordKindTest {

    private static String kindNameOf(String grammarText, String input) {
        var grammar = GrammarParser.parse(grammarText)
                                   .unwrap();
        var classification = RuleClassifier.classify(grammar)
                                           .unwrap();
        var built = DfaBuilder.build(grammar, classification)
                              .unwrap();
        var names = built.kinds()
                         .kindNameTable();
        var engine = new LexerEngine(built.dfa(),
                                     names,
                                     DfaBuilder.KIND_WHITESPACE,
                                     built.kinds()
                                          .keywordResolutions());
        var tokens = engine.lex(input);

        for (int i = 0; i < tokens.count(); i++) {
            if (!tokens.isTrivia(i)) {
                return names[tokens.kindAt(i)];
            }
        }

        return "";
    }

    /** The literal appears BOTH as a named rule and inline in a parser rule — the collision. */
    @Test
    void keywordAlsoSpelledInlineKeepsTheRuleName() {
        var grammar = """
            Stmt     <- CreateKW Ident / Reserved Ident
            CreateKW <- < 'CREATE'i ![a-zA-Z0-9_$] >
            Reserved <- ('CREATE'i / 'SELECT'i) ![a-zA-Z0-9_$]
            Ident    <- < [a-z] [a-z0-9_]* >
            %whitespace <- [ \\t\\r\\n]*
            """;

        assertThat(kindNameOf(grammar, "CREATE foo"))
        .withFailMessage("the lexeme must carry the name the grammar gave it, not a synthetic INLINE_ kind")
        .isEqualTo("CreateKW");
    }

    /** A rule spelling SEVERAL literals has no single lexeme to name, so it keeps aliasing. */
    @Test
    void multiLiteralRuleStillUsesSyntheticKinds() {
        var grammar = """
            Stmt  <- CmpOp Ident
            CmpOp <- '<=' / '>=' / '='
            Ident <- < [a-z] [a-z0-9_]* >
            %whitespace <- [ \\t\\r\\n]*
            """;

        assertThat(kindNameOf(grammar, "<= foo"))
        .withFailMessage("a multi-literal rule must not claim one lexeme's kind")
        .isNotEqualTo("CmpOp");
    }
}
