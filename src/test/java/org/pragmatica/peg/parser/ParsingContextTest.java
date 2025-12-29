package org.pragmatica.peg.parser;

import org.junit.jupiter.api.Test;
import org.pragmatica.peg.error.RecoveryStrategy;
import org.pragmatica.peg.grammar.Grammar;
import org.pragmatica.peg.grammar.GrammarParser;
import org.pragmatica.peg.tree.CstNode;
import org.pragmatica.peg.tree.SourceLocation;
import org.pragmatica.peg.tree.SourceSpan;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ParsingContext, focusing on packrat cache behavior
 * and state management.
 */
class ParsingContextTest {

    private static final String SIMPLE_GRAMMAR = "Root <- 'a'";

    // === Packrat Cache Tests ===

    @Test
    void packratCache_whenEnabled_cachesMemoizedResults() {
        var grammar = parseGrammar(SIMPLE_GRAMMAR);
        var config = new ParserConfig(true, RecoveryStrategy.NONE, true);  // packrat enabled
        var ctx = ParsingContext.create("a", grammar, config);

        // Cache should be empty initially
        assertTrue(ctx.getCached("Root").isEmpty());

        // Create a result and cache it
        var node = createTerminal("a");
        var result = ParseResult.Success.of(node, SourceLocation.at(1, 2, 1));
        ctx.cache("Root", result);

        // Should retrieve cached result
        var cached = ctx.getCached("Root");
        assertTrue(cached.isPresent());
        assertEquals(result, cached.unwrap());
    }

    @Test
    void packratCache_whenDisabled_returnsNone() {
        var grammar = parseGrammar(SIMPLE_GRAMMAR);
        var config = new ParserConfig(false, RecoveryStrategy.NONE, true);  // packrat disabled
        var ctx = ParsingContext.create("a", grammar, config);

        // Should return none even after caching
        var node = createTerminal("a");
        var result = ParseResult.Success.of(node, SourceLocation.at(1, 2, 1));
        ctx.cache("Root", result);

        assertTrue(ctx.getCached("Root").isEmpty());
    }

    @Test
    void packratCache_usesPositionInKey() {
        var grammar = parseGrammar(SIMPLE_GRAMMAR);
        var config = new ParserConfig(true, RecoveryStrategy.NONE, true);
        var ctx = ParsingContext.create("aa", grammar, config);

        // Cache at position 0
        var node1 = createTerminal("a");
        var result1 = ParseResult.Success.of(node1, SourceLocation.at(1, 2, 1));
        ctx.cacheAt("Root", 0, result1);

        // Cache at position 1
        ctx.setPos(1);
        var node2 = createTerminal("a");
        var result2 = ParseResult.Success.of(node2, SourceLocation.at(1, 3, 2));
        ctx.cacheAt("Root", 1, result2);

        // Should retrieve different results for different positions
        var cachedAt0 = ctx.getCachedAt("Root", 0);
        var cachedAt1 = ctx.getCachedAt("Root", 1);

        assertTrue(cachedAt0.isPresent());
        assertTrue(cachedAt1.isPresent());

        assertEquals(result1, cachedAt0.unwrap());
        assertEquals(result2, cachedAt1.unwrap());
    }

    @Test
    void packratCache_usesRuleNameInKey() {
        var grammar = parseGrammar("A <- 'a'\nB <- 'b'");
        var config = new ParserConfig(true, RecoveryStrategy.NONE, true);
        var ctx = ParsingContext.create("ab", grammar, config);

        // Cache for rule A
        var nodeA = createTerminal("a");
        var resultA = ParseResult.Success.of(nodeA, SourceLocation.at(1, 2, 1));
        ctx.cache("A", resultA);

        // Cache for rule B at same position
        var nodeB = createTerminal("b");
        var resultB = ParseResult.Success.of(nodeB, SourceLocation.at(1, 2, 1));
        ctx.cache("B", resultB);

        // Should retrieve different results for different rules
        var cachedA = ctx.getCached("A");
        var cachedB = ctx.getCached("B");

        assertTrue(cachedA.isPresent());
        assertTrue(cachedB.isPresent());

        assertEquals(resultA, cachedA.unwrap());
        assertEquals(resultB, cachedB.unwrap());
    }

