package org.pragmatica.peg.parser;

import org.pragmatica.lang.Option;

import java.util.List;

/**
 * Parameterizes parsing behavior to consolidate 3 parallel parsing paths.
 *
 * <p>Three modes exist:
 * <ul>
 *   <li>{@link #standard()} - Skip whitespace, return CST nodes</li>
 *   <li>{@link #withActions(List, String[])} - Skip whitespace, collect semantic values</li>
 *   <li>{@link #noWhitespace()} - Don't skip whitespace (for %whitespace rule itself)</li>
 * </ul>
 *
 * <p>Note: This is a mutable context holder, not a value object. The contained
 * List and array are mutated during parsing to collect semantic values.
 */
public final class ParseMode {
    private final boolean skipWhitespace;
    private final boolean collectActions;
    private final Option<List<Object>> semanticValues;
    private final Option<String[] > tokenCapture;

    private ParseMode(boolean skipWhitespace,
                      boolean collectActions,
                      Option<List<Object>> semanticValues,
                      Option<String[] > tokenCapture) {
        this.skipWhitespace = skipWhitespace;
        this.collectActions = collectActions;
        this.semanticValues = semanticValues;
        this.tokenCapture = tokenCapture;
    }

    /**
     * Standard CST parsing mode - skips whitespace, doesn't collect semantic values.
     */
    public static ParseMode standard() {
        return new ParseMode(true, false, Option.none(), Option.none());
    }

    /**
     * Action collection mode - skips whitespace, collects semantic values from child rules.
     */
    public static ParseMode withActions(List<Object> values, String[] tokenCapture) {
        return new ParseMode(true, true, Option.some(values), Option.some(tokenCapture));
    }

    /**
     * No-whitespace mode - for parsing the %whitespace rule itself.
     */
    public static ParseMode noWhitespace() {
        return new ParseMode(false, false, Option.none(), Option.none());
    }

    /**
     * Create a child mode for nested parsing that preserves action collection.
     */
    public ParseMode childMode(List<Object> childValues, String[] childTokenCapture) {
        if (collectActions) {
            return new ParseMode(skipWhitespace, true, Option.some(childValues), Option.some(childTokenCapture));
        }
        return this;
    }

    public boolean skipWhitespace() {
        return skipWhitespace;
    }

    public boolean collectActions() {
        return collectActions;
    }

    public Option<List<Object>> semanticValues() {
        return semanticValues;
    }

    public Option<String[] > tokenCapture() {
        return tokenCapture;
    }

    /**
     * Check if this mode should skip whitespace before parsing an element.
     */
    public boolean shouldSkipWhitespace() {
        return skipWhitespace;
    }

    /**
     * Check if we should collect semantic values from child rules.
     */
    public boolean shouldCollectActions() {
        return collectActions;
    }

    /**
     * Add a semantic value collected from a child rule.
     * Mutates the contained list.
     */
    public void addValue(Object value) {
        if (collectActions) {
            semanticValues.onPresent(list -> list.add(value));
        }
    }

    /**
     * Set the token capture (from &lt; &gt; boundary).
     * Mutates the contained array.
     */
    public void setTokenCapture(String text) {
        if (collectActions) {
            tokenCapture.onPresent(arr -> arr[0] = text);
        }
    }
}
