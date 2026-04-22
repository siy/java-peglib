package org.pragmatica.peg.perf;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
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
 * <p>Re-enables in a future release. Attribution threading is now implemented —
 * trivia between sibling sequence elements attaches to the following sibling's
 * {@code leadingTrivia} instead of being dropped. However, <b>trailing
 * intra-rule trivia</b> (trivia matched by a rule body that has no following
 * sibling to attach to) still requires rule-exit pos-rewind logic that is not
 * yet in place. Full byte-for-byte source reconstruction therefore still fails;
 * see {@code docs/TRIVIA-ATTRIBUTION.md} for the current state and remaining
 * work.
 */
@Disabled("Attribution threading landed in 0.2.4; full round-trip awaits rule-exit pos-rewind")
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
