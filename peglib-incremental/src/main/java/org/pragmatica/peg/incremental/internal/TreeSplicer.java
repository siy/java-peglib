package org.pragmatica.peg.incremental.internal;

import org.pragmatica.peg.tree.CstNode;
import org.pragmatica.peg.tree.SourceSpan;
import org.pragmatica.peg.tree.Trivia;

import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Splice a freshly parsed subtree into an existing CST, shifting all spans
 * that lie after the edit by {@code delta}.
 *
 * <p>Two responsibilities:
 * <ol>
 *   <li>{@link #spliceAndShift} — replace {@code oldSubtree} in its parent
 *       spine with {@code newSubtree}, then shift the spans of every node
 *       (and trivia) that starts at or after {@code editEnd} by {@code
 *       delta}. Ancestor spans on the spine are extended / contracted to
 *       accommodate the new subtree.</li>
 *   <li>{@link #shiftAll} — produce a deep copy of a CST with every span's
 *       start and end offsets shifted by {@code delta}. Used for the
 *       degenerate case where the reparsed subtree <em>is</em> the root.</li>
 * </ol>
 *
 * <p>Line/column are left unchanged — SPEC v1 pins parity on rule-node
 * structure and span-offset equality with the full-parse oracle; callers
 * that need accurate line/column after an edit use the {@code text()}
 * snapshot + an external tokenizer, or call {@code session.reparseAll()}.
 * CstHash (which is what parity checks) hashes offsets only, so the shifted
 * locations do not affect correctness.
 *
 * <p>Phase 1.4 (v0.5.0): when rebuilding ancestors on the spine, this splicer
 * <strong>reuses each old ancestor's {@code id}</strong> instead of allocating
 * a fresh one. Path D's {@link NodeIndex#applyIncremental} relies on this —
 * stable ancestor IDs let the index skip the {@code O(N)} sibling-rewire that
 * dominates flat-tree edits.
 *
 * <p>Phase 1.6 (v0.5.0): {@link #spliceAndShift} returns a {@link SpliceResult}
 * that exposes (a) the new root and (b) the {@code root → newPivot} path with
 * stable ancestor ids. The Path D {@code applyIncremental} algorithm consumes
 * both.
 *
 * <p><strong>Right-of-edit handling.</strong> {@link #shiftAll} preserves the
 * source node's {@code id} on every rebuilt record (Phase 1.2/1.4 invariant).
 * Consequence: the {@link NodeIndex} parents-map entries inside any
 * right-of-edit subtree remain valid across the splice — every
 * {@code (childId → parentId)} pair stays correct because both ids are
 * preserved. {@link #spliceAndShift} therefore does NOT need to expose the
 * shifted subtrees as separate bookkeeping; Path D's
 * {@code applyIncremental} only touches the old-pivot subtree (dead) and the
 * new-pivot subtree (fresh ids). This is the central correctness argument
 * behind the {@code O(oldPivotSize + newPivotSize)} cost claim.
 *
 * @since 0.3.1
 */
public final class TreeSplicer {
    private TreeSplicer() {}

    /**
     * Phase 1.6 (v0.5.0): the bookkeeping returned by {@link #spliceAndShift}.
     *
     * <ul>
     *   <li>{@link #newRoot} — root of the post-splice tree.</li>
     *   <li>{@link #newPath} — {@code newRoot → newPivot}, inclusive. Indices
     *       {@code [0, size-2]} carry stable ids matching the corresponding
     *       {@code oldPath} entries (Phase 1.4 invariant); the last element is
     *       the supplied {@code newSubtree}.</li>
     * </ul>
     */
    public record SpliceResult(CstNode newRoot, List<CstNode> newPath) {}

    /**
     * Build a new root by replacing {@code oldSubtree} with {@code newSubtree}
     * along the {@code path} spine. {@code path} must start at the root and
     * end at {@code oldSubtree}.
     *
     * <p>After the swap, every node whose span starts at or after
     * {@code editEnd} (in original-buffer coordinates) has its span offsets
     * shifted by {@code delta}. Nodes unrelated to the spine but outside the
     * shifted region are kept by reference — structural sharing (SPEC §4.2).
     */
    public static SpliceResult spliceAndShift(Deque<CstNode> path,
                                              CstNode oldSubtree,
                                              CstNode newSubtree,
                                              int editEnd,
                                              int delta) {
        if (path.isEmpty() || path.peekLast() != oldSubtree) {
            throw new IllegalArgumentException("path must terminate at oldSubtree");
        }
        var pathList = new ArrayList<>(path);
        // newPath is built leaf-first then reversed.
        var reversedNewPath = new ArrayList<CstNode>(pathList.size());
        reversedNewPath.add(newSubtree);
        // Start with the new subtree, walk up the spine replacing each ancestor.
        var replacement = newSubtree;
        for (int i = pathList.size() - 2; i >= 0; i-- ) {
            var ancestor = pathList.get(i);
            var next = pathList.get(i + 1);
            // replacement replaces `next` among ancestor's children.
            if (! (ancestor instanceof CstNode.NonTerminal nt)) {
                throw new IllegalStateException("ancestor at depth " + i + " is not a NonTerminal: " + ancestor);
            }
            var newChildren = new ArrayList<CstNode>(nt.children()
                                                       .size());
            for (var child : nt.children()) {
                if (child == next) {
                    newChildren.add(replacement);
                }else if (child.span()
                               .startOffset() >= editEnd) {
                    newChildren.add(shiftAll(child, delta));
                }else {
                    newChildren.add(child);
                }
            }
            replacement = rebuildNonTerminal(nt, newChildren, delta, editEnd);
            reversedNewPath.add(replacement);
        }
        var newPath = new ArrayList<CstNode>(reversedNewPath.size());
        for (int i = reversedNewPath.size() - 1; i >= 0; i-- ) {
            newPath.add(reversedNewPath.get(i));
        }
        return new SpliceResult(replacement, List.copyOf(newPath));
    }

    /**
     * Rebuild ancestor with updated children. Ancestor span grows/shrinks at
     * its end by {@code delta}; its start is unchanged (ancestor started
     * before the edit, by the enclosing-node invariant). Trivia following
     * the ancestor at or after {@code editEnd} is shifted.
     */
    private static CstNode.NonTerminal rebuildNonTerminal(CstNode.NonTerminal ancestor,
                                                          List<CstNode> newChildren,
                                                          int delta,
                                                          int editEnd) {
        var oldSpan = ancestor.span();
        int newEndOffset = oldSpan.endOffset() >= editEnd
                           ? oldSpan.endOffset() + delta
                           : oldSpan.endOffset();
        var newSpan = new SourceSpan(oldSpan.startLine(), oldSpan.startColumn(), oldSpan.startOffset(),
                                     oldSpan.endLine(), oldSpan.endColumn(), newEndOffset);
        return new CstNode.NonTerminal(
        ancestor.id(),
        newSpan,
        ancestor.rule(),
        List.copyOf(newChildren),
        shiftTrivia(ancestor.leadingTrivia(), delta, editEnd),
        shiftTrivia(ancestor.trailingTrivia(), delta, editEnd));
    }

    /**
     * Deep copy of {@code node} with every span offset shifted by
     * {@code delta} (start and end both shift by the same amount — this is a
     * wholesale move, not a resize). Used when a subtree sits entirely to
     * the right of the edit region.
     *
     * <p>Phase 1.4 (v0.5.0): every rebuilt record reuses the source node's
     * {@code id}. Path D's stable-id invariant requires it — even when the
     * record itself is fresh, the logical node identity carries through.
     */
    public static CstNode shiftAll(CstNode node, int delta) {
        if (delta == 0) {
            return node;
        }
        var span = shiftSpan(node.span(), delta);
        return switch (node) {
            case CstNode.Terminal t -> new CstNode.Terminal(
            t.id(),
            span,
            t.rule(),
            t.text(),
            shiftTriviaAll(t.leadingTrivia(), delta),
            shiftTriviaAll(t.trailingTrivia(), delta));
            case CstNode.Token t -> new CstNode.Token(
            t.id(),
            span,
            t.rule(),
            t.text(),
            shiftTriviaAll(t.leadingTrivia(), delta),
            shiftTriviaAll(t.trailingTrivia(), delta));
            case CstNode.Error e -> new CstNode.Error(
            e.id(),
            span,
            e.skippedText(),
            e.expected(),
            shiftTriviaAll(e.leadingTrivia(), delta),
            shiftTriviaAll(e.trailingTrivia(), delta));
            case CstNode.NonTerminal nt -> {
                var shifted = new ArrayList<CstNode>(nt.children()
                                                       .size());
                for (var child : nt.children()) {
                    shifted.add(shiftAll(child, delta));
                }
                yield new CstNode.NonTerminal(
                nt.id(),
                span,
                nt.rule(),
                List.copyOf(shifted),
                shiftTriviaAll(nt.leadingTrivia(), delta),
                shiftTriviaAll(nt.trailingTrivia(), delta));
            }
        };
    }

    private static SourceSpan shiftSpan(SourceSpan span, int delta) {
        return new SourceSpan(span.startLine(), span.startColumn(), span.startOffset() + delta,
                              span.endLine(), span.endColumn(), span.endOffset() + delta);
    }

    private static List<Trivia> shiftTrivia(List<Trivia> trivia, int delta, int editEnd) {
        if (trivia == null || trivia.isEmpty() || delta == 0) {
            return trivia == null
                   ? List.of()
                   : trivia;
        }
        var out = new ArrayList<Trivia>(trivia.size());
        boolean anyShift = false;
        for (var t : trivia) {
            if (t.span()
                 .startOffset() >= editEnd) {
                out.add(shiftTriviaOne(t, delta));
                anyShift = true;
            }else {
                out.add(t);
            }
        }
        return anyShift
               ? List.copyOf(out)
               : trivia;
    }

    private static List<Trivia> shiftTriviaAll(List<Trivia> trivia, int delta) {
        if (trivia == null || trivia.isEmpty() || delta == 0) {
            return trivia == null
                   ? List.of()
                   : trivia;
        }
        var out = new ArrayList<Trivia>(trivia.size());
        for (var t : trivia) {
            out.add(shiftTriviaOne(t, delta));
        }
        return List.copyOf(out);
    }

    private static Trivia shiftTriviaOne(Trivia trivia, int delta) {
        var span = shiftSpan(trivia.span(), delta);
        return switch (trivia) {
            case Trivia.Whitespace w -> new Trivia.Whitespace(span, w.text());
            case Trivia.LineComment l -> new Trivia.LineComment(span, l.text());
            case Trivia.BlockComment b -> new Trivia.BlockComment(span, b.text());
        };
    }
}
