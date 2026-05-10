package org.pragmatica.peg.v6.token;

import org.pragmatica.peg.v6.lexer.LexerEngine;

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
public final class TokenArray {
    public static final int KIND_WHITESPACE = 0;
    public static final int KIND_LINE_COMMENT = 1;
    public static final int KIND_BLOCK_COMMENT = 2;

    /** First numeric kind available to user grammar rules. Reserved kinds occupy {@code [0, FIRST_USER_KIND)}. */
    public static final int FIRST_USER_KIND = 3;

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
    private final String[] kindNameTable;

    TokenArray(String input, int[] starts, int[] ends, int[] kinds, int count, String[] kindNameTable) {
        this.input = input;
        this.starts = starts;
        this.ends = ends;
        this.kinds = kinds;
        this.count = count;
        this.kindNameTable = kindNameTable;
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
        var k = kindAt(i);
        return k == KIND_WHITESPACE || k == KIND_LINE_COMMENT || k == KIND_BLOCK_COMMENT;
    }

    public int nextNonTrivia(int from) {
        if (from < 0) {
            throw new IndexOutOfBoundsException("from=" + from + " < 0");
        }
        var i = from;
        while (i < count && isTriviaKind(kinds[i])) {
            i++ ;
        }
        return i;
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
     * Phase D.0 / D.0.1 — produce a new {@code TokenArray} reflecting an edit at
     * {@code [offset, offset + oldLen)} replaced by {@code newText}.
     *
     * <p>Convenience overload that bridges a {@link LexerEngine}; delegates to
     * {@link #spliceLex(LexFn, int, int, String)}. See that method for the
     * windowed re-lex contract.
     *
     * @throws IllegalArgumentException on invalid edit coordinates or null inputs
     */
    public TokenArray spliceLex(LexerEngine engine, int offset, int oldLen, String newText) {
        if (engine == null) {
            throw new IllegalArgumentException("engine must not be null");
        }
        return spliceLex((LexFn) engine::lex, offset, oldLen, newText);
    }

    /**
     * Phase D.0.1 — windowed re-lex.
     *
     * <p>Identifies the token range affected by the edit, conservatively expands by one
     * token on each side (covers merge/split cases at the boundary), re-lexes only the
     * affected window through {@code lexFn}, and splices the unaffected prefix/suffix
     * token spans (with offsets shifted by {@code newText.length() - oldLen}) around the
     * re-lexed window.
     *
     * <p>Correctness invariant: the returned {@code TokenArray} equals
     * {@code lexFn.lex(newInput)} byte-for-byte (same input string, same kinds[],
     * same starts[], same ends[]). Verified by
     * {@code TokenArraySpliceTest#splice_isByteForByteEqualToFreshLex} and the
     * additional parity test added in D.1.1.
     *
     * <p>Algorithm tolerates the standard edge cases:
     * <ul>
     *   <li>Edit at start: {@code reLexStart = 0}.</li>
     *   <li>Edit at end (insertion past last byte): {@code reLexEnd = count}.</li>
     *   <li>Insert into empty token array: re-lex {@code newText} only.</li>
     *   <li>Delete entire content: result has zero tokens (re-lex of empty string).</li>
     * </ul>
     *
     * @throws IllegalArgumentException on invalid edit coordinates or null inputs
     */
    public TokenArray spliceLex(LexFn lexFn, int offset, int oldLen, String newText) {
        if (lexFn == null) {
            throw new IllegalArgumentException("lexFn must not be null");
        }
        if (newText == null) {
            throw new IllegalArgumentException("newText must not be null");
        }
        if (oldLen < 0) {
            throw new IllegalArgumentException("oldLen must be >= 0, got " + oldLen);
        }
        if (offset < 0) {
            throw new IllegalArgumentException("offset must be >= 0, got " + offset);
        }
        if (offset + oldLen > input.length()) {
            throw new IllegalArgumentException(
            "edit range [" + offset + ", " + (offset + oldLen) + ") exceeds input length " + input.length());
        }
        var newInput = input.substring(0, offset) + newText + input.substring(offset + oldLen);
        var netDelta = newText.length() - oldLen;
        // Empty token array — fall back to plain lex of the resulting input.
        if (count == 0) {
            return lexFn.lex(newInput);
        }
        // Locate affected range [firstAffected, lastAffected] in OLD tokens.
        //   firstAffected = smallest i such that ends[i] > offset (first token reaching into the edit)
        //   lastAffected  = largest i such that starts[i] < offset+oldLen (last token starting before edit ends)
        // For oldLen == 0, lastAffected uses (offset + 0) so we get the last token starting strictly
        // before offset. The +/-1 conservative expansion below picks up the boundary tokens.
        var firstAffected = firstTokenWithEndAfter(offset);
        var lastAffected = lastTokenWithStartBefore(offset + oldLen);
        int reLexStart;
        int reLexEnd;
        // exclusive in OLD token index space
        if (firstAffected >= count && lastAffected < 0) {
            // No old token overlaps the edit; pure boundary insertion outside any token.
            reLexStart = Math.max(0, firstAffected);
            reLexEnd = reLexStart;
        }else {
            var first = (firstAffected >= count)
                        ? count - 1
                        : firstAffected;
            var last = (lastAffected < 0)
                       ? 0
                       : lastAffected;
            // Conservative expansion: back up by 1 and forward by 1 (handles merge/split).
            reLexStart = Math.max(0, first - 1);
            reLexEnd = Math.min(count, last + 2);
        }
        // Byte ranges in the OLD input that the window covers. With covered token streams
        // (every input byte is part of some token, including whitespace), the conservative
        // expansion above guarantees the window encloses [offset, offset+oldLen) fully.
        int oldByteStart;
        int oldByteEnd;
        if (reLexEnd <= reLexStart) {
            // Window collapsed (no enclosed tokens). Use the edit range itself; the
            // surrounding prefix/suffix (if any) already covers the needed context via
            // the +/- 1 token expansion further up. This branch fires only when the edit
            // sits in a region with no tokens at all (degenerate; shouldn't happen with
            // covered streams but is handled defensively).
            oldByteStart = offset;
            oldByteEnd = offset + oldLen;
        }else {
            oldByteStart = starts[reLexStart];
            oldByteEnd = ends[reLexEnd - 1];
        }
        // Construct the windowed input from the NEW input (so the edit is already applied).
        var newWindowEnd = oldByteEnd + netDelta;
        var windowedInput = newInput.substring(oldByteStart, newWindowEnd);
        // Re-lex only the window.
        var windowTokens = lexFn.lex(windowedInput);
        // Build the merged TokenArray:
        //   prefix [0, reLexStart)         — kinds/starts/ends copied verbatim from OLD
        //   window tokens                  — kinds copied; starts/ends shifted by +oldByteStart
        //   suffix [reLexEnd, count)       — kinds copied; starts/ends shifted by +netDelta
        var winCount = windowTokens.count();
        var totalCount = reLexStart + winCount + (count - reLexEnd);
        var newKinds = new int[totalCount];
        var newStarts = new int[totalCount];
        var newEnds = new int[totalCount];
        if (reLexStart > 0) {
            System.arraycopy(kinds, 0, newKinds, 0, reLexStart);
            System.arraycopy(starts, 0, newStarts, 0, reLexStart);
            System.arraycopy(ends, 0, newEnds, 0, reLexStart);
        }
        for (var i = 0; i < winCount; i++ ) {
            newKinds[reLexStart + i] = windowTokens.kindAt(i);
            newStarts[reLexStart + i] = windowTokens.startAt(i) + oldByteStart;
            newEnds[reLexStart + i] = windowTokens.endAt(i) + oldByteStart;
        }
        var suffixBase = reLexStart + winCount;
        for (var i = reLexEnd; i < count; i++ ) {
            newKinds[suffixBase + (i - reLexEnd)] = kinds[i];
            newStarts[suffixBase + (i - reLexEnd)] = starts[i] + netDelta;
            newEnds[suffixBase + (i - reLexEnd)] = ends[i] + netDelta;
        }
        return new TokenArray(newInput, newStarts, newEnds, newKinds, totalCount, kindNameTable);
    }

    /**
     * Smallest token index {@code i} such that {@code ends[i] > byteOffset}, i.e. the
     * first token whose span reaches strictly past {@code byteOffset}. Returns
     * {@code count} when every token ends at or before {@code byteOffset} (the offset
     * is at or past the end of the token stream).
     *
     * <p>Binary search relies on {@code ends} being monotonically non-decreasing across
     * tokens (a property of the maximal-munch lexer: token {@code i+1} starts at the
     * end of token {@code i}, so its end is &ge; that).
     */
    private int firstTokenWithEndAfter(int byteOffset) {
        var lo = 0;
        var hi = count;
        while (lo < hi) {
            var mid = (lo + hi)>>> 1;
            if (ends[mid] <= byteOffset) {
                lo = mid + 1;
            }else {
                hi = mid;
            }
        }
        return lo;
    }

    /**
     * Largest token index {@code i} such that {@code starts[i] < byteOffset}, i.e. the
     * last token whose span begins strictly before {@code byteOffset}. Returns
     * {@code -1} when no token starts before {@code byteOffset} (offset is at or before
     * the first token's start).
     */
    private int lastTokenWithStartBefore(int byteOffset) {
        if (count == 0) {
            return - 1;
        }
        var lo = 0;
        var hi = count;
        while (lo < hi) {
            var mid = (lo + hi)>>> 1;
            if (starts[mid] < byteOffset) {
                lo = mid + 1;
            }else {
                hi = mid;
            }
        }
        return lo - 1;
    }

    private static boolean isTriviaKind(int k) {
        return k == KIND_WHITESPACE || k == KIND_LINE_COMMENT || k == KIND_BLOCK_COMMENT;
    }

    private void checkIndex(int i) {
        if (i < 0 || i >= count) {
            throw new IndexOutOfBoundsException("token index " + i + " out of bounds [0, " + count + ")");
        }
    }
}
