package org.pragmatica.peg.playground;

import org.junit.jupiter.api.Test;
import org.pragmatica.peg.playground.v6.PlaygroundEngineV6;
import org.pragmatica.peg.playground.v6.PlaygroundEngineV6.ParseRequest;
import org.pragmatica.peg.v6.PegParser;
import org.pragmatica.peg.v6.cst.CstArray;
import org.pragmatica.peg.v6.token.TokenArray;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers {@link ParseTracer}'s 0.6.x flat-CST walk. The legacy recursive-CST
 * overloads are exercised by {@link ParseTracerTest} and disappear with the
 * 0.5.x code path.
 */
class CstArrayTracerTest {

    private static final String GRAMMAR = """
            Sum <- Number '+' Number
            Number <- [0-9]+
            %whitespace <- [ \\t]*
            """;

    private static final String ERROR_GRAMMAR = """
            Pair <- Head 'b'
            Head <- 'a' '#'
            """;

    /** Whitespace alternative also lexes {@code //} and {@code /* *}{@code /} runs as trivia. */
    private static final String COMMENT_GRAMMAR = """
            Sum <- Number '+' Number
            Number <- [0-9]+
            %whitespace <- ([ \\t\\n] / '//' [^\\n]* / '/*' (!'*/' .)* '*/')*
            """;

    private static CstArray parse(String grammar, String input) {
        return PegParser.fromGrammar(grammar)
                        .expect("grammar should compile")
                        .parse(input)
                        .cst();
    }

    @Test
    void walkCst_synthesisesRuleEventsAndTalliesNodes() {
        var tracer = ParseTracer.start();

        var walk = tracer.walkCst(parse(GRAMMAR, "12 + 34"));

        assertThat(walk.nodes()).isGreaterThan(0);
        assertThat(tracer.ruleEntries()).isGreaterThan(0);
        assertThat(tracer.records())
                .anyMatch(r -> r.kind() == TraceRecord.EventKind.RULE_ENTER);
        assertThat(tracer.records())
                .anyMatch(r -> r.kind() == TraceRecord.EventKind.RULE_SUCCESS);
    }

    @Test
    void walkCst_emitsOneEnterAndOneSuccessPerNode() {
        var tracer = ParseTracer.start();

        var walk = tracer.walkCst(parse(GRAMMAR, "12 + 34"));

        assertThat(tracer.records()
                         .stream()
                         .filter(r -> r.kind() == TraceRecord.EventKind.RULE_ENTER)
                         .count()).isEqualTo(walk.nodes());
        assertThat(tracer.records()
                         .stream()
                         .filter(r -> r.kind() == TraceRecord.EventKind.RULE_SUCCESS)
                         .count()).isEqualTo(walk.nodes());
    }

    @Test
    void countNodes_and_countTrivia_matchWalk() {
        var cst = parse(GRAMMAR, "12 + 34");
        var tracer = ParseTracer.start();

        var walk = tracer.walkCst(cst);

        assertThat(ParseTracer.countNodes(cst)).isEqualTo(walk.nodes());
        assertThat(ParseTracer.countTrivia(cst)).isEqualTo(walk.trivia());
    }

    @Test
    void countNodes_matchesArrayNodeCount() {
        var cst = parse(GRAMMAR, "12 + 34");

        assertThat(ParseTracer.countNodes(cst)).isEqualTo(cst.nodeCount());
    }

    /**
     * Each whitespace / comment token is counted exactly once. {@code "12 + 34"}
     * lexes to {@code 12 · ␠ · + · ␠ · 34}, so two trivia tokens — not 0 (which
     * a per-node leading/trailing sum would report, since both gaps are interior
     * to the single rule node's span) and not 4 (which such a sum would report
     * wherever tokens do become separate leaves).
     */
    @Test
    void countTrivia_countsEachTriviaTokenExactlyOnce() {
        var cst = parse(GRAMMAR, "12 + 34");

        assertThat(ParseTracer.countTrivia(cst)).isEqualTo(2);
    }

    @Test
    void countTrivia_countsLeadingTrivia() {
        var cst = parse(GRAMMAR, " 12 + 34");

        assertThat(ParseTracer.countTrivia(cst)).isEqualTo(3);
    }

