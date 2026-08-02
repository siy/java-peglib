package org.pragmatica.peg.formatter;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.peg.cst.CstArray;
import org.pragmatica.peg.cst.CstNode;


/**
 * Context passed to every {@link FormatterRule} invocation.
 *
 * <p>Carries the underlying {@link CstArray}, the index of the node currently
 * being formatted, and the renderer / trivia configuration. Immutable.
 *
 * <p>Validate at the boundary with {@link #formatContext}; the canonical
 * constructor performs no checks, so internally-derived contexts such as
 * {@link #forNode(int)} cost nothing.
 *
 * @since 0.6.0
 */
public record FormatContext(CstArray cst, int nodeIdx, int defaultIndent, int maxLineWidth, TriviaPolicy triviaPolicy) {
    /** Validating factory. Use this at any boundary where the inputs are not already known good. */
    public static Result<FormatContext> formatContext(CstArray cst,
                                                      int nodeIdx,
                                                      int defaultIndent,
                                                      int maxLineWidth,
                                                      TriviaPolicy triviaPolicy) {
        if (cst == null) {
            return Causes.cause("FormatContext.cst must not be null").result();
        }

        if (triviaPolicy == null) {
            return Causes.cause("FormatContext.triviaPolicy must not be null").result();
        }

        if (nodeIdx < 0 || nodeIdx >= cst.nodeCount()) {
            return Causes.cause("nodeIdx=" + nodeIdx + " out of bounds [0, " + cst.nodeCount() + ")").result();
        }

        if (defaultIndent < 0) {
            return Causes.cause("defaultIndent must be >= 0").result();
        }

        if (maxLineWidth <= 0) {
            return Causes.cause("maxLineWidth must be > 0").result();
        }

        return Result.success(new FormatContext(cst, nodeIdx, defaultIndent, maxLineWidth, triviaPolicy));
    }

    /** Derive a new context for {@code childIdx}, sharing all other settings. */
    public FormatContext forNode(int childIdx) {
        return new FormatContext(cst, childIdx, defaultIndent, maxLineWidth, triviaPolicy);
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
