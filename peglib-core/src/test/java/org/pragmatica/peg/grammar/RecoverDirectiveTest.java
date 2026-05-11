package org.pragmatica.peg.grammar;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 0.6.0 — grammar-level {@code %recover [chars] RuleName} directive parsing.
 *
 * <p>The directive lets users specify a per-rule sync set used by panic-mode
 * recovery instead of the default {@code {; , } ) ]}}. Multiple directives
 * are allowed (one entry per rule). Rule-level {@code %recover "msg"} —
 * which takes a string-literal argument — is preserved unchanged; the
 * grammar parser disambiguates by looking at the token following
 * {@code %recover}.
 */
class RecoverDirectiveTest {

    @Test
    void parse_singleRecoverDirective_populatesRecoverSet() {
        GrammarParser.parse("""
                                %recover [;,] Stmt
                                Stmt <- 'a' / 'b'
                                """)
                     .onFailure(cause -> fail(cause.message()))
                     .onSuccess(grammar -> {
                         var sets = grammar.recoverSets();
                         assertEquals(1, sets.size());
                         var stmtSet = sets.get("Stmt");
                         assertNotNull(stmtSet);
                         assertEquals(2, stmtSet.size());
                         assertTrue(stmtSet.contains(';'));
                         assertTrue(stmtSet.contains(','));
                     });
    }

    @Test
    void parse_multipleRecoverDirectives_populatesAllEntries() {
        GrammarParser.parse("""
                                %recover [;] Stmt
                                %recover [|] Expr
                                Stmt <- 'a'
                                Expr <- 'b'
                                """)
                     .onFailure(cause -> fail(cause.message()))
                     .onSuccess(grammar -> {
                         var sets = grammar.recoverSets();
                         assertEquals(2, sets.size());
                         assertTrue(sets.get("Stmt").contains(';'));
                         assertTrue(sets.get("Expr").contains('|'));
                     });
    }

    @Test
    void parse_noRecoverDirective_emptyMap() {
        GrammarParser.parse("Stmt <- 'a'")
                     .onFailure(cause -> fail(cause.message()))
                     .onSuccess(grammar -> assertTrue(grammar.recoverSets().isEmpty()));
    }

    @Test
    void parse_recoverWithRange_expandsToAllChars() {
        GrammarParser.parse("""
                                %recover [a-c] Stmt
                                Stmt <- 'x'
                                """)
                     .onFailure(cause -> fail(cause.message()))
                     .onSuccess(grammar -> {
                         var stmtSet = grammar.recoverSets().get("Stmt");
                         assertEquals(3, stmtSet.size());
                         assertTrue(stmtSet.contains('a'));
                         assertTrue(stmtSet.contains('b'));
                         assertTrue(stmtSet.contains('c'));
                     });
    }

    @Test
    void parse_recoverMissingRuleName_failsWithDiagnostic() {
        var result = GrammarParser.parse("""
                                             %recover [;,]
                                             Stmt <- 'a'
                                             """);
        assertTrue(result.isFailure(), "expected parse to fail when rule name is absent");
    }

    @Test
    void parse_recoverDirectiveBetweenRules_isAccepted() {
        GrammarParser.parse("""
                                Stmt <- 'a'
                                %recover [;] Stmt
                                Expr <- 'b'
                                """)
                     .onFailure(cause -> fail(cause.message()))
                     .onSuccess(grammar -> {
                         assertEquals(2, grammar.rules().size());
                         var stmtSet = grammar.recoverSets().get("Stmt");
                         assertNotNull(stmtSet);
                         assertTrue(stmtSet.contains(';'));
                     });
    }

    @Test
    void parse_ruleLevelRecoverWithStringLiteral_doesNotPopulateGrammarMap() {
        // The existing pre-0.6.0 rule-level form: %recover "msg" attaches a
        // recovery message to the rule itself; it does NOT contribute to the
        // grammar-level recoverSets map. This guarantees back-compat.
        GrammarParser.parse("""
                                Stmt <- 'a' %recover ";"
                                """)
                     .onFailure(cause -> fail(cause.message()))
                     .onSuccess(grammar -> {
                         assertTrue(grammar.recoverSets().isEmpty());
                         var rule = grammar.rules().getFirst();
                         assertTrue(rule.hasRecover());
                         assertEquals(";", rule.recover().unwrap());
                     });
    }

    @Test
    void parse_recoverWithEscape_decodesEscapeSequence() {
        GrammarParser.parse("""
                                %recover [\\n;] Stmt
                                Stmt <- 'a'
                                """)
                     .onFailure(cause -> fail(cause.message()))
                     .onSuccess(grammar -> {
                         var stmtSet = grammar.recoverSets().get("Stmt");
                         assertNotNull(stmtSet);
                         assertTrue(stmtSet.contains('\n'));
                         assertTrue(stmtSet.contains(';'));
                         assertFalse(stmtSet.contains('\\'));
                     });
    }
}
