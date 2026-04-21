package org.pragmatica.peg.grammar.analysis;

import org.pragmatica.peg.grammar.Expression;
import org.pragmatica.peg.grammar.Grammar;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Shared first-character analysis helpers for PEG grammars.
 *
 * <p>Consumed by both {@code ParserGenerator} (generator-time emission) and
 * {@code PegEngine} (interpreter-time fast-path short-circuit). Moved here in
 * 0.2.3 as part of the phase-1 interpreter port so both paths agree on
 * first-char sets derived from the same {@code %whitespace} rule.
 *
 * <p>All methods are side-effect-free with respect to inputs other than the
 * caller-supplied accumulator sets.
 */
public final class FirstCharAnalysis {

    private FirstCharAnalysis() {}

    /**
     * Derive the set of characters that can legally start a whitespace-rule
     * match. Returns empty {@link Optional} when the shape of the expression
     * is not analyzable (caller must fall back to the always-slow path).
     *
     * @param grammar the grammar providing rule resolution for {@link Expression.Reference}s
     * @param expr the inner expression of the grammar's {@code %whitespace} rule
     *             (after stripping the outer {@code ZeroOrMore}/{@code OneOrMore})
     * @return the set of potential first chars, or empty if shape unsupported
     */
    public static Optional<Set<Character>> whitespaceFirstChars(Grammar grammar, Expression expr) {
        var set = new LinkedHashSet<Character>();
        if (!collectFirstChars(grammar, expr, set, new HashSet<>())) {
            return Optional.empty();
        }
        if (set.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(set);
    }

    /**
     * Recursive first-char collection with cycle detection.
     *
     * @param grammar grammar providing rule resolution (null disables
     *                reference dereferencing)
     * @param expr    the expression to analyze
     * @param out     accumulator for collected first chars
     * @param visiting set of rule names currently on the recursion stack, for
     *                 cycle detection
     * @return {@code true} if analysis succeeded; {@code false} if the shape is
     *         unsupported and callers must assume any char can start the
     *         expression
     */
    public static boolean collectFirstChars(Grammar grammar,
                                            Expression expr,
                                            Set<Character> out,
                                            Set<String> visiting) {
        return switch (expr) {
            case Expression.Literal lit -> collectFromLiteral(lit, out);
            case Expression.CharClass cc -> cc.negated()
                                            ? false
                                            : enumerateCharClass(cc.pattern(), cc.caseInsensitive(), out);
            case Expression.Choice ch -> collectFromChoice(grammar, ch, out, visiting);
            case Expression.Sequence seq -> collectFromSequence(grammar, seq, out, visiting);
            case Expression.Group grp -> collectFirstChars(grammar, grp.expression(), out, visiting);
            case Expression.ZeroOrMore zom -> collectFirstChars(grammar, zom.expression(), out, visiting);
            case Expression.OneOrMore oom -> collectFirstChars(grammar, oom.expression(), out, visiting);
            case Expression.Optional opt -> collectFirstChars(grammar, opt.expression(), out, visiting);
            case Expression.TokenBoundary tb -> collectFirstChars(grammar, tb.expression(), out, visiting);
            case Expression.Ignore ig -> collectFirstChars(grammar, ig.expression(), out, visiting);
            case Expression.Capture cap -> collectFirstChars(grammar, cap.expression(), out, visiting);
            case Expression.CaptureScope cs -> collectFirstChars(grammar, cs.expression(), out, visiting);
            case Expression.Reference ref -> collectFromReference(grammar, ref, out, visiting);
            default -> false;
        };
    }

    private static boolean collectFromLiteral(Expression.Literal lit, Set<Character> out) {
        if (lit.text().isEmpty()) {
            return false;
        }
        char first = lit.text().charAt(0);
        if (lit.caseInsensitive()) {
            out.add(Character.toLowerCase(first));
            out.add(Character.toUpperCase(first));
        } else {
            out.add(first);
        }
        return true;
    }

    private static boolean collectFromChoice(Grammar grammar,
                                              Expression.Choice ch,
                                              Set<Character> out,
                                              Set<String> visiting) {
        for (var alt : ch.alternatives()) {
            if (!collectFirstChars(grammar, alt, out, visiting)) {
                return false;
            }
        }
        return true;
    }

    private static boolean collectFromSequence(Grammar grammar,
                                                Expression.Sequence seq,
                                                Set<Character> out,
                                                Set<String> visiting) {
        for (var el : seq.elements()) {
            // skip over leading predicates - they don't consume
            if (el instanceof Expression.And || el instanceof Expression.Not) {
                continue;
            }
            return collectFirstChars(grammar, el, out, visiting);
        }
        return false;
    }

    private static boolean collectFromReference(Grammar grammar,
                                                 Expression.Reference ref,
                                                 Set<Character> out,
                                                 Set<String> visiting) {
        if (grammar == null) {
            return false;
        }
        if (!visiting.add(ref.ruleName())) {
            return false;
        }
        var target = grammar.rules()
                            .stream()
                            .filter(r -> r.name().equals(ref.ruleName()))
                            .findFirst();
        if (target.isEmpty()) {
            return false;
        }
        return collectFirstChars(grammar, target.get().expression(), out, visiting);
    }

    /**
     * Enumerates a character class pattern into explicit chars. Handles
     * escapes ({@code \n}, {@code \r}, {@code \t}, {@code \\}, {@code \]},
     * {@code \-}) and short ranges ({@code a-z}). Refuses ranges wider than
     * 128 characters (caller must fall back).
     *
     * @return {@code true} when fully enumerated; {@code false} if the pattern
     *         contains an unsupported escape or an overly wide range.
     */
    public static boolean enumerateCharClass(String pattern, boolean caseInsensitive, Set<Character> out) {
        int i = 0;
        while (i < pattern.length()) {
            char start = pattern.charAt(i);
            if (start == '\\' && i + 1 < pattern.length()) {
                char escaped = pattern.charAt(i + 1);
                char ch;
                int consumed = 2;
                switch (escaped) {
                    case 'n': ch = '\n'; break;
                    case 'r': ch = '\r'; break;
                    case 't': ch = '\t'; break;
                    case '\\': ch = '\\'; break;
                    case ']': ch = ']'; break;
                    case '-': ch = '-'; break;
                    default: return false;
                }
                addCaseInsensitive(out, ch, caseInsensitive);
                i += consumed;
                continue;
            }
            if (i + 2 < pattern.length() && pattern.charAt(i + 1) == '-') {
                char end = pattern.charAt(i + 2);
                if (end - start > 128) {
                    return false;
                }
                for (char c = start; c <= end; c++) {
                    addCaseInsensitive(out, c, caseInsensitive);
                }
                i += 3;
            } else {
                addCaseInsensitive(out, start, caseInsensitive);
                i++;
            }
        }
        return true;
    }

    /**
     * Adds {@code c} to {@code out}, also adding its opposite-case variant
     * when {@code caseInsensitive}.
     */
    public static void addCaseInsensitive(Set<Character> out, char c, boolean caseInsensitive) {
        if (caseInsensitive) {
            out.add(Character.toLowerCase(c));
            out.add(Character.toUpperCase(c));
        } else {
            out.add(c);
        }
    }
}
