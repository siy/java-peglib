package org.pragmatica.peg.action;

import org.pragmatica.lang.Option;
import org.pragmatica.peg.tree.SourceSpan;

import java.util.List;

/**
 * Semantic values passed to actions during parsing.
 * Provides access to matched text and child values.
 */
public final class SemanticValues {
    private final String matchedText;
    private final SourceSpan span;
    private final List<Object> values;

    private SemanticValues(String matchedText, SourceSpan span, List<Object> values) {
        this.matchedText = matchedText;
        this.span = span;
        this.values = values;
    }

    public static SemanticValues of(String matchedText, SourceSpan span, List<Object> values) {
        return new SemanticValues(matchedText, span, values);
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
     *
     * <p>Note: This method uses unchecked cast intentionally for action DSL compatibility.
     * User actions know the expected types at the call site. For type-safe access,
     * use {@link #getOpt(int)} or {@link #get(int, Class)}.
     */
    @SuppressWarnings("unchecked")
    public <T> T get(int index) {
        return (T) values.get(index);
    }

    /**
     * Get child value by index with type checking.
     * Returns Option.none() if index is out of bounds or type doesn't match.
     *
     * @param index the index of the child value
     * @param type the expected type class
     * @return Option containing the value if present and of correct type
     */
    public <T> Option<T> get(int index, Class<T> type) {
        if (index < 0 || index >= values.size()) {
            return Option.none();
        }
        var value = values.get(index);
        if (type.isInstance(value)) {
            return Option.some(type.cast(value));
        }
        return Option.none();
    }

    /**
     * Get child value by index, with default if out of bounds.
     *
     * <p>Note: Uses Option internally for null-safety. Returns defaultValue
     * if index is out of bounds OR if the value is null.
     */
    @SuppressWarnings("unchecked")
    public <T> T getOrDefault(int index, T defaultValue) {
        var opt = this.<T>getOpt(index);
        return opt.isPresent()
               ? opt.unwrap()
               : defaultValue;
    }

    /**
     * Get child value as Option.
     * This is the recommended JBCT-compliant way to access values safely.
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
        return "SemanticValues{token='" + matchedText + "', values=" + values + "}";
    }
}
