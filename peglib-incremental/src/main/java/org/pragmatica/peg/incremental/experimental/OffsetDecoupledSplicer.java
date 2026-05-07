package org.pragmatica.peg.incremental.experimental;

import java.util.ArrayList;
import java.util.List;

/**
 * Path-A splicer for the v0.5.0 architectural rework prove-out
 * (see {@code docs/incremental/ARCHITECTURE-0.5.0.md} §2 + the path-A
 * blocker resolution).
 *
 * <p>Operates on {@link OffsetDecoupledNode} trees with offsets stored in
 * a separate {@link SpanIndex}. Mirrors the semantics of production
 * {@link org.pragmatica.peg.incremental.internal.TreeSplicer#spliceAndShift}
 * — replace a pivot subtree, shift offsets right of the edit by {@code delta}
 * — but with one critical difference:
 *
 * <ul>
 *   <li><strong>Production</strong>: siblings right of the edit are deep-copied
 *       via {@code shiftAll} because every {@code CstNode} record carries an
 *       embedded {@code SourceSpan} that must be rewritten.
 *   <li><strong>Path A (this class)</strong>: siblings right of the edit are
 *       reference-shared. The offset shift is a single in-place walk over the
 *       primitive {@code long[]} backing the {@link SpanIndex}.
 * </ul>
 *
 * <p>This is the central correctness claim under test by
 * {@code OffsetDecoupledSplicerTest#rightOfEditSiblingsPreserveIdentity}: even
 * for siblings whose offsets need shifting, the record references survive
 * the splice. The shift happens on the SpanIndex side.
 *
 * <h2>Algorithm</h2>
 *
 * <ol>
 *   <li>Copy the receiver's {@link SpanIndex} so the receiver remains valid
 *       (bench fairness — mirrors the snapshot semantics production v0.5.0
 *       will need).
 *   <li>Apply {@link SpanIndex#shift}{@code (editEnd, delta)} on the new
 *       index — rewrites every entry whose {@code startOffset >= editEnd}.
 *       Caller is responsible for inserting {@link SpanIndex} entries for
 *       {@code newPivot}'s subtree BEFORE calling {@code splice}; the
 *       splicer only registers ancestor spans (next step).
 *   <li>Walk {@code oldPath} from leaf to root, building one new
 *       {@link OffsetDecoupledNode.NonTerminal} per ancestor with the spliced
 *       child swapped and every other child slot reference-shared. For each
 *       new ancestor, insert its span into the new {@link SpanIndex}: the
 *       start equals the old ancestor's start (unchanged — the ancestor
 *       began before the edit by the enclosing-node invariant) and the end
 *       equals the old ancestor's end shifted by {@code delta} when the old
 *       end was {@code >= editEnd}, else unchanged. This mirrors
 *       {@code TreeSplicer.rebuildNonTerminal}.
 * </ol>
 *
 * <h2>Cost</h2>
 *
 * <p>{@code O(splicedSize + depth + spanIndex.size())} — the depth term is
 * the ancestor rebuild, the spliced-size term is the new-pivot span
 * registration (caller's responsibility, not in this class), and the
 * {@code spanIndex.size()} term is the eager shift walk. The dominant
 * cost shifts from {@code O(N)} record allocations (production) to
 * {@code O(N)} primitive-long writes (path A) — same big-O class but a
 * radically smaller constant factor and no GC pressure.
 *
 * <p>This sandbox class is not referenced by {@code peglib-core} and will
 * be promoted, reshaped, or deleted at the Phase 1.0/1.1 GO/NO-GO gate.
 *
 * @since 0.5.0
 */
public final class OffsetDecoupledSplicer {

    /**
     * Result of a splice — the new root, the new ancestor path
     * ({@code root → newPivot}, inclusive), and the new (independent)
     * span index.
     */
    public record Result(OffsetDecoupledNode newRoot,
                         List<OffsetDecoupledNode> newPath,
                         SpanIndex newSpans) {}

    private final IdGenerator idGen;

    public OffsetDecoupledSplicer(IdGenerator idGen) {
        this.idGen = idGen;
    }

