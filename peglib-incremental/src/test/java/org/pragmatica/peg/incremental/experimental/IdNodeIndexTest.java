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
 * Phase 0c tests for {@link IdNodeIndex}: full-build correctness, incremental
 * update equivalence to full-build, and the central perf microcount that
 * proves {@link IdNodeIndex#applyIncremental} is {@code O(δ)}, not
 * {@code O(N)} (per
 * {@code docs/incremental/ARCHITECTURE-0.5.0.md} §2 Lever A and §8 Q3).
 */
final class IdNodeIndexTest {
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
        @DisplayName("Single Terminal as root: size 0, no parent entry for root")
        void single_terminal_root() {
            var gen = new IdGenerator.PerSessionCounter();
            var root = terminal(gen, "x");

            var index = IdNodeIndex.build(root);

            assertThat(index.size()).isEqualTo(0);
            assertThat(index.contains(root.id())).isFalse();
            assertThat(index.parentIdOf(root.id()).isEmpty()).isTrue();
            assertThat(index.root()).isSameAs(root);
        }

        @Test
        @DisplayName("NonTerminal with 3 Terminal children: each child points to root")
        void nonterminal_three_children() {
            var gen = new IdGenerator.PerSessionCounter();
            var c1 = terminal(gen, "a");
            var c2 = terminal(gen, "b");
            var c3 = terminal(gen, "c");
            IdCstNode root = nonTerminal(gen, "Expr", List.of(c1, c2, c3));

            var index = IdNodeIndex.build(root);

            assertThat(index.size()).isEqualTo(3);
            assertThat(index.parentIdOf(c1.id())).isEqualTo(org.pragmatica.lang.Option.some(root.id()));
            assertThat(index.parentIdOf(c2.id())).isEqualTo(org.pragmatica.lang.Option.some(root.id()));
            assertThat(index.parentIdOf(c3.id())).isEqualTo(org.pragmatica.lang.Option.some(root.id()));
            assertThat(index.contains(root.id())).isFalse();
        }

        @Test
        @DisplayName("Three-deep tree: every interior parent link present, root has none")
        void three_deep_tree() {
            var gen = new IdGenerator.PerSessionCounter();
            var leaf = terminal(gen, "x");
            var middle = nonTerminal(gen, "Inner", List.of(leaf));
            IdCstNode root = nonTerminal(gen, "Outer", List.of(middle));

            var index = IdNodeIndex.build(root);

            assertThat(index.size()).isEqualTo(2);
            assertThat(index.parentIdOf(leaf.id())).isEqualTo(org.pragmatica.lang.Option.some(middle.id()));
            assertThat(index.parentIdOf(middle.id())).isEqualTo(org.pragmatica.lang.Option.some(root.id()));
            assertThat(index.contains(root.id())).isFalse();
        }
    }

    @Nested
    @DisplayName("applyIncremental")
    class ApplyIncrementalTests {

        @Test
        @DisplayName("Trivial replacement: middle child swapped under same root id")
        void trivial_replacement() {
            // Old tree: root -> [A, B, C]
            // New tree: newRoot (fresh id) -> [A, B', C] (A and C reused; B replaced)
            var gen = new IdGenerator.PerSessionCounter();
            var a = terminal(gen, "a");
            var b = terminal(gen, "b");
            var c = terminal(gen, "c");
            IdCstNode oldRoot = nonTerminal(gen, "R", List.of(a, b, c));

            var index = IdNodeIndex.build(oldRoot);

            // Construct the new tree using the SAME generator (continues incrementing).
            var bPrime = terminal(gen, "B-prime");
            IdCstNode newRoot = nonTerminal(gen, "R", List.of(a, bPrime, c));

            var oldPath = List.of(oldRoot, b);
            var newPath = List.of(newRoot, (IdCstNode) bPrime);

            var newIndex = index.applyIncremental(newRoot, oldPath, newPath);

            // A and C still point to NEW root.
            assertThat(newIndex.parentIdOf(a.id())).isEqualTo(org.pragmatica.lang.Option.some(newRoot.id()));
            assertThat(newIndex.parentIdOf(c.id())).isEqualTo(org.pragmatica.lang.Option.some(newRoot.id()));
            // B' points to new root.
            assertThat(newIndex.parentIdOf(bPrime.id())).isEqualTo(org.pragmatica.lang.Option.some(newRoot.id()));
            // Old B is dead.
            assertThat(newIndex.contains(b.id())).isFalse();
            // Old root was an entry? No — root never had a parent. Map entries: 3.
            assertThat(newIndex.size()).isEqualTo(3);
            assertThat(newIndex.root()).isSameAs(newRoot);
        }

        @Test
        @DisplayName("Pivot is the root itself: equivalent to full rebuild")
        void pivot_is_root() {
            var gen = new IdGenerator.PerSessionCounter();
            var a = terminal(gen, "a");
            var b = terminal(gen, "b");
            IdCstNode oldRoot = nonTerminal(gen, "R", List.of(a, b));

            var index = IdNodeIndex.build(oldRoot);

            var aNew = terminal(gen, "anew");
            var bNew = terminal(gen, "bnew");
            IdCstNode newRoot = nonTerminal(gen, "R", List.of(aNew, bNew));

            var oldPath = List.<IdCstNode>of(oldRoot);
            var newPath = List.<IdCstNode>of(newRoot);

            var newIndex = index.applyIncremental(newRoot, oldPath, newPath);

            // Old root and old children all gone.
            assertThat(newIndex.contains(oldRoot.id())).isFalse();
            assertThat(newIndex.contains(a.id())).isFalse();
            assertThat(newIndex.contains(b.id())).isFalse();
            // New entries match a fresh build.
            assertThat(newIndex.parentIdOf(aNew.id())).isEqualTo(org.pragmatica.lang.Option.some(newRoot.id()));
            assertThat(newIndex.parentIdOf(bNew.id())).isEqualTo(org.pragmatica.lang.Option.some(newRoot.id()));
            assertThat(newIndex.size()).isEqualTo(2);

            // Equivalence to full rebuild.
            var fresh = IdNodeIndex.build(newRoot);
            assertThat(newIndex.size()).isEqualTo(fresh.size());
            assertThat(newIndex.parentIdOf(aNew.id())).isEqualTo(fresh.parentIdOf(aNew.id()));
            assertThat(newIndex.parentIdOf(bNew.id())).isEqualTo(fresh.parentIdOf(bNew.id()));
        }

        @Test
        @DisplayName("Deep splice (4-level): siblings keep IDs, ancestor chain IDs replaced")
        void deep_splice() {
            // 4-level tree:
            //   oldRoot (depth 0)
            //   ├── lvl1Sibling      (depth 1, retained)
            //   └── oldMid           (depth 1, replaced)
            //       ├── mid_lvl2Sibling  (depth 2, retained)
            //       └── oldInner         (depth 2, replaced)
            //           └── oldPivot     (depth 3, replaced -- the smallest replaced subtree contains JUST the pivot)
            //
            // New tree mirrors structure with fresh ancestor IDs but reuses
            // lvl1Sibling and mid_lvl2Sibling (record-identical).
            var gen = new IdGenerator.PerSessionCounter();

            var lvl1Sibling = terminal(gen, "L1S");
            var midLvl2Sibling = terminal(gen, "L2S");
            var oldPivot = terminal(gen, "OLD_PIVOT");
            IdCstNode oldInner = nonTerminal(gen, "Inner", List.of(oldPivot));
            IdCstNode oldMid = nonTerminal(gen, "Mid", List.of(midLvl2Sibling, oldInner));
            IdCstNode oldRoot = nonTerminal(gen, "Root", List.of(lvl1Sibling, oldMid));

            var index = IdNodeIndex.build(oldRoot);

            // New tree: pivot replaced; ancestor chain Inner→Mid→Root all fresh.
            var newPivot = terminal(gen, "NEW_PIVOT");
            IdCstNode newInner = nonTerminal(gen, "Inner", List.of(newPivot));
            IdCstNode newMid = nonTerminal(gen, "Mid", List.of(midLvl2Sibling, newInner));
            IdCstNode newRoot = nonTerminal(gen, "Root", List.of(lvl1Sibling, newMid));

            // Pivot for incremental update is oldPivot (the smallest replaced node).
            var oldPath = List.of(oldRoot, oldMid, oldInner, (IdCstNode) oldPivot);
            var newPath = List.of(newRoot, newMid, newInner, (IdCstNode) newPivot);

            var newIndex = index.applyIncremental(newRoot, oldPath, newPath);

            // (a) Surviving siblings retain their IDs and have CORRECT new parent IDs.
            assertThat(newIndex.parentIdOf(lvl1Sibling.id()))
                .isEqualTo(org.pragmatica.lang.Option.some(newRoot.id()));
            assertThat(newIndex.parentIdOf(midLvl2Sibling.id()))
                .isEqualTo(org.pragmatica.lang.Option.some(newMid.id()));
            // (b) Old ancestor chain IDs are GONE.
            assertThat(newIndex.contains(oldRoot.id())).isFalse();
            assertThat(newIndex.contains(oldMid.id())).isFalse();
            assertThat(newIndex.contains(oldInner.id())).isFalse();
            assertThat(newIndex.contains(oldPivot.id())).isFalse();
            // New ancestor chain wired correctly.
            assertThat(newIndex.parentIdOf(newMid.id()))
                .isEqualTo(org.pragmatica.lang.Option.some(newRoot.id()));
            assertThat(newIndex.parentIdOf(newInner.id()))
                .isEqualTo(org.pragmatica.lang.Option.some(newMid.id()));
            assertThat(newIndex.parentIdOf(newPivot.id()))
                .isEqualTo(org.pragmatica.lang.Option.some(newInner.id()));

            // Cross-check with a fresh build.
            var fresh = IdNodeIndex.build(newRoot);
            assertThat(newIndex.size()).isEqualTo(fresh.size());
        }

        @Test
        @DisplayName("Identity-preservation invariant: put count is O(δ), shared subtrees not re-walked")
        void identity_preservation_microcount() {
            // Construct an old tree where the splice path is at depth 3, and
            // the splice point has TWO sibling subtrees flanking it, each
            // record-identical between old and new trees. Each sibling
            // subtree contains MANY internal nodes — the test asserts those
            // internal nodes' parent entries are NOT touched (preserved
            // verbatim) and the put count stays bounded by
            // splicedSize + depth × branching.
            //
            // Old tree:
            //   root
            //   ├── leftBigSubtree    (depth 1, record-shared, internally large)
            //   ├── mid                (depth 1, replaced)
            //   │   ├── midLeftBigSubtree  (depth 2, record-shared, internally large)
            //   │   ├── inner               (depth 2, replaced)
            //   │   │   └── oldPivot        (depth 3, replaced — the leaf splice target)
            //   │   └── midRightBigSubtree (depth 2, record-shared, internally large)
            //   └── rightBigSubtree   (depth 1, record-shared, internally large)
            var gen = new IdGenerator.PerSessionCounter();

            // Build four "big" sibling subtrees (each: 1 NonTerminal + 5 leaf terminals = 6 nodes / 5 internal parent entries).
            var leftBig = bigSubtree(gen, "leftBig", 5);
            var midLeftBig = bigSubtree(gen, "midLeftBig", 5);
            var midRightBig = bigSubtree(gen, "midRightBig", 5);
            var rightBig = bigSubtree(gen, "rightBig", 5);

            var oldPivot = terminal(gen, "oldPivot");
            IdCstNode oldInner = nonTerminal(gen, "Inner", List.of(oldPivot));
            IdCstNode oldMid = nonTerminal(gen, "Mid", List.of(midLeftBig, oldInner, midRightBig));
            IdCstNode oldRoot = nonTerminal(gen, "Root", List.of(leftBig, oldMid, rightBig));

            var index = IdNodeIndex.build(oldRoot);
            int totalNodes = countAll(oldRoot);
            // sanity — total nodes well above any small-constant we'll assert.
            assertThat(totalNodes).isGreaterThan(20);

            // Capture old parent links INSIDE the shared subtrees — they must be
            // unchanged after applyIncremental (proves we did NOT walk into them).
            var leftBigInternalChildIds = collectInternalChildIds(leftBig);
            var midLeftBigInternalChildIds = collectInternalChildIds(midLeftBig);

            // New tree: oldPivot replaced; ancestor chain refreshed.
            // CRITICAL: leftBig, midLeftBig, midRightBig, rightBig are REUSED (same object refs).
            var newPivot = terminal(gen, "newPivot");
            IdCstNode newInner = nonTerminal(gen, "Inner", List.of(newPivot));
            IdCstNode newMid = nonTerminal(gen, "Mid", List.of(midLeftBig, newInner, midRightBig));
            IdCstNode newRoot = nonTerminal(gen, "Root", List.of(leftBig, newMid, rightBig));

            var oldPath = List.of(oldRoot, oldMid, oldInner, (IdCstNode) oldPivot);
            var newPath = List.of(newRoot, newMid, newInner, (IdCstNode) newPivot);

            var newIndex = index.applyIncremental(newRoot, oldPath, newPath);

            // -- Identity preservation: shared subtrees' internal entries are EXACTLY as before.
            for (var e : leftBigInternalChildIds) {
                assertThat(newIndex.parentIdOf(e.childId))
                    .as("left big subtree internal child %d should still point to %d", e.childId, e.parentId)
                    .isEqualTo(org.pragmatica.lang.Option.some(e.parentId));
            }
            for (var e : midLeftBigInternalChildIds) {
                assertThat(newIndex.parentIdOf(e.childId))
                    .as("midLeft big subtree internal child %d should still point to %d", e.childId, e.parentId)
                    .isEqualTo(org.pragmatica.lang.Option.some(e.parentId));
            }

            // -- Microcount: put count is O(splicedSize + depth × branching), NOT O(N).
            // splicedSize = newPivot's subtree size (just newPivot, leaf) = 0 internal +1 (the pivot wired to its parent) = 1
            // depth (newPath.size()) = 4, max branching at any ancestor in newPath = 3 (Mid has 3 children)
            // Bound: 1 + 4*3 = 13. We allow some slack with a generous bound:
            int splicedSize = countAll(newPivot); // 1
            int depthBranchingBound = newPath.size() * 3 /* max branching */;
            int generousBound = splicedSize + depthBranchingBound + 4 /* slack */;
            assertThat(newIndex.lastIncrementalPutCount)
                .as("put count must be O(splicedSize + depth × branching), NOT O(N=%d)", totalNodes)
                .isLessThanOrEqualTo(generousBound)
                .isLessThan(totalNodes); // strictly less than full rebuild
            // Print for diagnostic — captured in test report.
            System.out.println("[Phase 0c] depth-3 incremental put count = "
                               + newIndex.lastIncrementalPutCount
                               + " (vs full-rebuild N = " + totalNodes + ")");
        }

        @Test
        @DisplayName("Equivalence to full rebuild: applyIncremental matches build(newRoot) exactly")
        void equivalence_to_full_rebuild() {
            // Random-ish 3-level tree, splice at depth 2.
            var gen = new IdGenerator.PerSessionCounter();
            var leaf1 = terminal(gen, "1");
            var leaf2 = terminal(gen, "2");
            var leaf3 = terminal(gen, "3");
            var leaf4 = terminal(gen, "4");
            IdCstNode oldChildA = nonTerminal(gen, "A", List.of(leaf1, leaf2));
            IdCstNode oldChildB = nonTerminal(gen, "B", List.of(leaf3, leaf4));
            IdCstNode oldRoot = nonTerminal(gen, "Root", List.of(oldChildA, oldChildB));

            var index = IdNodeIndex.build(oldRoot);

            // Replace child B with a fresh subtree.
            var newLeaf5 = terminal(gen, "5");
            var newLeaf6 = terminal(gen, "6");
            var newLeaf7 = terminal(gen, "7");
            IdCstNode newChildB = nonTerminal(gen, "B", List.of(newLeaf5, newLeaf6, newLeaf7));
            IdCstNode newRoot = nonTerminal(gen, "Root", List.of(oldChildA, newChildB));

            var oldPath = List.of(oldRoot, oldChildB);
            var newPath = List.of(newRoot, newChildB);

            var incremental = index.applyIncremental(newRoot, oldPath, newPath);
            var rebuilt = IdNodeIndex.build(newRoot);

            // Both must agree on every node in the new tree.
            for (var node : flatten(newRoot)) {
                assertThat(incremental.parentIdOf(node.id()))
                    .as("parent of node id %d (rule %s)", node.id(), node.rule())
                    .isEqualTo(rebuilt.parentIdOf(node.id()));
            }
            assertThat(incremental.size()).isEqualTo(rebuilt.size());
            // Old child B and its old descendants are gone from incremental (and absent from rebuilt).
            assertThat(incremental.contains(oldChildB.id())).isFalse();
            assertThat(incremental.contains(leaf3.id())).isFalse();
            assertThat(incremental.contains(leaf4.id())).isFalse();
        }
    }

    // -- helpers --

    private static IdCstNode bigSubtree(IdGenerator gen, String rule, int leafCount) {
        var children = new ArrayList<IdCstNode>(leafCount);
        for (int i = 0; i < leafCount; i++) {
            children.add(terminal(gen, rule + "-leaf-" + i));
        }
        return nonTerminal(gen, rule, children);
    }

    private static int countAll(IdCstNode node) {
        int c = 1;
        if (node instanceof IdCstNode.NonTerminal nt) {
            for (var ch : nt.children()) {
                c += countAll(ch);
            }
        }
        return c;
    }

    private record ParentLink(long childId, long parentId) {}

    /** Collect every (child.id → parent.id) link strictly inside {@code root}'s subtree. */
    private static List<ParentLink> collectInternalChildIds(IdCstNode root) {
        var out = new ArrayList<ParentLink>();
        collectInternalChildIdsInto(root, out);
        return out;
    }

    private static void collectInternalChildIdsInto(IdCstNode node, List<ParentLink> out) {
        if (node instanceof IdCstNode.NonTerminal nt) {
            for (var ch : nt.children()) {
                out.add(new ParentLink(ch.id(), nt.id()));
                collectInternalChildIdsInto(ch, out);
            }
        }
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
