package org.pragmatica.peg.generator;

import org.pragmatica.peg.grammar.Expression;
import org.pragmatica.peg.grammar.Grammar;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Phase 1.8 (selective packrat auto-skip): static analysis over a {@link Grammar} that
 * derives the set of rule names whose generated CST methods should bypass the packrat
 * cache. Skipping the cache for these rules eliminates two HashMap operations and one
 * {@code Long} autobox per rule entry — measurable wins on grammars dominated by many
 * cheap leaf-like rules.
 *
 * <h2>Skip heuristic</h2>
 *
 * <p>A rule is added to the skip-set when at least one of these holds and none of the
 * exclusions trip:
 *
 * <ol>
 *   <li><b>Leaf-like</b>: body is (transitively through transparent wrappers / single-element
 *       sequences) a {@link Expression.Literal}, {@link Expression.CharClass}, or
 *       {@link Expression.Any}. Re-parsing such a rule from the same position is essentially
 *       free, so caching costs more than it saves.</li>
 *   <li><b>Single-call-site, no quantifier</b>: the rule is referenced from at most one position
 *       in the entire grammar (excluding self-references) AND the body contains no
 *       {@link Expression.ZeroOrMore} / {@link Expression.OneOrMore} / {@link Expression.Repetition}.
 *       Re-entry is impossible by construction and there's no quantifier-driven repeat-work
 *       that could benefit from memoization.</li>
 * </ol>
 *
 * <h2>Hard exclusion</h2>
 *
 * <p>Left-recursive rules — present in {@link Grammar#leftRecursiveRules()} — are NEVER skipped
 * regardless of body shape. Warth-style seed-and-grow requires the cache to persist seeds across
 * loop iterations; bypassing the cache would break LR correctness.
 *
 * <h2>Rule selection rationale</h2>
 *
 * <p>Multi-alternative {@link Expression.Choice} rules and rules with quantifiers exhibit the
 * highest backtracking and re-entry probability — exactly where memoization pays off. We keep
 * those caching. Leaf rules called from many sites still cost less to re-parse than to cache,
 * because the work avoided is a literal/char-class match measured in nanoseconds.
 */
public final class PackratAnalyzer {
    private PackratAnalyzer() {}

    /**
     * Compute the set of rule names whose generated CST methods should bypass the packrat
     * cache. The returned set excludes left-recursive rules (mandatory) and is built from a
     * combination of leaf-like detection and single-call-site analysis.
     *
     * <p>The result is an immutable copy safe to pass through {@link ParserConfig#packratSkipRules()}.
     */
    public static Set<String> autoSkipPackratRules(Grammar grammar) {
        var lrRules = grammar.leftRecursiveRules();
        var callSites = countCallSites(grammar);
        var skip = new HashSet<String>();
        for (var rule : grammar.rules()) {
            var name = rule.name();
            if (lrRules.contains(name)) {
                continue;
            }
            if (isLeafLike(rule.expression())) {
                skip.add(name);
                continue;
            }
            int sites = callSites.getOrDefault(name, 0);
            if (sites <= 1 && !hasQuantifier(rule.expression())) {
                skip.add(name);
            }
        }
        return Set.copyOf(skip);
    }

    /**
     * True when the expression is — through transparent wrappers and single-element sequences —
     * a single {@link Expression.Literal}, {@link Expression.CharClass}, or {@link Expression.Any}.
     * A simple {@link Expression.Reference} also qualifies because it dispatches to a single rule
     * call with no inherent backtracking benefit at this level.
     */
    static boolean isLeafLike(Expression expr) {
        return switch (expr) {
            case Expression.Literal __ -> true;
            case Expression.CharClass __ -> true;
            case Expression.Any __ -> true;
            case Expression.Reference __ -> true;
            case Expression.TokenBoundary tb -> isLeafLike(tb.expression());
            case Expression.Capture c -> isLeafLike(c.expression());
            case Expression.CaptureScope cs -> isLeafLike(cs.expression());
            case Expression.Group g -> isLeafLike(g.expression());
            case Expression.Ignore i -> isLeafLike(i.expression());
            case Expression.Sequence seq -> seq.elements()
                                               .size() == 1 && isLeafLike(seq.elements()
                                                                             .getFirst());
            default -> false;
        };
    }

    /**
     * True when the expression sub-tree contains any {@link Expression.ZeroOrMore},
     * {@link Expression.OneOrMore}, or {@link Expression.Repetition}. Lookahead predicates
     * ({@link Expression.And}, {@link Expression.Not}) are NOT counted — they don't
     * accumulate cacheable work (they don't consume input).
     */
    static boolean hasQuantifier(Expression expr) {
        return switch (expr) {
            case Expression.ZeroOrMore __ -> true;
            case Expression.OneOrMore __ -> true;
            case Expression.Repetition __ -> true;
            case Expression.Sequence seq -> {
                for (var el : seq.elements()) {
                    if (hasQuantifier(el)) {
                        yield true;
                    }
                }
                yield false;
            }
            case Expression.Choice ch -> {
                for (var alt : ch.alternatives()) {
                    if (hasQuantifier(alt)) {
                        yield true;
                    }
                }
                yield false;
            }
            case Expression.Optional o -> hasQuantifier(o.expression());
            case Expression.And a -> hasQuantifier(a.expression());
            case Expression.Not n -> hasQuantifier(n.expression());
            case Expression.TokenBoundary tb -> hasQuantifier(tb.expression());
            case Expression.Ignore ig -> hasQuantifier(ig.expression());
            case Expression.Capture cap -> hasQuantifier(cap.expression());
            case Expression.CaptureScope cs -> hasQuantifier(cs.expression());
            case Expression.Group g -> hasQuantifier(g.expression());
            default -> false;
        };
    }

    /**
     * Count, for every rule name in the grammar, the number of {@link Expression.Reference}
     * sites pointing to it across ALL rule bodies. Self-references (a rule referencing itself)
     * are excluded — a recursive rule called from one external site still has re-entry of one
     * external entry per parse from the caller's perspective, so we count only external sites.
     */
    static Map<String, Integer> countCallSites(Grammar grammar) {
        var counts = new HashMap<String, Integer>();
        for (var rule : grammar.rules()) {
            countCallSitesIn(rule.expression(), rule.name(), counts);
        }
        return counts;
    }

    private static void countCallSitesIn(Expression expr, String enclosingRule, Map<String, Integer> counts) {
        switch (expr) {
            case Expression.Reference ref -> {
                var target = ref.ruleName();
                if (!target.equals(enclosingRule)) {
                    counts.merge(target, 1, Integer::sum);
                }
            }
            case Expression.Sequence seq -> seq.elements()
                                               .forEach(e -> countCallSitesIn(e, enclosingRule, counts));
            case Expression.Choice ch -> ch.alternatives()
                                           .forEach(e -> countCallSitesIn(e, enclosingRule, counts));
            case Expression.ZeroOrMore z -> countCallSitesIn(z.expression(), enclosingRule, counts);
            case Expression.OneOrMore o -> countCallSitesIn(o.expression(), enclosingRule, counts);
            case Expression.Optional o -> countCallSitesIn(o.expression(), enclosingRule, counts);
            case Expression.Repetition r -> countCallSitesIn(r.expression(), enclosingRule, counts);
            case Expression.And a -> countCallSitesIn(a.expression(), enclosingRule, counts);
            case Expression.Not n -> countCallSitesIn(n.expression(), enclosingRule, counts);
            case Expression.TokenBoundary tb -> countCallSitesIn(tb.expression(), enclosingRule, counts);
            case Expression.Ignore ig -> countCallSitesIn(ig.expression(), enclosingRule, counts);
            case Expression.Capture cap -> countCallSitesIn(cap.expression(), enclosingRule, counts);
            case Expression.CaptureScope cs -> countCallSitesIn(cs.expression(), enclosingRule, counts);
            case Expression.Group g -> countCallSitesIn(g.expression(), enclosingRule, counts);
            default -> {}
        }
    }
}
