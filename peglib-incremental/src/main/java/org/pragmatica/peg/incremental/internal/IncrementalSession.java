package org.pragmatica.peg.incremental.internal;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.peg.incremental.Cursor;
import org.pragmatica.peg.incremental.Edit;
import org.pragmatica.peg.incremental.EditOutcome;
import org.pragmatica.peg.incremental.ReparseOutcome;
import org.pragmatica.peg.incremental.Session;
import org.pragmatica.peg.incremental.SessionError;
import org.pragmatica.peg.incremental.Stats;
import org.pragmatica.peg.parser.PegEngine;
import org.pragmatica.peg.tree.CstNode;
import org.pragmatica.peg.tree.IdGenerator;
import org.pragmatica.peg.tree.SourceLocation;
import org.pragmatica.peg.tree.SourceSpan;

import java.util.List;

/**
 * Package-private {@link Session} implementation.
 *
 * <p>0.5.0 (Lever D): cursor state ({@code offset}, {@code enclosingNode}) was
 * split out of this record into the public {@link Cursor} type. The Session
 * now carries only buffer state ({@code text}, {@code root}, {@code index},
 * {@code idGen}, {@code stats}, {@code lastError}) plus its owning
 * {@link SessionFactory}.
 *
 * <p>The reparse-boundary algorithm (SPEC §5.3) walks outward from the cursor's
 * {@link Cursor#enclosingNodeId}-resolved warm pointer until a containing rule
 * node is found, invokes {@code parseRuleAt} at that boundary, and splices the
 * result back in through {@link TreeSplicer}. On any failure mode the
 * algorithm falls through to a full reparse.
 *
 * @since 0.3.1
 */
