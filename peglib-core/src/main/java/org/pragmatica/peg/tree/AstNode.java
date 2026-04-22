package org.pragmatica.peg.tree;

import org.pragmatica.lang.Option;

import java.util.List;

/**
 * Abstract Syntax Tree node - semantic representation without trivia.
 * Optimized for interpretation and compilation.
 */
public sealed interface AstNode {
    /**
     * The source span (for error reporting).
     */
    SourceSpan span();

    /**
     * The rule name that produced this node.
     */
    String rule();

    /**
     * Semantic value computed by action (if any).
     */
    Option<Object> value();

    /**
     * Terminal AST node - a leaf with text.
     */
    record Terminal(
    SourceSpan span,
    String rule,
    String text,
    Option<Object> value) implements AstNode {}

    /**
     * Non-terminal AST node - interior node with children.
     */
    record NonTerminal(
    SourceSpan span,
    String rule,
    List<AstNode> children,
    Option<Object> value) implements AstNode {}
}
