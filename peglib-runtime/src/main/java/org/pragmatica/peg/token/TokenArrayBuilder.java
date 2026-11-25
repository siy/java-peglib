package org.pragmatica.peg.token;

import java.util.Arrays;


/**
 * Append-style mutable builder for {@link TokenArray}. Single-shot: one call to
 * {@link #build(String[])} produces the array; subsequent calls fail fast.
 *
 * <p>Storage uses parallel {@code int[]} arrays grown by doubling.
 *
 * <p>Since 0.7.5 a lexer also records, per token, how far its scan looked: one past the last
 * position it examined, where examining end of input counts as position {@code input.length()}.
 * {@link TokenArray#spliceLex} needs it to know which tokens an edit can change. A lexer that
 * appends without it (one generated before 0.7.5) produces an array that splices by re-lexing
 * the whole input, which is always correct.
 *
 * <p>This builder is an internal hot-path helper invoked from generated lexer code and
 * a small number of trusted Java callers. Defensive validation is intentionally omitted:
 * callers pass sane inputs, JVM array bounds catch genuine bugs.
 */
/*
 * Parse hot path — imperative by design.
 *
 * JBCT-PAT-01 (raw loops) and JBCT-UTIL-02 (Verify.Is:: predicates) are suppressed for this
 * class as a deliberate policy, not an oversight. These methods run per-token / per-node on
 * every parse, and this is the most correctness-critical code in the project: a mechanical
 * rewrite to functional iteration would carry real regression risk for no user-visible gain.
 *
 * Note the honest framing: this is NOT a claim that streams measure slower here — nobody has
 * profiled that, and per the project's "profile-first, theorize never" rule such a claim would
 * be worth little. The argument is risk-versus-benefit, and it stands on that alone. If the
 * rewrite is ever attempted, bench it (Java25ParseBenchmark / Java25LargeFixturesBenchmark
 * from peglib-core/) rather than assuming either direction.
 */
@SuppressWarnings({"JBCT-PAT-01", "JBCT-UTIL-02"})
public final class TokenArrayBuilder {
    private static final int DEFAULT_INITIAL_CAPACITY = 64;

    private final String input;
    private int[] starts;
    private int[] ends;
    private int[] kinds;
    private int[] reaches;
    private boolean reachKnown;
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
        this.reaches = new int[cap];
        this.reachKnown = true;
        this.size = 0;
        this.built = false;
    }

    /** Append a token whose scan extent is unknown; the built array then splices by full re-lex. */
    @SuppressWarnings("JBCT-RET-01")
    public void append(int kind, int start, int end) {
        reachKnown = false;
        append(kind, start, end, Integer.MAX_VALUE);
    }

    /**
     * Append a token together with its scan extent.
     *
     * @param reach one past the last position the lexer examined to produce this token; examining
     *              end of input counts as examining position {@code input.length()}
     * @since 0.7.5
     */
    @SuppressWarnings("JBCT-RET-01")
    public void append(int kind, int start, int end, int reach) {
        ensureCapacity(size + 1);
        starts[size] = start;
        ends[size] = end;
        kinds[size] = kind;
        reaches[size] = reach;
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
        var trimmedReaches = reachKnown
                             ? trim(reaches, size)
                             : null;
        var nameTableCopy = kindNameTable.clone();

        built = true;
        starts = null;
        ends = null;
        kinds = null;
        reaches = null;

        return new TokenArray(input, trimmedStarts, trimmedEnds, trimmedKinds, trimmedReaches, size, nameTableCopy);
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
        reaches = Arrays.copyOf(reaches, newCap);
    }

    private static int[] trim(int[] src, int length) {
        if (src.length == length) {
            return src;
        }

        return Arrays.copyOf(src, length);
    }
}
