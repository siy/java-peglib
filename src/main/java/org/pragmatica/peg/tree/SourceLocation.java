package org.pragmatica.peg.tree;

/**
 * A position in source text (line and column, both 1-based).
 */
public record SourceLocation(int line, int column, int offset) {

    public static final SourceLocation START = new SourceLocation(1, 1, 0);

    public static SourceLocation at(int line, int column, int offset) {
        return new SourceLocation(line, column, offset);
    }

    public SourceLocation advanceColumn(int delta) {
        return new SourceLocation(line, column + delta, offset + delta);
    }

    public SourceLocation advanceLine() {
        return new SourceLocation(line + 1, 1, offset + 1);
    }

    @Override
    public String toString() {
        return line + ":" + column;
    }
}
