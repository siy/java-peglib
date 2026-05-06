package org.pragmatica.peg.incremental.experimental;

import org.pragmatica.lang.Option;

import java.util.ArrayList;
import java.util.List;

/**
 * Stable-ID-keyed parent index for {@link IdCstNode} trees — Phase 0c sandbox.
 *
 * <p>Mirrors the production
 * {@link org.pragmatica.peg.incremental.internal.NodeIndex} surface (parent
 * lookup, presence test, root accessor) but keys the parent map by the stable
 * {@code long id} carried on each {@link IdCstNode} record (per
 * {@code docs/incremental/ARCHITECTURE-0.5.0.md} §2 Lever A) instead of JVM
 * identity. This unlocks the central perf claim of the v0.5.0 rework:
 * {@link #applyIncremental} is {@code O(splicedSize + depth × branchingFactor)},
 * not {@code O(N)}.
 *
 * <h2>Two construction paths</h2>
 * <ul>
 *   <li>{@link #build(IdCstNode)} — initial full walk over a tree. {@code O(N)}
 *       in the node count. Used once per session at the initial parse.
 *   <li>{@link #applyIncremental(IdCstNode, List, List)} — splice-and-shift
 *       update producing a new index that reflects the post-edit tree.
 *       {@code O(oldPath.size() + oldPivotSize + newPivotSize +
 *       newPath.size() × branchingFactor)} — typically 100-300 map operations
 *       even on a 91k-node tree, vs the 91k operations a full rebuild would
 *       require. This is the hot path.
 * </ul>
 *
 * <h2><strong>Mutate-in-place / invalidate-on-incremental semantics</strong></h2>
 *
 * <p>{@link #applyIncremental} <strong>mutates the underlying parents map in
 * place</strong> and returns a NEW {@link IdNodeIndex} sharing that same map
 * with the receiver. <em>The receiver becomes invalid after the call.</em>
 * Callers MUST use the returned instance and discard the receiver. Any
 * subsequent observation of the receiver — including {@link #parentIdOf},
 * {@link #contains}, {@link #size}, {@link #root} — is undefined behaviour.
 *
 * <p>The reason: spike-grade perf measurement. Copying the {@link LongLongMap}
 * on every incremental update would add an {@code O(N)} step that defeats the
 * O(δ) cost claim we're trying to validate. Production v0.5.0 will likely
 * use a persistent (path-copying) map to recover snapshot semantics — that
 * design exploration is explicitly out of scope for Phase 0c.
 *
 * <p>This sandbox class is not referenced by {@code peglib-core} and will be
 * promoted, reshaped, or deleted at the Phase 0 GO/NO-GO gate.
 *
 * @since 0.5.0
 */
public final class IdNodeIndex {
    private final IdCstNode root;
    private final LongLongMap parents;

    /**
     * Test hook — number of {@code parents.put} calls performed during the
     * most recent {@link #applyIncremental} invocation that produced this
     * index, or {@code -1} when this index was produced by {@link #build}.
     * Package-private; used by Phase 0c microcount tests to validate the
     * algorithm is O(δ), not O(N). Will be removed when the spike is
     * promoted (test-only instrumentation).
     */
    final int lastIncrementalPutCount;

    private IdNodeIndex(IdCstNode root, LongLongMap parents, int lastIncrementalPutCount) {
        this.root = root;
        this.parents = parents;
        this.lastIncrementalPutCount = lastIncrementalPutCount;
    }

    /**
     * Build a fresh index over {@code root}. {@code O(N)} in the descendant
     * count. Used only on the initial parse; subsequent edits should call
     * {@link #applyIncremental} for {@code O(δ)} updates.
     *
     * <p>The backing {@link LongLongMap} is pre-sized to the exact descendant
     * count to avoid resize churn during the build (mirrors the 0.4.3
     * {@code NodeIndex} pre-sizing fix; see {@code HANDOVER.md} §5).
     */
    public static IdNodeIndex build(IdCstNode root) {
        int expectedSize = countDescendants(root);
        var parents = new LinearProbingLongLongMap(Math.max(expectedSize, 4));
        indexChildren(root, parents);
        return new IdNodeIndex(root, parents, -1);
    }

