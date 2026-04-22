package org.pragmatica.peg.grammar;

import org.pragmatica.lang.Result;
import org.pragmatica.peg.error.ParseError;
import org.pragmatica.peg.tree.SourceLocation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 0.2.8 — Resolves {@code %import} directives by inlining imported rules
 * (plus their transitive references) into the root grammar. Surface-level
 * composition: the composed grammar shares the root's {@code %whitespace}
 * binding; imported grammars' own whitespace rules are dropped.
 *
 * <p>Semantics:
 * <ul>
 *   <li><b>Transitive closure.</b> When {@code %import G.R} is resolved, {@code R}
 *       plus every rule reachable from {@code R} in grammar {@code G} is pulled
 *       into the composed grammar. Transitive rules are renamed to
 *       {@code G_OriginalName}; the explicitly-imported rule is renamed to
 *       either {@code G_R} (no alias) or the {@code as}-supplied local name.</li>
 *   <li><b>Root shadows transitive imports by name.</b> If the root grammar
 *       already defines a rule whose name collides with a transitively-pulled
 *       rule (before prefixing), the root wins silently — we skip the imported
 *       version. Explicit {@code %import} targets that collide with a root rule
 *       name require an {@code as} rename or an error is raised.</li>
 *   <li><b>Cycle detection.</b> A cycle among grammar imports (A→B→A) is a hard
 *       error reported at resolve time, not at parse time.</li>
 *   <li><b>RuleId naming.</b> Root rules keep their names unchanged; explicit
 *       imports use the local name ({@code G_R} or {@code as}-alias);
 *       transitive imports use {@code G_OriginalName}.</li>
 * </ul>
 */
public final class GrammarResolver {
    private final GrammarSource source;
    private final Map<String, Grammar> loadedGrammars = new HashMap<>();

    private GrammarResolver(GrammarSource source) {
        this.source = source;
    }

    /**
     * Resolve a root {@code Grammar} — if it has no imports, returns it
     * unchanged; otherwise composes all imported rules into it.
     */
    public static Result<Grammar> resolve(Grammar root, GrammarSource source) {
        if (root.imports()
                .isEmpty()) {
            return Result.success(root);
        }
        return new GrammarResolver(source).resolveRoot(root);
    }

    /**
     * Convenience — parse the root grammar text, then resolve imports.
     */
    public static Result<Grammar> resolveText(String rootText, GrammarSource source) {
        return GrammarParser.parse(rootText)
                            .flatMap(g -> resolve(g, source));
    }

    private Result<Grammar> resolveRoot(Grammar root) {
        var rootRuleNames = new HashSet<String>();
        for (var r : root.rules()) {
            rootRuleNames.add(r.name());
        }
        // Composed rule list starts with root rules (unchanged).
        var composedRules = new LinkedHashMap<String, Rule>();
        for (var r : root.rules()) {
            composedRules.put(r.name(), r);
        }
        for (var imp : root.imports()) {
            // Check explicit collision with a root-defined rule name. Note that the
            // default local name is G_R (grammar-qualified) so an unaliased import
            // of G.R only collides with a root rule literally named "G_R".
            var localName = imp.localName();
            if (rootRuleNames.contains(localName)) {
                return new ParseError.SemanticError(
                imp.span()
                   .start(),
                "Import name collision: '" + localName
                + "' is already defined in the root grammar; add 'as <LocalName>' to rename").result();
            }
            if (composedRules.containsKey(localName)) {
                // Two imports resolved to the same local name — a hard error.
                return new ParseError.SemanticError(
                imp.span()
                   .start(),
                "Import name collision: '" + localName + "' already imported; use different aliases").result();
            }
            var importedGrammar = loadGrammarOrFail(imp.grammarName(),
                                                    imp.span()
                                                       .start(),
                                                    List.of(imp.grammarName()));
            if (importedGrammar instanceof Result.Failure<Grammar> f) {
                return f.cause().result();
            }
            var g = importedGrammar.unwrap();
            // Detect cycle in the imports of the imported grammar, starting from
            // the importer's own grammar name. We walk %import declarations
            // without actually inlining them — we only care that the same
            // grammar name isn't reachable from itself.
            var cycleCheck = detectCycle(g,
                                         new ArrayList<>(List.of(imp.grammarName())));
            if (cycleCheck instanceof Result.Failure<Boolean> f) {
                return f.cause().result();
            }
            // Build transitive closure of rule names needed from this imported grammar.
            var ruleMap = g.ruleMap();
            if (!ruleMap.containsKey(imp.ruleName())) {
                return new ParseError.SemanticError(
                imp.span()
                   .start(),
                "Imported rule '" + imp.ruleName() + "' not found in grammar '" + imp.grammarName() + "'").result();
            }
            var closure = new LinkedHashSet<String>();
            collectClosure(imp.ruleName(), ruleMap, closure);
            // For each rule in the closure: rename to prefixed form (or alias for the root target),
            // rewrite references, and add to the composed rule list iff the root doesn't already
            // define a rule by that prefixed name.
            for (var originalName : closure) {
                var rule = ruleMap.get(originalName);
                String renamedName;
                if (originalName.equals(imp.ruleName())) {
                    renamedName = localName;
                }else {
                    renamedName = Import.prefixedName(imp.grammarName(), originalName);
                }
                // Root shadows transitive imports by prefixed-name too (unlikely
                // collision, but the rule is: root entries in composedRules
                // never get overwritten).
                if (composedRules.containsKey(renamedName)) {
                    // Don't overwrite. This preserves "root wins silently" for transitives,
                    // and prevents two imports from one grammar both pulling in the same
                    // transitive rule twice.
                    continue;
                }
                var rewritten = rewriteReferences(rule,
                                                  renamedName,
                                                  ref -> {
                                                      // References within the imported grammar → prefix (or alias for the target).
                if (ref.equals(imp.ruleName())) {
                                                      return localName;
                                                  }
                                                      if (ruleMap.containsKey(ref)) {
                                                      return Import.prefixedName(imp.grammarName(), ref);
                                                  }
                                                      // Reference leaves the imported grammar (unknown) — keep as-is; the
                // composed grammar's validate() will catch it.
                return ref;
                                                  });
                composedRules.put(renamedName, rewritten);
            }
        }
        // Produce composed Grammar. imports are cleared (surface-level composition is complete).
        return Result.success(new Grammar(
        new ArrayList<>(composedRules.values()),
        root.startRule(),
        root.whitespace(),
        root.word(),
        root.suggestRules(),
        List.of()));
    }

