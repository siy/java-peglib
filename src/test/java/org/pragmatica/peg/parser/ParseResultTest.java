package org.pragmatica.peg.parser;

import org.junit.jupiter.api.Test;
import org.pragmatica.peg.tree.CstNode;
import org.pragmatica.peg.tree.SourceLocation;
import org.pragmatica.peg.tree.SourceSpan;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ParseResult internal types and behavior.
 * These tests ensure that all ParseResult variants work correctly,
 * including edge cases around null handling and semantic values.
 */
class ParseResultTest {

    private static final SourceLocation LOC = SourceLocation.at(1, 1, 0);
    private static final SourceLocation END_LOC = SourceLocation.at(1, 5, 4);
    private static final SourceSpan SPAN = SourceSpan.of(LOC, END_LOC);

    // === Success Tests ===

    @Test
    void success_isSuccess_returnsTrue() {
        var node = createTerminal("test");
        var result = ParseResult.Success.of(node, END_LOC);

        assertTrue(result.isSuccess());
        assertFalse(result.isFailure());
    }

    @Test
    void success_withoutSemanticValue_hasNoValue() {
        var node = createTerminal("test");
        var result = ParseResult.Success.of(node, END_LOC);

        assertFalse(result.hasSemanticValue());
        assertNull(result.unwrapSemanticValue());
    }

    @Test
    void success_withValue_hasValue() {
        var node = createTerminal("42");
        var result = ParseResult.Success.withValue(node, END_LOC, 42);

        assertTrue(result.hasSemanticValue());
        assertEquals(42, result.unwrapSemanticValue());
    }

    @Test
    void success_withStringValue_preservesValue() {
        var node = createTerminal("hello");
        var result = ParseResult.Success.withValue(node, END_LOC, "hello");

        assertTrue(result.hasSemanticValue());
        assertEquals("hello", result.unwrapSemanticValue());
    }

    @Test
    void success_withExplicitNull_distinguishedFromNoValue() {
        var node = createTerminal("null");
        var result = ParseResult.Success.withValue(node, END_LOC, null);

        // Should have semantic value (explicit null is different from no value)
        assertTrue(result.hasSemanticValue());
        // Should unwrap back to null
        assertNull(result.unwrapSemanticValue());
    }

    @Test
    void success_withSemanticValue_addsToExisting() {
        var node = createTerminal("test");
        var result = ParseResult.Success.of(node, END_LOC);

        var withValue = result.withSemanticValue(100);

        assertFalse(result.hasSemanticValue());  // Original unchanged
        assertTrue(withValue.hasSemanticValue());
        assertEquals(100, withValue.unwrapSemanticValue());
    }

    @Test
    void success_withTrivia_preservesTrivia() {
        var node = createTerminal("test");
        var result = ParseResult.Success.of(node, END_LOC, List.of());

        assertEquals(List.of(), result.trailingTrivia());
    }

    // === Failure Tests ===

    @Test
    void failure_isFailure_returnsTrue() {
        var result = ParseResult.Failure.at(LOC, "expected");

        assertFalse(result.isSuccess());
        assertTrue(result.isFailure());
    }

    @Test
    void failure_preservesExpected() {
        var result = ParseResult.Failure.at(LOC, "digit");

        assertEquals("digit", result.expected());
        assertEquals(LOC, result.location());
    }

    // === CutFailure Tests ===

    @Test
    void cutFailure_isFailure_returnsTrue() {
        var result = ParseResult.CutFailure.at(LOC, "expected after cut");

        assertFalse(result.isSuccess());
        assertTrue(result.isFailure());
    }

    @Test
    void cutFailure_preservesExpected() {
        var result = ParseResult.CutFailure.at(LOC, "expression");

        assertEquals("expression", result.expected());
        assertEquals(LOC, result.location());
    }

    @Test
    void cutFailure_distinctFromRegularFailure() {
        var failure = ParseResult.Failure.at(LOC, "test");
        var cutFailure = ParseResult.CutFailure.at(LOC, "test");

        // Both are failures
        assertTrue(failure.isFailure());
        assertTrue(cutFailure.isFailure());

        // But they are different types
        assertInstanceOf(ParseResult.Failure.class, failure);
        assertInstanceOf(ParseResult.CutFailure.class, cutFailure);
    }

    // === PredicateSuccess Tests ===

    @Test
    void predicateSuccess_isSuccess_returnsTrue() {
        var result = new ParseResult.PredicateSuccess(LOC);

        assertTrue(result.isSuccess());
        assertFalse(result.isFailure());
    }

    @Test
    void predicateSuccess_preservesLocation() {
        var result = new ParseResult.PredicateSuccess(LOC);

        assertEquals(LOC, result.location());
    }

    // === Ignored Tests ===

    @Test
    void ignored_isSuccess_returnsTrue() {
        var result = new ParseResult.Ignored(END_LOC, "matched");

        assertTrue(result.isSuccess());
        assertFalse(result.isFailure());
    }

    @Test
    void ignored_preservesMatchedText() {
        var result = new ParseResult.Ignored(END_LOC, "whitespace");

        assertEquals("whitespace", result.matchedText());
        assertEquals(END_LOC, result.endLocation());
    }

    // === EXPLICIT_NULL Sentinel Tests ===

    @Test
    void explicitNull_hasDistinctToString() {
        assertEquals("EXPLICIT_NULL", ParseResult.EXPLICIT_NULL.toString());
    }

    @Test
    void explicitNull_notEqualToNull() {
        assertNotNull(ParseResult.EXPLICIT_NULL);
    }

    @Test
    void explicitNull_distinguishesNullFromAbsent() {
        var node = createTerminal("test");

        // No semantic value
        var noValue = ParseResult.Success.of(node, END_LOC);
        // Explicit null value
        var nullValue = ParseResult.Success.withValue(node, END_LOC, null);
        // Non-null value
        var someValue = ParseResult.Success.withValue(node, END_LOC, "value");

        // All return null when unwrapped (either no value or explicit null)
        assertNull(noValue.unwrapSemanticValue());
        assertNull(nullValue.unwrapSemanticValue());
        assertEquals("value", someValue.unwrapSemanticValue());

        // But hasSemanticValue distinguishes them
        assertFalse(noValue.hasSemanticValue());
        assertTrue(nullValue.hasSemanticValue());
        assertTrue(someValue.hasSemanticValue());
    }

    // === Helper Methods ===

    private static CstNode.Terminal createTerminal(String text) {
        return new CstNode.Terminal(SPAN, "Test", text, List.of(), List.of());
    }
}
