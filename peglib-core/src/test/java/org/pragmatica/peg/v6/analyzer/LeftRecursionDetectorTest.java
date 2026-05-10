package org.pragmatica.peg.v6.analyzer;

import org.pragmatica.lang.Option;
import org.pragmatica.peg.grammar.Expression;
import org.pragmatica.peg.grammar.Grammar;
import org.pragmatica.peg.grammar.Rule;
import org.pragmatica.peg.tree.SourceLocation;
import org.pragmatica.peg.tree.SourceSpan;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 0.6.0 — coverage for {@link LeftRecursionDetector}. Each test constructs a
 * minimal {@link Grammar} via the canonical constructor (bypassing
 * {@link Grammar#grammar Grammar.grammar()} validation, which already rejects
 * indirect LR) so the detector itself can be exercised in isolation. The final
 * test loads the production Java25 grammar and asserts it stays clean.
 */
class LeftRecursionDetectorTest {
    private static final SourceSpan SPAN = SourceSpan.sourceSpan(SourceLocation.START);

    @Test
    void directLeftRecursion_isDetected() {
        // Expr <- Expr '+' Term / Term
        var grammar = grammar(
        rule("Expr", choice(
        seq(ref("Expr"), literal("+"), ref("Term")), ref("Term"))), rule("Term", literal("x")));
        var result = LeftRecursionDetector.detect(grammar)
                                          .unwrap();
        assertTrue(result.hasErrors());
        var errors = result.errors();
        assertEquals(1, errors.size(), () -> "expected 1 LR error, got: " + errors);
        var err = errors.get(0);
        assertEquals("Expr", err.ruleName());
        assertEquals(List.of("Expr", "Expr"), err.witnessCycle());
        assertTrue(err.message()
                      .contains("Expr"));
        assertTrue(err.message()
                      .contains("left-recursive"));
    }

    @Test
    void indirectLeftRecursion_twoCycle_isDetected() {
        // A <- B 'x'
        // B <- A 'y'
        var grammar = grammar(
        rule("A", seq(ref("B"), literal("x"))), rule("B", seq(ref("A"), literal("y"))));
        var result = LeftRecursionDetector.detect(grammar)
                                          .unwrap();
        assertTrue(result.hasErrors());
        // Both A and B are left-recursive — each can reach itself via the other.
        assertEquals(2,
                     result.errors()
                           .size());
        var aWitness = findError(result.errors(),
                                 "A")
                       .witnessCycle();
        assertEquals(List.of("A", "B", "A"), aWitness);
        var bWitness = findError(result.errors(),
                                 "B")
                       .witnessCycle();
        assertEquals(List.of("B", "A", "B"), bWitness);
    }

    @Test
    void indirectLeftRecursion_threeCycle_isDetected() {
        // A <- B
        // B <- C
        // C <- A
        var grammar = grammar(
        rule("A", ref("B")), rule("B", ref("C")), rule("C", ref("A")));
        var result = LeftRecursionDetector.detect(grammar)
                                          .unwrap();
        assertTrue(result.hasErrors());
        var aWitness = findError(result.errors(),
                                 "A")
                       .witnessCycle();
        assertEquals(List.of("A", "B", "C", "A"), aWitness);
    }

    @Test
    void rightRecursion_isAccepted() {
        // Expr <- Term '+' Expr / Term
        var grammar = grammar(
        rule("Expr", choice(
        seq(ref("Term"), literal("+"), ref("Expr")), ref("Term"))), rule("Term", charClass("0-9")));
        var result = LeftRecursionDetector.detect(grammar)
                                          .unwrap();
        assertFalse(result.hasErrors(), () -> "right-recursive grammar must not be flagged: " + result.errors());
    }

    @Test
    void nullableBypass_triggersLeftRecursion() {
        // A <- B? A
        // B <- 'x'
        // B is nullable (optional), so A's leftmost can be A itself.
        var grammar = grammar(
        rule("A", seq(optional(ref("B")), ref("A"))), rule("B", literal("x")));
        var result = LeftRecursionDetector.detect(grammar)
                                          .unwrap();
        assertTrue(result.hasErrors(), () -> "nullable-prefix LR must be detected; nullable=" + result.nullable());
        assertEquals("A",
                     result.errors()
                           .get(0)
                           .ruleName());
    }

    @Test
    void nullableRule_propagatesThroughReferenceChain() {
        // C <- D? E
        // D <- 'd'
        // E <- C 'e'
        // E references C as leftmost; C's leftmost is D (nullable) then E. So C → E → C.
        var grammar = grammar(
        rule("C", seq(optional(ref("D")), ref("E"))), rule("D", literal("d")), rule("E", seq(ref("C"), literal("e"))));
        var result = LeftRecursionDetector.detect(grammar)
                                          .unwrap();
        assertTrue(result.hasErrors());
        var cWitness = findError(result.errors(),
                                 "C")
                       .witnessCycle();
        assertEquals(List.of("C", "E", "C"), cWitness);
    }

    @Test
    void noRecursion_returnsEmptyErrors() {
        // A <- 'foo'
        // B <- 'bar'
        var grammar = grammar(
        rule("A", literal("foo")), rule("B", literal("bar")));
        var result = LeftRecursionDetector.detect(grammar)
                                          .unwrap();
        assertFalse(result.hasErrors());
        assertTrue(result.errors()
                         .isEmpty());
    }

    @Test
    void nullableAnalysis_reportsCorrectFlags() {
        var grammar = grammar(
        rule("Empty", optional(literal("x"))),
        rule("NonEmpty", literal("y")),
        rule("Star", zeroOrMore(literal("z"))),
        rule("Plus", oneOrMore(literal("w"))),
        rule("Choice", choice(optional(literal("a")), literal("b"))),
        rule("AllNullable", seq(optional(literal("a")), zeroOrMore(literal("b")))));
        var nullable = LeftRecursionDetector.detect(grammar)
                                            .unwrap()
                                            .nullable();
        assertEquals(true, nullable.get("Empty"));
        assertEquals(false, nullable.get("NonEmpty"));
        assertEquals(true, nullable.get("Star"));
        assertEquals(false, nullable.get("Plus"));
        assertEquals(true, nullable.get("Choice"));
        assertEquals(true, nullable.get("AllNullable"));
    }

    @Test
    void leftmostRefs_areExposedForDiagnostics() {
        var grammar = grammar(
        rule("A", choice(ref("B"), ref("C"))), rule("B", literal("b")), rule("C", literal("c")));
        var refs = LeftRecursionDetector.detect(grammar)
                                        .unwrap()
                                        .leftmostRefs();
        assertEquals(java.util.Set.of("B", "C"), refs.get("A"));
        assertTrue(refs.get("B")
                       .isEmpty());
    }

    @Test
    void java25Grammar_isFreeOfLeftRecursion() throws IOException {
        var grammarPath = Paths.get("src/test/resources/java25.peg");
        var grammarText = Files.readString(grammarPath, StandardCharsets.UTF_8);
        var parsed = org.pragmatica.peg.grammar.GrammarParser.parse(grammarText);
        assertTrue(parsed.isSuccess(), () -> "Java25 grammar must parse: " + parsed);
        var grammar = parsed.unwrap();
        var result = LeftRecursionDetector.detect(grammar)
                                          .unwrap();
        assertFalse(result.hasErrors(),
                    () -> "Java25 grammar must be free of left-recursion. Offenders: " + result.errors()
                                                                                               .stream()
                                                                                               .map(LeftRecursionDetector.LeftRecursionError::message)
                                                                                               .toList());
    }

    @Test
    void leftRecursionCause_aggregatesErrors() {
        var grammar = grammar(
        rule("A", seq(ref("A"), literal("x"))), rule("B", seq(ref("B"), literal("y"))));
        var result = LeftRecursionDetector.detect(grammar)
                                          .unwrap();
        assertEquals(2,
                     result.errors()
                           .size());
        var cause = LeftRecursionCause.of(result);
        var msg = cause.message();
        assertTrue(msg.contains("2 left-recursive rules"), msg);
        assertTrue(msg.contains("'A'"), msg);
        assertTrue(msg.contains("'B'"), msg);
    }

    // ---------------------------------------------------------------------
    // Tiny DSL for assembling Grammar fixtures without touching the parser.
    // ---------------------------------------------------------------------
    private static LeftRecursionDetector.LeftRecursionError findError(List<LeftRecursionDetector.LeftRecursionError> errors,
                                                                      String ruleName) {
        return errors.stream()
                     .filter(e -> e.ruleName()
                                   .equals(ruleName))
                     .findFirst()
                     .orElseThrow(() -> new AssertionError("no error for rule '" + ruleName + "' in " + errors));
    }

    private static Grammar grammar(Rule... rules) {
        return new Grammar(
        List.of(rules), Option.none(), Option.none(), Option.none(), List.of(), List.of(), Map.of());
    }

    private static Rule rule(String name, Expression body) {
        return new Rule(SPAN, name, body, Option.none(), Option.none());
    }

    private static Expression ref(String name) {
        return new Expression.Reference(SPAN, name);
    }

    private static Expression literal(String text) {
        return new Expression.Literal(SPAN, text, false);
    }

    private static Expression charClass(String pattern) {
        return new Expression.CharClass(SPAN, pattern, false, false);
    }

    private static Expression seq(Expression... elements) {
        return new Expression.Sequence(SPAN, List.of(elements));
    }

    private static Expression choice(Expression... alts) {
        return new Expression.Choice(SPAN, List.of(alts));
    }

    private static Expression optional(Expression inner) {
        return new Expression.Optional(SPAN, inner);
    }

    private static Expression zeroOrMore(Expression inner) {
        return new Expression.ZeroOrMore(SPAN, inner);
    }

    private static Expression oneOrMore(Expression inner) {
        return new Expression.OneOrMore(SPAN, inner);
    }

    @SuppressWarnings("unused")
    private static Path placeholder() {
        return null;
    }
}
