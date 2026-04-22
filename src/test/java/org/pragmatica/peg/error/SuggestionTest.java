package org.pragmatica.peg.error;

import org.junit.jupiter.api.Test;
import org.pragmatica.peg.PegParser;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the grammar-level {@code %suggest RuleName} directive (0.2.4).
 * Designates a rule's literal alternatives as a suggestion vocabulary; when
 * the parser fails on an identifier-like token near the rule's position,
 * Levenshtein distance ≤ 2 produces a "did you mean 'X'?" hint.
 */
class SuggestionTest {

    private static final String GRAMMAR_WITH_SUGGEST = """
        %suggest Keyword
        Program <- Keyword+
        Keyword <- 'class' / 'interface' / 'enum' / 'record' / 'sealed'
        %whitespace <- [ \\t\\n]*
        """;

    @Test
    void suggestionHintForNearMissKeyword() {
        var parser = PegParser.builder(GRAMMAR_WITH_SUGGEST)
                              .recovery(RecoveryStrategy.ADVANCED)
                              .build()
                              .unwrap();
        // "clss" is one edit away from "class"
        var result = parser.parseCstWithDiagnostics("clss");
        assertTrue(!result.diagnostics()
                          .isEmpty(),
                   "near-miss input should produce at least one diagnostic");
        boolean hasHint = result.diagnostics()
                                .stream()
                                .flatMap(d -> d.notes()
                                               .stream())
                                .anyMatch(note -> note.contains("did you mean") && note.contains("class"));
        assertTrue(hasHint,
                   "expected 'did you mean class' hint, diagnostics: " + result.diagnostics());
    }

    @Test
    void noSuggestionForDistantMatch() {
        var parser = PegParser.builder(GRAMMAR_WITH_SUGGEST)
                              .recovery(RecoveryStrategy.ADVANCED)
                              .build()
                              .unwrap();
        // "xyzzy" is far from every vocabulary entry — no hint should fire.
        var result = parser.parseCstWithDiagnostics("xyzzy");
        boolean hasHint = result.diagnostics()
                                .stream()
                                .flatMap(d -> d.notes()
                                               .stream())
                                .anyMatch(note -> note.contains("did you mean"));
        assertTrue(!hasHint,
                   "no suggestion should fire for distant token, got: " + result.diagnostics());
    }

    @Test
    void grammarWithoutSuggestHasNoHint() {
        var grammar = """
            Program <- Keyword+
            Keyword <- 'class' / 'enum'
            %whitespace <- [ ]*
            """;
        var parser = PegParser.builder(grammar)
                              .recovery(RecoveryStrategy.ADVANCED)
                              .build()
                              .unwrap();
        var result = parser.parseCstWithDiagnostics("clss");
        boolean hasHint = result.diagnostics()
                                .stream()
                                .flatMap(d -> d.notes()
                                               .stream())
                                .anyMatch(note -> note.contains("did you mean"));
        assertTrue(!hasHint,
                   "grammar without %suggest should never emit hints");
    }
}
