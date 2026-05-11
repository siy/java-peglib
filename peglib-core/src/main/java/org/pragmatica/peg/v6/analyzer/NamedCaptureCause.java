package org.pragmatica.peg.v6.analyzer;

import org.pragmatica.lang.Cause;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 0.6.0 — failure {@link Cause} reported by {@code PegParser.fromGrammar} when
 * {@link NamedCaptureDetector} flags one or more named captures or
 * back-references. These constructs are accepted by the grammar parser but the
 * 0.6.0 lex-then-parse pipeline does not yet implement their runtime semantics
 * — emitting a no-op parser would silently accept inputs that ought to be
 * rejected (e.g. mismatched HTML-style tags). Rejecting at compile time
 * preserves correctness; full support is tracked for a future release.
 */
public record NamedCaptureCause( List<NamedCaptureDetector.Occurrence> occurrences) implements Cause {
    public static NamedCaptureCause of(NamedCaptureDetector.DetectionResult result) {
        return new NamedCaptureCause(List.copyOf(result.occurrences()));
    }

    @Override public String message() {
        var prefix = occurrences.size() == 1
                     ? "Grammar uses an unsupported feature (named captures / back-references):\n  - "
                     : "Grammar uses " + occurrences.size() + " unsupported features (named captures / back-references):\n  - ";
        var body = occurrences.stream().map(NamedCaptureDetector.Occurrence::message)
                                     .collect(Collectors.joining("\n  - "));
        return prefix + body + "\nNamed captures and back-references are not yet supported in peglib 0.6.0." + " Rewrite the rule to match without back-references, or pin to peglib 0.5.x" + " until support is added in a future release.";
    }
}
