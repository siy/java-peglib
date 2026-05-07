package org.pragmatica.peg.incremental.experimental;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.pragmatica.peg.tree.CstNode;
import org.pragmatica.peg.tree.SourceSpan;
import org.pragmatica.peg.tree.Trivia;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 0b builder tests — confirm post-order id assignment and
 * field round-tripping for every production {@link CstNode} variant.
 */
final class IdCstNodeBuilderTest {
    private static final SourceSpan SPAN = new SourceSpan(1, 1, 0, 1, 4, 3);
    private static final SourceSpan SPAN_INNER = new SourceSpan(1, 1, 0, 1, 2, 1);
    private static final List<Trivia> NO_TRIVIA = List.of();

    private IdCstNodeBuilder freshBuilder() {
        return new IdCstNodeBuilder(new IdGenerator.PerSessionCounter());
    }

    @Test
    @DisplayName("Single Terminal gets id 0")
    void single_terminal_gets_id_zero() {
        CstNode source = new CstNode.Terminal(0L, SPAN, "Number", "123", NO_TRIVIA, NO_TRIVIA);
        var built = freshBuilder().build(source);
        assertThat(built).isInstanceOf(IdCstNode.Terminal.class);
        var terminal = (IdCstNode.Terminal) built;
        assertThat(terminal.id()).isEqualTo(0L);
        assertThat(terminal.span()).isEqualTo(SPAN);
        assertThat(terminal.rule()).isEqualTo("Number");
        assertThat(terminal.text()).isEqualTo("123");
    }

    @Test
    @DisplayName("NonTerminal with three Terminal children: children 0,1,2 / parent 3 (post-order)")
    void nonterminal_post_order_ids() {
        var c1 = new CstNode.Terminal(0L, SPAN, "Number", "1", NO_TRIVIA, NO_TRIVIA);
        var c2 = new CstNode.Terminal(0L, SPAN, "Number", "2", NO_TRIVIA, NO_TRIVIA);
        var c3 = new CstNode.Terminal(0L, SPAN, "Number", "3", NO_TRIVIA, NO_TRIVIA);
        CstNode parent = new CstNode.NonTerminal(0L, SPAN, "Expr", List.of(c1, c2, c3), NO_TRIVIA, NO_TRIVIA);

        var built = (IdCstNode.NonTerminal) freshBuilder().build(parent);

        assertThat(built.id()).isEqualTo(3L);
        assertThat(built.children()).hasSize(3);
        assertThat(built.children().get(0).id()).isEqualTo(0L);
        assertThat(built.children().get(1).id()).isEqualTo(1L);
        assertThat(built.children().get(2).id()).isEqualTo(2L);
    }

    @Test
    @DisplayName("3-deep nested NonTerminal -> NonTerminal -> Terminal: 0,1,2 inside-out")
    void three_deep_nested_ids() {
        var leaf = new CstNode.Terminal(0L, SPAN_INNER, "Atom", "x", NO_TRIVIA, NO_TRIVIA);
        var middle = new CstNode.NonTerminal(0L, SPAN_INNER, "Inner", List.of(leaf), NO_TRIVIA, NO_TRIVIA);
        CstNode root = new CstNode.NonTerminal(0L, SPAN, "Outer", List.of(middle), NO_TRIVIA, NO_TRIVIA);

        var builtRoot = (IdCstNode.NonTerminal) freshBuilder().build(root);
        var builtMiddle = (IdCstNode.NonTerminal) builtRoot.children().get(0);
        var builtLeaf = (IdCstNode.Terminal) builtMiddle.children().get(0);

        assertThat(builtLeaf.id()).isEqualTo(0L);
        assertThat(builtMiddle.id()).isEqualTo(1L);
        assertThat(builtRoot.id()).isEqualTo(2L);
    }

    @Test
    @DisplayName("Mixed tree with Token and Error preserves all fields and assigns ids to every node")
    void mixed_variants_round_trip() {
        var token = new CstNode.Token(0L, SPAN_INNER, "Ident", "foo", NO_TRIVIA, NO_TRIVIA);
        var error = new CstNode.Error(0L, SPAN_INNER, "@@@", "expected ident", NO_TRIVIA, NO_TRIVIA);
        var terminal = new CstNode.Terminal(0L, SPAN_INNER, "Number", "1", NO_TRIVIA, NO_TRIVIA);
        CstNode root = new CstNode.NonTerminal(0L, SPAN, "Mixed", List.of(token, error, terminal), NO_TRIVIA, NO_TRIVIA);

        var builtRoot = (IdCstNode.NonTerminal) freshBuilder().build(root);

        assertThat(builtRoot.children()).hasSize(3);
        var builtToken = (IdCstNode.Token) builtRoot.children().get(0);
        var builtError = (IdCstNode.Error) builtRoot.children().get(1);
        var builtTerminal = (IdCstNode.Terminal) builtRoot.children().get(2);

        assertThat(builtToken.id()).isEqualTo(0L);
        assertThat(builtToken.text()).isEqualTo("foo");
        assertThat(builtError.id()).isEqualTo(1L);
        assertThat(builtError.skippedText()).isEqualTo("@@@");
        assertThat(builtError.expected()).isEqualTo("expected ident");
        assertThat(builtError.rule()).isEqualTo("<error>");
        assertThat(builtTerminal.id()).isEqualTo(2L);
        assertThat(builtTerminal.text()).isEqualTo("1");
        assertThat(builtRoot.id()).isEqualTo(3L);
    }

    @Test
    @DisplayName("Trivia lists are reference-equal pre/post conversion")
    void trivia_reference_identity_preserved() {
        var leading = List.<Trivia>of(new Trivia.Whitespace(SPAN_INNER, " "));
        var trailing = List.<Trivia>of(new Trivia.LineComment(SPAN_INNER, "// done"));
        CstNode source = new CstNode.Terminal(0L, SPAN, "Number", "1", leading, trailing);

        var built = (IdCstNode.Terminal) freshBuilder().build(source);

        assertThat(built.leadingTrivia()).isSameAs(leading);
        assertThat(built.trailingTrivia()).isSameAs(trailing);
    }

    @Test
    @DisplayName("Two builds with independent counters yield equal trees (id excluded from equality)")
    void independent_builds_are_structurally_equal() {
        var c1 = new CstNode.Terminal(0L, SPAN_INNER, "Number", "1", NO_TRIVIA, NO_TRIVIA);
        var c2 = new CstNode.Terminal(0L, SPAN_INNER, "Number", "2", NO_TRIVIA, NO_TRIVIA);
        CstNode source = new CstNode.NonTerminal(0L, SPAN, "Expr", List.of(c1, c2), NO_TRIVIA, NO_TRIVIA);

        var first = freshBuilder().build(source);
        var second = freshBuilder().build(source);

        assertThat(first).isEqualTo(second);
        assertThat(first.hashCode()).isEqualTo(second.hashCode());
    }
}
