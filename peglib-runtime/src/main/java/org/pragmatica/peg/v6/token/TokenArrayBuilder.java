package org.pragmatica.peg.v6.token;

import java.util.Arrays;

/**
 * Append-style mutable builder for {@link TokenArray}. Single-shot: one call to
 * {@link #build(String[])} produces the array; subsequent calls fail fast.
 *
 * <p>Storage uses three parallel {@code int[]} arrays grown by doubling.
 *
 * <p>This builder is an internal hot-path helper invoked from generated lexer code and
 * a small number of trusted Java callers. Defensive validation is intentionally omitted:
 * callers pass sane inputs, JVM array bounds catch genuine bugs.
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
        var cap = Math.max(initialCapacity, 1);
        this.input = input;
        this.starts = new int[cap];
        this.ends = new int[cap];
        this.kinds = new int[cap];
        this.size = 0;
        this.built = false;
    }

    @SuppressWarnings("JBCT-RET-01")
    public void append(int kind, int start, int end) {
        ensureCapacity(size + 1);
        starts[size] = start;
        ends[size] = end;
        kinds[size] = kind;
        size++;
    }

    public int size() {
        return size;
    }

    public boolean isBuilt() {
        return built;
    }

    public TokenArray build(String[] kindNameTable) {
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

    private void ensureCapacity(int required) {
        if ( required <= starts.length) {
        return;}
        var newCap = starts.length;
        while ( newCap < required) {
            newCap = newCap<< 1;
            if ( newCap < 0) {
            newCap = Integer.MAX_VALUE - 8;}
        }
        starts = Arrays.copyOf(starts, newCap);
        ends = Arrays.copyOf(ends, newCap);
        kinds = Arrays.copyOf(kinds, newCap);
    }

    private static int[] trim(int[] src, int length) {
        if ( src.length == length) {
        return src;}
        return Arrays.copyOf(src, length);
    }
}
