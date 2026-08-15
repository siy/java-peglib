package org.pragmatica.peg;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 0.7.2 — concurrent misses on the same grammar must serialise on the BUILD, not just on the
 * publish. Before single-flight, N racing threads each ran the full classify → DFA → compile
 * pipeline (~100-500 ms) and all but one result was discarded.
 */
class SingleFlightCacheTest {

    private static final String GRAMMAR = """
        Start <- Entry+
        Entry <- Ident '=' Number ';'
        Ident <- [a-z]+
        Number <- [0-9]+
        %whitespace <- [ \\t\\r\\n]*
        """;

    @Test
    void concurrentMissesShareOneParserInstance() throws Exception {
        PegParser.clearCache();
        var threads = 8;
        var start = new CountDownLatch(1);
        var done = new CountDownLatch(threads);
        var parsers = java.util.Collections.synchronizedList(new java.util.ArrayList<Parser>());
        var failures = new AtomicInteger();

        for (var i = 0; i < threads; i++) {
            Thread.ofPlatform()
                  .start(() -> {
                      try {
                          start.await();
                          PegParser.fromGrammar(GRAMMAR)
                                   .onSuccess(parsers::add)
                                   .onFailure(__ -> failures.incrementAndGet());
                      } catch (InterruptedException e) {
                          Thread.currentThread()
                                .interrupt();
                      } finally {
                          done.countDown();
                      }
                  });
        }

        start.countDown();
        assertThat(done.await(60, TimeUnit.SECONDS)).as("all racing threads must finish")
                                                    .isTrue();
        assertThat(failures.get()).isZero();
        assertThat(parsers).hasSize(threads);
        // Every racer must observe the SAME instance — proof they converged on one build
        // rather than each compiling their own and discarding the losers.
        assertThat(List.copyOf(parsers)).allMatch(p -> p == parsers.get(0));
        assertThat(PegParser.cacheSize()).isEqualTo(1);
    }

    @Test
    void failedBuildIsNotCachedAndCanBeRetried() {
        PegParser.clearCache();
        var broken = "Start <- Missing\n";

        assertThat(PegParser.fromGrammar(broken)
                            .isSuccess()).isFalse();
        // A failure must not leave a poisoned entry behind: the same text must be retryable,
        // and must fail the same way rather than inheriting a stale future.
        assertThat(PegParser.fromGrammar(broken)
                            .isSuccess()).isFalse();
        assertThat(PegParser.cacheSize()).as("a failed build leaves no cache entry")
                                         .isZero();
    }
}
