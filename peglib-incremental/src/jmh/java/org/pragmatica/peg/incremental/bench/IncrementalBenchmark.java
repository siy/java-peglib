package org.pragmatica.peg.incremental.bench;

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
import org.pragmatica.peg.grammar.Grammar;
import org.pragmatica.peg.grammar.GrammarParser;
import org.pragmatica.peg.incremental.Edit;
import org.pragmatica.peg.incremental.IncrementalParser;
import org.pragmatica.peg.incremental.Session;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * SPEC §7.4 JMH harness — measures incremental edit performance per the
 * SPEC §8 target matrix:
 *
 * <ul>
 *   <li>{@code initialize} — full parse of the 1,900-LOC fixture
 *       ({@code FactoryClassGenerator.java.txt}). Target ≤ {@code peglib-core}
 *       full-parse time (~100 ms ceiling on Apple Silicon JDK 25).</li>
 *   <li>{@code singleCharEdit} — one-character insert at a typical interior
 *       offset. Target &lt; 1 ms median.</li>
 *   <li>{@code wordEdit} — replace a 6-char identifier with another 6-char
 *       identifier. Target &lt; 5 ms median.</li>
 *   <li>{@code lineEdit} — paste a ~80-char line at an interior offset.
 *       Target &lt; 20 ms median.</li>
 *   <li>{@code fullReparse} — explicit {@code session.reparseAll()}. Target
 *       ≤ full-parse time.</li>
 *   <li>{@code undoRestore} — restore a saved predecessor session reference.
 *       Target &lt; 0.01 ms (session retention is O(1)).</li>
 * </ul>
 *
 * <p>Numbers below the SPEC target column are honest. v1's wholesale
 * cache-invalidation choice and the back-reference-rule fallback policy
 * mean some edits will reach the root and fall back to full reparse — that
 * cost is included in the median, not hidden. Smoke-runs confirming the
 * harness builds and runs end-to-end land at
 * {@code docs/bench-results/incremental-v1-smoke.json}; the full matrix is
 * deferred to a later release cycle.
 *
 * <p>Run via:
 * <pre>{@code
 *   cd peglib-incremental
 *   mvn -Pbench -DskipTests package
 *   java -jar target/benchmarks.jar org.pragmatica.peg.incremental.bench.IncrementalBenchmark
 * }</pre>
 *
 * @since 0.3.2
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(2)
@State(Scope.Benchmark)
public class IncrementalBenchmark {

    private static final String GRAMMAR_RESOURCE = "/java25.peg";
    private static final String FIXTURE_RESOURCE = "/perf-corpus/large/FactoryClassGenerator.java.txt";

    @Param({"initialize", "singleCharEdit", "wordEdit", "lineEdit", "fullReparse", "undoRestore"})
    public String variant;

    private Grammar grammar;
    private IncrementalParser parser;
    private String fixtureSource;

    // For per-edit variants: a warm session at trial level, edited at invocation level.
    private Session warmSession;
    // Saved predecessor for undoRestore.
    private Session savedSession;
    // Edit configuration per variant.
    private int singleCharOffset;
    private int wordOffset;
    private int lineOffset;
    private String wordReplacement;
    private String lineInsertion;

    @Setup(Level.Trial)
    public void setup() throws Exception {
        var grammarText = loadResource(GRAMMAR_RESOURCE);
        fixtureSource = loadResource(FIXTURE_RESOURCE);
        grammar = GrammarParser.parse(grammarText).fold(
            cause -> { throw new IllegalStateException("grammar parse failed: " + cause.message()); },
            g -> g);
        parser = IncrementalParser.create(grammar);

        warmSession = parser.initialize(fixtureSource, fixtureSource.length() / 2);
        savedSession = warmSession;

        // Pick edit offsets that land in the file's body (not in the
        // boilerplate header). The 1,900-LOC fixture has substantial body
        // content past the first ~200 chars.
        singleCharOffset = pickInteriorOffset(fixtureSource, 0.4);
        wordOffset = pickInteriorOffset(fixtureSource, 0.5);
        lineOffset = pickInteriorOffset(fixtureSource, 0.6);
        wordReplacement = "abcdef";
        lineInsertion = "    var __jmhBenchInsert = computeSomething();\n";
    }

    /**
     * Pick an offset at fraction {@code frac} of the buffer, snapped to a
     * non-whitespace position so per-edit benchmarks don't trivially land
     * in trivia (which would skew toward best-case behaviour).
     */
    private static int pickInteriorOffset(String text, double frac) {
        int target = (int) (text.length() * frac);
        for (int i = target; i < text.length(); i++) {
            if (!Character.isWhitespace(text.charAt(i))) {
                return i;
            }
        }
        return target;
    }

    @Benchmark
    public Object run() {
        return switch (variant) {
            case "initialize" -> parser.initialize(fixtureSource, 0);
            case "singleCharEdit" -> warmSession.edit(new Edit(singleCharOffset, 0, "x"));
            case "wordEdit" -> warmSession.edit(new Edit(wordOffset, 0, wordReplacement));
            case "lineEdit" -> warmSession.edit(new Edit(lineOffset, 0, lineInsertion));
            case "fullReparse" -> warmSession.reparseAll();
            case "undoRestore" -> savedSession;
            default -> throw new IllegalArgumentException("Unknown variant: " + variant);
        };
    }

    static String loadResource(String resourcePath) throws Exception {
        try (var in = IncrementalBenchmark.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IllegalStateException("Resource not found on classpath: " + resourcePath);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
