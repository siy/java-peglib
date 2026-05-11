package org.pragmatica.peg.formatter.v6;

import org.pragmatica.peg.formatter.Doc;

import java.util.List;

/**
 * Function turning a v6 CST node plus its already-formatted children into a
 * {@link Doc}. Looked up by rule name during the depth-first walk performed
 * by {@link V6Formatter}.
 *
 * @since 0.6.0
 */
@FunctionalInterface
public interface V6FormatterRule {
    /** Produce the doc for {@code ctx.nodeIdx()} given its formatted {@code childDocs}. */
    Doc format(V6FormatContext ctx, List<Doc> childDocs);
}
