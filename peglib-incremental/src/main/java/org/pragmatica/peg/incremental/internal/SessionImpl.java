package org.pragmatica.peg.incremental.internal;

import org.pragmatica.lang.Option;
import org.pragmatica.peg.incremental.Edit;
import org.pragmatica.peg.incremental.Session;
import org.pragmatica.peg.incremental.Stats;
import org.pragmatica.peg.tree.CstNode;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Package-private {@link Session} implementation carrying the SPEC §5.1
 * state: text, root, cursor, enclosing-node pointer, and stats.
 *
 * <p>The reparse-boundary algorithm (SPEC §5.3) is implemented inline in
 * {@link #edit(Edit)}; it walks outward from the cached enclosing-node
 * pointer until a containing rule node is found, invokes
 * {@code parseRuleAt} at that boundary, and splices the result back in
 * through {@link TreeSplicer}. On any failure mode the algorithm falls
 * through to a full reparse.
 *
 * <p>v1 cache-invalidation decision: every edit runs against a fresh
 * {@code Parser}-internal packrat cache (the {@code parseRuleAt} call
 * allocates a new {@code ParsingContext}). No cross-edit cache persistence.
 * SPEC §5.4 v1 choice.
 *
 * @since 0.3.1
 */
final class SessionImpl implements Session {
    private final SessionFactory factory;
    private final String text;
    private final CstNode root;
    private final int cursor;
    private final CstNode enclosingNode;
    private final NodeIndex index;
    private final Stats stats;

    private SessionImpl(SessionFactory factory,
                        String text,
                        CstNode root,
                        int cursor,
                        CstNode enclosingNode,
                        NodeIndex index,
                        Stats stats) {
        this.factory = factory;
        this.text = text;
        this.root = root;
        this.cursor = cursor;
        this.enclosingNode = enclosingNode;
        this.index = index;
        this.stats = stats;
    }

    /** Build the initial session after a fresh full parse. */
    static SessionImpl initial(SessionFactory factory, String text, int cursor, CstNode root) {
        var index = NodeIndex.build(root);
        var enclosing = index.smallestContaining(cursor).or(root);
        return new SessionImpl(factory, text, root, cursor, enclosing,
            index, Stats.INITIAL);
    }

    @Override public CstNode root() { return root; }
    @Override public String text() { return text; }
    @Override public int cursor() { return cursor; }
    @Override public Stats stats() { return stats; }

    @Override
    public Session edit(Edit edit) {
        if (edit == null) {
            throw new IllegalArgumentException("edit must not be null");
        }
        if (edit.offset() > text.length()) {
            throw new IllegalArgumentException(
                "edit.offset " + edit.offset() + " exceeds text length " + text.length());
        }
        if (edit.offset() + edit.oldLen() > text.length()) {
            throw new IllegalArgumentException(
                "edit range [" + edit.offset() + ", " + (edit.offset() + edit.oldLen())
                + ") exceeds text length " + text.length());
        }
        if (edit.isNoOp()) {
            return this;
        }
        long t0 = System.nanoTime();
        var newText = applyEdit(edit);
        int newCursor = shiftCursor(cursor, edit);

        // 0.3.2 v2: trivia-only fast-path. Edits whose range is entirely
        // contained in a single trivia run (whitespace or comment body) and
        // whose replacement remains legal trivia content are handled without
        // invoking the parser at all. The fast-path is gated by a per-factory
        // {@code triviaFastPathEnabled} flag because the path is only safe
        // for grammars where in-trivia edits cannot change tokenisation
        // decisions of adjacent tokens (e.g. simple PEG grammars; not the
        // Java grammar where {@code >>} vs {@code > >} parse differently).
        // See TriviaRedistribution for the SPEC §5.4 v2 design notes and the
        // v2.5 follow-up that aims to make this safe for all grammars.
        if (factory.triviaFastPathEnabled()) {
            var triviaRoot = TriviaRedistribution.tryTriviaOnlyEdit(root, newText, edit);
            if (triviaRoot != null) {
                var nextStats = new Stats(
                    stats.reparseCount() + 1,
                    stats.fullReparseCount(),
                    "<trivia>",
                    0,
                    System.nanoTime() - t0);
                var nextIndex = NodeIndex.build(triviaRoot);
                var nextEnclosing = nextIndex.smallestContaining(newCursor).or(triviaRoot);
                return new SessionImpl(factory, newText, triviaRoot, newCursor,
                    nextEnclosing, nextIndex, nextStats);
            }
        }

        // Try incremental reparse next.
        var incremental = tryIncrementalReparse(newText, edit);
        if (incremental != null) {
            // v2: trivia-aware splice normalization (currently a no-op for the
            // leading-trivia direction since parseRuleAt already attaches
            // trivia per 0.2.4 attribution; the seam exists for v2.5+).
            var normalized = TriviaRedistribution.normalizeSplicedTrivia(
                incremental.newRoot, incremental.spliced);
            var nextStats = new Stats(
                stats.reparseCount() + 1,
                stats.fullReparseCount(),
                incremental.ruleName,
                NodeIndex.flatten(incremental.spliced).size(),
                System.nanoTime() - t0);
            var nextIndex = NodeIndex.build(normalized);
            var nextEnclosing = nextIndex.smallestContaining(newCursor).or(normalized);
            return new SessionImpl(factory, newText, normalized, newCursor,
                nextEnclosing, nextIndex, nextStats);
        }
        // Fall back to a full reparse.
        return fallback(newText, newCursor, t0);
    }

    @Override
    public Session moveCursor(int newOffset) {
        int clamped = Math.max(0, Math.min(newOffset, text.length()));
        if (clamped == cursor) {
            return this;
        }
        var newEnclosing = index.smallestContainingFrom(enclosingNode, clamped).or(root);
        return new SessionImpl(factory, text, root, clamped,
            newEnclosing, index, stats);
    }

    @Override
    public Session reparseAll() {
        long t0 = System.nanoTime();
        var fresh = factory.parseFull(text);
        var freshIndex = NodeIndex.build(fresh);
        var enclosing = freshIndex.smallestContaining(cursor).or(fresh);
        var nextStats = new Stats(
            stats.reparseCount() + 1,
            stats.fullReparseCount() + 1,
            "<root>",
            NodeIndex.flatten(fresh).size(),
            System.nanoTime() - t0);
        return new SessionImpl(factory, text, fresh, cursor,
            enclosing, freshIndex, nextStats);
    }

    private Session fallback(String newText, int newCursor, long t0) {
        var fresh = factory.parseFull(newText);
        var freshIndex = NodeIndex.build(fresh);
        var enclosing = freshIndex.smallestContaining(newCursor).or(fresh);
        var nextStats = new Stats(
            stats.reparseCount() + 1,
            stats.fullReparseCount() + 1,
            "<root>",
            NodeIndex.flatten(fresh).size(),
            System.nanoTime() - t0);
        return new SessionImpl(factory, newText, fresh, newCursor,
            enclosing, freshIndex, nextStats);
    }

    /**
     * Attempt the incremental reparse per SPEC §5.3. Returns {@code null}
     * when the edit should fall back to a full reparse.
     *
     * <p>Walks outward from the enclosing-node pointer to the smallest
     * {@code NonTerminal} ancestor whose span fully contains the edit
     * region, then calls {@code parseRuleAt} on that ancestor's rule. If
     * the partial parse succeeds <em>and</em> ends exactly at the ancestor's
     * expected new-end offset ({@code oldEnd + delta}), the result is
     * spliced in. Otherwise we walk one more level up and retry; reaching
     * the root aborts to full reparse.
     *
     * <p>Any ancestor whose rule is listed in {@link SessionFactory#fallbackRules()}
     * short-circuits to full reparse.
     */
    private IncrementalResult tryIncrementalReparse(String newText, Edit edit) {
        int editStart = edit.offset();
        int editEnd = edit.offset() + edit.oldLen();
        int delta = edit.delta();

        // Find the smallest NonTerminal containing [editStart, editEnd] in the pre-edit buffer.
        var pivot = findBoundaryCandidate(editStart, editEnd);
        while (pivot != null) {
            if (!(pivot instanceof CstNode.NonTerminal nt)) {
                pivot = index.parentOf(pivot);
                continue;
            }
            if (factory.fallbackRules().contains(nt.rule())) {
                return null;
            }
            var reparsed = reparseAt(nt, newText, delta);
            if (reparsed.isPresent()) {
                var reparsedNode = reparsed.unwrap();
                var path = index.pathTo(nt);
                if (path.isEmpty()) {
                    // pivot == root — reparsed subtree replaces root wholesale.
                    return new IncrementalResult(reparsedNode, reparsedNode, nt.rule());
                }
                var newRoot = TreeSplicer.spliceAndShift(path, nt, reparsedNode, editEnd, delta);
                return new IncrementalResult(newRoot, reparsedNode, nt.rule());
            }
            pivot = index.parentOf(nt);
        }
        return null;
    }

    /**
     * Walk outward from the enclosing-node pointer to the smallest node
     * whose span fully contains {@code [editStart, editEnd]} in the pre-edit
     * buffer. Returns the pivot; {@code null} when the edit exceeds the root
     * (caller falls back to full reparse).
     */
    private CstNode findBoundaryCandidate(int editStart, int editEnd) {
        CstNode cursorNode = enclosingNode;
        if (cursorNode == null) {
            cursorNode = root;
        }
        // Walk up until the node's span encloses the entire edit region.
        while (cursorNode != null) {
            int spanStart = cursorNode.span().start().offset();
            int spanEnd = cursorNode.span().end().offset();
            if (spanStart <= editStart && spanEnd >= editEnd) {
                return cursorNode;
            }
            cursorNode = index.parentOf(cursorNode);
        }
        // Root didn't contain the edit (e.g., append past EOF): pivot at root itself.
        return root;
    }

    private Option<CstNode> reparseAt(CstNode.NonTerminal nt, String newText, int delta) {
        int startOffset = nt.span().start().offset();
        int expectedEnd = nt.span().end().offset() + delta;
        var ruleId = factory.registry().classFor(nt.rule());
        var partial = factory.parser().parseRuleAt(ruleId, newText, startOffset);
        return partial.option()
                      .filter(p -> p.node().span().end().offset() == expectedEnd)
                      .map(p -> p.node());
    }

    /**
     * Apply the edit to {@link #text} and return the new buffer. Uses
     * {@link StringBuilder} to keep the allocation count explicit.
     */
    private String applyEdit(Edit edit) {
        var sb = new StringBuilder(text.length() + edit.delta());
        sb.append(text, 0, edit.offset());
        sb.append(edit.newText());
        sb.append(text, edit.offset() + edit.oldLen(), text.length());
        return sb.toString();
    }

    /**
     * Shift the cursor per SPEC §5.5: before the edit → unchanged; inside
     * → snap to end of replacement; after → shift by delta.
     */
    private static int shiftCursor(int oldCursor, Edit edit) {
        if (oldCursor < edit.offset()) {
            return oldCursor;
        }
        if (oldCursor < edit.offset() + edit.oldLen()) {
            return edit.offset() + edit.newLen();
        }
        return oldCursor + edit.delta();
    }

    /** Result of a successful incremental reparse: the new root + pivot. */
    private record IncrementalResult(CstNode newRoot, CstNode spliced, String ruleName) {}

    /**
     * Bootstrap entry exposed for {@link SessionFactory#initialize(String, int)}.
     * Non-static for symmetry with the implementation path that produces
     * {@link #index} inline.
     */
    static Deque<CstNode> debugPathTo(SessionImpl session, CstNode node) {
        return session.index.pathTo(node) == null ? new ArrayDeque<>() : session.index.pathTo(node);
    }
}
