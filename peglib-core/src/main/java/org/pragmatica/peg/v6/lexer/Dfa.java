package org.pragmatica.peg.v6.lexer;
public final class Dfa {
    public static final int START_STATE = 0;
    public static final int NO_TRANSITION = - 1;
    public static final int NO_ACCEPT = - 1;
    public static final int ALPHABET_SIZE = 256;

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

    Dfa(int[][] transitions, int[] acceptKind, int[] acceptPriority, int[] nonAsciiTransition) {
        this.transitions = transitions;
        this.acceptKind = acceptKind;
        this.acceptPriority = acceptPriority;
        this.nonAsciiTransition = nonAsciiTransition;
    }

    public int stateCount() {
        return transitions.length;
    }

    public int alphabetSize() {
        return ALPHABET_SIZE;
    }

    public int transition(int state, int ch) {
        if ( ch < 0 || ch >= ALPHABET_SIZE) {
        return nonAsciiTransition[state];}
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
}
