package org.pragmatica.peg.incremental.experimental;

/**
 * External {@code id → (startOffset, endOffset)} index for the path-A spike of
 * the v0.5.0 incremental-native rework
 * (see {@code docs/incremental/ARCHITECTURE-0.5.0.md} §2 + the path-A blocker
 * resolution in HANDOVER §10/11).
 *
 * <p><strong>Why this exists.</strong> Production {@link org.pragmatica.peg.tree.CstNode}
 * carries a {@link org.pragmatica.peg.tree.SourceSpan} as a record component.
 * Mid-buffer edits force {@code TreeSplicer.spliceAndShift} to deep-copy every
 * sibling subtree right of the edit (~half the tree on a typical interior
 * keystroke) just to rewrite offsets. Path A decouples offsets from the
 * record, storing them in this {@code SpanIndex} keyed by the stable
 * {@code long id} carried on each {@link OffsetDecoupledNode}. Edits become
 * a single in-place walk over a primitive {@code long[]} (this class) instead
 * of a recursive record rebuild.
 *
 * <h2>Storage</h2>
 *
 * <p>Backed by a {@link LinearProbingLongLongMap} keyed by node id. Values pack
 * {@code (startOffset, endOffset)} into a single {@code long}: high 32 bits
 * are {@code startOffset}, low 32 bits are {@code endOffset & 0xFFFFFFFFL}.
 * This reuses the Phase 0a primitive-long-keyed map verbatim and avoids a
 * second parallel-arrays implementation.
 *
 * <p>The packed encoding constrains offsets to fit in a signed 32-bit int,
 * which matches {@link org.pragmatica.peg.tree.SourceSpan#startOffset()}
 * production semantics; offsets up to ~2 GiB are representable.
 *
 * <h2>Thread-safety</h2>
 *
 * <p>Not thread-safe; mirrors {@link LongLongMap}.
 *
 * @since 0.5.0
 */
public final class SpanIndex {
    private final LongLongMap map;

    public SpanIndex(int initialCapacity) {
        this.map = new LinearProbingLongLongMap(initialCapacity);
    }

    private SpanIndex(LongLongMap map) {
        this.map = map;
    }

    /** Insert or overwrite the {@code (startOffset, endOffset)} for {@code nodeId}. */
    public void put(long nodeId, int startOffset, int endOffset) {
        map.put(nodeId, pack(startOffset, endOffset));
    }

    /**
     * Start offset for {@code nodeId}.
     *
     * @throws IllegalStateException when {@code nodeId} is absent.
     */
    public int startOffset(long nodeId) {
        long packed = map.get(nodeId);
        if (packed == LongLongMap.MISSING && !map.containsKey(nodeId)) {
            throw new IllegalStateException("nodeId not present in SpanIndex: " + nodeId);
        }
        return unpackStart(packed);
    }

    /**
     * End offset for {@code nodeId}.
     *
     * @throws IllegalStateException when {@code nodeId} is absent.
     */
    public int endOffset(long nodeId) {
        long packed = map.get(nodeId);
        if (packed == LongLongMap.MISSING && !map.containsKey(nodeId)) {
            throw new IllegalStateException("nodeId not present in SpanIndex: " + nodeId);
        }
        return unpackEnd(packed);
    }

    /** {@code true} iff an entry exists for {@code nodeId}. */
    public boolean contains(long nodeId) {
        return map.containsKey(nodeId);
    }

    /** Number of entries. */
    public int size() {
        return map.size();
    }

    /**
     * Shift every entry whose {@code startOffset >= afterOffset} by
     * {@code delta}: both {@code startOffset} and {@code endOffset} move by
     * the same amount (a wholesale move, not a resize). Entries whose
     * {@code startOffset < afterOffset} but whose {@code endOffset >=
     * afterOffset} (i.e., spanning the edit) are NOT touched here — those
     * belong to ancestors on the splice path, which {@link OffsetDecoupledSplicer}
     * rewrites explicitly with their own end-extension logic.
     *
     * <p>Implemented eagerly: walks the underlying primitive {@code long[]}
     * via {@link LongLongMap#forEachEntry}. Cost is {@code O(size)} but each
     * slot touch is one packed-long compare and one packed-long write — the
     * tightest possible inner loop on the JVM. Compared to record-rebuilding
     * the same nodes (allocation + List.copyOf + per-record GC pressure)
     * this is the whole point of path A.
     *
     * <p>{@code delta == 0} is a no-op (skip the walk).
     *
     * <p>Future work (out of scope for the spike): a range-tree or lazy-log
     * encoding would lower cost to {@code O(log N)} per shift, at the cost of
     * read complexity. The eager walk is the simplest correct prove-out shape.
     */
    public void shift(int afterOffset, int delta) {
        if (delta == 0) {
            return;
        }
        map.forEachEntry((nodeId, packed) -> {
            int start = unpackStart(packed);
            if (start >= afterOffset) {
                int end = unpackEnd(packed);
                return pack(start + delta, end + delta);
            }
            return packed;
        });
    }

    /** Independent deep copy; subsequent mutations on either side are isolated. */
    public SpanIndex copy() {
        return new SpanIndex(map.copy());
    }

    // --- packing helpers ---

    private static long pack(int start, int end) {
        return ((long) start << 32) | (end & 0xFFFFFFFFL);
    }

    private static int unpackStart(long packed) {
        return (int) (packed >> 32);
    }

    private static int unpackEnd(long packed) {
        return (int) packed;
    }
}
