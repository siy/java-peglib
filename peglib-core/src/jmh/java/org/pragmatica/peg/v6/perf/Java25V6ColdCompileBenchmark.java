package org.pragmatica.peg.v6.perf;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import org.pragmatica.peg.v6.Parser;
import org.pragmatica.peg.v6.PegParser;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * Phase F.1 cold-compile benchmark — measures the cost of running the full
 * {@link PegParser#fromGrammar(String)} pipeline (classify → DFA → generate-lexer →
 * compile-lexer → generate-parser → compile-parser) starting from an empty cache.
 *
 * <p>Each invocation calls {@link PegParser#clearCache()} before {@code fromGrammar},
 * so every measurement is a true cold compile. {@link Mode#SingleShotTime} with
 * {@code batchSize=1} keeps each measurement an isolated event; we still take five
 * measurement iterations so JMH can compute a stddev.
 */
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 1)
@Measurement(iterations = 5, batchSize = 1)
@Fork(value = 1, warmups = 0)
@State(Scope.Thread)
public class Java25V6ColdCompileBenchmark {

    private static final Path GRAMMAR_PATH = Path.of("src/test/resources/java25.peg");

    private String grammarText;

    @Setup(Level.Iteration)
    public void setup() throws Exception {
        grammarText = Files.readString(GRAMMAR_PATH, StandardCharsets.UTF_8);
        PegParser.clearCache();
    }

    @Benchmark
    public Parser fromGrammar() {
        return PegParser.fromGrammar(grammarText).unwrap();
    }
}
