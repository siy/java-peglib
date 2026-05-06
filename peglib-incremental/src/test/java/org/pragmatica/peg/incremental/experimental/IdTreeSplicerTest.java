package org.pragmatica.peg.incremental.experimental;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.peg.tree.SourceSpan;
import org.pragmatica.peg.tree.Trivia;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 0d.1 — GO/NO-GO gate for the spec §8 Q3 identity-preservation
 * invariant. After splice, every sibling subtree of every node on the splice
 * path must satisfy reference equality ({@code ==}) with the corresponding
 * pre-edit subtree.
 *
 * <p>Without this invariant, the O(δ) cost claim of
 * {@link IdNodeIndex#applyIncremental} collapses — the index update would have
 * to re-walk re-allocated subtrees, and we'd be back to {@code O(N)}.
 */
final class IdTreeSplicerTest {
    private static final SourceSpan SPAN = new SourceSpan(1, 1, 0, 1, 1, 0);
    private static final List<Trivia> NO_TRIVIA = List.of();

    private static IdCstNode.Terminal terminal(IdGenerator gen, String text) {
        return new IdCstNode.Terminal(gen.next(), SPAN, "T", text, NO_TRIVIA, NO_TRIVIA);
    }

    private static IdCstNode.NonTerminal nonTerminal(IdGenerator gen, String rule, List<IdCstNode> children) {
        return new IdCstNode.NonTerminal(gen.next(), SPAN, rule, children, NO_TRIVIA, NO_TRIVIA);
    }

    @Nested
    @DisplayName("splice")
    class SpliceTests {

        @Test
        @DisplayName("Pivot is root: returns newPivot directly, newPath = [newPivot]")
        void pivot_as_root() {
            var gen = new IdGenerator.PerSessionCounter();
            IdCstNode oldRoot = terminal(gen, "old");
            IdCstNode newPivot = terminal(gen, "new");

            var splicer = new IdTreeSplicer(gen);
            var result = splicer.splice(List.of(oldRoot), newPivot);

            assertThat(result.newRoot()).isSameAs(newPivot);
            assertThat(result.newPath()).hasSize(1);
            assertThat(result.newPath().get(0)).isSameAs(newPivot);
        }

        @Test
        @DisplayName("Single-level splice: 3-child root, middle replaced; flanking siblings reference-shared")
        void single_level_splice() {
            var gen = new IdGenerator.PerSessionCounter();
            var a = terminal(gen, "A");
            var b = terminal(gen, "B");
            var c = terminal(gen, "C");
            IdCstNode root = nonTerminal(gen, "Root", List.of(a, b, c));

            IdCstNode bPrime = terminal(gen, "B'");

            var splicer = new IdTreeSplicer(gen);
            var result = splicer.splice(List.of(root, b), bPrime);

            // Root has fresh ID — different record, but same rule/span/trivia.
            assertThat(result.newRoot()).isNotSameAs(root);
            assertThat(result.newRoot()).isInstanceOf(IdCstNode.NonTerminal.class);

            var newRootNT = (IdCstNode.NonTerminal) result.newRoot();
            assertThat(newRootNT.id()).isNotEqualTo(root.id());
            assertThat(newRootNT.rule()).isEqualTo("Root");
            assertThat(newRootNT.children()).hasSize(3);

            // Identity preservation: A and C are reference-shared.
            assertThat(newRootNT.children().get(0)).isSameAs(a);
            assertThat(newRootNT.children().get(2)).isSameAs(c);
            // The spliced slot holds bPrime.
            assertThat(newRootNT.children().get(1)).isSameAs(bPrime);

            // newPath = [newRoot, newPivot].
            assertThat(result.newPath()).hasSize(2);
            assertThat(result.newPath().get(0)).isSameAs(result.newRoot());
            assertThat(result.newPath().get(1)).isSameAs(bPrime);
        }

        @Test
        @DisplayName("Deep splice (4-level): for every path node, all non-spliced siblings reference-shared")
        void deep_splice_siblings_preserved() {
            // Tree:
            //   root
            //   ├── lvl1Sib   (preserved sibling at depth 1)
            //   └── mid       (on splice path)
            //       ├── midSib1   (preserved sibling at depth 2)
            //       ├── inner     (on splice path)
            //       │   ├── innerSibA  (preserved sibling at depth 3)
            //       │   ├── pivot       (REPLACED)
            //       │   └── innerSibB  (preserved sibling at depth 3)
            //       └── midSib2   (preserved sibling at depth 2)
            var gen = new IdGenerator.PerSessionCounter();
            var lvl1Sib = terminal(gen, "lvl1Sib");
            var midSib1 = terminal(gen, "midSib1");
            var midSib2 = terminal(gen, "midSib2");
            var innerSibA = terminal(gen, "innerSibA");
            var innerSibB = terminal(gen, "innerSibB");
            var pivot = terminal(gen, "PIVOT");
            IdCstNode inner = nonTerminal(gen, "Inner", List.of(innerSibA, pivot, innerSibB));
            IdCstNode mid = nonTerminal(gen, "Mid", List.of(midSib1, inner, midSib2));
            IdCstNode root = nonTerminal(gen, "Root", List.of(lvl1Sib, mid));

            IdCstNode newPivot = terminal(gen, "NEW_PIVOT");
            var oldPath = List.of(root, mid, inner, (IdCstNode) pivot);

            var splicer = new IdTreeSplicer(gen);
            var result = splicer.splice(oldPath, newPivot);

            // newPath structurally parallel to oldPath.
            assertThat(result.newPath()).hasSize(4);
            assertThat(result.newPath().get(0)).isSameAs(result.newRoot());
            assertThat(result.newPath().get(3)).isSameAs(newPivot);

            // -- depth 1: root has [lvl1Sib, newMid]. lvl1Sib reference-shared.
            var newRootNT = (IdCstNode.NonTerminal) result.newRoot();
            assertThat(newRootNT.children().get(0)).isSameAs(lvl1Sib);
            assertThat(newRootNT.children().get(1)).isSameAs(result.newPath().get(1));

            // -- depth 2: newMid has [midSib1, newInner, midSib2]. Both midSibs reference-shared.
            var newMidNT = (IdCstNode.NonTerminal) result.newPath().get(1);
            assertThat(newMidNT.children().get(0)).isSameAs(midSib1);
            assertThat(newMidNT.children().get(1)).isSameAs(result.newPath().get(2));
            assertThat(newMidNT.children().get(2)).isSameAs(midSib2);

            // -- depth 3: newInner has [innerSibA, newPivot, innerSibB]. Both innerSibs reference-shared.
            var newInnerNT = (IdCstNode.NonTerminal) result.newPath().get(2);
            assertThat(newInnerNT.children().get(0)).isSameAs(innerSibA);
            assertThat(newInnerNT.children().get(1)).isSameAs(newPivot);
            assertThat(newInnerNT.children().get(2)).isSameAs(innerSibB);

            // -- old ancestor records are NOT reused (each replaced with fresh ID).
            assertThat(newRootNT.id()).isNotEqualTo(((IdCstNode.NonTerminal) root).id());
            assertThat(newMidNT.id()).isNotEqualTo(((IdCstNode.NonTerminal) mid).id());
            assertThat(newInnerNT.id()).isNotEqualTo(((IdCstNode.NonTerminal) inner).id());
        }

        @Test
        @DisplayName("Identity invariant under iteration: count preserved siblings exactly")
        void identity_preservation_under_iteration() {
            // Build a 4-deep, 4-wide tree, splice ONE leaf, count preserved
            // sibling references along the splice path.
            //
            // Path: root → lvl1[idx=1] → lvl2[idx=2] → lvl3[idx=3] → pivot
            // At each level, branching = 4 → 3 siblings preserved per level.
            // Total preserved direct siblings on path = 3 * 4 = 12.
            // Plus deep subtrees under those siblings — also reference-shared.
            var gen = new IdGenerator.PerSessionCounter();

            var leaves = new ArrayList<IdCstNode.Terminal>();
            for (int i = 0; i < 16; i++) {
                leaves.add(terminal(gen, "leaf-" + i));
            }
            // lvl3 nodes: 4 NonTerminals, each holding 4 leaves.
            // We need one specific lvl3 to become the splice path's lvl3 and
            // hold the pivot at index 3.
            var lvl3Nodes = new ArrayList<IdCstNode>();
            for (int g = 0; g < 4; g++) {
                var slice = new ArrayList<IdCstNode>(4);
                for (int j = 0; j < 4; j++) {
                    slice.add(leaves.get(g * 4 + j));
                }
                lvl3Nodes.add(nonTerminal(gen, "lvl3-" + g, slice));
            }
            // lvl2 wraps lvl3 nodes with 4 children — reuse lvl3Nodes 4-by-4.
            // To keep branching = 4 at lvl2 too, we need 4 lvl3 nodes per lvl2
            // and 4 lvl2 nodes total. Simplification: reuse the same lvl3 set
            // BUT one lvl2 holds the splice path's lvl3 at index 2.
            // To keep distinct subtrees, build 4 lvl2's each with 4 NEW lvl3
            // nodes. That's 16 lvl3 nodes total → 64 leaves.
            var lvl2Nodes = new ArrayList<IdCstNode>();
            for (int b = 0; b < 4; b++) {
                var lvl3Slice = new ArrayList<IdCstNode>(4);
                for (int g = 0; g < 4; g++) {
                    var slice = new ArrayList<IdCstNode>(4);
                    for (int j = 0; j < 4; j++) {
                        slice.add(terminal(gen, "leaf-" + b + "-" + g + "-" + j));
                    }
                    lvl3Slice.add(nonTerminal(gen, "lvl3-" + b + "-" + g, slice));
                }
                lvl2Nodes.add(nonTerminal(gen, "lvl2-" + b, lvl3Slice));
            }
            IdCstNode root = nonTerminal(gen, "Root", lvl2Nodes);

            // Splice path: root → lvl2Nodes.get(1) → its children.get(2) → its children.get(3) → pivot
            var lvl1OnPath = lvl2Nodes.get(1);
            var lvl2OnPath = ((IdCstNode.NonTerminal) lvl1OnPath).children().get(2);
            var lvl3OnPath = ((IdCstNode.NonTerminal) lvl2OnPath).children().get(3);

            IdCstNode newPivot = terminal(gen, "NEW_PIVOT");
            var oldPath = List.of(root, lvl1OnPath, lvl2OnPath, lvl3OnPath);

            var splicer = new IdTreeSplicer(gen);
            var result = splicer.splice(oldPath, newPivot);

            // Count siblings preserved by reference at every path level.
            int preservedCount = 0;
            int falseShareCount = 0; // would indicate corruption
            for (int depth = 0; depth < oldPath.size() - 1; depth++) {
                var oldAncestor = (IdCstNode.NonTerminal) oldPath.get(depth);
                var newAncestor = (IdCstNode.NonTerminal) result.newPath().get(depth);
                var oldChildren = oldAncestor.children();
                var newChildren = newAncestor.children();
                assertThat(newChildren).hasSize(oldChildren.size());
                int splicedIdx = oldChildren.indexOf(oldPath.get(depth + 1));
                for (int k = 0; k < newChildren.size(); k++) {
                    if (k == splicedIdx) {
                        // Spliced slot must NOT be == old (it was replaced).
                        if (newChildren.get(k) == oldChildren.get(k)) {
                            falseShareCount++;
                        }
                    } else {
                        // Non-spliced slots must be == old.
                        if (newChildren.get(k) == oldChildren.get(k)) {
                            preservedCount++;
                        }
                    }
                }
            }

            // 4 levels × (4 children - 1 spliced) = 12 preserved siblings.
            // (We have 4-3-2-1 indexing: oldPath[0]=root has 4 children, sliced-at-index-1; 3 preserved.
            //  oldPath[1]=lvl1OnPath has 4 children, sliced-at-2; 3 preserved.
            //  oldPath[2]=lvl2OnPath has 4 children, sliced-at-3; 3 preserved.
            //  Total preserved = 9. The pivot's level (oldPath[3]) is not iterated — pivot itself replaced.)
            int pathDepthsWithSiblings = oldPath.size() - 1; // 3
            int expectedPreserved = pathDepthsWithSiblings * 3; // 4-1
            assertThat(preservedCount)
                .as("every non-spliced sibling at every depth must be reference-shared")
                .isEqualTo(expectedPreserved);
            assertThat(falseShareCount)
                .as("no false-share: the spliced slot must NOT == old slot")
                .isZero();
        }

        @Test
        @DisplayName("Mismatched path: child not in parent's children list throws IllegalStateException")
        void mismatched_path_throws() {
            var gen = new IdGenerator.PerSessionCounter();
            var a = terminal(gen, "A");
            var b = terminal(gen, "B");
            // bogus node NOT in root's children.
            var bogus = terminal(gen, "BOGUS");
            IdCstNode root = nonTerminal(gen, "Root", List.of(a, b));

            var splicer = new IdTreeSplicer(gen);
            IdCstNode newPivot = terminal(gen, "NEW");

            assertThatThrownBy(() -> splicer.splice(List.of(root, bogus), newPivot))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("splice path broken");
        }

        @Test
        @DisplayName("Empty path: throws IllegalArgumentException")
        void empty_path_throws() {
            var gen = new IdGenerator.PerSessionCounter();
            var splicer = new IdTreeSplicer(gen);
            IdCstNode newPivot = terminal(gen, "NEW");

            assertThatThrownBy(() -> splicer.splice(List.of(), newPivot))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("oldPath");

            assertThatThrownBy(() -> splicer.splice(null, newPivot))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("Non-NonTerminal in middle of path throws IllegalStateException")
        void non_nonterminal_in_path_throws() {
            // A Terminal cannot have children; if it appears in the middle of
            // oldPath as a parent of the pivot, we must fail loudly.
            var gen = new IdGenerator.PerSessionCounter();
            var leaf = terminal(gen, "leaf");
            var rogueChild = terminal(gen, "rogue");
            // leaf does NOT contain rogueChild — but more importantly, leaf is not a NonTerminal.
            var splicer = new IdTreeSplicer(gen);
            IdCstNode newPivot = terminal(gen, "NEW");

            assertThatThrownBy(() -> splicer.splice(List.of((IdCstNode) leaf, rogueChild), newPivot))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not a NonTerminal");
        }
    }
}
