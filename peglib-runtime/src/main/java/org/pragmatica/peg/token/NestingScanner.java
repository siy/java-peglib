package org.pragmatica.peg.token;

/**
 * 0.7.3 — depth-counting scanner for nesting trivia (nested block comments).
 *
 * <p>A nested block comment is not a regular language, so no DFA path can match one: a DFA
 * has no counter, and the compiled {@code '/*' (!'*&#47;' .)* '*&#47;'} alternative closes at
 * the FIRST close delimiter. For a language that nests — SQL/PostgreSQL, Rust, Swift, Haskell,
 * D, OCaml, Scala 3 — that early close leaks the remainder of the comment into the token
 * stream as live source. The failure is not reliably loud: when the leaked text happens to
 * compose into valid source the parser accepts a DIFFERENT program with no diagnostic at all.
 *
 * <p>A grammar opts one delimiter pair into counting with {@code %nest '/*' '*&#47;'}. The
 * lexer then consults this scanner at each token start, ahead of the DFA.
 *
 * <h2>Why this class lives in peglib-runtime</h2>
 *
 * <p>Both lexer paths call it: the interpreted {@code LexerEngine} and the generated
 * {@code GLexer}. Those two are required to stay algorithmically identical, and this project's
 * recorded failure mode is precisely that they drift — {@code LexerEngine} is exercised by the
 * test suite while {@code GLexer} is what {@code PegParser} actually runs, so a divergence
 * ships green. One definition in the module both can see removes the opportunity.
 *
 * <h2>Cost</h2>
 *
 * <p>The scan is a single left-to-right pass over the comment, entered only after the caller
 * has confirmed an open delimiter at the position, and it REPLACES the DFA pass rather than
 * adding to it — a nesting comment is scanned once, and slightly cheaper than the DFA would
 * have scanned it (character compares, no state-table indirection per character). Grammars
 * that declare no {@code %nest} never reach this class, and the generated lexer for such a
 * grammar does not mention it.
 */
/*
 * Parse hot path — imperative by design.
 *
 * JBCT-PAT-01 (raw loops) and JBCT-UTIL-02 (Verify.Is:: predicates) are suppressed for this
 * class as a deliberate policy, not an oversight. It runs per comment on every parse and is
 * character-at-a-time by nature; the surrounding lexer classes carry the same suppression for
 * the same reason. See TokenArray for the full framing.
 */
@SuppressWarnings({"JBCT-PAT-01", "JBCT-UTIL-02"})
public final class NestingScanner {
    /** Returned by {@link #scanEnd} when the delimiters never balance before end of input. */
    public static final int UNTERMINATED = -1;

    private NestingScanner() {}

    /**
     * Scan a nesting delimited block starting at {@code pos} and return the index one past its
     * matching close delimiter.
     *
     * <p>The caller must have already established that {@code input} starts with {@code open}
     * at {@code pos}; this method does not re-check the entry condition beyond counting it.
     *
     * <h2>Delimiter precedence</h2>
     *
     * <p>At each position {@code open} is tested before {@code close}. The order is only
     * observable for a pathological pair where one delimiter is a prefix of the other; for the
     * usual pairs ({@code /* *&#47;}, <code>{- -}</code>, {@code (* *)}) the first characters
     * differ and no position can match both. Testing open first is what the languages that
     * nest do, and it makes {@code /*&#47;} unterminated (one open, no close) rather than a
     * degenerate empty comment.
     *
     * <p>{@code /**&#47;} is a complete comment at depth one: the open consumes {@code /*} and
     * the close consumes {@code *&#47;}, leaving nothing between them.
     *
     * <h2>Unterminated input</h2>
     *
     * <p>Returns {@link #UNTERMINATED} when end of input arrives at a depth above zero. The
     * caller is expected to fall through to its ordinary DFA path rather than consuming to end
     * of input, which keeps malformed input behaving exactly as it did before the grammar
     * declared {@code %nest} — the directive can then only change the reading of comments that
     * actually balance, never of ones that do not.
     *
     * @param input the full lexer input
     * @param pos   index of the open delimiter
     * @param open  open delimiter, never empty
     * @param close close delimiter, never empty
     *
     * @return index one past the matching close delimiter, or {@link #UNTERMINATED}
     */
    public static int scanEnd(String input, int pos, String open, String close) {
        int len = input.length();
        int openLen = open.length();
        int closeLen = close.length();
        // An empty delimiter would advance by zero and hang the lexer. GrammarParser rejects
        // %nest with an empty delimiter, so this is unreachable from a parsed grammar; the
        // guard is here because a hang is a far worse failure than a refused scan, and it
        // costs one branch per comment rather than one per character.
        if (openLen == 0 || closeLen == 0) {
            return UNTERMINATED;
        }

        int depth = 0;
        int i = pos;

        while (i < len) {
            if (input.startsWith(open, i)) {
                depth++;
                i += openLen;
            } else if (input.startsWith(close, i)) {
                depth--;
                i += closeLen;
                if (depth == 0) {
                    return i;
                }
            } else {
                i++;
            }
        }

        return UNTERMINATED;
    }
}
