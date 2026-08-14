package org.pragmatica.peg.grammar;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 0.7.1 — grammar-level {@code %memo RuleName} directive parsing.
 *
 * <p>The directive designates rules whose successful parse the generated parser
 * memoises at a token position (targeted packrat), consumed by
 * {@code ParserGenerator}. Multiple directives accumulate into a set; unknown
 * rule names are accepted (silently ignored by the generator, matching the
 * relaxed handling of {@code %checkpoint}).
 */
class MemoDirectiveTest {

    @Test
    void parse_singleMemoDirective_populatesMemoSet() {
        GrammarParser.parse("""
                                %memo Args
                                Args <- 'a' (',' 'a')*
                                """)
                     .onFailure(cause -> fail(cause.message()))
                     .onSuccess(grammar -> {
                         var memo = grammar.memoRules();
                         assertEquals(1, memo.size());
                         assertTrue(memo.contains("Args"));
                     });
    }

    @Test
    void parse_multipleAndDuplicateMemoDirectives_accumulateAsSet() {
        GrammarParser.parse("""
                                %memo Args
                                %memo Params
                                %memo Args
                                Args <- 'a'
                                Params <- 'b'
                                """)
                     .onFailure(cause -> fail(cause.message()))
                     .onSuccess(grammar -> {
                         var memo = grammar.memoRules();
                         assertEquals(2, memo.size());
                         assertTrue(memo.contains("Args"));
                         assertTrue(memo.contains("Params"));
                     });
    }

    @Test
    void parse_memoReferencingUnknownRule_isAccepted() {
        // Relaxed-directive principle: unknown rule names parse without error
        // and are simply ignored by the generator. Mirrors %checkpoint.
        GrammarParser.parse("""
                                %memo NoSuchRule
                                Stmt <- 'a'
                                """)
                     .onFailure(cause -> fail(cause.message()))
                     .onSuccess(grammar -> {
                         var memo = grammar.memoRules();
                         assertEquals(1, memo.size());
                         assertTrue(memo.contains("NoSuchRule"));
                     });
    }

    @Test
    void parse_noMemoDirective_emptySet() {
        GrammarParser.parse("Stmt <- 'a'")
                     .onFailure(cause -> fail(cause.message()))
                     .onSuccess(grammar -> assertTrue(grammar.memoRules().isEmpty()));
    }

    @Test
    void parse_memoDirective_coexistsWithCheckpointAndRecover() {
        GrammarParser.parse("""
                                %whitespace <- [ \\t]*
                                %recover [;] Stmt
                                %checkpoint Stmt
                                %memo Args
                                Stmt <- Args ';'
                                Args <- 'a' (',' 'a')*
                                """)
                     .onFailure(cause -> fail(cause.message()))
                     .onSuccess(grammar -> {
                         assertEquals(1, grammar.checkpointRules().size());
                         assertEquals(1, grammar.recoverSets().size());
                         assertEquals(1, grammar.memoRules().size());
                         assertTrue(grammar.memoRules().contains("Args"));
                     });
    }
}
