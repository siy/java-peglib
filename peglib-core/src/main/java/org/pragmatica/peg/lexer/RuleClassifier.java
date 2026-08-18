package org.pragmatica.peg.lexer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.pragmatica.lang.Unit;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.peg.grammar.Expression;
import org.pragmatica.peg.grammar.Grammar;
import org.pragmatica.peg.grammar.Rule;
import org.pragmatica.peg.grammar.analysis.WidthAnalysis;


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
        var kinds = initialLabelling(properties,
                                     grammar.ruleMap(),
                                     WidthAnalysis.computeAlwaysEmpty(grammar.ruleMap()));

        runFixedPointDemotion(properties, kinds);
        var keywordSkip = detectSkipPrefixRules(grammar, properties, kinds);
        var warnings = collectWarnings(rules, properties, kinds);

        return Result.success(new Classification(Collections.unmodifiableMap(kinds),
                                                 Map.copyOf(keywordSkip),
                                                 List.copyOf(warnings)));
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
    private static Map<String, RuleKind> initialLabelling(Map<String, RuleProperties> properties,
                                                          Map<String, Rule> ruleMap,
                                                          Map<String, Boolean> alwaysEmpty) {
        var kinds = new HashMap<String, RuleKind>();

        for (var entry : properties.entrySet()) {
            var p = entry.getValue();

            if (!p.usesOnlyLexicalConstructs) {
                kinds.put(entry.getKey(), RuleKind.PARSER);
            } else if (p.referencesAnyRule && p.hasTerminals) {
                kinds.put(entry.getKey(), RuleKind.PARSER);
            } else if (alwaysEmpty.getOrDefault(entry.getKey(), false)) {
                // A token has to consume something. A rule that can match ONLY the empty string
                // cannot be one — e.g. {@code EmptyStatement <- &';' / !.}, which reads as lexical
                // because it names no other rule, but asserts a position rather than describing a
                // lexeme. Note the test is always-empty, not nullable: {@code Word <- [a-z]*} is
                // nullable and is a legitimate lexer rule, warned about rather than reclassified.
                kinds.put(entry.getKey(), RuleKind.PARSER);
            } else if (p.referencesAnyRule && !spansSingleToken(entry.getKey(), ruleMap)) {
                // A body of nothing but references spanning more than one token is a sequence of
                // tokens, which is parsing: IfNotExists <- IfKW NotKW ExistsKW is three tokens with
                // trivia between them, and fusing them into the DFA yields a lexeme spelled
                // IFNOTEXISTS that no input contains. Composing ONE token out of finer rules is a
                // real and different intent — Identifier <- IdStart IdCont* — and it is spelled by
                // wrapping the body in a token boundary, which is what < > already means.
                kinds.put(entry.getKey(), RuleKind.PARSER);
            } else {
                kinds.put(entry.getKey(), RuleKind.LEXER);
            }
        }

        return kinds;
    }

    /**
     * Whether a reference-only rule describes a single token.
     *
     * <p>True when the body declares itself a token with {@code < >}, or when it spans exactly one
     * token anyway (an alias for one rule, or a choice between shapes). The token boundary is an
     * explicit override and is trusted: an author who writes {@code < IfKW NotKW ExistsKW >} has
     * asked for the fused lexeme.
     */
    private static boolean spansSingleToken(String ruleName, Map<String, Rule> ruleMap) {
        var rule = ruleMap.get(ruleName);

        if (rule == null) {
            return true;
        }

        return declaresTokenBoundary(rule.expression()) || referenceTokenCount(rule.expression()) == 1;
    }

    private static boolean declaresTokenBoundary(Expression expr) {
        return switch (expr) {
            case Expression.TokenBoundary __ -> true;
            case Expression.Group g -> declaresTokenBoundary(g.expression());
            default -> false;
        };
    }

    /** A token span that depends on the input rather than being fixed by the grammar. */
    private static final int VARIABLE_TOKEN_COUNT = -1;

    /**
     * How many tokens a reference-only body spans.
     *
     * <p>Only meaningful where the body names no terminal, which is the one place the caller
     * applies it. A reference counts as one token; lookahead and cut consume nothing; repetition
     * and optionality make the span depend on the input and so are never a fixed single token.
     */
    private static int referenceTokenCount(Expression expr) {
        return switch (expr) {
            case Expression.Reference __ -> 1;
            case Expression.And __ -> 0;
            case Expression.Not __ -> 0;
            case Expression.Cut __ -> 0;
            case Expression.Sequence seq -> sumTokenCounts(seq.elements());
            case Expression.Choice ch -> maxTokenCount(ch.alternatives());
            case Expression.Group g -> referenceTokenCount(g.expression());
            case Expression.TokenBoundary tb -> referenceTokenCount(tb.expression());
            case Expression.Capture cap -> referenceTokenCount(cap.expression());
            case Expression.CaptureScope cs -> referenceTokenCount(cs.expression());
            case Expression.Ignore ig -> referenceTokenCount(ig.expression());
            case Expression.ZeroOrMore __ -> VARIABLE_TOKEN_COUNT;
            case Expression.OneOrMore __ -> VARIABLE_TOKEN_COUNT;
            case Expression.Optional __ -> VARIABLE_TOKEN_COUNT;
            case Expression.Repetition __ -> VARIABLE_TOKEN_COUNT;
            // Terminals are characters within a token rather than tokens, so a body containing one
            // is not reference-only and does not reach here.
            case Expression.Literal __ -> VARIABLE_TOKEN_COUNT;
            case Expression.CharClass __ -> VARIABLE_TOKEN_COUNT;
            case Expression.Any __ -> VARIABLE_TOKEN_COUNT;
            case Expression.BackReference __ -> VARIABLE_TOKEN_COUNT;
            case Expression.Dictionary __ -> VARIABLE_TOKEN_COUNT;
        };
    }

    private static int sumTokenCounts(List<Expression> elements) {
        int total = 0;

        for (var element : elements) {
            int count = referenceTokenCount(element);

            if (count == VARIABLE_TOKEN_COUNT) {
                return VARIABLE_TOKEN_COUNT;
            }

            total += count;
        }

        return total;
    }

    private static int maxTokenCount(List<Expression> alternatives) {
        int max = 0;

        for (var alternative : alternatives) {
            int count = referenceTokenCount(alternative);

            if (count == VARIABLE_TOKEN_COUNT) {
                return VARIABLE_TOKEN_COUNT;
            }

            max = Math.max(max, count);
        }

        return max;
    }

    /**
     * Iteratively demote candidate-LEXER rules that transitively reference a
     * non-LEXER rule. Build a reverse-dependency map (referenced → referencer)
     * and process a worklist of newly-demoted rules; re-evaluate every rule
     * that depends on a demoted rule. Terminates because demotion is monotonic
     * (LEXER → PARSER/MIXED never reverses).
     */
    private static void runFixedPointDemotion(Map<String, RuleProperties> properties, Map<String, RuleKind> kinds) {
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
                reverse.computeIfAbsent(referenced, k -> new HashSet<>()).add(referencer);
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
            detectSkipPrefix(rule.expression(), ruleMap).onPresent(info -> recordSkipPrefix(rule,
                                                                                            info,
                                                                                            ruleMap,
                                                                                            kinds,
                                                                                            properties,
                                                                                            result));
        }

        return result;
    }

    private static void recordSkipPrefix(Rule rule,
                                         KeywordSkipInfo info,
                                         Map<String, Rule> ruleMap,
                                         Map<String, RuleKind> kinds,
                                         Map<String, RuleProperties> properties,
                                         Map<String, KeywordSkipInfo> result) {
        var body = resolveSkipBody(info.bodyExpression(), ruleMap, kinds);

        if (body.isEmpty()) {
            return;
        }

        var resolved = body.unwrap();
        // Force LEXER classification so DFA picks it up.
        kinds.put(rule.name(), RuleKind.LEXER);
        result.put(rule.name(), new KeywordSkipInfo(info.keywordRuleName(), resolved));
        // Update properties so downstream consumers see the body-only shape.
        properties.put(rule.name(), analyse(resolved));
    }

    /**
     * The DFA builder compiles a skip-prefix body on its own, so the body has to be pure-lexical.
     * A body that names other LEXER rules qualifies once those references are substituted — which
     * is what lets a rule carry both a keyword guard and named alternatives:
     *
     * <pre>{@code ColId <- !ReservedKeyword (QuotedIdent / UnquotedIdent)}</pre>
     *
     * Before substitution the guard forced the alternatives to be hand-inlined at every use site,
     * because the guard needs a named rule and a rule naming the alternatives was rejected here.
     *
     * <p>Returns {@link Option#none()} when the body cannot be made pure-lexical, leaving the rule
     * unregistered exactly as before.
     */
    private static Option<Expression> resolveSkipBody(Expression body,
                                                      Map<String, Rule> ruleMap,
                                                      Map<String, RuleKind> kinds) {
        var props = analyse(body);

        if (!props.usesOnlyLexicalConstructs()) {
            return Option.none();
        }

        if (!props.referencesAnyRule()) {
            return Option.some(body);
        }

        var expanded = inlineLexerReferences(body, ruleMap, kinds);

        if (expanded.isEmpty()) {
            return Option.none();
        }

        var expandedProps = analyse(expanded.unwrap());

        return expandedProps.usesOnlyLexicalConstructs() && !expandedProps.referencesAnyRule()
               ? expanded
               : Option.none();
    }

    /**
     * Substitute every {@link Expression.Reference} to a LEXER-classified rule with that rule's
     * own expression, recursively.
     *
     * <p>A DFA has no call stack, so {@link org.pragmatica.peg.lexer.DfaBuilder} cannot compile a
     * rule reference directly. Before 0.7.2 that made any reference fatal to lexical compilation,
     * which forced grammar authors to hand-inline shared lexical shapes — and made the
     * combination "guard plus alternatives" inexpressible, because the guard requires a named
     * rule and the alternatives then cannot be named. Reference substitution is sound precisely
     * because the referenced rules are themselves regular: inlining a non-recursive reference
     * yields the same language.
     *
     * <p>Returns {@link Option#none()} when substitution would not terminate (a reference cycle)
     * or would change classification (a reference to a non-LEXER rule). Callers leave the
     * expression untouched in that case, so the existing "cannot compile" path reports it.
     */
    public static Option<Expression> inlineLexerReferences(Expression expr,
                                                           Map<String, Rule> ruleMap,
                                                           Map<String, RuleKind> kinds) {
        return substitute(expr, ruleMap, kinds, new HashSet<>());
    }

    private static Option<Expression> substitute(Expression expr,
                                                 Map<String, Rule> ruleMap,
                                                 Map<String, RuleKind> kinds,
                                                 Set<String> onPath) {
        return switch (expr) {
            case Expression.Reference ref -> substituteReference(ref, ruleMap, kinds, onPath);
            case Expression.Sequence seq -> substituteList(seq.elements(), ruleMap, kinds, onPath).map(list -> new Expression.Sequence(seq.span(),
                                                                                                                                       list));
            case Expression.Choice ch -> substituteList(ch.alternatives(), ruleMap, kinds, onPath).map(list -> new Expression.Choice(ch.span(),
                                                                                                                                     list));
            case Expression.ZeroOrMore z -> substitute(z.expression(), ruleMap, kinds, onPath).map(inner -> new Expression.ZeroOrMore(z.span(),
                                                                                                                                      inner));
            case Expression.OneOrMore o -> substitute(o.expression(), ruleMap, kinds, onPath).map(inner -> new Expression.OneOrMore(o.span(),
                                                                                                                                    inner));
            case Expression.Optional opt -> substitute(opt.expression(), ruleMap, kinds, onPath).map(inner -> new Expression.Optional(opt.span(),
                                                                                                                                      inner));
            case Expression.Repetition rep -> substitute(rep.expression(), ruleMap, kinds, onPath).map(inner -> new Expression.Repetition(rep.span(),
                                                                                                                                          inner,
                                                                                                                                          rep.min(),
                                                                                                                                          rep.max()));
            case Expression.And and -> substitute(and.expression(), ruleMap, kinds, onPath).map(inner -> new Expression.And(and.span(),
                                                                                                                            inner));
            case Expression.Not not -> substitute(not.expression(), ruleMap, kinds, onPath).map(inner -> new Expression.Not(not.span(),
                                                                                                                            inner));
            case Expression.TokenBoundary tb -> substitute(tb.expression(), ruleMap, kinds, onPath).map(inner -> new Expression.TokenBoundary(tb.span(),
                                                                                                                                              inner));
            case Expression.Ignore ig -> substitute(ig.expression(), ruleMap, kinds, onPath).map(inner -> new Expression.Ignore(ig.span(),
                                                                                                                                inner));
            case Expression.Capture cap -> substitute(cap.expression(), ruleMap, kinds, onPath).map(inner -> new Expression.Capture(cap.span(),
                                                                                                                                    cap.name(),
                                                                                                                                    inner));
            case Expression.CaptureScope cs -> substitute(cs.expression(), ruleMap, kinds, onPath).map(inner -> new Expression.CaptureScope(cs.span(),
                                                                                                                                            inner));
            case Expression.Group g -> substitute(g.expression(), ruleMap, kinds, onPath).map(inner -> new Expression.Group(g.span(),
                                                                                                                            inner));
            // Terminals and categorical non-lexicals substitute to themselves. BackReference and
            // Dictionary are not regular, but they are rejected by the DFA builder on their own
            // merits, so passing them through keeps that diagnosis where it already lives.
            case Expression.Literal __ -> Option.some(expr);
            case Expression.CharClass __ -> Option.some(expr);
            case Expression.Any __ -> Option.some(expr);
            case Expression.Cut __ -> Option.some(expr);
            case Expression.BackReference __ -> Option.some(expr);
            case Expression.Dictionary __ -> Option.some(expr);
        };
    }

    private static Option<Expression> substituteReference(Expression.Reference ref,
                                                          Map<String, Rule> ruleMap,
                                                          Map<String, RuleKind> kinds,
                                                          Set<String> onPath) {
        var name = ref.ruleName();

        if (onPath.contains(name)) {
            // Recursive lexical rule — not regular, cannot be flattened into a DFA.
            return Option.none();
        }

        if (kinds.get(name) != RuleKind.LEXER) {
            return Option.none();
        }

        var target = ruleMap.get(name);

        if (target == null) {
            return Option.none();
        }

        onPath.add(name);
        var expanded = substitute(target.expression(), ruleMap, kinds, onPath);

        onPath.remove(name);
        // Wrap in a Group so that substituting into a Sequence cannot re-associate
        // the referenced rule's own alternation with its neighbours.
        return expanded.map(inner -> new Expression.Group(ref.span(), inner));
    }

    private static Option<List<Expression>> substituteList(List<Expression> items,
                                                           Map<String, Rule> ruleMap,
                                                           Map<String, RuleKind> kinds,
                                                           Set<String> onPath) {
        var out = new ArrayList<Expression>(items.size());

        for (var item : items) {
            var substituted = substitute(item, ruleMap, kinds, onPath);

            if (substituted.isEmpty()) {
                return Option.none();
            }

            out.add(substituted.unwrap());
        }

        return Option.some(List.copyOf(out));
    }

    private static Option<KeywordSkipInfo> detectSkipPrefix(Expression expr, Map<String, Rule> ruleMap) {
        var unwrapped = unwrapWrappers(expr);

        if (! (unwrapped instanceof Expression.Sequence seq)) {
            return Option.none();
        }

        var elements = seq.elements();

        if (elements.size() < 2) {
            return Option.none();
        }

        var head = unwrapWrappers(elements.get(0));

        if (! (head instanceof Expression.Not not)) {
            return Option.none();
        }

        var notInner = unwrapWrappers(not.expression());

        if (! (notInner instanceof Expression.Reference ref)) {
            return Option.none();
        }

        var referenced = ruleMap.get(ref.ruleName());

        if (referenced == null) {
            return Option.none();
        }

        if (!isLiteralSetRule(referenced.expression())) {
            return Option.none();
        }

        var rest = elements.subList(1, elements.size());
        Expression body = rest.size() == 1
                          ? rest.get(0)
                          : new Expression.Sequence(seq.span(), List.copyOf(rest));

        return Option.some(new KeywordSkipInfo(ref.ruleName(), body));
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
            if (seq.elements().isEmpty()) {
                return false;
            }

            choiceCandidate = unwrapWrappers(seq.elements().get(0));
        }

        if (! (choiceCandidate instanceof Expression.Choice choice)) {
            return false;
        }

        if (choice.alternatives().isEmpty()) {
            return false;
        }

        for (var alt : choice.alternatives()) {
            if (extractLeadingLiteral(alt).isEmpty()) {
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
            if (seq.elements().isEmpty()) {
                return List.of();
            }

            choiceCandidate = unwrapWrappers(seq.elements().get(0));
        }

        if (! (choiceCandidate instanceof Expression.Choice choice)) {
            return List.of();
        }

        var out = new ArrayList<String>(choice.alternatives().size());

        for (var alt : choice.alternatives()) {
            var litOpt = extractLeadingLiteral(alt);

            if (litOpt.isEmpty()) {
                return List.of();
            }

            out.add(litOpt.unwrap());
        }

        return List.copyOf(out);
    }

    private static Option<String> extractLeadingLiteral(Expression alt) {
        var unwrapped = unwrapWrappers(alt);

        if (unwrapped instanceof Expression.Literal lit) {
            return Option.some(lit.text());
        }

        if (unwrapped instanceof Expression.Sequence seq && !seq.elements().isEmpty()) {
            var first = unwrapWrappers(seq.elements().get(0));

            if (first instanceof Expression.Literal lit) {
                return Option.some(lit.text());
            }
        }

        return Option.none();
    }

    private record RuleProperties(boolean referencesAnyRule,
                                  boolean usesOnlyLexicalConstructs,
                                  boolean usesCharLevelConstructs,
                                  boolean hasTerminals,
                                  Set<String> referencedRules) {}

    private static RuleProperties analyse(Expression expr) {
        var visitor = new PropertyVisitor();

        visitor.walk(expr);

        return new RuleProperties(visitor.referencesAnyRule,
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

        Result<Unit> walk(Expression expr) {
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
                case Expression.Sequence seq -> seq.elements().forEach(this::walk);
                case Expression.Choice ch -> ch.alternatives().forEach(this::walk);
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

            return Result.unitResult();
        }
    }
}
