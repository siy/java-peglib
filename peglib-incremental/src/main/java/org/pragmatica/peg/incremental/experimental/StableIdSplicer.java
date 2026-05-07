package org.pragmatica.peg.incremental.experimental;

import java.util.ArrayList;
import java.util.List;

/**
 * Stable-ID variant of {@link IdTreeSplicer} — Path D spike for the v0.5.0
 * architectural rework
 * (see {@code docs/incremental/ARCHITECTURE-0.5.0.md} §2 Lever A and §8 Q3,
 * and {@code docs/bench-results/phase1-spanindex-results.md} "Architectural
 * insight" for the motivation).
 *
 * <p>Identical to {@link IdTreeSplicer} in every respect EXCEPT one: when
 * building the new ancestor records on the splice path, this splicer
 * <strong>reuses each old ancestor's {@code id}</strong> instead of allocating
 * a fresh one from the {@link IdGenerator}.
 *
 * <h2>Why stable IDs?</h2>
 *
 * <p>Phase 1 (Path A) bench surfaced the actual bottleneck on flat trees:
 * {@code IdNodeIndex.applyIncremental} step 3 walks every direct child of every
 * rebuilt ancestor because the new ancestor carries a fresh ID — every sibling
 * needs its parent-link rewritten to point to the new ID. On a flat tree
 * (one root with N statement children), that's {@code O(N)} parent-link
 * rewrites per edit, dominating any splice-side savings.
 *
 * <p>Reusing the old ancestor's id flips that:
 *
 * <ul>
 *   <li>The new ancestor record is structurally distinct ({@code !=}) but
 *       carries the same {@code id} as the old one.
 *   <li>Sibling subtrees that are reference-shared (per the identity invariant
 *       carried over from {@link IdTreeSplicer}) <em>also</em> have their
 *       existing parent-link entries in any {@link IdNodeIndex}'s parents map
 *       remain valid — the parent's id is unchanged.
 *   <li>{@link StableIdNodeIndex#applyIncremental} can therefore skip the
 *       sibling-rewire step entirely, dropping the cost from
 *       {@code O(N)} per edit to {@code O(oldPivotSize + newPivotSize)}.
 * </ul>
 *
 * <h2>ID semantics tradeoff</h2>
 *
 * <p>This is a semantic shift. With {@link IdTreeSplicer}, an id corresponds
 * 1:1 to a JVM record instance — {@code id == JVM identity}. With
 * {@code StableIdSplicer}, an id corresponds to a <em>logical</em> node
 * preserved across splices when the splicer's discretion deems the structural
 * role unchanged (here: every node on the splice path keeps its id, even
 * though its {@code children} list was rebuilt). Two distinct {@link IdCstNode}
 * records may share an id across edit generations.
 *
 * <p>{@link IdGenerator} is still required (and still parameterizable) because
 * future callers may need to allocate ids for genuinely new internal nodes
 * (e.g., a node split during recovery). The splicer itself does not consume
 * any new ids during the ancestor rebuild on the splice path.
 *
 * <p>This sandbox class is not referenced by {@code peglib-core} and will be
 * promoted, reshaped, or deleted at the Phase 0/1 GO/NO-GO gate.
 *
 * @since 0.5.0
 */
public final class StableIdSplicer {
    /**
     * Result of a splice — the new root and the new path (root → newPivot,
     * inclusive). Path elements at indices {@code [0, newPath.size() - 2]}
     * carry stable ids matching the corresponding {@code oldPath} entries; the
     * last element is the supplied {@code newPivot} (its id is whatever the
     * caller built it with).
     */
    public record Result(IdCstNode newRoot, List<IdCstNode> newPath) {}

    @SuppressWarnings("unused") // retained for symmetry with IdTreeSplicer; future use cases may need to allocate IDs for genuinely new internal nodes (e.g., recovery splits).
    private final IdGenerator idGen;

    public StableIdSplicer(IdGenerator idGen) {
        this.idGen = idGen;
    }

    /**
     * Splice {@code newPivot} into the tree by replacing the last element of
     * {@code oldPath}. Each new ancestor on the path reuses the corresponding
     * old ancestor's id; sibling subtrees are reference-shared.
     *
     * @param oldPath root → oldPivot, inclusive (size ≥ 1)
     * @param newPivot the replacement subtree
     * @return new root + new path; ancestors on the path are fresh records but
     *         carry stable ids matching their {@code oldPath} counterparts
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
        var reversedNewPath = new ArrayList<IdCstNode>(oldPath.size());
        reversedNewPath.add(newPivot);
        var current = newPivot;
        var oldChild = oldPath.get(oldPath.size() - 1);
        for (int i = oldPath.size() - 2; i >= 0; i-- ) {
            var oldAncestor = oldPath.get(i);
            if (! (oldAncestor instanceof IdCstNode.NonTerminal nt)) {
                throw new IllegalStateException(
                "splice path element at depth " + i + " is not a NonTerminal: " + oldAncestor);
            }
            var children = nt.children();
            int slot = indexOfByIdentity(children, oldChild);
            if (slot < 0) {
                throw new IllegalStateException(
                "splice path broken at depth " + i + ": child " + oldChild + " not found in parent's children list");
            }
            // Copy the children list, replacing only the spliced slot. Every
            // other entry is the same reference — preserves the spec §8 Q3
            // identity invariant.
            var newChildren = new ArrayList<IdCstNode>(children.size());
            for (int k = 0; k < children.size(); k++ ) {
                newChildren.add(k == slot
                                ? current
                                : children.get(k));
            }
            // SOLE DIVERGENCE FROM IdTreeSplicer: reuse oldAncestor.id() instead
            // of idGen.next(). The new record is structurally distinct from the
            // old (different children list) but carries the same id; the
            // parents map in any StableIdNodeIndex therefore preserves every
            // sibling's parent-link entry across the edit.
            var newAncestor = new IdCstNode.NonTerminal(
            oldAncestor.id(), nt.span(), nt.rule(), List.copyOf(newChildren), nt.leadingTrivia(), nt.trailingTrivia());
            reversedNewPath.add(newAncestor);
            current = newAncestor;
            oldChild = oldAncestor;
        }
        var newPath = new ArrayList<IdCstNode>(reversedNewPath.size());
        for (int i = reversedNewPath.size() - 1; i >= 0; i-- ) {
            newPath.add(reversedNewPath.get(i));
        }
        return new Result(current, List.copyOf(newPath));
    }

    /**
     * Linear scan for a child by record identity ({@code ==}). Children lists
     * are typically small (≤ 8); a linear scan dominates any hash-based
     * approach.
     */
    private static int indexOfByIdentity(List<IdCstNode> children, IdCstNode target) {
        for (int i = 0; i < children.size(); i++ ) {
            if (children.get(i) == target) {
                return i;
            }
        }
        return - 1;
    }
}
