package org.pragmatica.peg.tree;

/**
 * A range in source text from start (inclusive) to end (exclusive).
 *
 * <p>Stored as six primitive ints to avoid long-lived {@link SourceLocation}
 * references (which previously promoted to old gen via CstNode → SourceSpan → SourceLocation
 * chains, contributing to GC pause tail). {@link #start()}/{@link #end()} materialize
 * fresh SourceLocation instances on demand; callers needing a single field should use
 * the int accessors directly (e.g., {@link #startLine()}, {@link #endOffset()}) to avoid
 * the materialization cost.
 */
public record SourceSpan(int startLine, int startColumn, int startOffset,
                         int endLine, int endColumn, int endOffset) {

    public static SourceSpan sourceSpan(SourceLocation start, SourceLocation end) {
        return new SourceSpan(start.line(), start.column(), start.offset(),
                              end.line(), end.column(), end.offset());
    }

    public static SourceSpan sourceSpan(SourceLocation location) {
        return sourceSpan(location, location);
    }

    public SourceLocation start() {
        return SourceLocation.sourceLocation(startLine, startColumn, startOffset);
    }

    public SourceLocation end() {
        return SourceLocation.sourceLocation(endLine, endColumn, endOffset);
    }

    public int length() {
        return endOffset - startOffset;
    }

    public String extract(String source) {
        return source.substring(startOffset, endOffset);
    }

    public SourceSpan merge(SourceSpan other) {
        int newStartLine, newStartColumn, newStartOffset;
        int newEndLine, newEndColumn, newEndOffset;
        if (startOffset <= other.startOffset) {
            newStartLine = startLine; newStartColumn = startColumn; newStartOffset = startOffset;
        } else {
            newStartLine = other.startLine; newStartColumn = other.startColumn; newStartOffset = other.startOffset;
        }
        if (endOffset >= other.endOffset) {
            newEndLine = endLine; newEndColumn = endColumn; newEndOffset = endOffset;
        } else {
            newEndLine = other.endLine; newEndColumn = other.endColumn; newEndOffset = other.endOffset;
        }
        return new SourceSpan(newStartLine, newStartColumn, newStartOffset,
                              newEndLine, newEndColumn, newEndOffset);
    }

    @Override
    public String toString() {
        return startLine + ":" + startColumn + "-" + endLine + ":" + endColumn;
    }
}
