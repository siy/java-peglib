package org.pragmatica.peg.incremental.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.pragmatica.peg.grammar.Grammar;
import org.pragmatica.peg.grammar.GrammarParser;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link SafePivotAnalyzer}. Phase 2 (v0.5.0) Lever B
 * static-analysis pass that decides which grammar rules are safe to use as
 * incremental {@code parseRuleAt} pivots.
 *
 * <p>{@link SafePivotAnalyzer#safePivotRules(Grammar)} requires both:
 * <ul>
 *   <li>NOT in {@link BackReferenceScan#unsafeRules(Grammar)}, and</li>
 *   <li>has an unambiguous, non-empty literal prefix.</li>
 * </ul>
 *
 * <p>The literal-prefix requirement is the structural correctness gate
 * against PEG context-sensitivity (see
 * {@link org.pragmatica.peg.incremental.IncrementalParityTest}).
 */
final class SafePivotAnalyzerTest {

    private static Grammar grammar(String text) {
        return GrammarParser.parse(text).fold(
            cause -> { throw new IllegalStateException("grammar parse failed: " + cause.message()); },
            g -> g);
    }

    @Test
    @DisplayName("rule starting with a single-char literal is safe")
    void singleCharLiteralPrefixIsSafe() {
        var g = grammar("""
            Block <- '{' Stmt* '}'
            Stmt <- 'x' ';'
            """);
        var safe = SafePivotAnalyzer.safePivotRules(g);
        assertThat(safe).contains("Block", "Stmt");
    }

    @Test
    @DisplayName("rule starting with a multi-char literal is safe")
    void multiCharLiteralPrefixIsSafe() {
        var g = grammar("""
            Decl <- 'class' Name
            Name <- 'X'
            """);
        var safe = SafePivotAnalyzer.safePivotRules(g);
        assertThat(safe).contains("Decl");
    }

    @Test
    @DisplayName("rule starting with parenthesised literal is safe")
    void parenthesisedLiteralPrefixIsSafe() {
        var g = grammar("""
            Args <- ('(' Inner ')')
            Inner <- '0'
            """);
        var safe = SafePivotAnalyzer.safePivotRules(g);
        assertThat(safe).contains("Args");
    }

    @Test
    @DisplayName("rule starting with a Reference (rule call) is unsafe")
    void referencePrefixIsUnsafe() {
        var g = grammar("""
            Stmt <- Ident ';'
            Ident <- 'x'
            """);
        var safe = SafePivotAnalyzer.safePivotRules(g);
        // Ident is safe (literal prefix). Stmt is NOT — starts with rule reference.
        assertThat(safe).contains("Ident");
        assertThat(safe).doesNotContain("Stmt");
    }

    @Test
    @DisplayName("rule starting with a CharClass is unsafe")
    void charClassPrefixIsUnsafe() {
        var g = grammar("""
            Letter <- [a-z]
            """);
        var safe = SafePivotAnalyzer.safePivotRules(g);
        assertThat(safe).doesNotContain("Letter");
    }

    @Test
    @DisplayName("rule starting with an Optional is unsafe (could consume nothing)")
    void optionalPrefixIsUnsafe() {
        var g = grammar("""
            Maybe <- '+'? 'x'
            """);
        var safe = SafePivotAnalyzer.safePivotRules(g);
        assertThat(safe).doesNotContain("Maybe");
    }

    @Test
    @DisplayName("rule starting with ZeroOrMore is unsafe")
    void zeroOrMorePrefixIsUnsafe() {
        var g = grammar("""
            Stars <- '*'* 'end'
            """);
        var safe = SafePivotAnalyzer.safePivotRules(g);
        assertThat(safe).doesNotContain("Stars");
    }

    @Test
    @DisplayName("rule starting with OneOrMore is conservatively unsafe")
    void oneOrMorePrefixIsConservativelyUnsafe() {
        var g = grammar("""
            Pluses <- '+'+ 'x'
            """);
        var safe = SafePivotAnalyzer.safePivotRules(g);
        // OneOrMore is conservatively rejected — its inner is literal but the
        // quantifier itself is not a literal-prefix structure.
        assertThat(safe).doesNotContain("Pluses");
    }

    @Test
    @DisplayName("rule starting with Choice is unsafe")
    void choicePrefixIsUnsafe() {
        var g = grammar("""
            Either <- ('a' / 'b') 'x'
            """);
        var safe = SafePivotAnalyzer.safePivotRules(g);
        // The first element of the Sequence is a Choice — conservative no.
        assertThat(safe).doesNotContain("Either");
    }

    @Test
    @DisplayName("rule with TokenBoundary wrapping a literal is safe")
    void tokenBoundaryWrappingLiteralIsSafe() {
        var g = grammar("""
            Capture <- < 'foo' >
            """);
        var safe = SafePivotAnalyzer.safePivotRules(g);
        assertThat(safe).contains("Capture");
    }

    @Test
    @DisplayName("rule using back-reference is excluded even with literal prefix")
    void backRefRuleIsExcluded() {
        var g = grammar("""
            Tag <- '<' $name<Ident> '>' Body '</' $name '>'
            Body <- (!'<' .)*
            Ident <- < [a-zA-Z]+ >
            %whitespace <- [ \\t\\n]*
            """);
        var safe = SafePivotAnalyzer.safePivotRules(g);
        // Tag has '<' literal prefix BUT uses back-ref → must be excluded.
        assertThat(safe).doesNotContain("Tag");
    }

    @Test
    @DisplayName("singleton grammar with literal prefix yields singleton safe set")
    void singletonGrammarYieldsSingletonSafeSet() {
        var g = grammar("""
            R <- 'x'
            """);
        var safe = SafePivotAnalyzer.safePivotRules(g);
        assertThat(safe).containsExactly("R");
    }

    @Test
    @DisplayName("transitively unsafe rules are excluded even with literal prefix")
    void transitivelyUnsafeIsExcluded() {
        var g = grammar("""
            Wrapper <- '[' Tag ']'
            Tag <- '<' $name<Ident> '>' Body '</' $name '>'
            Body <- (!'<' .)*
            Ident <- < [a-zA-Z]+ >
            """);
        var safe = SafePivotAnalyzer.safePivotRules(g);
        // Wrapper transitively depends on Tag (back-ref), so transitively unsafe.
        assertThat(safe).doesNotContain("Wrapper");
    }
}
