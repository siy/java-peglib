package org.pragmatica.peg.playground;

import org.junit.jupiter.api.Test;
import org.pragmatica.peg.PegParser;

import static org.assertj.core.api.Assertions.assertThat;

class ParseTracerTest {

    private static final String SIMPLE_GRAMMAR = """
            Sum <- Number '+' Number
            Number <- < [0-9]+ >
            %whitespace <- [ \\t]*
            """;

    @Test
    void start_createsEmptyTracer() {
        var tracer = ParseTracer.start();
        assertThat(tracer.records()).isEmpty();
        assertThat(tracer.ruleEntries()).isZero();
        assertThat(tracer.cacheHits()).isZero();
    }

    @Test
    void recordRuleEnter_incrementsCounterAndAppendsRecord() {
        var tracer = ParseTracer.start();
        tracer.recordRuleEnter("Rule", 0);

        assertThat(tracer.ruleEntries()).isEqualTo(1);
        assertThat(tracer.records()).hasSize(1);
        assertThat(tracer.records().getFirst().kind()).isEqualTo(TraceRecord.EventKind.RULE_ENTER);
        assertThat(tracer.records().getFirst().rule()).isEqualTo("Rule");
    }

    @Test
    void recordCacheEvents_updateCounters() {
        var tracer = ParseTracer.start();
        tracer.recordCacheHit("A", 0);
        tracer.recordCacheMiss("B", 1);
        tracer.recordCachePut("B", 1);
        tracer.recordCutFired("C", 2);

        assertThat(tracer.cacheHits()).isEqualTo(1);
        assertThat(tracer.cacheMisses()).isEqualTo(1);
        assertThat(tracer.cachePuts()).isEqualTo(1);
        assertThat(tracer.cutsFired()).isEqualTo(1);
        assertThat(tracer.records()).hasSize(4);
    }

    @Test
    void walkCst_synthesisesRuleEventsAndTalliesNodes() {
        var parser = PegParser.fromGrammar(SIMPLE_GRAMMAR).unwrap();
        var cst = parser.parseCst("12 + 34").unwrap();

        var tracer = ParseTracer.start();
        var result = tracer.walkCst(cst);

        assertThat(result.nodes()).isGreaterThan(0);
        assertThat(tracer.ruleEntries()).isGreaterThan(0);
        assertThat(tracer.records())
                .anyMatch(r -> r.kind() == TraceRecord.EventKind.RULE_ENTER);
        assertThat(tracer.records())
                .anyMatch(r -> r.kind() == TraceRecord.EventKind.RULE_SUCCESS);
    }

    @Test
    void stats_combinesCountersWithWalkResult() {
        var tracer = ParseTracer.start();
        tracer.recordRuleEnter("X", 0);
        tracer.recordCacheHit("X", 0);
        tracer.recordCacheMiss("Y", 5);

        var stats = tracer.stats(7, 3, 1);

        assertThat(stats.nodeCount()).isEqualTo(7);
        assertThat(stats.triviaCount()).isEqualTo(3);
        assertThat(stats.diagnosticCount()).isEqualTo(1);
        assertThat(stats.ruleEntries()).isEqualTo(1);
        assertThat(stats.cacheHits()).isEqualTo(1);
        assertThat(stats.cacheMisses()).isEqualTo(1);
        assertThat(stats.timeMicros()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void countNodes_and_countTrivia_matchWalk() {
        var parser = PegParser.fromGrammar(SIMPLE_GRAMMAR).unwrap();
        var cst = parser.parseCst("12 + 34").unwrap();

        var tracer = ParseTracer.start();
        var walk = tracer.walkCst(cst);

        assertThat(ParseTracer.countNodes(cst)).isEqualTo(walk.nodes());
        assertThat(ParseTracer.countTrivia(cst)).isEqualTo(walk.trivia());
    }

    @Test
    void pretty_containsCounterHeader() {
        var tracer = ParseTracer.start();
        tracer.recordRuleEnter("R", 0);
        tracer.recordCacheHit("R", 0);

        String out = tracer.pretty();

        assertThat(out).contains("rule entries:");
        assertThat(out).contains("cache hits:");
        assertThat(out).contains("R");
    }
}
