package org.pragmatica.peg.incremental.internal;

import org.pragmatica.peg.grammar.Expression;
import org.pragmatica.peg.grammar.Grammar;

import java.util.HashSet;
import java.util.Set;

/**
 * Phase 2 (v0.5.0) — Lever B static analysis: determine which grammar rules are
 * <em>safe to use as a pivot</em> for incremental {@code parseRuleAt} reparse.
 *
 * <p>A rule is "safe" only when re-entering it at an absolute buffer offset —
 * with a freshly allocated parser context — produces the same parse it would
 * produce as part of the full parse. That holds when:
 * <ol>
 *   <li>The rule does NOT depend on captures that may be set outside its own
 *       span. {@link BackReferenceScan#unsafeRules(Grammar)} already excludes
 *       any rule that (transitively) uses a {@link Expression.BackReference}.</li>
 *   <li>The rule has an UNAMBIGUOUS LITERAL prefix — its first consuming
 *       expression is a non-empty {@link Expression.Literal}. This guards
 *       against accidentally pivoting on a rule whose entry could match anywhere
 *       (e.g., a rule that begins with another rule reference or a quantifier
 *       that consumes nothing).</li>
 * </ol>
 *
 * <p>This analyzer mirrors {@link BackReferenceScan} in shape: a single static
 * entry point that takes a {@link Grammar} and returns an immutable
 * {@link Set} of rule names. The result is computed once per session-factory
 * and stored on {@link SessionFactory}.
 *
 * <h2>Conservatism</h2>
 *
 * <p>False negatives (excluding a rule that would in fact be safe) cost
 * performance — the boundary algorithm has to walk one level higher, possibly
 * to the root, which then triggers full reparse. False positives (admitting a
 * rule that is actually unsafe) cost correctness — the spliced subtree may
 * disagree with the oracle and parity tests will catch it. This analyzer
 * deliberately biases toward conservative: only an unambiguous, non-empty
 * leading {@link Expression.Literal} qualifies. Choices, quantifiers,
 * character classes, and rule references at the front are all rejected.
 *
 * @since 0.5.0
 */
public final class SafePivotAnalyzer {
    private SafePivotAnalyzer() {}

    /**
     * Compute the set of rule names that are safe to use as incremental
     * reparse pivots in {@code grammar}. A rule is safe iff:
     * <ol>
     *   <li>it is NOT in {@link BackReferenceScan#unsafeRules(Grammar)}, AND</li>
     *   <li>its expression begins with an UNAMBIGUOUS, NON-EMPTY literal
     *       prefix — see {@link #hasUnambiguousLiteralPrefix(Expression)}.</li>
     * </ol>
     *
     * <p>Phase 2 (v0.5.0) — Lever B: the literal-prefix gate is the
     * structural correctness guarantee for isolated reparse. PEG ordered
     * choice is context-sensitive: a deeper rule reparsed at an absolute
     * offset can succeed with the same end-offset but match a DIFFERENT
     * alternative than the same rule embedded in a larger context. A
     * non-empty literal at the front anchors the rule's identity — when the
     * input at the offset matches the literal, isolated parse and contextual
     * parse necessarily agree on which alternative to take.
     *
     * <p>Rules that fail the literal-prefix test (start with a rule
     * reference, character class, choice, or quantifier) are excluded. The
     * boundary algorithm then walks UP the spine to find a safe ancestor;
     * this trades pivot depth for parity — the resulting pivot is sometimes
     * larger (more {@link TreeSplicer#shiftAll} work) but is provably
     * isolated-parse-equivalent to the contextual parse.
     *
     * <p>Empirically (Phase 2 IncrementalSessionBench / IncrementalParityTest):
     * permissive variants that admit non-literal-prefixed rules pick smaller
     * but context-sensitive pivots, breaking
     * {@link org.pragmatica.peg.incremental.IncrementalParityTest}. The strict
     * literal-prefix gate is the only filter we have validated to keep parity.
     */
    public static Set<String> safePivotRules(Grammar grammar) {
        var unsafe = BackReferenceScan.unsafeRules(grammar);
        var result = new HashSet<String>();
        for (var rule : grammar.rules()) {
            if (unsafe.contains(rule.name())) {
                continue;
            }
            if (hasUnambiguousLiteralPrefix(rule.expression())) {
                result.add(rule.name());
            }
        }
        return Set.copyOf(result);
    }

    /**
     * {@code true} iff walking the leftmost path of {@code expr} bottoms out at
     * a non-empty {@link Expression.Literal}. Transparent wrappers ({@link
     * Expression.Group}, {@link Expression.TokenBoundary}, {@link
     * Expression.Capture}, {@link Expression.CaptureScope}) are unwrapped;
     * {@link Expression.Sequence} recurses into its first element. Everything
     * else — choices, quantifiers (which may consume nothing), references,
     * character classes, predicates — fails the test.
     */
    private static boolean hasUnambiguousLiteralPrefix(Expression expr) {
        return switch (expr) {
            case Expression.Literal lit -> !lit.text().isEmpty();
            case Expression.Sequence seq -> !seq.elements().isEmpty()
                                            && hasUnambiguousLiteralPrefix(seq.elements().get(0));
            case Expression.Group grp -> hasUnambiguousLiteralPrefix(grp.expression());
            case Expression.TokenBoundary tb -> hasUnambiguousLiteralPrefix(tb.expression());
            case Expression.Capture cap -> hasUnambiguousLiteralPrefix(cap.expression());
            case Expression.CaptureScope cs -> hasUnambiguousLiteralPrefix(cs.expression());
            // Conservative-no for everything else:
            //   - Reference, CharClass, Any: ambiguous start (could match at any rule)
            //   - Choice: alternatives may not all start with the same literal
            //   - Optional, ZeroOrMore: rule could consume nothing
            //   - OneOrMore, Repetition: bounds permit non-literal inner expressions
            //   - And, Not: predicates do not consume input themselves
            //   - Ignore: wraps something whose match is discarded; treat as ambiguous
            //   - BackReference, Dictionary, Cut: not literal-prefixed in the strict sense
            default -> false;
        };
    }
}
