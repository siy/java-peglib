package org.pragmatica.peg.incremental.experimental;
/**
 * Primitive-keyed, primitive-valued map ({@code long → long}) used as the
 * backing store for the v0.5.0 {@code NodeIndex} (per
 * {@code docs/incremental/ARCHITECTURE-0.5.0.md} §2 Lever A).
 *
 * <p>Open question Q2 in §8 is resolved: hand-rolled, no third-party
 * collections dependency. Eclipse Collections would add ~10 MB of jar weight
 * for a single data structure; boxed {@code Map<Long, Long>} eats too much
 * per-entry overhead and pressures the GC under tight per-edit budgets.
 *
 * <p><strong>Sentinel.</strong> {@link #MISSING} is returned by {@link #get}
 * for absent keys. Callers must not store {@code Long.MIN_VALUE} as a value;
 * the {@code NodeIndex} use case (mapping child-id → parent-id, where IDs
 * come from {@link IdGenerator.PerSessionCounter} and start at 0) honours
 * this naturally.
 *
 * <p><strong>Thread-safety.</strong> Implementations are not thread-safe.
 *
 * <p>TODO(0.5.x): consider swapping {@link LinearProbingLongLongMap} for a
 * funnel-hashing variant per Farach-Colton, Krapivin, Kuszmaul,
 * "Optimal Bounds for Open Addressing Without Reordering" (2025).
 *
 * @since 0.5.0
 */
public sealed interface LongLongMap permits LinearProbingLongLongMap {
    /**
     * Returned by {@link #get(long)} for keys that are not present in the
     * map. Callers must never use {@code Long.MIN_VALUE} as a stored value.
     */
    long MISSING = Long.MIN_VALUE;

    /**
     * Insert or overwrite the value for {@code key}. {@link #size()} grows
     * when {@code key} is new and stays the same on overwrite.
     */
    void put(long key, long value);

    /**
     * Value associated with {@code key}, or {@link #MISSING} when absent.
     */
    long get(long key);

    /** {@code true} iff {@code key} is present. */
    boolean containsKey(long key);

    /**
     * Remove the entry for {@code key} if present. No-op when absent.
     */
    void remove(long key);

    /** Number of live entries. */
    int size();

    /** Discard every entry. Capacity is preserved by the implementation. */
    void clear();

    /**
     * Independent deep copy. Subsequent mutations on this map or the copy
     * do not affect the other. Required for snapshot/rollback paths in
     * future {@code TreeSplicer} work.
     */
    LongLongMap copy();
}
