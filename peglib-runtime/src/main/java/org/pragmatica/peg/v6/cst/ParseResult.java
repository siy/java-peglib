package org.pragmatica.peg.v6.cst;

import org.pragmatica.peg.v6.diagnostic.Diagnostic;
import org.pragmatica.peg.v6.diagnostic.Severity;

import java.util.List;
import java.util.Objects;

/**
 * Output of a 0.6.0 parse: the produced {@link CstArray} together with any diagnostics
 * collected during parsing. Per spec §3.8, every parse yields a tree — even on failure
 * the {@code cst} is non-null and may contain Error-flagged nodes (see
 * {@link CstArray#FLAG_ERROR}). The {@code diagnostics} list is defensively copied at
 * construction so the result is fully immutable.
 */
public record ParseResult( CstArray cst, List<Diagnostic> diagnostics) {
    public ParseResult {
        Objects.requireNonNull(cst, "cst");
        Objects.requireNonNull(diagnostics, "diagnostics");
        diagnostics = List.copyOf(diagnostics);
    }

    public boolean isSuccess() {
        return diagnostics.isEmpty();
    }

    public boolean hasErrors() {
        return diagnostics.stream().anyMatch(d -> d.severity() == Severity.ERROR);
    }
}
