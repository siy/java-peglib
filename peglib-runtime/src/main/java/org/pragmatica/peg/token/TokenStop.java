package org.pragmatica.peg.token;

/**
 * Tells a resumable lexer where to stop: asked at each token start, before that token is lexed.
 *
 * @since 0.7.5
 */
@FunctionalInterface
public interface TokenStop {
    boolean stopBefore(int position);
}