record IncrementalSession(
 SessionFactory factory,
 String text,
 CstNode root,
 NodeIndex index,
 IdGenerator idGen,
 Stats stats,
 Option<SessionError> lastError) implements Session {
    /** Build the initial session after a fresh full parse. */
    static IncrementalSession initial(SessionFactory factory,
                                      String text,
                                      CstNode root,
                                      IdGenerator idGen) {
        var index = NodeIndex.build(root);
        return new IncrementalSession(factory, text, root, index, idGen, Stats.INITIAL, Option.none());
    }

    /**
     * 0.5.0 (Path A) — synthesise a degraded initial session for the case
     * where {@link SessionFactory#parseFull(String, IdGenerator)} fails on
     * {@link SessionFactory#initialize(String, int)}. The resulting session
     * has a single {@link CstNode.Error} root carrying the rejected buffer
     * and an {@link Option#some(Object) Option.some(error)} for
     * {@link Session#lastParseError()}.
     */
    static IncrementalSession degradedInitial(SessionFactory factory,
                                              String text,
                                              IdGenerator idGen,
                                              SessionError error) {
        var root = degradedRoot(text, idGen, error);
        var index = NodeIndex.build(root);
        return new IncrementalSession(factory, text, root, index, idGen, Stats.INITIAL, Option.some(error));
    }

    /**
     * Build the single-{@link CstNode.Error}-root that represents a degraded
     * Session per Path A.
     */
    private static CstNode degradedRoot(String text, IdGenerator idGen, SessionError error) {
        var start = SourceLocation.START;
        var end = SourceLocation.sourceLocation(1, 1 + text.length(), text.length());
        var span = SourceSpan.sourceSpan(start, end);
        return new CstNode.Error(idGen.next(), span, text, error.message(), List.of(), List.of());
    }

    @Override
    public boolean parseSuccessful() {
        return lastError.isEmpty();
    }

    @Override
    public Option<String> lastParseError() {
        return lastError.map(SessionError::message);
    }

    @Override
    public EditOutcome edit(Cursor cursor, Edit edit) {
        if (cursor == null) {
            throw new IllegalArgumentException("cursor must not be null");
        }
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
            return new EditOutcome(this, cursor);
        }
        long t0 = System.nanoTime();
        var newText = applyEdit(edit);
        int newCursorOffset = shiftCursor(cursor.offset(), edit);
        // 0.3.2 v2: trivia-only fast-path. See TriviaRedistribution for the
        // SPEC §5.4 v2 design notes.
        if (factory.triviaFastPathEnabled()) {
            var triviaRoot = TriviaRedistribution.tryTriviaOnlyEdit(root, newText, edit);
            if (triviaRoot != null) {
                var nextStats = new Stats(
                stats.reparseCount() + 1, stats.fullReparseCount(), "<trivia>", 0, System.nanoTime() - t0);
                var nextIndex = NodeIndex.build(triviaRoot);
                var nextSession = new IncrementalSession(factory,
                                                         newText,
                                                         triviaRoot,
                                                         nextIndex,
                                                         idGen,
                                                         nextStats,
                                                         Option.none());
                return new EditOutcome(nextSession, Cursor.at(newCursorOffset, nextIndex));
            }
        }
        // Try incremental reparse next.
        var incremental = tryIncrementalReparse(cursor, newText, edit);
        if (incremental.isPresent()) {
            return applyIncremental(incremental.unwrap(), newText, newCursorOffset, t0);
        }
        // Fall back to a full reparse. 0.5.0 (Path A): on parse failure,
        // synthesise a degraded Session — never throw.
        return fallback(newText, newCursorOffset, t0)
        .fold(cause -> degradedFallback(newText, newCursorOffset, t0, (SessionError) cause),
              outcome -> outcome);
    }

    @Override
    public ReparseOutcome reparseAll(Cursor cursor) {
        if (cursor == null) {
            throw new IllegalArgumentException("cursor must not be null");
        }
        long t0 = System.nanoTime();
        // 0.5.0 (Path A): reparseAll is the diagnostic escape hatch.
        return factory.parseFull(text, idGen)
                      .fold(cause -> degradedReparseAll(cursor, t0, (SessionError) cause),
                            fresh -> reparseAllSuccess(fresh, cursor, t0));
    }

    private ReparseOutcome reparseAllSuccess(CstNode fresh, Cursor cursor, long t0) {
        var freshIndex = NodeIndex.build(fresh);
        var nextStats = new Stats(
        stats.reparseCount() + 1,
        stats.fullReparseCount() + 1,
        "<root>",
        NodeIndex.flatten(fresh)
                 .size(),
        System.nanoTime() - t0);
        var nextSession = new IncrementalSession(factory, text, fresh, freshIndex, idGen, nextStats, Option.none());
        return new ReparseOutcome(nextSession, Cursor.at(cursor.offset(), freshIndex));
    }

    private ReparseOutcome degradedReparseAll(Cursor cursor, long t0, SessionError error) {
        var fresh = degradedRoot(text, idGen, error);
        var freshIndex = NodeIndex.build(fresh);
        var nextStats = new Stats(
        stats.reparseCount() + 1,
        stats.fullReparseCount() + 1,
        "<root>",
        NodeIndex.flatten(fresh)
                 .size(),
        System.nanoTime() - t0);
        var nextSession = new IncrementalSession(factory,
                                                 text,
                                                 fresh,
                                                 freshIndex,
                                                 idGen,
                                                 nextStats,
                                                 Option.some(error));
        return new ReparseOutcome(nextSession, Cursor.at(cursor.offset(), freshIndex));
    }

    /**
     * 0.5.0 — full-reparse fallback now returns {@code Result<EditOutcome>}.
     */
    private Result<EditOutcome> fallback(String newText, int newCursorOffset, long t0) {
        return factory.parseFull(newText, idGen)
                      .map(fresh -> fallbackSuccess(fresh, newText, newCursorOffset, t0));
    }

    private EditOutcome fallbackSuccess(CstNode fresh, String newText, int newCursorOffset, long t0) {
        var freshIndex = NodeIndex.build(fresh);
        var nextStats = new Stats(
        stats.reparseCount() + 1,
        stats.fullReparseCount() + 1,
        "<root>",
        NodeIndex.flatten(fresh)
                 .size(),
        System.nanoTime() - t0);
        var nextSession = new IncrementalSession(factory,
                                                 newText,
                                                 fresh,
                                                 freshIndex,
                                                 idGen,
                                                 nextStats,
                                                 Option.none());
        return new EditOutcome(nextSession, Cursor.at(newCursorOffset, freshIndex));
    }

    /**
     * 0.5.0 (Path A) — synthesise the degraded Session for the {@code edit}
     * fallback path.
     */
    private EditOutcome degradedFallback(String newText, int newCursorOffset, long t0, SessionError error) {
        var fresh = degradedRoot(newText, idGen, error);
        var freshIndex = NodeIndex.build(fresh);
        var nextStats = new Stats(
        stats.reparseCount() + 1,
        stats.fullReparseCount() + 1,
        "<root>",
        NodeIndex.flatten(fresh)
                 .size(),
        System.nanoTime() - t0);
        var nextSession = new IncrementalSession(factory,
                                                 newText,
                                                 fresh,
                                                 freshIndex,
                                                 idGen,
                                                 nextStats,
                                                 Option.some(error));
        return new EditOutcome(nextSession, Cursor.at(newCursorOffset, freshIndex));
    }

    /**
     * Apply a successful incremental reparse: normalise trivia, update the
     * NodeIndex, and return the next {@link EditOutcome}.
     *
     * <p>Phase 1.6 (v0.5.0): Path D — calls
     * {@link NodeIndex#applyIncremental(CstNode, java.util.List, java.util.List)}
     * instead of {@link NodeIndex#build(CstNode)}.
     */
    private EditOutcome applyIncremental(IncrementalResult incremental, String newText, int newCursorOffset, long t0) {
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
            nextIndex = index.applyIncremental(normalized, incremental.oldPath, incremental.newPath);
        }else {
            nextIndex = NodeIndex.build(normalized);
        }
        var nextSession = new IncrementalSession(factory,
                                                 newText,
                                                 normalized,
                                                 nextIndex,
                                                 idGen,
                                                 nextStats,
                                                 Option.none());
        return new EditOutcome(nextSession, Cursor.at(newCursorOffset, nextIndex));
    }

    /**
     * Attempt the incremental reparse per SPEC §5.3. Returns {@code Option.none()}
     * when the edit should fall back to a full reparse.
     */
    private Option<IncrementalResult> tryIncrementalReparse(Cursor cursor, String newText, Edit edit) {
        int editStart = edit.offset();
        int editEnd = edit.offset() + edit.oldLen();
        int delta = edit.delta();
        var current = Option.some(findBoundaryCandidate(cursor, editStart, editEnd));
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
            return new IncrementalResult(reparsedNode, reparsedNode, nt.rule(), null, null);
        }
        var oldPath = List.copyOf(path);
        var splice = TreeSplicer.spliceAndShift(path, nt, reparsedNode, editEnd, delta);
        return new IncrementalResult(splice.newRoot(), reparsedNode, nt.rule(), oldPath, splice.newPath());
    }

    /**
     * Walk outward from the cursor's enclosing-node pointer to the smallest
     * node whose span fully contains {@code [editStart, editEnd]} in the
     * pre-edit buffer. Falls back to {@link #root} when:
     * <ul>
     *   <li>the cursor's {@code enclosingNodeId} can't be resolved against
     *       the current index (cursor was minted against a different
     *       lineage, or its enclosing was wholesale-replaced);</li>
     *   <li>the edit exceeds the root span (e.g., append past EOF).</li>
     * </ul>
     */
    private CstNode findBoundaryCandidate(Cursor cursor, int editStart, int editEnd) {
        var current = index.nodeById(cursor.enclosingNodeId())
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
        var engine = (PegEngine) factory.parser();
        var partial = engine.parseRuleAt(ruleId, newText, startOffset, idGen);
        return partial.option()
                      .filter(p -> p.node()
                                    .span()
                                    .end()
                                    .offset() == expectedEnd)
                      .map(p -> p.node());
    }

    /** Apply the edit to {@link #text} and return the new buffer. */
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
