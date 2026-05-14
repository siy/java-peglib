package org.pragmatica.peg.v6.lexer;

import org.pragmatica.peg.v6.token.TokenArray;
import org.pragmatica.peg.v6.token.TokenArrayBuilder;

import java.util.HashMap;
import java.util.Map;

/**
 * Phase A.4 — interpreted lexer driver. Drives a {@link Dfa} over an input string with
 * longest-match semantics and emits a {@link TokenArray}. Library-side counterpart to
 * the source-generated {@link org.pragmatica.peg.v6.generator.LexerGenerator GLexer};
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
public final class LexerEngine {
    private final Dfa dfa;
    private final String[] kindNameTable;
    private final int whitespaceKind;
    private final Map<Integer, DfaBuilder.KeywordResolution> keywordResolutions;

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
        // Internal constructor: callers (DfaBuilder pipeline, tests) pass validated
        // inputs. Defensive null checks omitted by JBCT policy.
        this.dfa = dfa;
        this.kindNameTable = kindNameTable.clone();
        this.whitespaceKind = whitespaceKind;
        this.keywordResolutions = keywordResolutions.isEmpty()
                                  ? Map.of()
                                  : Map.copyOf(keywordResolutions);
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
        while ( pos < len) {
            int state = Dfa.START_STATE;
            int lastAcceptEnd = - 1;
            int lastAcceptKind = - 1;
            int cur = pos;
            while ( cur < len) {
                int ch = input.charAt(cur);
                int next;
                if ( ch >= Dfa.ALPHABET_SIZE) {
                next = dfa.nonAsciiTransition(state);} else
                {
                next = dfa.transition(state, ch);}
                if ( next == Dfa.NO_TRANSITION) {
                break;}
                state = next;
                cur++;
                int ak = dfa.acceptKind(state);
                if ( ak != Dfa.NO_ACCEPT) {
                    lastAcceptEnd = cur;
                    lastAcceptKind = ak;
                }
            }
            if ( lastAcceptEnd <= pos) {
                // No DFA-recognised token at this position. Emit a 1-char synthetic
                // WHITESPACE token so the input is fully covered and lexing can
                // progress; the parser will surface this as a trailing-input error.
                builder.append(TokenArray.KIND_WHITESPACE, pos, pos + 1);
                pos++;
                continue;
            }
            // Phase B.0 keyword resolution — remap identifier kinds to keyword kinds when applicable.
            var resolver = keywordResolutions.get(lastAcceptKind);
            if ( resolver != null) {
                var override = resolver.textToKind().get(input.substring(pos, lastAcceptEnd));
                if ( override != null) {
                lastAcceptKind = override;}
            }
            // Phase A.6 / 0.6.1 — content-based trivia classification. WHITESPACE tokens
            // whose text begins with a comment prefix are reclassified into the matching
            // (regular or doc) line/block-comment kind. Pure whitespace runs never start
            // with '/', so this is a sound prefix check.
            //   //         → LINE_COMMENT          (also /+ that isn't doc; see below)
            //   ///        → DOC_LINE_COMMENT      (3 or more slashes)
            //   /* ... */  → BLOCK_COMMENT
            //   /** ... */ → DOC_BLOCK_COMMENT     (NOT the smallest empty block /**/)
            if ( lastAcceptKind == TokenArray.KIND_WHITESPACE && lastAcceptEnd > pos + 1) {
                char c0 = input.charAt(pos);
                char c1 = input.charAt(pos + 1);
                if ( c0 == '/') {
                    if ( c1 == '/') {
                        // Line comment. Doc-line variant requires a third '/'.
                        if ( lastAcceptEnd > pos + 2 && input.charAt(pos + 2) == '/') {
                            lastAcceptKind = TokenArray.KIND_DOC_LINE_COMMENT;
                        } else {
                            lastAcceptKind = TokenArray.KIND_LINE_COMMENT;
                        }
                    } else if ( c1 == '*') {
                        // Block comment. Doc-block variant requires '/**' followed by
                        // anything except a closing '/'. The 4-char empty block '/**/'
                        // is the smallest regular block comment, NOT Javadoc.
                        boolean isDoc =
                            lastAcceptEnd > pos + 2
                            && input.charAt(pos + 2) == '*'
                            && !(lastAcceptEnd == pos + 4 && input.charAt(pos + 3) == '/');
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
     * Convenience helper used by tests and callers that build a context but
     * don't yet need keyword resolution. Equivalent to passing
     * {@link Map#of()} as the keyword resolutions map.
     *
     * @deprecated prefer the four-arg constructor; kept as a transitional API
     *     while older call sites are updated.
     */
    @Deprecated public static LexerEngine withoutKeywordResolution(Dfa dfa,
                                                                   String[] kindNameTable,
                                                                   int whitespaceKind) {
        return new LexerEngine(dfa, kindNameTable, whitespaceKind, new HashMap<>());
    }
}
