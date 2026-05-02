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
 * Phase-2 §7.1 + §7.2 + §7.3 combined CST parity: for every file in {@code perf-corpus/},
 * the CST hash produced with all phase-1 flags on plus all three phase-2 <i>structural</i>
 * flags ({@code choiceDispatch}, {@code markResetChildren}, {@code inlineLocations}) on
 * simultaneously must match the checked-in baseline. {@code selectivePackrat} remains off —
 * that flag is commit #8's concern.
 *
 * <p>This is the integration invariant ensuring the three structural optimizations compose
 * safely. Each is independently verified by its own parity test; this test catches any
 * interaction bug that only surfaces when all three are enabled together.
 *
 * <p>A failure here means the combined structural emission diverges from the baseline on
 * some input — report the file and the first divergent node and leave the baselines
 * untouched.
 */
class Phase2AllStructuralParityTest {

    private static final Path CORPUS_ROOT = Path.of("src/test/resources/perf-corpus");
    private static final Path BASELINE_ROOT = Path.of("src/test/resources/perf-corpus-baseline");

    private static final ParserConfig PHASE2_ALL_STRUCTURAL = new ParserConfig(
        /* packratEnabled         */ true,
        /* recoveryStrategy       */ RecoveryStrategy.BASIC,
        /* captureTrivia          */ true,
        /* fastTrackFailure       */ true,
        /* literalFailureCache    */ true,
        /* charClassFailureCache  */ true,
        /* bulkAdvanceLiteral     */ true,
        /* skipWhitespaceFastPath */ true,
        /* reuseEndLocation       */ true,
        /* choiceDispatch         */ true,
        /* markResetChildren      */ true,
        /* inlineLocations        */ true,
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
    void phase2AllStructuralCstHashMatchesBaseline(Path file) throws Exception {
        String source = Files.readString(file, StandardCharsets.UTF_8);
        var cst = GeneratedJava25Parser.parseToCst(source, PHASE2_ALL_STRUCTURAL);
        String actual = CstHash.cstHash(cst);

        Path relative = CORPUS_ROOT.relativize(file);
        Path hashFile = BASELINE_ROOT.resolve(relative + ".hash");
        assertThat(hashFile)
            .as("baseline file exists for " + relative)
            .exists();
        String expected = Files.readString(hashFile, StandardCharsets.UTF_8).trim();

        assertThat(actual)
            .as("Phase-2 combined (choiceDispatch + markResetChildren + inlineLocations) CST hash mismatch for %s%nexpected: %s%nactual:   %s",
                relative, expected, actual)
            .isEqualTo(expected);
    }
}
