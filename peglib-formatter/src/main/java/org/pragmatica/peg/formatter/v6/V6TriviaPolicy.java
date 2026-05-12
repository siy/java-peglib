package org.pragmatica.peg.formatter.v6;

import org.pragmatica.peg.formatter.Doc;
import org.pragmatica.peg.v6.token.TokenArray;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.pragmatica.peg.formatter.Docs.concat;
import static org.pragmatica.peg.formatter.Docs.empty;
import static org.pragmatica.peg.formatter.Docs.text;

/**
 * Strategy for emitting trivia (whitespace, comments) collected from the
 * surrounding {@link TokenArray} while formatting a v6 CST node.
 *
 * <p>v6's flat-array trivia model is purely positional: trivia tokens live
 * inline with content tokens, classified by kind
 * ({@link TokenArray#KIND_WHITESPACE}, {@link TokenArray#KIND_LINE_COMMENT},
 * {@link TokenArray#KIND_BLOCK_COMMENT}, {@link TokenArray#KIND_DOC_LINE_COMMENT},
 * {@link TokenArray#KIND_DOC_BLOCK_COMMENT}). The policy is invoked once per
 * trivia run with the run's token indices and produces a {@link Doc}
 * fragment to splice into the output.
 *
 * <p>Predefined policies:
 * <ul>
 *   <li>{@link #PRESERVE} — emit each trivia token verbatim (newlines as hard breaks).</li>
 *   <li>{@link #STRIP_WHITESPACE} — drop whitespace, keep comments.</li>
 *   <li>{@link #DROP_ALL} — drop every trivia.</li>
 *   <li>{@link #NORMALIZE_BLANK_LINES} — collapse multi-newline whitespace runs to one blank line.</li>
 * </ul>
 *
 * @since 0.6.0
 */
@FunctionalInterface
public interface V6TriviaPolicy {

    /**
     * Render a contiguous run of trivia tokens (in {@code tokenIndices} order
     * over {@code tokens}) into a {@link Doc}. Return
     * {@link org.pragmatica.peg.formatter.Docs#empty()} to drop the run.
     */
    Doc render(TokenArray tokens, IntStream tokenIndices);

    /** Preserve every trivia verbatim. Newlines become hard line breaks. */
    V6TriviaPolicy PRESERVE = (tokens, indices) -> renderTrivia(tokens, indices, false, false);

    /** Drop whitespace; keep comments. */
    V6TriviaPolicy STRIP_WHITESPACE = (tokens, indices) -> renderTrivia(tokens, indices, true, false);

    /** Drop every trivia. */
    V6TriviaPolicy DROP_ALL = (tokens, indices) -> empty();

    /** Collapse runs of more than one newline in whitespace down to a single blank line. */
    V6TriviaPolicy NORMALIZE_BLANK_LINES = (tokens, indices) -> renderTrivia(tokens, indices, false, true);

    private static Doc renderTrivia(TokenArray tokens,
                                    IntStream indices,
                                    boolean stripWhitespace,
                                    boolean normalizeBlankLines) {
        var parts = new ArrayList<Doc>();
        indices.forEach(idx -> {
            var kind = tokens.kindAt(idx);
            var raw = tokens.textAt(idx).toString();
            switch (kind) {
                case TokenArray.KIND_WHITESPACE -> {
                    if (stripWhitespace) {
                        return;
                    }
                    var ws = normalizeBlankLines ? collapseBlankLines(raw) : raw;
                    appendWhitespace(parts, ws);
                }
                case TokenArray.KIND_LINE_COMMENT, TokenArray.KIND_DOC_LINE_COMMENT -> appendLineComment(parts, raw);
                case TokenArray.KIND_BLOCK_COMMENT, TokenArray.KIND_DOC_BLOCK_COMMENT -> appendBlockComment(parts, raw);
                default -> parts.add(text(raw));
            }
        });
        return parts.isEmpty() ? empty() : concat(parts);
    }

    private static void appendWhitespace(List<Doc> parts, String ws) {
        if (ws.isEmpty()) {
            return;
        }
        var run = new StringBuilder();
        for (var i = 0; i < ws.length(); i++) {
            var c = ws.charAt(i);
            if (c == '\n') {
                if (!run.isEmpty()) {
                    parts.add(text(run.toString()));
                    run.setLength(0);
                }
                parts.add(new Doc.HardLine());
            } else {
                run.append(c);
            }
        }
        if (!run.isEmpty()) {
            parts.add(text(run.toString()));
        }
    }

    private static void appendLineComment(List<Doc> parts, String raw) {
        var stripped = raw.endsWith("\n") ? raw.substring(0, raw.length() - 1) : raw;
        parts.add(text(stripped));
        parts.add(new Doc.HardLine());
    }

    private static void appendBlockComment(List<Doc> parts, String raw) {
        if (raw.indexOf('\n') < 0) {
            parts.add(text(raw));
            return;
        }
        var lines = raw.split("\n", -1);
        for (var i = 0; i < lines.length; i++) {
            if (i > 0) {
                parts.add(new Doc.HardLine());
            }
            parts.add(text(lines[i]));
        }
    }

    private static String collapseBlankLines(String text) {
        var newlines = 0;
        for (var i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                newlines++;
            }
        }
        if (newlines <= 1) {
            return text;
        }
        return "\n\n";
    }
}
