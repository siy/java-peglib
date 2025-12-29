package org.pragmatica.peg.grammar;

import org.pragmatica.lang.Option;
import org.pragmatica.peg.tree.SourceSpan;

/**
 * A grammar rule: Name <- Expression { action } { error_message "..." }
 */
public record Rule(
 SourceSpan span,
 String name,
 Expression expression,
 Option<String> action,
 Option<String> errorMessage) {
    public boolean hasAction() {
        return action.isPresent();
    }

    public boolean hasErrorMessage() {
        return errorMessage.isPresent();
    }
}
