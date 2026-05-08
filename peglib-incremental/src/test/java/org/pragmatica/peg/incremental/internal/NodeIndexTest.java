package org.pragmatica.peg.incremental.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.peg.tree.CstNode;
import org.pragmatica.peg.tree.SourceSpan;
import org.pragmatica.peg.tree.Trivia;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link NodeIndex#smallestEnclosing(int, int)} — Phase 2 (v0.5.0)
 * Lever B top-down boundary descent. Builds tiny synthetic CSTs by hand so the
 * test asserts the descent contract directly, independent of the grammar parser
 * or {@code parseRuleAt} machinery.
 *
 * <p>Tree shape used by most cases:
 * <pre>{@code
 *   root  [0..30]      "Block"
 *     a   [0..10]      "Stmt"
 *       a1 [0..5]      "Token"  (Terminal)
 *       a2 [5..10]     "Token"  (Terminal)
 *     b   [10..20]     "Stmt"
 *       b1 [10..15]    "Token"
 *       b2 [15..20]    "Token"
 *     c   [20..30]     "Stmt"
 *       c1 [20..25]    "Token"
 *       c2 [25..30]    "Token"
 * }</pre>
 */
final class NodeIndexTest {
    private final AtomicLong ids = new AtomicLong(1);

    private CstNode terminal(int start, int end, String rule, String text) {
        return new CstNode.Terminal(
            ids.getAndIncrement(),
            span(start, end),
            rule,
            text,
            List.<Trivia>of(),
            List.<Trivia>of());
    }

    private CstNode nonTerminal(int start, int end, String rule, List<CstNode> children) {
        return new CstNode.NonTerminal(
            ids.getAndIncrement(),
            span(start, end),
            rule,
            children,
            List.<Trivia>of(),
            List.<Trivia>of());
    }

    private static SourceSpan span(int start, int end) {
        return new SourceSpan(1, start + 1, start, 1, end + 1, end);
    }

    private CstNode buildSampleTree() {
        var a1 = terminal(0, 5, "Token", "aaaaa");
        var a2 = terminal(5, 10, "Token", "bbbbb");
        var a = nonTerminal(0, 10, "Stmt", List.of(a1, a2));
        var b1 = terminal(10, 15, "Token", "ccccc");
        var b2 = terminal(15, 20, "Token", "ddddd");
        var b = nonTerminal(10, 20, "Stmt", List.of(b1, b2));
        var c1 = terminal(20, 25, "Token", "eeeee");
        var c2 = terminal(25, 30, "Token", "fffff");
        var c = nonTerminal(20, 30, "Stmt", List.of(c1, c2));
        return nonTerminal(0, 30, "Block", List.of(a, b, c));
    }

    @Nested
    @DisplayName("smallestEnclosing")
    class SmallestEnclosing {

        @Test
        @DisplayName("returns deepest leaf when edit fits inside one terminal")
        void editFullyInsideLeaf() {
            var root = buildSampleTree();
            var index = NodeIndex.build(root);

            // Edit [12..14] is inside b1 [10..15].
            var pivot = index.smallestEnclosing(12, 14);

            assertThat(pivot.isPresent()).isTrue();
            var node = pivot.unwrap();
            assertThat(node.span().startOffset()).isEqualTo(10);
            assertThat(node.span().endOffset()).isEqualTo(15);
            assertThat(node.rule()).isEqualTo("Token");
        }

        @Test
        @DisplayName("returns parent when edit straddles two children")
        void editStraddlesChildBoundary() {
            var root = buildSampleTree();
            var index = NodeIndex.build(root);

            // Edit [3..7] straddles a1 [0..5] and a2 [5..10] — both inside a.
            // No single child of a contains [3..7], so a itself is the pivot.
            var pivot = index.smallestEnclosing(3, 7);

            assertThat(pivot.isPresent()).isTrue();
            var node = pivot.unwrap();
            assertThat(node.span().startOffset()).isEqualTo(0);
            assertThat(node.span().endOffset()).isEqualTo(10);
            assertThat(node.rule()).isEqualTo("Stmt");
        }

        @Test
        @DisplayName("returns root when edit straddles two top-level children")
        void editStraddlesTopLevelBoundary() {
            var root = buildSampleTree();
            var index = NodeIndex.build(root);

            // Edit [8..22] straddles a [0..10], b [10..20], and c [20..30] —
            // no single root child contains it, so root is the pivot.
            var pivot = index.smallestEnclosing(8, 22);

            assertThat(pivot.isPresent()).isTrue();
            var node = pivot.unwrap();
            assertThat(node.span().startOffset()).isEqualTo(0);
            assertThat(node.span().endOffset()).isEqualTo(30);
            assertThat(node.rule()).isEqualTo("Block");
        }

        @Test
        @DisplayName("zero-length insertion at an interior boundary picks adjacent child")
        void zeroLengthInsertionAtBoundary() {
            var root = buildSampleTree();
            var index = NodeIndex.build(root);

            // Zero-length insert at offset 5 — boundary between a1 [0..5] and a2 [5..10].
            // contains() is inclusive on both ends, so the FIRST child that
            // satisfies start <= 5 <= end wins → a1 is selected over a2.
            var pivot = index.smallestEnclosing(5, 5);

            assertThat(pivot.isPresent()).isTrue();
            var node = pivot.unwrap();
            assertThat(node.span().startOffset()).isEqualTo(0);
            assertThat(node.span().endOffset()).isEqualTo(5);
        }

        @Test
        @DisplayName("returns none when edit exceeds root span (append past EOF)")
        void editExceedsRoot() {
            var root = buildSampleTree();
            var index = NodeIndex.build(root);

            // Edit [25..40] extends beyond root.endOffset() = 30 — root cannot
            // contain it, so descent is impossible.
            var pivot = index.smallestEnclosing(25, 40);

            assertThat(pivot.isEmpty()).isTrue();
        }

        @Test
        @DisplayName("returns none when edit starts before root span")
        void editBeforeRoot() {
            // Build a tree whose root does NOT start at offset 0.
            var t = terminal(10, 20, "Token", "xxxxxxxxxx");
            var root = nonTerminal(10, 20, "Wrap", List.of(t));
            var index = NodeIndex.build(root);

            var pivot = index.smallestEnclosing(5, 8);

            assertThat(pivot.isEmpty()).isTrue();
        }

        @Test
        @DisplayName("returns root when root is a leaf and contains the edit")
        void rootIsLeaf() {
            var root = terminal(0, 10, "Atom", "0123456789");
            var index = NodeIndex.build(root);

            var pivot = index.smallestEnclosing(2, 8);

            assertThat(pivot.isPresent()).isTrue();
            assertThat(pivot.unwrap()).isSameAs(root);
        }

        @Test
        @DisplayName("edit at root-aligned full span returns root")
        void editEqualsRootSpan() {
            var root = buildSampleTree();
            var index = NodeIndex.build(root);

            var pivot = index.smallestEnclosing(0, 30);

            assertThat(pivot.isPresent()).isTrue();
            assertThat(pivot.unwrap().rule()).isEqualTo("Block");
        }
    }
}
