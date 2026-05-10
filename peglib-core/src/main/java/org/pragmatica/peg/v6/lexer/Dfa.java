package org.pragmatica.peg.v6.lexer;
/**
 * Phase A.3 — immutable DFA produced by {@link DfaBuilder} for the lexer-rules
 * of a grammar. The DFA itself only encodes the transition function and accept
 * metadata. Longest-match semantics are implemented by the lexer driver:
 *
 * <pre>
 *   int state = Dfa.START_STATE;
 *   int lastAccept = -1;
 *   int lastAcceptEnd = from;
 *   for (int i = from; i &lt; input.length(); i++) {
 *       int ch = input.charAt(i);
 *       int next = (ch &lt; 256) ? dfa.transition(state, ch) : Dfa.NO_TRANSITION;
 *       if (next == Dfa.NO_TRANSITION) break;
 *       state = next;
 *       int kind = dfa.acceptKind(state);
 *       if (kind != Dfa.NO_ACCEPT) { lastAccept = kind; lastAcceptEnd = i + 1; }
 *   }
 * </pre>
 *
 * <h2>Alphabet</h2>
 *
 * <p>Phase A restricts the DFA alphabet to {@code 0..255} (ASCII + Latin-1).
 * Characters with codepoint &gt; 255 produce {@link #NO_TRANSITION} from any
 * state; the lexer driver routes such characters through a per-codepoint
 * char-level fallback path. This is sufficient for the Java25 grammar bulk
 * and will be extended in a later phase.
 *
 * <h2>Tie-breaking</h2>
 *
 * <p>When two NFA accept states collapse into the same DFA state, the lower
 * priority value wins (= the rule defined earlier in the grammar's source
 * order, matching PEG first-match-wins semantics).
 */
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
        if (ch < 0 || ch >= ALPHABET_SIZE) {
            return NO_TRANSITION;
        }
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
