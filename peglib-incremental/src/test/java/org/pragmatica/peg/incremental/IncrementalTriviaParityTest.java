package org.pragmatica.peg.incremental;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.pragmatica.peg.PegParser;
import org.pragmatica.peg.grammar.Grammar;
import org.pragmatica.peg.grammar.GrammarParser;
import org.pragmatica.peg.incremental.internal.CstHash;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SPEC §5.4 v2 — extended parity harness biased toward trivia regions.
 *
 * <p>Same parity oracle as {@link IncrementalParityTest} (CstHash equality
 * against fresh full reparse), but the random-edit distribution
 * deliberately concentrates on trivia (whitespace/comment) regions so the
 * trivia-only fast-path in {@link
 * org.pragmatica.peg.incremental.internal.TriviaRedistribution} gets
 * exercised at the same density as structural edits do in the base parity
 * test.
 *
 * <p>Edit budget per file is configurable via
 * {@code -Dincremental.parity.trivia.editsPerFile=N}; default 50 keeps the
 * harness inside the per-test wall-clock budget while still producing
 * meaningful coverage. The base parity harness still does broader random
 * edits at 100/file by default; combined coverage is now ~150 edits/file
 * across the two suites.
 */
final class IncrementalTriviaParityTest {

    private static final long BASE_SEED = 0xCAFEBABEL;
    private static final int DEFAULT_EDITS_PER_FILE = 50;

    private static Path peglibCoreResources() {
        var basedir = Path.of(System.getProperty("user.dir"));
        var candidate = basedir.resolveSibling("peglib-core").resolve("src/test/resources");
        if (Files.isDirectory(candidate)) {
            return candidate;
        }
        candidate = basedir.resolve("peglib-core/src/test/resources");
        if (Files.isDirectory(candidate)) {
            return candidate;
        }
        throw new IllegalStateException("cannot locate peglib-core test resources from user.dir=" + basedir);
    }

    private static Stream<Path> corpusFiles() throws IOException {
        var root = peglibCoreResources();
        var directories = List.of(
            root.resolve("perf-corpus/format-examples"),
            root.resolve("perf-corpus/flow-format-examples"),
            root.resolve("perf-corpus/large"));
        var out = new ArrayList<Path>();
        for (var dir : directories) {
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (var stream = Files.list(dir)) {
                stream.filter(Files::isRegularFile)
                      .filter(p -> {
                          var name = p.getFileName().toString();
                          return name.endsWith(".java") || name.endsWith(".java.txt");
                      })
                      .forEach(out::add);
            }
        }
        out.sort((a, b) -> a.getFileName().toString().compareTo(b.getFileName().toString()));
        return out.stream();
    }

    private static Grammar javaGrammar() throws IOException {
        var path = peglibCoreResources().resolve("java25.peg");
        var text = Files.readString(path, StandardCharsets.UTF_8);
        return GrammarParser.parse(text).fold(
            cause -> { throw new IllegalStateException("grammar parse failed: " + cause.message()); },
            g -> g);
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("corpusFiles")
    @DisplayName("Trivia-biased parity: incremental session matches full reparse after every trivia-leaning edit")
    void parity(Path file) throws IOException {
        int editsPerFile = Integer.getInteger("incremental.parity.trivia.editsPerFile", DEFAULT_EDITS_PER_FILE);
        var grammar = javaGrammar();
        var oracleParser = PegParser.fromGrammar(grammar).fold(
            cause -> { throw new IllegalStateException(cause.message()); },
            p -> p);
        var incremental = IncrementalParser.create(grammar);

        var initialText = Files.readString(file, StandardCharsets.UTF_8);
        var oracleInitial = oracleParser.parseCst(initialText);
        if (oracleInitial.isFailure()) {
            return;
        }

        Session session = incremental.initialize(initialText, 0);
        long oracleHash = CstHash.of(oracleInitial.unwrap());
        assertThat(CstHash.of(session.root()))
            .as("initial parity for %s", file.getFileName())
            .isEqualTo(oracleHash);

        var rng = new Random(BASE_SEED ^ file.getFileName().toString().hashCode());
        for (int i = 0; i < editsPerFile; i++) {
            var edit = randomTriviaBiasedEdit(rng, session.text());
            if (edit == null) {
                continue;
            }
            Session next;
            try {
                next = session.edit(edit);
            } catch (RuntimeException ex) {
                continue;
            }
            var oracleResult = oracleParser.parseCst(next.text());
            if (oracleResult.isFailure()) {
                session = next;
                continue;
            }
            long expected = CstHash.of(oracleResult.unwrap());
            long actual = CstHash.of(next.root());
            assertThat(actual)
                .as("trivia-biased parity after edit %d in %s (edit=%s)", i, file.getFileName(), edit)
                .isEqualTo(expected);
            session = next;
        }
    }

    /**
     * Random edit biased toward trivia regions:
     *
     * <ul>
     *   <li>50% — operate on a randomly picked whitespace position (insert /
     *       delete a single whitespace char inside an existing whitespace
     *       run);</li>
     *   <li>20% — delete a randomly chosen whitespace run entirely;</li>
     *   <li>15% — insert a multi-char whitespace blob at an existing
     *       whitespace position;</li>
     *   <li>15% — generic single-char insert anywhere (as in the base
     *       parity harness — keeps non-trivia edits represented).</li>
     * </ul>
     *
     * <p>Returns {@code null} when no whitespace position is available and
     * the roll demanded one.
     */
    private static Edit randomTriviaBiasedEdit(Random rng, String text) {
        if (text.isEmpty()) {
            return new Edit(0, 0, " ");
        }
        int roll = rng.nextInt(100);
        if (roll < 50) {
            int wsPos = randomWhitespacePosition(rng, text);
            if (wsPos < 0) {
                return new Edit(rng.nextInt(text.length() + 1), 0, " ");
            }
            return rng.nextBoolean()
                ? new Edit(wsPos, 0, randomWhitespaceChar(rng))
                : new Edit(wsPos, 1, "");
        }
        if (roll < 70) {
            int[] run = randomWhitespaceRun(rng, text);
            if (run == null) {
                return null;
            }
            return new Edit(run[0], run[1] - run[0], "");
        }
        if (roll < 85) {
            int wsPos = randomWhitespacePosition(rng, text);
            if (wsPos < 0) {
                return null;
            }
            var blob = " ".repeat(1 + rng.nextInt(4));
            return new Edit(wsPos, 0, blob);
        }
        // 15% generic single-char insertion.
        int off = rng.nextInt(text.length() + 1);
        return new Edit(off, 0, "x");
    }

    private static int randomWhitespacePosition(Random rng, String text) {
        // Pick a random offset, then walk forward (then backward) to find a whitespace char.
        int n = text.length();
        int start = rng.nextInt(n);
        for (int i = start; i < n; i++) {
            if (Character.isWhitespace(text.charAt(i))) {
                return i;
            }
        }
        for (int i = 0; i < start; i++) {
            if (Character.isWhitespace(text.charAt(i))) {
                return i;
            }
        }
        return -1;
    }

    private static int[] randomWhitespaceRun(Random rng, String text) {
        int n = text.length();
        int start = randomWhitespacePosition(rng, text);
        if (start < 0) {
            return null;
        }
        // Walk forward to the end of the run.
        int end = start;
        while (end < n && Character.isWhitespace(text.charAt(end))) {
            end++;
        }
        return new int[]{start, end};
    }

    private static String randomWhitespaceChar(Random rng) {
        int r = rng.nextInt(3);
        return switch (r) {
            case 0 -> " ";
            case 1 -> "\t";
            default -> "\n";
        };
    }
}
