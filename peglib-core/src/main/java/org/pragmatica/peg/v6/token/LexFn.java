package org.pragmatica.peg.v6.token;
/**
 * Functional adapter exposing a single {@code String -> TokenArray} entry point. Used by
 * {@link TokenArray#spliceLex(LexFn, int, int, String)} so callers may bridge either the
 * interpreted {@link org.pragmatica.peg.v6.lexer.LexerEngine} or the source-generated
 * {@code CompiledLexer} without dragging the concrete type into the
 * {@code v6.token} package.
 */
@FunctionalInterface
public interface LexFn {
    TokenArray lex(String input);
}
