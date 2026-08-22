package org.pragmatica.peg.lexer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.pragmatica.peg.PegParser;
import org.pragmatica.peg.analyzer.Analyzer;
import org.pragmatica.peg.analyzer.AnalyzerReport;
import org.pragmatica.peg.grammar.GrammarParser;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 0.7.3 — {@code < >} around a whole rule body is an override, and is honoured or reported.
 *
 * <p>The token boundary is documented as "an explicit override and is trusted". It was not: a body
 * mixing rule references with a terminal — {@code Qualified <- < ColId '.' ColId >} — was
 * classified PARSER by a check that ran BEFORE the override was consulted, so the rule silently
 * became three tokens and the author's instruction vanished. Nothing failed, which is why it went
 * unnoticed: the three-token reading parses perfectly well.
 *
 * <p>Two changes, because the boundary cannot always be delivered:
 * <ul>
 *   <li>where the body is lexically compilable, the boundary now wins and the rule fuses;</li>
 *   <li>where it is not — the body names a guarded rule, whose lookahead the DFA cannot compile —
 *       the rule still falls back to PARSER, but the analyzer says so via
 *       {@code grammar.token-boundary-ignored} instead of leaving it to be inferred from a token
 *       stream.</li>
 * </ul>
 */
class TokenBoundaryOverrideTest {
    private static final String FUSED = """
        Doc   <- Q ';'
        Q     <- < Plain '.' Plain >
        Plain <- < [a-z]+ >
        %whitespace <- [ \\t\\r\\n]*
        """;

    private static final String UNFUSED = """
        Doc   <- Q ';'
        Q     <- Plain '.' Plain
        Plain <- < [a-z]+ >
        %whitespace <- [ \\t\\r\\n]*
        """;

    private static final String GUARDED_IN_BOUNDARY = """
        Doc      <- Q ';'
        Q        <- < ColId '.' ColId >
        ColId    <- !Reserved < [a-z]+ >
        Reserved <- ('select' / 'from') ![a-z0-9_]
        %whitespace <- [ \\t\\r\\n]*
        """;

    private static List<String> contentTokens(String grammar, String input) {
        var tokens = PegParser.fromGrammar(grammar)
                              .unwrap()
                              .parse(input)
                              .cst()
                              .tokens();
        var out = new ArrayList<String>();

        for (int i = 0; i < tokens.count(); i++) {
            if (!tokens.isTrivia(i)) {
                out.add(tokens.kindName(i) + "<" + tokens.textAt(i) + ">");
            }
        }

        return out;
    }

    private static AnalyzerReport analyze(String grammar) {
        return Analyzer.analyze(GrammarParser.parse(grammar)
                                             .unwrap());
    }

    private static List<String> boundaryFindings(AnalyzerReport report) {
        return report.findings()
                     .stream()
                     .filter(f -> "grammar.token-boundary-ignored".equals(f.tag()))
                     .map(f -> f.ruleName())
                     .toList();
    }

    @Test
    void aBoundaryOverAReferenceAndTerminalBodyFuses() {
        // The fix. Before, the terminals-and-references check reached PARSER first and the
        // boundary was never consulted.
        assertThat(contentTokens(FUSED, "a.b ;")).containsExactly("Q<a.b>", "INLINE__SEMI<;>");
    }

    @Test
    void withoutTheBoundaryTheSameBodyStaysThreeTokens() {
        // The control, and the reason the fix is narrow: an unmarked body mixing references and
        // terminals is ordinary parsing — Sum <- Number '+' Number must NOT fuse.
        assertThat(contentTokens(UNFUSED, "a.b ;")).containsExactly("Plain<a>", "INLINE__DOT<.>", "Plain<b>", "INLINE__SEMI<;>");
    }

    @Test
    void bothReadingsStillParseCleanly() {
        assertThat(PegParser.fromGrammar(FUSED)
                            .unwrap()
                            .parse("a.b ;")
                            .diagnostics()).isEmpty();
        assertThat(PegParser.fromGrammar(UNFUSED)
                            .unwrap()
                            .parse("a.b ;")
                            .diagnostics()).isEmpty();
    }

    @Test
    void aReferenceOnlyBoundaryStillFuses() {
        // Pre-existing behaviour, pinned so the reordering above cannot quietly break it.
        var grammar = """
            Doc  <- INE ';'
            INE  <- < IfKW NotKW ExistsKW >
            IfKW <- 'if'
            NotKW <- 'not'
            ExistsKW <- 'exists'
            %whitespace <- [ \\t\\r\\n]*
            """;

        assertThat(contentTokens(grammar, "ifnotexists ;")).containsExactly("INE<ifnotexists>", "INLINE__SEMI<;>");
    }

    @Test
    void aBoundaryThatCannotBeHonouredIsReported() {
        // The other half. The rule still falls back to PARSER — inlining ColId drags its
        // !Reserved lookahead in, and the DFA has none — but that is now stated rather than
        // silently done.
        assertThat(boundaryFindings(analyze(GUARDED_IN_BOUNDARY))).containsExactly("Q");
    }

    @Test
    void anHonouredBoundaryIsNotReported() {
        // Without this, a check that flagged every < > rule would pass the test above.
        assertThat(boundaryFindings(analyze(FUSED))).isEmpty();
    }

    @Test
    void aBodyWithoutABoundaryIsNotReported() {
        assertThat(boundaryFindings(analyze(UNFUSED))).isEmpty();
    }

    @Test
    void aBoundaryAroundOneAlternativeIsNotReported() {
        // java25's shape: Literal <- < 'null' > / CharLit wraps ONE alternative, not the body.
        // Such a rule is correctly PARSER and flagging it would be noise — which is exactly the
        // kind of false positive that gets a lint check switched off.
        var grammar = """
            Doc     <- Lit ';'
            Lit     <- < 'null' ![a-z] > / Word
            Word    <- < [a-z]+ >
            %whitespace <- [ \\t\\r\\n]*
            """;

        assertThat(boundaryFindings(analyze(grammar))).isEmpty();
    }

    @Test
    void aBoundaryOnARuleThatEndsUpMixedIsAlsoReported() {
        // The question the check asks is "did this become one token?", so the test is NOT LEXER
        // rather than == PARSER. A rule can be promoted to MIXED instead of demoted to PARSER
        // and still have failed to honour its boundary; checking only for PARSER skipped that
        // half silently, which is the same class of miss the check exists to catch.
        var grammar = """
            Doc   <- Q ';'
            Q     <- < Pair [a-z] >
            Pair  <- Left Right
            Left  <- < [a-z]+ >
            Right <- < [0-9]+ >
            %whitespace <- [ \\t\\r\\n]*
            """;

        assertThat(RuleClassifier.classify(GrammarParser.parse(grammar)
                                                        .unwrap())
                                 .unwrap()
                                 .kinds())
        .withFailMessage("fixture must actually produce a MIXED rule, or this asserts nothing")
        .containsEntry("Q", RuleKind.MIXED);
        assertThat(boundaryFindings(analyze(grammar))).contains("Q");
    }

    @Test
    void java25ReportsNoIgnoredBoundaries() throws Exception {
        // The gate, same discipline as grammar.unreachable-kind: java25.peg is corpus-validated,
        // so any finding here is a false positive by definition.
        var report = analyze(Files.readString(Path.of("src/test/resources/java25.peg")));

        assertThat(boundaryFindings(report))
        .withFailMessage("java25.peg is corpus-validated; an ignored-boundary finding here is a false positive")
        .isEmpty();
    }
}
