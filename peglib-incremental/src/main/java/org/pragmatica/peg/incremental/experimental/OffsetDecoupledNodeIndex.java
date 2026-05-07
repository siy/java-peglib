package org.pragmatica.peg.incremental.experimental;

import org.pragmatica.lang.Option;

import java.util.ArrayList;
import java.util.List;

/**
 * Path-A counterpart of {@link IdNodeIndex} — keyed parent index for
 * {@link OffsetDecoupledNode} trees.
 *
 * <p>Identical algorithm to {@link IdNodeIndex} (build, applyIncremental).
 * The only delta is the node type — we cannot reuse {@code IdNodeIndex}
 * directly because {@link OffsetDecoupledNode} and {@link IdCstNode} are
 * disjoint sealed hierarchies with no common parent. Java sealed-interface
 * constraints make a structural-typing approach more invasive than a clone.
 *
 * <p>Mutate-in-place / invalidate-on-incremental semantics mirror
 * {@link IdNodeIndex}: callers MUST use the returned instance after
 * {@link #applyIncremental} and discard the receiver.
 *
 * <p>This sandbox class is not referenced by {@code peglib-core} and will
 * be promoted, reshaped, or deleted at the Phase 1.0/1.1 GO/NO-GO gate.
 *
 * @since 0.5.0
 */
public final class OffsetDecoupledNodeIndex {
    private final OffsetDecoupledNode root;
    private final LongLongMap parents;

    private OffsetDecoupledNodeIndex(OffsetDecoupledNode root, LongLongMap parents) {
        this.root = root;
        this.parents = parents;
    }

    /**
     * Build a fresh index over {@code root}. {@code O(N)} in the descendant
     * count. Pre-sizes the backing map to avoid resize churn.
     */
    public static OffsetDecoupledNodeIndex build(OffsetDecoupledNode root) {
        int expectedSize = countDescendants(root);
        var parents = new LinearProbingLongLongMap(Math.max(expectedSize, 4));
        indexChildren(root, parents);
        return new OffsetDecoupledNodeIndex(root, parents);
    }

    /**
     * Splice-and-shift index update. Mirrors {@link IdNodeIndex#applyIncremental}.
     */
    public OffsetDecoupledNodeIndex applyIncremental(OffsetDecoupledNode newRoot,
                                                     List<OffsetDecoupledNode> oldPath,
                                                     List<OffsetDecoupledNode> newPath) {
        if (oldPath == null || oldPath.isEmpty()) {
            throw new IllegalArgumentException("oldPath must contain at least the old root");
        }
        if (newPath == null || newPath.isEmpty()) {
            throw new IllegalArgumentException("newPath must contain at least the new root");
        }
        var oldPivot = oldPath.get(oldPath.size() - 1);
        var newPivot = newPath.get(newPath.size() - 1);

        // Step 1a — Remove every old-path node's up-entry.
        for (var oldNode : oldPath) {
            parents.remove(oldNode.id());
        }
        // Step 1b — Remove the old pivot's descendants.
        var oldPivotDescendants = new ArrayList<OffsetDecoupledNode>();
        flattenDescendantsInto(oldPivot, oldPivotDescendants);
        for (var d : oldPivotDescendants) {
            parents.remove(d.id());
        }

        // Step 2 — Insert new pivot's subtree internal links.
        indexChildren(newPivot, parents);
        if (newPath.size() >= 2) {
            var newPivotParent = newPath.get(newPath.size() - 2);
            parents.put(newPivot.id(), newPivotParent.id());
        }

        // Step 3 — Walk new ancestor chain top-down, set parent links for
        // ALL direct children of each ancestor.
        for (int i = 0; i < newPath.size() - 1; i++) {
            var ancestor = newPath.get(i);
            if (ancestor instanceof OffsetDecoupledNode.NonTerminal nt) {
                for (var child : nt.children()) {
                    parents.put(child.id(), ancestor.id());
                }
            }
        }

        return new OffsetDecoupledNodeIndex(newRoot, parents);
    }

    public OffsetDecoupledNode root() {
        return root;
    }

    public Option<Long> parentIdOf(long childId) {
        if (!parents.containsKey(childId)) {
            return Option.none();
        }
        return Option.some(parents.get(childId));
    }

    public boolean contains(long id) {
        return parents.containsKey(id);
    }

    public int size() {
        return parents.size();
    }

    // -- Helpers --

    private static int countDescendants(OffsetDecoupledNode node) {
        int count = 0;
        if (node instanceof OffsetDecoupledNode.NonTerminal nt) {
            for (var child : nt.children()) {
                count += 1 + countDescendants(child);
            }
        }
        return count;
    }

    private static void indexChildren(OffsetDecoupledNode node, LongLongMap parents) {
        if (node instanceof OffsetDecoupledNode.NonTerminal nt) {
            for (var child : nt.children()) {
                parents.put(child.id(), nt.id());
                indexChildren(child, parents);
            }
        }
    }

    private static void flattenDescendantsInto(OffsetDecoupledNode node, List<OffsetDecoupledNode> out) {
        if (node instanceof OffsetDecoupledNode.NonTerminal nt) {
            for (var child : nt.children()) {
                out.add(child);
                flattenDescendantsInto(child, out);
            }
        }
    }
}
