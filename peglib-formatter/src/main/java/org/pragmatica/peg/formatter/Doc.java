package org.pragmatica.peg.formatter;

import java.util.List;

/**
 * Wadler-style pretty-printer document algebra.
 *
 * <p>A {@code Doc} is an abstract description of a pretty-printed text
 * fragment. It is rendered to a {@link String} by
 * {@link org.pragmatica.peg.formatter.internal.Renderer} using the
 * Wadler / Lindig "best" algorithm: groups fit on one line when they can,
 * otherwise their {@link Line} and {@link Softline} elements break.
 *
 * <p>Seven constructors, all immutable records:
 * <ul>
 *   <li>{@link Empty} — the empty document;</li>
 *   <li>{@link Text} — literal string (must not contain newlines);</li>
 *   <li>{@link Line} — either a single space (when the enclosing group fits on
 *       one line) or a hard newline + current indent (when it doesn't);</li>
 *   <li>{@link Softline} — same as {@link Line} but becomes the empty string
 *       in flat mode instead of a space;</li>
 *   <li>{@link HardLine} — always renders as a newline + current indent,
 *       regardless of group mode. Emitted primarily by the trivia machinery
 *       to preserve newlines in comments and whitespace runs;</li>
 *   <li>{@link Group} — "prefer to fit on one line if possible"; decides flat
 *       vs. break mode for any {@link Line}/{@link Softline} inside;</li>
 *   <li>{@link Indent} — adds {@code amount} columns to the current indent for
 *       any break that occurs inside {@code inner};</li>
 *   <li>{@link Concat} — sequential composition.</li>
 * </ul>
 *
 * <p>User code typically constructs docs via the {@link Docs} static builders
 * ({@code text}, {@code line}, {@code group}, ...).
 *
 * @since 0.3.3
 */
public sealed interface Doc {
    /** The empty document. Renders to the empty string in every mode. */
    record Empty() implements Doc {}

    /** Literal text. Must not contain newlines; use {@link Line} for those. */
    record Text(String value) implements Doc {
        public Text {
            if (value == null) {
                throw new IllegalArgumentException("Text value must not be null");
            }
            if (value.indexOf('\n') >= 0) {
                throw new IllegalArgumentException(
                    "Text.value must not contain newlines; use Doc.line() for line breaks");
            }
        }
    }

    /**
     * A "line break" — renders as a single space in flat mode, as a newline
     * followed by the current indent in break mode.
     */
    record Line() implements Doc {}

    /**
     * A "soft line break" — renders as the empty string in flat mode, as a
     * newline followed by the current indent in break mode.
     */
    record Softline() implements Doc {}

    /**
     * A hard line break — always renders as a newline + current indent,
     * regardless of enclosing group mode. Also forces any enclosing group to
     * render in break mode. Used by the trivia machinery to preserve newlines
     * in comments and whitespace runs.
     */
    record HardLine() implements Doc {}

    /**
     * Group: the renderer attempts to format {@code inner} in flat mode; if
     * the flat form exceeds the target line width, the whole group breaks.
     */
    record Group(Doc inner) implements Doc {
        public Group {
            if (inner == null) {
                throw new IllegalArgumentException("Group.inner must not be null");
            }
        }
    }

    /**
     * Indent: increases the current indent by {@code amount} columns for any
     * break that occurs inside {@code inner}.
     */
    record Indent(int amount, Doc inner) implements Doc {
        public Indent {
            if (inner == null) {
                throw new IllegalArgumentException("Indent.inner must not be null");
            }
        }
    }

    /** Sequential composition: render {@code left} then {@code right}. */
    record Concat(Doc left, Doc right) implements Doc {
        public Concat {
            if (left == null || right == null) {
                throw new IllegalArgumentException("Concat operands must not be null");
            }
        }
    }

    /**
     * Convenience: flatten a list of docs into a single {@link Concat} chain
     * (right-associated). Returns {@link Empty} for an empty list.
     */
    static Doc concatAll(List<? extends Doc> parts) {
        if (parts == null || parts.isEmpty()) {
            return new Empty();
        }
        Doc acc = parts.getLast();
        for (int i = parts.size() - 2; i >= 0; i--) {
            acc = new Concat(parts.get(i), acc);
        }
        return acc;
    }
}
