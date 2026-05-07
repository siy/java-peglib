package org.pragmatica.peg.incremental.experimental;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.peg.tree.Trivia;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 1.0/1.1 — GO/NO-GO gate for the path-A correctness invariant.
 *
 * <p><strong>Central claim under test:</strong> after splice, every sibling
 * of every node on the splice path satisfies reference equality ({@code ==})
 * with its pre-edit record — including siblings to the RIGHT of the edit
 * whose offsets must shift. The shift happens on the {@link SpanIndex}
 * side, not by record rebuild.
 *
 * <p>Without this invariant, path A doesn't pay off: rebuilding right-of-edit
 * siblings is exactly what production {@code TreeSplicer.spliceAndShift}
 * already does, and the bench would just re-measure the existing 0.4.3 cost
 * with extra primitive-array overhead.
 */
final class OffsetDecoupledSplicerTest {
    private static final List<Trivia> NO_TRIVIA = List.of();

    private static OffsetDecoupledNode.Terminal terminal(IdGenerator gen, String text) {
        return new OffsetDecoupledNode.Terminal(gen.next(), "T", text, NO_TRIVIA, NO_TRIVIA);
    }

    private static OffsetDecoupledNode.NonTerminal nonTerminal(
        IdGenerator gen, String rule, List<OffsetDecoupledNode> children) {
        return new OffsetDecoupledNode.NonTerminal(gen.next(), rule, children, NO_TRIVIA, NO_TRIVIA);
    }

    @Nested
    @DisplayName("identity preservation")
    class IdentityTests {

        @Test
        @DisplayName("Right-of-edit siblings preserve identity AND have shifted spans")
        void rightOfEditSiblingsPreserveIdentity() {
            // Tree: Root[A, target, C]
            //   A spans [0, 5)
            //   target spans [5, 10)
            //   C spans [10, 20)   <-- right of edit
            // Edit: replace target with newPivot ([5, 13)) — delta = +3, editEnd = 10.
            // Expectation:
            //   - newRoot.children().get(0) == A (left, identity preserved)
            //   - newRoot.children().get(2) == C (RIGHT, identity preserved — path A claim)
            //   - newSpans.startOffset(C.id()) == 13 (10 + 3)
            //   - newSpans.endOffset(C.id())   == 23 (20 + 3)
            var gen = new IdGenerator.PerSessionCounter();
            var a = terminal(gen, "A");
            var target = terminal(gen, "target");
            var c = terminal(gen, "C");
            var root = nonTerminal(gen, "Root", List.of(a, target, c));

            var oldSpans = new SpanIndex(16);
            oldSpans.put(a.id(), 0, 5);
            oldSpans.put(target.id(), 5, 10);
            oldSpans.put(c.id(), 10, 20);
            oldSpans.put(root.id(), 0, 20);

            var newPivot = terminal(gen, "newTarget");
            // Caller registers newPivot's span before splicing.
            oldSpans.put(newPivot.id(), 5, 13);

            var splicer = new OffsetDecoupledSplicer(gen);
            var result = splicer.splice(oldSpans, List.of(root, target), newPivot, /*editEnd=*/10, /*delta=*/3);

            // Root has fresh ID — different record.
            var newRoot = (OffsetDecoupledNode.NonTerminal) result.newRoot();
            assertThat(newRoot.id()).isNotEqualTo(root.id());
            assertThat(newRoot.children()).hasSize(3);

            // Identity preservation — left and RIGHT.
            assertThat(newRoot.children().get(0)).isSameAs(a);
            assertThat(newRoot.children().get(1)).isSameAs(newPivot);
            assertThat(newRoot.children().get(2)).isSameAs(c); // *** THE PATH-A CLAIM ***

            // Spans for surviving siblings shifted on the SpanIndex side.
            var newSpans = result.newSpans();
            assertThat(newSpans.startOffset(a.id())).isEqualTo(0);   // left, untouched
            assertThat(newSpans.endOffset(a.id())).isEqualTo(5);
            assertThat(newSpans.startOffset(c.id())).isEqualTo(13);  // RIGHT, shifted
            assertThat(newSpans.endOffset(c.id())).isEqualTo(23);

            // newPivot span: caller-registered, NOT in shift range (start=5 < editEnd=10).
            assertThat(newSpans.startOffset(newPivot.id())).isEqualTo(5);
            assertThat(newSpans.endOffset(newPivot.id())).isEqualTo(13);

            // Ancestor span: start unchanged, end extended by delta.
            assertThat(newSpans.startOffset(newRoot.id())).isEqualTo(0);
            assertThat(newSpans.endOffset(newRoot.id())).isEqualTo(23);
        }

