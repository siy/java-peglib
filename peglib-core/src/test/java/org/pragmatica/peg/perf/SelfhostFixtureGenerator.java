package org.pragmatica.peg.perf;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import org.pragmatica.peg.PegParser;

/**
 * One-shot fixture generator for the canonical "selfhost" benchmark input.
 *
 * <p>Produces {@code peglib-core/src/test/resources/bench-fixtures/Java25SelfHost-v51.java.txt}
 * by running the 0.5.x source generator over the {@code java25.peg} grammar and ASCII-sanitizing
 * the result so the v6 lexer (ASCII-only character class) accepts every byte.
 *
 * <p>The output file is a CHECKED-IN benchmark artifact — both
 * {@code Java25LargeFixturesBenchmark} (0.6.0) and {@code Java25LargeFixturesV51Benchmark} (0.5.x)
 * read it, ensuring identical input bytes for apples-to-apples comparison.
 *
 * <p>Run manually when the {@code java25.peg} grammar changes:
 * <pre>{@code
 * mvn -pl peglib-core test \
 *     -Dtest=SelfhostFixtureGenerator#generate \
 *     -DfailIfNoTests=false \
 *     -DexcludedGroups= \
 *     -Djbct.skip=true
 * }</pre>
 *
 * <p>Disabled by default so it does not run during normal test sweeps.
 */
@Disabled("One-shot fixture generator. Run manually when java25 grammar changes.")
class SelfhostFixtureGenerator {

    private static final Path GRAMMAR_PATH = Path.of("src/test/resources/java25.peg");
    private static final Path OUTPUT_PATH =
        Path.of("src/test/resources/bench-fixtures/Java25SelfHost-v51.java.txt");

    @Test
    void generate() throws Exception {
        var grammarText = Files.readString(GRAMMAR_PATH, StandardCharsets.UTF_8);
        var sourceResult = PegParser.generateCstParser(
            grammarText, "selfhost.gen", "Java25SelfHost51");
        if (sourceResult.isFailure()) {
            throw new IllegalStateException("Failed to generate 0.5.x parser source: " + sourceResult);
        }
        var source = sourceResult.unwrap();

        var sanitized = new StringBuilder(source.length());
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            sanitized.append(c < 0x80 ? c : ' ');
        }

        Files.createDirectories(OUTPUT_PATH.getParent());
        Files.writeString(OUTPUT_PATH, sanitized.toString(), StandardCharsets.UTF_8);

        System.out.println("Wrote " + OUTPUT_PATH.toAbsolutePath()
            + " (" + sanitized.length() + " bytes, "
            + countLines(sanitized) + " lines)");
    }

    private static int countLines(CharSequence s) {
        int n = 1;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '\n') {
                n++;
            }
        }
        return n;
    }
}
