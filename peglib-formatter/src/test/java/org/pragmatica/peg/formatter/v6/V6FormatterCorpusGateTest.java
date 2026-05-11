package org.pragmatica.peg.formatter.v6;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.pragmatica.peg.v6.PegParser;
import org.pragmatica.peg.v6.Parser;
import org.pragmatica.peg.v6.cst.CstArray;
import org.pragmatica.peg.v6.cst.ParseResult;
import org.pragmatica.peg.v6.token.TokenArray;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * End-to-end gate per 0.6.0 GA: drives {@link PegParser} → {@link CstArray} →
 * {@link V6Formatter} → {@link String} on every fixture in the Java25 reference
 * corpus and verifies (a) the formatter never throws, (b) the meaningful (non-trivia)
 * token stream survives a round-trip through the formatter and a re-parse.
 *
 * <p>The corpus lives under {@code peglib-core/src/test/resources/perf-corpus/format-examples}.
 * Tests are run from the {@code peglib-formatter} module, so paths are resolved
 * relative to {@code ../peglib-core}. If the directory cannot be located (atypical
 * layouts), the tests fail loudly rather than silently passing.
 */
final class V6FormatterCorpusGateTest {

    private static final Path GRAMMAR_PATH = Paths.get("../peglib-core/src/test/resources/java25.peg");
    private static final Path FIXTURE_DIR =
        Paths.get("../peglib-core/src/test/resources/perf-corpus/format-examples");

    private static Parser parser;
    private static List<Path> fixtures;

    @BeforeAll
    static void setUp() throws IOException {
        assertFalse(!Files.isReadable(GRAMMAR_PATH),
            "java25 grammar not readable at " + GRAMMAR_PATH.toAbsolutePath());
        var grammar = Files.readString(GRAMMAR_PATH, StandardCharsets.UTF_8);
        PegParser.clearCache();
        parser = PegParser.fromGrammar(grammar).unwrap();
        fixtures = listFixtures();
        assertFalse(fixtures.isEmpty(),
            "no corpus fixtures found at " + FIXTURE_DIR.toAbsolutePath());
    }

    @Test
    void allCorpusFixturesFormatWithoutCrash() throws IOException {
        var formatter = V6Formatter.formatter(
            V6FormatterConfig.builder().triviaPolicy(V6TriviaPolicy.PRESERVE).build());

        var crashes = new ArrayList<String>();
        var ruleFailures = new ArrayList<String>();
        for (var fixture : fixtures) {
            var fname = fixture.getFileName().toString();
            var input = Files.readString(fixture, StandardCharsets.UTF_8);
            ParseResult parsed;
            try {
                parsed = parser.parse(input);
            } catch (RuntimeException e) {
                crashes.add(fname + ": parse threw " + e.getClass().getSimpleName()
                    + " — " + e.getMessage());
                continue;
            }
            assertNotNull(parsed, () -> "parse returned null for " + fname);
            assertNotNull(parsed.cst(), () -> "cst was null for " + fname);

            try {
                var result = formatter.format(parsed.cst());
                if (result.isFailure()) {
                    ruleFailures.add(fname + ": " + result.toString());
                }
            } catch (Throwable t) {
                crashes.add(fname + ": format threw " + t.getClass().getSimpleName()
                    + " — " + t.getMessage());
            }
        }

        System.out.println();
        System.out.println("=== V6Formatter corpus gate (no-crash) ===");
        System.out.println("fixtures examined : " + fixtures.size());
        System.out.println("crashes           : " + crashes.size());
        System.out.println("rule failures     : " + ruleFailures.size());
        if (!crashes.isEmpty()) {
            System.out.println("--- crashes ---");
            crashes.forEach(c -> System.out.println("  " + c));
        }
        if (!ruleFailures.isEmpty()) {
            System.out.println("--- rule failures ---");
            ruleFailures.forEach(c -> System.out.println("  " + c));
        }
        System.out.println("==========================================");

        assertEquals(List.of(), crashes,
            "V6Formatter must never crash on parsed corpus fixtures");
        assertEquals(List.of(), ruleFailures,
            "V6Formatter must not return Result.failure on default-config corpus runs");
    }

