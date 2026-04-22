package org.pragmatica.peg.grammar;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.peg.error.ParseError;
import org.pragmatica.peg.grammar.analysis.LeftRecursionAnalysis;
import org.pragmatica.peg.tree.SourceLocation;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A complete PEG grammar - collection of rules with directives.
 *
 * <p>{@code suggestRules} is the list of rule names declared at grammar level via
 * {@code %suggest RuleName}. The literal alternatives of those rules form a
 * suggestion vocabulary used by error reporting to emit "did you mean 'X'?"
 * hints on near-miss identifier failures. When empty, no suggestion logic is
 * activated and hot paths remain unaffected.
 *
 * <p>{@code imports} (0.2.8) is the list of {@code %import} directives declared
 * at grammar level. When empty the grammar is standalone; when non-empty it must
 * be passed through {@link GrammarResolver} before being handed to the engine
 * or generator. After resolution the composed {@code Grammar} carries an empty
 * {@code imports} list and all imported rules appear as regular entries in
 * {@link #rules()}.
 */
public record Grammar(
 List<Rule> rules,
 Option<String> startRule,
 Option<Expression> whitespace,
 Option<Expression> word,
 List<String> suggestRules,
 List<Import> imports) {
    /**
     * Backwards-compatible constructor matching the pre-0.2.4 signature.
     */
    public Grammar(List<Rule> rules,
                   Option<String> startRule,
                   Option<Expression> whitespace,
                   Option<Expression> word) {
        this(rules, startRule, whitespace, word, List.of(), List.of());
    }

    /**
     * Backwards-compatible constructor matching the 0.2.4-0.2.7 signature.
     */
    public Grammar(List<Rule> rules,
                   Option<String> startRule,
                   Option<Expression> whitespace,
                   Option<Expression> word,
                   List<String> suggestRules) {
        this(rules, startRule, whitespace, word, suggestRules, List.of());
    }

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
     * Validate the grammar for undefined references and unsupported recursion
     * shapes.
     *
     * <p>0.2.9: rejects grammars that contain <b>indirect</b> left-recursion
     * (cycle length &gt; 1 in the left-position reference graph). Direct
     * left-recursion ({@code Expr <- Expr '+' Term / Term}) is supported via
     * Warth-style seeding. See {@link LeftRecursionAnalysis}.
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
        var indirect = LeftRecursionAnalysis.findIndirectCycle(this);
        if (!indirect.isEmpty()) {
            var chain = String.join(" -> ", indirect);
            return Result.failure(new ParseError.SemanticError(
            SourceLocation.START, "indirect left-recursion detected in rule chain " + chain + "; not supported in 0.2.9"));
        }
        return Result.success(this);
    }

    /**
     * 0.2.9 — the set of directly left-recursive rule names in this grammar.
     * Computed on demand from {@link LeftRecursionAnalysis}. Empty when the
     * grammar has no LR rules (the common case). Engines and generators use
     * this set to wrap each LR rule's body in a Warth seed-and-grow loop.
     */
    public Set<String> leftRecursiveRules() {
        return LeftRecursionAnalysis.directLeftRecursiveRules(this);
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
