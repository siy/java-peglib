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
     * Literal text. The value must not contain newlines — use {@link #line()}
     * for line breaks.
     */
    public static Doc text(String value) {
        return new Doc.Text(value);
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

    /** Group variant taking a single inner doc. */
    public static Doc group(Doc inner) {
        return new Doc.Group(inner);
    }

    /** Increase the indent by {@code amount} for any breaks within {@code inner}. */
    public static Doc indent(int amount, Doc inner) {
        return new Doc.Indent(amount, inner);
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
            return parts[0];
        }
        return Doc.concatAll(List.of(parts));
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
