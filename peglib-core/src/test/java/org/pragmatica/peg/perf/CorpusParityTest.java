package org.pragmatica.peg.perf;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Parity check: for every file in {@code perf-corpus/}, the live CST hash (produced
 * by the generated Java 25 parser) must match the checked-in baseline under
 * {@code perf-corpus-baseline/}. Any generator change that alters tree shape lights
 * this test up.
 *
 * <p><b>Rule-hit counting semantics.</b> Rule-hit histograms (in
 * {@code ruleHits.txt} / {@code ruleCoverage.txt}) measure <em>CST-visible rule
 * names</em>, not grammar rules invoked during parsing. Passthrough rules
 * (e.g. {@code ClassDecl}, {@code MethodDecl}) whose output is re-labelled by
 * child rules via {@code wrapWithRuleName} never appear in the CST, so they are
 * structurally absent from the histogram. The hash parity check is the primary
 * correctness gate; the rule-hit histogram is a coarse smoke signal for detecting
 * unexpected CST changes.
 */
class CorpusParityTest {

    private static final Path CORPUS_ROOT = Path.of("src/test/resources/perf-corpus");
    private static final Path BASELINE_ROOT = Path.of("src/test/resources/perf-corpus-baseline");

    @BeforeAll
    static void loadParserOnce() {
        GeneratedJava25Parser.ensureLoaded();
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
    void cstHashMatchesBaseline(Path file) throws Exception {
        String source = Files.readString(file, StandardCharsets.UTF_8);
        var cst = GeneratedJava25Parser.parseToCst(source);
        String actual = CstHash.of(cst);

        Path relative = CORPUS_ROOT.relativize(file);
        Path hashFile = BASELINE_ROOT.resolve(relative + ".hash");
        assertThat(hashFile)
            .as("baseline file exists for " + relative + " (run BaselineGenerator.main to create)")
            .exists();
        String expected = Files.readString(hashFile, StandardCharsets.UTF_8).trim();

        assertThat(actual)
            .as("CST hash mismatch for %s%nexpected: %s%nactual:   %s", relative, expected, actual)
            .isEqualTo(expected);
    }
}
