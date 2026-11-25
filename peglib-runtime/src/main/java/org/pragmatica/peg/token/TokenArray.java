package org.pragmatica.peg.token;

/**
 * Phase A.2 — flat token-stream data structure for the 0.6.0 lex-then-parse pipeline.
 *
 * <p>Per spec §3.2, every successful lex produces one {@code TokenArray}: the original
 * input, three parallel primitive arrays carrying span and kind metadata, plus a
 * kind-name table for diagnostics. Trivia tokens (whitespace and comments) live in
 * the same stream as content tokens, classified by kind (§3.6).
 *
 * <p>The shape is read-only: instances are produced by {@link TokenArrayBuilder#build}
 * and never mutated thereafter.
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
public final class TokenArray {
    public static final int KIND_WHITESPACE = 0;
    public static final int KIND_LINE_COMMENT = 1;

    public static final int KIND_BLOCK_COMMENT = 2;

    /** Triple-slash documentation line comment ({@code /// ...}). Trivia. */
    public static final int KIND_DOC_LINE_COMMENT = 3;

    /** Javadoc-style block comment ({@code /** ... *}{@code /}). Trivia. */
    public static final int KIND_DOC_BLOCK_COMMENT = 4;

    /** First numeric kind available to user grammar rules. Reserved kinds occupy {@code [0, FIRST_USER_KIND)}. */
    public static final int FIRST_USER_KIND = 5;

    private final String input;
    private final int[] starts;

    private final int[] ends;

    /**
     * Kind ID per token. {@code int[]} (not {@code byte[]}/{@code short[]}) because real grammars
     * (Java25, see CLAUDE.md) exceed 128 rule kinds; the 4-bytes-per-token cost is negligible
     * vs the cost of subtle overflow bugs under {@code byte}.
     */
    private final int[] kinds;

    private final int count;

    /**
     * Per token, one past the last input position its scan examined (end of input counts as
     * position {@code input.length()}); {@code null} when the lexer did not record it. Read only
     * by {@link #spliceLex}.
     */
    private final int[] reaches;

    private final String[] kindNameTable;

    /**
     * Precomputed lookup table for {@link #nextNonTrivia(int)}. {@code nextNonTriviaTable[i]}
     * is the smallest {@code j >= i} such that {@code j == count} or {@code kinds[j]} is a
     * non-trivia kind. Computed in O(n) at construction; lookup is O(1). Trades 4 bytes per
     * token for eliminating a hot-loop linear scan past trivia runs.
     *
     * <p>Length is {@code count + 1}; {@code nextNonTriviaTable[count] == count} acts as a
     * sentinel so callers passing {@code from == count} get a defined answer.
     */
    private final int[] nextNonTriviaTable;

    /** First re-lex window, in characters; it doubles until the re-lex resynchronises. */
    private static final int MIN_WINDOW = 256;

    TokenArray(String input, int[] starts, int[] ends, int[] kinds, int[] reaches, int count, String[] kindNameTable) {
        this.input = input;
        this.starts = starts;
        this.ends = ends;
        this.kinds = kinds;
        this.reaches = reaches;
        this.count = count;
        this.kindNameTable = kindNameTable;
        this.nextNonTriviaTable = computeNextNonTrivia(kinds, count);
    }

    private static int[] computeNextNonTrivia(int[] kinds, int count) {
        var table = new int[count + 1];

        table[count] = count;
        for (var i = count - 1; i >= 0; i--) {
            table[i] = isTriviaKind(kinds[i])
                       ? table[i + 1]
                       : i;
        }

        return table;
    }

    /**
     * Re-express this token array against a different source string, translating every token
     * span through {@code offsetMap}.
     *
     * <p>Used for Java's Unicode-escape pre-pass (JLS 3.3): the lexer runs over text in which
     * {@code \\uXXXX} has already been replaced by the character it denotes, so
     * {@code \\u0069f} lexes as the keyword {@code if}. The resulting spans point into that
     * translated text, which would make {@link org.pragmatica.peg.cst.CstArray#reconstruct()}
     * emit translated source and break the formatter's byte-identical round-trip. Remapping
     * restores spans over the ORIGINAL text, so the escape sequence survives verbatim while
     * the parser still sees the character it denotes.
     *
     * <p>{@code offsetMap} has one entry per translated character plus a terminating entry, so
     * an end offset immediately after the last character maps correctly.
     *
     * @param originalInput the untranslated source
     * @param offsetMap     translated index to original index; length at least
     *                      {@code translatedLength + 1}
     */
    public TokenArray remapOffsets(String originalInput, int[] offsetMap) {
        var newStarts = new int[count];
        var newEnds = new int[count];

        for (var i = 0; i < count; i++) {
            newStarts[i] = mapOffset(offsetMap, starts[i]);
            newEnds[i] = mapOffset(offsetMap, ends[i]);
        }
        // Extents measured over the translated text do not carry over; splicing re-lexes whole.
        return new TokenArray(originalInput, newStarts, newEnds, kinds, null, count, kindNameTable);
    }

    private static int mapOffset(int[] offsetMap, int offset) {
        if (offset < 0) {
            return 0;
        }

        return offset < offsetMap.length
               ? offsetMap[offset]
               : offsetMap[offsetMap.length - 1];
    }

    public int count() {
        return count;
    }

    public int kindAt(int i) {
        checkIndex(i);

        return kinds[i];
    }

    public int startAt(int i) {
        checkIndex(i);

        return starts[i];
    }

    public int endAt(int i) {
        checkIndex(i);

        return ends[i];
    }

    public CharSequence textAt(int i) {
        checkIndex(i);

        return input.subSequence(starts[i], ends[i]);
    }

    public boolean isTrivia(int i) {
        return isTriviaKind(kindAt(i));
    }

    public int nextNonTrivia(int from) {
        if (from >= count) {
            return count;
        }

        return nextNonTriviaTable[from];
    }

    public String kindName(int i) {
        var k = kindAt(i);

        if (k < 0 || k >= kindNameTable.length) {
            return "<kind:" + k + ">";
        }

        return kindNameTable[k];
    }

    public String input() {
        return input;
    }

    /**
     * Whether every token carries its scan extent, so {@link #spliceLex} can re-lex a window
     * instead of the whole input.
     *
     * @since 0.7.5
     */
    public boolean hasScanReach() {
        return reaches != null;
    }

    /**
     * Re-lex after an edit replacing {@code input[offset, offset + oldLen)} with {@code newText}.
     *
     * <p>The result equals {@code lexFn.lex(newInput)} token for token. That is the whole contract
     * (issue #49); the window below is only how it is met cheaply. Lexing is deterministic and
     * restarts from scratch at every token start, so a token can change only if its scan examined
     * the edited text:
     * <ul>
     *   <li><b>Start.</b> Re-lexing starts at the first token whose scan reached {@code offset}.
     *       Every earlier token examined only unchanged text. A scan can look far past its own
     *       token: an unterminated {@code /*} scans to end of input and then falls back to
     *       {@code /}, so closing it anywhere later changes that token. The reach recorded by
     *       the lexer is what makes this exact.</li>
     *   <li><b>Stop.</b> Re-lexing stops at the first new token that starts past the edited text
     *       at a position where an old token also started (shifted by the edit). From there the
     *       lexer sees the same suffix as before, so it produces the same tokens.</li>
     * </ul>
     *
     * <p>The re-lex runs over windows of the new input that double in size. A token whose scan
     * examined the end of a window is not trusted (it may have seen the artificial end of input)
     * and is re-lexed in the next window. An array without scan extents, from a lexer generated
     * before 0.7.5 or re-expressed by {@link #remapOffsets}, is re-lexed whole.
     */
    public TokenArray spliceLex(LexFn lexFn, int offset, int oldLen, String newText) {
        // Caller (IncrementalParser) validates edit coordinates; defensive checks omitted.
        var newInput = input.substring(0, offset) + newText + input.substring(offset + oldLen);

        if (reaches == null || count == 0) {
            return lexFn.lex(newInput);
        }

        int delta = newText.length() - oldLen;
        int editEndNew = offset + newText.length();
        int newLen = newInput.length();
        int first = 0;

        while (first < count && reaches[first] <= offset) {
            first++;
        }

        int pos = first < count
                  ? starts[first]
                  : ends[count - 1];
        var middle = new TokenArrayBuilder(newInput);
        int resume = count;
        int window = Math.max(MIN_WINDOW, editEndNew - pos + MIN_WINDOW);

        outer : while (pos < newLen) {
            int end = (int) Math.min(newLen, (long) pos + window);
            int limit = end - pos;
            boolean trueEnd = end == newLen;
            var chunk = lexFn.lex(newInput.substring(pos, end));

            if (chunk.reaches == null) {
                return lexFn.lex(newInput);
            }

            int base = pos;

            for (int j = 0; j < chunk.count; j++) {
                if (!trueEnd && chunk.reaches[j]> limit) {
                    break;
                }

                int start = chunk.starts[j] + base;

                if (start >= editEndNew) {
                    int old = indexOfStart(start - delta);

                    if (old >= 0) {
                        resume = old;
                        break outer;
                    }
                }

                middle.append(chunk.kinds[j], start, chunk.ends[j] + base, chunk.reaches[j] + base);
                pos = chunk.ends[j] + base;
            }

            window = window > Integer.MAX_VALUE / 2
                     ? Integer.MAX_VALUE
                     : window * 2;
        }
        var mid = middle.build(kindNameTable);
        int midCount = mid.count;
        int tail = count - resume;
        int total = first + midCount + tail;
        var newKinds = new int[total];
        var newStarts = new int[total];
        var newEnds = new int[total];
        var newReaches = new int[total];

        System.arraycopy(kinds, 0, newKinds, 0, first);
        System.arraycopy(starts, 0, newStarts, 0, first);
        System.arraycopy(ends, 0, newEnds, 0, first);
        System.arraycopy(reaches, 0, newReaches, 0, first);
        System.arraycopy(mid.kinds, 0, newKinds, first, midCount);
        System.arraycopy(mid.starts, 0, newStarts, first, midCount);
        System.arraycopy(mid.ends, 0, newEnds, first, midCount);
        System.arraycopy(mid.reaches, 0, newReaches, first, midCount);
        int at = first + midCount;

        System.arraycopy(kinds, resume, newKinds, at, tail);
        for (int i = 0; i < tail; i++) {
            newStarts[at + i] = starts[resume + i] + delta;
            newEnds[at + i] = ends[resume + i] + delta;
            newReaches[at + i] = reaches[resume + i] + delta;
        }

        return new TokenArray(newInput, newStarts, newEnds, newKinds, newReaches, total, kindNameTable);
    }

    /** Index of the token starting exactly at {@code position}, or {@code -1}. Starts strictly increase. */
    private int indexOfStart(int position) {
        int lo = 0;
        int hi = count - 1;

        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;

            if (starts[mid]< position) {
                lo = mid + 1;
            } else if (starts[mid]> position) {
                hi = mid - 1;
            } else {
                return mid;
            }
        }

        return -1;
    }

    private static boolean isTriviaKind(int k) {
        return k == KIND_WHITESPACE || k == KIND_LINE_COMMENT || k == KIND_BLOCK_COMMENT || k == KIND_DOC_LINE_COMMENT || k == KIND_DOC_BLOCK_COMMENT;
    }

    @SuppressWarnings("JBCT-RET-01")
    private void checkIndex(int i) {}
}
