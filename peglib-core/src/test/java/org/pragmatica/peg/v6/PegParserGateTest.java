package org.pragmatica.peg.v6;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.pragmatica.peg.v6.cst.CstArray;
import org.pragmatica.peg.v6.cst.ParseResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase C gate per spec §7: drive {@link PegParser#fromGrammar(String)} end-to-end on the
 * Java25 reference corpus and verify the reconstructed CST byte-equals the original input.
 *
 * <p>Mirrors the corpus loop pattern of {@code Java25ParserGateTest} but goes through the
 * top-level {@link PegParser} cache instead of driving the lexer/parser components directly.
 */
class PegParserGateTest {

    private static final Path GRAMMAR_PATH = Paths.get("src/test/resources/java25.peg");
    private static final Path FIXTURE_DIR = Paths.get("src/test/resources/perf-corpus/format-examples");

    private static String grammarText;
    private static Parser parser;
    private static long coldFromGrammarMs;

    @BeforeAll
    static void setUpParserOnce() throws IOException {
        grammarText = Files.readString(GRAMMAR_PATH, StandardCharsets.UTF_8);
        // Ensure a clean cache slot for this grammar so the first call exercises the
        // full classify -> DFA -> generate -> compile pipeline.
        PegParser.clearCache();
        long t0 = System.nanoTime();
        parser = PegParser.fromGrammar(grammarText).unwrap();
        coldFromGrammarMs = (System.nanoTime() - t0) / 1_000_000L;
        System.out.println("[Phase C gate] cold fromGrammar: " + coldFromGrammarMs + " ms");
    }

    @Test
    void allFixturesParseAndRoundTrip() throws IOException {
        var fixtures = listFixtures();
        assertFalse(fixtures.isEmpty(), "no corpus fixtures found under " + FIXTURE_DIR);

        var clean = new ArrayList<String>();
        var withDiagnostics = new ArrayList<String>();
        var hardFailures = new ArrayList<String>();
        long totalLexParseMs = 0;
        long totalBytes = 0;
        long totalTokens = 0;
        long totalNodes = 0;
        long totalDiagnostics = 0;

        for (var fixture : fixtures) {
            var input = Files.readString(fixture, StandardCharsets.UTF_8);
            var fname = fixture.getFileName().toString();
            totalBytes += input.length();

            ParseResult result;
            long t0 = System.nanoTime();
            try {
                result = parser.parse(input);
            } catch (RuntimeException e) {
                long elapsed = (System.nanoTime() - t0) / 1_000_000L;
                totalLexParseMs += elapsed;
                hardFailures.add(fname + ": THREW " + e.getClass().getSimpleName()
                    + " — " + (e.getMessage() == null ? e.toString() : e.getMessage()));
                continue;
            }
            long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;
            totalLexParseMs += elapsedMs;

            assertNotNull(result, () -> "parse() returned null for " + fname);
            CstArray cst = result.cst();
            assertNotNull(cst, () -> "ParseResult.cst() was null for " + fname);
            totalTokens += cst.tokens().count();
            totalNodes += cst.nodeCount();
            totalDiagnostics += result.diagnostics().size();

            var reconstructed = cst.reconstruct();
            if (!reconstructed.equals(input)) {
                hardFailures.add(fname + ": RECONSTRUCTION MISMATCH at "
                    + firstDiff(input, reconstructed));
                continue;
            }

            String summary = fname + " (" + input.length() + " B, "
                + cst.tokens().count() + " tok, "
                + cst.nodeCount() + " nodes, " + elapsedMs + " ms)";
            if (result.isSuccess()) {
                clean.add(summary);
            } else {
                withDiagnostics.add(summary + " — " + result.diagnostics().size()
                    + " diag; first=" + result.diagnostics().get(0));
            }
        }

        System.out.println();
        System.out.println("=== Phase C gate truth report ===");
        System.out.println("fixtures examined  : " + fixtures.size());
        System.out.println("clean (no diag)    : " + clean.size());
        System.out.println("recovered          : " + withDiagnostics.size());
        System.out.println("hard failures      : " + hardFailures.size());
        System.out.println("total input bytes  : " + totalBytes);
        System.out.println("total tokens       : " + totalTokens);
        System.out.println("total CST nodes    : " + totalNodes);
        System.out.println("total diagnostics  : " + totalDiagnostics);
        System.out.println("total parse ms     : " + totalLexParseMs);
        System.out.println("cold fromGrammar   : " + coldFromGrammarMs + " ms");
        System.out.println();
        if (!clean.isEmpty()) {
            System.out.println("--- clean ---");
            clean.forEach(p -> System.out.println("  CLEAN " + p));
        }
        if (!withDiagnostics.isEmpty()) {
            System.out.println("--- recovered (diagnostics, but CST built + round-trip OK) ---");
            withDiagnostics.forEach(f -> System.out.println("  RECOV " + f));
        }
        if (!hardFailures.isEmpty()) {
            System.out.println("--- hard failures ---");
            hardFailures.forEach(f -> System.out.println("  HARD  " + f));
        }
        System.out.println("=================================");

        assertEquals(List.of(), hardFailures,
            "Phase C gate: " + hardFailures.size() + "/" + fixtures.size()
                + " fixtures hit hard failure (exception or round-trip mismatch)");
    }

    @Test
    void cacheHitOnSecondCall() {
        long t0 = System.nanoTime();
        Parser p2 = PegParser.fromGrammar(grammarText).unwrap();
        long warmMs = (System.nanoTime() - t0) / 1_000_000L;
        System.out.println("[Phase C gate] warm fromGrammar: " + warmMs + " ms");
        assertSame(parser, p2,
            "PegParser.fromGrammar must return the cached Parser for identical grammar text");
        assertTrue(warmMs < coldFromGrammarMs,
            "warm lookup (" + warmMs + " ms) must be faster than cold compile ("
                + coldFromGrammarMs + " ms)");
    }

    private static List<Path> listFixtures() throws IOException {
        try (Stream<Path> stream = Files.list(FIXTURE_DIR)) {
            return stream
                .filter(p -> p.getFileName().toString().endsWith(".java"))
                .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                .toList();
        }
    }

    private static String firstDiff(String expected, String actual) {
        int len = Math.min(expected.length(), actual.length());
        for (int i = 0; i < len; i++) {
            if (expected.charAt(i) != actual.charAt(i)) {
                int from = Math.max(0, i - 20);
                int to = Math.min(expected.length(), i + 20);
                return "offset " + i + " expected='" + escape(expected.charAt(i))
                    + "' actual='" + escape(actual.charAt(i))
                    + "' context=\"" + escape(expected.substring(from, to)) + "\"";
            }
        }
        return "length mismatch: expected=" + expected.length() + " actual=" + actual.length();
    }

    private static String escape(char c) {
        return switch (c) {
            case '\n' -> "\\n";
            case '\r' -> "\\r";
            case '\t' -> "\\t";
            case '"' -> "\\\"";
            case '\\' -> "\\\\";
            default -> c < 32 || c == 127 ? String.format("\\u%04x", (int) c) : String.valueOf(c);
        };
    }

    private static String escape(String s) {
        var sb = new StringBuilder(s.length() + 4);
        for (int i = 0; i < s.length(); i++) {
            sb.append(escape(s.charAt(i)));
        }
        return sb.toString();
    }
}
