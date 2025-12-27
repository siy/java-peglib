package org.pragmatica.peg.tree;

/**
 * A position in source text (line and column, both 1-based).
 */
public record SourceLocation(int line, int column, int offset) {

    public static final SourceLocation START = new SourceLocation(1, 1, 0);

    public static SourceLocation at(int line, int column, int offset) {
        return new SourceLocation(line, column, offset);
    }

    @Override
    public String toString() {
        return line + ":" + column;
    }
}
