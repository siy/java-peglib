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
import org.openjdk.jmh.infra.Blackhole;

import org.pragmatica.peg.v6.Parser;
import org.pragmatica.peg.v6.PegParser;
import org.pragmatica.peg.v6.cst.ParseResult;
import org.pragmatica.peg.v6.incremental.IncrementalParser;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * Phase D.1.x latency benchmark — interactive-edit reparse via {@link IncrementalParser}.
 *
 * <p>Uses {@link Mode#SampleTime} so JMH emits a percentile distribution (p50, p90, p95,
 * p99, p99.9, p100) for each (fixture × editKind) combination. Microsecond output unit.
 *
 * <p>Per-invocation reset: {@link Level#Invocation} setup restores a captured
 * {@link IncrementalParser.Snapshot} so every measured invocation applies the
 * SAME edit to the SAME starting state. Without a reset, sequential edits would
 * compound and percentiles would drift. The previous implementation rebuilt the
 * parser via {@code new IncrementalParser(...)} per invocation, which paid a full
 * initial parse on every measurement and dominated the timing. The current
 * snapshot/restore path is O(1) reference assignment, so the measurement reflects
 * edit latency alone.
 *
 * <p>Edit scenarios cover real interactive workloads: single-char insert/delete (typing),
 * line-comment paste, identifier rename. Offsets are picked at file midpoint to avoid
 * edge-case behaviour at the first/last line.
 */
@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
@Fork(1)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 2)
public class IncrementalEditBenchmark {

    private static final Path GRAMMAR_PATH = Path.of("src/test/resources/java25.peg");
    private static final Path REFERENCE_FIXTURE =
        Path.of("src/test/resources/perf-corpus/large/FactoryClassGenerator.java.txt");
    private static final Path SELFHOST_FIXTURE =
        Path.of("src/test/resources/bench-fixtures/Java25SelfHost-v51.java.txt");

    @Param({"reference", "selfhost"})
    public String fixture;

    @Param({"insert_char", "delete_char", "insert_line", "rename_identifier"})
    public String editKind;

    private Parser parser;
    private String originalInput;
    private IncrementalParser incremental;
    private IncrementalParser.Snapshot initialSnapshot;
    private int editOffset;
    private int editLen;
    private String editText;

    @Setup(Level.Trial)
    public void setupTrial() throws Exception {
        var grammarText = Files.readString(GRAMMAR_PATH, StandardCharsets.UTF_8);
        PegParser.clearCache();
        parser = PegParser.fromGrammar(grammarText).unwrap();

        var fixturePath = "reference".equals(fixture) ? REFERENCE_FIXTURE : SELFHOST_FIXTURE;
        originalInput = Files.readString(fixturePath, StandardCharsets.UTF_8);

        // Build the IncrementalParser once per trial; the constructor pays a full
        // initial parse. Capture the post-construction state for O(1) per-invocation
        // restore.
        incremental = new IncrementalParser(parser, originalInput);
        initialSnapshot = incremental.snapshot();

        // Pick edit parameters once per trial — they depend only on input + editKind.
        var midOffset = originalInput.length() / 2;
        var lineStart = originalInput.lastIndexOf('\n', midOffset) + 1;

        switch (editKind) {
            case "insert_char" -> {
                editOffset = lineStart;
                editLen = 0;
                editText = " ";
            }
            case "delete_char" -> {
                editOffset = lineStart;
                editLen = 1;
                editText = "";
            }
            case "insert_line" -> {
                editOffset = lineStart;
                editLen = 0;
                editText = "// added line\n";
            }
            case "rename_identifier" -> {
                var idStart = -1;
                var scanLimit = Math.min(originalInput.length() - 5, midOffset + 1000);
                for (var i = midOffset; i < scanLimit; i++) {
                    var c = originalInput.charAt(i);
                    if (Character.isJavaIdentifierStart(c)) {
                        var end = i;
                        while (end < originalInput.length()
                                && Character.isJavaIdentifierPart(originalInput.charAt(end))) {
                            end++;
                        }
                        if (end - i >= 5) {
                            idStart = i;
                            break;
                        }
                    }
                }
                if (idStart < 0) {
                    idStart = lineStart;
                }
                editOffset = idStart;
                var idEnd = idStart;
                while (idEnd < originalInput.length()
                        && Character.isJavaIdentifierPart(originalInput.charAt(idEnd))) {
                    idEnd++;
                }
                editLen = idEnd - idStart;
                editText = "renamedXXX";
            }
            default -> throw new IllegalStateException("unknown editKind: " + editKind);
        }
    }

    @Setup(Level.Invocation)
    public void resetState() {
        // O(1) reference-restore so every measurement applies the SAME edit to
        // the SAME starting CST without paying a fresh full parse per invocation.
        incremental.restore(initialSnapshot);
    }

    @Benchmark
    public ParseResult edit(Blackhole bh) {
        var result = incremental.edit(editOffset, editLen, editText);
        bh.consume(result);
        return result;
    }
}