    @Test
    void countTrivia_agreesWithRawTriviaTokenScan() {
        var cst = parse(GRAMMAR, " 12 + 34 ");

        assertThat(ParseTracer.countTrivia(cst)).isEqualTo((int) triviaTokenCount(cst));
    }

    /**
     * The tracer's tally and the number the engine puts on the user-visible
     * stats line must be the same value — they share one helper precisely so
     * they cannot drift.
     */
    @Test
    void countTrivia_matchesEngineStatsTriviaCount() {
        var outcome = PlaygroundEngineV6.run(new ParseRequest(GRAMMAR, " 12 + 34"))
                                        .expect("engine should compile and parse");

        assertThat(outcome.stats()
                          .triviaCount()).isEqualTo(ParseTracer.countTrivia(outcome.cst()));
    }

    @Test
    void countTrivia_countsDocCommentTrivia() {
        var withDoc = parse(COMMENT_GRAMMAR, "/// doc\n12 + 34");
        var withoutDoc = parse(COMMENT_GRAMMAR, "12 + 34");

        assertThat(triviaKindsOf(withDoc)).contains("doc-line-comment");
        assertThat(ParseTracer.countTrivia(withDoc))
                .isGreaterThan(ParseTracer.countTrivia(withoutDoc));
    }

    @Test
    void countTrivia_countsLineCommentTrivia() {
        var cst = parse(COMMENT_GRAMMAR, "// plain\n12 + 34");

        assertThat(triviaKindsOf(cst)).contains("line-comment");
        assertThat(ParseTracer.countTrivia(cst)).isGreaterThan(0);
    }

    @Test
    void walkCst_recordsFailureAndNoteForErrorNodes() {
        var cst = parse(ERROR_GRAMMAR, "a#x");
        var tracer = ParseTracer.start();

        tracer.walkCst(cst);

        assertThat(hasErrorNode(cst)).as("recovery should flag at least one error node")
                                     .isTrue();
        assertThat(tracer.records())
                .anyMatch(r -> r.kind() == TraceRecord.EventKind.RULE_FAILURE);
        assertThat(tracer.records())
                .anyMatch(r -> r.kind() == TraceRecord.EventKind.NOTE
                               && r.detail()
                                   .startsWith("error region:"));
    }

    @Test
    void walkCst_leavesPackratCountersAtZero() {
        var tracer = ParseTracer.start();

        tracer.walkCst(parse(GRAMMAR, "12 + 34"));

        assertThat(tracer.cacheHits()).isZero();
        assertThat(tracer.cacheMisses()).isZero();
        assertThat(tracer.cachePuts()).isZero();
        assertThat(tracer.cutsFired()).isZero();
    }

    @Test
    void triviaKind_mapsAllFiveTriviaKinds() {
        assertThat(ParseTracer.triviaKind(TokenArray.KIND_WHITESPACE)).isEqualTo("whitespace");
        assertThat(ParseTracer.triviaKind(TokenArray.KIND_LINE_COMMENT)).isEqualTo("line-comment");
        assertThat(ParseTracer.triviaKind(TokenArray.KIND_BLOCK_COMMENT)).isEqualTo("block-comment");
        assertThat(ParseTracer.triviaKind(TokenArray.KIND_DOC_LINE_COMMENT)).isEqualTo("doc-line-comment");
        assertThat(ParseTracer.triviaKind(TokenArray.KIND_DOC_BLOCK_COMMENT)).isEqualTo("doc-block-comment");
    }

    @Test
    void triviaKind_reportsContentForUserKinds() {
        assertThat(ParseTracer.triviaKind(TokenArray.FIRST_USER_KIND)).isEqualTo("content");
    }

    private static boolean hasErrorNode(CstArray cst) {
        return cst.descendants(cst.rootIndex())
                  .anyMatch(cst::isError);
    }

    private static long triviaTokenCount(CstArray cst) {
        var tokens = cst.tokens();
        return IntStream.range(0, tokens.count())
                        .filter(tokens::isTrivia)
                        .count();
    }

    private static List<String> triviaKindsOf(CstArray cst) {
        var tokens = cst.tokens();
        return IntStream.range(0, tokens.count())
                        .filter(tokens::isTrivia)
                        .mapToObj(i -> ParseTracer.triviaKind(tokens.kindAt(i)))
                        .toList();
    }
}
