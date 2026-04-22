package org.pragmatica.peg.formatter;

import java.util.List;

/**
 * A function that turns a CST node plus its already-formatted children into a
 * {@link Doc}.
 *
 * <p>The formatter walks the CST depth-first: for each node it first recurses
 * on every child, collecting the resulting {@link Doc}s in order, then looks
 * up the rule by {@link org.pragmatica.peg.tree.CstNode#rule() node.rule()}
 * and invokes {@link #format(FormatContext, List)} with the child docs.
 *
 * <p>A rule's implementation decides how to arrange the child docs —
 * typically via the {@link Docs} static builders. Children absent from a
 * rule's output are effectively dropped; children duplicated are emitted
 * multiple times.
 *
 * @since 0.3.3
 */
@FunctionalInterface
public interface FormatterRule {
    /**
     * Produce the {@link Doc} for {@code ctx.node()} given its already-
     * formatted {@code childDocs} (in child order).
     */
    Doc format(FormatContext ctx, List<Doc> childDocs);
}
