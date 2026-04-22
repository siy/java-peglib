package org.pragmatica.peg.analyzer;

import org.pragmatica.peg.grammar.Expression;
import org.pragmatica.peg.grammar.Grammar;
import org.pragmatica.peg.grammar.Rule;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Static analyzer for PEG grammars. Runs a fixed battery of checks and
 * produces an {@link AnalyzerReport} of {@link Finding}s.
 *
 * <p>Checks implemented:
 * <ol>
 *   <li>{@code grammar.unreachable-rule} — rules not transitively reachable
 *       from the start rule. Severity: WARNING.</li>
 *   <li>{@code grammar.ambiguous-choice} — {@link Expression.Choice}
 *       alternatives whose first-character sets are identical AND whose
 *       prefixes are literal. Conservative: only flags the high-confidence
 *       ambiguities caught by {@link ChoiceDispatchAnalyzer}. Severity: WARNING.</li>
 *   <li>{@code grammar.nullable-rule} — rules whose expression can match the
 *       empty string, computed by fix-point analysis. Severity: INFO, promoted
 *       to WARNING if the rule is on a (direct) left-recursive path.</li>
 *   <li>{@code grammar.duplicate-literal} — literal alternatives that repeat
 *       verbatim within the same {@link Expression.Choice}. Severity: ERROR.</li>
 *   <li>{@code grammar.whitespace-cycle} — transitive self-reference in the
 *       {@code %whitespace} directive. Severity: ERROR.</li>
 *   <li>{@code grammar.has-backreference} — rules containing any
 *       {@link Expression.BackReference}. Forward-compatibility note for
 *       incremental parsing. Severity: INFO.</li>
 * </ol>
 */
public final class Analyzer {
    private final Grammar grammar;

    private Analyzer(Grammar grammar) {
        this.grammar = grammar;
    }

    /**
     * Analyze a grammar and return a report of findings.
     */
    public static AnalyzerReport analyze(Grammar grammar) {
        return new Analyzer(grammar).run();
    }

    private AnalyzerReport run() {
        var findings = new ArrayList<Finding>();
        findings.addAll(checkUnreachableRules());
        findings.addAll(checkAmbiguousChoices());
        findings.addAll(checkNullableRules());
        findings.addAll(checkDuplicateLiterals());
        findings.addAll(checkWhitespaceCycle());
        findings.addAll(checkBackReferences());
        // Stable sort: by rule name, then tag, then severity. Ensures deterministic output.
        findings.sort(Comparator.<Finding, String>comparing(Finding::ruleName)
                                .thenComparing(Finding::tag)
                                .thenComparing(f -> f.severity()
                                                     .name()));
        return new AnalyzerReport(List.copyOf(findings));
    }

    // === Check 1: Unreachable rules ===

    private List<Finding> checkUnreachableRules() {
        var start = grammar.effectiveStartRule();
        if (start.isEmpty()) {
            return List.of();
        }
        var reachable = new HashSet<String>();
        var ruleMap = grammar.ruleMap();
        String startName = start.unwrap()
                                .name();
        collectReferences(startName, ruleMap, reachable);
        var findings = new ArrayList<Finding>();
        for (var rule : grammar.rules()) {
            if (!reachable.contains(rule.name())) {
                findings.add(Finding.warning(
                "grammar.unreachable-rule",
                rule.name(),
                "rule '" + rule.name() + "' is unreachable from start rule '" + startName + "'"));
            }
        }
        return findings;
    }

    private static void collectReferences(String ruleName, Map<String, Rule> ruleMap, Set<String> visited) {
        if (!visited.add(ruleName)) {
            return;
        }
        var rule = ruleMap.get(ruleName);
        if (rule == null) {
            return;
        }
        for (var ref : collectReferences(rule.expression())) {
            collectReferences(ref, ruleMap, visited);
        }
    }

    private static Set<String> collectReferences(Expression expr) {
        var refs = new LinkedHashSet<String>();
        gatherReferences(expr, refs);
        return refs;
    }

