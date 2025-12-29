package org.pragmatica.peg.parser;

import org.pragmatica.lang.Option;
import org.pragmatica.peg.error.Diagnostic;
import org.pragmatica.peg.tree.CstNode;

import java.util.List;

/**
 * Result of parsing with error recovery - contains partial result and accumulated diagnostics.
 *
 * <p>When parsing succeeds completely, {@code node} is present with the full CST and {@code diagnostics} is empty.
 * When parsing encounters errors but recovers, {@code node} contains valid fragments with
 * {@link CstNode.Error} nodes for unparseable regions, and {@code diagnostics} contains the errors.
 * When parsing fails completely (even with recovery), {@code node} is empty.
 *
 * @param node        The parsed CST (may contain Error nodes), or empty if parsing failed completely
 * @param diagnostics Accumulated diagnostic messages (empty on full success)
 * @param source      The original source text (for formatting diagnostics)
 */
public record ParseResultWithDiagnostics(
    Option<CstNode> node,
    List<Diagnostic> diagnostics,
    String source
) {
    /**
     * Create a successful result with no errors.
     */
    public static ParseResultWithDiagnostics success(CstNode node, String source) {
        return new ParseResultWithDiagnostics(Option.some(node), List.of(), source);
    }

    /**
     * Create a result with errors (may have partial node or none).
     */
    public static ParseResultWithDiagnostics withErrors(Option<CstNode> node, List<Diagnostic> diagnostics, String source) {
        return new ParseResultWithDiagnostics(node, List.copyOf(diagnostics), source);
    }

    /**
     * Check if parsing succeeded without any errors.
     */
    public boolean isSuccess() {
        return node.isPresent() && diagnostics.isEmpty();
    }

    /**
     * Check if there were any errors during parsing.
     */
    public boolean hasErrors() {
        return !diagnostics.isEmpty();
    }

    /**
     * Check if parsing produced any result (may include errors).
     */
    public boolean hasNode() {
        return node.isPresent();
    }

    /**
     * Format all diagnostics in Rust style.
     *
     * @param filename Optional filename for display
     * @return Formatted diagnostics string
     */
    public String formatDiagnostics(String filename) {
        if (diagnostics.isEmpty()) {
            return "";
        }
        var sb = new StringBuilder();
        for (var diag : diagnostics) {
            sb.append(diag.format(source, filename));
            sb.append("\n");
        }
        return sb.toString();
    }

    /**
     * Format all diagnostics with default filename "input".
     */
    public String formatDiagnostics() {
        return formatDiagnostics("input");
    }

    /**
     * Get error count.
     */
    public int errorCount() {
        return (int) diagnostics.stream()
            .filter(d -> d.severity() == Diagnostic.Severity.ERROR)
            .count();
    }

    /**
     * Get warning count.
     */
    public int warningCount() {
        return (int) diagnostics.stream()
            .filter(d -> d.severity() == Diagnostic.Severity.WARNING)
            .count();
    }
}
