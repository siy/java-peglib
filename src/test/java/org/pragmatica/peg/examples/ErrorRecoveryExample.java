package org.pragmatica.peg.examples;

import org.junit.jupiter.api.Test;
import org.pragmatica.peg.PegParser;
import org.pragmatica.peg.error.Diagnostic;
import org.pragmatica.peg.error.RecoveryStrategy;
import org.pragmatica.peg.tree.CstNode;
import org.pragmatica.peg.tree.SourceLocation;
import org.pragmatica.peg.tree.SourceSpan;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Examples demonstrating error recovery and Rust-style diagnostics.
 *
 * <h2>Features Demonstrated</h2>
 * <ul>
 *   <li>Recovery strategies: NONE, BASIC, ADVANCED</li>
 *   <li>Multiple error collection</li>
 *   <li>Rust-style diagnostic formatting</li>
 *   <li>Error nodes in CST</li>
 *   <li>Custom diagnostic creation</li>
 * </ul>
 */
class ErrorRecoveryExample {

    private static final String LIST_GRAMMAR = """
        List <- Item (',' Item)*
        Item <- < [a-z]+ >
        %whitespace <- [ ]*
        """;

    // ========================================================================
    // Recovery Strategies
    // ========================================================================

    /**
     * NONE strategy: Parser fails immediately on first error.
     */
    @Test
    void noneStrategy_failsImmediately() {
        var parser = PegParser.builder(LIST_GRAMMAR)
            .recovery(RecoveryStrategy.NONE)
            .build()
            .unwrap();

        // Valid input succeeds
        assertTrue(parser.parseCst("abc, def, ghi").isSuccess());

        // Invalid input fails immediately
        assertTrue(parser.parseCst("abc, 123, def").isFailure());
    }

    /**
     * BASIC strategy: Parser reports error with context but stops.
     */
    @Test
    void basicStrategy_reportsErrorAndStops() {
        var parser = PegParser.builder(LIST_GRAMMAR)
            .recovery(RecoveryStrategy.BASIC)
            .build()
            .unwrap();

        var result = parser.parseCst("abc, 123, def");
        assertTrue(result.isFailure());
    }

    /**
     * ADVANCED strategy: Parser continues after errors.
     */
    @Test
    void advancedStrategy_collectsAllErrors() {
        var parser = PegParser.builder(LIST_GRAMMAR)
            .recovery(RecoveryStrategy.ADVANCED)
            .build()
            .unwrap();

        var input = "abc, 123, def, @@@";
        var result = parser.parseCstWithDiagnostics(input);

        // Multiple errors collected
        assertTrue(result.diagnostics().size() >= 2,
            "Should collect multiple errors");

        // Can still access partial results
        assertTrue(result.hasNode());
    }

    // ========================================================================
    // Rust-Style Diagnostic Formatting
    // ========================================================================

    /**
     * Diagnostics format in Rust compiler style with source context.
     *
     * Output looks like:
     * <pre>
     * error: unexpected token
     *   --> example.txt:1:9
     *    |
     *  1 | let x = @invalid;
     *    |         ^^^^^^^^ expected expression
     *    |
     *    = help: expressions start with identifiers, literals, or '('
     * </pre>
     */
    @Test
    void diagnostics_formatInRustStyle() {
        var source = "let x = @invalid;";
        var span = SourceSpan.of(
            SourceLocation.at(1, 9, 8),
            SourceLocation.at(1, 17, 16)
        );

        var diagnostic = Diagnostic.error("unexpected token", span)
            .withLabel("expected expression")
            .withHelp("expressions start with identifiers, literals, or '('");

        var formatted = diagnostic.format(source, "example.txt");

        // Verify Rust-style format
        assertTrue(formatted.contains("error: unexpected token"));
        assertTrue(formatted.contains("--> example.txt:1:9"));
        assertTrue(formatted.contains("^^^^^^^^"));
        assertTrue(formatted.contains("expected expression"));
        assertTrue(formatted.contains("help:"));
    }

    /**
     * Multiple diagnostics format together.
     */
    @Test
    void multipleDiagnostics_formatTogether() {
        var parser = PegParser.builder(LIST_GRAMMAR)
            .recovery(RecoveryStrategy.ADVANCED)
            .build()
            .unwrap();

        var input = "abc, 123, def";
        var result = parser.parseCstWithDiagnostics(input);

        var formatted = result.formatDiagnostics("input.txt");

        assertTrue(formatted.contains("error:"));
        assertTrue(formatted.contains("-->"));
    }

    // ========================================================================
    // Custom Diagnostic Creation
    // ========================================================================

