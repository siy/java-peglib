package org.pragmatica.peg.tree;
/**
 * Trivia represents non-semantic content: whitespace and comments.
 * Grouped for convenience in CST representation.
 */
public sealed interface Trivia {
    SourceSpan span();

    String text();

    record Whitespace(SourceSpan span, String text) implements Trivia {}

    record LineComment(SourceSpan span, String text) implements Trivia {}

    record BlockComment(SourceSpan span, String text) implements Trivia {}
}
