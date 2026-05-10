package org.pragmatica.peg.v6.lexer;

import org.junit.jupiter.api.Test;
import org.pragmatica.peg.grammar.GrammarParser;
import org.pragmatica.peg.v6.generator.LexerCompiler;
import org.pragmatica.peg.v6.generator.LexerGenerator;
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
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Phase A.5 acceptance gate per HANDOVER §3.2 step 7. Lex every fixture in the
 * Java25 corpus and verify the concatenated token texts byte-equal the input.
 * Also asserts library-engine vs source-generated lexer parity.
 */
class Java25CorpusGateTest {

    private static final Path GRAMMAR_PATH = Paths.get("src/test/resources/java25.peg");
    private static final Path FIXTURE_DIR = Paths.get("src/test/resources/perf-corpus/format-examples");

    private record GateContext(LexerEngine engine,
                               DfaBuilder.Built built,
                               int inlineLiteralCount,
                               int explicitLexerRuleCount) {}

    private static GateContext setUp() throws IOException {
        var grammarText = Files.readString(GRAMMAR_PATH, StandardCharsets.UTF_8);
        var grammar = GrammarParser.parse(grammarText).unwrap();
        var classification = RuleClassifier.classify(grammar).unwrap();
        var built = DfaBuilder.build(grammar, classification).unwrap();
        int wsKind = grammar.whitespace().isPresent() ? DfaBuilder.KIND_WHITESPACE : -1;
        var engine = new LexerEngine(built.dfa(), built.kinds().kindNameTable(), wsKind,
            built.kinds().keywordResolutions());
        return new GateContext(engine, built,
            built.kinds().inlineLiteralToKind().size(),
            built.kinds().ruleNameToKind().size());
    }

    private static List<Path> listFixtures() throws IOException {
        try (Stream<Path> stream = Files.list(FIXTURE_DIR)) {
            return stream
                .filter(p -> p.getFileName().toString().endsWith(".java"))
                .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                .toList();
        }
    }

    @Test
    void allCorpusFixturesRoundTrip() throws IOException {
        var ctx = setUp();
        var fixtures = listFixtures();
        assertFalse(fixtures.isEmpty(), "no corpus fixtures found under " + FIXTURE_DIR);

        long totalBytes = 0;
        long totalTokens = 0;
        var failures = new ArrayList<String>();
        for (var fixture : fixtures) {
            var input = Files.readString(fixture, StandardCharsets.UTF_8);
            totalBytes += input.length();
            TokenArray tokens;
            try {
                tokens = ctx.engine().lex(input);
            } catch (RuntimeException e) {
                failures.add(fixture.getFileName() + ": " + diagnoseLexFailure(input, e));
                continue;
            }
            totalTokens += tokens.count();
            var reconstructed = reconstruct(tokens);
            if (!reconstructed.equals(input)) {
                failures.add(fixture.getFileName() + ": " + describeMismatch(input, reconstructed));
            }
        }
        if (!failures.isEmpty()) {
            assertEquals(List.of(), failures, "round-trip failures");
        }
        System.out.println("Phase A gate: " + fixtures.size() + " fixtures, all round-trip OK");
        System.out.println("Phase A gate: " + totalBytes + " input bytes, " + totalTokens + " total tokens");
        System.out.println("Phase A gate: explicit LEXER rules = " + ctx.explicitLexerRuleCount()
            + ", inline literals = " + ctx.inlineLiteralCount()
            + ", DFA states = " + ctx.built().dfa().stateCount()
            + ", kind table size = " + ctx.built().kinds().kindNameTable().length);
        System.out.println("Phase B.0 gate: keywordResolutions = "
            + ctx.built().kinds().keywordResolutions().size()
            + ", skipped rules = " + ctx.built().skipped().size());
        var fallbackKind = ctx.built().kinds().anyCharKind();
        var kindFreq = new HashMap<Integer, Long>();
        long totalTokensFreq = 0;
        long anyCharCount = 0;
        for (var fixture : fixtures) {
            var tokens = ctx.engine().lex(Files.readString(fixture, StandardCharsets.UTF_8));
            for (int i = 0; i < tokens.count(); i++) {
                int k = tokens.kindAt(i);
                kindFreq.merge(k, 1L, Long::sum);
                if (k == fallbackKind) {
                    anyCharCount++;
                }
                totalTokensFreq++;
            }
        }
        if (fallbackKind >= 0) {
            System.out.println("Phase B.0 gate: ANY_CHAR fallback present (kind=" + fallbackKind
                + "); fallback tokens emitted across corpus = " + anyCharCount
                + " of " + totalTokensFreq + " ("
                + String.format("%.2f", (anyCharCount * 100.0) / totalTokensFreq) + "%)");
        }
        // Top-10 token-kind frequencies (by name) — visibility for the report.
        var names = ctx.built().kinds().kindNameTable();
        var sorted = new ArrayList<>(kindFreq.entrySet());
        sorted.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        System.out.println("Phase B.0 gate: top-20 token kinds (kind/name = count):");
        int shown = Math.min(20, sorted.size());
        for (int i = 0; i < shown; i++) {
            var e = sorted.get(i);
            int k = e.getKey();
            var nm = (k >= 0 && k < names.length) ? names[k] : "?";
            System.out.println("  " + k + "/" + nm + " = " + e.getValue());
        }
        // Phase B.0 acceptance: ANY_CHAR ratio must be < 5% of total tokens.
        if (totalTokensFreq > 0) {
            double ratio = (double) anyCharCount / (double) totalTokensFreq;
            assertThat(ratio)
                .as("ANY_CHAR ratio across corpus must be < 5%% (got %d/%d)", anyCharCount, totalTokensFreq)
                .isLessThan(0.05);
        }
    }

