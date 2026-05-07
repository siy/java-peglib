package org.pragmatica.peg.incremental;

/**
 * Result of {@link Session#edit(Cursor, Edit)} — a paired
 * {@code (Session, Cursor)} so the caller can update both references in a
 * single assignment without forgetting one.
 *
 * <p>0.5.0 (Lever D): cursor state was split out of {@link Session}; this
 * record bundles the post-edit Session and the post-edit Cursor that arises
 * from the SPEC §5.5 cursor-shift rules (before edit → unchanged; inside edit
 * → snapped to end of replacement; after edit → shifted by
 * {@link Edit#delta()}).
 *
 * @param newSession the post-edit Session (never {@code null})
 * @param newCursor  the post-edit Cursor, anchored against
 *                   {@code newSession.index()} (never {@code null})
 *
 * @since 0.5.0
 */
public record EditOutcome(Session newSession, Cursor newCursor) {}
