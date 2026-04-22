package org.pragmatica.peg.formatter;

import org.pragmatica.peg.tree.Trivia;

import java.util.ArrayList;
import java.util.List;

/**
 * Strategy for emitting {@link Trivia} (whitespace / comments) attached to
 * CST nodes during formatting.
 *
 * <p>Four predefined policies cover common cases:
 * <ul>
 *   <li>{@link #PRESERVE} — emit every trivia verbatim (default v1 policy);</li>
 *   <li>{@link #STRIP_WHITESPACE} — drop whitespace trivia, keep comments;</li>
 *   <li>{@link #DROP_ALL} — drop every trivia;</li>
 *   <li>{@link #NORMALIZE_BLANK_LINES} — collapse runs of blank lines into at
 *       most one blank line; comments preserved.</li>
 * </ul>
 *
 * <p>Custom policies can be provided by implementing
 * {@link #transform(List)}.
 *
 * @since 0.3.3
 */
public interface TriviaPolicy {
    /**
     * Transform a run of trivia into the trivia the formatter should emit.
     * Return an empty list to drop the trivia entirely. Implementations must
     * not mutate the argument.
     */
    List<Trivia> transform(List<Trivia> trivia);

    /** Emit every trivia verbatim. */
    TriviaPolicy PRESERVE = trivia -> trivia;

    /** Emit comments only, drop whitespace. */
    TriviaPolicy STRIP_WHITESPACE = trivia -> {
        var out = new ArrayList<Trivia>(trivia.size());
        for (var t : trivia) {
            if (!(t instanceof Trivia.Whitespace)) {
                out.add(t);
            }
        }
        return out;
    };

    /** Drop every trivia. */
    TriviaPolicy DROP_ALL = trivia -> List.of();

    /**
     * Collapse runs of whitespace containing multiple newlines into a single
     * whitespace trivia carrying exactly two newlines (i.e. at most one blank
     * line). Comments are preserved verbatim.
     */
    TriviaPolicy NORMALIZE_BLANK_LINES = trivia -> {
        var out = new ArrayList<Trivia>(trivia.size());
        for (var t : trivia) {
            if (t instanceof Trivia.Whitespace ws) {
                out.add(collapseBlankLines(ws));
            } else {
                out.add(t);
            }
        }
        return out;
    };

    private static Trivia.Whitespace collapseBlankLines(Trivia.Whitespace ws) {
        var text = ws.text();
        int newlines = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                newlines++;
            }
        }
        if (newlines <= 1) {
            return ws;
        }
        return new Trivia.Whitespace(ws.span(), "\n\n");
    }
}
