package org.pragmatica.peg.v6.perf;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import org.pragmatica.peg.v6.Parser;
import org.pragmatica.peg.v6.PegParser;
import org.pragmatica.peg.v6.cst.ParseResult;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * Phase F.1 warm-parse benchmark for the 0.6.0 lex-then-parse pipeline across the
 * Java25 format-examples corpus.
 *
 * <p>One {@link Parser} instance is built from the {@code java25.peg} grammar at
 * {@link Level#Trial} via {@link PegParser#fromGrammar(String)} (cold compile cost
 * is hoisted out of the measurement) and reused across invocations. Each invocation
 * lex-then-parses the chosen fixture and returns the {@link ParseResult} so the JIT
 * cannot DCE the work.
 *
 * <p>Counterpart cold-compile bench is {@link Java25V6ColdCompileBenchmark}; counterpart
 * 0.5.1 throughput bench (different generated-parser pipeline) is
 * {@code org.pragmatica.peg.bench.Java25ParseBenchmark}.
 */
@BenchmarkMode({Mode.AverageTime, Mode.Throughput})
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@State(Scope.Benchmark)
public class Java25V6ParseBenchmark {

    private static final Path GRAMMAR_PATH = Path.of("src/test/resources/java25.peg");
    private static final Path FIXTURE_DIR = Path.of("src/test/resources/perf-corpus/format-examples");

    @Param({
        "BlankLines.java",
        "Comments.java",
        "CompoundAssignments.java",
        "Enums.java",
        "ExhaustiveSwitchPatterns.java",
        "Imports.java",
        "KeywordPrefixedIdentifiers.java",
        "ModuleInfo.java",
        "Records.java",
        "SwitchExpressions.java",
        "TernaryOperators.java",
        "TextBlocks.java"
    })
    public String fixture;

    private Parser parser;
    private String input;

    @Setup(Level.Trial)
    public void setup() throws Exception {
        String grammarText = Files.readString(GRAMMAR_PATH, StandardCharsets.UTF_8);
        PegParser.clearCache();
        parser = PegParser.fromGrammar(grammarText).unwrap();
        input = Files.readString(FIXTURE_DIR.resolve(fixture), StandardCharsets.UTF_8);
    }

    @Benchmark
    public ParseResult parse() {
        return parser.parse(input);
    }
}