    private static void gatherReferences(Expression expr, Set<String> out) {
        switch (expr) {
            case Expression.Reference ref -> out.add(ref.ruleName());
            case Expression.Sequence seq -> seq.elements()
                                               .forEach(e -> gatherReferences(e, out));
            case Expression.Choice ch -> ch.alternatives()
                                           .forEach(e -> gatherReferences(e, out));
            case Expression.ZeroOrMore zom -> gatherReferences(zom.expression(), out);
            case Expression.OneOrMore oom -> gatherReferences(oom.expression(), out);
            case Expression.Optional opt -> gatherReferences(opt.expression(), out);
            case Expression.Repetition rep -> gatherReferences(rep.expression(), out);
            case Expression.And and -> gatherReferences(and.expression(), out);
            case Expression.Not not -> gatherReferences(not.expression(), out);
            case Expression.TokenBoundary tb -> gatherReferences(tb.expression(), out);
            case Expression.Ignore ig -> gatherReferences(ig.expression(), out);
            case Expression.Capture cap -> gatherReferences(cap.expression(), out);
            case Expression.CaptureScope cs -> gatherReferences(cs.expression(), out);
            case Expression.Group grp -> gatherReferences(grp.expression(), out);
            case Expression.Literal _, Expression.CharClass _, Expression.Any _,
                 Expression.BackReference _, Expression.Dictionary _, Expression.Cut _ -> {
                // terminals — no nested refs
            }
        }
    }

    // === Check 2: Ambiguous choices (first-char overlap with literal prefixes) ===

    private List<Finding> checkAmbiguousChoices() {
        var findings = new ArrayList<Finding>();
        for (var rule : grammar.rules()) {
            gatherAmbiguousChoices(rule, rule.expression(), findings);
        }
        return findings;
    }

    private void gatherAmbiguousChoices(Rule rule, Expression expr, List<Finding> findings) {
        if (expr instanceof Expression.Choice choice) {
            inspectChoice(rule, choice, findings);
        }
        // Recurse into children
        switch (expr) {
            case Expression.Sequence seq -> seq.elements()
                                               .forEach(e -> gatherAmbiguousChoices(rule, e, findings));
            case Expression.Choice ch -> ch.alternatives()
                                           .forEach(e -> gatherAmbiguousChoices(rule, e, findings));
            case Expression.ZeroOrMore zom -> gatherAmbiguousChoices(rule, zom.expression(), findings);
            case Expression.OneOrMore oom -> gatherAmbiguousChoices(rule, oom.expression(), findings);
            case Expression.Optional opt -> gatherAmbiguousChoices(rule, opt.expression(), findings);
            case Expression.Repetition rep -> gatherAmbiguousChoices(rule, rep.expression(), findings);
            case Expression.And and -> gatherAmbiguousChoices(rule, and.expression(), findings);
            case Expression.Not not -> gatherAmbiguousChoices(rule, not.expression(), findings);
            case Expression.TokenBoundary tb -> gatherAmbiguousChoices(rule, tb.expression(), findings);
            case Expression.Ignore ig -> gatherAmbiguousChoices(rule, ig.expression(), findings);
            case Expression.Capture cap -> gatherAmbiguousChoices(rule, cap.expression(), findings);
            case Expression.CaptureScope cs -> gatherAmbiguousChoices(rule, cs.expression(), findings);
            case Expression.Group grp -> gatherAmbiguousChoices(rule, grp.expression(), findings);
            default -> {
                // terminals
            }
        }
    }

    private void inspectChoice(Rule rule, Expression.Choice choice, List<Finding> findings) {
        // Conservative: only flag ambiguity when EVERY alternative has a fixed literal prefix
        // (i.e. ChoiceDispatchAnalyzer would classify it as dispatchable) AND two or more
        // alternatives share the same dispatch char. This avoids false positives on
        // char-class / rule-ref-prefixed alternatives where overlap may be resolved downstream.
        var alternatives = choice.alternatives();
        if (alternatives.size() < 2) {
            return;
        }
        var firstChars = new ArrayList<Character>(alternatives.size());
        for (var alt : alternatives) {
            var ch = literalFirstChar(alt);
            if (ch == null) {
                return; // not fully literal-prefixed; skip
            }
            firstChars.add(ch);
        }
        var byChar = new HashMap<Character, List<Integer>>();
        for (int i = 0; i < firstChars.size(); i++ ) {
            byChar.computeIfAbsent(firstChars.get(i),
                                   k -> new ArrayList<>())
                  .add(i);
        }
        for (var bucket : byChar.entrySet()) {
            if (bucket.getValue()
                      .size() >= 2) {
                findings.add(Finding.warning(
                "grammar.ambiguous-choice",
                rule.name(),
                "choice alternatives at positions " + bucket.getValue()
                + " share first char '" + bucket.getKey() + "' (potential ambiguity)"));
            }
        }
    }

