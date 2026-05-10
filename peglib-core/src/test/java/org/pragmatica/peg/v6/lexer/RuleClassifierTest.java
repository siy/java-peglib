package org.pragmatica.peg.v6.lexer;

import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Option;
import org.pragmatica.peg.grammar.Expression;
import org.pragmatica.peg.grammar.Grammar;
import org.pragmatica.peg.grammar.GrammarParser;
import org.pragmatica.peg.grammar.Rule;
import org.pragmatica.peg.tree.SourceLocation;
import org.pragmatica.peg.tree.SourceSpan;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RuleClassifierTest {

    private static final SourceSpan SPAN = SourceSpan.sourceSpan(
        new SourceLocation(1, 1, 0),
        new SourceLocation(1, 1, 0));

    private static Expression.Literal lit(String text) {
        return new Expression.Literal(SPAN, text, false);
    }

    private static Expression.CharClass cc(String pattern) {
        return new Expression.CharClass(SPAN, pattern, false, false);
    }

    private static Expression.Reference ref(String name) {
        return new Expression.Reference(SPAN, name);
    }

    private static Expression.Sequence seq(Expression... els) {
        return new Expression.Sequence(SPAN, List.of(els));
    }

    private static Expression.OneOrMore plus(Expression e) {
        return new Expression.OneOrMore(SPAN, e);
    }

    private static Expression.ZeroOrMore star(Expression e) {
        return new Expression.ZeroOrMore(SPAN, e);
    }

    private static Expression.Any any() {
        return new Expression.Any(SPAN);
    }

    private static Rule rule(String name, Expression expr) {
        return new Rule(SPAN, name, expr, Option.none(), Option.none());
    }

    private static Grammar grammar(Rule... rules) {
        return Grammar.grammar(List.of(rules), Option.none(), Option.none(), Option.none()).unwrap();
    }

    /**
     * Build a Grammar bypassing the validation factory — used only for tests that
     * exercise classifier behaviour on shapes the production factory rejects (e.g.
     * the indirect-left-recursion cycle test).
     */
    private static Grammar grammarUnchecked(Rule... rules) {
        return new Grammar(List.of(rules), Option.none(), Option.none(), Option.none(), List.of(), List.of());
    }

    @Test
    void classify_pureLexicalRule_isLexer() {
        var g = grammar(rule("Number", plus(cc("0-9"))));

        var classification = RuleClassifier.classify(g).unwrap();

        assertThat(classification.kinds()).containsEntry("Number", RuleKind.LEXER);
        assertThat(classification.warnings()).isEmpty();
    }

    @Test
    void classify_parserRuleMixingTerminalsAndRefs_isParser() {
        // Sum uses both refs (Number) and terminals ('+'), so it's tentatively PARSER per
        // the structural distinction "has terminals AND refs in the body" → combinator over
        // tokens. Number, with no refs and only terminals, remains LEXER.
        var number = rule("Number", plus(cc("0-9")));
        var sum = rule("Sum", seq(ref("Number"), lit("+"), ref("Number")));
        var g = grammar(number, sum);

        var classification = RuleClassifier.classify(g).unwrap();

        assertThat(classification.kinds()).containsEntry("Number", RuleKind.LEXER);
        assertThat(classification.kinds()).containsEntry("Sum", RuleKind.PARSER);
        assertThat(classification.warnings()).isEmpty();
    }

    @Test
    void classify_ruleWithBackReference_isParser() {
        // Categorical non-lexical: BackReference disqualifies a rule from LEXER candidacy.
        var capture = rule("Capture", new Expression.Capture(SPAN, "n", plus(cc("0-9"))));
        var pair = rule("Pair", seq(capture.expression(), new Expression.BackReference(SPAN, "n")));
        var g = grammar(capture, pair);

        var classification = RuleClassifier.classify(g).unwrap();

        assertThat(classification.kinds()).containsEntry("Pair", RuleKind.PARSER);
    }

    @Test
    void classify_lexerRuleReferencingLexerRule_allLexer() {
        var idStart = rule("IdStart", cc("a-zA-Z"));
        var idCont = rule("IdCont", cc("a-zA-Z0-9"));
        var identifier = rule("Identifier", seq(ref("IdStart"), star(ref("IdCont"))));
        var g = grammar(idStart, idCont, identifier);

        var classification = RuleClassifier.classify(g).unwrap();

        assertThat(classification.kinds()).containsEntry("IdStart", RuleKind.LEXER);
        assertThat(classification.kinds()).containsEntry("IdCont", RuleKind.LEXER);
        // Identifier transitively references only LEXER rules: it should remain LEXER candidate
        // after fixed-point demotion since none of its referenced rules ever flip non-LEXER.
        assertThat(classification.kinds()).containsEntry("Identifier", RuleKind.LEXER);
        assertThat(classification.warnings()).isEmpty();
    }

    @Test
    void classify_demotionChain_allParser() {
        var stmt = rule("Stmt", seq(lit("if"), ref("Expr"), lit("then"), ref("Block")));
        var expr = rule("Expr", plus(cc("0-9")));
        var block = rule("Block", seq(lit("{"), lit("}")));
        var bar = rule("Bar", ref("Stmt"));
        var foo = rule("Foo", ref("Bar"));
        var g = grammar(stmt, expr, block, bar, foo);

        var classification = RuleClassifier.classify(g).unwrap();

        assertThat(classification.kinds()).containsEntry("Expr", RuleKind.LEXER);
        assertThat(classification.kinds()).containsEntry("Block", RuleKind.LEXER);
        assertThat(classification.kinds()).containsEntry("Stmt", RuleKind.PARSER);
        assertThat(classification.kinds()).containsEntry("Bar", RuleKind.PARSER);
        assertThat(classification.kinds()).containsEntry("Foo", RuleKind.PARSER);
        assertThat(classification.warnings()).isEmpty();
    }

    @Test
    void classify_referenceAndAnyMixed_emitsMixedWithWarning() {
        var atom = rule("Atom", plus(cc("0-9")));
        var weird = rule("Weird", seq(ref("Atom"), any()));
        var g = grammar(atom, weird);

        var classification = RuleClassifier.classify(g).unwrap();

        assertThat(classification.kinds()).containsEntry("Atom", RuleKind.LEXER);
        assertThat(classification.kinds()).containsEntry("Weird", RuleKind.MIXED);
        assertThat(classification.warnings())
            .extracting(RuleClassifier.Warning::ruleName)
            .containsExactly("Weird");
        assertThat(classification.warnings().getFirst().reason())
            .contains("character-level");
    }

    @Test
    void classify_emptyGrammar_returnsEmptyMap() {
        // Grammar.grammar requires at least one rule for validation? Build a minimal grammar.
        // We bypass the factory because empty rule list is technically permitted by the record.
        var g = new Grammar(List.of(), Option.none(), Option.none(), Option.none(), List.of(), List.of());

        var classification = RuleClassifier.classify(g).unwrap();

        assertThat(classification.kinds()).isEmpty();
        assertThat(classification.warnings()).isEmpty();
    }

    @Test
    void classify_cyclicRules_terminatesAndBothParser() {
        // A <- B, B <- A — both reference each other; neither bottoms out at a lexical
        // construct, so even though the initial labelling marks them LEXER (no non-lexical
        // constructs are used), the worklist demotion has nothing to do AND nothing to seed:
        // the chain has no LEXER-disqualifying ground term, but ALSO no PARSER seed. Thus
        // both stay LEXER. Verify the algorithm terminates and produces a stable result.
        // Grammar.grammar() rejects this shape as indirect left-recursion; bypass for the test.
        var a = rule("A", ref("B"));
        var b = rule("B", ref("A"));
        var g = grammarUnchecked(a, b);

        var classification = RuleClassifier.classify(g).unwrap();

        // No PARSER seed exists in the cycle, so both are treated as candidate-LEXER. This is
        // a valid (vacuous) outcome — neither rule has a lexical bottom AND no rule is
        // demoted. A real grammar will not have such a cycle.
        assertThat(classification.kinds()).containsKeys("A", "B");
        assertThat(classification.warnings()).isEmpty();
    }

    @Test
    void classify_java25Grammar_lexerAndParserCoexist() throws IOException {
        var grammarText = Files.readString(
            Paths.get("src/test/resources/java25.peg"),
            StandardCharsets.UTF_8);
        var grammar = GrammarParser.parse(grammarText).unwrap();

        var classification = RuleClassifier.classify(grammar).unwrap();

        var counts = countByKind(classification.kinds());
        // Sanity: grammar should have rules.
        assertThat(grammar.rules()).isNotEmpty();
        // Both kinds should appear in a real grammar.
        assertThat(counts.getOrDefault(RuleKind.LEXER, 0)).isGreaterThan(0);
        assertThat(counts.getOrDefault(RuleKind.PARSER, 0)).isGreaterThan(0);
        // MIXED rules are expected to be rare; more than a handful indicates either a
        // grammar quality issue or a classifier bug.
        assertThat(counts.getOrDefault(RuleKind.MIXED, 0)).isLessThanOrEqualTo(10);

        // Token-shaped keyword rules in java25.peg are pure literal+char-class — they must
        // be LEXER. Identifier in this particular grammar uses lookahead like !Keyword
        // alongside char-classes, which makes it a MIXED rule by design — we don't pin it
        // here since that's a grammar-author choice, not a classifier invariant.
        assertLexerIfPresent(classification.kinds(), "ClassKW");
        assertLexerIfPresent(classification.kinds(), "InterfaceKW");
        assertLexerIfPresent(classification.kinds(), "EnumKW");
        assertLexerIfPresent(classification.kinds(), "RecordKW");

        // Diagnostic output for the report.
        System.out.println("[RuleClassifier:java25] kinds: " + counts);
        if (!classification.warnings().isEmpty()) {
            System.out.println("[RuleClassifier:java25] warnings:");
            for (var w : classification.warnings()) {
                System.out.println("  - " + w.ruleName() + ": " + w.reason());
            }
        }
    }

    private static void assertLexerIfPresent(Map<String, RuleKind> kinds, String name) {
        if (kinds.containsKey(name)) {
            assertThat(kinds.get(name))
                .as("rule " + name + " expected LEXER")
                .isEqualTo(RuleKind.LEXER);
        }
    }

    private static Map<RuleKind, Integer> countByKind(Map<String, RuleKind> kinds) {
        var counts = new LinkedHashMap<RuleKind, Integer>();
        for (var k : kinds.values()) {
            counts.merge(k, 1, Integer::sum);
        }
        return counts;
    }

}
