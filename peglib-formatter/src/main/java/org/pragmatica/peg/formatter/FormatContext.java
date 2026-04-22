package org.pragmatica.peg.formatter;

import org.pragmatica.peg.tree.CstNode;

/**
 * Context passed to every {@link FormatterRule} invocation.
 *
 * <p>Carries the CST node currently being formatted, the effective
 * {@link TriviaPolicy} for the run, and the renderer configuration
 * (indent / max line width). Immutable.
 *
 * @since 0.3.3
 */
public record FormatContext(CstNode node,
                            int defaultIndent,
                            int maxLineWidth,
                            TriviaPolicy triviaPolicy) {
    public FormatContext {
        if (node == null) {
            throw new IllegalArgumentException("FormatContext.node must not be null");
        }
        if (triviaPolicy == null) {
            throw new IllegalArgumentException("FormatContext.triviaPolicy must not be null");
        }
        if (defaultIndent < 0) {
            throw new IllegalArgumentException("defaultIndent must be >= 0");
        }
        if (maxLineWidth <= 0) {
            throw new IllegalArgumentException("maxLineWidth must be > 0");
        }
    }

    /** Derive a new context for a child node. */
    public FormatContext forNode(CstNode child) {
        return new FormatContext(child, defaultIndent, maxLineWidth, triviaPolicy);
    }
}
