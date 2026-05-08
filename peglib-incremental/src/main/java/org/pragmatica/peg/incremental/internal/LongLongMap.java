package org.pragmatica.peg.incremental.internal;
/**
 * Primitive-keyed, primitive-valued map ({@code long → long}) used as the
 * backing store for {@link NodeIndex} since v0.5.0
 * (per {@code docs/incremental/ARCHITECTURE-0.5.0.md} §2 Lever A).
 *
 * <p>Promoted to production from {@code experimental.LongLongMap} in Phase 1.5.
 * The experimental copy stays in place as the prove-out reference; this
 * production copy is the one wired into {@link NodeIndex}.
 *
 * <p><strong>Sentinel.</strong> {@link #MISSING} is returned by {@link #get}
 * for absent keys. Callers must not store {@code Long.MIN_VALUE} as a value;
 * the {@code NodeIndex} use case (mapping child-id → parent-id, where IDs
 * come from {@code IdGenerator.PerSessionCounter} and start at 0) honours
 * this naturally.
 *
 * <p><strong>Thread-safety.</strong> Implementations are not thread-safe.
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
}
