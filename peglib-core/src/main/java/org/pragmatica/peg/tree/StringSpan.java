package org.pragmatica.peg.tree;

import java.util.Objects;

/**
 * View into a substring of the parser input. Defers materialization to a
 * Java {@link String} until first access via {@link #toString()}.
 *
 * <p>Created by parser match helpers (token boundary capture, literal/charclass/any
 * Terminal construction) instead of eager {@code String.substring(int, int)}, which
 * (in Java 9+ compact strings) allocates both a fresh {@code byte[]} and a
 * {@code String} header. StringSpan eliminates both, deferring materialization until
 * a consumer actually needs a {@link String}.
 *
 * <p><b>Memory retention:</b> StringSpan holds a reference to the entire input.
 * CST nodes that survive the parse will keep input alive. This is fine for the
 * IDE plugin / incremental use case (input lives alongside the CST anyway). For
 * one-shot parses the cost is bounded by input size.
 *
 * <p><b>Thread-safety:</b> the parser is single-threaded; the lazy
 * {@code materialized} field uses a plain write-once pattern with no
 * synchronization.
 *
 * <p><b>Equality semantics:</b> {@code equals} compares text content (not
 * source/start/end), so two StringSpans pointing into different sources but with
 * identical text are equal. This is required so consumers that put StringSpans
 * in {@link java.util.Set} / {@link java.util.Map} compare by text.
 */
public final class StringSpan implements CharSequence {
    private final String source;
    private final int start;
    private final int end;
    private String materialized;

    // lazy cache; written-once
    public StringSpan(String source, int start, int end) {
        if (start < 0 || end < start || end > source.length()) {
            throw new IndexOutOfBoundsException("start=" + start + ", end=" + end + ", length=" + source.length());
        }
        this.source = source;
        this.start = start;
        this.end = end;
    }

    public static StringSpan of(String source, int start, int end) {
        return new StringSpan(source, start, end);
    }

    /**
     * Wrap a fully-materialized {@link String} as a StringSpan with no deferral.
     * Used when a callsite already has a String (e.g. a literal pattern), so the
     * String is captured directly and {@link #toString()} returns it without copying.
     */
    public static StringSpan ofString(String text) {
        var span = new StringSpan(text, 0, text.length());
        span.materialized = text;
        return span;
    }

    public String source() {
        return source;
    }

    public int start() {
        return start;
    }

    public int end() {
        return end;
    }

    @Override
    public int length() {
        return end - start;
    }

    @Override
    public char charAt(int index) {
        if (index < 0 || index >= length()) {
            throw new IndexOutOfBoundsException("index=" + index + ", length=" + length());
        }
        return source.charAt(start + index);
    }

    @Override
    public CharSequence subSequence(int s, int e) {
        if (s < 0 || e < s || e > length()) {
            throw new IndexOutOfBoundsException("s=" + s + ", e=" + e + ", length=" + length());
        }
        return new StringSpan(source, start + s, start + e);
    }

    @Override
    public String toString() {
        var m = materialized;
        if (m == null) {
            m = source.substring(start, end);
            materialized = m;
        }
        return m;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o instanceof StringSpan that) {
            int len = this.length();
            if (len != that.length()) {
                return false;
            }
            // Cheap path: same backing source and same offsets
            if (this.source == that.source && this.start == that.start && this.end == that.end) {
                return true;
            }
            // Compare characters
            for (int i = 0; i < len; i++ ) {
                if (this.source.charAt(this.start + i) != that.source.charAt(that.start + i)) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    @Override
    public int hashCode() {
        // Match String.hashCode() so StringSpan-by-content semantics are predictable.
        if (materialized != null) {
            return materialized.hashCode();
        }
        int h = 0;
        int len = end - start;
        for (int i = 0; i < len; i++ ) {
            h = 31 * h + source.charAt(start + i);
        }
        return h;
    }
}
