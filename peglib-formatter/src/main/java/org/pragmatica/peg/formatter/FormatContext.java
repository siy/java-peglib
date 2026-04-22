package org.pragmatica.peg.formatter;

import org.pragmatica.peg.tree.CstNode;

/**
 * Context passed to every {@link FormatterRule} invocation.
 *
 * <p>Carries the CST node currently being formatted, the original source
 * buffer the CST was parsed from, the effective {@link TriviaPolicy} for
 * the run, and the renderer configuration (indent / max line width).
 * Immutable.
 *
 * <p>Rules can recover the exact source text for any node via
 * {@link CstNode#span()}.extract(source()) — useful for passing raw tokens
 * through verbatim.
 *
 * @since 0.3.3
 */
public record FormatContext(CstNode node,
                            String source,
                            int defaultIndent,
                            int maxLineWidth,
                            TriviaPolicy triviaPolicy) {
    public FormatContext {
        if (node == null) {
            throw new IllegalArgumentException("FormatContext.node must not be null");
        }
        if (source == null) {
            throw new IllegalArgumentException("FormatContext.source must not be null");
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
        return new FormatContext(child, source, defaultIndent, maxLineWidth, triviaPolicy);
    }

    /** The source text covered by the current node (excluding trivia). */
    public String nodeText() {
        var span = node.span();
        int start = Math.max(0, span.start().offset());
        int end = Math.min(source.length(), span.end().offset());
        if (start >= end) {
            return "";
        }
        return source.substring(start, end);
    }
}
