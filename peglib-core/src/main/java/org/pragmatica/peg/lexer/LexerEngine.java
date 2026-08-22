package org.pragmatica.peg.lexer;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.pragmatica.peg.grammar.NestingPair;
import org.pragmatica.peg.token.NestingScanner;
import org.pragmatica.peg.token.TokenArray;
import org.pragmatica.peg.token.TokenArrayBuilder;


/**
 * Phase A.4 — interpreted lexer driver. Drives a {@link Dfa} over an input string with
 * longest-match semantics and emits a {@link TokenArray}. Library-side counterpart to
 * the source-generated {@link org.pragmatica.peg.generator.LexerGenerator GLexer};
 * the two paths are kept algorithmically identical so a parity test can byte-compare
 * their token output.
 *
 * <h2>Algorithm</h2>
 *
 * <p>Standard maximal-munch scan. From each position {@code pos} the driver follows
 * DFA transitions while remembering the latest accepting state encountered; on
 * stall it emits one token spanning {@code [pos, lastAcceptEnd)} with the
 * remembered kind, then resumes from {@code lastAcceptEnd}.
 *
 * <h2>Phase B.0 keyword resolution</h2>
 *
 * <p>If the matched token's kind has an entry in {@code keywordResolutions}, the
 * engine looks up the matched text and remaps the kind to a specific keyword
 * kind when present. This handles the {@code Identifier <- !Keyword <body>}
 * idiom common in Java-style grammars: the DFA accepts the identifier shape
 * uniformly and the engine demotes specific texts to their keyword kinds.
 *
 * <h2>Empty-match safety</h2>
 *
 * <p>If a LEXER rule body matches the empty string (e.g. {@code [a-z]*}), the DFA's
 * start state itself is accepting. A naive longest-match would emit zero-width
 * tokens at every position and loop forever. The driver detects {@code lastAcceptEnd
 * == pos} (zero-length match) and fails with the same diagnostic as no-progress;
 * empty-match LEXER rules are considered ill-formed for Phase A.
 *
 * <h2>Alphabet</h2>
 *
 * <p>The DFA's ASCII transition table is defined over {@code 0..255}. For input
 * characters {@code >= 256} (non-ASCII / BMP-plus), the lexer consults a parallel
 * per-state {@code nonAsciiTransition} slot populated by the builder whenever the
 * state's NFA closure contains an edge that accepts non-ASCII characters
 * (Any {@code .} or negated CharClass {@code [^...]}). Positive character classes
 * like {@code [a-z]} stay ASCII-only.
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
public final class LexerEngine {
    private final Dfa dfa;
    private final String[] kindNameTable;
    private final int whitespaceKind;
    private final Map<Integer, DfaBuilder.KeywordResolution> keywordResolutions;
    // 0.7.3 — %nest delimiter pairs, flattened into parallel arrays and resolved to their
    // trivia kinds once at construction. Flat arrays rather than a NestingPair[] so the
    // per-token reject test touches one primitive array and no object header; `nestFirst`
    // holds each open delimiter's first character so that test is a single char compare,
    // and String.startsWith is reached only by a position that already matched it.
    private final char[] nestFirst;
    private final String[] nestOpen;
    private final String[] nestClose;

    private final int[] nestKind;

    /**
     * @param dfa                  compiled lexer DFA
     * @param kindNameTable        name-per-kind table (index = kind id; reserved trivia kinds at 0..2)
     * @param whitespaceKind       {@link DfaBuilder#KIND_WHITESPACE} when the grammar declares
     *                             a {@code %whitespace} directive; {@code -1} otherwise
     * @param keywordResolutions   per-kind {@code text → kind} remappers used after a match;
     *                             may be empty
     */
    public LexerEngine(Dfa dfa,
                       String[] kindNameTable,
                       int whitespaceKind,
                       Map<Integer, DfaBuilder.KeywordResolution> keywordResolutions) {
        this(dfa, kindNameTable, whitespaceKind, keywordResolutions, List.of());
    }

    /**
     * @param nestingTrivia {@code %nest} delimiter pairs; empty for a grammar that declares
     *                      none, which is the case this constructor is tuned for
     *
     * @since 0.7.3
     */
    public LexerEngine(Dfa dfa,
                       String[] kindNameTable,
                       int whitespaceKind,
                       Map<Integer, DfaBuilder.KeywordResolution> keywordResolutions,
                       List<NestingPair> nestingTrivia) {
        // Internal constructor: callers (DfaBuilder pipeline, tests) pass validated
        // inputs. Defensive null checks omitted by JBCT policy.
        this.dfa = dfa;
        this.kindNameTable = kindNameTable.clone();
        this.whitespaceKind = whitespaceKind;
        this.keywordResolutions = keywordResolutions.isEmpty()
                                  ? Map.of()
                                  : Map.copyOf(keywordResolutions);
        int nestCount = nestingTrivia.size();

        this.nestFirst = new char[nestCount];
        this.nestOpen = new String[nestCount];
        this.nestClose = new String[nestCount];
        this.nestKind = new int[nestCount];
        for (int i = 0; i < nestCount; i++) {
            var pair = nestingTrivia.get(i);

            this.nestOpen[i] = pair.open();
            this.nestClose[i] = pair.close();
            this.nestFirst[i] = pair.open().charAt(0);
            // Resolved here, not per token: the kind a %nest pair emits is a property of its
            // open delimiter and never of the input. Asking DfaBuilder keeps it identical to
            // the kind the equivalent %whitespace alternative would have been given.
            this.nestKind[i] = DfaBuilder.triviaKindForPrefix(pair.open());
        }
    }

    public int whitespaceKind() {
        return whitespaceKind;
    }

    public String[] kindNameTable() {
        return kindNameTable.clone();
    }

    /**
     * Lex {@code input} into a {@link TokenArray}. On a no-transition stall the engine
     * emits a one-character WHITESPACE token to make progress; the downstream parser
     * surfaces such bytes as trailing-input diagnostics. Phase A has no formal recovery;
     * deeper recovery is Phase B.
     */
    public TokenArray lex(String input) {
        var builder = new TokenArrayBuilder(input);
        int len = input.length();
        int pos = 0;

        while (pos < len) {
            int lastAcceptEnd = -1;
            int lastAcceptKind = -1;
            // 0.7.3 — nesting trivia. A %nest open delimiter at a token start is consumed by
            // the depth counter INSTEAD OF the DFA, not in addition to it, so a nesting
            // comment is scanned exactly once. Testing only at a token start is also what
            // makes an open delimiter inside a string literal safe: the string rule's token
            // starts at the quote and swallows the delimiter, so the scanner never sees it.
            int nested = nestingOpenAt(input, pos);

            if (nested >= 0) {
                int end = NestingScanner.scanEnd(input, pos, nestOpen[nested], nestClose[nested]);
                // An unterminated block falls through to the DFA rather than consuming to end
                // of input. That keeps malformed input reading exactly as it did before the
                // grammar declared %nest, so the directive can only change the reading of
                // comments that actually balance.
                if (end != NestingScanner.UNTERMINATED) {
                    lastAcceptEnd = end;
                    lastAcceptKind = nestKind[nested];
                }
            }

            if (lastAcceptEnd < 0) {
                int state = Dfa.START_STATE;
                int cur = pos;

                while (cur < len) {
                    int ch = input.charAt(cur);
                    int next;

                    if (ch >= Dfa.ALPHABET_SIZE) {
                        next = dfa.nonAsciiTransition(state);
                    } else {
                        next = dfa.transition(state, ch);
                    }

                    if (next == Dfa.NO_TRANSITION) {
                        break;
                    }

                    state = next;
                    cur++;
                    int ak = dfa.acceptKind(state);
                    // A rule ending in a lookahead constrains the character after the match. A denied
                    // accept is simply not recorded, so maximal munch carries on from the last one that
                    // was — which is what lets a longer rule win where the guarded one is refused.
                    if (ak != Dfa.NO_ACCEPT && dfa.acceptAllowsFollower(state,
                                                                        cur < len
                                                                        ? input.charAt(cur)
                                                                        : Dfa.EOF)) {
                        lastAcceptEnd = cur;
                        lastAcceptKind = ak;
                    }
                }
            }

            if (lastAcceptEnd <= pos) {
                // No DFA-recognised token at this position. Emit a 1-char synthetic
                // WHITESPACE token so the input is fully covered and lexing can
                // progress; the parser will surface this as a trailing-input error.
                builder.append(TokenArray.KIND_WHITESPACE, pos, pos + 1);
                pos++;
                continue;
            }
            // Phase B.0 keyword resolution — remap identifier kinds to keyword kinds when applicable.
            var resolver = keywordResolutions.get(lastAcceptKind);

            if (resolver != null) {
                var override = resolver.textToKind().get(input.substring(pos, lastAcceptEnd));

                if (override != null) {
                    lastAcceptKind = override;
                }
            }
            // Phase A.6 / 0.6.1 / 0.6.2 — content-based trivia refinement. Two
            // entry conditions:
            //   1. legacy fallback: a coalesced WHITESPACE token whose text begins
            //      with a comment prefix (folded-%whitespace path that did not go
            //      through structural per-alternative absorption);
            //   2. structural base kind: DfaBuilder.absorbWhitespace already
            //      assigned LINE_COMMENT / BLOCK_COMMENT structurally, but the
            //      grammar's single alternative cannot distinguish the doc variant
            //      (/// vs //, /** vs /*) — only the matched text can.
            // In both cases the matched span text is inspected to pick the (regular
            // or doc) line/block-comment kind. Pure whitespace runs never start
            // with '/', so the WHITESPACE branch is a sound prefix check.
            //   //         → LINE_COMMENT
            //   ///        → DOC_LINE_COMMENT      (3 or more slashes)
            //   /* ... */  → BLOCK_COMMENT
            //   /** ... */ → DOC_BLOCK_COMMENT     (NOT the smallest empty block /**/)
            if ((lastAcceptKind == TokenArray.KIND_WHITESPACE || lastAcceptKind == TokenArray.KIND_LINE_COMMENT || lastAcceptKind == TokenArray.KIND_BLOCK_COMMENT) && lastAcceptEnd > pos + 1) {
                char c0 = input.charAt(pos);
                char c1 = input.charAt(pos + 1);

                if (c0 == '/') {
                    if (c1 == '/') {
                        // Line comment. Doc-line variant requires a third '/'.
                        if (lastAcceptEnd > pos + 2 && input.charAt(pos + 2) == '/') {
                            lastAcceptKind = TokenArray.KIND_DOC_LINE_COMMENT;
                        } else {
                            lastAcceptKind = TokenArray.KIND_LINE_COMMENT;
                        }
                    } else if (c1 == '*') {
                        // Block comment. Doc-block variant requires '/**' followed by
                        // anything except a closing '/'. The 4-char empty block '/**/'
                        // is the smallest regular block comment, NOT Javadoc.
                        boolean isDoc = lastAcceptEnd > pos + 2 && input.charAt(pos + 2) == '*' && !(lastAcceptEnd == pos + 4 && input.charAt(pos + 3) == '/');

                        lastAcceptKind = isDoc
                                         ? TokenArray.KIND_DOC_BLOCK_COMMENT
                                         : TokenArray.KIND_BLOCK_COMMENT;
                    }
                }
            }

            builder.append(lastAcceptKind, pos, lastAcceptEnd);
            pos = lastAcceptEnd;
        }

        return builder.build(kindNameTable);
    }

    /**
     * Index of the {@code %nest} pair whose open delimiter sits at {@code pos}, or {@code -1}.
     *
     * <p>Cost matters here — this runs once per token, on the parse hot path. For a grammar
     * that declares no {@code %nest} (every grammar before 0.7.3, and every Java one) the
     * array is empty, the loop does not execute, and the whole method is a hoistable length
     * compare. For a grammar that does declare one, a position is rejected on a single char
     * compare against the delimiter's first character; {@code startsWith} is reached only
     * where that character already matched, which for {@code /*} means only at a slash.
     *
     * @since 0.7.3
     */
    private int nestingOpenAt(String input, int pos) {
        if (nestFirst.length == 0) {
            return -1;
        }

        char c = input.charAt(pos);

        for (int i = 0; i < nestFirst.length; i++) {
            if (nestFirst[i] == c && input.startsWith(nestOpen[i], pos)) {
                return i;
            }
        }

        return -1;
    }

    /**
     * Convenience helper used by tests and callers that build a context but
     * don't yet need keyword resolution. Equivalent to passing
     * {@link Map#of()} as the keyword resolutions map.
     *
     * @deprecated prefer the four-arg constructor; kept as a transitional API
     *     while older call sites are updated.
     */
    @Deprecated
    public static LexerEngine withoutKeywordResolution(Dfa dfa, String[] kindNameTable, int whitespaceKind) {
        return new LexerEngine(dfa, kindNameTable, whitespaceKind, new HashMap<>());
    }
}
