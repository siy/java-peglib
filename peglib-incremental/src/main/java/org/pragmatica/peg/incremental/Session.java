package org.pragmatica.peg.incremental;

import org.pragmatica.lang.Option;
import org.pragmatica.peg.incremental.internal.NodeIndex;
import org.pragmatica.peg.tree.CstNode;

/**
 * Immutable view over a parsed buffer.
 *
 * <p>0.5.0 (Lever D, SPEC §5): cursor state has been split out of
 * {@code Session} into the standalone {@link Cursor} record. The Session
 * holds {@link #text()}, {@link #root()}, {@link #index()}, and {@link #stats()};
 * the Cursor holds the editor pointer ({@code offset} + stable
 * {@code enclosingNodeId}). State transitions return paired
 * {@code (Session, Cursor)} outcomes — {@link EditOutcome} from
 * {@link #edit(Cursor, Edit)}, {@link ReparseOutcome} from
 * {@link #reparseAll(Cursor)}.
 *
 * <p>Cursor moves no longer allocate a Session. A {@link Cursor} can outlive
 * the Session it was created with (Phase 1's stable IDs survive incremental
 * splices for ancestors that aren't wholesale-replaced).
 *
 * <p>Two sessions produced from the same lineage share structure through
 * untouched {@link CstNode} references; the splice only reallocates the
 * subtree that reparsed plus its ancestor spine — SPEC §4.2.
 *
 * <p>Sessions are not thread-safe for concurrent <em>reads and writes</em>
 * against the same instance. Concurrent read-only access ({@link #root()},
 * {@link #text()}, {@link #stats()}, {@link #index()}) is safe.
 *
 * @since 0.3.1
 */
public interface Session {
    /** Current CST (v1: CST-only; v3+ may expose AST via action replay). */
    CstNode root();

    /** Current buffer text. */
    String text();

    /** Per-session diagnostic counters. */
    Stats stats();

    /**
     * The index over {@link #root()} used by {@link Cursor} for boundary
     * resolution and by the engine for pivot lookup.
     *
     * <p>0.5.0 (Lever D): exposed as a public surface because {@link Cursor}
     * needs an index to anchor an offset to an enclosing-node id, and callers
     * need access to that index when constructing or moving a Cursor that
     * outlives the session it was minted in.
     */
    NodeIndex index();

    /**
     * Apply an edit. Returns a new {@link EditOutcome} carrying the post-edit
     * Session and a Cursor whose offset has been shifted per SPEC §5.5
     * (before the edit → unchanged; inside the edit region → snapped to end
     * of replacement; after the edit → shifted by {@link Edit#delta()}).
     *
     * <p>No-op edits ({@code oldLen == 0 && newText.isEmpty()}) return an
     * EditOutcome whose {@code newSession} is identical to {@code this} and
     * whose {@code newCursor} is the supplied cursor (SPEC §4.4 "Bounded
     * reparse on no-op").
     *
     * @param cursor the cursor before the edit; used as the warm pointer for
     *               boundary detection and as the source of the post-edit
     *               cursor offset.
     * @param edit   the edit to apply.
     * @throws IllegalArgumentException when the edit's offset or oldLen
     *         falls outside the current buffer, or when {@code edit} is
     *         {@code null}.
     */
    EditOutcome edit(Cursor cursor, Edit edit);

    /** Convenience: {@code edit(cursor, new Edit(offset, oldLen, newText))}. */
    default EditOutcome edit(Cursor cursor, int offset, int oldLen, String newText) {
        return edit(cursor, new Edit(offset, oldLen, newText));
    }

    /**
     * Discard the current CST and packrat cache, parse {@link #text()}
     * afresh. Diagnostic escape hatch + explicit recovery path after any
     * suspected incremental divergence.
     *
     * <p>Returns a new {@link ReparseOutcome} whose
     * {@code newSession.stats().fullReparseCount} is incremented by one.
     * The cursor's {@link Cursor#offset()} is preserved; its
     * {@link Cursor#enclosingNodeId} is re-resolved against the freshly built
     * {@link NodeIndex}.
     */
    ReparseOutcome reparseAll(Cursor cursor);

    /**
     * 0.5.0 (Path A) — {@code true} when the most recent full parse (or the
     * splice-and-validate of an incremental reparse) produced a structurally
     * valid CST; {@code false} when {@link #edit(Cursor, Edit)} or
     * {@link #reparseAll(Cursor)} fell through to the degraded-Session
     * synthesis path because the backing parser rejected the buffer.
     *
     * <p>Default: {@code true}. The package-private degraded session produced
     * by {@code SessionFactory} on parse failure overrides this to surface the
     * failure without resorting to exceptional control flow.
     */
    default boolean parseSuccessful() {
        return true;
    }

    /**
     * 0.5.0 (Path A) — when {@link #parseSuccessful()} is {@code false}, the
     * {@link SessionError} returned by the most recent full parse, expressed
     * via its {@link SessionError#message()}. Empty otherwise.
     */
    default Option<String> lastParseError() {
        return Option.none();
    }
}
