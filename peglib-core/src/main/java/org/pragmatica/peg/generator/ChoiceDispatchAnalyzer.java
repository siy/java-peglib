package org.pragmatica.peg.generator;

import org.pragmatica.peg.grammar.Expression;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Classifies a {@link Expression.Choice} for character-dispatch (§7.1) optimization.
 *
 * <p>A Choice is eligible when <b>every</b> alternative has a fixed literal prefix
 * reachable through a conservative set of transparent wrappers
 * ({@code Sequence}, {@code Group}, {@code TokenBoundary}, {@code Ignore},
 * {@code Capture}). Non-literal starters ({@code CharClass}, {@code Reference},
 * {@code Choice}, {@code Optional}, {@code ZeroOrMore}, {@code OneOrMore},
 * {@code Repetition}, {@code Any}, {@code BackReference}, {@code Cut}) disqualify
 * the whole choice.
 *
 * <p>If classification succeeds, {@link #groupByChar(List)} produces a LinkedHashMap
 * from dispatch char to the (still original-order) subset of alternatives whose
 * first char matches. Case-insensitive literals contribute both their upper and
 * lower case variants as dispatch keys; the raw character at {@code input.charAt(pos)}
 * is used as the switch value, so case-sensitive alternatives remain sound.
 */
final class ChoiceDispatchAnalyzer {
    record FirstChar(char c, boolean caseInsensitive) {}

    record AltEntry(int originalIndex, Expression alt, FirstChar firstChar) {}

    private ChoiceDispatchAnalyzer() {}

    /**
     * Classify a Choice. Returns all alt entries (preserving the original grammar order)
     * if every alternative is literal-prefixable; otherwise {@link Optional#empty()}.
     */
    static Optional<List<AltEntry>> classify(Expression.Choice choice) {
        var alternatives = choice.alternatives();
        if (alternatives.isEmpty()) {
            return Optional.empty();
        }
        var entries = new ArrayList<AltEntry>(alternatives.size());
        for (int i = 0; i < alternatives.size(); i++ ) {
            var alt = alternatives.get(i);
            var first = firstLiteral(alt);
            if (first == null) {
                return Optional.empty();
            }
            entries.add(new AltEntry(i, alt, first));
        }
        return Optional.of(List.copyOf(entries));
    }

    /**
     * Group classified entries by dispatch character. For case-insensitive first chars,
     * the entry is added under BOTH the lowercase and the uppercase char. Preserves
     * original grammar order within each bucket. Returned map is a {@link LinkedHashMap}
     * keyed on dispatch char; iteration order follows first-insertion of each char
     * (i.e. grammar order of the first alternative that contributes it).
     */
    static Map<Character, List<AltEntry>> groupByChar(List<AltEntry> entries) {
        var map = new LinkedHashMap<Character, List<AltEntry>>();
        for (var entry : entries) {
            char c = entry.firstChar()
                          .c();
            if (entry.firstChar()
                     .caseInsensitive()) {
                char lower = Character.toLowerCase(c);
                char upper = Character.toUpperCase(c);
                map.computeIfAbsent(lower,
                                    k -> new ArrayList<>())
                   .add(entry);
                if (upper != lower) {
                    map.computeIfAbsent(upper,
                                        k -> new ArrayList<>())
                       .add(entry);
                }
            }else {
                map.computeIfAbsent(c,
                                    k -> new ArrayList<>())
                   .add(entry);
            }
        }
        return map;
    }

    /** Compare two alt-lists by original-index sequence (order matters — grammar priority). */
    private static boolean sameAlts(List<AltEntry> a, List<AltEntry> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++ ) {
            if (a.get(i)
                 .originalIndex() != b.get(i)
                                      .originalIndex()) return false;
        }
        return true;
    }

    /**
     * A bucket of dispatch chars sharing an identical alternative list. The emitter
     * can produce a single {@code case 'a': case 'A': { ... }} block per bucket,
     * avoiding duplicate emission of alt bodies for case-insensitive literals.
     */
    record DispatchBucket(List<Character> chars, List<AltEntry> alts) {}

    /**
     * Coalesce {@link #groupByChar(List)} output into buckets where chars share an
     * identical alt-list (by original-index sequence). Each bucket maps a set of
     * dispatch chars to the alternatives that should be tried when any of those chars
     * is at {@code pos}. Bucket iteration order is stable (first-insertion per key).
     */
    static List<DispatchBucket> buckets(Map<Character, List<AltEntry>> grouped) {
        var assigned = new java.util.HashSet<Character>();
        var result = new ArrayList<DispatchBucket>();
        for (var entry : grouped.entrySet()) {
            if (assigned.contains(entry.getKey())) continue;
            var alts = entry.getValue();
            var chars = new ArrayList<Character>();
            chars.add(entry.getKey());
            assigned.add(entry.getKey());
            for (var other : grouped.entrySet()) {
                if (assigned.contains(other.getKey())) continue;
                if (sameAlts(other.getValue(), alts)) {
                    chars.add(other.getKey());
                    assigned.add(other.getKey());
                }
            }
            result.add(new DispatchBucket(List.copyOf(chars), List.copyOf(alts)));
        }
        return List.copyOf(result);
    }

    /**
     * Walk transparent wrappers to the first content element. If that element is a
     * {@link Expression.Literal} with non-empty text, return its dispatch char.
     * Otherwise return {@code null}.
     *
     * <p>Transparent wrappers: {@code Sequence} (first non-predicate element),
     * {@code Group}, {@code TokenBoundary}, {@code Ignore}, {@code Capture}.
     */
    private static FirstChar firstLiteral(Expression expr) {
        return switch (expr) {
            case Expression.Literal lit -> lit.text()
                                              .isEmpty()
                                           ? null
                                           : new FirstChar(lit.text()
                                                              .charAt(0),
                                                           lit.caseInsensitive());
            case Expression.Sequence seq -> firstLiteralInSequence(seq);
            case Expression.Group grp -> firstLiteral(grp.expression());
            case Expression.TokenBoundary tb -> firstLiteral(tb.expression());
            case Expression.Ignore ig -> firstLiteral(ig.expression());
            case Expression.Capture cap -> firstLiteral(cap.expression());
            default -> null;
        };
    }

    private static FirstChar firstLiteralInSequence(Expression.Sequence seq) {
        for (var el : seq.elements()) {
            if (el instanceof Expression.And || el instanceof Expression.Not) {
                continue;
            }
            return firstLiteral(el);
        }
        return null;
    }
}
