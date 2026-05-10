package org.pragmatica.peg.v6.token;
@FunctionalInterface public interface LexFn {
    TokenArray lex(String input);
}
