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
 * Phase-2 §7.4 CST parity: for every file in {@code perf-corpus/}, the CST hash produced
 * by the generator with all phase-1 flags on <b>and</b> {@code selectivePackrat=true}
 * (with a non-trivial skip set covering three substantial rules: {@code Identifier},
 * {@code QualifiedName}, {@code Type}) must match the checked-in baseline. Proves the
 * fundamental correctness invariant for §7.4: packrat is an optimization, not a
 * correctness feature — skipping the cache for any subset of rules must never change
 * the parsed CST.
 *
 * <p>The other phase-2 structural flags ({@code choiceDispatch}, {@code markResetChildren},
 * {@code inlineLocations}) are OFF here so this test isolates §7.4 from their emission
 * changes.
 *
 * <p>A failure here would mean skipping the cache for some rule produces a different CST
 * than caching it — which would indicate a packrat semantics bug (e.g. a rule whose
 * side-effects are observed by packrat-cached siblings). Report the file and the first
 * divergent node and leave the baselines untouched.
 */
class Phase2SelectivePackratParityTest {

    private static final Path CORPUS_ROOT = Path.of("src/test/resources/perf-corpus");
    private static final Path BASELINE_ROOT = Path.of("src/test/resources/perf-corpus-baseline");

    private static final ParserConfig PHASE2_SELECTIVE_PACKRAT_ON = new ParserConfig(
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
        /* packratSkipRules       */ Set.of("Identifier", "QualifiedName", "Type")
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
    void phase2SelectivePackratCstHashMatchesBaseline(Path file) throws Exception {
        String source = Files.readString(file, StandardCharsets.UTF_8);
        var cst = GeneratedJava25Parser.parseToCst(source, PHASE2_SELECTIVE_PACKRAT_ON);
        String actual = CstHash.cstHash(cst);

        Path relative = CORPUS_ROOT.relativize(file);
        Path hashFile = BASELINE_ROOT.resolve(relative + ".hash");
        assertThat(hashFile)
            .as("baseline file exists for " + relative)
            .exists();
        String expected = Files.readString(hashFile, StandardCharsets.UTF_8).trim();

        assertThat(actual)
            .as("Phase-2 selectivePackrat (skip=%s) CST hash mismatch for %s%nexpected: %s%nactual:   %s",
                PHASE2_SELECTIVE_PACKRAT_ON.packratSkipRules(), relative, expected, actual)
            .isEqualTo(expected);
    }
}