    /**
     * Walk transparent wrappers to the first literal prefix character. Returns null
     * if any alternative is not literal-prefixed. Mirrors ChoiceDispatchAnalyzer logic.
     */
    private static Character literalFirstChar(Expression expr) {
        return switch (expr) {
            case Expression.Literal lit -> lit.text()
                                              .isEmpty()
                                           ? null
                                           : lit.text()
                                                .charAt(0);
            case Expression.Sequence seq -> firstLiteralOfSequence(seq);
            case Expression.Group grp -> literalFirstChar(grp.expression());
            case Expression.TokenBoundary tb -> literalFirstChar(tb.expression());
            case Expression.Ignore ig -> literalFirstChar(ig.expression());
            case Expression.Capture cap -> literalFirstChar(cap.expression());
            default -> null;
        };
    }

    private static Character firstLiteralOfSequence(Expression.Sequence seq) {
        for (var el : seq.elements()) {
            if (el instanceof Expression.And || el instanceof Expression.Not) {
                continue;
            }
            return literalFirstChar(el);
        }
        return null;
    }

    // === Check 3: Nullable rules ===

    private List<Finding> checkNullableRules() {
        var nullable = computeNullableFixedPoint();
        var leftRecursive = computeDirectLeftRecursiveRules();
        var findings = new ArrayList<Finding>();
        for (var rule : grammar.rules()) {
            if (Boolean.TRUE.equals(nullable.get(rule.name()))) {
                var severity = leftRecursive.contains(rule.name())
                               ? Finding.Severity.WARNING
                               : Finding.Severity.INFO;
                var suffix = leftRecursive.contains(rule.name())
                             ? " (left-recursive path — risk of infinite loop)"
                             : "";
                findings.add(new Finding(
                severity,
                "grammar.nullable-rule",
                rule.name(),
                "rule '" + rule.name() + "' can match the empty string" + suffix));
            }
        }
        return findings;
    }

    private Map<String, Boolean> computeNullableFixedPoint() {
        var nullable = new HashMap<String, Boolean>();
        for (var rule : grammar.rules()) {
            nullable.put(rule.name(), false);
        }
        boolean changed = true;
        while (changed) {
            changed = false;
            for (var rule : grammar.rules()) {
                if (Boolean.TRUE.equals(nullable.get(rule.name()))) {
                    continue;
                }
                if (isNullable(rule.expression(), nullable)) {
                    nullable.put(rule.name(), true);
                    changed = true;
                }
            }
        }
        return nullable;
    }

    private static boolean isNullable(Expression expr, Map<String, Boolean> nullable) {
        return switch (expr) {
            case Expression.Literal lit -> lit.text()
                                              .isEmpty();
            case Expression.CharClass _ -> false;
            case Expression.Any _ -> false;
            case Expression.Reference ref -> Boolean.TRUE.equals(nullable.get(ref.ruleName()));
            case Expression.Sequence seq -> allNullable(seq.elements(), nullable);
            case Expression.Choice ch -> anyNullable(ch.alternatives(), nullable);
            case Expression.ZeroOrMore _ -> true;
            case Expression.OneOrMore oom -> isNullable(oom.expression(), nullable);
            case Expression.Optional _ -> true;
            case Expression.Repetition rep -> rep.min() == 0 || isNullable(rep.expression(), nullable);
            case Expression.And _ -> true;
            case Expression.Not _ -> true;
            case Expression.TokenBoundary tb -> isNullable(tb.expression(), nullable);
            case Expression.Ignore ig -> isNullable(ig.expression(), nullable);
            case Expression.Capture cap -> isNullable(cap.expression(), nullable);
            case Expression.CaptureScope cs -> isNullable(cs.expression(), nullable);
            case Expression.Group grp -> isNullable(grp.expression(), nullable);
            case Expression.BackReference _ -> false;
            case Expression.Dictionary _ -> false;
            case Expression.Cut _ -> true;
        };
    }