    /**
     * Create custom error diagnostic.
     */
    @Test
    void customDiagnostics_error() {
        var source = "return;";
        var span = SourceSpan.of(
            SourceLocation.at(1, 1, 0),
            SourceLocation.at(1, 7, 6)
        );

        var error = Diagnostic.error("E0001", "missing return value", span)
            .withLabel("expected value")
            .withHelp("add a return value");

        assertEquals(Diagnostic.Severity.ERROR, error.severity());
        assertEquals("E0001", error.code());

        var formatted = error.format(source, "test.js");
        assertTrue(formatted.contains("error[E0001]"));
    }

    /**
     * Create custom warning diagnostic.
     */
    @Test
    void customDiagnostics_warning() {
        var source = "let unused = 42;";
        var span = SourceSpan.of(
            SourceLocation.at(1, 5, 4),
            SourceLocation.at(1, 11, 10)
        );

        var warning = Diagnostic.warning("unused variable", span)
            .withLabel("'unused' is never read")
            .withNote("consider removing or using this variable");

        assertEquals(Diagnostic.Severity.WARNING, warning.severity());

        var formatted = warning.format(source, "test.js");
        assertTrue(formatted.contains("warning:"));
    }

    // ========================================================================
    // Error Nodes in CST
    // ========================================================================

    /**
     * Error nodes contain skipped text.
     */
    @Test
    void errorNodes_containSkippedText() {
        var parser = PegParser.builder(LIST_GRAMMAR)
            .recovery(RecoveryStrategy.ADVANCED)
            .build()
            .unwrap();

        var input = "abc, @@@, def";
        var result = parser.parseCstWithDiagnostics(input);

        // Find error node
        var errorNode = findErrorNode(result.node());
        if (errorNode != null) {
            assertTrue(errorNode.skippedText().contains("@"),
                "Error node should contain skipped text");
        }
    }

    // ========================================================================
    // Error Statistics
    // ========================================================================

    /**
     * Get error and warning counts.
     */
    @Test
    void errorStatistics() {
        var parser = PegParser.builder(LIST_GRAMMAR)
            .recovery(RecoveryStrategy.ADVANCED)
            .build()
            .unwrap();

        var input = "abc, 123, def";
        var result = parser.parseCstWithDiagnostics(input);

        int errorCount = result.errorCount();
        assertTrue(errorCount > 0, "Should have errors");
    }

    /**
     * Iterate diagnostics for custom processing.
     */
    @Test
    void iterateDiagnostics() {
        var parser = PegParser.builder(LIST_GRAMMAR)
            .recovery(RecoveryStrategy.ADVANCED)
            .build()
            .unwrap();

        var input = "abc, 123, def";
        var result = parser.parseCstWithDiagnostics(input);

        List<String> messages = new ArrayList<>();
        for (var diag : result.diagnostics()) {
            var loc = diag.span().start();
            messages.add(String.format("[%d:%d] %s",
                loc.line(), loc.column(), diag.message()));
        }

        assertFalse(messages.isEmpty());
    }

    // ========================================================================
    // IDE Integration
    // ========================================================================

    /**
     * Convert diagnostics to LSP format.
     */
    @Test
    void convertToLspFormat() {
        var parser = PegParser.builder(LIST_GRAMMAR)
            .recovery(RecoveryStrategy.ADVANCED)
            .build()
            .unwrap();

        var input = "abc, 123";
        var result = parser.parseCstWithDiagnostics(input);

        record LspDiagnostic(int line, int column, int severity, String message) {}

        var lspDiagnostics = result.diagnostics().stream()
            .map(d -> new LspDiagnostic(
                d.span().start().line() - 1,  // LSP uses 0-based
                d.span().start().column() - 1,
                d.severity() == Diagnostic.Severity.ERROR ? 1 : 2,
                d.message()
            ))
            .toList();

        assertFalse(lspDiagnostics.isEmpty());
    }

    /**
     * Extract error ranges for highlighting.
     */
    @Test
    void extractErrorRanges() {
        var parser = PegParser.builder(LIST_GRAMMAR)
            .recovery(RecoveryStrategy.ADVANCED)
            .build()
            .unwrap();

        var input = "abc, @@@";
        var result = parser.parseCstWithDiagnostics(input);

        record ErrorRange(int start, int end) {}

        var ranges = result.diagnostics().stream()
            .map(d -> new ErrorRange(
                d.span().start().offset(),
                d.span().end().offset()
            ))
            .toList();

        assertFalse(ranges.isEmpty());
    }

    // ========================================================================
    // Helper Methods
    // ========================================================================

    private CstNode.Error findErrorNode(CstNode node) {
        if (node == null) return null;
        if (node instanceof CstNode.Error err) return err;
        if (node instanceof CstNode.NonTerminal nt) {
            for (var child : nt.children()) {
                var found = findErrorNode(child);
                if (found != null) return found;
            }
        }
        return null;
    }
}
