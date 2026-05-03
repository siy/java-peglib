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
 * A complete PEG grammar — collection of rules with directives.
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
 *
 * <h2>Construction (parse-don't-validate)</h2>
 *
 * <p>Use {@link #grammar(List, Option, Option, Option, List, List)} to build a
 * {@code Grammar} — the factory runs the same checks formerly performed by
 * {@code validate()} (undefined rule references, unsupported indirect
 * left-recursion) and returns {@link Result.Failure} when those fail.
 *
 * <p>The record's canonical constructor remains technically callable (Java
 * records require canonical-ctor visibility ≥ class visibility), but it is for
 * internal/library use only — external callers should always go through
 * {@link #grammar(List, Option, Option, Option, List, List)}. Constructing a
 * {@code Grammar} via {@code new} bypasses validation and the resulting object
 * may be rejected by the engine and generator at use time.
 *
 * @since 0.4.0 — parse-don't-validate factory replaces the post-construction
 * {@code validate()} method.
 */
public record Grammar(
 List<Rule> rules,
 Option<String> startRule,
 Option<Expression> whitespace,
 Option<Expression> word,
 List<String> suggestRules,
 List<Import> imports) {
    /**
     * Construct a validated {@code Grammar}.
     *
     * <p>Runs the legacy {@code validate()} checks during construction:
     * <ul>
     *   <li>every {@link Expression.Reference} must point to a rule defined in
     *       {@code rules};</li>
     *   <li>the rule reference graph contains no <b>indirect</b> left-recursion
     *       cycles (cycle length &gt; 1). Direct left-recursion is supported via
     *       Warth-style seeding and is allowed.</li>
     * </ul>
     *
     * <p>On failure, returns a {@link Result.Failure} carrying a
     * {@link ParseError.SemanticError} describing the offending rule.
     *
     * @since 0.4.0
     */
    public static Result<Grammar> grammar(List<Rule> rules,
                                          Option<String> startRule,
                                          Option<Expression> whitespace,
                                          Option<Expression> word,
                                          List<String> suggestRules,
                                          List<Import> imports) {
        return validate(new Grammar(rules, startRule, whitespace, word, suggestRules, imports));
    }

    /**
     * Convenience overload — empty {@code suggestRules} and {@code imports}.
     *
     * @since 0.4.0
     */
    public static Result<Grammar> grammar(List<Rule> rules,
                                          Option<String> startRule,
                                          Option<Expression> whitespace,
                                          Option<Expression> word) {
        return grammar(rules, startRule, whitespace, word, List.of(), List.of());
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
     * 0.2.9 — the set of directly left-recursive rule names in this grammar.
     * Computed on demand from {@link LeftRecursionAnalysis}. Empty when the
     * grammar has no LR rules (the common case). Engines and generators use
     * this set to wrap each LR rule's body in a Warth seed-and-grow loop.
     */
    public Set<String> leftRecursiveRules() {
        return LeftRecursionAnalysis.directLeftRecursiveRules(this);
    }

    /**
     * Run the validation checks against {@code candidate} and return either
     * {@code candidate} on success or a {@link Result.Failure} describing the
     * first offence. Pure function over the candidate's rule list.
     */
    private static Result<Grammar> validate(Grammar candidate) {
        var ruleNames = candidate.rules.stream()
                                 .map(Rule::name)
                                 .collect(Collectors.toSet());
        for (var rule : candidate.rules) {
            var undefinedRef = findUndefinedReference(rule.expression(), ruleNames);
            if (undefinedRef.isPresent()) {
                var ref = undefinedRef.unwrap();
                return new ParseError.SemanticError(
                ref.span()
                   .start(),
                "Undefined rule reference: '" + ref.ruleName() + "'").result();
            }
        }
        var indirect = LeftRecursionAnalysis.findIndirectCycle(candidate);
        if (!indirect.isEmpty()) {
            var chain = String.join(" -> ", indirect);
            return new ParseError.SemanticError(
            SourceLocation.START, "indirect left-recursion detected in rule chain " + chain + "; not supported in 0.2.9").result();
        }
        return Result.success(candidate);
    }

    /**
     * Recursively find the first undefined rule reference in an expression.
     */
    private static Option<Expression.Reference> findUndefinedReference(Expression expr,
                                                                       java.util.Set<String> ruleNames) {
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
