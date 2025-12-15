package org.pragmatica.peg.grammar;

import org.pragmatica.lang.Option;
import org.pragmatica.peg.tree.SourceSpan;

/**
 * A grammar rule: Name <- Expression { action }
 */
public record Rule(
    SourceSpan span,
    String name,
    Expression expression,
    Option<String> action
) {
    public boolean hasAction() {
        return action.isPresent();
    }
}