    /**
     * Splice-and-shift update: produce a new {@link IdNodeIndex} reflecting a
     * post-edit tree.
     *
     * <p><strong>Invalidates the receiver.</strong> See class Javadoc. Callers
     * MUST use the returned instance and discard {@code this}.
     *
     * @param newRoot root of the post-edit tree
     * @param oldPath {@code root → oldPivot} chain in the pre-edit tree,
     *                inclusive (size ≥ 1; first element is the pre-edit root,
     *                last element is the pre-edit pivot — the smallest node
     *                whose subtree was wholesale replaced)
     * @param newPath {@code root → newPivot} chain in the post-edit tree,
     *                inclusive (size ≥ 1; first element is {@code newRoot},
     *                last element is the post-edit pivot)
     *
     * <h3>Algorithm (per spec §2)</h3>
     * <ol>
     *   <li><strong>Step 1 — Remove dead entries.</strong> Every node on the
     *       old path was replaced (their record identities are dead — the
     *       newPath nodes carry fresh IDs). For each {@code oldNode} in
     *       {@code oldPath} we remove its up-entry. Then we walk the old
     *       pivot's descendants (excluding the pivot itself, already removed
     *       in the previous loop) and remove their up-entries — the old
     *       pivot's subtree is wholesale replaced, so none of its descendants
     *       survive in the new tree by ID.
     *       Cost: {@code O(oldPath.size() + oldPivotSize)}.
     *   <li><strong>Step 2 — Insert new spliced subtree.</strong> Walk the new
     *       pivot's subtree pre-order and {@code parents.put(child.id,
     *       parent.id)} for every parent-child pair. Wire the new pivot to
     *       its new parent (the second-to-last entry in {@code newPath}) when
     *       the pivot is not itself the root.
     *       Cost: {@code O(newPivotSize)}.
     *   <li><strong>Step 3 — Walk new ancestor chain top-down.</strong> For
     *       each new ancestor on {@code newPath} except the pivot itself, set
     *       parent links for ALL its direct children. This catches sibling
     *       subtrees that are record-shared with the old tree (same internal
     *       IDs survive) but whose parent in the new tree has a fresh ID
     *       (different from the old ancestor's ID). Their internal subtrees
     *       are NOT walked — the {@code parents} entries inside those shared
     *       subtrees were created by an earlier {@link #build} or
     *       {@link #applyIncremental} and remain correct because the IDs
     *       there are unchanged.
     *       Cost: {@code O(newPath.size() × branchingFactor)}.
     * </ol>
     *
     * <p><strong>Total cost: O(splicedSize + depth × branchingFactor).</strong>
     * On the 1900-LOC Java fixture (≈10k nodes, depth ≈ 30, branching ≈ 4),
     * a single-token edit produces ≈100-300 map operations vs ≈10k for full
     * rebuild — the central perf claim of the v0.5.0 rework.
     *
     * <h3>Resolved spec ambiguities</h3>
     * <ul>
     *   <li><strong>Step 1 scope of {@code oldPath} removal:</strong> spec
     *       reads "Walk oldPath … remove their entries". We interpret this as
     *       <em>every</em> node on the old path (including {@code oldRoot}),
     *       because even when {@code oldRoot.id == newRoot.id} structurally,
     *       a splice that touches the root produces a new {@link IdCstNode}
     *       record with a fresh id (the {@code IdCstNodeBuilder} assigns IDs
     *       fresh per build). Step 3 then re-establishes parent entries for
     *       the surviving siblings under the new ancestor chain.
     *   <li><strong>Step 1 walk of pivot descendants:</strong> the spec is
     *       silent on whether the pivot's subtree must be cleared. We clear
     *       it (excluding the pivot itself, already removed) because the
     *       splice <em>replaces</em> the pivot's subtree wholesale; no
     *       subtree-internal record sharing is preserved across the splice
     *       boundary. Step 2 then inserts the new pivot's subtree.
     * </ul>
     */
    public IdNodeIndex applyIncremental(IdCstNode newRoot, List<IdCstNode> oldPath, List<IdCstNode> newPath) {
        if (oldPath == null || oldPath.isEmpty()) {
            throw new IllegalArgumentException("oldPath must contain at least the old root");
        }
        if (newPath == null || newPath.isEmpty()) {
            throw new IllegalArgumentException("newPath must contain at least the new root");
        }
        var oldPivot = oldPath.get(oldPath.size() - 1);
        var newPivot = newPath.get(newPath.size() - 1);

        int putCount = 0;

        // Step 1a — Remove every old-path node's up-entry (their records are dead).
        for (var oldNode : oldPath) {
            parents.remove(oldNode.id());
        }
        // Step 1b — Remove the old pivot's descendants (subtree replaced wholesale).
        var oldPivotDescendants = new ArrayList<IdCstNode>();
        flattenDescendantsInto(oldPivot, oldPivotDescendants);
        for (var d : oldPivotDescendants) {
            parents.remove(d.id());
        }

        // Step 2 — Insert new pivot's subtree internal links.
        indexChildren(newPivot, parents);
        putCount += subtreeChildCount(newPivot);
        // Wire pivot to its new parent (unless pivot is the new root).
        if (newPath.size() >= 2) {
            var newPivotParent = newPath.get(newPath.size() - 2);
            parents.put(newPivot.id(), newPivotParent.id());
            putCount++;
        }

        // Step 3 — Walk new ancestor chain (excluding pivot — already wired in step 2).
        // For each ancestor, set parent links for ALL its direct children. Sibling
        // subtrees keep their internal entries (same IDs, still correct).
        for (int i = 0; i < newPath.size() - 1; i++) {
            var ancestor = newPath.get(i);
            if (ancestor instanceof IdCstNode.NonTerminal nt) {
                for (var child : nt.children()) {
                    parents.put(child.id(), ancestor.id());
                    putCount++;
                }
            }
        }

        return new IdNodeIndex(newRoot, parents, putCount);
    }

