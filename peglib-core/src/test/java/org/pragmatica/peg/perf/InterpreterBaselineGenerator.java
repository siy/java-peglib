package org.pragmatica.peg.perf;

import org.pragmatica.peg.PegParser;
import org.pragmatica.peg.grammar.GrammarParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Generates interpreter CST hashes for every file in {@code perf-corpus/} and
 * writes them under {@code src/test/resources/perf-corpus-interpreter-baseline/}.
 *
 * <p>Run this manually when (a) the interpreter's CST shape legitimately changes
 * and (b) the phase-1 parity review has blessed the change. The committed baseline
 * files are the ground truth enforced by {@link Phase1InterpreterParityTest}.
 *
 * <p>Usage: {@code mvn test-compile && java -cp ... InterpreterBaselineGenerator}.
 */
public final class InterpreterBaselineGenerator {

    private static final Path CORPUS_ROOT = Path.of("src/test/resources/perf-corpus");
    private static final Path BASELINE_ROOT = Path.of("src/test/resources/perf-corpus-interpreter-baseline");
    private static final Path GRAMMAR_PATH = Path.of("src/test/resources/java25.peg");

    private InterpreterBaselineGenerator() {}

    public static void main(String[] args) throws IOException {
        var grammarText = Files.readString(GRAMMAR_PATH, StandardCharsets.UTF_8);
        var grammar = GrammarParser.parse(grammarText).unwrap();
        var parser = PegParser.fromGrammarWithoutActions(grammar,
                                                         org.pragmatica.peg.parser.ParserConfig.DEFAULT)
                              .unwrap();

        List<Path> files;
        try (var walk = Files.walk(CORPUS_ROOT)) {
            files = walk.filter(Files::isRegularFile).sorted().toList();
        }
        int written = 0;
        for (var file : files) {
            var source = Files.readString(file, StandardCharsets.UTF_8);
            var cstResult = parser.parseCst(source);
            if (cstResult.isFailure()) {
                System.err.println("SKIP (parse failed): " + file + " -> " + cstResult);
                continue;
            }
            var hash = CstHash.of(cstResult.unwrap());
            var relative = CORPUS_ROOT.relativize(file);
            var out = BASELINE_ROOT.resolve(relative + ".hash");
            Files.createDirectories(out.getParent());
            Files.writeString(out, hash + "\n", StandardCharsets.UTF_8);
            written++;
            System.out.println(relative + " -> " + hash);
        }
        System.out.println("wrote " + written + " interpreter baselines under " + BASELINE_ROOT);
    }
}
