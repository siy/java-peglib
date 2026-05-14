package org.pragmatica.peg.v6.generator;

import org.junit.jupiter.api.Test;
import org.pragmatica.peg.v6.PegParser;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 0.6.1 — Item E. Exercises the MIXED-rule char-level fallback: when a rule is
 * classified {@link org.pragmatica.peg.v6.lexer.RuleKind#MIXED}, bare
 * char-level constructs ({@link org.pragmatica.peg.grammar.Expression.CharClass}
 * and {@link org.pragmatica.peg.grammar.Expression.Any}) gain a token-level
 * proxy that peeks at the first character of the current token's source text
 * and consumes one token on match.
 *
 * <p>{@code &(char-level)} / {@code !(char-level)} predicates inside MIXED
 * rules deliberately stay no-op (they are word-boundary guards already
 * enforced by the lexer; activating them as token-level probes mis-fires on
 * the next token). {@link org.pragmatica.peg.grammar.Expression.Dictionary}
 * also remains a no-op (still unsupported in 0.6.x). Outside MIXED rules all
 * char-level constructs remain parse-time no-ops; LEXER rules don't go
 * through {@link ParserGenerator} at all.
 *
 * <p>NOTE: the {@code LexerEngine} emits a one-char WHITESPACE token for any
 * input char its DFA cannot match (so lexing can always make progress). For
 * the char-class proxy in a MIXED rule to ever observe a punctuation
 * character like {@code !}, the grammar must contain an inline literal /
 * char-class that teaches the lexer about that char; otherwise it becomes
 * trivia and {@code pos} (which always points to a non-trivia token) never
 * sees it. Each test below uses {@code Punct <- '!' / '?'} (a LEXER alias
 * rule that exists solely to register punctuation kinds with the DFA).
 */
class MixedRuleFallbackTest {

    private static final String PUNCT_RULE = "Punct <- '!' / '?'\n";
    private static final String WS_RULE = "%whitespace <- [ \\t\\n]*\n";

    /**
     * MIXED rule that references the LEXER-classified {@code Word} and then a
     * bare {@code [!]} char-level construct. With the fallback in place, the
     * proxy peeks at the current token's first char and consumes it on match.
     */
    @Test
    void charClassConsumesTokenAfterLexerRef() {
        var grammar = """
                Start <- MixedRef+
                MixedRef <- Word [!]
                Word <- [a-z]+
                """
                + PUNCT_RULE + WS_RULE;
        var parser = PegParser.fromGrammar(grammar).unwrap();
        var result = parser.parse("hello! world!");
        assertTrue(result.diagnostics().isEmpty(),
                   "expected clean parse, got diagnostics: " + result.diagnostics());
        var cst = result.cst();
        assertTrue(cst.descendants(0).count() > 0,
                   "expected non-empty CST under Start");
    }

    /**
     * On a non-matching char ({@code ?} where {@code !} is required), the
     * MIXED rule's CharClass must report failure rather than silently passing.
     */
    @Test
    void charClassMismatchProducesDiagnostic() {
        var grammar = """
                Start <- MixedRef+
                MixedRef <- Word [!]
                Word <- [a-z]+
                """
                + PUNCT_RULE + WS_RULE;
        var parser = PegParser.fromGrammar(grammar).unwrap();
        var result = parser.parse("hello?");
        assertFalse(result.diagnostics().isEmpty(),
                    "expected diagnostics for 'hello?' (CharClass [!] should not match '?')");
    }

    /**
     * MIXED rule using {@code Any} ({@code .}) — consumes one token of any
     * kind. Verifies that the existing Any-token emit also fires inside MIXED.
     */
    @Test
    void anyConsumesOneTokenInMixedRule() {
        var grammar = """
                Start <- MixedRef+
                MixedRef <- Word .
                Word <- [a-z]+
                """
                + PUNCT_RULE + WS_RULE;
        var parser = PegParser.fromGrammar(grammar).unwrap();
        var result = parser.parse("hello! world?");
        assertTrue(result.diagnostics().isEmpty(),
                   "expected clean parse with '.' in MIXED rule, got: " + result.diagnostics());
    }

    /**
     * Choice between a char-level alternative {@code [!]} and a parser-level
     * alternative {@code Word} inside a MIXED rule. Both branches must reach
     * successfully on appropriate input — the char-class proxy must succeed
     * inside a Choice alternative, not just at the top of a Sequence.
     */
    @Test
    void choiceBetweenCharAndParserAlternativesInMixed() {
        var grammar = """
                Start <- Item+
                Item <- (Word / [!])
                Word <- [a-z]+
                """
                + PUNCT_RULE + WS_RULE;
        var parser = PegParser.fromGrammar(grammar).unwrap();
        var result = parser.parse("hello ! world !");
        assertTrue(result.diagnostics().isEmpty(),
                   "expected clean parse with mixed-alts Choice in MIXED rule, got: " + result.diagnostics());
    }

    /**
     * Negated CharClass {@code [^!]} inside a MIXED rule: must reject a token
     * whose first char is {@code !} and accept other tokens.
     */
    @Test
    void negatedCharClassRejectsListedChar() {
        var grammar = """
                Start <- MixedRef
                MixedRef <- Word [^!]
                Word <- [a-z]+
                """
                + PUNCT_RULE + WS_RULE;
        var parser = PegParser.fromGrammar(grammar).unwrap();
        var bad = parser.parse("hello!");
        assertFalse(bad.diagnostics().isEmpty(),
                    "expected diagnostics: '!' should not match [^!]");
        var ok = parser.parse("hello world");
        assertTrue(ok.diagnostics().isEmpty(),
                   "expected clean parse: 'world' matches [^!]; got: " + ok.diagnostics());
    }

    /**
     * Char-level And/Not predicates remain no-op inside MIXED rules. We verify
     * this by constructing a rule whose predicate would REJECT the input if
     * activated — and showing it still parses cleanly. Rationale: in real
     * grammars these predicates are word-boundary guards (e.g.
     * {@code 'var' ![a-zA-Z0-9_$]}) already enforced by the lexer's token
     * boundary; re-activating them as token-level probes mis-fires on the
     * NEXT token (a token after the keyword token).
     */
    @Test
    void charLevelPredicateInsideMixedIsNoOp() {
        var grammar = """
                Start <- MixedRef+
                MixedRef <- Word ![a-z]
                Word <- [a-z]+
                """
                + WS_RULE;
        // After Word matches "hello", the next non-trivia token is "world"
        // whose first char is 'w' — in [a-z]. If the !-predicate were active,
        // this would fail. With predicates kept as no-op (matching prior
        // behavior), the parse must succeed cleanly.
        var parser = PegParser.fromGrammar(grammar).unwrap();
        var result = parser.parse("hello world");
        assertTrue(result.diagnostics().isEmpty(),
                   "char-level !-predicate inside MIXED must remain no-op; got: " + result.diagnostics());
    }
}
