package org.pragmatica.peg.incremental.internal;

import org.pragmatica.lang.Option;
import org.pragmatica.peg.tree.CstNode;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Span-to-node + child-to-parent index built once over the current CST.
 * Supports SPEC §5.2 "enclosing-node pointer" operations:
 *
 * <ul>
 *   <li>{@link #parentOf(CstNode)} — O(1) parent lookup.</li>
 *   <li>{@link #smallestContaining(int)} — locate the smallest node whose
 *       span contains a given offset (used when a {@link Session} has no
 *       warm enclosing-node pointer yet).</li>
 *   <li>{@link #smallestContainingFrom(CstNode, int)} — climb outward from
 *       a hot pointer until a node containing {@code offset} is found, then
 *       descend to the smallest containing child. O(depth) for adjacent
 *       moves.</li>
 * </ul>
 *
 * <p>The index is keyed by {@link System#identityHashCode identity}. peglib
 * CST records are value objects and two structurally equal subtrees may be
 * equal by {@code .equals}; identity-based mapping is required to keep
 * parent links unambiguous.
 *
 * @since 0.3.1
 */
public final class NodeIndex {
    private final CstNode root;
    private final Map<CstNode, CstNode> parents;

    private NodeIndex(CstNode root, Map<CstNode, CstNode> parents) {
        this.root = root;
        this.parents = parents;
    }

    /**
     * Build a fresh index over {@code root}. O(n) in the node count.
     */
    public static NodeIndex build(CstNode root) {
        var parents = new IdentityHashMap<CstNode, CstNode>();
        indexChildren(root, parents);
        return new NodeIndex(root, parents);
    }

    private static void indexChildren(CstNode node, Map<CstNode, CstNode> parents) {
        if (node instanceof CstNode.NonTerminal nt) {
            for (var child : nt.children()) {
                parents.put(child, nt);
                indexChildren(child, parents);
            }
        }
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
        return Option.option(parents.get(node));
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
        if (start == null || !parents.containsKey(start) && start != root) {
            return smallestContaining(offset);
        }
        var cursor = start;
        while (cursor != null && !contains(cursor, offset)) {
            cursor = parents.get(cursor);
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
            cursor = parents.get(cursor);
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
        return offset >= span.start()
                             .offset() && offset <= span.end()
                                                        .offset();
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
