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
        return new NodeIndex(newRoot, parents, nodesById);
    }

    /** Root of the CST this index was built over. */
    public CstNode root() {
        return root;
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
