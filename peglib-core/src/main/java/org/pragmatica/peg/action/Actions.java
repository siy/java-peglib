package org.pragmatica.peg.action;

import org.pragmatica.lang.Option;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * Immutable, composable container for programmatic action attachments (0.2.6).
 *
 * <p>Each entry maps a {@link RuleId} class to a lambda of type
 * {@code Function<SemanticValues, Object>}. The parser dispatches on rule
 * success: when a rule's sanitized name matches a {@code RuleId}'s simple
 * class name, the attached lambda runs in place of any inline grammar action.
 *
 * <p>Lambda attachment wins over inline grammar actions — document this at the
 * call site so callers opt in consciously.
 *
 * <p>Instances are immutable: {@link #with(Class, Function)} always returns a
 * new {@code Actions}, leaving the receiver untouched. This lets builders be
 * shared and threaded through without defensive copying.
 */
public final class Actions {
    private static final Actions EMPTY = new Actions(Map.of(), Map.of());

    private final Map<Class< ? extends RuleId>, Function<SemanticValues, Object>> byClass;
    private final Map<String, Function<SemanticValues, Object>> byName;

    private Actions(Map<Class< ? extends RuleId>, Function<SemanticValues, Object>> byClass,
                    Map<String, Function<SemanticValues, Object>> byName) {
        this.byClass = byClass;
        this.byName = byName;
    }

    /**
     * Return the empty {@code Actions} — no lambda attachments.
     */
    public static Actions empty() {
        return EMPTY;
    }

    /**
     * Attach a lambda for the given {@code RuleId} class. Returns a new
     * {@code Actions} with the mapping added; if a mapping for the same
     * class (or same rule name) already exists, it is replaced.
     */
    public Actions with(Class< ? extends RuleId> ruleIdClass, Function<SemanticValues, Object> action) {
        var ruleName = ruleNameOf(ruleIdClass);
        var newByClass = new LinkedHashMap<>(byClass);
        newByClass.put(ruleIdClass, action);
        var newByName = new HashMap<>(byName);
        newByName.put(ruleName, action);
        return new Actions(Map.copyOf(newByClass), Map.copyOf(newByName));
    }

    /**
     * Lookup by rule name. Returns an empty {@link Option} when no lambda is
     * attached for the given rule. Used by the interpreter's action dispatch.
     */
    public Option<Function<SemanticValues, Object>> get(String ruleName) {
        return Option.option(byName.get(ruleName));
    }

    /**
     * Lookup by {@code RuleId} class. Reserved for {@code parseRuleAt} (0.3.0)
     * dispatch by rule class.
     */
    public Function<SemanticValues, Object> get(Class< ? extends RuleId> ruleIdClass) {
        return byClass.get(ruleIdClass);
    }

    /**
     * Number of attached lambdas. Primarily for testing.
     */
    public int size() {
        return byClass.size();
    }

    /**
     * True when no lambdas are attached. Hot-path short-circuit.
     */
    public boolean isEmpty() {
        return byClass.isEmpty();
    }

    /**
     * Resolve the rule name associated with a {@code RuleId} class. Uses the
     * simple class name directly — matches the sanitized rule-name convention
     * used by the generator and by the library-provided default
     * {@link RuleId#name()}.
     */
    private static String ruleNameOf(Class< ? extends RuleId> ruleIdClass) {
        return ruleIdClass.getSimpleName();
    }
}
