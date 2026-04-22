package org.pragmatica.peg.grammar;

import org.pragmatica.lang.Option;
import org.pragmatica.peg.tree.SourceSpan;

/**
 * A grammar rule: Name <- Expression { action } { error_message "..." }
 *
 * <p>Additionally supports optional 0.2.4 directives:
 * <ul>
 *   <li>{@code %expected "label"} — semantic expected-label for failure diagnostics</li>
 *   <li>{@code %recover '<term>'} — per-rule recovery terminator overriding the global set</li>
 *   <li>{@code %tag "name"} — diagnostic tag emitted for this rule's failures</li>
 * </ul>
 * These features are strictly additive: when all three are {@link Option#none()},
 * engine and generator produce byte-identical output to pre-0.2.4 behaviour.
 */
public record Rule(
 SourceSpan span,
 String name,
 Expression expression,
 Option<String> action,
 Option<String> errorMessage,
 Option<String> expected,
 Option<String> recover,
 Option<String> tag) {
    /**
     * Backwards-compatible constructor matching the pre-0.2.4 signature.
     */
    public Rule(SourceSpan span,
                String name,
                Expression expression,
                Option<String> action,
                Option<String> errorMessage) {
        this(span, name, expression, action, errorMessage, Option.none(), Option.none(), Option.none());
    }

    public boolean hasAction() {
        return action.isPresent();
    }

    public boolean hasErrorMessage() {
        return errorMessage.isPresent();
    }

    public boolean hasExpected() {
        return expected.isPresent();
    }

    public boolean hasRecover() {
        return recover.isPresent();
    }

    public boolean hasTag() {
        return tag.isPresent();
    }
}
