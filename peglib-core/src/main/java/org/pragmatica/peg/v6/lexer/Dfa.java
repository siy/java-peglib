package org.pragmatica.peg.v6.lexer;
public final class Dfa {
    public static final int START_STATE = 0;
    public static final int NO_TRANSITION = - 1;
    public static final int NO_ACCEPT = - 1;
    public static final int ALPHABET_SIZE = 256;

    private final int[][] transitions;
    private final int[] acceptKind;
    private final int[] acceptPriority;

    Dfa(int[][] transitions, int[] acceptKind, int[] acceptPriority) {
        this.transitions = transitions;
        this.acceptKind = acceptKind;
        this.acceptPriority = acceptPriority;
    }

    public int stateCount() {
        return transitions.length;
    }

    public int alphabetSize() {
        return ALPHABET_SIZE;
    }

    public int transition(int state, int ch) {
        if ( ch < 0 || ch >= ALPHABET_SIZE) {
        return NO_TRANSITION;}
        return transitions[state][ch];
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
}
