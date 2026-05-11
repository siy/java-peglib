package org.pragmatica.peg.v6.generator;

import org.pragmatica.peg.grammar.Grammar;
import org.pragmatica.peg.grammar.GrammarParser;
import org.pragmatica.peg.v6.cst.CstArray;
import org.pragmatica.peg.v6.cst.ParseResult;
import org.pragmatica.peg.v6.lexer.DfaBuilder;
import org.pragmatica.peg.v6.lexer.LexerEngine;
import org.pragmatica.peg.v6.lexer.RuleClassifier;
import org.pragmatica.peg.v6.token.TokenArray;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Phase B gate per spec §7: parse the Java25 reference corpus end-to-end and verify
 * the reconstructed CST byte-equals the original input.
 *
 * <p>The test loads {@code java25.peg}, classifies rules, builds the DFA + lexer,
 * generates parser source via {@link ParserGenerator}, compiles via
 * {@link ParserCompiler}, then for every {@code *.java} fixture under
 * {@code perf-corpus/format-examples} it lexes the fixture, parses it, and
 * reconstructs the input from the resulting {@link CstArray} via
 * {@link CstArray#reconstruct()}.
 */
class Java25ParserGateTest {
    private static final Path GRAMMAR_PATH = Paths.get("src/test/resources/java25.peg");
    private static final Path FIXTURE_DIR = Paths.get("src/test/resources/perf-corpus/format-examples");

    private static Grammar grammar;
    private static RuleClassifier.Classification classification;
    private static DfaBuilder.Built built;
    private static LexerEngine lexer;
    private static ParserCompiler.CompiledParser compiledParser;
    private static int generatedSourceBytes;
    private static long generationMillis;
    private static long compilationMillis;

    @BeforeAll
    static void setupParserOnce() throws IOException {
        var grammarText = Files.readString(GRAMMAR_PATH, StandardCharsets.UTF_8);
        grammar = GrammarParser.parse(grammarText)
                               .unwrap();
        classification = RuleClassifier.classify(grammar)
                                       .unwrap();
        built = DfaBuilder.build(grammar, classification)
                          .unwrap();
        int wsKind = grammar.whitespace()
                            .isPresent()
                     ? DfaBuilder.KIND_WHITESPACE
                     : - 1;
        lexer = new LexerEngine(built.dfa(),
                                built.kinds()
                                     .kindNameTable(),
                                wsKind,
                                built.kinds()
                                     .keywordResolutions());
        long t0 = System.currentTimeMillis();
        var generated = ParserGenerator.generate(grammar,
                                                 classification,
                                                 built.kinds(),
                                                 "test.gen.parser.java25.gate",
                                                 "Java25GateParser")
                                       .unwrap();
        long t1 = System.currentTimeMillis();
        generatedSourceBytes = generated.source()
                                        .length();
        generationMillis = t1 - t0;
        long c0 = System.currentTimeMillis();
        compiledParser = ParserCompiler.compile(generated)
                                       .unwrap();
        long c1 = System.currentTimeMillis();
        compilationMillis = c1 - c0;
        System.out.println("Phase B gate: generated parser source = " + generatedSourceBytes + " bytes, generation = " + generationMillis
                           + " ms, compilation = " + compilationMillis + " ms");
        System.out.println("Phase B gate: parser rule count = " + countParserRules(classification));
    }

    private static int countParserRules(RuleClassifier.Classification c) {
        int n = 0;
        for (var k : c.kinds()
                      .values()) {
            if (k == org.pragmatica.peg.v6.lexer.RuleKind.PARSER || k == org.pragmatica.peg.v6.lexer.RuleKind.MIXED) {
                n++ ;
            }
        }
        return n;
    }

    private static List<Path> listFixtures() throws IOException {
        try (Stream<Path> stream = Files.list(FIXTURE_DIR)) {
            return stream.filter(p -> p.getFileName()
                                       .toString()
                                       .endsWith(".java"))
                                          .sorted(Comparator.comparing(p -> p.getFileName()
                                                                             .toString()))
                                          .toList();
        }
    }

    @Test
    void allFixturesParseAndRoundTrip() throws IOException {
        var fixtures = listFixtures();
        assertFalse(fixtures.isEmpty(), "no corpus fixtures found under " + FIXTURE_DIR);
        var clean = new ArrayList<String>();
        // parse + diag-empty + round-trip
        var withDiagnostics = new ArrayList<String>();
        // parsed but recovered
        var hardFailures = new ArrayList<String>();
        // exception or reconstruction mismatch
        long totalBytes = 0;
        long totalTokens = 0;
        long totalNodes = 0;
        long totalParseMs = 0;
        long totalDiagnostics = 0;
        for (var fixture : fixtures) {
            var input = Files.readString(fixture, StandardCharsets.UTF_8);
            totalBytes += input.length();
            String fname = fixture.getFileName()
                                  .toString();
            TokenArray tokens;
            try{
                tokens = lexer.lex(input);
            } catch (RuntimeException e) {
                hardFailures.add(fname + ": LEX FAILURE — " + describeException(input, e));
                continue;
            }
            totalTokens += tokens.count();
            ParseResult result;
            long p0 = System.currentTimeMillis();
            try{
                result = compiledParser.parse(tokens);
            } catch (RuntimeException e) {
                hardFailures.add(fname + ": PARSE FAILURE — " + describeParseException(input, tokens, e));
                continue;
            }
            long p1 = System.currentTimeMillis();
            totalParseMs += (p1 - p0);
            CstArray cst = result.cst();
            totalNodes += cst.nodeCount();
            totalDiagnostics += result.diagnostics()
                                      .size();
            var reconstructed = cst.reconstruct();
            if (!reconstructed.equals(input)) {
                hardFailures.add(fname + ": RECONSTRUCTION MISMATCH — " + describeMismatch(input, reconstructed));
                continue;
            }
            String summary = fname + " (" + input.length() + " B, " + tokens.count() + " tok, " + cst.nodeCount()
                             + " nodes, " + (p1 - p0) + " ms)";
            if (result.diagnostics()
                      .isEmpty()) {
                clean.add(summary);
            }else {
                withDiagnostics.add(summary + " — " + result.diagnostics()
                                                            .size() + " diag; first: " + result.diagnostics()
                                                                                               .get(0));
            }
        }
        System.out.println();
        System.out.println("=== Phase B.3.1 truth report ===");
        System.out.println("fixtures examined  : " + fixtures.size());
        System.out.println("clean (no diag)    : " + clean.size());
        System.out.println("recovered          : " + withDiagnostics.size());
        System.out.println("hard failures      : " + hardFailures.size());
        System.out.println("total input bytes  : " + totalBytes);
        System.out.println("total tokens       : " + totalTokens);
        System.out.println("total CST nodes    : " + totalNodes);
        System.out.println("total diagnostics  : " + totalDiagnostics);
        System.out.println("total parse ms     : " + totalParseMs);
        if (!clean.isEmpty()) {
            double avgNodes = clean.stream()
                                   .mapToInt(s -> Integer.parseInt(s.replaceAll(".*B, \\d+ tok, (\\d+) nodes.*",
                                                                                "$1")))
                                   .average()
                                   .orElse(0);
            System.out.printf("avg nodes (clean)  : %.1f%n", avgNodes);
        }
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
        System.out.println("================================");
        // Phase B.3.1 surfaces parser/grammar gaps honestly. We assert only on the
        // hardest contract: no exceptions and round-trip must hold (the CST always
        // reconstructs the input losslessly, even when diagnostics are present).
        assertEquals(List.of(),
                     hardFailures,
                     "Phase B gate: " + hardFailures.size()
                     + " fixtures hit hard failure (exception or round-trip mismatch)");
    }

    @Test
    void smallestFixtureSmoke() throws IOException {
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
        var tokens = lexer.lex(input);
        ParseResult result;
        try{
            result = compiledParser.parse(tokens);
        } catch (RuntimeException e) {
            // For the smoke test we want a clear diagnostic, not a buried stack trace.
            String diag = describeParseException(input, tokens, e);
            throw new AssertionError("smallest fixture " + smallest.getFileName() + " failed to parse: " + diag, e);
        }
        if (!result.diagnostics()
                   .isEmpty()) {
            throw new AssertionError("smallest fixture " + smallest.getFileName() + " produced " + result.diagnostics()
                                                                                                         .size()
                                     + " parse diagnostics; first: " + result.diagnostics()
                                                                             .get(0));
        }
        CstArray cst = result.cst();
        var startName = grammar.effectiveStartRule()
                               .unwrap()
                               .name();
        assertThat(cst.nodeCount())
        .as("smoke fixture %s produced no CST nodes",
            smallest.getFileName())
        .isGreaterThan(0);
        assertThat(cst.rootIndex())
        .isGreaterThanOrEqualTo(0);
        // Phase B.3.1: rootIndex now points at the synthetic "_ROOT" wrapper.
        // The start rule (or recovery Error nodes) hangs underneath.
        assertThat(cst.kindNameAt(cst.rootIndex()))
        .as("smoke fixture %s root must be synthetic _ROOT wrapper",
            smallest.getFileName())
        .isEqualTo("_ROOT");
        int firstChild = cst.firstChildAt(cst.rootIndex());
        String firstChildKind = firstChild == CstArray.NO_NODE
                                ? "<no-children>"
                                : cst.kindNameAt(firstChild);
        System.out.println("Phase B gate smoke: fixture = " + smallest.getFileName() + ", bytes = " + input.length()
                           + ", tokens = " + tokens.count() + ", nodes = " + cst.nodeCount() + ", startRule = " + startName
                           + ", firstChild = " + firstChildKind);
    }

    private static String describeException(String input, RuntimeException e) {
        var msg = e.getMessage() == null
                  ? e.toString()
                  : e.getMessage();
        int offsetIdx = msg.indexOf("offset ");
        if (offsetIdx < 0) {
            return msg;
        }
        try{
            int start = offsetIdx + "offset ".length();
            int end = start;
            while (end < msg.length() && Character.isDigit(msg.charAt(end))) {
                end++ ;
            }
            int offset = Integer.parseInt(msg.substring(start, end));
            int from = Math.max(0, offset - 40);
            int to = Math.min(input.length(), offset + 40);
            return msg + " | context: \"" + escape(input.substring(from, to)) + "\"";
        } catch (NumberFormatException nfe) {
            return msg;
        }
    }

    /**
     * Extract pos / expected / found from the generated parser's ParseException message
     * format: "unexpected token at &lt;pos&gt;: expected &lt;name&gt;, found kind=&lt;k&gt;".
     * Walk back to the failing token's source offset and print 80 chars of context.
     */
    private static String describeParseException(String input, TokenArray tokens, RuntimeException e) {
        var msg = e.getMessage() == null
                  ? e.toString()
                  : e.getMessage();
        int offset = - 1;
        try{
            int at = msg.indexOf(" at ");
            if (at >= 0) {
                int start = at + " at ".length();
                int end = start;
                while (end < msg.length() && Character.isDigit(msg.charAt(end))) {
                    end++ ;
                }
                if (end > start) {
                    offset = Integer.parseInt(msg.substring(start, end));
                }
            }
        } catch (NumberFormatException ignored) {}
        if (offset < 0 || offset > input.length()) {
            return msg;
        }
        int from = Math.max(0, offset - 40);
        int to = Math.min(input.length(), offset + 40);
        return msg + " | source@" + offset + ": \"" + escape(input.substring(from, to)) + "\"";
    }

    private static String describeMismatch(String expected, String actual) {
        int len = Math.min(expected.length(), actual.length());
        int diff = - 1;
        for (int i = 0; i < len; i++ ) {
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
        return "first diff at offset " + diff + " ('" + escape(expected.charAt(diff)) + "' vs '" + (diff < actual.length()
                                                                                                    ? escape(actual.charAt(diff))
                                                                                                    : "EOF")
               + "'); context: \"" + escape(expected.substring(from, to)) + "\"";
    }

    private static String escape(char c) {
        return switch (c) {
            case'\n' -> "\\n";
            case'\r' -> "\\r";
            case'\t' -> "\\t";
            case'"' -> "\\\"";
            case'\\' -> "\\\\";
            default -> c < 32 || c == 127
                       ? String.format("\\u%04x", (int) c)
                       : String.valueOf(c);
        };
    }

    private static String escape(String s) {
        var sb = new StringBuilder(s.length() + 4);
        for (int i = 0; i < s.length(); i++ ) {
            sb.append(escape(s.charAt(i)));
        }
        return sb.toString();
    }
}
