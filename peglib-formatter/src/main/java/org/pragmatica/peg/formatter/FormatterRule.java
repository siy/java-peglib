package org.pragmatica.peg.formatter;

import org.pragmatica.peg.formatter.Doc;

import java.util.List;

/**
 * Function turning a CST node plus its already-formatted children into a
 * {@link Doc}. Looked up by rule name during the depth-first walk performed
 * by {@link Formatter}.
 *
 * @since 0.6.0
 */
@FunctionalInterface
public interface FormatterRule {
    /** Produce the doc for {@code ctx.nodeIdx()} given its formatted {@code childDocs}. */
    Doc format(FormatContext ctx, List<Doc> childDocs);
}