    // === Position Management Tests ===

    @Test
    void position_startsAtZero() {
        var grammar = parseGrammar(SIMPLE_GRAMMAR);
        var config = new ParserConfig(true, RecoveryStrategy.NONE, true);
        var ctx = ParsingContext.create("test", grammar, config);

        assertEquals(0, ctx.pos());
        var loc = ctx.location();
        assertEquals(1, loc.line());
        assertEquals(1, loc.column());
        assertEquals(0, loc.offset());
    }

    @Test
    void advance_updatesPositionAndColumn() {
        var grammar = parseGrammar(SIMPLE_GRAMMAR);
        var config = new ParserConfig(true, RecoveryStrategy.NONE, true);
        var ctx = ParsingContext.create("abc", grammar, config);

        ctx.advance();
        assertEquals(1, ctx.pos());
        assertEquals(1, ctx.location().line());
        assertEquals(2, ctx.location().column());

        ctx.advance();
        assertEquals(2, ctx.pos());
        assertEquals(3, ctx.location().column());
    }

    @Test
    void advance_updatesLineOnNewline() {
        var grammar = parseGrammar(SIMPLE_GRAMMAR);
        var config = new ParserConfig(true, RecoveryStrategy.NONE, true);
        var ctx = ParsingContext.create("a\nb", grammar, config);

        ctx.advance();  // 'a'
        assertEquals(1, ctx.location().line());

        ctx.advance();  // '\n'
        assertEquals(2, ctx.location().line());
        assertEquals(1, ctx.location().column());  // Reset to 1
    }

    @Test
    void restoreLocation_resetsPositionAndLineColumn() {
        var grammar = parseGrammar(SIMPLE_GRAMMAR);
        var config = new ParserConfig(true, RecoveryStrategy.NONE, true);
        var ctx = ParsingContext.create("abc", grammar, config);

        var saved = ctx.location();

        ctx.advance();
        ctx.advance();
        assertEquals(2, ctx.pos());

        ctx.restoreLocation(saved);
        assertEquals(0, ctx.pos());
        assertEquals(saved, ctx.location());
    }

    // === Capture Tests ===

    @Test
    void capture_setAndGet() {
        var grammar = parseGrammar(SIMPLE_GRAMMAR);
        var config = new ParserConfig(true, RecoveryStrategy.NONE, true);
        var ctx = ParsingContext.create("test", grammar, config);

        ctx.setCapture("name", "value");

        var captured = ctx.getCapture("name");
        assertTrue(captured.isPresent());
        assertEquals("value", captured.unwrap());
    }

    @Test
    void capture_getMissing_returnsNone() {
        var grammar = parseGrammar(SIMPLE_GRAMMAR);
        var config = new ParserConfig(true, RecoveryStrategy.NONE, true);
        var ctx = ParsingContext.create("test", grammar, config);

        var captured = ctx.getCapture("nonexistent");
        assertTrue(captured.isEmpty());
    }

    @Test
    void capture_saveAndRestore() {
        var grammar = parseGrammar(SIMPLE_GRAMMAR);
        var config = new ParserConfig(true, RecoveryStrategy.NONE, true);
        var ctx = ParsingContext.create("test", grammar, config);

        ctx.setCapture("a", "1");
        var saved = ctx.saveCaptures();

        ctx.setCapture("a", "2");
        ctx.setCapture("b", "3");

        ctx.restoreCaptures(saved);

        assertEquals("1", ctx.getCapture("a").unwrap());
        assertTrue(ctx.getCapture("b").isEmpty());
    }

    // === Helper Methods ===

    private static Grammar parseGrammar(String grammar) {
        return GrammarParser.parse(grammar).unwrap();
    }

    private static CstNode.Terminal createTerminal(String text) {
        var span = SourceSpan.of(SourceLocation.at(1, 1, 0), SourceLocation.at(1, text.length() + 1, text.length()));
        return new CstNode.Terminal(span, "Test", text, List.of(), List.of());
    }
}
