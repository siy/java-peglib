package org.pragmatica.peg.incremental.experimental;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.peg.tree.SourceSpan;
import org.pragmatica.peg.tree.Trivia;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Path D — tests for {@link StableIdNodeIndex}. Central correctness claim:
 * after {@link StableIdNodeIndex#applyIncremental} on a tree spliced via
 * {@link StableIdSplicer}, the resulting parents map matches what
 * {@code StableIdNodeIndex.build(newRoot)} would produce — even though the
 * incremental update skips the ancestor-removal and sibling-rewire steps that
 * {@link IdNodeIndex#applyIncremental} performs.
 *
 * <p>Central perf claim: the {@code parents.put} + {@code parents.remove} call
 * counts during {@code applyIncremental} are bounded by
 * {@code oldPivotSize + newPivotSize + small constant}, independent of N.
 */
final class StableIdNodeIndexTest {
    private static final SourceSpan SPAN = new SourceSpan(1, 1, 0, 1, 1, 0);
    private static final List<Trivia> NO_TRIVIA = List.of();

    private static IdCstNode.Terminal terminal(IdGenerator gen, String text) {
        return new IdCstNode.Terminal(gen.next(), SPAN, "T", text, NO_TRIVIA, NO_TRIVIA);
    }

    private static IdCstNode.NonTerminal nonTerminal(IdGenerator gen, String rule, List<IdCstNode> children) {
        return new IdCstNode.NonTerminal(gen.next(), SPAN, rule, children, NO_TRIVIA, NO_TRIVIA);
    }

    @Nested
    @DisplayName("build")
    class BuildTests {
        @Test
        @DisplayName("Equivalence to IdNodeIndex.build: same parents map on the same tree")
        void build_equivalent_to_idnodeindex() {
            var gen = new IdGenerator.PerSessionCounter();
            var a = terminal(gen, "a");
            var b = terminal(gen, "b");
            var c = terminal(gen, "c");
            IdCstNode inner = nonTerminal(gen, "Inner", List.of(b, c));
            IdCstNode root = nonTerminal(gen, "Root", List.of(a, inner));

            var stableIndex = StableIdNodeIndex.build(root);
            var idIndex = IdNodeIndex.build(root);

            assertThat(stableIndex.size()).isEqualTo(idIndex.size());
            for (var node : flatten(root)) {
                assertThat(stableIndex.parentIdOf(node.id()))
                    .as("node id %d (rule %s) parent must agree", node.id(), node.rule())
                    .isEqualTo(idIndex.parentIdOf(node.id()));
            }
            assertThat(stableIndex.root()).isSameAs(root);
        }
    }

    @Nested
    @DisplayName("applyIncremental")
    class ApplyIncrementalTests {

        @Test
        @DisplayName("Equivalence to full rebuild after StableIdSplicer splice")
        void equivalence_to_full_rebuild() {
            // 3-level tree, splice at depth 2 via StableIdSplicer, verify
            // the resulting StableIdNodeIndex matches build(newRoot) exactly.
            var gen = new IdGenerator.PerSessionCounter();
            var leaf1 = terminal(gen, "1");
            var leaf2 = terminal(gen, "2");
            var leaf3 = terminal(gen, "3");
            var leaf4 = terminal(gen, "4");
            IdCstNode oldChildA = nonTerminal(gen, "A", List.of(leaf1, leaf2));
            IdCstNode oldChildB = nonTerminal(gen, "B", List.of(leaf3, leaf4));
            IdCstNode oldRoot = nonTerminal(gen, "Root", List.of(oldChildA, oldChildB));

            var index = StableIdNodeIndex.build(oldRoot);

            // Replace child B with a fresh subtree using StableIdSplicer.
            var newLeaf5 = terminal(gen, "5");
            var newLeaf6 = terminal(gen, "6");
            var newLeaf7 = terminal(gen, "7");
            IdCstNode newChildB = nonTerminal(gen, "B", List.of(newLeaf5, newLeaf6, newLeaf7));

            var splicer = new StableIdSplicer(gen);
            var result = splicer.splice(List.of(oldRoot, oldChildB), newChildB);

            var oldPath = List.of(oldRoot, oldChildB);
            var newPath = result.newPath();

            var incremental = index.applyIncremental(result.newRoot(), oldPath, newPath);
            var rebuilt = StableIdNodeIndex.build(result.newRoot());

            // Both indices must agree on every node in the new tree.
            for (var node : flatten(result.newRoot())) {
                assertThat(incremental.parentIdOf(node.id()))
                    .as("parent of node id %d (rule %s)", node.id(), node.rule())
                    .isEqualTo(rebuilt.parentIdOf(node.id()));
            }
            assertThat(incremental.size()).isEqualTo(rebuilt.size());
            // Old pivot and its old descendants are gone.
            assertThat(incremental.contains(oldChildB.id())).isFalse();
            assertThat(incremental.contains(leaf3.id())).isFalse();
            assertThat(incremental.contains(leaf4.id())).isFalse();
            // Stable ancestor: the new root has the SAME id as old root.
            assertThat(result.newRoot().id()).isEqualTo(oldRoot.id());
        }

        @Test
        @DisplayName("Flat tree: 100 children, splice middle; remaining children's parent entries unchanged")
        void flat_tree_splice_preserves_sibling_entries() {
            // Build a flat tree: root with 100 terminal children.
            var gen = new IdGenerator.PerSessionCounter();
            var children = new ArrayList<IdCstNode>(100);
            for (int i = 0; i < 100; i++) {
                children.add(terminal(gen, "c" + i));
            }
            IdCstNode oldRoot = nonTerminal(gen, "Root", children);
            long stableRootId = oldRoot.id();

            var index = StableIdNodeIndex.build(oldRoot);
            assertThat(index.size()).isEqualTo(100);

            // Replace child #50 via StableIdSplicer.
            var oldTarget = children.get(50);
            IdCstNode newTarget = terminal(gen, "newC50");
            var splicer = new StableIdSplicer(gen);
            var result = splicer.splice(List.of(oldRoot, oldTarget), newTarget);

            var newIndex = index.applyIncremental(result.newRoot(), List.of(oldRoot, oldTarget), result.newPath());

            // Stable root id propagated.
            assertThat(result.newRoot().id()).isEqualTo(stableRootId);
            // Every remaining child still points to the (stable-id) root.
            for (int i = 0; i < 100; i++) {
                if (i == 50) {
                    // old c50 is dead.
                    assertThat(newIndex.contains(children.get(i).id())).isFalse();
                    continue;
                }
                assertThat(newIndex.parentIdOf(children.get(i).id()))
                    .as("flat-tree sibling at index %d still points to stable root id", i)
                    .isEqualTo(org.pragmatica.lang.Option.some(stableRootId));
            }
            // The new c50 points to the stable root id.
            assertThat(newIndex.parentIdOf(newTarget.id()))
                .isEqualTo(org.pragmatica.lang.Option.some(stableRootId));
            assertThat(newIndex.size()).isEqualTo(100);
        }

        @Test
        @DisplayName("Microcount on flat tree: put + remove counts ≤ oldPivotSize + newPivotSize + small slack")
        void microcount_o_delta() {
            // Central perf claim: applyIncremental cost is O(oldPivotSize + newPivotSize),
            // NOT O(N). Build a 1000-child flat tree, splice one terminal, assert
            // microcounts are tiny.
            var gen = new IdGenerator.PerSessionCounter();
            int N = 1000;
            var children = new ArrayList<IdCstNode>(N);
            for (int i = 0; i < N; i++) {
                children.add(terminal(gen, "c" + i));
            }
            IdCstNode oldRoot = nonTerminal(gen, "Root", children);

            var index = StableIdNodeIndex.build(oldRoot);
            int totalNodes = countAll(oldRoot); // 1001 (root + 1000 children)
            assertThat(totalNodes).isEqualTo(N + 1);

            var oldTarget = children.get(N / 2);
            IdCstNode newTarget = terminal(gen, "newMid");

            var splicer = new StableIdSplicer(gen);
            var result = splicer.splice(List.of(oldRoot, oldTarget), newTarget);

            var newIndex = index.applyIncremental(result.newRoot(), List.of(oldRoot, oldTarget), result.newPath());

            // oldPivotSize: oldTarget is a leaf — 0 descendants.
            // newPivotSize: newTarget is a leaf — 0 descendants. +1 for pivot up-pointer.
            // Removes: 0 descendants + 1 (oldPivot) = 1
            // Puts: 0 internal + 1 pivot up-pointer = 1
            // Slack of 5 for any future implementation detail.
            int oldPivotSize = countAll(oldTarget); // 1 (just the leaf itself)
            int newPivotSize = countAll(newTarget); // 1
            int microBound = oldPivotSize + newPivotSize + 5;

            int totalMicro = newIndex.lastIncrementalPutCount + newIndex.lastIncrementalRemoveCount;

            assertThat(totalMicro)
                .as("put + remove count must be O(oldPivotSize + newPivotSize), NOT O(N=%d). "
                    + "Got puts=%d, removes=%d (bound=%d)",
                    totalNodes, newIndex.lastIncrementalPutCount, newIndex.lastIncrementalRemoveCount,
                    microBound)
                .isLessThanOrEqualTo(microBound)
                .isLessThan(totalNodes);

            System.out.println("[Path D] flat-tree(N=" + N + ") incremental microcount: puts="
                + newIndex.lastIncrementalPutCount
                + " removes=" + newIndex.lastIncrementalRemoveCount
                + " (vs full-rebuild N=" + totalNodes + ")");
        }

        @Test
        @DisplayName("Deep splice (4-level): equivalence to fresh build, stable ancestor ids preserved")
        void deep_splice_equivalence() {
            var gen = new IdGenerator.PerSessionCounter();
            var lvl1Sib = terminal(gen, "L1S");
            var midLvl2Sib = terminal(gen, "L2S");
            var oldPivot = terminal(gen, "OLD_PIVOT");
            IdCstNode oldInner = nonTerminal(gen, "Inner", List.of(oldPivot));
            IdCstNode oldMid = nonTerminal(gen, "Mid", List.of(midLvl2Sib, oldInner));
            IdCstNode oldRoot = nonTerminal(gen, "Root", List.of(lvl1Sib, oldMid));

            var index = StableIdNodeIndex.build(oldRoot);

            IdCstNode newPivot = terminal(gen, "NEW_PIVOT");
            var splicer = new StableIdSplicer(gen);
            var result = splicer.splice(List.of(oldRoot, oldMid, oldInner, (IdCstNode) oldPivot), newPivot);

            var oldPath = List.of(oldRoot, oldMid, oldInner, (IdCstNode) oldPivot);
            var newIndex = index.applyIncremental(result.newRoot(), oldPath, result.newPath());

            // Stable ancestor ids: newRoot.id == oldRoot.id, etc.
            for (int i = 0; i < oldPath.size() - 1; i++) {
                assertThat(result.newPath().get(i).id())
                    .as("ancestor at depth %d preserves stable id", i)
                    .isEqualTo(oldPath.get(i).id());
            }

            // Equivalence to full rebuild.
            var fresh = StableIdNodeIndex.build(result.newRoot());
            for (var node : flatten(result.newRoot())) {
                assertThat(newIndex.parentIdOf(node.id()))
                    .as("deep splice: node id %d (rule %s) parent agrees with full build",
                        node.id(), node.rule())
                    .isEqualTo(fresh.parentIdOf(node.id()));
            }
            assertThat(newIndex.size()).isEqualTo(fresh.size());
            // Old pivot is dead.
            assertThat(newIndex.contains(oldPivot.id())).isFalse();
            // BUT old ancestor ids remain LIVE (their records were rebuilt with same ids).
            assertThat(newIndex.contains(oldMid.id())).isTrue();
            assertThat(newIndex.contains(oldInner.id())).isTrue();
            // Old root id has no parent entry (it's the root).
            assertThat(newIndex.contains(oldRoot.id())).isFalse();
        }
    }

    // -- helpers --

    private static int countAll(IdCstNode node) {
        int c = 1;
        if (node instanceof IdCstNode.NonTerminal nt) {
            for (var ch : nt.children()) {
                c += countAll(ch);
            }
        }
        return c;
    }

    private static List<IdCstNode> flatten(IdCstNode root) {
        var out = new ArrayList<IdCstNode>();
        flattenInto(root, out);
        return out;
    }

    private static void flattenInto(IdCstNode node, List<IdCstNode> out) {
        out.add(node);
        if (node instanceof IdCstNode.NonTerminal nt) {
            for (var ch : nt.children()) {
                flattenInto(ch, out);
            }
        }
    }
}
