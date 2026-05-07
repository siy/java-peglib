package org.pragmatica.peg.incremental.experimental;

import org.pragmatica.lang.Option;

import java.util.ArrayList;
import java.util.List;

/**
 * Path D — stable-id-aware parent index for {@link IdCstNode} trees.
 *
 * <p>Identical surface to {@link IdNodeIndex}; the sole difference is the
 * implementation of {@link #applyIncremental}, which exploits the stable
 * ancestor-id invariant guaranteed by {@link StableIdSplicer} to drop the
 * cost from {@code O(N)} (on flat trees) to
 * {@code O(oldPivotSize + newPivotSize)} — independent of N <em>and</em> of
 * tree shape.
 *
 * <h2>Path D vs IdNodeIndex.applyIncremental</h2>
 *
 * <p>{@link IdNodeIndex#applyIncremental} executes three steps:
 *
 * <ol>
 *   <li>Remove every old-path ancestor's up-entry, plus every old-pivot
 *       descendant's up-entry.
 *   <li>Insert the new pivot's subtree internal links and wire the pivot to
 *       its new parent.
 *   <li>Walk the new ancestor chain top-down and rewire ALL direct children
 *       of every ancestor — needed because each new ancestor has a FRESH id
 *       (the old siblings' parent-links still point to the dead old ancestor
 *       ids).
 * </ol>
 *
 * <p>{@link StableIdSplicer} reuses each old ancestor's id when building the
 * corresponding new ancestor record. Consequence:
 *
 * <ul>
 *   <li>Old-path ancestor entries in the parents map remain <em>valid</em> —
 *       their parent's id is unchanged (the parent record was rebuilt but
 *       carries the same id as before). Step 1's old-path-ancestor removal
 *       becomes <em>wrong</em> (would delete still-correct entries).
 *   <li>Step 3's sibling-rewire becomes <em>redundant</em> — sibling subtrees
 *       are reference-shared (per the identity invariant), so their
 *       parent-link entries already point to the correct id (the stable
 *       ancestor id, unchanged across the splice).
 * </ul>
 *
 * <p>Path D therefore performs only the work that <em>must</em> happen:
 *
 * <ol>
 *   <li>Remove the old pivot's descendants (the wholesale-replaced subtree).</li>
 *   <li>Remove the old pivot's own up-pointer (the new pivot has a fresh id by
 *       caller construction, so this entry is dead even with stable ancestors).</li>
 *   <li>Insert the new pivot's subtree internal links.</li>
 *   <li>Wire the new pivot's up-pointer to its parent's stable id.</li>
 * </ol>
 *
 * <p>Total cost: {@code O(oldPivotSize + newPivotSize)}. Independent of N.
 *
 * <h2><strong>Mutate-in-place / invalidate-on-incremental semantics</strong></h2>
 *
 * <p>Same as {@link IdNodeIndex}: {@link #applyIncremental} mutates the
 * receiver's parents map in place and the receiver becomes invalid after the
 * call. Production v0.5.0 will likely use a persistent map for snapshot
 * semantics; that design is out of scope for the spike.
 *
 * <p>This sandbox class is not referenced by {@code peglib-core} and will be
 * promoted, reshaped, or deleted at the Phase 0/1 GO/NO-GO gate.
 *
 * @since 0.5.0
 */
public final class StableIdNodeIndex {
    private final IdCstNode root;
    private final LongLongMap parents;

    /**
     * Test hook — number of {@code parents.put} calls performed during the
     * most recent {@link #applyIncremental} invocation that produced this
     * index, or {@code -1} when this index was produced by {@link #build}.
     * Mirrors {@link IdNodeIndex#lastIncrementalPutCount} for microcount
     * comparisons.
     */
    final int lastIncrementalPutCount;

    /**
     * Test hook — number of {@code parents.remove} calls performed during
     * the most recent {@link #applyIncremental} invocation that produced this
     * index, or {@code -1} when this index was produced by {@link #build}.
     */
    final int lastIncrementalRemoveCount;

    private StableIdNodeIndex(IdCstNode root,
                              LongLongMap parents,
                              int lastIncrementalPutCount,
                              int lastIncrementalRemoveCount) {
        this.root = root;
        this.parents = parents;
        this.lastIncrementalPutCount = lastIncrementalPutCount;
        this.lastIncrementalRemoveCount = lastIncrementalRemoveCount;
    }

    /**
     * Build a fresh index over {@code root}. {@code O(N)} in the descendant
     * count. Used only on the initial parse; subsequent edits should call
     * {@link #applyIncremental} for {@code O(δ)} updates.
     */
    public static StableIdNodeIndex build(IdCstNode root) {
        int expectedSize = countDescendants(root);
        var parents = new LinearProbingLongLongMap(Math.max(expectedSize, 4));
        indexChildren(root, parents);
        return new StableIdNodeIndex(root, parents, -1, -1);
    }

