package org.pragmatica.peg.token;

/**
 * A lexer that can start at any token boundary of an input and stop early. {@link TokenArray#spliceLex}
 * uses it to re-lex exactly the tokens an edit changed, over the real input; a plain {@link LexFn} is
 * re-lexed over windows of it instead, which repeats work when one token outgrows a window.
 *
 * @since 0.7.5
 */
public interface ResumableLexFn extends LexFn {
    /**
     * Lex {@code input} from {@code from}, which must be a token start, until end of input or until
     * {@code stop} answers true at a token start. Token positions are positions in {@code input}.
     */
    TokenArray lexFrom(String input, int from, TokenStop stop);
}
