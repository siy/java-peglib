package org.pragmatica.peg.error;

import org.pragmatica.peg.tree.SourceSpan;

import java.util.List;

/**
 * Rich diagnostic message for Rust-style error reporting.
 *
 * <p>Example output:
 * <pre>
 * error: unexpected token
 *   --> input.txt:3:15
 *    |
 *  3 |     let x = @invalid;
 *    |             ^^^^^^^^ expected expression
 *    |
 *    = help: expressions can start with identifiers, literals, or '('
 * </pre>
 *
 * @param severity    Error severity level
 * @param code        Optional error code (e.g., "E0001")
 * @param message     Primary error message
 * @param span        Source span where error occurred
 * @param labels      Additional labeled spans for context
 * @param notes       Additional notes or suggestions
 */
public record Diagnostic(
    Severity severity,
    String code,
    String message,
    SourceSpan span,
    List<Label> labels,
    List<String> notes
) {
    /**
     * Error severity levels.
     */
    public enum Severity {
        ERROR("error"),
        WARNING("warning"),
        INFO("info"),
        HINT("hint");

        private final String display;

        Severity(String display) {
            this.display = display;
        }

        public String display() {
            return display;
        }
    }

    /**
     * A labeled span providing additional context.
     *
     * @param span    Source span for this label
     * @param message Label message
     * @param primary Whether this is the primary label (shown with ^^^)
     */
    public record Label(SourceSpan span, String message, boolean primary) {
        public static Label primary(SourceSpan span, String message) {
            return new Label(span, message, true);
        }

        public static Label secondary(SourceSpan span, String message) {
            return new Label(span, message, false);
        }
    }

    /**
     * Create an error diagnostic.
     */
    public static Diagnostic error(String message, SourceSpan span) {
        return new Diagnostic(Severity.ERROR, null, message, span, List.of(), List.of());
    }

    /**
     * Create an error with code.
     */
    public static Diagnostic error(String code, String message, SourceSpan span) {
        return new Diagnostic(Severity.ERROR, code, message, span, List.of(), List.of());
    }

    /**
     * Create a warning diagnostic.
     */
    public static Diagnostic warning(String message, SourceSpan span) {
        return new Diagnostic(Severity.WARNING, null, message, span, List.of(), List.of());
    }

    /**
     * Add a primary label.
     */
    public Diagnostic withLabel(String message) {
        var newLabels = new java.util.ArrayList<>(labels);
        newLabels.add(Label.primary(span, message));
        return new Diagnostic(severity, code, this.message, span, List.copyOf(newLabels), notes);
    }

    /**
     * Add a secondary label at a different span.
     */
    public Diagnostic withSecondaryLabel(SourceSpan labelSpan, String message) {
        var newLabels = new java.util.ArrayList<>(labels);
        newLabels.add(Label.secondary(labelSpan, message));
        return new Diagnostic(severity, code, this.message, span, List.copyOf(newLabels), notes);
    }

    /**
     * Add a note.
     */
    public Diagnostic withNote(String note) {
        var newNotes = new java.util.ArrayList<>(notes);
        newNotes.add(note);
        return new Diagnostic(severity, code, message, span, labels, List.copyOf(newNotes));
    }

    /**
     * Add a help suggestion.
     */
    public Diagnostic withHelp(String help) {
        return withNote("help: " + help);
    }

    /**
     * Format this diagnostic in Rust style.
     *
     * @param source   The source text
     * @param filename Optional filename for display
     * @return Formatted diagnostic string
     */
    public String format(String source, String filename) {
        var sb = new StringBuilder();
        var lines = source.split("\n", -1);

        // Header: error[E0001]: message
        sb.append(severity.display());
        if (code != null) {
            sb.append("[").append(code).append("]");
        }
        sb.append(": ").append(message).append("\n");

        // Location: --> filename:line:column
        var loc = span.start();
        sb.append("  --> ");
        if (filename != null) {
            sb.append(filename).append(":");
        }
        sb.append(loc.line()).append(":").append(loc.column()).append("\n");

        // Find all lines we need to display
        int minLine = span.start().line();
        int maxLine = span.end().line();
        for (var label : labels) {
            minLine = Math.min(minLine, label.span().start().line());
            maxLine = Math.max(maxLine, label.span().end().line());
        }

        // Calculate gutter width
        int gutterWidth = String.valueOf(maxLine).length();

        // Empty line before source
        sb.append(" ".repeat(gutterWidth + 1)).append("|\n");

        // Display source lines with labels
        for (int lineNum = minLine; lineNum <= maxLine; lineNum++) {
            if (lineNum < 1 || lineNum > lines.length) continue;

            String lineContent = lines[lineNum - 1];
            String lineNumStr = String.format("%" + gutterWidth + "d", lineNum);

            // Source line
            sb.append(lineNumStr).append(" | ").append(lineContent).append("\n");

            // Underline labels on this line
            var lineLabels = getLabelsOnLine(lineNum);
            if (!lineLabels.isEmpty()) {
                sb.append(" ".repeat(gutterWidth)).append(" | ");
                sb.append(formatUnderlines(lineNum, lineContent, lineLabels));
                sb.append("\n");
            }
        }

        // Empty line after source
        sb.append(" ".repeat(gutterWidth + 1)).append("|\n");

        // Notes
        for (var note : notes) {
            sb.append(" ".repeat(gutterWidth + 1)).append("= ").append(note).append("\n");
        }

        return sb.toString();
    }

    private List<Label> getLabelsOnLine(int lineNum) {
        var result = new java.util.ArrayList<Label>();

        // Check if primary span covers this line
        if (span.start().line() <= lineNum && span.end().line() >= lineNum) {
            // Add implicit primary label if no explicit labels
            if (labels.isEmpty()) {
                result.add(Label.primary(span, ""));
            }
        }

        // Add explicit labels on this line
        for (var label : labels) {
            if (label.span().start().line() <= lineNum && label.span().end().line() >= lineNum) {
                result.add(label);
            }
        }

        return result;
    }

    private String formatUnderlines(int lineNum, String lineContent, List<Label> lineLabels) {
        var sb = new StringBuilder();
        int currentCol = 1;

        // Sort labels by start column
        var sorted = lineLabels.stream()
            .sorted((a, b) -> Integer.compare(a.span().start().column(), b.span().start().column()))
            .toList();

        for (var label : sorted) {
            int startCol = label.span().start().line() == lineNum ? label.span().start().column() : 1;
            int endCol = label.span().end().line() == lineNum
                ? label.span().end().column()
                : lineContent.length() + 1;

            // Pad to start
            while (currentCol < startCol) {
                sb.append(" ");
                currentCol++;
            }

            // Draw underline
            char underlineChar = label.primary() ? '^' : '-';
            int underlineLen = Math.max(1, endCol - startCol);
            sb.append(String.valueOf(underlineChar).repeat(underlineLen));
            currentCol += underlineLen;

            // Add label message if present
            if (!label.message().isEmpty()) {
                sb.append(" ").append(label.message());
            }
        }

        return sb.toString();
    }

    /**
     * Simple single-line format for quick display.
     */
    public String formatSimple() {
        var loc = span.start();
        return String.format("%s:%d:%d: %s: %s",
            "input", loc.line(), loc.column(), severity.display(), message);
    }
}
