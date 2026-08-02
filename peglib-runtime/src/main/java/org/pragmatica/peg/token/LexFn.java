package org.pragmatica.peg.token;
@FunctionalInterface public interface LexFn {
    TokenArray lex(String input);
}
