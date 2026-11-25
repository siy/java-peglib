package org.pragmatica.peg.analyzer;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.pragmatica.peg.generator.ParserGenerator;
import org.pragmatica.peg.grammar.Expression;
import org.pragmatica.peg.grammar.Grammar;
import org.pragmatica.peg.grammar.Rule;
import org.pragmatica.peg.grammar.analysis.WidthAnalysis;
import org.pragmatica.peg.lexer.Dfa;
import org.pragmatica.peg.lexer.DfaBuilder;
import org.pragmatica.peg.lexer.RuleClassifier;
import org.pragmatica.peg.lexer.RuleKind;
import org.pragmatica.peg.token.TokenArray;


/**
 * 0.7.4 — report character-level constructs that a token-level rule cannot honour (issue #48).
 *
 * <p>The generated parser sees tokens, so a consuming character class does one of two things
 * outside a LEXER rule: in a MIXED rule it consumes ONE WHOLE TOKEN whose first character is in the
 * class, and in a PARSER rule it is a no-op. Trivia is skipped before either is consulted.
 *
 * <p>"Can never match" is decided from the DFA rather than by asking whether the class looks like
 * whitespace, because whether a newline is trivia is the grammar's choice: a class is dead exactly
 * when no content token can start with any of its characters.
 *
 * <p>Word-boundary guards ({@code 'var' ![a-zA-Z0-9_$]}) are deliberately exempt: the lexer already
 * enforces them. Only a lookahead naming a character that is trivia in this grammar is reported.
 */
final class CharLevelChecks {
    private static final int NON_ASCII = Dfa.ALPHABET_SIZE;
    private static final int SLOTS = Dfa.ALPHABET_SIZE + 1;

    private final RuleClassifier.Classification classification;
    private final BitSet contentStart = new BitSet(SLOTS);
    private final BitSet longContentStart = new BitSet(SLOTS);
    private final BitSet triviaWhitespace;
    private final Set<Finding> findings = new LinkedHashSet<>();

    private CharLevelChecks(Grammar grammar, RuleClassifier.Classification classification, Dfa dfa) {
        this.classification = classification;
        this.triviaWhitespace = triviaWhitespace(grammar);
        profileContentTokens(dfa);
    }

    static List<Finding> check(Grammar grammar) {
        return RuleClassifier.classify(grammar)
                             .flatMap(classification -> DfaBuilder.build(grammar, classification).map(built -> new CharLevelChecks(grammar,
                                                                                                                                   classification,
                                                                                                                                   built.dfa()).run(grammar)))
                             .or(List::of);
    }

    private List<Finding> run(Grammar grammar) {
        grammar.rules()
               .stream()
               .filter(rule -> classification.kinds()
                                             .get(rule.name()) != RuleKind.LEXER)
               .forEach(rule -> visit(rule,
                                      rule.expression(),
                                      false));

        return List.copyOf(findings);
    }

    /**
     * Walk the constructs the parser generator emits code for. {@code optional} is true only while
     * the whole of {@code expr} may be skipped without its surroundings noticing.
     */
    private void visit(Rule rule, Expression expr, boolean optional) {
        switch (expr) {
            case Expression.CharClass cc -> checkConsumingClass(rule, cc, optional);
            case Expression.Literal lit -> checkLiteral(rule, lit);
            case Expression.And and -> visitPredicate(rule, and.expression());
            case Expression.Not not -> visitPredicate(rule, not.expression());
            case Expression.Sequence seq -> seq.elements().forEach(e -> visit(rule,
                                                                              e,
                                                                              optional && seq.elements().size() == 1));
            case Expression.Choice choice -> visitChoice(rule, choice, optional);
            case Expression.ZeroOrMore zom -> visit(rule, zom.expression(), true);
            case Expression.Optional opt -> visit(rule, opt.expression(), true);
            case Expression.Repetition rep -> visit(rule, rep.expression(), optional || rep.min() == 0);
            case Expression.OneOrMore oom -> visit(rule, oom.expression(), optional);
            case Expression.TokenBoundary tb -> visit(rule, tb.expression(), optional);
            case Expression.Ignore ig -> visit(rule, ig.expression(), optional);
            case Expression.Capture cap -> visit(rule, cap.expression(), optional);
            case Expression.CaptureScope cs -> visit(rule, cs.expression(), optional);
            case Expression.Group grp -> visit(rule, grp.expression(), optional);
            case Expression.Any _, Expression.Reference _, Expression.BackReference _, Expression.Dictionary _, Expression.Cut _ -> {}
        }
    }

    /** A class elided from a parser rule always succeeds, so it hides every alternative after it. */
    private void visitChoice(Rule rule, Expression.Choice choice, boolean optional) {
        var last = choice.alternatives().getLast();
        boolean elided = classification.kinds().get(rule.name()) == RuleKind.PARSER;

        choice.alternatives().forEach(e -> visit(rule, e, optional && (!elided || e == last)));
    }

