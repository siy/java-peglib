package org.pragmatica.peg.tree;

/**
 * A range in source text from start (inclusive) to end (exclusive).
 */
public record SourceSpan(SourceLocation start, SourceLocation end) {

    public static SourceSpan of(SourceLocation start, SourceLocation end) {
        return new SourceSpan(start, end);
    }

    public static SourceSpan at(SourceLocation location) {
        return new SourceSpan(location, location);
    }

    public int length() {
        return end.offset() - start.offset();
    }

    public String extract(String source) {
        return source.substring(start.offset(), end.offset());
    }

    public SourceSpan merge(SourceSpan other) {
        var newStart = start.offset() <= other.start.offset() ? start : other.start;
        var newEnd = end.offset() >= other.end.offset() ? end : other.end;
        return new SourceSpan(newStart, newEnd);
    }

    @Override
    public String toString() {
        return start + "-" + end;
    }
}
