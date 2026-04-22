package org.pragmatica.peg.action;
/**
 * Marker interface for rule identifiers used in programmatic action attachment
 * (0.2.6) and, forward-compat, partial-parse entry points ({@code parseRuleAt},
 * 0.3.0).
 *
 * <p>Conceptually a {@code RuleId} identifies a grammar rule by its sanitized
 * name. Implementations are intended to be parameter-less {@code record}s —
 * identity is conveyed entirely by the class. The {@link #name()} default
 * returns {@link Class#getSimpleName()}, matching the sanitized rule-name
 * convention used by {@link org.pragmatica.peg.generator.ParserGenerator} when
 * emitting marker records for each rule.
 *
 * <p>Usage with the interpreter: define a marker record per rule and key
 * {@link Actions} by its class:
 * <pre>{@code
 * record Number() implements RuleId {}
 * record Sum() implements RuleId {}
 *
 * var actions = Actions.empty()
 *     .with(Number.class, sv -> sv.toInt())
 *     .with(Sum.class, sv -> (Integer) sv.get(0) + (Integer) sv.get(1));
 * }</pre>
 *
 * <p>Generated parsers emit a nested {@code sealed interface RuleId} that
 * extends this interface, with one {@code record} per rule.
 */
public interface RuleId {
    /**
     * Sanitized rule name used to resolve this identifier against a grammar's
     * rule table. Default implementation returns {@link Class#getSimpleName()}.
     */
    default String name() {
        return getClass()
               .getSimpleName();
    }
}