    /** A purely character-level lookahead is elided by the generator; any other is emitted. */
    private void visitPredicate(Rule rule, Expression inner) {
        if (!ParserGenerator.isCharLevelOnly(inner)) {
            visit(rule, inner, false);

            return;
        }

        classesIn(inner).stream()
                 .filter(cc -> listedChars(cc).intersects(triviaWhitespace))
                 .forEach(cc -> findings.add(Finding.warning("grammar.whitespace-lookahead",
                                                             rule.name(),
                                                             "lookahead over " + show(cc)
                                                            + " in rule '" + rule.name()
                                                            + "' always passes: whitespace is trivia, and a character-level"
                                                            + " lookahead is not evaluated outside a lexer rule")
                                                    .atLine(lineOf(cc))));
    }

    private void checkConsumingClass(Rule rule, Expression.CharClass cc, boolean optional) {
        var matched = matchedChars(cc);
        boolean harmless = optional && !matched.intersects(contentStart);
        // A skippable class that nothing could match reads the same whether or not it is evaluated.
        if (classification.kinds().get(rule.name()) == RuleKind.PARSER && !harmless) {
            findings.add(Finding.error("grammar.char-class-no-op",
                                       rule.name(),
                                       "character class " + show(cc)
                                      + " in parser rule '" + rule.name()
                                      + "' is never evaluated and consumes nothing; move it into a lexer rule, or drop"
                                      + " the %parser pin if the rule has one")
                                .atLine(lineOf(cc)));

            return;
        }

        if (!matched.intersects(contentStart)) {
            var message = "character class " + show(cc)
                        + " in rule '" + rule.name()
                        + "' can never match: the rule is parsed over tokens, and no content token starts with one"
                        + " of these characters (whitespace between tokens is already skipped)";

            findings.add((optional
                          ? Finding.warning("grammar.char-class-never-matches", rule.name(), message)
                          : Finding.error("grammar.char-class-never-matches", rule.name(), message)).atLine(lineOf(cc)));

            return;
        }

        if (matched.intersects(longContentStart)) {
            findings.add(Finding.warning("grammar.char-class-whole-token",
                                         rule.name(),
                                         "character class " + show(cc)
                                        + " in rule '" + rule.name()
                                        + "' tests only a token's first character and consumes the whole token, which"
                                        + " may be longer than one character")
                                .atLine(lineOf(cc)));
        }
    }

    private void checkLiteral(Rule rule, Expression.Literal lit) {
        if (lit.text().isEmpty() || !lit.text().chars().allMatch(triviaWhitespace::get)) {
            return;
        }

        findings.add(Finding.warning("grammar.whitespace-literal",
                                     rule.name(),
                                     "whitespace literal in rule '" + rule.name()
                                    + "' becomes a content token, so this exact text is no longer skipped as"
                                    + " whitespace anywhere in the grammar")
                            .atLine(lit.span().start().line()));
    }

    /** Which first characters can begin a content token, and which can begin one longer than a character. */
    private void profileContentTokens(Dfa dfa) {
        var reaches = statesReachingContent(dfa);

        for (int c = 0; c < SLOTS; c++) {
            int first = step(dfa, Dfa.START_STATE, c);

            if (first == Dfa.NO_TRANSITION || !reaches.get(first)) {
                continue;
            }

            contentStart.set(c);
            for (int next = 0; next < SLOTS; next++) {
                int second = step(dfa, first, next);

                if (second != Dfa.NO_TRANSITION && reaches.get(second)) {
                    longContentStart.set(c);
                    break;
                }
            }
        }
    }

    private static BitSet statesReachingContent(Dfa dfa) {
        var reaches = new BitSet(dfa.stateCount());

        for (int s = 0; s < dfa.stateCount(); s++) {
            int kind = dfa.acceptKind(s);

            reaches.set(s, kind >= TokenArray.FIRST_USER_KIND);
        }

        boolean changed = true;

        while (changed) {
            changed = false;
            for (int s = dfa.stateCount() - 1; s >= 0; s--) {
                if (!reaches.get(s) && anySuccessorIn(dfa, s, reaches)) {
                    reaches.set(s);
                    changed = true;
                }
            }
        }

        return reaches;
    }

    private static boolean anySuccessorIn(Dfa dfa, int state, BitSet targets) {
        for (int c = 0; c < SLOTS; c++) {
            int next = step(dfa, state, c);

            if (next != Dfa.NO_TRANSITION && targets.get(next)) {
                return true;
            }
        }

        return false;
    }

    private static int step(Dfa dfa, int state, int slot) {
        return slot == NON_ASCII
               ? dfa.nonAsciiTransition(state)
               : dfa.transition(state, slot);
    }

    /** Whitespace characters that can begin this grammar's {@code %whitespace}. */
    private static BitSet triviaWhitespace(Grammar grammar) {
        var first = new BitSet(SLOTS);

        grammar.whitespace()
               .onPresent(ws -> addFirstChars(ws,
                                              grammar.ruleMap(),
                                              WidthAnalysis.computeNullable(grammar.ruleMap()),
                                              new HashSet<>(),
                                              first));
        for (int c = 0; c < SLOTS; c++) {
            if (c == NON_ASCII || !Character.isWhitespace(c)) {
                first.clear(c);
            }
        }

        return first;
    }

