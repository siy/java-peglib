package org.pragmatica.peg.incremental.internal;

import org.pragmatica.lang.Option;
import org.pragmatica.peg.incremental.Edit;
import org.pragmatica.peg.incremental.Session;
import org.pragmatica.peg.incremental.Stats;
import org.pragmatica.peg.parser.PegEngine;
import org.pragmatica.peg.tree.CstNode;
import org.pragmatica.peg.tree.IdGenerator;

import java.util.List;

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
 * <p>0.4.0 — converted from {@code SessionImpl} class to a {@code record} to
 * remove the {@code Impl} anti-pattern. The seven components are internal
 * implementation state; callers continue to consume the {@link Session}
 * interface, which keeps the public surface narrow.
 *
 * @since 0.3.1
 */
record IncrementalSession(
 SessionFactory factory,
 String text,
 CstNode root,
 int cursor,
 CstNode enclosingNode,
 NodeIndex index,
 IdGenerator idGen,
 Stats stats) implements Session {
    /** Build the initial session after a fresh full parse. */
    static IncrementalSession initial(SessionFactory factory,
                                      String text,
                                      int cursor,
                                      CstNode root,
                                      IdGenerator idGen) {
        var index = NodeIndex.build(root);
        var enclosing = index.smallestContaining(cursor)
                             .or(root);
        return new IncrementalSession(factory, text, root, cursor, enclosing, index, idGen, Stats.INITIAL);
    }

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
            "edit range [" + edit.offset() + ", " + (edit.offset() + edit.oldLen()) + ") exceeds text length " + text.length());
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
                stats.reparseCount() + 1, stats.fullReparseCount(), "<trivia>", 0, System.nanoTime() - t0);
                var nextIndex = NodeIndex.build(triviaRoot);
                var nextEnclosing = nextIndex.smallestContaining(newCursor)
                                             .or(triviaRoot);
                return new IncrementalSession(factory,
                                              newText,
                                              triviaRoot,
                                              newCursor,
                                              nextEnclosing,
                                              nextIndex,
                                              idGen,
                                              nextStats);
            }
        }
        // Try incremental reparse next.
        var incremental = tryIncrementalReparse(newText, edit);
        if (incremental.isPresent()) {
            return applyIncremental(incremental.unwrap(), newText, newCursor, t0);
        }
        // Fall back to a full reparse.
        return fallback(newText, newCursor, t0);
    }

    @Override
    public Session moveCursor(int newOffset) {
        int clamped = Math.max(0,
                               Math.min(newOffset, text.length()));
        if (clamped == cursor) {
            return this;
        }
        var newEnclosing = index.smallestContainingFrom(enclosingNode, clamped)
                                .or(root);
        return new IncrementalSession(factory, text, root, clamped, newEnclosing, index, idGen, stats);
    }

    @Override
    public Session reparseAll() {
        long t0 = System.nanoTime();
        var fresh = factory.parseFull(text, idGen);
        var freshIndex = NodeIndex.build(fresh);
        var enclosing = freshIndex.smallestContaining(cursor)
                                  .or(fresh);
        var nextStats = new Stats(
        stats.reparseCount() + 1,
        stats.fullReparseCount() + 1,
        "<root>",
        NodeIndex.flatten(fresh)
                 .size(),
        System.nanoTime() - t0);
        return new IncrementalSession(factory, text, fresh, cursor, enclosing, freshIndex, idGen, nextStats);
    }

    private Session fallback(String newText, int newCursor, long t0) {
        var fresh = factory.parseFull(newText, idGen);
        var freshIndex = NodeIndex.build(fresh);
        var enclosing = freshIndex.smallestContaining(newCursor)
                                  .or(fresh);
        var nextStats = new Stats(
        stats.reparseCount() + 1,
        stats.fullReparseCount() + 1,
        "<root>",
        NodeIndex.flatten(fresh)
                 .size(),
        System.nanoTime() - t0);
        return new IncrementalSession(factory, newText, fresh, newCursor, enclosing, freshIndex, idGen, nextStats);
    }

    /**
     * Apply a successful incremental reparse: normalise trivia, update the
     * NodeIndex, and return the next session snapshot. Extracted so the
     * caller in {@link #edit(Edit)} stays a single Result-style branch.
     *
     * <p>Phase 1.6 (v0.5.0): Path D — calls
     * {@link NodeIndex#applyIncremental(CstNode, java.util.List, java.util.List)}
     * instead of {@link NodeIndex#build(CstNode)}. Cost drops from O(N) to
     * O(oldPivotSize + newPivotSize). The receiver index is invalidated; we
     * use only the returned instance.
     *
     * <p><strong>Trivia-redistribution caveat.</strong> When
     * {@link TriviaRedistribution#normalizeSplicedTrivia} mutates the spliced
     * subtree (currently a no-op for the leading-trivia direction; the seam
     * exists for v2.5+), the {@code newPath} we computed before normalisation
     * may carry stale record references for the pivot. We fall back to a full
     * {@link NodeIndex#build} on that path so structural sharing isn't broken.
     */
    private Session applyIncremental(IncrementalResult incremental, String newText, int newCursor, long t0) {
        var normalized = TriviaRedistribution.normalizeSplicedTrivia(
        incremental.newRoot, incremental.spliced);
        var nextStats = new Stats(
        stats.reparseCount() + 1,
        stats.fullReparseCount(),
        incremental.ruleName,
        NodeIndex.flatten(incremental.spliced)
                 .size(),
        System.nanoTime() - t0);
        NodeIndex nextIndex;
        if (normalized == incremental.newRoot && incremental.oldPath != null && incremental.newPath != null) {
            // Path D fast-path: trivia normalisation was a no-op (the common
            // case today) — apply the optimised O(oldPivotSize + newPivotSize)
            // index update.
            nextIndex = index.applyIncremental(normalized, incremental.oldPath, incremental.newPath);
        }else {
            // Trivia normalisation mutated the tree, OR the pivot was the root
            // (newPath == null path-was-root branch in buildIncrementalResult).
            // Fall back to a full rebuild — correct, just not Path-D-fast.
            nextIndex = NodeIndex.build(normalized);
        }
        var nextEnclosing = nextIndex.smallestContaining(newCursor)
                                     .or(normalized);
        return new IncrementalSession(factory, newText, normalized, newCursor, nextEnclosing, nextIndex, idGen, nextStats);
    }

    /**
     * Attempt the incremental reparse per SPEC §5.3. Returns {@code Option.none()}
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
    private Option<IncrementalResult> tryIncrementalReparse(String newText, Edit edit) {
        int editStart = edit.offset();
        int editEnd = edit.offset() + edit.oldLen();
        int delta = edit.delta();
        // Find the smallest NonTerminal containing [editStart, editEnd] in the pre-edit buffer.
        var current = Option.some(findBoundaryCandidate(editStart, editEnd));
        while (current.isPresent()) {
            var pivot = current.unwrap();
            if (! (pivot instanceof CstNode.NonTerminal nt)) {
                current = index.parentOf(pivot);
                continue;
            }
            if (factory.fallbackRules()
                       .contains(nt.rule())) {
                return Option.none();
            }
            var reparsed = reparseAt(nt, newText, delta);
            if (reparsed.isPresent()) {
                return Option.some(buildIncrementalResult(nt, reparsed.unwrap(), editEnd, delta));
            }
            current = index.parentOf(nt);
        }
        return Option.none();
    }

    private IncrementalResult buildIncrementalResult(CstNode.NonTerminal nt,
                                                     CstNode reparsedNode,
                                                     int editEnd,
                                                     int delta) {
        var path = index.pathTo(nt);
        if (path.isEmpty()) {
            // pivot == root — reparsed subtree replaces root wholesale. No
            // path information for Path D; the caller falls back to
            // NodeIndex.build via the null-newPath branch in applyIncremental.
            return new IncrementalResult(reparsedNode, reparsedNode, nt.rule(), null, null);
        }
        var oldPath = List.copyOf(path);
        var splice = TreeSplicer.spliceAndShift(path, nt, reparsedNode, editEnd, delta);
        return new IncrementalResult(splice.newRoot(), reparsedNode, nt.rule(), oldPath, splice.newPath());
    }

    /**
     * Walk outward from the enclosing-node pointer to the smallest node
     * whose span fully contains {@code [editStart, editEnd]} in the pre-edit
     * buffer. Falls back to {@link #root} when the edit exceeds the root
     * (e.g., append past EOF) so the caller always gets a non-null pivot.
     */
    private CstNode findBoundaryCandidate(int editStart, int editEnd) {
        // 0.4.0 — Option.option() defends against a (theoretically) null
        // {@code enclosingNode} record component; falls back to {@code root}
        // so the walk is well-defined.
        var current = Option.option(enclosingNode)
                            .orElse(Option.some(root));
        while (current.isPresent()) {
            var cursorNode = current.unwrap();
            int spanStart = cursorNode.span()
                                      .start()
                                      .offset();
            int spanEnd = cursorNode.span()
                                    .end()
                                    .offset();
            if (spanStart <= editStart && spanEnd >= editEnd) {
                return cursorNode;
            }
            current = index.parentOf(cursorNode);
        }
        // Root didn't contain the edit (e.g., append past EOF): pivot at root itself.
        return root;
    }

    private Option<CstNode> reparseAt(CstNode.NonTerminal nt, String newText, int delta) {
        int startOffset = nt.span()
                            .start()
                            .offset();
        int expectedEnd = nt.span()
                            .end()
                            .offset() + delta;
        var ruleId = factory.registry()
                            .classFor(nt.rule());
        // Phase 1.5 (v0.5.0): route through the id-aware overload so the
        // spliced subtree's CstNode ids come from THIS session's counter,
        // not a fresh per-call one. Path D's NodeIndex.applyIncremental
        // requires every node in the lineage to share the same id space.
        var engine = (PegEngine) factory.parser();
        var partial = engine.parseRuleAt(ruleId, newText, startOffset, idGen);
        return partial.option()
                      .filter(p -> p.node()
                                    .span()
                                    .end()
                                    .offset() == expectedEnd)
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
        sb.append(text,
                  edit.offset() + edit.oldLen(),
                  text.length());
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

    /**
     * Result of a successful incremental reparse: the new root + pivot, plus
     * the {@code oldPath} and {@code newPath} bookkeeping needed by Path D's
     * {@link NodeIndex#applyIncremental}. {@code oldPath} and {@code newPath}
     * are {@code null} when the pivot equals the root (no spine to splice).
     */
    private record IncrementalResult(CstNode newRoot,
                                     CstNode spliced,
                                     String ruleName,
                                     List<CstNode> oldPath,
                                     List<CstNode> newPath) {}
}
