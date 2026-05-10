package org.pragmatica.peg.formatter.v6;

import org.pragmatica.peg.v6.cst.CstArray;
import org.pragmatica.peg.v6.cst.CstNode;

/**
 * Context passed to every {@link V6FormatterRule} invocation.
 *
 * <p>Carries the underlying {@link CstArray}, the index of the node currently
 * being formatted, and the renderer / trivia configuration. Immutable.
 *
 * @since 0.6.0
 */
public record V6FormatContext(CstArray cst,
                              int nodeIdx,
                              int defaultIndent,
                              int maxLineWidth,
                              V6TriviaPolicy triviaPolicy) {
    public V6FormatContext {
        if (cst == null) {
            throw new IllegalArgumentException("V6FormatContext.cst must not be null");
        }
        if (triviaPolicy == null) {
            throw new IllegalArgumentException("V6FormatContext.triviaPolicy must not be null");
        }
        if (nodeIdx < 0 || nodeIdx >= cst.nodeCount()) {
            throw new IllegalArgumentException(
                "nodeIdx=" + nodeIdx + " out of bounds [0, " + cst.nodeCount() + ")");
        }
        if (defaultIndent < 0) {
            throw new IllegalArgumentException("defaultIndent must be >= 0");
        }
        if (maxLineWidth <= 0) {
            throw new IllegalArgumentException("maxLineWidth must be > 0");
        }
    }

    /** Derive a new context for {@code childIdx}, sharing all other settings. */
    public V6FormatContext forNode(int childIdx) {
        return new V6FormatContext(cst, childIdx, defaultIndent, maxLineWidth, triviaPolicy);
    }

    /** Source text covered by the current node (excluding surrounding trivia). */
    public CharSequence nodeText() {
        return cst.textAt(nodeIdx);
    }

    /** Rule name of the current node. */
    public String ruleName() {
        return cst.kindNameAt(nodeIdx);
    }

    /** Sealed view ({@link CstNode.Branch}/{@link CstNode.Leaf}/{@link CstNode.Error}) of the current node. */
    public CstNode view() {
        return cst.viewAt(nodeIdx);
    }
}
