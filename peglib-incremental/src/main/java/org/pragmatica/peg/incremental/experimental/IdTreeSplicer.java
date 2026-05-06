package org.pragmatica.peg.incremental.experimental;

import java.util.ArrayList;
import java.util.List;

/**
 * Hand-rolled sandbox splicer for {@link IdCstNode} trees — Phase 0d.1 spike
 * for the v0.5.0 architectural rework
 * (see {@code docs/incremental/ARCHITECTURE-0.5.0.md} §2 Lever A and §8 Q3).
 *
 * <p>Given an {@code oldPath} (root → oldPivot, inclusive) and a freshly built
 * {@code newPivot}, produces a new tree by replacing {@code oldPivot} with
 * {@code newPivot} and rebuilding ONLY the ancestor chain. Every sibling
 * subtree of every node on the splice path is reference-shared ({@code ==})
 * with the corresponding subtree in the input tree.
 *
 * <p>This is the GO/NO-GO gate for spec §8 Q3 — the "identity-preservation
 * invariant" — without which {@link IdNodeIndex#applyIncremental} cannot
 * achieve its O(δ) cost claim. (If sibling subtrees were re-allocated by the
 * splicer, the index update would have to re-walk them, defeating the whole
 * point.)
 *
 * <h2>Algorithm</h2>
 *
 * <ol>
 *   <li>Walk {@code oldPath} from leaf upward.
 *   <li>At each ancestor, locate the child slot by record identity ({@code ==})
 *       to the next-deeper element of {@code oldPath}.
 *   <li>Build a fresh {@link IdCstNode.NonTerminal} with new ID, copying the
 *       old children list verbatim except for the spliced slot — every other
 *       slot is reference-shared.
 *   <li>Continue upward; the rebuilt ancestor becomes the splice target for
 *       the next level.
 * </ol>
 *
 * <p>Trivia lists are reference-shared (immutable). {@link org.pragmatica.peg.tree.SourceSpan}
 * is reference-shared (immutable record). Only fresh allocations: one
 * {@link IdCstNode.NonTerminal} per ancestor on the splice path, each with a
 * new {@code ArrayList} for {@code children}.
 *
 * <p>This sandbox class is not referenced by {@code peglib-core} and will be
 * promoted, reshaped, or deleted at the Phase 0 GO/NO-GO gate.
 *
 * @since 0.5.0
 */
public final class IdTreeSplicer {

    /**
     * Result of a splice — the new root and the new path (root → newPivot,
     * inclusive). The new path is parallel to {@code oldPath}: {@code newPath.get(i)}
     * is the freshly allocated ancestor that replaces {@code oldPath.get(i)}.
     */
    public record Result(IdCstNode newRoot, List<IdCstNode> newPath) {}

    private final IdGenerator idGen;

    public IdTreeSplicer(IdGenerator idGen) {
        this.idGen = idGen;
    }

    /**
     * Splice {@code newPivot} into the tree by replacing the last element of
     * {@code oldPath}.
     *
     * @param oldPath root → oldPivot, inclusive (size ≥ 1)
     * @param newPivot the replacement subtree
     * @return new root + new path; every sibling subtree of every splice-path
     *         node is reference-shared ({@code ==}) with the corresponding old
     *         subtree
     * @throws IllegalArgumentException when {@code oldPath} is null or empty
     * @throws IllegalStateException when an ancestor is not a {@code NonTerminal}
     *         or the child-identity link in {@code oldPath} is broken
     */
    public Result splice(List<IdCstNode> oldPath, IdCstNode newPivot) {
        if (oldPath == null || oldPath.isEmpty()) {
            throw new IllegalArgumentException("oldPath must contain at least the old pivot");
        }
        if (newPivot == null) {
            throw new IllegalArgumentException("newPivot must not be null");
        }

        // Pivot IS the root — no rebuild needed.
        if (oldPath.size() == 1) {
            return new Result(newPivot, List.of(newPivot));
        }

        // Accumulator: builds newPath in REVERSE (leaf → root), reversed at end.
        // We pre-size to oldPath.size() to avoid resize churn.
        var reversedNewPath = new ArrayList<IdCstNode>(oldPath.size());
        reversedNewPath.add(newPivot);

        var current = newPivot;
        var oldChild = oldPath.get(oldPath.size() - 1);

        for (int i = oldPath.size() - 2; i >= 0; i--) {
            var oldAncestor = oldPath.get(i);
            if (!(oldAncestor instanceof IdCstNode.NonTerminal nt)) {
                throw new IllegalStateException(
                    "splice path element at depth " + i + " is not a NonTerminal: " + oldAncestor);
            }
            var children = nt.children();
            int slot = indexOfByIdentity(children, oldChild);
            if (slot < 0) {
                throw new IllegalStateException(
                    "splice path broken at depth " + i
                    + ": child " + oldChild + " not found in parent's children list");
            }

            // Copy the children list, replacing only the spliced slot.
            // CRITICAL: every other entry is the same reference — this is what
            // preserves the identity invariant (spec §8 Q3).
            var newChildren = new ArrayList<IdCstNode>(children.size());
            for (int k = 0; k < children.size(); k++) {
                newChildren.add(k == slot ? current : children.get(k));
            }

            var newAncestor = new IdCstNode.NonTerminal(
                idGen.next(),
                nt.span(),
                nt.rule(),
                List.copyOf(newChildren),
                nt.leadingTrivia(),
                nt.trailingTrivia());

            reversedNewPath.add(newAncestor);
            current = newAncestor;
            oldChild = oldAncestor;
        }

        // Reverse to get root → newPivot order.
        var newPath = new ArrayList<IdCstNode>(reversedNewPath.size());
        for (int i = reversedNewPath.size() - 1; i >= 0; i--) {
            newPath.add(reversedNewPath.get(i));
        }
        return new Result(current, List.copyOf(newPath));
    }

    /**
     * Linear scan for a child by record identity ({@code ==}). Children lists
     * are typically small (≤ 8); a linear scan is dominant and avoids hashing.
     */
    private static int indexOfByIdentity(List<IdCstNode> children, IdCstNode target) {
        for (int i = 0; i < children.size(); i++) {
            if (children.get(i) == target) {
                return i;
            }
        }
        return -1;
    }
}