    /**
     * Splice {@code newPivot} into the tree at {@code oldPath}'s terminus and
     * shift every span whose {@code startOffset >= editEnd} by {@code delta}.
     *
     * <p>Caller MUST register {@code newPivot}'s subtree spans into
     * {@code oldSpans} (or in a way that survives the {@link SpanIndex#copy}
     * below) BEFORE calling this method. The splicer registers ancestor
     * spans only.
     *
     * @param oldSpans pre-edit span index. Copied; the receiver is left valid.
     * @param oldPath  {@code root → oldPivot} chain in the pre-edit tree
     *                 (size ≥ 1, last element is the pivot to replace).
     * @param newPivot the replacement subtree.
     * @param editEnd  the offset at or after which spans should shift.
     * @param delta    the offset delta to apply.
     */
    public Result splice(SpanIndex oldSpans,
                         List<OffsetDecoupledNode> oldPath,
                         OffsetDecoupledNode newPivot,
                         int editEnd,
                         int delta) {
        if (oldPath == null || oldPath.isEmpty()) {
            throw new IllegalArgumentException("oldPath must contain at least the old pivot");
        }
        if (newPivot == null) {
            throw new IllegalArgumentException("newPivot must not be null");
        }
        if (oldSpans == null) {
            throw new IllegalArgumentException("oldSpans must not be null");
        }

        // Step 1 — Independent span index for the post-edit tree.
        var newSpans = oldSpans.copy();

        // Step 2 — Eager shift on the new span index.
        newSpans.shift(editEnd, delta);

        // Pivot IS the root: no ancestor rebuild. The pivot's spans must
        // already be registered in oldSpans by the caller.
        if (oldPath.size() == 1) {
            return new Result(newPivot, List.of(newPivot), newSpans);
        }

        // Step 3 — Rebuild the ancestor chain leaf-to-root, splicing
        // newPivot in and reference-sharing every sibling slot.
        var reversedNewPath = new ArrayList<OffsetDecoupledNode>(oldPath.size());
        reversedNewPath.add(newPivot);

        OffsetDecoupledNode current = newPivot;
        OffsetDecoupledNode oldChild = oldPath.get(oldPath.size() - 1);

        for (int i = oldPath.size() - 2; i >= 0; i--) {
            var oldAncestor = oldPath.get(i);
            if (!(oldAncestor instanceof OffsetDecoupledNode.NonTerminal nt)) {
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

            // Reference-share every sibling slot — both LEFT AND RIGHT of
            // the edit. This is the path-A invariant: siblings right of the
            // edit don't need rebuilding because their offsets live in
            // newSpans, which we already shifted.
            var newChildren = new ArrayList<OffsetDecoupledNode>(children.size());
            for (int k = 0; k < children.size(); k++) {
                newChildren.add(k == slot ? current : children.get(k));
            }

            long newAncestorId = idGen.next();
            var newAncestor = new OffsetDecoupledNode.NonTerminal(
                newAncestorId,
                nt.rule(),
                List.copyOf(newChildren),
                nt.leadingTrivia(),
                nt.trailingTrivia());

            // Compute the new ancestor's span:
            //   start = old start (unchanged — ancestor began before the edit)
            //   end   = old end + delta if old end >= editEnd, else old end
            // Mirrors TreeSplicer.rebuildNonTerminal.
            int oldStart = oldSpans.startOffset(oldAncestor.id());
            int oldEnd = oldSpans.endOffset(oldAncestor.id());
            int newEnd = oldEnd >= editEnd ? oldEnd + delta : oldEnd;
            newSpans.put(newAncestorId, oldStart, newEnd);

            reversedNewPath.add(newAncestor);
            current = newAncestor;
            oldChild = oldAncestor;
        }

        // Reverse to get root → newPivot order.
        var newPath = new ArrayList<OffsetDecoupledNode>(reversedNewPath.size());
        for (int i = reversedNewPath.size() - 1; i >= 0; i--) {
            newPath.add(reversedNewPath.get(i));
        }
        return new Result(current, List.copyOf(newPath), newSpans);
    }

    /** Linear scan for a child by record identity ({@code ==}). */
    private static int indexOfByIdentity(List<OffsetDecoupledNode> children, OffsetDecoupledNode target) {
        for (int i = 0; i < children.size(); i++) {
            if (children.get(i) == target) {
                return i;
            }
        }
        return -1;
    }
}
