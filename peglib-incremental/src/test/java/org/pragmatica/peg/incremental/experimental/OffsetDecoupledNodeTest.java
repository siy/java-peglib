package org.pragmatica.peg.incremental.experimental;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.pragmatica.peg.tree.Trivia;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Minimal contract tests for {@link OffsetDecoupledNode}. The path-A spike
 * design is exhaustively exercised by {@link OffsetDecoupledSplicerTest};
 * this file pins the equality contract and sealed-switch exhaustiveness.
 */
final class OffsetDecoupledNodeTest {
    private static final List<Trivia> NO_TRIVIA = List.of();

    @Test
    @DisplayName("Terminal: constructs and exposes its components")
    void terminal_construct() {
        var t = new OffsetDecoupledNode.Terminal(1L, "Lit", "x", NO_TRIVIA, NO_TRIVIA);
        assertThat(t.id()).isEqualTo(1L);
        assertThat(t.rule()).isEqualTo("Lit");
        assertThat(t.text()).isEqualTo("x");
        assertThat(t.leadingTrivia()).isEmpty();
        assertThat(t.trailingTrivia()).isEmpty();
    }

    @Test
    @DisplayName("NonTerminal: constructs with children")
    void nonterminal_construct() {
        var c1 = new OffsetDecoupledNode.Terminal(1L, "T", "a", NO_TRIVIA, NO_TRIVIA);
        var c2 = new OffsetDecoupledNode.Terminal(2L, "T", "b", NO_TRIVIA, NO_TRIVIA);
        var nt = new OffsetDecoupledNode.NonTerminal(3L, "Pair", List.of(c1, c2), NO_TRIVIA, NO_TRIVIA);

        assertThat(nt.id()).isEqualTo(3L);
        assertThat(nt.rule()).isEqualTo("Pair");
        assertThat(nt.children()).hasSize(2);
        assertThat(nt.children().get(0)).isSameAs(c1);
        assertThat(nt.children().get(1)).isSameAs(c2);
    }

    @Test
    @DisplayName("Token: constructs and exposes text")
    void token_construct() {
        var tok = new OffsetDecoupledNode.Token(1L, "Number", "42", NO_TRIVIA, NO_TRIVIA);
        assertThat(tok.id()).isEqualTo(1L);
        assertThat(tok.rule()).isEqualTo("Number");
        assertThat(tok.text()).isEqualTo("42");
    }

    @Test
    @DisplayName("Equality excludes id (R1: ids are metadata)")
    void equality_excludes_id() {
        var a = new OffsetDecoupledNode.Terminal(1L, "T", "x", NO_TRIVIA, NO_TRIVIA);
        var b = new OffsetDecoupledNode.Terminal(2L, "T", "x", NO_TRIVIA, NO_TRIVIA);
        var c = new OffsetDecoupledNode.Terminal(3L, "T", "y", NO_TRIVIA, NO_TRIVIA);

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
        assertThat(a).isNotEqualTo(c);
    }

    @Test
    @DisplayName("Different variants are never equal even if structural fields match")
    void variants_never_cross_equal() {
        var term = new OffsetDecoupledNode.Terminal(1L, "T", "x", NO_TRIVIA, NO_TRIVIA);
        var tok = new OffsetDecoupledNode.Token(1L, "T", "x", NO_TRIVIA, NO_TRIVIA);

        assertThat(term).isNotEqualTo(tok);
        assertThat(tok).isNotEqualTo(term);
    }

    @Test
    @DisplayName("Sealed switch is exhaustive over all three variants")
    void sealed_switch_exhaustive() {
        OffsetDecoupledNode term = new OffsetDecoupledNode.Terminal(1L, "T", "x", NO_TRIVIA, NO_TRIVIA);
        OffsetDecoupledNode tok = new OffsetDecoupledNode.Token(2L, "T", "y", NO_TRIVIA, NO_TRIVIA);
        OffsetDecoupledNode nt = new OffsetDecoupledNode.NonTerminal(3L, "Pair", List.of(), NO_TRIVIA, NO_TRIVIA);

        assertThat(label(term)).isEqualTo("Terminal");
        assertThat(label(tok)).isEqualTo("Token");
        assertThat(label(nt)).isEqualTo("NonTerminal");
    }

    private static String label(OffsetDecoupledNode node) {
        return switch (node) {
            case OffsetDecoupledNode.Terminal t -> "Terminal";
            case OffsetDecoupledNode.Token t -> "Token";
            case OffsetDecoupledNode.NonTerminal n -> "NonTerminal";
        };
    }
}
