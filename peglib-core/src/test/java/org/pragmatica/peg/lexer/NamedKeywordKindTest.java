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

    /**
     * The same literal claimed by a MULTI-literal rule and a single-literal rule, with the
     * multi-literal one declared FIRST — the order PostgreSQL's grammar uses (`ReservedKeyword`
     * at line 508, `CaseKW` at 567).
     *
     * <p>Adoption must not depend on declaration order. Redirecting the literal to the rule's kind
     * after the multi-literal rule already captured the synthetic kind leaves that rule's alias
     * array pointing at a kind the lexer no longer emits, and every reference to it silently stops
     * matching. Declared the other way round it happened to work, which is exactly the asymmetry
     * that made this hard to see.
     */
    @Test
    void adoptionIsIndependentOfDeclarationOrder() {
        var grammar = """
            Stmt     <- CaseExpr
            CaseExpr <- CaseKW Ident EndKW
            Reserved <- ('CASE'i / 'END'i / 'SELECT'i) ![a-z0-9_]
            CaseKW   <- < 'CASE'i ![a-z0-9_] >
            EndKW    <- < 'END'i ![a-z0-9_] >
            Ident    <- !Reserved < [a-z] [a-z0-9_]* >
            %whitespace <- [ \\t\\r\\n]*
            """;

        assertThat(kindNameOf(grammar, "CASE foo END"))
        .withFailMessage("the keyword must adopt its rule's kind even when a multi-literal rule is declared first")
        .isEqualTo("CaseKW");
        // The mechanism, asserted directly: an alias array captured BEFORE adoption redirected the
        // literal still names the old synthetic kind, which the lexer no longer emits, so every
        // reference to that rule silently stops matching. Whether a given grammar then fails to
        // parse depends on what else competes for the text — so assert the invariant, not a parse.
        assertThat(staleAliasKinds(grammar))
        .withFailMessage("every alias kind must be one the lexer still emits for that literal")
        .isEmpty();
    }

    /** Alias kinds that no longer agree with the literal-to-kind map — i.e. captured too early. */
    private static java.util.List<String> staleAliasKinds(String grammarText) {
        var grammar = GrammarParser.parse(grammarText)
                                   .unwrap();
        var classification = RuleClassifier.classify(grammar)
                                           .unwrap();
        var built = DfaBuilder.build(grammar, classification)
                              .unwrap();
        var live = new java.util.HashSet<>(built.kinds()
                                                .inlineLiteralToKind()
                                                .values());
        var stale = new java.util.ArrayList<String>();

        built.kinds()
             .ruleNameToAliasKinds()
             .forEach((rule, kinds) -> {
                 for (var kind : kinds) {
                     if (!live.contains(kind)) {
                         stale.add(rule + " -> " + kind);
                     }
                 }
             });

        return stale;
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
