package org.pragmatica.peg.analyzer;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.pragmatica.peg.grammar.GrammarParser;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 0.7.3 — {@code grammar.unreachable-kind}: a token kind allocated to a rule that no input can
 * produce.
 *
 * <p>The defect this catches cost several rounds of hand diagnosis during the 0.7.2 migration.
 * One rule out-prioritised another matching the same text, so every identifier in the language
 * lexed as the wrong kind while the intended rule sat allocated and dead — no guard firing, no
 * diagnostic, nothing failing. The grammar compiled, parsed, and meant something else.
 *
 * <h2>The false-positive test is the important one</h2>
 *
 * <p>A lint check that fires on a correct grammar is noise, and noise gets switched off. The
 * naive formulation of this check — "the kind is not the accept kind of any DFA state" — reports
 * FIVE rules on {@code java25.peg}, which is corpus-validated against javac at 99.45%. Measured,
 * not guessed: `Keyword`, `Modifier`, `PrimType`, `RestrictedTypeName`, `IllegalLocalClassMod`.
 * Every one is a literal-set rule whose individual literals carry their own higher-priority
 * kinds, so the rule's own kind never wins — correct behaviour, not a defect.
 *
 * <p>{@link #java25ProducesNoUnreachableKindFindings()} is therefore the test that decides
 * whether this check is worth having, and it is written against the real grammar rather than a
 * fixture for exactly that reason.
 */
class UnreachableKindTest {
    private static AnalyzerReport analyze(String grammarText) {
        return Analyzer.analyze(GrammarParser.parse(grammarText)
                                             .unwrap());
    }

    private static long unreachableKindFindings(AnalyzerReport report) {
        return report.findings()
                     .stream()
                     .filter(f -> "grammar.unreachable-kind".equals(f.tag()))
                     .count();
    }

    @Test
    void reportsARuleShadowedByAHigherPriorityTwin() {
        // Alpha and Beta match exactly the same language. Alpha is declared first and wins on
        // priority at every position, so Beta's kind is allocated and never produced. Both are
        // referenced from a PARSER rule, so neither is inlined away — which matters, because an
        // inlined rule is a different (and legitimate) reason for a kind to be absent.
        var report = analyze("""
                             Doc   <- Alpha Beta
                             Alpha <- < [a-z]+ >
                             Beta  <- < [a-z]+ >
                             %whitespace <- [ \\t\\r\\n]*
                             """);

        assertThat(report.findings()).anyMatch(f -> "grammar.unreachable-kind".equals(f.tag()) && "Beta".equals(f.ruleName()));
        assertThat(report.findings()).noneMatch(f -> "grammar.unreachable-kind".equals(f.tag()) && "Alpha".equals(f.ruleName()));
    }

    @Test
    void doesNotReportRulesThatDiffer() {
        // The control for the test above. Same shape, but the two rules match disjoint inputs,
        // so neither shadows the other and nothing should be reported. Without this, a check
        // that flagged every rule would pass the shadowing test.
        var report = analyze("""
                             Doc   <- Alpha Beta
                             Alpha <- < [a-z]+ >
                             Beta  <- < [0-9]+ >
                             %whitespace <- [ \\t\\r\\n]*
                             """);

        assertThat(unreachableKindFindings(report)).isZero();
    }

    @Test
    void doesNotReportLiteralSetRulesRepresentedByTheirKeywordKinds() {
        // The false-positive class in miniature. Keyword's own kind never wins — each literal
        // carries its own — but the rule is lexed, under those kinds. Reporting it would be
        // wrong, and this is the shape that makes the naive check unusable on real grammars.
        var report = analyze("""
                             Doc     <- Word+
                             Word    <- !Keyword < [a-z]+ >
                             Keyword <- 'if' / 'else' / 'while'
                             %whitespace <- [ \\t\\r\\n]*
                             """);

        assertThat(unreachableKindFindings(report)).isZero();
    }

    @Test
    void java25ProducesNoUnreachableKindFindings() throws Exception {
        // The gate. java25.peg is corpus-validated against javac's own parse phase at 99.45%,
        // so any finding here is a false positive by definition. The naive formulation of this
        // check reports five.
        var grammarText = Files.readString(Path.of("src/test/resources/java25.peg"));
        var report = analyze(grammarText);

        assertThat(report.findings()
                         .stream()
                         .filter(f -> "grammar.unreachable-kind".equals(f.tag()))
                         .toList())
        .withFailMessage("java25.peg is corpus-validated; any unreachable-kind finding here is a false positive")
        .isEmpty();
    }

    @Test
    void findingNamesTheRuleAndItsKind() {
        // The 0.7.2 diagnosis was slow because nothing said which rule was dead. The message has
        // to carry the rule name and the allocated kind or it does not save anyone the rounds.
        var report = analyze("""
                             Doc   <- Alpha Beta
                             Alpha <- < [a-z]+ >
                             Beta  <- < [a-z]+ >
                             %whitespace <- [ \\t\\r\\n]*
                             """);
        var finding = report.findings()
                            .stream()
                            .filter(f -> "grammar.unreachable-kind".equals(f.tag()))
                            .findFirst()
                            .orElseThrow();

        assertThat(finding.message()).contains("Beta")
                                     .contains("higher-priority");
        assertThat(finding.severity()).isEqualTo(Finding.Severity.WARNING);
    }
}
