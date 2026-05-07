package org.pragmatica.peg.incremental;

import org.pragmatica.lang.Option;
import org.pragmatica.peg.tree.CstNode;

/**
 * Immutable view over a parsed buffer at a specific cursor position.
 *
 * <p>Every {@link #edit(Edit)} / {@link #moveCursor(int)} / {@link #reparseAll()}
 * call returns a fresh {@code Session}; existing {@code Session} values are
 * never mutated. This makes {@code Session} references safe to retain for
 * undo stacks, diagnostic snapshots, and cross-thread reads.
 *
 * <p>Two sessions produced from the same lineage share structure through
 * untouched {@link CstNode} references (records are value objects; the
 * incremental splice only reallocates the subtree that reparsed plus its
 * ancestor spine — SPEC §4.2).
 *
 * <p>Sessions are not thread-safe for concurrent <em>reads and writes</em>
 * against the same instance — two concurrent {@code edit(...)} calls each
 * produce an independent new session, which is well-defined, but neither
 * instance observes the other's edit. Concurrent read-only access
 * ({@link #root()}, {@link #text()}, {@link #cursor()}, {@link #stats()})
 * is safe.
 *
 * @since 0.3.1
 */
public interface Session {
    /** Current CST (v1: CST-only; v3+ may expose AST via action replay). */
    CstNode root();

    /** Current buffer text. */
    String text();

    /**
     * Current cursor offset, in the range {@code [0, text().length()]}. The
     * cursor moves automatically with edits per SPEC §5.5: before the edit →
     * unchanged; inside the edit region → snapped to the end of the
     * replacement; after the edit → shifted by {@link Edit#delta()}.
     */
    int cursor();

    /** Per-session diagnostic counters. */
    Stats stats();

    /**
     * Apply an edit. Returns a new {@code Session}.
     *
     * <p>No-op edits ({@code oldLen == 0 && newText.isEmpty()}) return
     * {@code this} unchanged (SPEC §4.4 "Bounded reparse on no-op").
     *
     * @throws IllegalArgumentException when the edit's offset or oldLen
     *         falls outside the current buffer.
     */
    Session edit(Edit edit);

    /** Convenience: {@code edit(new Edit(offset, oldLen, newText))}. */
    default Session edit(int offset, int oldLen, String newText) {
        return edit(new Edit(offset, oldLen, newText));
    }

    /**
     * Move the cursor to {@code newOffset} without changing the buffer or
     * the tree. Clamps to {@code [0, text().length()]}.
     */
    Session moveCursor(int newOffset);

    /**
     * Discard the current CST and packrat cache, parse {@link #text()}
     * afresh. Diagnostic escape hatch + explicit recovery path after any
     * suspected incremental divergence.
     *
     * <p>Returns a new {@code Session} whose {@link Stats#fullReparseCount}
     * is incremented by one.
     */
    Session reparseAll();

    /**
     * 0.5.0 (Path A) — {@code true} when the most recent full parse (or the
     * splice-and-validate of an incremental reparse) produced a structurally
     * valid CST; {@code false} when {@link #edit(Edit)} or {@link #reparseAll()}
     * fell through to the degraded-Session synthesis path because the backing
     * parser rejected the buffer.
     *
     * <p>Default: {@code true}. The package-private degraded session produced
     * by {@code SessionFactory} on parse failure overrides this to surface the
     * failure without resorting to exceptional control flow.
     *
     * <p>Editor-style callers that need to distinguish "edit applied to a
     * valid buffer" from "edit applied to a buffer the grammar rejects"
     * inspect this flag (and {@link #lastParseError()} for the cause message).
     * Callers that don't care continue to use {@link #root()} and
     * {@link #text()} as before — the degraded session still has a non-null
     * root and text, just a single {@link CstNode.Error} root carrying the
     * rejected buffer.
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
