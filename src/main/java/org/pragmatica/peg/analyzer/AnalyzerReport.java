package org.pragmatica.peg.analyzer;

import java.util.List;

/**
 * Result of running the {@link Analyzer} on a {@link org.pragmatica.peg.grammar.Grammar}.
 *
 * <p>Provides convenience accessors for severity-based filtering and a
 * Rust-cargo-check style formatter for CLI output.
 *
 * @param findings all findings produced by the analyzer, in deterministic rule order
 */
public record AnalyzerReport(List<Finding> findings) {
    public AnalyzerReport {
        findings = List.copyOf(findings);
    }

    public boolean hasErrors() {
        return findings.stream()
                       .anyMatch(f -> f.severity() == Finding.Severity.ERROR);
    }

    public boolean hasWarnings() {
        return findings.stream()
                       .anyMatch(f -> f.severity() == Finding.Severity.WARNING);
    }

    public long count(Finding.Severity severity) {
        return findings.stream()
                       .filter(f -> f.severity() == severity)
                       .count();
    }

    /**
     * Format this report in a Rust-cargo-check style.
     *
     * <p>Example output:
     * <pre>
     * warning[grammar.unreachable-rule]: rule 'Orphan' is unreachable from start rule 'Start'
     *   --> grammar.peg: Orphan
     *
     * error[grammar.duplicate-literal]: rule 'Sum' has duplicate literal 'foo' in Choice
     *   --> grammar.peg: Sum
     *
     * analyzer: 1 error, 1 warning, 0 info
     * </pre>
     */
    public String formatRustStyle(String grammarPath) {
        var sb = new StringBuilder();
        for (var f : findings) {
            sb.append(severityWord(f.severity()))
              .append('[')
              .append(f.tag())
              .append("]: ")
              .append(f.message())
              .append('\n');
            sb.append("  --> ")
              .append(grammarPath);
            if (!f.ruleName()
                  .isEmpty()) {
                sb.append(": ")
                  .append(f.ruleName());
            }
            sb.append("\n\n");
        }
        sb.append("analyzer: ")
          .append(count(Finding.Severity.ERROR))
          .append(" error")
          .append(count(Finding.Severity.ERROR) == 1
                  ? ""
                  : "s")
          .append(", ")
          .append(count(Finding.Severity.WARNING))
          .append(" warning")
          .append(count(Finding.Severity.WARNING) == 1
                  ? ""
                  : "s")
          .append(", ")
          .append(count(Finding.Severity.INFO))
          .append(" info\n");
        return sb.toString();
    }

    private static String severityWord(Finding.Severity severity) {
        return switch (severity) {
            case ERROR -> "error";
            case WARNING -> "warning";
            case INFO -> "info";
        };
    }
}
