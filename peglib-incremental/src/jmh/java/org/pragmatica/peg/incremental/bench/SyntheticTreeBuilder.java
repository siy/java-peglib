package org.pragmatica.peg.incremental.bench;

import org.pragmatica.peg.incremental.experimental.IdCstNode;
import org.pragmatica.peg.incremental.experimental.IdGenerator;
import org.pragmatica.peg.tree.SourceSpan;
import org.pragmatica.peg.tree.Trivia;

import java.util.ArrayList;
import java.util.List;

/**
 * Synthesizes balanced {@link IdCstNode} trees for the Phase 0 perf-gate JMH
 * spike (see {@code docs/incremental/ARCHITECTURE-0.5.0.md} §6 Phase 0,
 * §9 perf targets, and {@link Phase0SpikeBench}).
 *
 * <p>The builder produces a balanced tree of approximately {@code targetSize}
 * nodes with the supplied branching factor — branching 4 mirrors the typical
 * fan-out of a real PEG CST. Leaves are {@link IdCstNode.Terminal}; interior
 * nodes are {@link IdCstNode.NonTerminal} with rule names cycled from a small
 * pool ({@code "Block"}, {@code "Stmt"}, {@code "Expr"}, {@code "Decl"}).
 *
 * <p>This class is only used from the JMH source tree and is sandbox-only;
 * it is not referenced by {@code peglib-core} and will be deleted at the
 * Phase 0 GO/NO-GO gate.
 *
 * @since 0.5.0
 */
final class SyntheticTreeBuilder {

    private static final SourceSpan SPAN = new SourceSpan(1, 1, 0, 1, 1, 0);
    private static final List<Trivia> NO_TRIVIA = List.of();
    private static final String[] RULES = {"Block", "Stmt", "Expr", "Decl"};

    private SyntheticTreeBuilder() {}

    /**
     * Build a balanced tree of approximately {@code targetSize} nodes with the
     * given {@code branchingFactor}.
     *
     * <p>The tree is a perfect {@code branchingFactor}-ary tree: depth is
     * chosen as {@code ceil(log_b(targetSize))}, then the bottom level is
     * truncated so the total node count is the largest perfect tree
     * {@code <= targetSize × 1.2}. Exact sizing is not critical for a
     * spike bench — what matters is that depth and branching are realistic.
     */
    static IdCstNode buildBalanced(int targetSize, int branchingFactor, IdGenerator idGen) {
        if (targetSize < 1) {
            throw new IllegalArgumentException("targetSize must be >= 1");
        }
        if (branchingFactor < 2) {
            throw new IllegalArgumentException("branchingFactor must be >= 2");
        }
        // Compute depth d so that branchingFactor^d roughly matches targetSize.
        // For a balanced tree: total nodes = (b^(d+1) - 1) / (b - 1).
        int depth = 0;
        long total = 1;
        long levelSize = 1;
        while (total < targetSize) {
            depth++;
            levelSize *= branchingFactor;
            total += levelSize;
        }
        return buildSubtree(depth, branchingFactor, idGen);
    }

    /**
     * Recursively build a perfect tree of given depth. Depth 0 → single
     * {@link IdCstNode.Terminal} leaf. Depth &gt; 0 → {@link IdCstNode.NonTerminal}
     * with {@code branchingFactor} children of depth-1 subtrees.
     *
     * <p>Children are built first (post-order ID assignment, mirroring
     * {@link org.pragmatica.peg.incremental.experimental.IdCstNodeBuilder}).
     */
    private static IdCstNode buildSubtree(int depth, int branchingFactor, IdGenerator idGen) {
        if (depth == 0) {
            return new IdCstNode.Terminal(idGen.next(), SPAN, "Leaf", "x", NO_TRIVIA, NO_TRIVIA);
        }
        var children = new ArrayList<IdCstNode>(branchingFactor);
        for (int i = 0; i < branchingFactor; i++) {
            children.add(buildSubtree(depth - 1, branchingFactor, idGen));
        }
        var rule = RULES[depth % RULES.length];
        return new IdCstNode.NonTerminal(idGen.next(), SPAN, rule, List.copyOf(children), NO_TRIVIA, NO_TRIVIA);
    }

    /**
     * Find a path from {@code root} to a node at exactly {@code targetDepth}.
     * Walks the leftmost child at each level. Used to position the splice
     * pivot at a representative interior depth.
     *
     * <p>Returns the inclusive {@code root → pivot} path. The list size equals
     * {@code targetDepth + 1}.
     *
     * @throws IllegalArgumentException if the tree is not deep enough
     */
    static List<IdCstNode> findPathAtDepth(IdCstNode root, int targetDepth) {
        if (targetDepth < 0) {
            throw new IllegalArgumentException("targetDepth must be >= 0");
        }
        var path = new ArrayList<IdCstNode>(targetDepth + 1);
        path.add(root);
        var current = root;
        for (int i = 0; i < targetDepth; i++) {
            if (!(current instanceof IdCstNode.NonTerminal nt) || nt.children().isEmpty()) {
                throw new IllegalArgumentException(
                    "tree not deep enough: requested depth " + targetDepth + ", reached level " + i);
            }
            current = nt.children().get(0);
            path.add(current);
        }
        return List.copyOf(path);
    }

    /**
     * Build a small replacement subtree to splice in at the pivot. The pivot
     * is a small balanced {@code branchingFactor}-ary tree of the requested
     * depth, sharing IDs from the supplied generator (so it doesn't collide
     * with the existing tree).
     */
    static IdCstNode buildPivot(int pivotDepth, int branchingFactor, IdGenerator idGen) {
        return buildSubtree(pivotDepth, branchingFactor, idGen);
    }

    /** Count every node in the tree (terminals and non-terminals). */
    static int countAll(IdCstNode node) {
        int c = 1;
        if (node instanceof IdCstNode.NonTerminal nt) {
            for (var child : nt.children()) {
                c += countAll(child);
            }
        }
        return c;
    }
}
