package org.pragmatica.peg.incremental.internal;

import org.pragmatica.peg.grammar.Expression;
import org.pragmatica.peg.grammar.Grammar;
import org.pragmatica.peg.grammar.Rule;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Grammar-parse-time analysis: determine which rules either contain a
 * {@link Expression.BackReference} directly or (transitively) reference
 * another rule that does.
 *
 * <p>Per SPEC §6.3 / §10, rules whose match depends on a {@code $name}
 * back-reference cannot be safely reparsed in isolation — the back-reference
 * resolves against a capture that may live outside the rule's own span. v1
 * falls back to a full reparse on any edit whose enclosing rule is in this
 * set.
 *
 * <p>Analysis runs once per {@code IncrementalParser.create(...)} and the
 * result is cached on the parser. The scan is O(expression tree size), not
 * per-edit.
 *
 * @since 0.3.1
 */
public final class BackReferenceScan {
    private BackReferenceScan() {}

    /**
     * Compute the set of rule names that must fall back to full reparse due
     * to (direct or transitive) back-reference dependency.
     */
    public static Set<String> unsafeRules(Grammar grammar) {
        var directlyUnsafe = directlyUnsafe(grammar);
        if (directlyUnsafe.isEmpty()) {
            return Set.of();
        }
        var byName = grammar.ruleMap();
        var refs = referenceGraph(grammar);
        var result = new HashSet<>(directlyUnsafe);
        // transitive closure: if rule A references rule B and B is unsafe,
        // A is unsafe too.
        boolean changed = true;
        while (changed) {
            changed = false;
            for (var rule : grammar.rules()) {
                if (result.contains(rule.name())) {
                    continue;
                }
                var outgoing = refs.getOrDefault(rule.name(), Set.of());
                for (var target : outgoing) {
                    if (result.contains(target) && byName.containsKey(target)) {
                        result.add(rule.name());
                        changed = true;
                        break;
                    }
                }
            }
        }
        return Set.copyOf(result);
    }

    private static Set<String> directlyUnsafe(Grammar grammar) {
        var out = new HashSet<String>();
        for (var rule : grammar.rules()) {
            if (hasBackReference(rule.expression())) {
                out.add(rule.name());
            }
        }
        return out;
    }

    private static boolean hasBackReference(Expression expr) {
        return switch (expr) {
            case Expression.BackReference _ -> true;
            case Expression.Sequence seq -> anyHasBackReference(seq.elements());
            case Expression.Choice choice -> anyHasBackReference(choice.alternatives());
            case Expression.ZeroOrMore zom -> hasBackReference(zom.expression());
            case Expression.OneOrMore oom -> hasBackReference(oom.expression());
            case Expression.Optional opt -> hasBackReference(opt.expression());
            case Expression.Repetition rep -> hasBackReference(rep.expression());
            case Expression.And and -> hasBackReference(and.expression());
            case Expression.Not not -> hasBackReference(not.expression());
            case Expression.TokenBoundary tb -> hasBackReference(tb.expression());
            case Expression.Ignore ign -> hasBackReference(ign.expression());
            case Expression.Capture cap -> hasBackReference(cap.expression());
            case Expression.CaptureScope cs -> hasBackReference(cs.expression());
            case Expression.Group grp -> hasBackReference(grp.expression());
            case Expression.Literal _, Expression.CharClass _, Expression.Any _,
                 Expression.Reference _, Expression.Dictionary _, Expression.Cut _ -> false;
        };
    }

    private static boolean anyHasBackReference(Iterable<Expression> exprs) {
        for (var e : exprs) {
            if (hasBackReference(e)) {
                return true;
            }
        }
        return false;
    }

    private static Map<String, Set<String>> referenceGraph(Grammar grammar) {
        var out = new HashMap<String, Set<String>>();
        for (var rule : grammar.rules()) {
            var targets = new HashSet<String>();
            collectReferences(rule.expression(), targets);
            out.put(rule.name(), targets);
        }
        return out;
    }

    private static void collectReferences(Expression expr, Set<String> out) {
        switch (expr) {
            case Expression.Reference ref -> out.add(ref.ruleName());
            case Expression.Sequence seq -> seq.elements().forEach(e -> collectReferences(e, out));
            case Expression.Choice choice -> choice.alternatives().forEach(e -> collectReferences(e, out));
            case Expression.ZeroOrMore zom -> collectReferences(zom.expression(), out);
            case Expression.OneOrMore oom -> collectReferences(oom.expression(), out);
            case Expression.Optional opt -> collectReferences(opt.expression(), out);
            case Expression.Repetition rep -> collectReferences(rep.expression(), out);
            case Expression.And and -> collectReferences(and.expression(), out);
            case Expression.Not not -> collectReferences(not.expression(), out);
            case Expression.TokenBoundary tb -> collectReferences(tb.expression(), out);
            case Expression.Ignore ign -> collectReferences(ign.expression(), out);
            case Expression.Capture cap -> collectReferences(cap.expression(), out);
            case Expression.CaptureScope cs -> collectReferences(cs.expression(), out);
            case Expression.Group grp -> collectReferences(grp.expression(), out);
            case Expression.Literal _, Expression.CharClass _, Expression.Any _,
                 Expression.BackReference _, Expression.Dictionary _, Expression.Cut _ -> {}
        }
    }

    /**
     * 0.3.1 — find all rule names referenced by expressions inside any rule.
     * Helper for tests / tooling; not used on the edit hot path.
     */
    public static Set<String> ruleDependencies(Rule rule) {
        var out = new HashSet<String>();
        collectReferences(rule.expression(), out);
        return out;
    }
}
