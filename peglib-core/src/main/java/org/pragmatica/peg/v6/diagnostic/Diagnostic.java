package org.pragmatica.peg.v6.diagnostic;
public record Diagnostic(
 Severity severity,
 int offset,
 int length,
 String message,
 String expected,
 String found) {
    // Internal record: callers (parser/lexer codegen) pass validated values.
    // Defensive null/range checks omitted by JBCT policy.
    public static Diagnostic error(int offset, int length, String message, String expected, String found) {
        return new Diagnostic(Severity.ERROR, offset, length, message, expected, found);
    }

    public static Diagnostic error(int offset, int length, String message) {
        return new Diagnostic(Severity.ERROR, offset, length, message, "", "");
    }

    public String formatRustStyle(String filename, String input) {
        var pos = locate(input, offset);
        int line = pos[0];
        int col = pos[1];
        int lineStart = pos[2];
        int lineEnd = lineEndOffset(input, lineStart);
        String lineText = input.substring(lineStart, lineEnd);
        String lineNumStr = Integer.toString(line);
        int gutterWidth = lineNumStr.length();
        String emptyGutter = " ".repeat(gutterWidth + 2);
        var sb = new StringBuilder();
        sb.append(severity.label()).append(": ")
                 .append(message)
                 .append('\n');
        sb.append("  --> ").append(filename)
                 .append(':')
                 .append(line)
                 .append(':')
                 .append(col)
                 .append('\n');
        sb.append(emptyGutter).append("|\n");
        sb.append(' ').append(lineNumStr)
                 .append(" | ")
                 .append(lineText)
                 .append('\n');
        sb.append(emptyGutter).append("| ")
                 .append(caretIndent(col));
        sb.append(carets(length));
        if ( !found.isEmpty()) {
        sb.append(" found '").append(found)
                 .append('\'');}
        sb.append('\n');
        sb.append(emptyGutter).append("|\n");
        if ( !expected.isEmpty()) {
        sb.append(emptyGutter).append("= help: expected ")
                 .append(expected)
                 .append('\n');}
        return sb.toString();
    }

    private static String caretIndent(int column) {
        return " ".repeat(Math.max(0, column - 1));
    }

    private static String carets(int length) {
        return length <= 0
               ? "^"
               : "^".repeat(length);
    }

    private static int[] locate(String input, int offset) {
        int clamped = Math.min(offset, input.length());
        int line = 1;
        int lineStart = 0;
        for ( int i = 0; i < clamped; i++) {
        if ( input.charAt(i) == '\n') {
            line++;
            lineStart = i + 1;
        }}
        int col = clamped - lineStart + 1;
        return new int[]{line, col, lineStart};
    }

    private static int lineEndOffset(String input, int lineStart) {
        int end = input.indexOf('\n', lineStart);
        return end < 0
               ? input.length()
               : end;
    }
}
