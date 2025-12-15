package org.pragmatica.peg.grammar;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.peg.error.ParseError;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * A complete PEG grammar - collection of rules with directives.
 */
public record Grammar(
    List<Rule> rules,
    Option<String> startRule,
    Option<Expression> whitespace,
    Option<Expression> word
) {
    /**
     * Get rule by name.
     */
    public Option<Rule> rule(String name) {
        return rules.stream()
            .filter(r -> r.name().equals(name))
            .findFirst()
            .map(Option::some)
            .orElse(Option.none());
    }

    /**
     * Get the effective start rule (first rule if not explicitly specified).
     */
    public Option<Rule> effectiveStartRule() {
        var explicitStart = startRule.flatMap(this::rule);
        if (explicitStart.isPresent()) {
            return explicitStart;
        }
        return rules.isEmpty() ? Option.none() : Option.some(rules.getFirst());
    }

    /**
     * Build a lookup map for efficient rule access.
     */
    public Map<String, Rule> ruleMap() {
        return rules.stream()
            .collect(Collectors.toMap(Rule::name, r -> r));
    }

    /**
     * Validate the grammar for undefined references.
     */
    public Result<Grammar> validate() {
        var ruleNames = rules.stream().map(Rule::name).collect(Collectors.toSet());
        // TODO: Walk expressions and check all References exist in ruleNames
        return Result.success(this);
    }
}
