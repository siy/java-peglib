package org.pragmatica.peg.lexer;

public final class Dfa {
    public static final int START_STATE = 0;
    public static final int NO_TRANSITION = -1;
    public static final int NO_ACCEPT = -1;
    public static final int ALPHABET_SIZE = 256;
    /** No follow constraint on this state's accept. */
    public static final int NO_FOLLOW = -1;
    /** Slot in a follow row for "the next character is non-ASCII". */
    public static final int FOLLOW_NON_ASCII = ALPHABET_SIZE;
    /** Slot in a follow row for "there is no next character". */
    public static final int FOLLOW_EOF = ALPHABET_SIZE + 1;

    /** Width of a follow row: one slot per ASCII code, plus non-ASCII, plus end of input. */
    public static final int FOLLOW_ROW = ALPHABET_SIZE + 2;

    /** Passed to {@link #acceptAllowsFollower} when the accept sits at end of input. */
    public static final int EOF = -1;

    private final int[][] transitions;
    private final int[] acceptKind;

    private final int[] acceptPriority;

    /**
     * Per-state target for input characters {@code >= ALPHABET_SIZE} (i.e. non-ASCII / BMP-plus).
     * Set by the builder whenever a state's NFA closure contains an NFA edge that accepts
     * non-ASCII characters (Any {@code .} or negated CharClass {@code [^...]}). When non-negative,
     * the lexer follows this transition; when {@link #NO_TRANSITION}, the lexer treats the
     * input as a stall (same as a missing ASCII transition).
     */
    private final int[] nonAsciiTransition;

    /**
     * Per-state index into {@link #followTable}, or {@link #NO_FOLLOW}.
     *
     * <p>A lexer rule ending in {@code ![c]} or {@code &[c]} constrains the character AFTER the
     * match without consuming it, which a DFA transition cannot express. The constraint rides on
     * the accepting state instead: the driver consults it before recording the accept, so a
     * denied accept simply does not count and maximal munch continues from the last one that did.
     */
    private final int[] acceptFollow;

    /**
     * Interned follow constraints, {@link #FOLLOW_ROW} entries each, non-zero where the follower
     * is ALLOWED. Negation is resolved here at build time so the driver never branches on polarity.
     */
    private final int[][] followTable;

    Dfa(int[][] transitions,
        int[] acceptKind,
        int[] acceptPriority,
        int[] nonAsciiTransition,
        int[] acceptFollow,
        int[][] followTable) {
        this.transitions = transitions;
        this.acceptKind = acceptKind;
        this.acceptPriority = acceptPriority;
        this.nonAsciiTransition = nonAsciiTransition;
        this.acceptFollow = acceptFollow;
        this.followTable = followTable;
    }

    public int stateCount() {
        return transitions.length;
    }

    public int alphabetSize() {
        return ALPHABET_SIZE;
    }

    public int transition(int state, int ch) {
        if (ch < 0 || ch >= ALPHABET_SIZE) {
            return nonAsciiTransition[state];
        }

        return transitions[state][ch];
    }

    /**
     * Returns the next state when the input character is non-ASCII (code point &ge; 256),
     * or {@link #NO_TRANSITION} when this state has no non-ASCII edge. Used by the lexer
     * driver and the generated lexer for the {@code ch >= ALPHABET_SIZE} fast-path.
     */
    public int nonAsciiTransition(int state) {
        return nonAsciiTransition[state];
    }

    public int acceptKind(int state) {
        return acceptKind[state];
    }

    public int acceptPriority(int state) {
        return acceptPriority[state];
    }

    public int[][] transitionTable() {
        return transitions;
    }

    public int[] acceptKinds() {
        return acceptKind;
    }

    public int[] acceptPriorities() {
        return acceptPriority;
    }

    public int[] nonAsciiTransitions() {
        return nonAsciiTransition;
    }

    public int[] acceptFollows() {
        return acceptFollow;
    }

    public int[][] followTable() {
        return followTable;
    }

    /**
     * Whether {@code state}'s accept is permitted given the character that follows the match.
     *
     * <p>Pass {@link #EOF} when the match ends the input. States with no constraint always allow.
     */
    public boolean acceptAllowsFollower(int state, int follower) {
        int constraint = acceptFollow[state];

        if (constraint == NO_FOLLOW) {
            return true;
        }

        var row = followTable[constraint];

        if (follower == EOF) {
            return row[FOLLOW_EOF] != 0;
        }

        return follower >= ALPHABET_SIZE
               ? row[FOLLOW_NON_ASCII] != 0
               : row[follower] != 0;
    }
}
