package org.pragmatica.peg.incremental;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.pragmatica.peg.PegParser;
import org.pragmatica.peg.grammar.Grammar;
import org.pragmatica.peg.grammar.GrammarParser;
import org.pragmatica.peg.incremental.internal.CstHash;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SPEC §7.3 idempotency: applying an edit followed by its inverse must
 * restore the original tree's {@link CstHash}. Cursor may differ (both
 * edits move it), buffer is restored by construction of the inverse edit.
 */
final class IdempotencyTest {

    // Permissive grammar: intermediate buffers stay parseable after a
    // partial edit, so inverse-edit parity tests aren't forced through the
    // full-reparse fallback by an unparseable intermediate.
    private static final String GRAMMAR = """
        Program <- Token*
        Token <- Word / Punct
        Word <- < [a-zA-Z0-9_]+ >
        Punct <- < [=;+\\-*/(){}\\[\\].,:!<>] >
        %whitespace <- [ \\t\\n]*
        """;

    private static IncrementalParser parser() {
        Grammar g = GrammarParser.parse(GRAMMAR).fold(
            cause -> { throw new IllegalStateException(cause.message()); },
            r -> r);
        return IncrementalParser.create(g);
    }

    @Test
    @DisplayName("Insert then delete restores the CST hash")
    void insert_then_delete() {
        var s0 = parser().initialize("let x = 1;");
        long hash0 = CstHash.cstHash(s0.root());
        var s1 = s0.edit(9, 0, "42");
        var s2 = s1.edit(9, 2, "");
        assertThat(CstHash.cstHash(s2.root())).isEqualTo(hash0);
        assertThat(s2.text()).isEqualTo(s0.text());
    }

    @Test
    @DisplayName("Delete then insert restores the CST hash")
    void delete_then_insert() {
        var s0 = parser().initialize("let x = 123;");
        long hash0 = CstHash.cstHash(s0.root());
        var s1 = s0.edit(8, 3, "");
        var s2 = s1.edit(8, 0, "123");
        assertThat(CstHash.cstHash(s2.root())).isEqualTo(hash0);
        assertThat(s2.text()).isEqualTo(s0.text());
    }

    @Test
    @DisplayName("Replace then restore via inverse replacement")
    void replace_then_restore() {
        var s0 = parser().initialize("let x = 1; let y = 2;");
        long hash0 = CstHash.cstHash(s0.root());
        var s1 = s0.edit(4, 1, "foo");
        var s2 = s1.edit(4, 3, "x");
        assertThat(CstHash.cstHash(s2.root())).isEqualTo(hash0);
    }

    @Test
    @DisplayName("Undo via saved session reference is O(1)")
    void undo_via_session_reference() {
        var s0 = parser().initialize("let x = 1;");
        var s1 = s0.edit(9, 0, "42");
        // Simulate undo: restore s0. Tree reference is unchanged.
        assertThat(s0.root()).isNotNull();
        assertThat(s0.text()).isEqualTo("let x = 1;");
        assertThat(s1.text()).isEqualTo("let x = 142;");
        // Re-edit from s0 works independently of s1.
        var s2 = s0.edit(9, 0, "99");
        assertThat(s2.text()).isEqualTo("let x = 199;");
    }

    @Test
    @DisplayName("Session forking produces independent lineages")
    void session_forking() {
        var oracle = PegParser.fromGrammar(GRAMMAR).fold(
            cause -> { throw new IllegalStateException(cause.message()); }, p -> p);
        var s0 = parser().initialize("let x = 1;");
        var branchA = s0.edit(9, 0, "42");
        var branchB = s0.edit(0, 0, "let z = 0; ");
        assertThat(CstHash.cstHash(branchA.root()))
            .isEqualTo(CstHash.cstHash(oracle.parseCst(branchA.text()).unwrap()));
        assertThat(CstHash.cstHash(branchB.root()))
            .isEqualTo(CstHash.cstHash(oracle.parseCst(branchB.text()).unwrap()));
        // s0 unchanged.
        assertThat(s0.text()).isEqualTo("let x = 1;");
    }
}
