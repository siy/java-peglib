package org.pragmatica.peg.incremental;

/**
 * A single splice over a {@link Session}'s buffer: replace the substring
 * {@code text[offset, offset + oldLen)} with {@code newText}.
 *
 * <p>Insertions use {@code oldLen == 0}; pure deletions use
 * {@code newText.isEmpty()}. A no-op edit ({@code oldLen == 0 &&
 * newText.isEmpty()}) is legal and short-circuits inside
 * {@link Session#edit(Edit)} without triggering a reparse
 * (SPEC §4.4 "Bounded reparse on no-op" invariant).
 *
 * @param offset  absolute offset in the current buffer where the replacement
 *                begins; must satisfy {@code 0 <= offset <= text.length()}
 * @param oldLen  number of characters to remove starting at {@code offset}
 * @param newText replacement text; must be non-null (use {@code ""} for pure
 *                deletions)
 * @since 0.3.1
 */
public record Edit(int offset, int oldLen, String newText) {
    /**
     * Construct an {@code Edit}, validating basic shape. Further validation
     * (that the edit fits the current buffer) happens inside
     * {@link Session#edit(Edit)}.
     *
     * @throws IllegalArgumentException when {@code offset < 0},
     *                                  {@code oldLen < 0}, or {@code newText}
     *                                  is {@code null}.
     */
    public Edit {
        if (offset < 0) {
            throw new IllegalArgumentException("offset must be >= 0, got " + offset);
        }
        if (oldLen < 0) {
            throw new IllegalArgumentException("oldLen must be >= 0, got " + oldLen);
        }
        if (newText == null) {
            throw new IllegalArgumentException("newText must not be null");
        }
    }

    /** Length of the replacement text. */
    public int newLen() {
        return newText.length();
    }

    /** Signed length delta applied to the buffer by this edit. */
    public int delta() {
        return newLen() - oldLen;
    }

    /**
     * {@code true} iff this edit neither removes nor inserts any character.
     * No-op edits never trigger a reparse.
     */
    public boolean isNoOp() {
        return oldLen == 0 && newText.isEmpty();
    }
}
