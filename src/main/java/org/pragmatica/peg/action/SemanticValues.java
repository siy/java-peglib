package org.pragmatica.peg.action;

import org.pragmatica.lang.Option;
import org.pragmatica.peg.tree.SourceSpan;

import java.util.List;

/**
 * Semantic values passed to actions during parsing.
 * Provides access to matched text, child values, and choice information.
 */
public final class SemanticValues {

    private final String matchedText;
    private final SourceSpan span;
    private final List<Object> values;
    private final int choice;

    private SemanticValues(String matchedText, SourceSpan span, List<Object> values, int choice) {
        this.matchedText = matchedText;
        this.span = span;
        this.values = values;
        this.choice = choice;
    }

    public static SemanticValues of(String matchedText, SourceSpan span, List<Object> values) {
        return new SemanticValues(matchedText, span, values, 0);
    }

    public static SemanticValues of(String matchedText, SourceSpan span, List<Object> values, int choice) {
        return new SemanticValues(matchedText, span, values, choice);
    }

    /**
     * Get the full matched text (token text for token boundary).
     * Accessible as $0 in actions.
     */
    public String token() {
        return matchedText;
    }

    /**
     * Alias for token().
     */
    public String str() {
        return matchedText;
    }

    /**
     * Parse matched text as integer.
     */
    public int toInt() {
        return Integer.parseInt(matchedText.trim());
    }

    /**
     * Parse matched text as long.
     */
    public long toLong() {
        return Long.parseLong(matchedText.trim());
    }

    /**
     * Parse matched text as double.
     */
    public double toDouble() {
        return Double.parseDouble(matchedText.trim());
    }

    /**
     * Get source span of the match.
     */
    public SourceSpan span() {
        return span;
    }

    /**
     * Get the number of child values.
     */
    public int size() {
        return values.size();
    }

    /**
     * Check if there are child values.
     */
    public boolean isEmpty() {
        return values.isEmpty();
    }

    /**
     * Get child value by index.
     * Accessible as $1, $2, etc. in actions.
     */
    @SuppressWarnings("unchecked")
    public <T> T get(int index) {
        return (T) values.get(index);
    }

    /**
     * Get child value by index, with default.
     */
    @SuppressWarnings("unchecked")
    public <T> T getOrDefault(int index, T defaultValue) {
        if (index < 0 || index >= values.size()) {
            return defaultValue;
        }
        var value = values.get(index);
        return value != null ? (T) value : defaultValue;
    }

    /**
     * Get child value as Option.
     */
    @SuppressWarnings("unchecked")
    public <T> Option<T> getOpt(int index) {
        if (index < 0 || index >= values.size()) {
            return Option.none();
        }
        return Option.option((T) values.get(index));
    }

    /**
     * Get all child values.
     */
    public List<Object> values() {
        return values;
    }

    /**
     * Get which alternative matched (for choice expressions).
     * 0-indexed.
     */
    public int choice() {
        return choice;
    }

    /**
     * Transform all child values to a specific type.
     */
    @SuppressWarnings("unchecked")
    public <T> List<T> transform() {
        return values.stream()
            .map(v -> (T) v)
            .toList();
    }

    @Override
    public String toString() {
        return "SemanticValues{token='" + matchedText + "', values=" + values + ", choice=" + choice + "}";
    }
}
