package org.pragmatica.peg.incremental.experimental;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.peg.tree.SourceSpan;
import org.pragmatica.peg.tree.Trivia;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 0b foundation tests for {@link IdCstNode}.
 *
 * <p>Asserts the {@code id}-as-metadata equality contract from spec §7 R1:
 * structural equality excludes the id, and cross-variant comparisons never
 * collide.
 */
final class IdCstNodeTest {
    private static final SourceSpan SPAN_A = new SourceSpan(1, 1, 0, 1, 4, 3);
    private static final SourceSpan SPAN_B = new SourceSpan(1, 5, 4, 1, 8, 7);
    private static final List<Trivia> NO_TRIVIA = List.of();

    @Nested
    @DisplayName("Construction and accessors")
    final class Construction {
        @Test
        @DisplayName("Terminal exposes its assigned id")
        void terminal_exposes_id() {
            var node = new IdCstNode.Terminal(42L, SPAN_A, "Number", "123", NO_TRIVIA, NO_TRIVIA);
            assertThat(node.id()).isEqualTo(42L);
            assertThat(node.span()).isEqualTo(SPAN_A);
            assertThat(node.rule()).isEqualTo("Number");
            assertThat(node.text()).isEqualTo("123");
        }

        @Test
        @DisplayName("NonTerminal exposes its assigned id and children")
        void nonterminal_exposes_id() {
            var child = new IdCstNode.Terminal(0L, SPAN_A, "Number", "1", NO_TRIVIA, NO_TRIVIA);
            var parent = new IdCstNode.NonTerminal(7L, SPAN_A, "Expr", List.of(child), NO_TRIVIA, NO_TRIVIA);
            assertThat(parent.id()).isEqualTo(7L);
            assertThat(parent.children()).containsExactly(child);
        }

        @Test
        @DisplayName("Token exposes its assigned id")
        void token_exposes_id() {
            var node = new IdCstNode.Token(9L, SPAN_A, "Ident", "foo", NO_TRIVIA, NO_TRIVIA);
            assertThat(node.id()).isEqualTo(9L);
            assertThat(node.text()).isEqualTo("foo");
        }

        @Test
        @DisplayName("Error exposes its assigned id and reports rule()=<error>")
        void error_exposes_id_and_default_rule() {
            var node = new IdCstNode.Error(11L, SPAN_A, "@@@", "expected number", NO_TRIVIA, NO_TRIVIA);
            assertThat(node.id()).isEqualTo(11L);
            assertThat(node.rule()).isEqualTo("<error>");
            assertThat(node.skippedText()).isEqualTo("@@@");
            assertThat(node.expected()).isEqualTo("expected number");
        }
    }

    @Nested
    @DisplayName("Equality excludes id (spec §7 R1)")
    final class Equality {
        @Test
        @DisplayName("Terminals with identical structure but different ids are equal")
        void terminal_equality_ignores_id() {
            var a = new IdCstNode.Terminal(0L, SPAN_A, "Number", "1", NO_TRIVIA, NO_TRIVIA);
            var b = new IdCstNode.Terminal(999L, SPAN_A, "Number", "1", NO_TRIVIA, NO_TRIVIA);
            assertThat(a).isEqualTo(b);
            assertThat(a.hashCode()).isEqualTo(b.hashCode());
        }

        @Test
        @DisplayName("Terminals with different text are not equal")
        void terminal_inequality_when_text_differs() {
            var a = new IdCstNode.Terminal(0L, SPAN_A, "Number", "1", NO_TRIVIA, NO_TRIVIA);
            var b = new IdCstNode.Terminal(0L, SPAN_A, "Number", "2", NO_TRIVIA, NO_TRIVIA);
            assertThat(a).isNotEqualTo(b);
        }

        @Test
        @DisplayName("NonTerminals are equal when children are structurally equal even with differing ids throughout")
        void nonterminal_equality_ignores_id_recursively() {
            var leftChild = new IdCstNode.Terminal(0L, SPAN_A, "Number", "1", NO_TRIVIA, NO_TRIVIA);
            var rightChild = new IdCstNode.Terminal(500L, SPAN_A, "Number", "1", NO_TRIVIA, NO_TRIVIA);
            var left = new IdCstNode.NonTerminal(1L, SPAN_A, "Expr", List.of(leftChild), NO_TRIVIA, NO_TRIVIA);
            var right = new IdCstNode.NonTerminal(900L, SPAN_A, "Expr", List.of(rightChild), NO_TRIVIA, NO_TRIVIA);
            assertThat(left).isEqualTo(right);
            assertThat(left.hashCode()).isEqualTo(right.hashCode());
        }

        @Test
        @DisplayName("NonTerminals with different rules are not equal")
        void nonterminal_inequality_when_rule_differs() {
            var a = new IdCstNode.NonTerminal(0L, SPAN_A, "Expr", List.of(), NO_TRIVIA, NO_TRIVIA);
            var b = new IdCstNode.NonTerminal(0L, SPAN_A, "Stmt", List.of(), NO_TRIVIA, NO_TRIVIA);
            assertThat(a).isNotEqualTo(b);
        }

        @Test
        @DisplayName("Tokens with identical structure but different ids are equal")
        void token_equality_ignores_id() {
            var a = new IdCstNode.Token(2L, SPAN_A, "Ident", "x", NO_TRIVIA, NO_TRIVIA);
            var b = new IdCstNode.Token(8L, SPAN_A, "Ident", "x", NO_TRIVIA, NO_TRIVIA);
            assertThat(a).isEqualTo(b);
            assertThat(a.hashCode()).isEqualTo(b.hashCode());
        }

        @Test
        @DisplayName("Tokens with different spans are not equal")
        void token_inequality_when_span_differs() {
            var a = new IdCstNode.Token(0L, SPAN_A, "Ident", "x", NO_TRIVIA, NO_TRIVIA);
            var b = new IdCstNode.Token(0L, SPAN_B, "Ident", "x", NO_TRIVIA, NO_TRIVIA);
            assertThat(a).isNotEqualTo(b);
        }

        @Test
        @DisplayName("Errors with identical structure but different ids are equal")
        void error_equality_ignores_id() {
            var a = new IdCstNode.Error(0L, SPAN_A, "@@@", "expected number", NO_TRIVIA, NO_TRIVIA);
            var b = new IdCstNode.Error(123L, SPAN_A, "@@@", "expected number", NO_TRIVIA, NO_TRIVIA);
            assertThat(a).isEqualTo(b);
            assertThat(a.hashCode()).isEqualTo(b.hashCode());
        }

        @Test
        @DisplayName("Errors with different skippedText are not equal")
        void error_inequality_when_skipped_differs() {
            var a = new IdCstNode.Error(0L, SPAN_A, "@@@", "expected number", NO_TRIVIA, NO_TRIVIA);
            var b = new IdCstNode.Error(0L, SPAN_A, "###", "expected number", NO_TRIVIA, NO_TRIVIA);
            assertThat(a).isNotEqualTo(b);
        }

        @Test
        @DisplayName("Cross-variant comparison is never equal even with overlapping fields")
        void cross_variant_never_equal() {
            var terminal = new IdCstNode.Terminal(0L, SPAN_A, "Ident", "x", NO_TRIVIA, NO_TRIVIA);
            var token = new IdCstNode.Token(0L, SPAN_A, "Ident", "x", NO_TRIVIA, NO_TRIVIA);
            assertThat(terminal).isNotEqualTo(token);
            assertThat(token).isNotEqualTo(terminal);
        }
    }
}
