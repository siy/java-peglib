package org.pragmatica.peg.grammar;

import org.pragmatica.lang.Option;
import org.pragmatica.peg.tree.SourceSpan;

import java.util.List;

/**
 * PEG expression types - the building blocks of grammar rules.
 */
public sealed interface Expression {

    /**
     * Source location of this expression in the grammar.
     */
    SourceSpan span();

    // === Terminals ===

    /**
     * Literal string match: 'text' or "text"
     */
    record Literal(SourceSpan span, String text, boolean caseInsensitive) implements Expression {}

    /**
     * Character class: [a-z], [^a-z]
     */
    record CharClass(SourceSpan span, String pattern, boolean negated, boolean caseInsensitive) implements Expression {}

    /**
     * Any character: .
     */
    record Any(SourceSpan span) implements Expression {}

    /**
     * Rule reference: RuleName
     */
    record Reference(SourceSpan span, String ruleName) implements Expression {}

    // === Combinators ===

    /**
     * Sequence: e1 e2 e3
     */
    record Sequence(SourceSpan span, List<Expression> elements) implements Expression {}

    /**
     * Ordered choice: e1 / e2 / e3
     */
    record Choice(SourceSpan span, List<Expression> alternatives) implements Expression {}

    // === Repetition ===

    /**
     * Zero or more: e*
     */
    record ZeroOrMore(SourceSpan span, Expression expression) implements Expression {}

    /**
     * One or more: e+
     */
    record OneOrMore(SourceSpan span, Expression expression) implements Expression {}

    /**
     * Optional: e?
     */
    record Optional(SourceSpan span, Expression expression) implements Expression {}

    /**
     * Repetition with bounds: e{n}, e{n,}, e{n,m}
     */
    record Repetition(SourceSpan span, Expression expression, int min, Option<Integer> max) implements Expression {}

    // === Predicates ===

    /**
     * Positive lookahead: &e
     */
    record And(SourceSpan span, Expression expression) implements Expression {}

    /**
     * Negative lookahead: !e
     */
    record Not(SourceSpan span, Expression expression) implements Expression {}

    // === Special ===

    /**
     * Token boundary: < e > - captures matched text
     */
    record TokenBoundary(SourceSpan span, Expression expression) implements Expression {}

    /**
     * Ignore: ~e - discards semantic value
     */
    record Ignore(SourceSpan span, Expression expression) implements Expression {}

    /**
     * Named capture: $name< e >
     */
    record Capture(SourceSpan span, String name, Expression expression) implements Expression {}

    /**
     * Capture scope: $( e ) - isolates captures within the expression
     */
    record CaptureScope(SourceSpan span, Expression expression) implements Expression {}

    /**
     * Back-reference: $name
     */
    record BackReference(SourceSpan span, String name) implements Expression {}

    /**
     * Dictionary: 'word1' | 'word2' | 'word3' - efficient Trie-based string matching
     */
    record Dictionary(SourceSpan span, List<String> words, boolean caseInsensitive) implements Expression {}

    /**
     * Cut operator: ↑ - commits to current choice
     */
    record Cut(SourceSpan span) implements Expression {}

    /**
     * Grouping: ( e )
     */
    record Group(SourceSpan span, Expression expression) implements Expression {}
}
