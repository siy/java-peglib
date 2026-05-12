package org.pragmatica.peg.grammar;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 0.6.1 — grammar-level {@code %checkpoint RuleName} directive parsing.
 *
 * <p>The directive declares incremental-reparse boundaries consumed by
 * {@code IncrementalParser}. Multiple directives accumulate into a set;
 * unknown rule names are accepted (silently ignored by the engine, matching
 * the relaxed handling of grammar-level {@code %recover}).
 */
class CheckpointDirectiveTest {

    @Test
    void parse_singleCheckpointDirective_populatesCheckpointSet() {
        GrammarParser.parse("""
                                %checkpoint Stmt
                                Stmt <- 'a' / 'b'
                                """)
                     .onFailure(cause -> fail(cause.message()))
                     .onSuccess(grammar -> {
                         var checkpoints = grammar.checkpointRules();
                         assertEquals(1, checkpoints.size());
                         assertTrue(checkpoints.contains("Stmt"));
                     });
    }

    @Test
    void parse_multipleCheckpointDirectives_accumulate() {
        GrammarParser.parse("""
                                %checkpoint Stmt
                                %checkpoint MethodDecl
                                %checkpoint TypeDecl
                                Stmt <- 'a'
                                MethodDecl <- 'b'
                                TypeDecl <- 'c'
                                """)
                     .onFailure(cause -> fail(cause.message()))
                     .onSuccess(grammar -> {
                         var checkpoints = grammar.checkpointRules();
                         assertEquals(3, checkpoints.size());
                         assertTrue(checkpoints.contains("Stmt"));
                         assertTrue(checkpoints.contains("MethodDecl"));
                         assertTrue(checkpoints.contains("TypeDecl"));
                     });
    }

    @Test
    void parse_duplicateCheckpointDirective_deduplicates() {
        // Set semantics: repeated declarations of the same rule collapse to one entry.
        GrammarParser.parse("""
                                %checkpoint Stmt
                                %checkpoint Stmt
                                Stmt <- 'a'
                                """)
                     .onFailure(cause -> fail(cause.message()))
                     .onSuccess(grammar -> {
                         var checkpoints = grammar.checkpointRules();
                         assertEquals(1, checkpoints.size());
                         assertTrue(checkpoints.contains("Stmt"));
                     });
    }

    @Test
    void parse_checkpointReferencingUnknownRule_isAccepted() {
        // Relaxed-directive principle: unknown rule names parse without error
        // and are simply ignored by the engine. Mirrors %recover semantics.
        GrammarParser.parse("""
                                %checkpoint NoSuchRule
                                Stmt <- 'a'
                                """)
                     .onFailure(cause -> fail(cause.message()))
                     .onSuccess(grammar -> {
                         var checkpoints = grammar.checkpointRules();
                         assertEquals(1, checkpoints.size());
                         assertTrue(checkpoints.contains("NoSuchRule"));
                     });
    }

    @Test
    void parse_noCheckpointDirective_emptySet() {
        GrammarParser.parse("Stmt <- 'a'")
                     .onFailure(cause -> fail(cause.message()))
                     .onSuccess(grammar -> {
                         var checkpoints = grammar.checkpointRules();
                         assertTrue(checkpoints.isEmpty());
                         assertFalse(checkpoints.contains("Stmt"));
                     });
    }

    @Test
    void parse_checkpointDirective_coexistsWithRecoverAndWhitespace() {
        // Sanity: the new directive doesn't perturb the parsing of others.
        GrammarParser.parse("""
                                %whitespace <- [ \\t]*
                                %recover [;] Stmt
                                %checkpoint Stmt
                                Stmt <- 'a' / 'b'
                                """)
                     .onFailure(cause -> fail(cause.message()))
                     .onSuccess(grammar -> {
                         assertTrue(grammar.whitespace().isPresent());
                         assertEquals(1, grammar.recoverSets().size());
                         assertTrue(grammar.recoverSets().containsKey("Stmt"));
                         assertEquals(1, grammar.checkpointRules().size());
                         assertTrue(grammar.checkpointRules().contains("Stmt"));
                     });
    }
}