    /**
     * Path D optimized incremental index update. Assumes the splicer
     * preserved ancestor IDs (use {@link StableIdSplicer}).
     *
     * <p>Cost: {@code O(oldPivotSize + newPivotSize)} — independent of N.
     *
     * <p><strong>Invalidates the receiver.</strong> Callers MUST use the
     * returned instance and discard {@code this}.
     *
     * @param newRoot root of the post-edit tree
     * @param oldPath {@code root → oldPivot} chain in the pre-edit tree
     *                (size ≥ 1)
     * @param newPath {@code root → newPivot} chain in the post-edit tree
     *                (size ≥ 1; ancestors carry stable ids matching
     *                {@code oldPath})
     */
    public StableIdNodeIndex applyIncremental(IdCstNode newRoot,
                                              List<IdCstNode> oldPath,
                                              List<IdCstNode> newPath) {
        if (oldPath == null || oldPath.isEmpty()) {
            throw new IllegalArgumentException("oldPath must contain at least the old pivot");
        }
        if (newPath == null || newPath.isEmpty()) {
            throw new IllegalArgumentException("newPath must contain at least the new pivot");
        }
        var oldPivot = oldPath.get(oldPath.size() - 1);
        var newPivot = newPath.get(newPath.size() - 1);

        int putCount = 0;
        int removeCount = 0;

        // Step 1 — remove oldPivot's descendants (wholesale replaced subtree).
        // Note: we do NOT touch oldPath ancestors' entries — their ids are
        // reused on the new path, so the entries are still valid.
        var oldPivotDescendants = new ArrayList<IdCstNode>();
        flattenDescendantsInto(oldPivot, oldPivotDescendants);
        for (var d : oldPivotDescendants) {
            parents.remove(d.id());
            removeCount++;
        }

        // Step 2 — remove the oldPivot's own up-pointer. The new pivot
        // typically has a fresh id (caller responsibility), so the old entry
        // is dead. (If the caller chose to reuse oldPivot.id() for the new
        // pivot, step 4 below will simply re-insert the same entry — still
        // correct.)
        parents.remove(oldPivot.id());
        removeCount++;

        // Step 3 — insert new pivot's subtree internal links.
        indexChildren(newPivot, parents);
        putCount += subtreeChildCount(newPivot);

        // Step 4 — wire the new pivot to its parent's stable id, unless the
        // pivot is itself the new root.
        if (newPath.size() >= 2) {
            var newPivotParent = newPath.get(newPath.size() - 2);
            parents.put(newPivot.id(), newPivotParent.id());
            putCount++;
        }

        // Steps explicitly SKIPPED vs IdNodeIndex.applyIncremental:
        //   - removal of oldPath ancestors' entries (their ids are reused)
        //   - sibling rewire under each new ancestor (siblings already point
        //     to the correct stable id, unchanged across the splice).

        return new StableIdNodeIndex(newRoot, parents, putCount, removeCount);
    }

    /** Root of the CST this index reflects. */
    public IdCstNode root() {
        return root;
    }

    /**
     * Parent ID of the node identified by {@code childId}, or
     * {@link Option#none()} when {@code childId} is the root, absent from
     * this index, or has been removed.
     */
    public Option<Long> parentIdOf(long childId) {
        if (!parents.containsKey(childId)) {
            return Option.none();
        }
        return Option.some(parents.get(childId));
    }

    /**
     * {@code true} iff {@code id} is present as a key (i.e., the node has a
     * parent in this index). Note: the root itself returns {@code false}
     * because the root has no parent entry.
     */
    public boolean contains(long id) {
        return parents.containsKey(id);
    }

    /** Number of parent entries — equals {@code descendantCount(root)}. */
    public int size() {
        return parents.size();
    }

    // -- Helpers (mirror IdNodeIndex's static helpers) --

    private static int countDescendants(IdCstNode node) {
        int count = 0;
        if (node instanceof IdCstNode.NonTerminal nt) {
            for (var child : nt.children()) {
                count += 1 + countDescendants(child);
            }
        }
        return count;
    }

    private static void indexChildren(IdCstNode node, LongLongMap parents) {
        if (node instanceof IdCstNode.NonTerminal nt) {
            for (var child : nt.children()) {
                parents.put(child.id(), nt.id());
                indexChildren(child, parents);
            }
        }
    }

    private static void flattenDescendantsInto(IdCstNode node, List<IdCstNode> out) {
        if (node instanceof IdCstNode.NonTerminal nt) {
            for (var child : nt.children()) {
                out.add(child);
                flattenDescendantsInto(child, out);
            }
        }
    }

    private static int subtreeChildCount(IdCstNode node) {
        return countDescendants(node);
    }
}
