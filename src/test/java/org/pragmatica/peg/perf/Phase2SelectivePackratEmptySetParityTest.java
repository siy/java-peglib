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
 * Phase-2 §7.4 flag-on / empty-skip-set no-op parity: for every file in
 * {@code perf-corpus/}, the CST hash produced with {@code selectivePackrat=true} but
 * {@code packratSkipRules=Set.of()} must match the checked-in baseline. Confirms that
 * enabling the flag without nominating any rules to skip is a pure no-op — every rule
 * still consults and populates the packrat cache exactly as it did before §7.4.
 *
 * <p>This is the defence-in-depth companion to {@code Phase2SelectivePackratParityTest}:
 * the latter proves that skipping a subset is correct; this one proves that the code
 * path gate on the flag itself introduces no unintended behaviour change when the skip
 * set is empty.
 *
 * <p>A failure here would mean the {@code selectivePackrat} gate logic diverges from the
 * unmodified baseline even when the skip-set is empty — a generator emission bug.
 */
class Phase2SelectivePackratEmptySetParityTest {

    private static final Path CORPUS_ROOT = Path.of("src/test/resources/perf-corpus");
    private static final Path BASELINE_ROOT = Path.of("src/test/resources/perf-corpus-baseline");

    private static final ParserConfig PHASE2_SELECTIVE_PACKRAT_EMPTY = new ParserConfig(
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
        /* selectivePackrat       */ true,
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
    void phase2SelectivePackratEmptySetCstHashMatchesBaseline(Path file) throws Exception {
        String source = Files.readString(file, StandardCharsets.UTF_8);
        var cst = GeneratedJava25Parser.parseToCst(source, PHASE2_SELECTIVE_PACKRAT_EMPTY);
        String actual = CstHash.of(cst);

        Path relative = CORPUS_ROOT.relativize(file);
        Path hashFile = BASELINE_ROOT.resolve(relative + ".hash");
        assertThat(hashFile)
            .as("baseline file exists for " + relative)
            .exists();
        String expected = Files.readString(hashFile, StandardCharsets.UTF_8).trim();

        assertThat(actual)
            .as("Phase-2 selectivePackrat (empty skip-set) CST hash mismatch for %s%nexpected: %s%nactual:   %s",
                relative, expected, actual)
            .isEqualTo(expected);
    }
}