        @Test
        @DisplayName("Old SpanIndex receiver remains valid (independent copy)")
        void receiver_independence() {
            var gen = new IdGenerator.PerSessionCounter();
            var a = terminal(gen, "A");
            var target = terminal(gen, "target");
            var root = nonTerminal(gen, "Root", List.of(a, target));

            var oldSpans = new SpanIndex(8);
            oldSpans.put(a.id(), 0, 5);
            oldSpans.put(target.id(), 5, 10);
            oldSpans.put(root.id(), 0, 10);

            var newPivot = terminal(gen, "newTarget");
            oldSpans.put(newPivot.id(), 5, 12);

            var splicer = new OffsetDecoupledSplicer(gen);
            splicer.splice(oldSpans, List.of(root, target), newPivot, 10, 2);

            // Receiver still reports pre-edit offsets.
            assertThat(oldSpans.startOffset(target.id())).isEqualTo(5);
            assertThat(oldSpans.endOffset(target.id())).isEqualTo(10);
        }
    }

    @Nested
    @DisplayName("edge cases")
    class EdgeCases {

        @Test
        @DisplayName("Pivot is root → returns newPivot, no ancestor rebuild, but shift still applied")
        void pivot_as_root() {
            var gen = new IdGenerator.PerSessionCounter();
            var oldRoot = terminal(gen, "old");

            var oldSpans = new SpanIndex(4);
            oldSpans.put(oldRoot.id(), 0, 5);

            var newPivot = terminal(gen, "new");
            oldSpans.put(newPivot.id(), 0, 7);

            var splicer = new OffsetDecoupledSplicer(gen);
            var result = splicer.splice(oldSpans, List.of(oldRoot), newPivot, 5, 2);

            assertThat(result.newRoot()).isSameAs(newPivot);
            assertThat(result.newPath()).hasSize(1);
            assertThat(result.newPath().get(0)).isSameAs(newPivot);
            // Pivot's own registered span is unchanged (start < editEnd).
            assertThat(result.newSpans().startOffset(newPivot.id())).isEqualTo(0);
            assertThat(result.newSpans().endOffset(newPivot.id())).isEqualTo(7);
        }

        @Test
        @DisplayName("delta = 0 → spans unchanged, splice still rebuilds path with fresh ids")
        void zero_delta_path() {
            var gen = new IdGenerator.PerSessionCounter();
            var a = terminal(gen, "A");
            var target = terminal(gen, "target");
            var c = terminal(gen, "C");
            var root = nonTerminal(gen, "Root", List.of(a, target, c));

            var oldSpans = new SpanIndex(8);
            oldSpans.put(a.id(), 0, 5);
            oldSpans.put(target.id(), 5, 10);
            oldSpans.put(c.id(), 10, 20);
            oldSpans.put(root.id(), 0, 20);

            // Replace target with same-length newPivot.
            var newPivot = terminal(gen, "same");
            oldSpans.put(newPivot.id(), 5, 10);

            var splicer = new OffsetDecoupledSplicer(gen);
            var result = splicer.splice(oldSpans, List.of(root, target), newPivot, 10, 0);

            var newRoot = (OffsetDecoupledNode.NonTerminal) result.newRoot();
            assertThat(newRoot.id()).isNotEqualTo(root.id()); // fresh ancestor id
            assertThat(newRoot.children().get(0)).isSameAs(a);
            assertThat(newRoot.children().get(2)).isSameAs(c);

            // Spans unchanged.
            assertThat(result.newSpans().startOffset(c.id())).isEqualTo(10);
            assertThat(result.newSpans().endOffset(c.id())).isEqualTo(20);
            assertThat(result.newSpans().startOffset(newRoot.id())).isEqualTo(0);
            assertThat(result.newSpans().endOffset(newRoot.id())).isEqualTo(20);
        }

