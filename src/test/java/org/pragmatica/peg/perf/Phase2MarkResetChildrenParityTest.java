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
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase-2 §7.2 CST parity: for every file in {@code perf-corpus/}, the CST hash produced
 * by the generator with all phase-1 flags on <b>and</b> {@code markResetChildren=true}
 * (and {@code choiceDispatch=false} — this test isolates §7.2 from §7.1) must match the
 * checked-in baseline (which was recorded from the clone+addAll generator). Proves that
 * the mark/trim optimization for {@code children} restore in Choice emission is
 * CST-preserving.
 *
 * <p>A failure here means the mark/trim restore diverges from the clone+addAll path on
 * some input — report the file and the first divergent node and leave the baselines
 * untouched.
 */
class Phase2MarkResetChildrenParityTest {

    private static final Path CORPUS_ROOT = Path.of("src/test/resources/perf-corpus");
    private static final Path BASELINE_ROOT = Path.of("src/test/resources/perf-corpus-baseline");

    private static final ParserConfig PHASE2_MARK_RESET_ON = new ParserConfig(
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
        /* markResetChildren      */ true,
        /* inlineLocations        */ false,
        /* selectivePackrat       */ false
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
    void phase2MarkResetChildrenCstHashMatchesBaseline(Path file) throws Exception {
        String source = Files.readString(file, StandardCharsets.UTF_8);
        var cst = GeneratedJava25Parser.parseToCst(source, PHASE2_MARK_RESET_ON);
        String actual = CstHash.of(cst);

        Path relative = CORPUS_ROOT.relativize(file);
        Path hashFile = BASELINE_ROOT.resolve(relative + ".hash");
        assertThat(hashFile)
            .as("baseline file exists for " + relative)
            .exists();
        String expected = Files.readString(hashFile, StandardCharsets.UTF_8).trim();

        assertThat(actual)
            .as("Phase-2 markResetChildren CST hash mismatch for %s%nexpected: %s%nactual:   %s", relative, expected, actual)
            .isEqualTo(expected);
    }
}
