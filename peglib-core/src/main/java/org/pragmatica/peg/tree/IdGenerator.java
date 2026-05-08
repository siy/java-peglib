package org.pragmatica.peg.tree;
/**
 * Source of stable {@code long} identifiers for CST nodes.
 *
 * <p>Phase 1.2 of the v0.5.0 incremental-native rework promotes the sandbox
 * Phase-0 spike (Lever A in {@code docs/incremental/ARCHITECTURE-0.5.0.md} §2)
 * into the production tree. Each {@link org.pragmatica.peg.parser.ParsingContext}
 * instantiates its own generator at parse start; IDs are unique within that
 * parse session's lineage but not across sessions.
 *
 * <p>The interface exists so that future strategies — a process-global
 * {@link java.util.concurrent.atomic.AtomicLong}, content-derived hashes, or
 * an external store — can swap in without disturbing call sites. Only
 * {@link PerSessionCounter} is permitted today (YAGNI; add variants when a
 * concrete need lands).
 *
 * <p>Implementations are <strong>not required to be thread-safe</strong>.
 * Parsing is single-threaded; the engine never races on ID allocation.
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
            return next++ ;
        }
    }
}