    /** Root of the CST this index reflects. */
    public IdCstNode root() {
        return root;
    }

    /**
     * Parent ID of the node identified by {@code childId}, or
     * {@link Option#none()} when {@code childId} is the root, absent from this
     * index, or has been removed.
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

    // -- Helpers (mirror the production NodeIndex static helpers) --

    private static int countDescendants(IdCstNode node) {
        int count = 0;
        if (node instanceof IdCstNode.NonTerminal nt) {
            for (var child : nt.children()) {
                count += 1 + countDescendants(child);
            }
        }
        return count;
    }

    /**
     * Pre-order recursive walk: for every parent-child pair under {@code node}
     * (inclusive), {@code parents.put(child.id, parent.id)}. Mirrors the
     * production {@code NodeIndex.indexChildren}.
     */
    private static void indexChildren(IdCstNode node, LongLongMap parents) {
        if (node instanceof IdCstNode.NonTerminal nt) {
            for (var child : nt.children()) {
                parents.put(child.id(), nt.id());
                indexChildren(child, parents);
            }
        }
    }

    /**
     * Pre-order: append every <em>strict descendant</em> of {@code node} to
     * {@code out} (excludes {@code node} itself). Used in step 1 to enumerate
     * the old pivot's subtree for removal.
     */
    private static void flattenDescendantsInto(IdCstNode node, List<IdCstNode> out) {
        if (node instanceof IdCstNode.NonTerminal nt) {
            for (var child : nt.children()) {
                out.add(child);
                flattenDescendantsInto(child, out);
            }
        }
    }

    /**
     * Counts the number of {@code parents.put} calls {@link #indexChildren}
     * will perform on {@code node} — equals the descendant count.
     */
    private static int subtreeChildCount(IdCstNode node) {
        return countDescendants(node);
    }
}
