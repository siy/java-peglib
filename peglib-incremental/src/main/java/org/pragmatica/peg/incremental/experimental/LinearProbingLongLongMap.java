package org.pragmatica.peg.incremental.experimental;
/**
 * Open-addressing, linear-probing implementation of {@link LongLongMap}.
 *
 * <p>Capacity is always a power of two so the slot index can be computed via a
 * bitmask. Resize at load factor 0.75 doubles the table.
 *
 * <p>Slot occupancy is encoded as a single {@code byte[]} ({@link #EMPTY},
 * {@link #OCCUPIED}, {@link #TOMBSTONE}) rather than two parallel
 * {@code boolean[]}s. This keeps each probe step to one byte fetch +
 * branch instead of two array reads, and halves the metadata footprint
 * (1 byte/slot vs 2 bytes/slot for two parallel boolean arrays — the JVM
 * stores a {@code boolean[]} element as a byte).
 *
 * <p>Probing rules:
 * <ul>
 *   <li>{@link #put}: stop at the first empty slot or matching key; the first
 *       tombstone seen on the probe is remembered and reused as the
 *       insertion target if the key is not already present further on.
 *   <li>{@link #get}, {@link #containsKey}, {@link #remove}: walk past
 *       tombstones, stop at empty.
 * </ul>
 *
 * @since 0.5.0
 */
public final class LinearProbingLongLongMap implements LongLongMap {
    /** Default backing-table capacity used by {@link #LinearProbingLongLongMap()}. */
    private static final int DEFAULT_CAPACITY = 16;

    private static final int MIN_CAPACITY = 4;

    /** Resize when {@code size > capacity * 0.75}. */
    private static final double LOAD_FACTOR = 0.75;

    private static final byte EMPTY = 0;
    private static final byte OCCUPIED = 1;
    private static final byte TOMBSTONE = 2;

    private long[] keys;
    private long[] values;
    private byte[] state;
    private int size;
    private int threshold;
    private int mask;

    public LinearProbingLongLongMap() {
        this(DEFAULT_CAPACITY);
    }

    public LinearProbingLongLongMap(int initialCapacity) {
        if (initialCapacity < 0) {
            throw new IllegalArgumentException("initialCapacity must be non-negative: " + initialCapacity);
        }
        int capacity = roundUpToPowerOfTwo(Math.max(initialCapacity, MIN_CAPACITY));
        this.keys = new long[capacity];
        this.values = new long[capacity];
        this.state = new byte[capacity];
        this.size = 0;
        this.mask = capacity - 1;
        this.threshold = (int)(capacity * LOAD_FACTOR);
    }

    @Override
    public void put(long key, long value) {
        int slot = slotFor(key);
        int firstTombstone = - 1;
        while (true) {
            byte s = state[slot];
            if (s == EMPTY) {
                int target = firstTombstone >= 0
                             ? firstTombstone
                             : slot;
                keys[target] = key;
                values[target] = value;
                state[target] = OCCUPIED;
                size++;
                if (size > threshold) {
                    resize(state.length<< 1);
                }
                return;
            }
            if (s == OCCUPIED && keys[slot] == key) {
                values[slot] = value;
                return;
            }
            if (s == TOMBSTONE && firstTombstone < 0) {
                firstTombstone = slot;
            }
            slot = (slot + 1) & mask;
        }
    }

    @Override
    public long get(long key) {
        int slot = slotFor(key);
        while (true) {
            byte s = state[slot];
            if (s == EMPTY) {
                return MISSING;
            }
            if (s == OCCUPIED && keys[slot] == key) {
                return values[slot];
            }
            slot = (slot + 1) & mask;
        }
    }

    @Override
    public boolean containsKey(long key) {
        int slot = slotFor(key);
        while (true) {
            byte s = state[slot];
            if (s == EMPTY) {
                return false;
            }
            if (s == OCCUPIED && keys[slot] == key) {
                return true;
            }
            slot = (slot + 1) & mask;
        }
    }

    @Override
    public void remove(long key) {
        int slot = slotFor(key);
        while (true) {
            byte s = state[slot];
            if (s == EMPTY) {
                return;
            }
            if (s == OCCUPIED && keys[slot] == key) {
                state[slot] = TOMBSTONE;
                size--;
                return;
            }
            slot = (slot + 1) & mask;
        }
    }

    @Override
    public int size() {
        return size;
    }

    @Override
    public void clear() {
        java.util.Arrays.fill(state, EMPTY);
        size = 0;
    }

    @Override
    public LongLongMap copy() {
        var copy = new LinearProbingLongLongMap(state.length);
        // Bypass the rounding/threshold dance — same capacity by construction.
        System.arraycopy(this.keys, 0, copy.keys, 0, this.keys.length);
        System.arraycopy(this.values, 0, copy.values, 0, this.values.length);
        System.arraycopy(this.state, 0, copy.state, 0, this.state.length);
        copy.size = this.size;
        return copy;
    }

    private int slotFor(long key) {
        return Long.hashCode(key) & mask;
    }

    private void resize(int newCapacity) {
        long[] oldKeys = this.keys;
        long[] oldValues = this.values;
        byte[] oldState = this.state;
        this.keys = new long[newCapacity];
        this.values = new long[newCapacity];
        this.state = new byte[newCapacity];
        this.mask = newCapacity - 1;
        this.threshold = (int)(newCapacity * LOAD_FACTOR);
        this.size = 0;
        for (int i = 0; i < oldState.length; i++) {
            if (oldState[i] == OCCUPIED) {
                put(oldKeys[i], oldValues[i]);
            }
        }
    }

    private static int roundUpToPowerOfTwo(int value) {
        // Smallest power of two >= value, with a sane lower bound.
        int n = MIN_CAPACITY;
        while (n < value) {
            n <<= 1;
        }
        return n;
    }
}