    private static boolean allNullable(List<Expression> elements, Map<String, Boolean> nullable) {
        for (var el : elements) {
            if (!isNullable(el, nullable)) {
                return false;
            }
        }
        return true;
    }

    private static boolean anyNullable(List<Expression> alternatives, Map<String, Boolean> nullable) {
        for (var alt : alternatives) {
            if (isNullable(alt, nullable)) {
                return true;
            }
        }
        return false;
    }

    private Set<String> computeDirectLeftRecursiveRules() {
        var result = new HashSet<String>();
        for (var rule : grammar.rules()) {
            if (isDirectLeftRecursive(rule)) {
                result.add(rule.name());
            }
        }
        return result;
    }

    private boolean isDirectLeftRecursive(Rule rule) {
        return firstExpressionCanReference(rule.expression(), rule.name(), new HashSet<>());
    }

    private boolean firstExpressionCanReference(Expression expr, String target, Set<String> visiting) {
        return switch (expr) {
            case Expression.Reference ref -> {
                if (ref.ruleName()
                       .equals(target)) {
                    yield true;
                }
                if (!visiting.add(ref.ruleName())) {
                    yield false;
                }
                var referenced = grammar.ruleMap()
                                        .get(ref.ruleName());
                yield referenced != null && firstExpressionCanReference(referenced.expression(), target, visiting);
            }
            case Expression.Sequence seq -> {
                for (var el : seq.elements()) {
                    if (el instanceof Expression.And || el instanceof Expression.Not) {
                        continue;
                    }
                    yield firstExpressionCanReference(el, target, visiting);
                }
                yield false;
            }
            case Expression.Choice ch -> {
                for (var alt : ch.alternatives()) {
                    if (firstExpressionCanReference(alt, target, visiting)) {
                        yield true;
                    }
                }
                yield false;
            }
            case Expression.Group grp -> firstExpressionCanReference(grp.expression(), target, visiting);
            case Expression.TokenBoundary tb -> firstExpressionCanReference(tb.expression(), target, visiting);
            case Expression.Ignore ig -> firstExpressionCanReference(ig.expression(), target, visiting);
            case Expression.Capture cap -> firstExpressionCanReference(cap.expression(), target, visiting);
            case Expression.CaptureScope cs -> firstExpressionCanReference(cs.expression(), target, visiting);
            default -> false;
        };
    }

    // === Check 4: Duplicate literals across alternatives ===

    private List<Finding> checkDuplicateLiterals() {
        var findings = new ArrayList<Finding>();
        for (var rule : grammar.rules()) {
            gatherDuplicateLiterals(rule, rule.expression(), findings);
        }
        return findings;
    }

