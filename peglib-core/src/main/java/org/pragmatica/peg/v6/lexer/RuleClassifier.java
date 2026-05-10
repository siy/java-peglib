package org.pragmatica.peg.v6.lexer;

import org.pragmatica.lang.Result;
import org.pragmatica.peg.grammar.Expression;
import org.pragmatica.peg.grammar.Grammar;
import org.pragmatica.peg.grammar.Rule;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Phase A.1 — classify every rule in a {@link Grammar} as {@link RuleKind#LEXER},
 * {@link RuleKind#PARSER}, or {@link RuleKind#MIXED} per spec §3.2.
 *
 * <h2>Algorithm</h2>
 *
 * <ol>
 *   <li>For each rule walk its expression tree and record per-rule properties:
 *       does it reference any rule? does it use only lexical constructs? does
 *       it use char-level constructs ({@code .}, {@code [..]}, predicates over
 *       char-level inner)? which other rules does it reference?</li>
 *   <li>Initial labelling: a rule with no references and only lexical constructs
 *       is a candidate {@link RuleKind#LEXER}; everything else is a tentative
 *       {@link RuleKind#PARSER}.</li>
 *   <li>Fixed-point demotion: a candidate LEXER rule that references (transitively
 *       via {@link Expression.Reference}) any non-LEXER rule is demoted to
 *       PARSER (or MIXED if it also uses char-level constructs). Iterate until
 *       stable. Worklist algorithm: each demotion enqueues rules that referenced
 *       the demoted rule.</li>
 *   <li>MIXED detection: a final-PARSER rule that ALSO uses char-level constructs
 *       produces a {@link Warning}.</li>
 * </ol>
 *
 * <h2>Lexical construct policy</h2>
 *
 * <p>The following node kinds are lexical: {@link Expression.Literal},
 * {@link Expression.CharClass}, {@link Expression.Any}, {@link Expression.Sequence},
 * {@link Expression.Choice}, {@link Expression.ZeroOrMore},
 * {@link Expression.OneOrMore}, {@link Expression.Optional},
 * {@link Expression.Repetition}, {@link Expression.And}, {@link Expression.Not},
 * {@link Expression.TokenBoundary}, {@link Expression.Ignore},
 * {@link Expression.Capture}, {@link Expression.CaptureScope},
 * {@link Expression.Group}, {@link Expression.Cut}.
 *
 * <p>Non-lexical: {@link Expression.Reference}, {@link Expression.BackReference},
 * {@link Expression.Dictionary}.
 */
public final class RuleClassifier {
    private RuleClassifier() {}

    public record Warning(String ruleName, String reason) {}

    /**
     * Phase B.0 — describes a rule that matches the skip-prefix pattern
     * {@code !LiteralSetRule <body>}. The classifier extracts the {@code body}
     * (everything after the negative lookahead head) so the DFA path can compile
     * the body alone — bypassing the unsupported {@code Not} node — while the
     * lexer engine performs post-match keyword resolution by matched text.
     *
     * @param keywordRuleName name of the rule referenced by the leading {@code !}
     * @param bodyExpression  rest of the sequence after the {@code !Reference} head
     */
    public record KeywordSkipInfo(String keywordRuleName, Expression bodyExpression) {}

    public record Classification(Map<String, RuleKind> kinds,
                                 Map<String, KeywordSkipInfo> keywordSkip,
                                 List<Warning> warnings) {}

    /**
     * Classify every rule in {@code grammar}. Always succeeds: the result wraps
     * a complete kind map (including any rule whose body is malformed-but-typed)
     * and zero or more warnings.
     */
    public static Result<Classification> classify(Grammar grammar) {
        var rules = grammar.rules();
        if (rules.isEmpty()) {
            return Result.success(new Classification(Map.of(), Map.of(), List.of()));
        }
        var properties = collectProperties(rules);
        var kinds = initialLabelling(properties);
        runFixedPointDemotion(properties, kinds);
        var keywordSkip = detectSkipPrefixRules(grammar, properties, kinds);
        var warnings = collectWarnings(rules, properties, kinds);
        return Result.success(new Classification(
        Collections.unmodifiableMap(kinds), Map.copyOf(keywordSkip), List.copyOf(warnings)));
    }

    private static Map<String, RuleProperties> collectProperties(List<Rule> rules) {
        var map = new LinkedHashMap<String, RuleProperties>();
        for (var rule : rules) {
            map.put(rule.name(), analyse(rule.expression()));
        }
        return map;
    }

    /**
     * Initial labelling per spec §3.2 with the structural distinction:
     * <ul>
     *   <li>Categorical non-lexicals ({@link Expression.BackReference},
     *       {@link Expression.Dictionary}) → PARSER.</li>
     *   <li>Has terminals AND refs (combines token producers with literal text in body) →
     *       tentative PARSER. Pattern: {@code Sum <- Number '+' Number}.</li>
     *   <li>Has terminals AND no refs → LEXER. Pattern: {@code Number <- [0-9]+}.</li>
     *   <li>Has refs AND no terminals → candidate LEXER pending fixed-point demotion.
     *       Pattern: {@code Identifier <- IdStart IdCont*} stays LEXER iff all transitively-
     *       referenced rules are LEXER. Otherwise demoted to PARSER.</li>
     *   <li>Empty/combinator-only body → LEXER (degenerate case).</li>
     * </ul>
     */
    private static Map<String, RuleKind> initialLabelling(Map<String, RuleProperties> properties) {
        var kinds = new HashMap<String, RuleKind>();
        for (var entry : properties.entrySet()) {
            var p = entry.getValue();
            if (!p.usesOnlyLexicalConstructs) {
                kinds.put(entry.getKey(), RuleKind.PARSER);
            }else if (p.referencesAnyRule && p.hasTerminals) {
                kinds.put(entry.getKey(), RuleKind.PARSER);
            }else {
                kinds.put(entry.getKey(), RuleKind.LEXER);
            }
        }
        return kinds;
    }

    /**
     * Iteratively demote candidate-LEXER rules that transitively reference a
     * non-LEXER rule. Build a reverse-dependency map (referenced → referencer)
     * and process a worklist of newly-demoted rules; re-evaluate every rule
     * that depends on a demoted rule. Terminates because demotion is monotonic
     * (LEXER → PARSER/MIXED never reverses).
     */
    private static void runFixedPointDemotion(Map<String, RuleProperties> properties,
                                              Map<String, RuleKind> kinds) {
        var reverseDeps = buildReverseDependencies(properties);
        var worklist = new ArrayList<String>();
        for (var entry : kinds.entrySet()) {
            if (entry.getValue() != RuleKind.LEXER) {
                worklist.add(entry.getKey());
            }
        }
        while (!worklist.isEmpty()) {
            var demoted = worklist.removeLast();
            var dependents = reverseDeps.getOrDefault(demoted, Set.of());
            for (var dep : dependents) {
                if (kinds.get(dep) == RuleKind.LEXER) {
                    kinds.put(dep, RuleKind.PARSER);
                    worklist.add(dep);
                }
            }
        }
    }

    private static Map<String, Set<String>> buildReverseDependencies(Map<String, RuleProperties> properties) {
        var reverse = new HashMap<String, Set<String>>();
        for (var entry : properties.entrySet()) {
            var referencer = entry.getKey();
            for (var referenced : entry.getValue().referencedRules) {
                reverse.computeIfAbsent(referenced,
                                        k -> new HashSet<>())
                       .add(referencer);
            }
        }
        return reverse;
    }

    private static List<Warning> collectWarnings(List<Rule> rules,
                                                 Map<String, RuleProperties> properties,
                                                 Map<String, RuleKind> kinds) {
        var warnings = new ArrayList<Warning>();
        for (var rule : rules) {
            var name = rule.name();
            var p = properties.get(name);
            if (kinds.get(name) == RuleKind.PARSER && p.usesCharLevelConstructs && p.referencesAnyRule) {
                kinds.put(name, RuleKind.MIXED);
                warnings.add(new Warning(name,
                                         "rule combines rule references with character-level constructs (., [..], or char-level &/!); "
                                         + "consider splitting into a lexer rule and a parser rule"));
            }
        }
        return warnings;
    }

    /**
     * Phase B.0 skip-prefix detection. For each LEXER rule whose body has the
     * shape {@code !RefName <rest>} where {@code RefName} resolves to a
     * literal-set rule (a {@link Expression.Choice} of {@link Expression.Literal}s,
     * optionally each followed by trailing guard expressions, optionally with a
     * trailing top-level guard), record the {@code rest} expression so the DFA
     * builder can compile the body alone. The lexer engine then performs
     * post-match keyword resolution by matched text.
     *
     * <p>Rules that don't match the pattern are unaffected. The classifier still
     * may demote them on its own.
     */
    private static Map<String, KeywordSkipInfo> detectSkipPrefixRules(Grammar grammar,
                                                                      Map<String, RuleProperties> properties,
                                                                      Map<String, RuleKind> kinds) {
        var result = new LinkedHashMap<String, KeywordSkipInfo>();
        var ruleMap = grammar.ruleMap();
        for (var rule : grammar.rules()) {
            var info = detectSkipPrefix(rule.expression(), ruleMap);
            if (info.isEmpty()) {
                continue;
            }
            var bodyProps = analyse(info.get()
                                        .bodyExpression());
            // Body must itself be pure-lexical (no rule references, no back-references, no dictionaries).
            if (!bodyProps.usesOnlyLexicalConstructs() || bodyProps.referencesAnyRule()) {
                continue;
            }
            // Force LEXER classification so DFA picks it up.
            kinds.put(rule.name(), RuleKind.LEXER);
            result.put(rule.name(), info.get());
            // Update properties so downstream consumers see the body-only shape.
            properties.put(rule.name(), bodyProps);
        }
        return result;
    }

    private static Optional<KeywordSkipInfo> detectSkipPrefix(Expression expr, Map<String, Rule> ruleMap) {
        var unwrapped = unwrapWrappers(expr);
        if (! (unwrapped instanceof Expression.Sequence seq)) {
            return Optional.empty();
        }
        var elements = seq.elements();
        if (elements.size() < 2) {
            return Optional.empty();
        }
        var head = unwrapWrappers(elements.get(0));
        if (! (head instanceof Expression.Not not)) {
            return Optional.empty();
        }
        var notInner = unwrapWrappers(not.expression());
        if (! (notInner instanceof Expression.Reference ref)) {
            return Optional.empty();
        }
        var referenced = ruleMap.get(ref.ruleName());
        if (referenced == null) {
            return Optional.empty();
        }
        if (!isLiteralSetRule(referenced.expression())) {
            return Optional.empty();
        }
        var rest = elements.subList(1, elements.size());
        Expression body = rest.size() == 1
                          ? rest.get(0)
                          : new Expression.Sequence(seq.span(), List.copyOf(rest));
        return Optional.of(new KeywordSkipInfo(ref.ruleName(), body));
    }

    /**
     * Strip {@link Expression.Group}, {@link Expression.TokenBoundary}, and
     * {@link Expression.Capture} wrappers — they don't affect token matching.
     */
    private static Expression unwrapWrappers(Expression expr) {
        Expression cur = expr;
        while (true) {
            switch (cur) {
                case Expression.Group g -> cur = g.expression();
                case Expression.TokenBoundary tb -> cur = tb.expression();
                case Expression.Capture cap -> cur = cap.expression();
                case Expression.CaptureScope cs -> cur = cs.expression();
                default -> {
                    return cur;
                }
            }
        }
    }

    /**
     * Return true if {@code expr} has the shape of a literal-set rule:
     * {@link Expression.Choice} of alternatives, each of which is either a
     * {@link Expression.Literal} or a {@link Expression.Sequence} whose first
     * element is a {@link Expression.Literal}. The choice itself may be wrapped
     * in a top-level {@link Expression.Sequence} with one or more trailing
     * guard expressions (which are ignored — only the leading literals matter
     * for keyword resolution).
     */
    static boolean isLiteralSetRule(Expression expr) {
        var unwrapped = unwrapWrappers(expr);
        Expression choiceCandidate = unwrapped;
        if (unwrapped instanceof Expression.Sequence seq) {
            if (seq.elements()
                   .isEmpty()) {
                return false;
            }
            choiceCandidate = unwrapWrappers(seq.elements()
                                                .get(0));
        }
        if (! (choiceCandidate instanceof Expression.Choice choice)) {
            return false;
        }
        if (choice.alternatives()
                  .isEmpty()) {
            return false;
        }
        for (var alt : choice.alternatives()) {
            if (extractLeadingLiteral(alt) == null) {
                return false;
            }
        }
        return true;
    }

    /**
     * Extract every leading literal text from a literal-set rule body. Mirrors
     * {@link #isLiteralSetRule(Expression)} but returns the actual texts rather
     * than a boolean. Returns an empty list if the shape doesn't match.
     */
    static List<String> extractLiteralSet(Expression expr) {
        var unwrapped = unwrapWrappers(expr);
        Expression choiceCandidate = unwrapped;
        if (unwrapped instanceof Expression.Sequence seq) {
            if (seq.elements()
                   .isEmpty()) {
                return List.of();
            }
            choiceCandidate = unwrapWrappers(seq.elements()
                                                .get(0));
        }
        if (! (choiceCandidate instanceof Expression.Choice choice)) {
            return List.of();
        }
        var out = new ArrayList<String>(choice.alternatives()
                                              .size());
        for (var alt : choice.alternatives()) {
            var lit = extractLeadingLiteral(alt);
            if (lit == null) {
                return List.of();
            }
            out.add(lit);
        }
        return List.copyOf(out);
    }

    private static String extractLeadingLiteral(Expression alt) {
        var unwrapped = unwrapWrappers(alt);
        if (unwrapped instanceof Expression.Literal lit) {
            return lit.text();
        }
        if (unwrapped instanceof Expression.Sequence seq && !seq.elements()
                                                                .isEmpty()) {
            var first = unwrapWrappers(seq.elements()
                                          .get(0));
            if (first instanceof Expression.Literal lit) {
                return lit.text();
            }
        }
        return null;
    }

    private record RuleProperties(boolean referencesAnyRule,
                                  boolean usesOnlyLexicalConstructs,
                                  boolean usesCharLevelConstructs,
                                  boolean hasTerminals,
                                  Set<String> referencedRules) {}

    private static RuleProperties analyse(Expression expr) {
        var visitor = new PropertyVisitor();
        visitor.walk(expr);
        return new RuleProperties(
        visitor.referencesAnyRule,
        visitor.usesOnlyLexicalConstructs,
        visitor.usesCharLevelConstructs,
        visitor.hasTerminals,
        Set.copyOf(visitor.referencedRules));
    }

    private static final class PropertyVisitor {
        boolean referencesAnyRule = false;
        boolean usesOnlyLexicalConstructs = true;
        boolean usesCharLevelConstructs = false;
        boolean hasTerminals = false;
        final Set<String> referencedRules = new HashSet<>();

        void walk(Expression expr) {
            switch (expr) {
                case Expression.Literal __ -> hasTerminals = true;
                case Expression.CharClass __ -> {
                    hasTerminals = true;
                    usesCharLevelConstructs = true;
                }
                case Expression.Any __ -> {
                    hasTerminals = true;
                    usesCharLevelConstructs = true;
                }
                case Expression.Reference ref -> {
                    // References don't disqualify a rule from LEXER candidacy by themselves;
                    // the fixed-point demotion phase decides based on the referenced rule's kind.
                    referencesAnyRule = true;
                    referencedRules.add(ref.ruleName());
                }
                case Expression.BackReference __ -> usesOnlyLexicalConstructs = false;
                case Expression.Dictionary __ -> usesOnlyLexicalConstructs = false;
                case Expression.Sequence seq -> seq.elements()
                                                   .forEach(this::walk);
                case Expression.Choice ch -> ch.alternatives()
                                               .forEach(this::walk);
                case Expression.ZeroOrMore z -> walk(z.expression());
                case Expression.OneOrMore o -> walk(o.expression());
                case Expression.Optional o -> walk(o.expression());
                case Expression.Repetition r -> walk(r.expression());
                case Expression.And a -> walk(a.expression());
                case Expression.Not n -> walk(n.expression());
                case Expression.TokenBoundary tb -> walk(tb.expression());
                case Expression.Ignore ig -> walk(ig.expression());
                case Expression.Capture cap -> walk(cap.expression());
                case Expression.CaptureScope cs -> walk(cs.expression());
                case Expression.Group g -> walk(g.expression());
                case Expression.Cut __ -> {}
            }
        }
    }
}
