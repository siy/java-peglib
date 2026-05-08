package org.pragmatica.peg.incremental.internal;

import org.pragmatica.lang.Option;
import org.pragmatica.peg.tree.CstNode;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Span-to-node + child-to-parent index built once over the current CST.
 * Supports SPEC §5.2 "enclosing-node pointer" operations:
 *
 * <ul>
 *   <li>{@link #parentOf(CstNode)} — O(1) parent lookup.</li>
 *   <li>{@link #smallestContaining(int)} — locate the smallest node whose
 *       span contains a given offset (used when a {@link
 *       org.pragmatica.peg.incremental.Session Session} has no warm
 *       enclosing-node pointer yet).</li>
 *   <li>{@link #smallestContainingFrom(CstNode, int)} — climb outward from
 *       a hot pointer until a node containing {@code offset} is found, then
 *       descend to the smallest containing child. O(depth) for adjacent
 *       moves.</li>
 * </ul>
 *
 * <p>Phase 1.5 (v0.5.0): the index is keyed by the stable {@code long id}
 * carried on each {@link CstNode} record (Phase 1.2 invariant) using a
 * primitive-keyed {@link LongLongMap}. The id→node lookup is held in a boxed
 * {@code HashMap<Long, CstNode>}; promoting that to a primitive
 * {@code long → object} map is a future micro-optimisation.
 *
 * <p>Phase 1.6 (v0.5.0): Path D's optimised {@link #applyIncremental} replaces
 * the previous {@link #build}-on-every-edit hot path. Cost drops from
 * {@code O(N)} to {@code O(oldPivotSize + newPivotSize)} — independent of N
 * <em>and</em> of tree shape — by trusting the stable-ancestor-id invariant
 * established by {@link TreeSplicer#spliceAndShift}.
 *
 * @since 0.3.1
 */
public final class NodeIndex {
    private final CstNode root;
    private final LongLongMap parents;
    private final Map<Long, CstNode> nodesById;

    private NodeIndex(CstNode root, LongLongMap parents, Map<Long, CstNode> nodesById) {
        this.root = root;
        this.parents = parents;
        this.nodesById = nodesById;
    }

    /**
     * Build a fresh index over {@code root}. O(n) in the node count.
     */
    public static NodeIndex build(CstNode root) {
        int expectedSize = countDescendants(root) + 1;
        var parents = new LinearProbingLongLongMap(Math.max(expectedSize, 4));
        var nodesById = new HashMap<Long, CstNode>(expectedSize * 2);
        nodesById.put(root.id(), root);
        indexChildren(root, parents, nodesById);
        return new NodeIndex(root, parents, nodesById);
    }

    private static int countDescendants(CstNode node) {
        int count = 0;
        if (node instanceof CstNode.NonTerminal nt) {
            for (var child : nt.children()) {
                count += 1 + countDescendants(child);
            }
        }
        return count;
    }

    private static void indexChildren(CstNode node, LongLongMap parents, Map<Long, CstNode> nodesById) {
        if (node instanceof CstNode.NonTerminal nt) {
            for (var child : nt.children()) {
                parents.put(child.id(), nt.id());
                nodesById.put(child.id(), child);
                indexChildren(child, parents, nodesById);
            }
        }
    }

    private static void flattenDescendantsInto(CstNode node, List<CstNode> out) {
        if (node instanceof CstNode.NonTerminal nt) {
            for (var child : nt.children()) {
                out.add(child);
                flattenDescendantsInto(child, out);
            }
        }
    }

    /**
     * Phase 1.6 (v0.5.0) — Path D optimised splice update.
     *
     * <p>Mutates the receiver's {@code parents} map in place and returns a NEW
     * {@code NodeIndex} sharing that same map. <em>The receiver becomes
     * invalid after the call.</em> Callers MUST use the returned instance and
     * discard {@code this}.
     *
     * <p>Cost: {@code O(oldPivotSize + newPivotSize)}. Only the wholesale-replaced
     * subtree (oldPivot) and the newly inserted subtree (newPivot) need touch
     * the map. Spine ancestors keep their stable ids ({@link
     * TreeSplicer#spliceAndShift} reuses {@code oldAncestor.id()} on rebuild),
     * so their parent-map entries remain valid; right-of-edit subtrees that
     * {@link TreeSplicer#shiftAll} rebuilt also keep every node's id, so their
     * internal parent-map entries are unaffected.
     *
     * @param newRoot root of the post-edit tree (must be {@code newPath.get(0)})
     * @param oldPath {@code root → oldPivot} chain in the pre-edit tree
     *                (size ≥ 1)
     * @param newPath {@code newRoot → newPivot} chain in the post-edit tree
     *                (size ≥ 1; ancestors carry stable ids matching
     *                {@code oldPath})
     * @return a new index reflecting the post-edit tree; the receiver is
     *         invalidated
     */
    public NodeIndex applyIncremental(CstNode newRoot, List<CstNode> oldPath, List<CstNode> newPath) {
        if (oldPath == null || oldPath.isEmpty()) {
            throw new IllegalArgumentException("oldPath must contain at least the old pivot");
        }
        if (newPath == null || newPath.isEmpty()) {
            throw new IllegalArgumentException("newPath must contain at least the new pivot");
        }
        var oldPivot = oldPath.get(oldPath.size() - 1);
        var newPivot = newPath.get(newPath.size() - 1);
        // Step 1 — remove the old pivot's descendants (wholesale-replaced subtree).
        // Their ids are dead: newPivot has fresh ids from the parser's id-gen.
        var oldPivotDescendants = new ArrayList<CstNode>();
        flattenDescendantsInto(oldPivot, oldPivotDescendants);
        for (var d : oldPivotDescendants) {
            parents.remove(d.id());
            nodesById.remove(d.id());
        }
        // Step 2 — remove the old pivot's own up-pointer; newPivot has a fresh id.
        parents.remove(oldPivot.id());
        nodesById.remove(oldPivot.id());
        // Step 3 — insert the new pivot's subtree internal links.
        nodesById.put(newPivot.id(), newPivot);
        indexChildren(newPivot, parents, nodesById);
        // Step 4 — wire newPivot to its parent on the new spine. When the pivot
        // is itself the root, there is no parent entry to write.
        if (newPath.size() >= 2) {
            var newPivotParent = newPath.get(newPath.size() - 2);
            parents.put(newPivot.id(), newPivotParent.id());
        }
        // Step 5 — refresh the spine ancestors' record references in nodesById
        // so subsequent lookups resolve to the post-splice records (their ids
        // are stable, but the records themselves are rebuilt). We DO NOT touch
        // the parents map for these — every ancestor's id is unchanged, so the
        // up-pointer entries already reflect the correct logical parent.
        for (var ancestor : newPath) {
            nodesById.put(ancestor.id(), ancestor);
        }
        // Step 6 — refresh nodesById for right-of-edit subtrees that
        // {@link TreeSplicer#shiftAll} deep-copied. Those subtrees keep every
        // node's stable id (so the parents-map entries remain valid) but the
        // RECORDS are fresh — they carry post-edit (shifted) spans. Without
        // this refresh, {@link #nodesById} keeps mapping the stable id to the
        // pre-edit record with the OLD span. Subsequent boundary walks that
        // resolve a parent via {@code nodesById.get(parentId)} would then see
        // a stale span and pick the wrong pivot — exactly what causes
        // {@code tryIncrementalReparse} to fall through to full reparse on
        // edits that touch a right-of-edit subtree (regression seen between
        // Phase 1.2 and Phase 1.6 in {@code IncrementalSessionBench}).
        //
        // Cost: bounded by the number of right-of-edit subtree NODES under
        // each spine ancestor — i.e., the post-edit shifted region. For the
        // typical edit (small pivot, modest right tail) this stays
        // proportional to {@code O(rightOfEditNodeCount)}. The previous
        // {@link #build}-on-every-edit baseline traversed the WHOLE tree, so
        // this is still strictly cheaper.
        for (int i = 0; i + 1 < newPath.size(); i++) {
            refreshShiftedChildrenOf(newPath.get(i), newPath.get(i + 1), nodesById);
        }
        return new NodeIndex(newRoot, parents, nodesById);
    }

    /**
     * Refresh {@link #nodesById} entries for every right-of-edit child of
     * {@code spineAncestor} except {@code spineChild}. {@code spineChild} is
     * the next ancestor on the new spine — already refreshed by step 5 of
     * {@link #applyIncremental}; descending into it would also touch the
     * newPivot subtree which is already indexed by step 3. Left-of-edit
     * children are kept by reference (not rebuilt) so their nodesById
     * entries remain valid.
     */
    private static void refreshShiftedChildrenOf(CstNode spineAncestor,
                                                 CstNode spineChild,
                                                 Map<Long, CstNode> nodesById) {
        if (! (spineAncestor instanceof CstNode.NonTerminal nt)) {
            return;
        }
        for (var child : nt.children()) {
            if (child == spineChild) {
                continue;
            }
            // Whether {@link TreeSplicer} kept this child by reference (left
            // of edit) or deep-copied it via {@link TreeSplicer#shiftAll} (at
            // or after edit), refreshing the nodesById entry to point at the
            // current record is always correct: same id, equal-or-shifted
            // span. Walk the subtree to refresh descendants likewise.
            refreshAllNodesById(child, nodesById);
        }
    }

    private static void refreshAllNodesById(CstNode node, Map<Long, CstNode> nodesById) {
        nodesById.put(node.id(), node);
        if (node instanceof CstNode.NonTerminal nt) {
            for (var child : nt.children()) {
                refreshAllNodesById(child, nodesById);
            }
        }
    }

    /** Root of the CST this index was built over. */
    public CstNode root() {
        return root;
    }

    /**
     * Resolve a stable {@link CstNode#id() id} to its current record reference
     * in the post-edit tree. Returns {@link Option#none()} when the id is not
     * known to this index — including the case where the id was valid in a
     * predecessor index but its node was wholesale-replaced by an incremental
     * splice (in which case the carrying record is gone with that splice).
     *
     * <p>0.5.0 (Lever D): used by {@link org.pragmatica.peg.incremental.Cursor}
     * to convert its persistent {@code enclosingNodeId} back into a warm
     * pointer for the boundary walk in
     * {@link org.pragmatica.peg.incremental.internal.IncrementalSession}.
     */
    public Option<CstNode> nodeById(long id) {
        return Option.option(nodesById.get(id));
    }

    /**
     * The parent of {@code node}, or empty {@link Option} when {@code node}
     * is the root (or not present in this index).
     */
    public Option<CstNode> parentOf(CstNode node) {
        if (node == null) {
            return Option.none();
        }
        long parentId = parents.get(node.id());
        if (parentId == LongLongMap.MISSING) {
            return Option.none();
        }
        return Option.option(nodesById.get(parentId));
    }

    /**
     * Smallest node whose span contains {@code offset}. Returns the root for
     * any offset within its span; empty {@link Option} when the root itself
     * does not contain the offset.
     */
    public Option<CstNode> smallestContaining(int offset) {
        if (!contains(root, offset)) {
            return Option.none();
        }
        return Option.some(descendTo(root, offset));
    }

    /**
     * Phase 2 (v0.5.0) — Lever B: top-down descent to the smallest node whose
     * span <em>strictly contains</em> the half-open edit range
     * {@code [editStart, editEnd]} in the pre-edit buffer.
     *
     * <p>Replaces the warm-pointer walk-up from
     * {@link IncrementalSession#findBoundaryCandidate} for incremental boundary
     * selection. With Phase 1's stable IDs and id-keyed {@code nodesById},
     * descent-from-root is O(depth × branching) — a few microseconds even on
     * tens-of-thousands-of-node trees. Top-down is also REGIME-INSENSITIVE: a
     * cursor pinned at offset 0 picks the same pivot as one moved to the edit
     * site, eliminating Phase 1.7's Regime A/B asymmetry.
     *
     * <p>Algorithm: start at root; at each level look for the (single) child
     * whose span strictly contains the edit range; descend if found, stop if
     * not. The stopping point is either:
     * <ul>
     *   <li>a leaf (Terminal/Token/Error) entirely containing the edit, or</li>
     *   <li>an interior node whose children straddle the edit boundary (no
     *       single child contains it). That node is the correct pivot — the
     *       smallest <em>structural</em> region affected.</li>
     * </ul>
     *
     * <p>Boundary semantics match {@link #contains(CstNode, int)}: offsets are
     * inclusive on both ends. A zero-length insertion at offset X is treated
     * as inside any node whose {@code start <= X <= end}.
     *
     * <p>Returns {@link Option#none()} when the root itself does not contain
     * {@code [editStart, editEnd]} (e.g., append past EOF). Callers handle
     * the empty case by falling back to a full reparse.
     */
    public Option<CstNode> smallestEnclosing(int editStart, int editEnd) {
        if (root == null) {
            return Option.none();
        }
        var rootSpan = root.span();
        if (rootSpan.startOffset() > editStart || rootSpan.endOffset() < editEnd) {
            return Option.none();
        }
        var current = root;
        while (current instanceof CstNode.NonTerminal nt) {
            var next = pickStrictlyContainingChild(nt, editStart, editEnd);
            if (next == null) {
                break;
            }
            current = next;
        }
        return Option.some(current);
    }

    private static CstNode pickStrictlyContainingChild(CstNode.NonTerminal nt, int editStart, int editEnd) {
        for (var child : nt.children()) {
            var span = child.span();
            if (span.startOffset() <= editStart && editEnd <= span.endOffset()) {
                return child;
            }
        }
        return null;
    }

    /**
     * Climb from {@code start} up through parents until a node containing
     * {@code offset} is found, then descend to the smallest containing
     * descendant. Matches SPEC §5.2 "cheap walk from current enclosingNode".
     *
     * <p>When {@code start} is not in this index (e.g., session fork), falls
     * back to {@link #smallestContaining(int)} from the root.
     */
    public Option<CstNode> smallestContainingFrom(CstNode start, int offset) {
        if (start == null) {
            return smallestContaining(offset);
        }
        // start may legitimately BE the root (no parent entry in the map). Check
        // both conditions: present as a key in the parents map OR equal to root.
        boolean knownToIndex = start == root || parents.containsKey(start.id());
        if (!knownToIndex) {
            return smallestContaining(offset);
        }
        var cursor = start;
        while (cursor != null && !contains(cursor, offset)) {
            long parentId = parents.get(cursor.id());
            if (parentId == LongLongMap.MISSING) {
                cursor = null;
            }else {
                cursor = nodesById.get(parentId);
            }
        }
        if (cursor == null) {
            return Option.none();
        }
        return Option.some(descendTo(cursor, offset));
    }

    /**
     * Path from {@code root} to {@code node}, inclusive. Useful when an
     * algorithm needs to walk back up a specific spine, or to find the
     * depth of {@code node}. Returns an empty deque when {@code node} is
     * absent from this index (and is not the root).
     */
    public Deque<CstNode> pathTo(CstNode node) {
        var stack = new ArrayDeque<CstNode>();
        var cursor = node;
        while (cursor != null) {
            stack.push(cursor);
            if (cursor == root) {
                return stack;
            }
            long parentId = parents.get(cursor.id());
            if (parentId == LongLongMap.MISSING) {
                cursor = null;
            }else {
                cursor = nodesById.get(parentId);
            }
        }
        stack.clear();
        return stack;
    }

    private static CstNode descendTo(CstNode node, int offset) {
        var current = node;
        outer :
        while (current instanceof CstNode.NonTerminal nt) {
            for (var child : nt.children()) {
                if (contains(child, offset)) {
                    current = child;
                    continue outer;
                }
            }
            break;
        }
        return current;
    }

    /**
     * {@code true} iff {@code node.span().start().offset() <= offset <=
     * node.span().end().offset()}. Using {@code <=} on both ends lets a
     * cursor sitting right at a boundary be considered "inside" the adjacent
     * node — usually the desired behaviour for editor-style cursors.
     */
    public static boolean contains(CstNode node, int offset) {
        var span = node.span();
        return offset >= span.startOffset() && offset <= span.endOffset();
    }

    /**
     * List every node in the subtree rooted at {@code node}, including
     * {@code node} itself. Pre-order traversal.
     */
    public static List<CstNode> flatten(CstNode node) {
        var out = new java.util.ArrayList<CstNode>();
        flattenInto(node, out);
        return out;
    }

    private static void flattenInto(CstNode node, List<CstNode> out) {
        out.add(node);
        if (node instanceof CstNode.NonTerminal nt) {
            for (var child : nt.children()) {
                flattenInto(child, out);
            }
        }
    }
}
