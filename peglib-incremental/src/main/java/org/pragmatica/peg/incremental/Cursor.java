package org.pragmatica.peg.incremental;

import org.pragmatica.peg.incremental.internal.NodeIndex;
import org.pragmatica.peg.tree.CstNode;

/**
 * Editor-style cursor anchored over a {@link Session}'s buffer.
 *
 * <p>0.5.0 (Lever D, SPEC §5): the cursor is split out of {@link Session} so
 * cursor moves don't allocate a new Session. A {@code Cursor} is two longs +
 * an int (the JVM word-aligns to 16 bytes): an offset and the stable
 * {@link CstNode#id() id} of the smallest enclosing CST node at that offset.
 *
 * <p>Phase 1's stable-id invariant ({@link CstNode}'s {@code long id} survives
 * incremental splices for ancestors that aren't wholesale-replaced) lets a
 * cursor outlive the {@link Session} it was created with: as long as the
 * enclosing node still exists in the post-edit tree, the cursor's
 * {@link #enclosingNodeId} resolves to a current record via
 * {@link NodeIndex#nodeById(long)}. When that resolution fails (the enclosing
 * node was inside a replaced subtree), boundary-walking code falls back to
 * top-down descent from the new root.
 *
 * <p>This makes undo/redo cleaner: save {@code (Session, Cursor)} snapshots
 * independently. The Session lineage shares CST structure; the cursor stack
 * is just a 16-byte-per-entry array.
 *
 * @since 0.5.0
 */
public record Cursor(int offset, long enclosingNodeId) {

    /**
     * Construct a {@code Cursor} pointing at {@code offset} in the tree
     * indexed by {@code index}. The {@link #enclosingNodeId} is resolved by
     * {@link NodeIndex#smallestContaining(int)}; when {@code offset} lies
     * outside the root span (e.g., past EOF), the cursor's enclosing falls
     * back to the root.
     *
     * <p>{@code offset} is clamped to {@code [0, root.span().endOffset()]}.
     */
    public static Cursor at(int offset, NodeIndex index) {
        var root = index.root();
        int clamped = clamp(offset, root);
        var enclosing = index.smallestContaining(clamped)
                             .or(root);
        return new Cursor(clamped, enclosing.id());
    }

    /**
     * Move the cursor to {@code newOffset} against the same {@code index}.
     * Pure — no Session involvement, no allocation beyond the new
     * {@code Cursor} record itself (16 bytes). Resolves the new enclosing
     * node by top-down descent through {@code index}.
     */
    public Cursor moveTo(int newOffset, NodeIndex index) {
        return Cursor.at(newOffset, index);
    }

    private static int clamp(int offset, CstNode root) {
        int max = root.span()
                      .end()
                      .offset();
        if (offset < 0) {
            return 0;
        }
        if (offset > max) {
            return max;
        }
        return offset;
    }
}