    private Result<Grammar> loadGrammarOrFail(String grammarName, SourceLocation errorLocation, List<String> chain) {
        var cached = loadedGrammars.get(grammarName);
        if (cached != null) {
            return Result.success(cached);
        }
        var loaded = source.load(grammarName);
        if (loaded.isEmpty()) {
            return new ParseError.SemanticError(
            errorLocation,
            "Grammar '" + grammarName + "' not found via configured GrammarSource" + " (import chain: " + String.join(" -> ",
                                                                                                                      chain)
            + ")").result();
        }
        var parsed = GrammarParser.parse(loaded.unwrap());
        if (parsed instanceof Result.Failure<Grammar> f) {
            return f.cause().result();
        }
        var g = parsed.unwrap();
        loadedGrammars.put(grammarName, g);
        return Result.success(g);
    }

    /**
     * Detect a cycle by walking imports of {@code g}. If any import in {@code g}
     * references a grammar name already on the chain, that's a cycle.
     */
    private Result<Boolean> detectCycle(Grammar g, List<String> chain) {
        for (var imp : g.imports()) {
            if (chain.contains(imp.grammarName())) {
                return new ParseError.SemanticError(
                imp.span()
                   .start(),
                "Cyclic grammar import detected: " + String.join(" -> ", chain) + " -> " + imp.grammarName()).result();
            }
            var loaded = loadGrammarOrFail(imp.grammarName(),
                                           imp.span()
                                              .start(),
                                           appendChain(chain, imp.grammarName()));
            if (loaded instanceof Result.Failure<Grammar> f) {
                return f.cause().result();
            }
            var newChain = appendChain(chain, imp.grammarName());
            var sub = detectCycle(loaded.unwrap(), newChain);
            if (sub instanceof Result.Failure<Boolean> f) {
                return f.cause().result();
            }
        }
        return Result.success(true);
    }

    private static List<String> appendChain(List<String> chain, String name) {
        var out = new ArrayList<>(chain);
        out.add(name);
        return out;
    }

    /**
     * Collect the transitive closure of rule names starting at {@code rootRule},
     * following every {@link Expression.Reference} that resolves within the
     * imported grammar. References that don't resolve are left for the final
     * validate() pass to flag.
     */
    private static void collectClosure(String rootRule, Map<String, Rule> ruleMap, Set<String> acc) {
        if (!acc.add(rootRule)) {
            return;
        }
        var rule = ruleMap.get(rootRule);
        if (rule == null) {
            return;
        }
        collectReferencedRules(rule.expression(), ruleMap, acc);
    }

