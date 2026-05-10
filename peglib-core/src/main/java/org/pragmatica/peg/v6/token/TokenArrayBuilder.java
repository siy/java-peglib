package org.pragmatica.peg.v6.token;

import java.util.Arrays;

/**
 * Append-style mutable builder for {@link TokenArray}. Single-shot: one call to
 * {@link #build(String[])} produces the array; subsequent calls fail fast.
 *
 * <p>Storage uses three parallel {@code int[]} arrays grown by doubling. Validation
 * is deferred to {@link #build} so that callers can stream tokens without per-append
 * allocation overhead.
 */
public final class TokenArrayBuilder {

    private static final int DEFAULT_INITIAL_CAPACITY = 64;

    private final String input;
    private int[] starts;
    private int[] ends;
    private int[] kinds;
    private int size;
    private boolean built;

    public TokenArrayBuilder(String input) {
        this(input, DEFAULT_INITIAL_CAPACITY);
    }

    public TokenArrayBuilder(String input, int initialCapacity) {
        if (input == null) {
            throw new IllegalArgumentException("input must not be null");
        }
        if (initialCapacity < 0) {
            throw new IllegalArgumentException("initialCapacity must be >= 0, got " + initialCapacity);
        }
        var cap = Math.max(initialCapacity, 1);
        this.input = input;
        this.starts = new int[cap];
        this.ends = new int[cap];
        this.kinds = new int[cap];
        this.size = 0;
        this.built = false;
    }

    public void append(int kind, int start, int end) {
        if (built) {
            throw new IllegalStateException("builder already built; cannot append further tokens");
        }
        ensureCapacity(size + 1);
        starts[size] = start;
        ends[size] = end;
        kinds[size] = kind;
        size++;
    }

    public int size() {
        return size;
    }

    public TokenArray build(String[] kindNameTable) {
        if (built) {
            throw new IllegalStateException("builder already built; build() is single-shot");
        }
        if (kindNameTable == null) {
            throw new IllegalArgumentException("kindNameTable must not be null");
        }
        validate(kindNameTable);
        var trimmedStarts = trim(starts, size);
        var trimmedEnds = trim(ends, size);
        var trimmedKinds = trim(kinds, size);
        var nameTableCopy = kindNameTable.clone();
        built = true;
        starts = null;
        ends = null;
        kinds = null;
        return new TokenArray(input, trimmedStarts, trimmedEnds, trimmedKinds, size, nameTableCopy);
    }

    private void validate(String[] kindNameTable) {
        var inputLen = input.length();
        var prevStart = 0;
        for (var i = 0; i < size; i++) {
            var s = starts[i];
            var e = ends[i];
            var k = kinds[i];
            if (s < 0 || s > inputLen) {
                throw new IllegalArgumentException(
                    "token[" + i + "] start=" + s + " out of input bounds [0, " + inputLen + "]");
            }
            if (e < s || e > inputLen) {
                throw new IllegalArgumentException(
                    "token[" + i + "] end=" + e + " invalid (start=" + s + ", input length=" + inputLen + ")");
            }
            if (s < prevStart) {
                throw new IllegalArgumentException(
                    "token[" + i + "] start=" + s + " less than previous start=" + prevStart
                        + " (starts must be non-decreasing)");
            }
            if (k < 0 || k >= kindNameTable.length) {
                throw new IllegalArgumentException(
                    "token[" + i + "] kind=" + k + " out of kindNameTable range [0, "
                        + kindNameTable.length + ")");
            }
            prevStart = s;
        }
    }

    private void ensureCapacity(int required) {
        if (required <= starts.length) {
            return;
        }
        var newCap = starts.length;
        while (newCap < required) {
            newCap = newCap << 1;
            if (newCap < 0) {
                newCap = Integer.MAX_VALUE - 8;
            }
        }
        starts = Arrays.copyOf(starts, newCap);
        ends = Arrays.copyOf(ends, newCap);
        kinds = Arrays.copyOf(kinds, newCap);
    }

    private static int[] trim(int[] src, int length) {
        if (src.length == length) {
            return src;
        }
        return Arrays.copyOf(src, length);
    }
}
