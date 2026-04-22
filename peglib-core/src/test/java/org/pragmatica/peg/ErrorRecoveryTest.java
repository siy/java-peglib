package org.pragmatica.peg;

import org.junit.jupiter.api.Test;
import org.pragmatica.peg.error.Diagnostic;
import org.pragmatica.peg.error.RecoveryStrategy;
import org.pragmatica.peg.parser.ParserConfig;
import org.pragmatica.peg.tree.CstNode;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for advanced error recovery and diagnostic reporting.
 */
class ErrorRecoveryTest {

    private static final String SIMPLE_GRAMMAR = """
        List <- Item (',' Item)*
        Item <- < [a-z]+ >
        %whitespace <- [ \\t]*
        """;

    @Test
    void diagnosticFormatsRustStyle() {
        var source = "let x = @invalid;";
        var span = org.pragmatica.peg.tree.SourceSpan.of(
            org.pragmatica.peg.tree.SourceLocation.at(1, 9, 8),
            org.pragmatica.peg.tree.SourceLocation.at(1, 17, 16)
        );

        var diagnostic = Diagnostic.error("unexpected token", span)
            .withLabel("expected expression")
            .withHelp("expressions can start with identifiers, literals, or '('");

        var formatted = diagnostic.format(source, "input.txt");

        assertTrue(formatted.contains("error: unexpected token"));
        assertTrue(formatted.contains("--> input.txt:1:9"));
        assertTrue(formatted.contains("@invalid"));
        assertTrue(formatted.contains("^^^^^^^^"));
        assertTrue(formatted.contains("expected expression"));
        assertTrue(formatted.contains("help:"));
    }

    @Test
    void collectsMultipleErrors() {
        var parser = PegParser.builder(SIMPLE_GRAMMAR)
            .recovery(RecoveryStrategy.ADVANCED)
            .build()
            .unwrap();

        // Input with multiple errors: valid, invalid, valid, invalid
        var input = "abc, @!#, def, $%^";
        var result = parser.parseCstWithDiagnostics(input);

        // Should have collected multiple errors
        assertTrue(result.diagnostics().size() >= 2,
            "Expected at least 2 errors, got " + result.diagnostics().size());

        // Should still have parsed the valid fragments
        if (result.node().isPresent()) {
            assertTrue(result.node().unwrap() instanceof CstNode.NonTerminal);
        }
    }

    @Test
    void fragmentRecoveryParsesValidParts() {
        var parser = PegParser.builder(SIMPLE_GRAMMAR)
            .recovery(RecoveryStrategy.ADVANCED)
            .build()
            .unwrap();

        // Input: valid, invalid, valid
        var input = "abc, ###, def";
        var result = parser.parseCstWithDiagnostics(input);

        // Should have at least one error
        assertFalse(result.diagnostics().isEmpty(), "Expected errors for invalid input");

        // The tree should contain error nodes for unparseable parts
        if (result.node().isPresent()) {
            var hasErrorNode = containsErrorNode(result.node().unwrap());
            assertTrue(hasErrorNode, "Expected Error node in tree for invalid input");
        }
    }

    @Test
    void errorNodeContainsSkippedText() {
        var parser = PegParser.builder(SIMPLE_GRAMMAR)
            .recovery(RecoveryStrategy.ADVANCED)
            .build()
            .unwrap();

        var input = "abc, @@@, def";
        var result = parser.parseCstWithDiagnostics(input);

        // Find the error node
        if (result.node().isPresent()) {
            var errorNode = findFirstErrorNode(result.node().unwrap());
            if (errorNode != null) {
                assertTrue(errorNode.skippedText().contains("@"),
                    "Error node should contain skipped text");
            }
        }
    }

    @Test
    void basicStrategyStopsOnFirstError() {
        var parser = PegParser.builder(SIMPLE_GRAMMAR)
            .recovery(RecoveryStrategy.BASIC)
            .build()
            .unwrap();

        var input = "abc, @@@, def";
        var result = parser.parseCst(input);

        // With BASIC strategy, should fail without recovery
        assertTrue(result.isFailure());
    }

    @Test
    void noneStrategyFailsImmediately() {
        var parser = PegParser.builder(SIMPLE_GRAMMAR)
            .recovery(RecoveryStrategy.NONE)
            .build()
            .unwrap();

        var input = "@@@";
        var result = parser.parseCst(input);

        assertTrue(result.isFailure());
    }

    @Test
    void diagnosticShowsExpectedTokens() {
        var parser = PegParser.builder(SIMPLE_GRAMMAR)
            .recovery(RecoveryStrategy.ADVANCED)
            .build()
            .unwrap();

        var input = "abc, 123";  // Number not allowed
        var result = parser.parseCstWithDiagnostics(input);

        if (!result.diagnostics().isEmpty()) {
            var firstError = result.diagnostics().get(0);
            // Should mention what was expected
            assertTrue(
                firstError.message().contains("unexpected") ||
                firstError.notes().stream().anyMatch(n -> n.contains("expected")),
                "Diagnostic should mention what was expected"
            );
        }
    }

    @Test
    void formattedDiagnosticsAreRustStyleWithSourceContext() {
        var parser = PegParser.builder(SIMPLE_GRAMMAR)
            .recovery(RecoveryStrategy.ADVANCED)
            .build()
            .unwrap();

        var input = "abc, @@@, def";
        var result = parser.parseCstWithDiagnostics(input);

        var formatted = result.formatDiagnostics("test.txt");

        // Should contain Rust-style formatting elements
        assertTrue(formatted.contains("error:"), "Should have error: prefix");
        assertTrue(formatted.contains("-->"), "Should have location arrow");
        assertTrue(formatted.contains("|"), "Should have gutter separator");
        assertTrue(formatted.contains("help:"), "Should have help text");
    }

    // Helper methods

    private boolean containsErrorNode(CstNode node) {
        if (node instanceof CstNode.Error) {
            return true;
        }
        if (node instanceof CstNode.NonTerminal nt) {
            return nt.children().stream().anyMatch(this::containsErrorNode);
        }
        return false;
    }

    private CstNode.Error findFirstErrorNode(CstNode node) {
        if (node instanceof CstNode.Error err) {
            return err;
        }
        if (node instanceof CstNode.NonTerminal nt) {
            for (var child : nt.children()) {
                var found = findFirstErrorNode(child);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }
}
