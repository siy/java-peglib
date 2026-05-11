package org.pragmatica.peg.v6.analyzer;

import org.pragmatica.lang.Result;
import org.pragmatica.peg.grammar.Expression;
import org.pragmatica.peg.grammar.Grammar;
import org.pragmatica.peg.grammar.Rule;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 0.6.0 — left-recursion detector. PEG grammars cannot express left recursion;
 * a left-recursive rule causes the parsing engine to loop forever (or fail
 * silently in stack-overflow handlers). This analyzer rejects such grammars at
 * {@code PegParser.fromGrammar} time with a witness path the user can act on.
 *
 * <h2>Algorithm</h2>
 *
 * <p>For every rule {@code R} we compute the set {@code leftmostRefs(R)} —
 * rules reachable through {@code R}'s leftmost path. {@code R} is
 * left-recursive iff the transitive closure of {@code leftmostRefs} starting
 * at {@code R} contains {@code R} itself. The leftmost relation is
 * <i>nullable-aware</i>: {@code Sequence(a, b)} flows into {@code b} only when
 * {@code a} can match the empty string. Nullability is computed via fixed-point
 * iteration over the rule graph.
 *
 * <p>Witness paths are produced by a simple parent-tracking BFS so the failure
 * message can show the exact cycle ({@code Expr → Term → Expr}). Paths are
 * minimal — shortest discovered cycle through the start rule.
 */
public final class LeftRecursionDetector {
    private LeftRecursionDetector() {}

    public record LeftRecursionError(String ruleName, List<String> witnessCycle) {
        public String message() {
            var path = String.join(" → ", witnessCycle);
            return "Rule '" + ruleName + "' is left-recursive: " + path + ". PEG cannot express left recursion; rewrite as right-recursive," + " e.g. 'A <- B (op B)*' instead of 'A <- A op B / B'.";
        }
    }

    public record DetectionResult(List<LeftRecursionError> errors,
                                  Map<String, Boolean> nullable,
                                  Map<String, Set<String>> leftmostRefs) {
        public boolean hasErrors() {
            return ! errors.isEmpty();
        }
    }

    public static Result<DetectionResult> detect(Grammar grammar) {
        // Internal entry: callers (PegParser/tests) pass validated inputs.
        var ruleMap = new LinkedHashMap<String, Rule>();
        for ( var rule : grammar.rules()) {
        ruleMap.putIfAbsent(rule.name(), rule);}
        var nullable = computeNullable(ruleMap);
        var leftmostRefs = computeLeftmostRefs(ruleMap, nullable);
        var errors = new ArrayList<LeftRecursionError>();
        for ( var name : ruleMap.keySet()) {
            var cycle = findCycle(name, leftmostRefs);
            if ( !cycle.isEmpty()) {
            errors.add(new LeftRecursionError(name, cycle));}
        }
        return Result.success(new DetectionResult(List.copyOf(errors),
                                                  Collections.unmodifiableMap(nullable),
                                                  Collections.unmodifiableMap(leftmostRefs)));
    }

    // ---------------------------------------------------------------------
    // Nullable analysis (fixed-point)
    // ---------------------------------------------------------------------
    private static Map<String, Boolean> computeNullable(Map<String, Rule> ruleMap) {
        var nullable = new HashMap<String, Boolean>();
        for ( var name : ruleMap.keySet()) {
        nullable.put(name, false);}
        boolean changed = true;
        while ( changed) {
            changed = false;
            for ( var entry : ruleMap.entrySet()) {
                if ( nullable.get(entry.getKey())) {
                continue;}
                if ( isNullable(entry.getValue().expression(),
                                nullable)) {
                    nullable.put(entry.getKey(), true);
                    changed = true;
                }
            }
        }
        return nullable;
    }

    private static boolean isNullable(Expression expr, Map<String, Boolean> nullable) {
        return switch (expr) {case Expression.Literal lit -> lit.text().isEmpty();case Expression.CharClass __ -> false;case Expression.Any __ -> false;case Expression.Reference ref -> nullable.getOrDefault(ref.ruleName(),
                                                                                                                                                                                                               false);case Expression.Sequence seq -> allNullable(seq.elements(),
                                                                                                                                                                                                                                                                  nullable);case Expression.Choice ch -> ch.alternatives().stream()
                                                                                                                                                                                                                                                                                                                        .anyMatch(a -> isNullable(a,
                                                                                                                                                                                                                                                                                                                                                  nullable));case Expression.ZeroOrMore __ -> true;case Expression.Optional __ -> true;case Expression.OneOrMore o -> isNullable(o.expression(),
                                                                                                                                                                                                                                                                                                                                                                                                                                                                                 nullable);case Expression.Repetition r -> r.min() == 0 || isNullable(r.expression(),
                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                      nullable);case Expression.And __ -> true;case Expression.Not __ -> true;case Expression.TokenBoundary tb -> isNullable(tb.expression(),
                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                             nullable);case Expression.Ignore ig -> isNullable(ig.expression(),
                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                               nullable);case Expression.Capture cap -> isNullable(cap.expression(),
                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                   nullable);case Expression.CaptureScope cs -> isNullable(cs.expression(),
                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                           nullable);case Expression.Group g -> isNullable(g.expression(),
                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                           nullable);case Expression.Cut __ -> false;case Expression.BackReference __ -> false;case Expression.Dictionary __ -> false;};
    }