    private static void collectReferencedRules(Expression expr, Map<String, Rule> ruleMap, Set<String> acc) {
        switch (expr) {
            case Expression.Reference ref -> {
                if (ruleMap.containsKey(ref.ruleName()) && acc.add(ref.ruleName())) {
                    collectReferencedRules(ruleMap.get(ref.ruleName())
                                                  .expression(),
                                           ruleMap,
                                           acc);
                }
            }
            case Expression.Sequence seq -> {
                for (var e : seq.elements()) {
                    collectReferencedRules(e, ruleMap, acc);
                }
            }
            case Expression.Choice ch -> {
                for (var e : ch.alternatives()) {
                    collectReferencedRules(e, ruleMap, acc);
                }
            }
            case Expression.ZeroOrMore z -> collectReferencedRules(z.expression(), ruleMap, acc);
            case Expression.OneOrMore o -> collectReferencedRules(o.expression(), ruleMap, acc);
            case Expression.Optional o -> collectReferencedRules(o.expression(), ruleMap, acc);
            case Expression.Repetition r -> collectReferencedRules(r.expression(), ruleMap, acc);
            case Expression.And a -> collectReferencedRules(a.expression(), ruleMap, acc);
            case Expression.Not n -> collectReferencedRules(n.expression(), ruleMap, acc);
            case Expression.TokenBoundary t -> collectReferencedRules(t.expression(), ruleMap, acc);
            case Expression.Ignore i -> collectReferencedRules(i.expression(), ruleMap, acc);
            case Expression.Capture c -> collectReferencedRules(c.expression(), ruleMap, acc);
            case Expression.CaptureScope c -> collectReferencedRules(c.expression(), ruleMap, acc);
            case Expression.Group g -> collectReferencedRules(g.expression(), ruleMap, acc);
            case Expression.Literal _, Expression.CharClass _, Expression.Any _,
            Expression.BackReference _, Expression.Dictionary _, Expression.Cut _ -> {}
        }
    }

    /**
     * Rewrite all {@link Expression.Reference} nodes in {@code rule}'s
     * expression by mapping their referenced names through {@code mapper}.
     * The rule itself is renamed to {@code newName}.
     * Action text, error messages, and directives are copied unchanged.
     */
    private static Rule rewriteReferences(Rule rule,
                                          String newName,
                                          java.util.function.Function<String, String> mapper) {
        var newExpr = rewriteExpr(rule.expression(), mapper);
        return new Rule(rule.span(),
                        newName,
                        newExpr,
                        rule.action(),
                        rule.errorMessage(),
                        rule.expected(),
                        rule.recover(),
                        rule.tag());
    }

    private static Expression rewriteExpr(Expression expr, java.util.function.Function<String, String> mapper) {
        return switch (expr) {
            case Expression.Reference ref -> new Expression.Reference(ref.span(),
                                                                      mapper.apply(ref.ruleName()));
            case Expression.Sequence seq -> new Expression.Sequence(seq.span(),
                                                                    seq.elements()
                                                                       .stream()
                                                                       .map(e -> rewriteExpr(e, mapper))
                                                                       .toList());
            case Expression.Choice ch -> new Expression.Choice(ch.span(),
                                                               ch.alternatives()
                                                                 .stream()
                                                                 .map(e -> rewriteExpr(e, mapper))
                                                                 .toList());
            case Expression.ZeroOrMore z -> new Expression.ZeroOrMore(z.span(), rewriteExpr(z.expression(), mapper));
            case Expression.OneOrMore o -> new Expression.OneOrMore(o.span(), rewriteExpr(o.expression(), mapper));
            case Expression.Optional o -> new Expression.Optional(o.span(), rewriteExpr(o.expression(), mapper));
            case Expression.Repetition r -> new Expression.Repetition(r.span(),
                                                                      rewriteExpr(r.expression(), mapper),
                                                                      r.min(),
                                                                      r.max());
            case Expression.And a -> new Expression.And(a.span(), rewriteExpr(a.expression(), mapper));
            case Expression.Not n -> new Expression.Not(n.span(), rewriteExpr(n.expression(), mapper));
            case Expression.TokenBoundary t -> new Expression.TokenBoundary(t.span(),
                                                                            rewriteExpr(t.expression(), mapper));
            case Expression.Ignore i -> new Expression.Ignore(i.span(), rewriteExpr(i.expression(), mapper));
            case Expression.Capture c -> new Expression.Capture(c.span(), c.name(), rewriteExpr(c.expression(), mapper));
            case Expression.CaptureScope c -> new Expression.CaptureScope(c.span(), rewriteExpr(c.expression(), mapper));
            case Expression.Group g -> new Expression.Group(g.span(), rewriteExpr(g.expression(), mapper));
            // Terminals — no inner refs
            case Expression.Literal l -> l;
            case Expression.CharClass c -> c;
            case Expression.Any a -> a;
            case Expression.BackReference b -> b;
            case Expression.Dictionary d -> d;
            case Expression.Cut c -> c;
        };
    }
}
