package org.pragmatica.peg.lexer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.peg.PegParser;
import org.pragmatica.peg.grammar.Expression;
import org.pragmatica.peg.grammar.Rule;
import org.pragmatica.peg.source.SourceSpan;

import org.pragmatica.lang.Option;

import java.util.List;
import java.util.Map;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 0.7.2 — a LEXER rule may name other LEXER rules; the references are substituted before
 * DFA compilation.
 *
 * <p>Until 0.7.2 a rule reference made a rule uncompilable, so a keyword guard and named
 * alternatives could not coexist: the guard requires a named rule, and naming the
 * alternatives got the rule rejected. That combination is what every SQL-shaped grammar
 * needs for {@code ColId}, and it is why {@code java25.peg} spells its {@code value}
 * lookahead inline at four separate use sites.
 */
class LexerReferenceInliningTest {

    private static final SourceSpan SPAN = new SourceSpan(1, 1, 0, 1, 1, 0);

    private static Rule rule(String name, Expression body) {
        return new Rule(SPAN, name, body, Option.none(), Option.none());
    }

    /** {@code ColId <- !ReservedKeyword (Quoted / Unquoted)} — guard plus named alternatives. */
    private static final String COL_ID_GRAMMAR = """
        Stmt     <- 'use'i ColId ';'
        ColId    <- !Keyword (Quoted / Unquoted)
        Quoted   <- '"' < [^"]+ > '"'
        Unquoted <- < [a-zA-Z_] [a-zA-Z0-9_$]* >
        Keyword  <- ('use'i / 'select'i) ![a-zA-Z0-9_$]
        %whitespace <- [ \\t\\r\\n]*
        """;

    @Test
    void guardedRuleWithNamedAlternativesCompiles() {
        assertTrue(PegParser.fromGrammar(COL_ID_GRAMMAR).isSuccess(),
                   "grammar must compile: " + PegParser.fromGrammar(COL_ID_GRAMMAR));
    }

    @Test
    void everyNamedAlternativeStaysReachable() {
        var parser = PegParser.fromGrammar(COL_ID_GRAMMAR).unwrap();
        // Both alternatives must match. Before substitution the second was unreachable in
        // practice, because the only way to satisfy the classifier was to inline one shape
        // and drop the other.
        assertTrue(parser.parse("use foo;").diagnostics().isEmpty(),
                   "unquoted alternative must match");
        assertTrue(parser.parse("use \"my table\";").diagnostics().isEmpty(),
                   "quoted alternative must match");
    }

    @Test
    void guardStillRejectsReservedKeyword() {
        var parser = PegParser.fromGrammar(COL_ID_GRAMMAR).unwrap();
        // Substitution must not weaken the !Keyword guard into an accept-anything rule.
        assertFalse(parser.parse("use select;").diagnostics().isEmpty(),
                    "reserved keyword must not be accepted as ColId");
    }

    /**
     * Substitution is unbounded without a cycle guard, so the guard is tested directly.
     *
     * <p>It cannot be reached through {@code fromGrammar} today: a LEXER-classified cycle needs
     * pure-reference bodies (the initial labelling sends any rule mixing references with
     * terminals to PARSER), and every pure-reference cycle is left-recursive, so
     * {@code LeftRecursionDetector} rejects the grammar several phases earlier. The guard is
     * therefore defence-in-depth — termination here must not depend on an invariant enforced by
     * a different component. An end-to-end test would pass with the guard removed, which is why
     * this one calls the substitution directly.
     */
    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void referenceCycleIsRefusedRatherThanExpandedForever() {
        var span = SPAN;
        var ruleMap = Map.of("A", rule("A", new Expression.Reference(span, "B")),
                             "B", rule("B", new Expression.Reference(span, "A")));
        var kinds = Map.of("A", RuleKind.LEXER, "B", RuleKind.LEXER);

        assertTrue(RuleClassifier.inlineLexerReferences(new Expression.Reference(span, "A"), ruleMap, kinds)
                                 .isEmpty(),
                   "a reference cycle must be refused rather than substituted forever");
    }

    /** A self-reference is the depth-0 case of the same guard. */
    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void selfReferenceIsRefusedRatherThanExpandedForever() {
        var span = SPAN;
        var ruleMap = Map.of("Loop", rule("Loop", new Expression.Reference(span, "Loop")));
        var kinds = Map.of("Loop", RuleKind.LEXER);

        assertTrue(RuleClassifier.inlineLexerReferences(new Expression.Reference(span, "Loop"), ruleMap, kinds)
                                 .isEmpty(),
                   "a self-reference must be refused rather than substituted forever");
    }

    /** A shared reference is not a cycle: the same rule may be substituted on sibling branches. */
    @Test
    void repeatedReferenceOnSiblingBranchesIsNotMistakenForCycle() {
        var span = SPAN;
        var shared = new Expression.Reference(span, "Digit");
        var ruleMap = Map.of("Digit", rule("Digit", new Expression.CharClass(span, "0-9", false, false)));
        var kinds = Map.of("Digit", RuleKind.LEXER);
        var body = new Expression.Sequence(span, List.of(shared, shared));

        assertTrue(RuleClassifier.inlineLexerReferences(body, ruleMap, kinds).isPresent(),
                   "the same rule referenced twice is not a cycle and must still substitute");
    }
}
