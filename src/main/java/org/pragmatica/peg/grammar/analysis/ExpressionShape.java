package org.pragmatica.peg.grammar.analysis;

import org.pragmatica.peg.grammar.Expression;

/**
 * Shared expression-shape helpers for PEG grammar analysis.
 *
 * <p>Extracted in 0.2.3 so both {@code ParserGenerator} (generator-time) and
 * {@code PegEngine} (interpreter-time) share a single definition of "inner
 * expression of a whitespace rule" and related shape predicates.
 */
public final class ExpressionShape {

    private ExpressionShape() {}

    /**
     * Extract the inner expression of a repetition wrapper
     * ({@code ZeroOrMore}, {@code OneOrMore}, {@code Optional}). Peels
     * {@code Group} wrappers transitively so the result is the innermost
     * repeated element - the thing that should be matched one element at a
     * time during whitespace/trivia collection.
     *
     * <p>For non-repetition expressions, returns the expression unchanged.
     */
    public static Expression extractInnerExpression(Expression expr) {
        return switch (expr) {
            case Expression.ZeroOrMore zom -> zom.expression();
            case Expression.OneOrMore oom -> oom.expression();
            case Expression.Optional opt -> opt.expression();
            case Expression.Group grp -> extractInnerExpression(grp.expression());
            default -> expr;
        };
    }
}
