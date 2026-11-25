package org.pragmatica.peg.perf;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.pragmatica.peg.PegParser;
import org.pragmatica.peg.token.LexFn;
import org.pragmatica.peg.token.TokenArray;

/**
 * The lex half of an incremental edit on its own (issue #49): {@code spliceLex} against re-lexing the
 * whole edited input, which is the trivially correct alternative. Uses only API that exists in 0.7.4,
 * so the same file measures both versions. Run from {@code peglib-core/}.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
@Fork(1)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 8, time = 1)
public class SpliceLexBenchmark {
    @Param({"reference", "selfhost"})
    public String fixture;

    // insert_char: a space at the start of a middle line. open_comment: "/*" there, which turns
    // everything up to the next "*/" into one comment, so the re-lex cannot stop at the next token.
    @Param({"insert_char", "open_comment"})
    public String editKind;

    private LexFn lexFn;
    private LexFn windowed;
    private TokenArray tokens;
    private String edited;
    private int offset;
    private String text;

    @Setup
    public void setup() throws Exception {
        var grammar = Files.readString(Path.of("src/test/resources/java25.peg"), StandardCharsets.UTF_8);
        var parser = PegParser.fromGrammar(grammar).unwrap();
        var path = "reference".equals(fixture)
                   ? Path.of("src/test/resources/perf-corpus/large/FactoryClassGenerator.java.txt")
                   : Path.of("src/test/resources/bench-fixtures/Java25SelfHost-v51.java.txt");
        var input = Files.readString(path, StandardCharsets.UTF_8);

        // 0.7.5's compiled lexer is itself a resumable LexFn; 0.7.4's is not a LexFn at all. Written
        // through Object so the same source compiles against both.
        Object lexer = parser.lexer();
        lexFn = lexer instanceof LexFn direct
                ? direct
                : parser.lexer()::lex;
        windowed = parser.lexer()::lex;
        tokens = lexFn.lex(input);
        offset = input.lastIndexOf('\n', input.length() / 2) + 1;
        text = "open_comment".equals(editKind)
               ? "/*"
               : " ";
        edited = input.substring(0, offset) + text + input.substring(offset);
    }

    @Benchmark
    public TokenArray splice() {
        return tokens.spliceLex(lexFn, offset, 0, text);
    }

    /** Through a plain LexFn, which 0.7.5 splices over windows. Identical to {@link #splice} on 0.7.4. */
    @Benchmark
    public TokenArray spliceWindowed() {
        return tokens.spliceLex(windowed, offset, 0, text);
    }

    @Benchmark
    public TokenArray fullRelex() {
        return lexFn.lex(edited);
    }
}