        @Test
        @DisplayName("Multi-level splice: every ancestor on the path is rebuilt with fresh id")
        void multi_level_splice() {
            // Root[Mid[Inner[target], Sibling]]
            //                       ^ leaf right of edit
            // Splice replaces target. Verify Inner, Mid, Root all have fresh ids
            // and that Sibling identity is preserved at the Mid level.
            var gen = new IdGenerator.PerSessionCounter();
            var target = terminal(gen, "t");
            var inner = nonTerminal(gen, "Inner", List.of(target));
            var sibling = terminal(gen, "S");
            var mid = nonTerminal(gen, "Mid", List.of(inner, sibling));
            var root = nonTerminal(gen, "Root", List.of(mid));

            var oldSpans = new SpanIndex(16);
            oldSpans.put(target.id(), 0, 3);
            oldSpans.put(inner.id(), 0, 3);
            oldSpans.put(sibling.id(), 3, 5);
            oldSpans.put(mid.id(), 0, 5);
            oldSpans.put(root.id(), 0, 5);

            var newPivot = terminal(gen, "t'");
            oldSpans.put(newPivot.id(), 0, 4);

            var splicer = new OffsetDecoupledSplicer(gen);
            var result = splicer.splice(oldSpans, List.of(root, mid, inner, target), newPivot, 3, 1);

            var newRoot = (OffsetDecoupledNode.NonTerminal) result.newRoot();
            assertThat(newRoot.id()).isNotEqualTo(root.id());

            var newMid = (OffsetDecoupledNode.NonTerminal) newRoot.children().get(0);
            assertThat(newMid.id()).isNotEqualTo(mid.id());
            assertThat(newMid.children().get(1)).isSameAs(sibling); // RIGHT of edit, identity preserved

            var newInner = (OffsetDecoupledNode.NonTerminal) newMid.children().get(0);
            assertThat(newInner.id()).isNotEqualTo(inner.id());
            assertThat(newInner.children().get(0)).isSameAs(newPivot);

            // newPath: root → mid → inner → newPivot, all fresh except pivot.
            assertThat(result.newPath()).hasSize(4);
            assertThat(result.newPath().get(0)).isSameAs(newRoot);
            assertThat(result.newPath().get(1)).isSameAs(newMid);
            assertThat(result.newPath().get(2)).isSameAs(newInner);
            assertThat(result.newPath().get(3)).isSameAs(newPivot);

            // Sibling span shifted (start 3 >= editEnd 3, so shifts).
            assertThat(result.newSpans().startOffset(sibling.id())).isEqualTo(4);
            assertThat(result.newSpans().endOffset(sibling.id())).isEqualTo(6);
            // Mid and Root: ends extended; starts unchanged.
            assertThat(result.newSpans().startOffset(newMid.id())).isEqualTo(0);
            assertThat(result.newSpans().endOffset(newMid.id())).isEqualTo(6);
            assertThat(result.newSpans().startOffset(newRoot.id())).isEqualTo(0);
            assertThat(result.newSpans().endOffset(newRoot.id())).isEqualTo(6);
        }

        @Test
        @DisplayName("Empty oldPath rejected")
        void empty_path_rejected() {
            var gen = new IdGenerator.PerSessionCounter();
            var pivot = terminal(gen, "x");
            var splicer = new OffsetDecoupledSplicer(gen);
            assertThatThrownBy(() -> splicer.splice(new SpanIndex(4), List.of(), pivot, 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("Null newPivot rejected")
        void null_pivot_rejected() {
            var gen = new IdGenerator.PerSessionCounter();
            var root = terminal(gen, "x");
            var splicer = new OffsetDecoupledSplicer(gen);
            assertThatThrownBy(() -> splicer.splice(new SpanIndex(4), List.of(root), null, 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("Non-NonTerminal ancestor rejected (broken path)")
        void non_nonterminal_ancestor_rejected() {
            var gen = new IdGenerator.PerSessionCounter();
            // Construct a fake path where a Terminal pretends to be an ancestor.
            var fakeAncestor = terminal(gen, "fake");
            var pivot = terminal(gen, "p");
            var oldSpans = new SpanIndex(4);
            oldSpans.put(fakeAncestor.id(), 0, 1);
            oldSpans.put(pivot.id(), 0, 1);
            var splicer = new OffsetDecoupledSplicer(gen);

            assertThatThrownBy(() -> splicer.splice(oldSpans, List.of(fakeAncestor, pivot), pivot, 0, 0))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not a NonTerminal");
        }
    }
}
