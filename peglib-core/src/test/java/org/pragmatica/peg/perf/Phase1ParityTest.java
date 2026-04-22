package org.pragmatica.peg.perf;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.pragmatica.peg.error.RecoveryStrategy;
import org.pragmatica.peg.parser.ParserConfig;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase-1 CST parity: for every file in {@code perf-corpus/}, the CST hash produced
 * by the optimized parser (all phase-1 perf flags ON; phase-2 flags OFF) must match
 * the checked-in baseline produced by the flag-off parser. Proves that the phase-1
 * emitter changes are CST-preserving.
 *
 * <p>A failure in this test indicates that one (or more) of the phase-1 optimizations
 * is CST-mutating and must be fixed before its flag can be enabled by default.
 */
class Phase1ParityTest {

    private static final Path CORPUS_ROOT = Path.of("src/test/resources/perf-corpus");
    private static final Path BASELINE_ROOT = Path.of("src/test/resources/perf-corpus-baseline");

    private static final ParserConfig PHASE1_ALL_ON = new ParserConfig(
        /* packratEnabled         */ true,
        /* recoveryStrategy       */ RecoveryStrategy.BASIC,
        /* captureTrivia          */ true,
        /* fastTrackFailure       */ true,
        /* literalFailureCache    */ true,
        /* charClassFailureCache  */ true,
        /* bulkAdvanceLiteral     */ true,
        /* skipWhitespaceFastPath */ true,
        /* reuseEndLocation       */ true,
        /* choiceDispatch         */ false,
        /* markResetChildren      */ false,
        /* inlineLocations        */ false,
        /* selectivePackrat       */ false,
        /* packratSkipRules       */ Set.of()
    );

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
    void phase1OptimizedCstHashMatchesBaseline(Path file) throws Exception {
        String source = Files.readString(file, StandardCharsets.UTF_8);
        var cst = GeneratedJava25Parser.parseToCst(source, PHASE1_ALL_ON);
        String actual = CstHash.of(cst);

        Path relative = CORPUS_ROOT.relativize(file);
        Path hashFile = BASELINE_ROOT.resolve(relative + ".hash");
        assertThat(hashFile)
            .as("baseline file exists for " + relative)
            .exists();
        String expected = Files.readString(hashFile, StandardCharsets.UTF_8).trim();

        assertThat(actual)
            .as("Phase-1 optimized CST hash mismatch for %s%nexpected: %s%nactual:   %s", relative, expected, actual)
            .isEqualTo(expected);
    }
}
