package org.pragmatica.peg.v6.perf;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

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

/**
 * 0.6.0 lex-then-parse benchmark on canonical LARGE Java25 fixtures:
 * <ul>
 *   <li>{@code reference} — {@code FactoryClassGenerator.java.txt} (~1900 LOC, 99KB)</li>
 *   <li>{@code selfhost} — the Java25 v6 generator's own emitted parser source
 *       (parser-parsing-itself; ~30K LOC)</li>
 * </ul>
 *
 * <p>Counterpart 0.5.x-gen bench: {@link Java25LargeFixturesV51Benchmark}.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Fork(1)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
public class Java25LargeFixturesBenchmark {

    private static final Path GRAMMAR_PATH = Path.of("src/test/resources/java25.peg");
    private static final Path REFERENCE_FIXTURE =
        Path.of("src/test/resources/perf-corpus/large/FactoryClassGenerator.java.txt");
    /**
     * Pre-generated, ASCII-sanitized 0.5.x source-generated parser. Checked in so both
     * {@link Java25LargeFixturesBenchmark} and {@link Java25LargeFixturesV51Benchmark}
     * parse the SAME bytes — true apples-to-apples comparison. Regenerate via
     * {@code SelfhostFixtureGenerator} when the Java25 grammar changes.
     */
    private static final Path SELFHOST_FIXTURE =
        Path.of("src/test/resources/bench-fixtures/Java25SelfHost-v51.java.txt");

    @Param({"reference", "selfhost"})
    public String fixture;

    private Parser v6Parser;
    private String input;

    @Setup(Level.Trial)
    public void setup() throws Exception {
        var grammarText = Files.readString(GRAMMAR_PATH, StandardCharsets.UTF_8);
        PegParser.clearCache();
        v6Parser = PegParser.fromGrammar(grammarText).unwrap();

        if ("reference".equals(fixture)) {
            input = sanitizeForJava25Lexer(Files.readString(REFERENCE_FIXTURE, StandardCharsets.UTF_8));
        } else if ("selfhost".equals(fixture)) {
            // Pre-generated, ASCII-sanitized 0.5.x source-generated parser. SAME bytes as
            // the v51 bench reads — guarantees apples-to-apples comparison.
            input = Files.readString(SELFHOST_FIXTURE, StandardCharsets.UTF_8);
        } else {
            throw new IllegalStateException("unknown fixture: " + fixture);
        }
        // Warm cache: one parse to ensure the JIT sees representative shapes.
        v6Parser.parse(input);
    }

    /**
     * The Java25 v6 lexer's character class only covers ASCII; both fixtures contain
     * non-ASCII characters inside line comments (em-dash etc.) which the 0.5.x packrat
     * interpreter masks via {@code .} but the v6 DFA rejects. Strip them to non-ASCII
     * spaces so the size and structure of the input is preserved while keeping the bench
     * apples-to-apples between the two paths. Documented grammar-coverage gap; orthogonal
     * to perf comparison.
     */
    private static String sanitizeForJava25Lexer(String s) {
        var sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            sb.append(c < 0x80 ? c : ' ');
        }
        return sb.toString();
    }

    @Benchmark
    public ParseResult v6_parse() {
        return v6Parser.parse(input);
    }
}
