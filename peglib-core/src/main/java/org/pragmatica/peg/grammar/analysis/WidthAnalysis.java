package org.pragmatica.peg.grammar.analysis;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.pragmatica.peg.grammar.Expression;
import org.pragmatica.peg.grammar.Rule;


/**
 * How much input a rule can consume — two distinct properties that are easy to conflate.
 *
 * <p><b>Nullable</b> means a rule <i>can</i> match the empty string. Left-recursion detection uses
 * it to decide whether a leading element can be skipped when following a cycle.
 *
 * <p><b>Always-empty</b> means a rule can match <i>only</i> the empty string. Rule classification
 * uses it to reject such a rule as a token: a lexeme has to consume something, and a rule built
 * entirely from lookaheads asserts a position rather than describing one.
 *
 * <p>The difference matters. {@code Word <- [a-z]*} is nullable but not always-empty — it is a
 * legitimate lexer rule that happens to accept in its start state (deliberate, and warned about
 * elsewhere). {@code EmptyStatement <- &';' / !.} is always-empty and cannot be a token at all.
 * Classifying on nullability would sweep the first case up with the second.
 *
 * <p>Both are fixed points over the rule graph, approached from opposite ends: nullability starts
 * false and grows, always-empty starts true and shrinks. Each terminates because its direction of
 * travel is monotonic.
 */
public final class WidthAnalysis {
    private WidthAnalysis() {}

    /** Whether each rule <i>can</i> match the empty string, keyed by rule name. */
    public static Map<String, Boolean> computeNullable(Map<String, Rule> ruleMap) {
        var nullable = new HashMap<String, Boolean>();

        for (var name : ruleMap.keySet()) {
            nullable.put(name, false);
        }

        boolean changed = true;

        while (changed) {
            changed = false;
            for (var entry : ruleMap.entrySet()) {
                if (nullable.get(entry.getKey())) {
                    continue;
                }

                if (isNullable(entry.getValue().expression(),
                               nullable)) {
                    nullable.put(entry.getKey(), true);
                    changed = true;
                }
            }
        }

        return nullable;
    }

    /**
     * Whether {@code expr} can match the empty string, given the nullability of referenced rules.
     *
     * <p>{@code And} and {@code Not} are nullable because a lookahead consumes nothing whatever
     * its outcome — which is exactly why a rule built only from lookaheads cannot be a token.
     */
    public static boolean isNullable(Expression expr, Map<String, Boolean> nullable) {
        return switch (expr) {
            case Expression.Literal lit -> lit.text().isEmpty();
            case Expression.CharClass __ -> false;
            case Expression.Any __ -> false;
            case Expression.Reference ref -> nullable.getOrDefault(ref.ruleName(), false);
            case Expression.Sequence seq -> allNullable(seq.elements(), nullable);
            case Expression.Choice ch -> ch.alternatives().stream().anyMatch(a -> isNullable(a, nullable));
            case Expression.ZeroOrMore __ -> true;
            case Expression.Optional __ -> true;
            case Expression.OneOrMore o -> isNullable(o.expression(), nullable);
            case Expression.Repetition r -> r.min() == 0 || isNullable(r.expression(), nullable);
            case Expression.And __ -> true;
            case Expression.Not __ -> true;
            case Expression.TokenBoundary tb -> isNullable(tb.expression(), nullable);
            case Expression.Ignore ig -> isNullable(ig.expression(), nullable);
            case Expression.Capture cap -> isNullable(cap.expression(), nullable);
            case Expression.CaptureScope cs -> isNullable(cs.expression(), nullable);
            case Expression.Group g -> isNullable(g.expression(), nullable);
            case Expression.Cut __ -> false;
            case Expression.BackReference __ -> false;
            case Expression.Dictionary __ -> false;
        };
    }

    private static boolean allNullable(List<Expression> elements, Map<String, Boolean> nullable) {
        for (var element : elements) {
            if (!isNullable(element, nullable)) {
                return false;
            }
        }

        return true;
    }

    /** Whether each rule can match <i>only</i> the empty string, keyed by rule name. */
    public static Map<String, Boolean> computeAlwaysEmpty(Map<String, Rule> ruleMap) {
        var alwaysEmpty = new HashMap<String, Boolean>();

        for (var name : ruleMap.keySet()) {
            alwaysEmpty.put(name, true);
        }

        boolean changed = true;

        while (changed) {
            changed = false;
            for (var entry : ruleMap.entrySet()) {
                if (!alwaysEmpty.get(entry.getKey())) {
                    continue;
                }

                if (!isAlwaysEmpty(entry.getValue().expression(),
                                   alwaysEmpty)) {
                    alwaysEmpty.put(entry.getKey(), false);
                    changed = true;
                }
            }
        }

        return alwaysEmpty;
    }

    /**
     * Whether {@code expr} can match only the empty string.
     *
     * <p>A repetition is always-empty exactly when its body is: repeating something that consumes
     * input can consume input, however many times it runs. An unknown reference is assumed to
     * consume, which keeps an unresolved name from demoting a rule.
     */
    public static boolean isAlwaysEmpty(Expression expr, Map<String, Boolean> alwaysEmpty) {
        return switch (expr) {
            case Expression.Literal lit -> lit.text().isEmpty();
            case Expression.CharClass __ -> false;
            case Expression.Any __ -> false;
            case Expression.Reference ref -> alwaysEmpty.getOrDefault(ref.ruleName(), false);
            case Expression.Sequence seq -> seq.elements().stream().allMatch(e -> isAlwaysEmpty(e, alwaysEmpty));
            case Expression.Choice ch -> ch.alternatives().stream().allMatch(a -> isAlwaysEmpty(a, alwaysEmpty));
            case Expression.ZeroOrMore z -> isAlwaysEmpty(z.expression(), alwaysEmpty);
            case Expression.OneOrMore o -> isAlwaysEmpty(o.expression(), alwaysEmpty);
            case Expression.Optional o -> isAlwaysEmpty(o.expression(), alwaysEmpty);
            case Expression.Repetition r -> isAlwaysEmpty(r.expression(), alwaysEmpty);
            // A lookahead or a cut consumes nothing whatever its outcome.
            case Expression.And __ -> true;
            case Expression.Not __ -> true;
            case Expression.Cut __ -> true;
            case Expression.TokenBoundary tb -> isAlwaysEmpty(tb.expression(), alwaysEmpty);
            case Expression.Ignore ig -> isAlwaysEmpty(ig.expression(), alwaysEmpty);
            case Expression.Capture cap -> isAlwaysEmpty(cap.expression(), alwaysEmpty);
            case Expression.CaptureScope cs -> isAlwaysEmpty(cs.expression(), alwaysEmpty);
            case Expression.Group g -> isAlwaysEmpty(g.expression(), alwaysEmpty);
            case Expression.BackReference __ -> false;
            case Expression.Dictionary __ -> false;
        };
    }
}