    private void gatherDuplicateLiterals(Rule rule, Expression expr, List<Finding> findings) {
        if (expr instanceof Expression.Choice choice) {
            var seen = new HashMap<String, Integer>();
            var duplicates = new LinkedHashSet<String>();
            for (int i = 0; i < choice.alternatives()
                                      .size(); i++ ) {
                var alt = choice.alternatives()
                                .get(i);
                if (alt instanceof Expression.Literal lit) {
                    var key = lit.caseInsensitive()
                              ? "(?i)" + lit.text()
                              : lit.text();
                    if (seen.containsKey(key)) {
                        duplicates.add(lit.text());
                    } else {
                        seen.put(key, i);
                    }
                }
            }
            for (var dup : duplicates) {
                findings.add(Finding.error(
                "grammar.duplicate-literal",
                rule.name(),
                "rule '" + rule.name() + "' has duplicate literal '" + dup + "' in Choice"));
            }
        }
        // Recurse
        switch (expr) {
            case Expression.Sequence seq -> seq.elements()
                                               .forEach(e -> gatherDuplicateLiterals(rule, e, findings));
            case Expression.Choice ch -> ch.alternatives()
                                           .forEach(e -> gatherDuplicateLiterals(rule, e, findings));
            case Expression.ZeroOrMore zom -> gatherDuplicateLiterals(rule, zom.expression(), findings);
            case Expression.OneOrMore oom -> gatherDuplicateLiterals(rule, oom.expression(), findings);
            case Expression.Optional opt -> gatherDuplicateLiterals(rule, opt.expression(), findings);
            case Expression.Repetition rep -> gatherDuplicateLiterals(rule, rep.expression(), findings);
            case Expression.And and -> gatherDuplicateLiterals(rule, and.expression(), findings);
            case Expression.Not not -> gatherDuplicateLiterals(rule, not.expression(), findings);
            case Expression.TokenBoundary tb -> gatherDuplicateLiterals(rule, tb.expression(), findings);
            case Expression.Ignore ig -> gatherDuplicateLiterals(rule, ig.expression(), findings);
            case Expression.Capture cap -> gatherDuplicateLiterals(rule, cap.expression(), findings);
            case Expression.CaptureScope cs -> gatherDuplicateLiterals(rule, cs.expression(), findings);
            case Expression.Group grp -> gatherDuplicateLiterals(rule, grp.expression(), findings);
            default -> {
                // terminals
            }
        }
    }

    // === Check 5: Whitespace cycle ===

    private List<Finding> checkWhitespaceCycle() {
        var ws = grammar.whitespace();
        if (ws.isEmpty()) {
            return List.of();
        }
        var expr = ws.unwrap();
        var refs = collectReferences(expr);
        var visited = new HashSet<String>();
        for (var ref : refs) {
            if (isCyclic(ref, visited, new HashSet<>())) {
                return List.of(Finding.error(
                "grammar.whitespace-cycle",
                "",
                "%whitespace expression transitively references itself through rule '" + ref + "'"));
            }
        }
        return List.of();
    }

    private boolean isCyclic(String ruleName, Set<String> globallyVisited, Set<String> pathVisiting) {
        if (!pathVisiting.add(ruleName)) {
            return true;
        }
        if (!globallyVisited.add(ruleName)) {
            pathVisiting.remove(ruleName);
            return false;
        }
        var rule = grammar.ruleMap()
                          .get(ruleName);
        if (rule == null) {
            pathVisiting.remove(ruleName);
            return false;
        }
        for (var ref : collectReferences(rule.expression())) {
            if (isCyclic(ref, globallyVisited, pathVisiting)) {
                return true;
            }
        }
        pathVisiting.remove(ruleName);
        return false;
    }

    // === Check 6: BackReferences (forward-compat note) ===

    private List<Finding> checkBackReferences() {
        var findings = new ArrayList<Finding>();
        for (var rule : grammar.rules()) {
            if (containsBackReference(rule.expression())) {
                findings.add(Finding.info(
                "grammar.has-backreference",
                rule.name(),
                "rule '" + rule.name() + "' uses back-reference (incremental parsing will full-reparse this rule)"));
            }
        }
        return findings;
    }

    private static boolean containsBackReference(Expression expr) {
        return switch (expr) {
            case Expression.BackReference _ -> true;
            case Expression.Sequence seq -> seq.elements()
                                               .stream()
                                               .anyMatch(Analyzer::containsBackReference);
            case Expression.Choice ch -> ch.alternatives()
                                           .stream()
                                           .anyMatch(Analyzer::containsBackReference);
            case Expression.ZeroOrMore zom -> containsBackReference(zom.expression());
            case Expression.OneOrMore oom -> containsBackReference(oom.expression());
            case Expression.Optional opt -> containsBackReference(opt.expression());
            case Expression.Repetition rep -> containsBackReference(rep.expression());
            case Expression.And and -> containsBackReference(and.expression());
            case Expression.Not not -> containsBackReference(not.expression());
            case Expression.TokenBoundary tb -> containsBackReference(tb.expression());
            case Expression.Ignore ig -> containsBackReference(ig.expression());
            case Expression.Capture cap -> containsBackReference(cap.expression());
            case Expression.CaptureScope cs -> containsBackReference(cs.expression());
            case Expression.Group grp -> containsBackReference(grp.expression());
            default -> false;
        };
    }

}
