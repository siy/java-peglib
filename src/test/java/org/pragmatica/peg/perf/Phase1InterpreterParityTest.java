package org.pragmatica.peg.perf;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.pragmatica.peg.PegParser;
import org.pragmatica.peg.grammar.GrammarParser;
import org.pragmatica.peg.parser.Parser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase-1 interpreter CST parity: for every file in {@code perf-corpus/}, the CST hash
 * produced by the {@code PegEngine} interpreter (with all phase-1 optimizations applied
 * unconditionally, per 0.2.3) must match the interpreter baseline captured at the
 * moment of the 0.2.3 port.
 *
 * <p><b>Why a dedicated interpreter baseline.</b> The generator baseline at
 * {@code perf-corpus-baseline/} does <em>not</em> match the interpreter's CST shape —
 * a pre-existing divergence from before 0.2.3 (generator and interpreter build
 * different tree shapes in some corners). The interpreter baseline at
 * {@code perf-corpus-interpreter-baseline/} was generated with the pre-port
 * {@code PegEngine} (commit 2f89903); this test asserts that the phase-1 port
 * preserves that shape file-for-file. Regenerate with
 * {@link InterpreterBaselineGenerator} only when a CST change in the interpreter
 * has been reviewed and accepted.
 */
class Phase1InterpreterParityTest {

    private static final Path CORPUS_ROOT = Path.of("src/test/resources/perf-corpus");
    private static final Path BASELINE_ROOT = Path.of("src/test/resources/perf-corpus-interpreter-baseline");
    private static final Path GRAMMAR_PATH = Path.of("src/test/resources/java25.peg");

    private static volatile Parser interpreter;

    @BeforeAll
    static void setUp() throws IOException {
        var grammarText = Files.readString(GRAMMAR_PATH, StandardCharsets.UTF_8);
        var grammar = GrammarParser.parse(grammarText).unwrap();
        interpreter = PegParser.fromGrammarWithoutActions(grammar,
                                                          org.pragmatica.peg.parser.ParserConfig.DEFAULT)
                               .unwrap();
    }

    static Stream<Path> corpusFiles() throws IOException {
        if (!Files.isDirectory(CORPUS_ROOT)) {
            return Stream.empty();
        }
        List<Path> files;
        try (var walk = Files.walk(CORPUS_ROOT)) {
            files = walk.filter(Files::isRegularFile).sorted().toList();
        }
        return files.stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("corpusFiles")
    void interpreterCstHashMatchesBaseline(Path file) throws Exception {
        String source = Files.readString(file, StandardCharsets.UTF_8);
        var cstResult = interpreter.parseCst(source);
        assertThat(cstResult.isSuccess())
            .as("interpreter parse succeeded for %s; failure=%s", file, cstResult)
            .isTrue();
        String actual = CstHash.of(cstResult.unwrap());

        Path relative = CORPUS_ROOT.relativize(file);
        Path hashFile = BASELINE_ROOT.resolve(relative + ".hash");
        assertThat(hashFile)
            .as("baseline file exists for " + relative)
            .exists();
        String expected = Files.readString(hashFile, StandardCharsets.UTF_8).trim();

        assertThat(actual)
            .as("interpreter CST hash mismatch for %s%nexpected: %s%nactual:   %s", relative, expected, actual)
            .isEqualTo(expected);
    }
}
