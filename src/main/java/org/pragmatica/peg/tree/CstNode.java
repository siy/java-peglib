package org.pragmatica.peg.tree;

import java.util.List;

/**
 * Concrete Syntax Tree node - lossless representation preserving all source details.
 * Can be used for formatting, linting, and round-trip transformations.
 */
public sealed interface CstNode {

    /**
     * The source span covered by this node (excluding trivia).
     */
    SourceSpan span();

    /**
     * The rule name that produced this node.
     */
    String rule();

    /**
     * Leading trivia (whitespace/comments before this node).
     */
    List<Trivia> leadingTrivia();

    /**
     * Trailing trivia (whitespace/comments after this node).
     */
    List<Trivia> trailingTrivia();

    /**
     * Terminal node - a leaf that matched literal text.
     */
    record Terminal(
        SourceSpan span,
        String rule,
        String text,
        List<Trivia> leadingTrivia,
        List<Trivia> trailingTrivia
    ) implements CstNode {}

    /**
     * Non-terminal node - an interior node with children.
     */
    record NonTerminal(
        SourceSpan span,
        String rule,
        List<CstNode> children,
        List<Trivia> leadingTrivia,
        List<Trivia> trailingTrivia
    ) implements CstNode {}

    /**
     * Token node - result of token boundary operator {@code < >}.
     * Captures the matched text as a single unit.
     */
    record Token(
        SourceSpan span,
        String rule,
        String text,
        List<Trivia> leadingTrivia,
        List<Trivia> trailingTrivia
    ) implements CstNode {}
}