    private static boolean allNullable(List<Expression> elements, Map<String, Boolean> nullable) {
        for ( var e : elements) {
        if ( !isNullable(e, nullable)) {
        return false;}}
        return true;
    }

    // ---------------------------------------------------------------------
    // Leftmost-reference graph (nullable-aware)
    // ---------------------------------------------------------------------
    private static Map<String, Set<String>> computeLeftmostRefs(Map<String, Rule> ruleMap,
                                                                Map<String, Boolean> nullable) {
        var graph = new LinkedHashMap<String, Set<String>>();
        for ( var entry : ruleMap.entrySet()) {
            var refs = new java.util.LinkedHashSet<String>();
            collectLeftmost(entry.getValue().expression(),
                            nullable,
                            refs);
            graph.put(entry.getKey(), Collections.unmodifiableSet(refs));
        }
        return graph;
    }

    private static void collectLeftmost(Expression expr,
                                        Map<String, Boolean> nullable,
                                        Set<String> out) {
        switch ( expr) {
            case Expression.Reference ref -> out.add(ref.ruleName());
            case Expression.Choice ch -> {
                for ( var alt : ch.alternatives()) {
                collectLeftmost(alt, nullable, out);}
            }
            case Expression.Sequence seq -> {
                for ( var el : seq.elements()) {
                    collectLeftmost(el, nullable, out);
                    if ( !isNullable(el, nullable)) {
                    return;}
                }
            }
            case Expression.ZeroOrMore z -> collectLeftmost(z.expression(), nullable, out);
            case Expression.OneOrMore o -> collectLeftmost(o.expression(), nullable, out);
            case Expression.Optional o -> collectLeftmost(o.expression(), nullable, out);
            case Expression.Repetition r -> collectLeftmost(r.expression(), nullable, out);
            case Expression.And a -> collectLeftmost(a.expression(), nullable, out);
            case Expression.Not n -> collectLeftmost(n.expression(), nullable, out);
            case Expression.TokenBoundary tb -> collectLeftmost(tb.expression(), nullable, out);
            case Expression.Ignore ig -> collectLeftmost(ig.expression(), nullable, out);
            case Expression.Capture cap -> collectLeftmost(cap.expression(), nullable, out);
            case Expression.CaptureScope cs -> collectLeftmost(cs.expression(), nullable, out);
            case Expression.Group g -> collectLeftmost(g.expression(), nullable, out);
            case Expression.Literal __ -> {}
            case Expression.CharClass __ -> {}
            case Expression.Any __ -> {}
            case Expression.Cut __ -> {}
            case Expression.BackReference __ -> {}
            case Expression.Dictionary __ -> {}
        }
    }

    // ---------------------------------------------------------------------
    // Cycle search — BFS with parent tracking for shortest witness path
    // ---------------------------------------------------------------------
    /**
     * Return the shortest cycle that returns to {@code start} via the leftmost
     * graph, in the form {@code [start, ..., start]}. Empty list means
     * {@code start} is not left-recursive.
     */
    private static List<String> findCycle(String start, Map<String, Set<String>> leftmostRefs) {
        var directRefs = leftmostRefs.getOrDefault(start, Set.of());
        if ( directRefs.contains(start)) {
        return List.of(start, start);}
        var parent = new HashMap<String, String>();
        var visited = new HashSet<String>();
        var queue = new ArrayDeque<String>();
        for ( var ref : directRefs) {
            queue.add(ref);
            parent.put(ref, start);
            visited.add(ref);
        }
        while ( !queue.isEmpty()) {
            var node = queue.removeFirst();
            for ( var ref : leftmostRefs.getOrDefault(node, Set.of())) {
                if ( ref.equals(start)) {
                return reconstructPath(start, node, parent);}
                if ( visited.add(ref)) {
                    parent.put(ref, node);
                    queue.add(ref);
                }
            }
        }
        return List.of();
    }

    /**
     * Walk parent chain from {@code last} back to (but not including) {@code start},
     * then return {@code [start, intermediates..., last, start]} with the
     * intermediates in source-to-sink order.
     */
    private static List<String> reconstructPath(String start, String last, Map<String, String> parent) {
        var reversed = new ArrayList<String>();
        var cursor = last;
        while ( cursor != null && !cursor.equals(start)) {
            reversed.add(cursor);
            cursor = parent.get(cursor);
        }
        Collections.reverse(reversed);
        var path = new ArrayList<String>(reversed.size() + 2);
        path.add(start);
        path.addAll(reversed);
        path.add(start);
        return List.copyOf(path);
    }
}