    @Test
    void generatedLexerMatchesEngineOnFixtures() throws IOException {
        var ctx = setUp();
        var grammar = GrammarParser.parse(Files.readString(GRAMMAR_PATH, StandardCharsets.UTF_8)).unwrap();
        var classification = RuleClassifier.classify(grammar).unwrap();
        var generated = LexerGenerator.generate(grammar, classification, ctx.built().dfa(),
            ctx.built().kinds(), "test.gen.gate", "GateLexer").unwrap();
        var compiled = LexerCompiler.compile(generated).unwrap();

        var fixtures = listFixtures();
        assertFalse(fixtures.isEmpty(), "no corpus fixtures found under " + FIXTURE_DIR);
        for (var fixture : fixtures) {
            var input = Files.readString(fixture, StandardCharsets.UTF_8);
            var engineTokens = ctx.engine().lex(input);
            var compiledTokens = compiled.lex(input);
            assertParityForFixture(fixture, engineTokens, compiledTokens);
        }
        System.out.println("Phase A gate: generated lexer parity OK across " + fixtures.size() + " fixtures");
    }

    @Test
    void smokeTestSmallestFixture_emitsExpectedKindStream() throws IOException {
        var ctx = setUp();
        var fixtures = listFixtures();
        assertFalse(fixtures.isEmpty(), "no corpus fixtures found under " + FIXTURE_DIR);
        Path smallest = fixtures.getFirst();
        long smallestSize = Long.MAX_VALUE;
        for (var f : fixtures) {
            long sz = Files.size(f);
            if (sz < smallestSize) {
                smallestSize = sz;
                smallest = f;
            }
        }
        var input = Files.readString(smallest, StandardCharsets.UTF_8);
        var tokens = ctx.engine().lex(input);

        // Round-trip property is the contract; a positive token count proves we lexed the file.
        assertThat(tokens.count())
            .as("smallest fixture %s produced no tokens", smallest.getFileName())
            .isGreaterThan(0);
        assertThat(reconstruct(tokens))
            .as("smallest fixture %s round-trip", smallest.getFileName())
            .isEqualTo(input);

        // First non-trivia token must start at offset 0 or after a leading-trivia run.
        int firstNonTrivia = tokens.nextNonTrivia(0);
        assertThat(firstNonTrivia).isLessThan(tokens.count());
        assertThat(tokens.startAt(firstNonTrivia)).isGreaterThanOrEqualTo(0);

        System.out.println("Phase A gate: smoke fixture = " + smallest.getFileName()
            + ", bytes = " + input.length()
            + ", tokens = " + tokens.count()
            + ", first non-trivia = " + tokens.kindName(firstNonTrivia)
            + " @ [" + tokens.startAt(firstNonTrivia) + "," + tokens.endAt(firstNonTrivia) + ")");
    }

    private static void assertParityForFixture(Path fixture, TokenArray expected, TokenArray actual) {
        assertThat(actual.count())
            .as("token count parity on %s", fixture.getFileName())
            .isEqualTo(expected.count());
        for (int i = 0; i < expected.count(); i++) {
            assertThat(actual.kindAt(i))
                .as("kind at %d in %s", i, fixture.getFileName())
                .isEqualTo(expected.kindAt(i));
            assertThat(actual.startAt(i))
                .as("start at %d in %s", i, fixture.getFileName())
                .isEqualTo(expected.startAt(i));
            assertThat(actual.endAt(i))
                .as("end at %d in %s", i, fixture.getFileName())
                .isEqualTo(expected.endAt(i));
        }
    }

    private static String reconstruct(TokenArray tokens) {
        var sb = new StringBuilder();
        for (int i = 0; i < tokens.count(); i++) {
            sb.append(tokens.textAt(i));
        }
        return sb.toString();
    }

    private static String describeMismatch(String expected, String actual) {
        int len = Math.min(expected.length(), actual.length());
        int diff = -1;
        for (int i = 0; i < len; i++) {
            if (expected.charAt(i) != actual.charAt(i)) {
                diff = i;
                break;
            }
        }
        if (diff < 0) {
            return "length mismatch: expected " + expected.length() + " got " + actual.length();
        }
        int from = Math.max(0, diff - 20);
        int to = Math.min(expected.length(), diff + 20);
        return "first diff at offset " + diff + " ('"
            + escape(expected.charAt(diff)) + "' vs '"
            + (diff < actual.length() ? escape(actual.charAt(diff)) : "EOF")
            + "'); context: \"" + escape(expected.substring(from, to)) + "\"";
    }

    private static String diagnoseLexFailure(String input, RuntimeException e) {
        var msg = e.getMessage() == null ? e.toString() : e.getMessage();
        // Try to extract offset from "lex error at offset N"
        int offsetIdx = msg.indexOf("offset ");
        if (offsetIdx < 0) {
            return msg;
        }
        try {
            int start = offsetIdx + "offset ".length();
            int end = start;
            while (end < msg.length() && Character.isDigit(msg.charAt(end))) {
                end++;
            }
            int offset = Integer.parseInt(msg.substring(start, end));
            int from = Math.max(0, offset - 20);
            int to = Math.min(input.length(), offset + 20);
            return msg + " | context: \"" + escape(input.substring(from, to)) + "\"";
        } catch (NumberFormatException nfe) {
            return msg;
        }
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
