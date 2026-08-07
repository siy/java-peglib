package org.pragmatica.peg.lexer;

import org.pragmatica.lang.Option;


/**
 * JLS 3.3 — the Unicode-escape translation that runs before lexing.
 *
 * <p>Java processes {@code \\uXXXX} in a pre-pass over the whole source, not inside the lexer.
 * That is why {@code \\u0061bc} is the identifier {@code abc}, {@code \\u000a} is a real line
 * terminator that ends a {@code //} comment, and an escape may appear in the middle of a token
 * or inside a comment. A grammar cannot express this: by the time the lexer runs, the
 * substitution has already happened.
 *
 * <p>Two rules are easy to get wrong and both are exercised by the OpenJDK corpus:
 * <ul>
 *   <li>A backslash starts an escape only when preceded by an EVEN number of backslashes.
 *       {@code \\\\u0041} is a literal backslash followed by {@code u0041}, not an {@code A}.
 *   <li>Any number of {@code u}s may follow the backslash — {@code \\uuuu0041} is legal.
 * </ul>
 *
 * <p>The translation carries an offset map so token spans can be pushed back onto the original
 * text (see {@code TokenArray.remapOffsets}). Without it the formatter would reconstruct
 * translated source instead of what the user wrote.
 */
public final class UnicodeEscapes {
    private UnicodeEscapes() {}

    /**
     * @param text      source with escapes replaced by the characters they denote
     * @param offsetMap {@code offsetMap[i]} is the original offset of translated character
     *                  {@code i}; length is {@code text.length() + 1} so an end offset just
     *                  past the last character maps correctly
     */
    public record Translated(String text, int[] offsetMap) {}

    /**
     * Translate {@code input}, or {@link Option#none()} when it contains no Unicode escape at
     * all — the overwhelmingly common case, which must not pay for this feature. The check is
     * a single substring scan; callers lex the original string unchanged when this is empty.
     */
    public static Option<Translated> translate(String input) {
        if (input.indexOf("\\u") < 0) {
            return Option.none();
        }

        var n = input.length();
        var out = new StringBuilder(n);
        var map = new int[n + 1];
        // At the start of input, zero preceding backslashes is an even count.
        var evenBackslashes = true;
        var i = 0;

        while (i < n) {
            var c = input.charAt(i);

            if (c == '\\' && evenBackslashes) {
                var end = escapeEnd(input, i);

                if (end > 0) {
                    map[out.length()] = i;
                    out.append((char) Integer.parseInt(input, end - 4, end, 16));
                    i = end;
                    // The escape is consumed whole; the next backslash starts a fresh count.
                    evenBackslashes = true;
                    continue;
                }
            }

            evenBackslashes = c != '\\' || !evenBackslashes;
            map[out.length()] = i;
            out.append(c);
            i++;
        }

        map[out.length()] = n;

        return Option.some(new Translated(out.toString(),
                                          java.util.Arrays.copyOf(map, out.length() + 1)));
    }

    /**
     * If a well-formed escape starts at {@code start} (a backslash, one or more {@code u}, then
     * exactly four hex digits), return the index just past it; otherwise {@code -1}. A
     * malformed escape is left alone here rather than reported: javac raises
     * {@code illegal.unicode.esc}, which is a lexical-validity concern, not a shape one.
     */
    private static int escapeEnd(String input, int start) {
        var i = start + 1;
        var n = input.length();

        if (i >= n || input.charAt(i) != 'u') {
            return -1;
        }

        while (i < n && input.charAt(i) == 'u') {
            i++;
        }

        if (i + 4 > n) {
            return -1;
        }

        for (var k = i; k < i + 4; k++) {
            if (Character.digit(input.charAt(k), 16) < 0) {
                return -1;
            }
        }

        return i + 4;
    }
}
