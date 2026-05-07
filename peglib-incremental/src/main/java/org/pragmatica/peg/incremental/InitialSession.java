package org.pragmatica.peg.incremental;

/**
 * Result of {@link IncrementalParser#initialize(String, int)} — the freshly
 * built {@link Session} paired with an initial {@link Cursor}.
 *
 * <p>0.5.0 (Lever D): {@link Session} no longer carries cursor state; the
 * factory therefore returns both objects so the caller wires them up in a
 * single statement. Subsequent state transitions go through
 * {@link EditOutcome} (from {@link Session#edit(Cursor, Edit)}) and
 * {@link ReparseOutcome} (from {@link Session#reparseAll(Cursor)}).
 *
 * @param session the initial Session over the supplied buffer (never
 *                {@code null}; may be a degraded Session if the parser
 *                rejected the buffer — inspect
 *                {@link Session#parseSuccessful()})
 * @param cursor  the initial Cursor at {@code cursorOffset}, anchored against
 *                {@code session.index()} (never {@code null})
 *
 * @since 0.5.0
 */
public record InitialSession(Session session, Cursor cursor) {}