    private static void addFirstChars(Expression expr,
                                      Map<String, Rule> rules,
                                      Map<String, Boolean> nullable,
                                      Set<String> visited,
                                      BitSet out) {
        switch (expr) {
            case Expression.CharClass cc -> out.or(matchedChars(cc));
            case Expression.Literal lit -> lit.text().chars().limit(1).filter(c -> c < NON_ASCII).forEach(out::set);
            case Expression.Choice choice -> choice.alternatives().forEach(e -> addFirstChars(e,
                                                                                              rules,
                                                                                              nullable,
                                                                                              visited,
                                                                                              out));
            case Expression.Sequence seq -> addFirstCharsOfSequence(seq, rules, nullable, visited, out);
            case Expression.ZeroOrMore zom -> addFirstChars(zom.expression(), rules, nullable, visited, out);
            case Expression.OneOrMore oom -> addFirstChars(oom.expression(), rules, nullable, visited, out);
            case Expression.Optional opt -> addFirstChars(opt.expression(), rules, nullable, visited, out);
            case Expression.Repetition rep -> addFirstChars(rep.expression(), rules, nullable, visited, out);
            case Expression.TokenBoundary tb -> addFirstChars(tb.expression(), rules, nullable, visited, out);
            case Expression.Ignore ig -> addFirstChars(ig.expression(), rules, nullable, visited, out);
            case Expression.Group grp -> addFirstChars(grp.expression(), rules, nullable, visited, out);
            case Expression.Reference ref -> {
                var rule = rules.get(ref.ruleName());

                if (rule != null && visited.add(ref.ruleName())) {
                    addFirstChars(rule.expression(), rules, nullable, visited, out);
                }
            }
            default -> {}
        }
    }

    /** Every element up to and including the first that must consume can contribute the first character. */
    private static void addFirstCharsOfSequence(Expression.Sequence seq,
                                                Map<String, Rule> rules,
                                                Map<String, Boolean> nullable,
                                                Set<String> visited,
                                                BitSet out) {
        for (var element : seq.elements()) {
            if (element instanceof Expression.Not || element instanceof Expression.And) {
                continue;
            }

            addFirstChars(element, rules, nullable, visited, out);
            if (!WidthAnalysis.isNullable(element, nullable)) {
                return;
            }
        }
    }

    private static List<Expression.CharClass> classesIn(Expression expr) {
        var out = new ArrayList<Expression.CharClass>();

        collectClasses(expr, out);

        return out;
    }

    private static void collectClasses(Expression expr, List<Expression.CharClass> out) {
        switch (expr) {
            case Expression.CharClass cc -> out.add(cc);
            case Expression.Sequence seq -> seq.elements().forEach(e -> collectClasses(e, out));
            case Expression.Choice choice -> choice.alternatives().forEach(e -> collectClasses(e, out));
            case Expression.ZeroOrMore zom -> collectClasses(zom.expression(), out);
            case Expression.OneOrMore oom -> collectClasses(oom.expression(), out);
            case Expression.Optional opt -> collectClasses(opt.expression(), out);
            case Expression.Repetition rep -> collectClasses(rep.expression(), out);
            case Expression.And and -> collectClasses(and.expression(), out);
            case Expression.Not not -> collectClasses(not.expression(), out);
            case Expression.TokenBoundary tb -> collectClasses(tb.expression(), out);
            case Expression.Ignore ig -> collectClasses(ig.expression(), out);
            case Expression.Capture cap -> collectClasses(cap.expression(), out);
            case Expression.CaptureScope cs -> collectClasses(cs.expression(), out);
            case Expression.Group grp -> collectClasses(grp.expression(), out);
            default -> {}
        }
    }

    private static BitSet matchedChars(Expression.CharClass cc) {
        var chars = listedChars(cc);

        if (cc.negated()) {
            chars.flip(0, SLOTS);
            // Whatever non-ASCII characters it lists, a negated class still accepts the others.
            chars.set(NON_ASCII);
        }

        return chars;
    }

    /** The characters written between the brackets, ignoring negation. */
    private static BitSet listedChars(Expression.CharClass cc) {
        var chars = new BitSet(SLOTS);

        for (var range : ParserGenerator.charClassRanges(cc.pattern(), cc.caseInsensitive())) {
            chars.set(Math.min(range[0], NON_ASCII), Math.min(range[1], NON_ASCII) + 1);
        }

        return chars;
    }

    private static int lineOf(Expression.CharClass cc) {
        return cc.span()
                 .start()
                 .line();
    }

    private static String show(Expression.CharClass cc) {
        return (cc.negated()
                ? "[^"
                : "[") + cc.pattern()
                           .replace("\t", "\\t")
                           .replace("\r", "\\r")
                           .replace("\n", "\\n")
             + "]";
    }
}
