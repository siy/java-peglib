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
 Option<Expression> word) {
    /**
     * Get rule by name.
     */
    public Option<Rule> rule(String name) {
        return rules.stream()
                    .filter(r -> r.name()
                                  .equals(name))
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
        return rules.isEmpty()
               ? Option.none()
               : Option.some(rules.getFirst());
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
        var ruleNames = rules.stream()
                             .map(Rule::name)
                             .collect(Collectors.toSet());
        for (var rule : rules) {
            var undefinedRef = findUndefinedReference(rule.expression(), ruleNames);
            if (undefinedRef.isPresent()) {
                var ref = undefinedRef.unwrap();
                return Result.failure(new ParseError.SemanticError(
                ref.span()
                   .start(),
                "Undefined rule reference: '" + ref.ruleName() + "'"));
            }
        }
        return Result.success(this);
    }

    /**
     * Recursively find the first undefined rule reference in an expression.
     */
    private Option<Expression.Reference> findUndefinedReference(Expression expr, java.util.Set<String> ruleNames) {
        return switch (expr) {
            case Expression.Reference ref -> ruleNames.contains(ref.ruleName())
                                             ? Option.none()
                                             : Option.some(ref);
            case Expression.Sequence seq -> seq.elements()
                                               .stream()
                                               .map(e -> findUndefinedReference(e, ruleNames))
                                               .filter(Option::isPresent)
                                               .findFirst()
                                               .orElse(Option.none());
            case Expression.Choice choice -> choice.alternatives()
                                                   .stream()
                                                   .map(e -> findUndefinedReference(e, ruleNames))
                                                   .filter(Option::isPresent)
                                                   .findFirst()
                                                   .orElse(Option.none());
            case Expression.ZeroOrMore zom -> findUndefinedReference(zom.expression(), ruleNames);
            case Expression.OneOrMore oom -> findUndefinedReference(oom.expression(), ruleNames);
            case Expression.Optional opt -> findUndefinedReference(opt.expression(), ruleNames);
            case Expression.Repetition rep -> findUndefinedReference(rep.expression(), ruleNames);
            case Expression.And and -> findUndefinedReference(and.expression(), ruleNames);
            case Expression.Not not -> findUndefinedReference(not.expression(), ruleNames);
            case Expression.TokenBoundary tb -> findUndefinedReference(tb.expression(), ruleNames);
            case Expression.Ignore ign -> findUndefinedReference(ign.expression(), ruleNames);
            case Expression.Capture cap -> findUndefinedReference(cap.expression(), ruleNames);
            case Expression.CaptureScope cs -> findUndefinedReference(cs.expression(), ruleNames);
            case Expression.Group grp -> findUndefinedReference(grp.expression(), ruleNames);
            // Terminals - no nested expressions
            case Expression.Literal _ -> Option.none();
            case Expression.CharClass _ -> Option.none();
            case Expression.Any _ -> Option.none();
            case Expression.BackReference _ -> Option.none();
            case Expression.Dictionary _ -> Option.none();
            case Expression.Cut _ -> Option.none();
        };
    }
}
