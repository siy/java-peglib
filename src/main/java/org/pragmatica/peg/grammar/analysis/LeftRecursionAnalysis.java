package org.pragmatica.peg.grammar.analysis;

import org.pragmatica.peg.grammar.Expression;
import org.pragmatica.peg.grammar.Grammar;
import org.pragmatica.peg.grammar.Rule;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Left-recursion detection for PEG grammars (0.2.9).
 *
 * <p>A rule is <b>directly</b> left-recursive when its expression can begin with
 * a reference to itself at position zero, walking through transparent wrappers
 * (sequence first-element, choice alternative, group, token-boundary, capture,
 * capture-scope, ignore).
 *
 * <p><b>Indirect</b> left-recursion forms a cycle of length &gt; 1 in the
 * left-position reference graph and is <i>not supported</i> by the 0.2.9
 * engine/generator. The {@link #findIndirectCycle(Grammar)} helper returns the
 * first such cycle found so callers can emit a hard-error with a clear message.
 *
 * <p>This analyzer is side-effect-free and reusable across {@link Grammar#validate()},
 * {@code PegEngine}, and {@code ParserGenerator}.
 */
public final class LeftRecursionAnalysis {
    private LeftRecursionAnalysis() {}

    /**
     * Collect the set of rule names that are directly left-recursive.
     *
     * <p>A rule {@code R} is directly left-recursive if, walking from
     * {@code R}'s expression through transparent wrappers, the first potential
     * consuming element is an {@link Expression.Reference} naming {@code R}
     * itself. {@code Choice} is handled by checking each alternative independently;
     * a rule with {@code R <- R '+' Term / Term} is direct-LR via its first
     * alternative.
     *
     * @param grammar the grammar to analyze
     * @return the set of rule names that are directly left-recursive
     */
    public static Set<String> directLeftRecursiveRules(Grammar grammar) {
        var result = new LinkedHashSet<String>();
        for (var rule : grammar.rules()) {
            if (isDirectLeftRecursive(rule)) {
                result.add(rule.name());
            }
        }
        return result;
    }

    /**
     * @return {@code true} if {@code rule} is directly left-recursive
     */
    public static boolean isDirectLeftRecursive(Rule rule) {
        return expressionStartsWithRef(rule.expression(), rule.name());
    }

    /**
     * Walks {@code expr} through transparent wrappers and {@link Expression.Choice}
     * alternatives to determine whether at least one leftmost path begins with
     * a {@link Expression.Reference} to {@code self}.
     */
    private static boolean expressionStartsWithRef(Expression expr, String self) {
        return switch (expr) {
            case Expression.Reference ref -> ref.ruleName()
                                                .equals(self);
            case Expression.Choice ch -> ch.alternatives()
                                           .stream()
                                           .anyMatch(alt -> expressionStartsWithRef(alt, self));
            case Expression.Sequence seq -> {
                for (var el : seq.elements()) {
                    if (el instanceof Expression.And || el instanceof Expression.Not) {
                        continue;
                    }
                    yield expressionStartsWithRef(el, self);
                }
                yield false;
            }
            case Expression.Group grp -> expressionStartsWithRef(grp.expression(), self);
            case Expression.TokenBoundary tb -> expressionStartsWithRef(tb.expression(), self);
            case Expression.Capture cap -> expressionStartsWithRef(cap.expression(), self);
            case Expression.CaptureScope cs -> expressionStartsWithRef(cs.expression(), self);
            case Expression.Ignore ig -> expressionStartsWithRef(ig.expression(), self);
            default -> false;
        };
    }

    /**
     * Find an indirect left-recursion cycle, if any. An indirect cycle is a path
     * of length &gt; 1 in the "left-position reference" graph that returns to
     * its start. Direct self-loops (length 1) are ignored — those are handled
     * by the direct-LR Warth seeding path.
     *
     * @return an ordered list of rule names forming the cycle (first == last),
     *         or an empty list if no indirect cycle exists
     */
    public static List<String> findIndirectCycle(Grammar grammar) {
        Map<String, List<String>> leftRefs = buildLeftRefGraph(grammar);
        var visited = new HashSet<String>();
        for (var start : leftRefs.keySet()) {
            if (visited.contains(start)) {
                continue;
            }
            var path = new ArrayList<String>();
            var onPath = new HashSet<String>();
            var cycle = dfs(start, leftRefs, visited, path, onPath);
            if (!cycle.isEmpty()) {
                return cycle;
            }
        }
        return List.of();
    }

    private static List<String> dfs(String node,
                                    Map<String, List<String>> leftRefs,
                                    Set<String> visited,
                                    List<String> path,
                                    Set<String> onPath) {
        if (onPath.contains(node)) {
            int from = path.indexOf(node);
            if (from < 0) {
                return List.of();
            }
            var cycle = new ArrayList<>(path.subList(from, path.size()));
            cycle.add(node);
            // Filter direct self-loops (length-2: [A, A]) — direct LR is handled elsewhere.
            if (cycle.size() <= 2) {
                return List.of();
            }
            return cycle;
        }
        if (visited.contains(node)) {
            return List.of();
        }
        path.add(node);
        onPath.add(node);
        var refs = leftRefs.getOrDefault(node, List.of());
        for (var ref : refs) {
            if (ref.equals(node)) {
                continue;
            }
            var found = dfs(ref, leftRefs, visited, path, onPath);
            if (!found.isEmpty()) {
                return found;
            }
        }
        path.remove(path.size() - 1);
        onPath.remove(node);
        visited.add(node);
        return List.of();
    }

    /**
     * Build the left-position reference graph: for each rule {@code R}, the
     * list of rule names that can appear at a leftmost consuming position
     * inside {@code R}'s expression.
     */
    private static Map<String, List<String>> buildLeftRefGraph(Grammar grammar) {
        var g = new HashMap<String, List<String>>();
        for (var rule : grammar.rules()) {
            var refs = new ArrayList<String>();
            collectLeftRefs(rule.expression(), refs);
            g.put(rule.name(), refs);
        }
        return g;
    }

    private static void collectLeftRefs(Expression expr, List<String> out) {
        switch (expr) {
            case Expression.Reference ref -> {
                if (!out.contains(ref.ruleName())) {
                    out.add(ref.ruleName());
                }
            }
            case Expression.Choice ch -> {
                for (var alt : ch.alternatives()) {
                    collectLeftRefs(alt, out);
                }
            }
            case Expression.Sequence seq -> {
                for (var el : seq.elements()) {
                    if (el instanceof Expression.And || el instanceof Expression.Not) {
                        continue;
                    }
                    collectLeftRefs(el, out);
                    return;
                }
            }
            case Expression.Group grp -> collectLeftRefs(grp.expression(), out);
            case Expression.TokenBoundary tb -> collectLeftRefs(tb.expression(), out);
            case Expression.Capture cap -> collectLeftRefs(cap.expression(), out);
            case Expression.CaptureScope cs -> collectLeftRefs(cs.expression(), out);
            case Expression.Ignore ig -> collectLeftRefs(ig.expression(), out);
            default -> {}
        }
    }
}
