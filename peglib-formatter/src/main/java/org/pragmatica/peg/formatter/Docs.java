package org.pragmatica.peg.formatter;

import java.util.List;


/**
 * Static builder functions for {@link Doc} values. Intended for static import.
 *
 * <pre>{@code
 * import static org.pragmatica.peg.formatter.Docs.*;
 *
 * Doc block = group(text("{"), line(), indent(2, children), line(), text("}"));
 * }</pre>
 *
 * @since 0.3.3
 */
public final class Docs {
    private Docs() {}

    /** The empty document. */
    public static Doc empty() {
        return new Doc.Empty();
    }

    /**
     * Literal text. Total: never throws.
     *
     * <p>A {@code null} or empty value yields {@link #empty()}. A value containing
     * newlines is split into newline-free {@link Doc.Text} segments joined by
     * {@link #hardline()}, which establishes the {@code Doc.Text} invariant by
     * construction rather than by validation.
     */
    public static Doc text(String value) {
        if (value == null || value.isEmpty()) {
            return new Doc.Empty();
        }

        if (value.indexOf('\n') < 0) {
            return new Doc.Text(value);
        }

        var segments = value.split("\n", -1);
        var parts = new java.util.ArrayList<Doc>(segments.length * 2 - 1);

        for (var i = 0; i < segments.length; i++) {
            if (i > 0) {
                parts.add(new Doc.HardLine());
            }

            if (!segments[i].isEmpty()) {
                parts.add(new Doc.Text(segments[i]));
            }
        }

        return Doc.concatAll(parts);
    }

    /** A line break: becomes a space in flat mode, a newline in break mode. */
    public static Doc line() {
        return new Doc.Line();
    }

    /** A soft line break: becomes empty in flat mode, a newline in break mode. */
    public static Doc softline() {
        return new Doc.Softline();
    }

    /**
     * A hard line break: always newline + current indent. Forces any
     * enclosing group into break mode. Useful for emitting required line
     * breaks (e.g. after a line comment).
     */
    public static Doc hardline() {
        return new Doc.HardLine();
    }

    /**
     * Group: the renderer prefers to render {@code parts} flat (single line)
     * if they fit within the target width. Otherwise line / softline elements
     * inside break.
     */
    public static Doc group(Doc... parts) {
        return new Doc.Group(concat(parts));
    }

    /** Group variant taking a single inner doc. A {@code null} inner is treated as {@link #empty()}. */
    public static Doc group(Doc inner) {
        return new Doc.Group(orEmpty(inner));
    }

    /** Increase the indent by {@code amount} for any breaks within {@code inner}. */
    public static Doc indent(int amount, Doc inner) {
        return new Doc.Indent(amount, orEmpty(inner));
    }

    /** Variadic indent for convenience. */
    public static Doc indent(int amount, Doc... parts) {
        return new Doc.Indent(amount, concat(parts));
    }

    /** Concatenate {@code parts} sequentially. {@code concat()} returns empty. */
    public static Doc concat(Doc... parts) {
        if (parts == null || parts.length == 0) {
            return empty();
        }

        if (parts.length == 1) {
            return orEmpty(parts[0]);
        }

        return Doc.concatAll(List.of(parts));
    }

    /**
     * Null-coalesce a doc to {@link #empty()}.
     *
     * <p>The {@link Doc} records carry no validation, so the factories keep null
     * out of the tree instead — a null operand becomes the empty document rather
     * than surfacing as a {@code NullPointerException} during rendering.
     */
    private static Doc orEmpty(Doc doc) {
        return doc == null
               ? new Doc.Empty()
               : doc;
    }

    /** Concatenate a list of docs. */
    public static Doc concat(List<? extends Doc> parts) {
        return Doc.concatAll(parts);
    }

    /**
     * Join {@code parts} with {@code separator} between adjacent elements.
     * Returns {@link #empty()} for an empty list.
     */
    public static Doc join(Doc separator, List<? extends Doc> parts) {
        if (parts == null || parts.isEmpty()) {
            return empty();
        }

        Doc acc = parts.getFirst();

        for (int i = 1; i < parts.size(); i++) {
            acc = new Doc.Concat(new Doc.Concat(acc, separator), parts.get(i));
        }

        return acc;
    }
}
