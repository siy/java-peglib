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

import org.pragmatica.lang.Result;
import org.pragmatica.peg.PegParser;
import org.pragmatica.peg.grammar.GrammarParser;
import org.pragmatica.peg.parser.Parser;
import org.pragmatica.peg.parser.ParserConfig;
import org.pragmatica.peg.tree.CstNode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * Phase F.1 baseline — 0.5.x warm-parse benchmark across the same Java25
 * format-examples fixtures used by {@link Java25V6ParseBenchmark}, so the two
 * harnesses can be diffed apples-to-apples.
 *
 * <p>Uses the 0.5.x interpreter path ({@link PegParser#fromGrammarWithoutActions})
 * with {@link ParserConfig#DEFAULT}, mirroring how the v6 parser is wired (no
 * per-variant tunables). The interpreter parser is reusable across {@code parseCst}
 * calls because each call allocates its own {@code ParsingContext}.
 */
@BenchmarkMode({Mode.AverageTime, Mode.Throughput})
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@State(Scope.Benchmark)
public class Java25V51ParseBenchmark {

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
        var grammar = GrammarParser.parse(grammarText).unwrap();
        parser = PegParser.fromGrammarWithoutActions(grammar, ParserConfig.DEFAULT).unwrap();
        input = Files.readString(FIXTURE_DIR.resolve(fixture), StandardCharsets.UTF_8);
    }

    @Benchmark
    public Result<CstNode> parse() {
        return parser.parseCst(input);
    }
}
