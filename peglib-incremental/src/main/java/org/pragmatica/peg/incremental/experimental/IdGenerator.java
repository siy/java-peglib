package org.pragmatica.peg.incremental.experimental;
/**
 * Source of stable {@code long} identifiers for CST nodes.
 *
 * <p>Phase 0 of the v0.5.0 incremental-native rework introduces stable per-node
 * IDs (Lever A in {@code docs/incremental/ARCHITECTURE-0.5.0.md} §2). Open
 * question Q1 in §8 is resolved: the v0.5.0 strategy is a per-Session counter,
 * not a process-global counter. Each {@link org.pragmatica.peg.incremental.Session}
 * instantiates its own generator and the IDs it produces are unique only
 * within that session's lineage.
 *
 * <p>The interface exists so that future strategies — a process-global
 * {@link java.util.concurrent.atomic.AtomicLong}, content-derived hashes, or
 * an external store — can swap in without disturbing call sites. Only
 * {@link PerSessionCounter} is permitted today (YAGNI; add variants when a
 * concrete need lands).
 *
 * <p>Implementations are <strong>not required to be thread-safe</strong>.
 * {@link org.pragmatica.peg.incremental.Session} is single-threaded by design
 * (per the {@code Session} Javadoc, concurrent edits against the same
 * instance are undefined), so the parser engine never races on ID
 * allocation.
 *
 * @since 0.5.0
 */
public sealed interface IdGenerator permits IdGenerator.PerSessionCounter {
    /**
     * Produce the next ID. Successive calls on the same instance return
     * strictly monotonically increasing values starting from 0.
     */
    long next();

    /**
     * Single-threaded counter. The first call returns 0, then 1, 2, …
     *
     * <p>Overflow is theoretically possible after 2^63 calls but practically
     * unreachable: at one ID per nanosecond it would take ~292 years to wrap.
     * No overflow check is performed.
     */
    final class PerSessionCounter implements IdGenerator {
        private long next;

        public PerSessionCounter() {
            this.next = 0L;
        }

        @Override
        public long next() {
            return next++;
        }
    }
}
