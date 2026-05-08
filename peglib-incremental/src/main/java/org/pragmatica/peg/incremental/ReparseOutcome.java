package org.pragmatica.peg.incremental;

/**
 * Result of {@link Session#reparseAll(Cursor)} — a paired
 * {@code (Session, Cursor)} mirroring {@link EditOutcome}'s shape so callers
 * can pattern-match either kind of state transition uniformly.
 *
 * <p>The cursor is preserved at its incoming offset and re-resolved against
 * the freshly built {@link org.pragmatica.peg.incremental.internal.NodeIndex},
 * since reparseAll discards the previous CST.
 *
 * @param newSession the post-reparse Session (never {@code null})
 * @param newCursor  the post-reparse Cursor, anchored against
 *                   {@code newSession.index()} (never {@code null})
 *
 * @since 0.5.0
 */
public record ReparseOutcome(Session newSession, Cursor newCursor) {}
