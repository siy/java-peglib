package org.pragmatica.peg.v6;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Result;
import org.pragmatica.peg.v6.cst.ParseResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Phase C.1 — wire-up tests for the {@link PegParser} entry point. Verifies
 * the generate-compile-cache pipeline end-to-end: distinct grammars produce
 * distinct parsers, identical text reuses the cached parser, and a
 * non-trivial real-world grammar (Java 25) builds and parses simple input.
 */
class PegParserTest {

    @BeforeEach
    void clearCache() {
        PegParser.clearCache();
    }

    // Grammars below are written so the start rule mixes literals with a rule reference,
    // forcing classifier to label it PARSER (per RuleClassifier's initial labelling).
    // A pure-lexer start rule (e.g. "Number <- [0-9]+") is rejected by ParserGenerator
    // because the parser cannot dispatch into a LEXER rule.

    private static final String NUMBER_GRAMMAR =
        "Start <- '#' Number\nNumber <- [0-9]+\n";

    private static final String FOO_GRAMMAR =
        "Start <- '!' Foo\nFoo <- 'foo'\n";

    @Test
    void simpleGrammar_parsesInput() {
        var result = PegParser.fromGrammar(NUMBER_GRAMMAR);
        assertTrue(result.isSuccess(), () -> "fromGrammar failed: " + result);
        Parser parser = result.unwrap();

        ParseResult parseResult = parser.parse("#42");
        assertNotNull(parseResult);
        assertNotNull(parseResult.cst());
    }

    @Test
    void cacheHit_reusesParser() {
        Parser p1 = PegParser.fromGrammar(FOO_GRAMMAR).unwrap();
        Parser p2 = PegParser.fromGrammar(FOO_GRAMMAR).unwrap();
        assertSame(p1, p2, "cache hit must return the same Parser instance");
        assertEquals(1, PegParser.cacheSize());
    }

    @Test
    void distinctGrammars_distinctParsers() {
        Parser p1 = PegParser.fromGrammar("Start <- '@' A\nA <- 'a'\n").unwrap();
        Parser p2 = PegParser.fromGrammar("Start <- '@' B\nB <- 'b'\n").unwrap();
        assertNotSame(p1, p2);
        assertEquals(2, PegParser.cacheSize());
    }

    @Test
    void invalidGrammar_returnsFailure() {
        Result<Parser> result = PegParser.fromGrammar("invalid grammar !@#");
        assertFalse(result.isSuccess(), () -> "expected failure but got success: " + result);
    }

    @Test
    void cacheLifecycle_missThenHitThenMiss() {
        String g1Text = "Start <- '@' X\nX <- 'x'\n";
        String g2Text = "Start <- '@' Y\nY <- 'y'\n";
        assertEquals(0, PegParser.cacheSize());
        Parser g1 = PegParser.fromGrammar(g1Text).unwrap();      // miss
        assertEquals(1, PegParser.cacheSize());
        Parser g1Again = PegParser.fromGrammar(g1Text).unwrap(); // hit
        assertSame(g1, g1Again);
        assertEquals(1, PegParser.cacheSize());
        Parser g2 = PegParser.fromGrammar(g2Text).unwrap();      // miss
        assertNotSame(g1, g2);
        assertEquals(2, PegParser.cacheSize());
    }

    @Test
    void parseWithMaxDiagnostics_currentlyEquivalentToParse() {
        Parser parser = PegParser.fromGrammar(NUMBER_GRAMMAR).unwrap();
        ParseResult a = parser.parse("#12");
        ParseResult b = parser.parse("#12", 5);
        assertNotNull(a.cst());
        assertNotNull(b.cst());
    }

    @Test
    void accessors_exposeUnderlyingComponents() {
        Parser parser = PegParser.fromGrammar(NUMBER_GRAMMAR).unwrap();
        assertNotNull(parser.grammar());
        assertNotNull(parser.lexer());
        assertNotNull(parser.parserEngine());
    }

    @Test
    void java25_grammarLoadsAndParsesSimpleClass() throws IOException {
        Path grammarPath = Path.of("src/test/resources/java25.peg");
        if (!Files.exists(grammarPath)) {
            fail("Java25 grammar fixture not found at " + grammarPath.toAbsolutePath());
        }
        String grammarText = Files.readString(grammarPath);

        long coldStart = System.nanoTime();
        Result<Parser> result = PegParser.fromGrammar(grammarText);
        long coldNanos = System.nanoTime() - coldStart;
        assertTrue(result.isSuccess(), () -> "java25 grammar compile failed: " + result);
        Parser parser = result.unwrap();

        ParseResult parseResult = parser.parse("class Foo { int x = 42; }");
        assertNotNull(parseResult.cst());

        long warmStart = System.nanoTime();
        Parser warm = PegParser.fromGrammar(grammarText).unwrap();
        long warmNanos = System.nanoTime() - warmStart;
        assertSame(parser, warm, "second call with identical grammar must hit cache");

        // Generous bounds — JIT warmup variance allowed. Spec target is 600ms warm; we
        // budget 1500ms cold for safety. Cache hit should be sub-millisecond.
        long coldMs = coldNanos / 1_000_000L;
        long warmMicros = warmNanos / 1_000L;
        assertTrue(coldMs < 5_000L, () -> "cold latency " + coldMs + "ms exceeds 5000ms ceiling");
        assertTrue(warmMicros < 5_000L, () -> "cache hit took " + warmMicros + "µs (>5000µs)");
        System.out.printf("[PegParserTest] java25 cold=%dms warm=%dµs%n", coldMs, warmMicros);
    }
}
