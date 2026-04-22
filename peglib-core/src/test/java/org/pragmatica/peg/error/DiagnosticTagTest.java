package org.pragmatica.peg.error;

import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Option;
import org.pragmatica.peg.PegParser;
import org.pragmatica.peg.tree.SourceLocation;
import org.pragmatica.peg.tree.SourceSpan;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for diagnostic tag metadata (0.2.4). Every built-in diagnostic-producing
 * site attaches a stable machine-readable tag alongside the user-facing message.
 */
class DiagnosticTagTest {

    @Test
    void diagnosticCarriesTagField() {
        var span = SourceSpan.at(SourceLocation.START);
        var diag = Diagnostic.error("test", span)
                             .withTag("error.custom");
        assertEquals(Option.some("error.custom"), diag.tag());
    }

    @Test
    void defaultDiagnosticHasNoTag() {
        var span = SourceSpan.at(SourceLocation.START);
        var diag = Diagnostic.error("test", span);
        assertEquals(Option.none(), diag.tag());
    }

    @Test
    void unexpectedInputDiagnosticIsTagged() {
        var grammar = """
            List <- Item (',' Item)*
            Item <- < [a-z]+ >
            %whitespace <- [ \\t]*
            """;
        var parser = PegParser.builder(grammar)
                              .recovery(RecoveryStrategy.ADVANCED)
                              .build()
                              .unwrap();
        var result = parser.parseCstWithDiagnostics("abc, @@@, def");
        assertTrue(!result.diagnostics()
                          .isEmpty(),
                   "expected at least one diagnostic for invalid input");
        var tags = result.diagnostics()
                         .stream()
                         .map(Diagnostic::tag)
                         .toList();
        assertTrue(tags.stream()
                       .anyMatch(Option::isPresent),
                   "at least one diagnostic should carry a tag, got: " + tags);
    }

    @Test
    void taggedDiagnosticSurvivesChainedBuilders() {
        var span = SourceSpan.at(SourceLocation.START);
        var diag = Diagnostic.error("x", span)
                             .withTag("error.foo")
                             .withLabel("lbl")
                             .withHelp("tip");
        // withLabel and withHelp must preserve the tag
        assertEquals(Option.some("error.foo"), diag.tag(),
                     "tag must survive withLabel/withHelp chain");
        assertTrue(diag.notes()
                       .stream()
                       .anyMatch(n -> n.contains("tip")));
        assertTrue(!diag.labels()
                        .isEmpty());
    }

    @Test
    void diagnosticsListContainsTagValues() {
        var grammar = """
            Word <- [a-z]+
            """;
        var parser = PegParser.builder(grammar)
                              .recovery(RecoveryStrategy.ADVANCED)
                              .build()
                              .unwrap();
        var result = parser.parseCstWithDiagnostics("123");
        // At least one diagnostic in the recovery-mode failure path has tag
        // from the built-in set {error.unexpected-input, error.expected,
        // error.unclosed}.
        List<Diagnostic> diags = result.diagnostics();
        boolean anyBuiltIn = diags.stream()
                                  .anyMatch(d -> d.tag()
                                                  .isPresent() && d.tag()
                                                                   .unwrap()
                                                                   .startsWith("error."));
        assertTrue(anyBuiltIn,
                   "expected at least one 'error.*' tag, got: " + diags);
    }
}
