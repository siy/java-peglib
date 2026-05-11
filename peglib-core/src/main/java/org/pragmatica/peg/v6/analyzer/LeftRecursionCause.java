package org.pragmatica.peg.v6.analyzer;

import org.pragmatica.lang.Cause;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 0.6.0 — failure {@link Cause} reported by {@code PegParser.fromGrammar} when
 * {@link LeftRecursionDetector} flags one or more left-recursive rules. Carries
 * the full list of offending rules so tooling can surface every diagnostic in
 * a single pass; {@link #message()} emits a human-readable, multi-line summary.
 */
public record LeftRecursionCause( List<LeftRecursionDetector.LeftRecursionError> errors) implements Cause {
    public static LeftRecursionCause of(LeftRecursionDetector.DetectionResult result) {
        return new LeftRecursionCause(List.copyOf(result.errors()));
    }

    @Override public String message() {
        var prefix = errors.size() == 1
                     ? "Grammar contains a left-recursive rule:\n  - "
                     : "Grammar contains " + errors.size() + " left-recursive rules:\n  - ";
        return prefix + errors.stream().map(LeftRecursionDetector.LeftRecursionError::message)
                                     .collect(Collectors.joining("\n  - "));
    }
}
