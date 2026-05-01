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
 * Round-trip equality: {@code reconstruct(parse(source)) == source} byte-for-byte
 * for every corpus file. Catches dropped tokens, lost trivia, and span off-by-ones.
 *
 * <p>Enabled in 0.3.5: Bug A (full-list pending-trivia restore on backtrack),
 * Bug B (cache-safe leading trivia), Bug C (cache empty-leading wrapped node),
 * Bug C' (rule-exit trailing-trivia attribution to last child), and Bug C''
 * (Sequence children rollback on element failure) collectively achieve
 * byte-equal round-trip for all 22 corpus fixtures.
 */
class RoundTripTest {

    private static final Path CORPUS_ROOT = Path.of("src/test/resources/perf-corpus");

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
    void reconstructsOriginalSource(Path file) throws Exception {
        String source = Files.readString(file, StandardCharsets.UTF_8);
        var cst = GeneratedJava25Parser.parseToCst(source);
        String reconstructed = CstReconstruct.reconstruct(cst);

        assertThat(reconstructed)
            .as("round-trip mismatch for %s (len orig=%d, recon=%d)",
                CORPUS_ROOT.relativize(file), source.length(), reconstructed.length())
            .isEqualTo(source);
    }
}