    @Test
    void allCorpusFixturesPreserveNonTriviaTokensRoundTrip() throws IOException {
        var formatter = V6Formatter.formatter(
            V6FormatterConfig.builder().triviaPolicy(V6TriviaPolicy.PRESERVE).build());

        var passes = new ArrayList<String>();
        var diverges = new ArrayList<String>();
        var reparseCrashes = new ArrayList<String>();
        var ruleKinds = new TreeMap<String, Integer>();

        for (var fixture : fixtures) {
            var fname = fixture.getFileName().toString();
            var input = Files.readString(fixture, StandardCharsets.UTF_8);

            var parsed = parser.parse(input);
            collectKindCounts(parsed.cst(), ruleKinds);

            String formatted;
            var fmtResult = formatter.format(parsed.cst());
            if (fmtResult.isFailure()) {
                diverges.add(fname + ": format failure " + fmtResult);
                continue;
            }
            formatted = fmtResult.unwrap();

            ParseResult reparsed;
            try {
                reparsed = parser.parse(formatted);
            } catch (RuntimeException e) {
                reparseCrashes.add(fname + ": reparse threw " + e.getClass().getSimpleName()
                    + " — " + e.getMessage());
                continue;
            }

            var origTokens = nonTriviaTokenTexts(parsed.cst().tokens());
            var newTokens = nonTriviaTokenTexts(reparsed.cst().tokens());

            if (origTokens.equals(newTokens)) {
                passes.add(fname + " (" + origTokens.size() + " non-trivia tokens)");
            } else {
                diverges.add(fname + ": " + origTokens.size() + " → " + newTokens.size()
                    + " non-trivia tokens; first diff at " + firstDiffIndex(origTokens, newTokens));
            }
        }

        System.out.println();
        System.out.println("=== V6Formatter corpus gate (round-trip non-trivia) ===");
        System.out.println("fixtures examined : " + fixtures.size());
        System.out.println("passes            : " + passes.size());
        System.out.println("token diverges    : " + diverges.size());
        System.out.println("reparse crashes   : " + reparseCrashes.size());
        System.out.println("distinct kinds    : " + ruleKinds.size());
        if (!passes.isEmpty()) {
            System.out.println("--- passes ---");
            passes.forEach(p -> System.out.println("  " + p));
        }
        if (!diverges.isEmpty()) {
            System.out.println("--- diverges ---");
            diverges.forEach(d -> System.out.println("  " + d));
        }
        if (!reparseCrashes.isEmpty()) {
            System.out.println("--- reparse crashes ---");
            reparseCrashes.forEach(d -> System.out.println("  " + d));
        }
        System.out.println("--- top 10 most-frequent rule kinds (no formatter rules registered) ---");
        ruleKinds.entrySet().stream()
            .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
            .limit(10)
            .forEach(e -> System.out.println("  " + e.getKey() + " : " + e.getValue()));
        System.out.println("=======================================================");

        assertEquals(List.of(), reparseCrashes,
            "Reparse of formatter output must not throw");
        assertEquals(List.of(), diverges,
            "V6Formatter must preserve non-trivia tokens on round-trip");
    }

    private static List<String> nonTriviaTokenTexts(TokenArray tokens) {
        var out = new ArrayList<String>(tokens.count());
        for (int i = 0; i < tokens.count(); i++) {
            if (!tokens.isTrivia(i)) {
                out.add(tokens.textAt(i).toString());
            }
        }
        return out;
    }

    private static String firstDiffIndex(List<String> a, List<String> b) {
        int len = Math.min(a.size(), b.size());
        for (int i = 0; i < len; i++) {
            if (!a.get(i).equals(b.get(i))) {
                return "index " + i + " expected='" + escape(a.get(i))
                    + "' actual='" + escape(b.get(i)) + "'";
            }
        }
        if (a.size() != b.size()) {
            return "length mismatch only (common prefix matches): " + a.size() + " vs " + b.size();
        }
        return "no diff";
    }

    private static String escape(String s) {
        var sb = new StringBuilder(s.length() + 4);
        for (int i = 0; i < s.length(); i++) {
            var c = s.charAt(i);
            switch (c) {
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c < 32 || c == 127 ? String.format("\\u%04x", (int) c) : c);
            }
        }
        return sb.toString();
    }

    private static void collectKindCounts(CstArray cst, Map<String, Integer> counts) {
        for (int i = 0; i < cst.nodeCount(); i++) {
            var name = cst.kindNameAt(i);
            counts.merge(name, 1, Integer::sum);
        }
    }

    private static List<Path> listFixtures() throws IOException {
        try (Stream<Path> stream = Files.list(FIXTURE_DIR)) {
            return stream
                .filter(p -> p.getFileName().toString().endsWith(".java"))
                .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                .toList();
        }
    }
}
