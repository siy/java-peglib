package org.pragmatica.peg.analyzer;

/**
 * A single analyzer finding about a grammar.
 *
 * @param severity Finding severity (ERROR, WARNING, INFO)
 * @param tag      Machine-readable identifier (e.g. "grammar.unreachable-rule")
 * @param ruleName Name of the rule the finding applies to (empty string for grammar-level findings)
 * @param message  Human-readable description
 */
public record Finding(Severity severity, String tag, String ruleName, String message, int line) {
    /** The finding has no position in the analyzed grammar file. */
    public static final int NO_LINE = 0;

    public Finding atLine(int newLine) {
        return new Finding(severity, tag, ruleName, message, newLine);
    }

    /**
     * Finding severity levels.
     */
    public enum Severity {
        ERROR,
        WARNING,
        INFO
    }

    public static Finding error(String tag, String ruleName, String message) {
        return new Finding(Severity.ERROR, tag, ruleName, message, NO_LINE);
    }

    public static Finding warning(String tag, String ruleName, String message) {
        return new Finding(Severity.WARNING, tag, ruleName, message, NO_LINE);
    }

    public static Finding info(String tag, String ruleName, String message) {
        return new Finding(Severity.INFO, tag, ruleName, message, NO_LINE);
    }
}
