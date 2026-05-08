package org.pragmatica.peg.generator;

import org.pragmatica.peg.grammar.Expression;
import org.pragmatica.peg.grammar.Grammar;
import org.pragmatica.peg.grammar.Rule;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Classifies a {@link Expression.Choice} for character-dispatch (§7.1) optimization.
 *
 * <p>Phase 1F (extended FIRST): a Choice is eligible when AT LEAST ONE alternative
 * has a known FIRST set computed via fixed-point analysis over the grammar. Alternatives
 * whose FIRST set cannot be determined (or that match {@link Expression.Any} or
 * {@link Expression.BackReference}) flow into a default fallback that runs in PEG order.
 *
 * <p>FIRST set computation (closed over the grammar):
 * <ul>
 *   <li>FIRST(Literal) = first char (with both cases when caseInsensitive).</li>
 *   <li>FIRST(CharClass) = chars matched by the class (ASCII range only; non-ASCII /
 *       negated → wildcard, disqualifies).</li>
 *   <li>FIRST(Reference R) = FIRST(rule R's expression) (fixed-point over grammar).</li>
 *   <li>FIRST(Sequence A B …) = FIRST(A) if A is non-nullable; ∪ FIRST(remainder) if A is
 *       nullable. {@link Expression.And} / {@link Expression.Not} are skipped.</li>
 *   <li>FIRST(Choice A | B | …) = ∪ FIRST(alts).</li>
 *   <li>FIRST(Optional / ZoM / OoM / Repetition) = FIRST(inner). Optional + ZoM + Rep(0,*) are
 *       nullable.</li>
 *   <li>FIRST(TokenBoundary / Group / Capture / CaptureScope / Ignore / Cut) = FIRST(inner)
 *       (transparent).</li>
 *   <li>FIRST(Any / BackReference / Dictionary) = WILDCARD (disqualifies single-char dispatch).</li>
 * </ul>
 *
 * <p>If classification succeeds, {@link #groupByChar(Classified)} produces a LinkedHashMap
 * from dispatch char to the (still original-order) subset of alternatives whose FIRST set
 * contains that char. The same alternative may live in multiple buckets (one per char in its
 * FIRST set). Wildcard alternatives (no known FIRST) populate the {@link Classified#defaults()}
 * list — they go in the {@code default:} branch of the dispatch switch in PEG order.
 *
 * <p>The pre-Phase-1F surface ({@link AltEntry}, {@link DispatchBucket}, {@link FirstChar},
 * {@code classify(Choice)}, {@code groupByChar(List)}, {@code buckets(Map)}) is preserved so
 * the existing emission path (literal-only choices) keeps working when invoked without a
 * grammar context.
 */
public final class ChoiceDispatchAnalyzer {
    /** Disqualifies dispatch — alternative matches anything. */
    private static final BitSet WILDCARD = new BitSet(0);

    public record FirstChar(char c, boolean caseInsensitive) {}

    public record AltEntry(int originalIndex, Expression alt, FirstChar firstChar) {}

    /** Bucket of dispatch chars sharing an identical alternative list. */
    public record DispatchBucket(List<Character> chars, List<AltEntry> alts) {}

    /**
     * Phase 1F result. {@code dispatched} maps a dispatch char to the alternatives whose FIRST
     * set covers it (preserving grammar order). {@code defaults} lists alternatives with no known
     * FIRST — they MUST be tried in PEG order in the {@code default:} branch when no dispatch
     * case fires AND can also serve as a fallback when a dispatch case fails.
     *
     * <p>Contract for emission soundness: for every alt at original-index i that participates in
     * dispatch, all alternatives j > i with j in {@code defaults} must be tried after i fails on
     * any matched dispatch char. We therefore emit defaults at the END of every case block whose
     * dispatched alts may not consume the input — preserving PEG ordered-choice semantics.
     */
    public record Classified(List<AltEntry> dispatched,
                             List<AltEntry> defaults,
                             Map<Character, List<AltEntry>> grouped) {}

    private ChoiceDispatchAnalyzer() {}

    // === Pre-Phase-1F surface (literal-only path) ===
    /**
     * Classify a Choice by literal-prefix only (legacy path, no grammar context). Returns all
     * alt entries (preserving original grammar order) if every alternative is literal-prefixable;
     * otherwise {@link Optional#empty()}.
     */
    public static Optional<List<AltEntry>> classify(Expression.Choice choice) {
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
     * Group classified entries by dispatch character (legacy path). Case-insensitive entries
     * are duplicated under both lowercase and uppercase. Preserves original grammar order
     * within each bucket.
     */
    public static Map<Character, List<AltEntry>> groupByChar(List<AltEntry> entries) {
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
     * Coalesce {@link #groupByChar(List)} output into buckets where chars share an identical
     * alt-list (by original-index sequence). Each bucket maps a set of dispatch chars to the
     * alternatives that should be tried when any of those chars is at {@code pos}. Bucket
     * iteration order is stable (first-insertion per key).
     */
    public static List<DispatchBucket> buckets(Map<Character, List<AltEntry>> grouped) {
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
     * Walk transparent wrappers to the first content element. If it's a non-empty
     * {@link Expression.Literal}, return its dispatch char. Otherwise null.
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

    // === Phase 1F: extended FIRST-set classification ===
    /**
     * Classify a Choice using grammar-wide FIRST-set analysis. Alternatives with a known
     * FIRST set are dispatched on every char in that set; alternatives with unknown FIRST
     * (wildcards) flow into the {@code defaults} list and run in PEG order under the
     * {@code default:} branch.
     *
     * <p>Returns {@link Optional#empty()} ONLY when no alternative has a known FIRST set
     * (i.e. dispatch would buy nothing — every alt would be in defaults).
     */
    public static Optional<Classified> classify(Expression.Choice choice, FirstSets firstSets) {
        var alternatives = choice.alternatives();
        if (alternatives.isEmpty()) {
            return Optional.empty();
        }
        var dispatched = new ArrayList<AltEntry>();
        var defaults = new ArrayList<AltEntry>();
        var grouped = new LinkedHashMap<Character, List<AltEntry>>();
        for (int i = 0; i < alternatives.size(); i++ ) {
            var alt = alternatives.get(i);
            var first = firstSets.firstOf(alt);
            // Wildcard FIRST = unknown / Any / BackReference / cycle-broken.
            // Such alts cannot be safely dispatched — they go to defaults.
            // ALSO: if the alternative is nullable (could match empty), it is wildcard-equivalent
            // because no input char can definitively select it.
            if (first == FirstSets.WILDCARD || firstSets.isNullable(alt)) {
                // PEG-ordering preservation: once an alternative goes to defaults, ALL subsequent
                // alternatives must also go to defaults — otherwise dispatch could try a later alt
                // before a wildcard earlier alt had its chance. Once we leak to defaults, we
                // accumulate all remaining alts in PEG order.
                var entry = new AltEntry(i, alt, null);
                defaults.add(entry);
                // Force all subsequent alts to defaults too:
                for (int j = i + 1; j < alternatives.size(); j++ ) {
                    defaults.add(new AltEntry(j, alternatives.get(j), null));
                }
                break;
            }
            // Known FIRST set — register under each char.
            var entry = new AltEntry(i, alt, null);
            dispatched.add(entry);
            // Populate grouped map with each char in FIRST(alt). For ASCII range we expand
            // BitSet bits to actual chars.
            for (int b = first.nextSetBit(0); b >= 0; b = first.nextSetBit(b + 1)) {
                char c = (char) b;
                grouped.computeIfAbsent(c,
                                        k -> new ArrayList<>())
                       .add(entry);
            }
        }
        // Need at least one dispatchable alt to bother with switch emission.
        if (dispatched.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new Classified(List.copyOf(dispatched), List.copyOf(defaults), unmodifiableLinked(grouped)));
    }

    private static Map<Character, List<AltEntry>> unmodifiableLinked(LinkedHashMap<Character, List<AltEntry>> in) {
        var out = new LinkedHashMap<Character, List<AltEntry>>();
        for (var e : in.entrySet()) {
            out.put(e.getKey(),
                    List.copyOf(e.getValue()));
        }
        return java.util.Collections.unmodifiableMap(out);
    }

    /**
     * Coalesce {@link Classified#grouped()} into buckets where chars share an identical alt-list.
     * Same coalescing rules as the legacy {@link #buckets(Map)} entry, but each bucket's alt-list
     * preserves PEG (original-index) order, and the per-bucket alt-list is a subset of the choice's
     * alternatives.
     */
    public static List<DispatchBucket> bucketsForClassified(Classified classified) {
        return buckets(classified.grouped());
    }

    // === FIRST-set computation ===
    /**
     * Pre-computed grammar-wide FIRST sets keyed by rule name. {@link #firstOf(Expression)}
     * returns FIRST for an arbitrary expression by walking it (using the cached rule sets).
     * {@link #isNullable(Expression)} returns whether the expression can match the empty input.
     */
    public static final class FirstSets {
        public static final BitSet WILDCARD = ChoiceDispatchAnalyzer.WILDCARD;

        /** Maximum fixed-point iteration safety bound. */
        private static final int MAX_ITERATIONS = 64;

        private final Map<String, BitSet> ruleFirst;
        private final Map<String, Boolean> ruleNullable;
        private final Map<String, Rule> rulesByName;

        private FirstSets(Map<String, BitSet> ruleFirst,
                          Map<String, Boolean> ruleNullable,
                          Map<String, Rule> rulesByName) {
            this.ruleFirst = ruleFirst;
            this.ruleNullable = ruleNullable;
            this.rulesByName = rulesByName;
        }

        /**
         * Compute FIRST and nullable sets for every rule in the grammar via fixed-point
         * iteration. ASCII-range only (0..127); any rule that can start with a non-ASCII char
         * (or {@link Expression.Any}/{@link Expression.BackReference}) gets the WILDCARD set.
         * Rules whose FIRST/nullable hasn't stabilized within {@link #MAX_ITERATIONS} are
         * conservatively marked WILDCARD + nullable.
         */
        public static FirstSets compute(Grammar grammar) {
            var rulesByName = new HashMap<String, Rule>();
            for (var r : grammar.rules()) {
                rulesByName.put(r.name(), r);
            }
            var first = new HashMap<String, BitSet>();
            var nullable = new HashMap<String, Boolean>();
            for (var r : grammar.rules()) {
                first.put(r.name(), new BitSet(128));
                nullable.put(r.name(), false);
            }
            // Provisional snapshot used during walk — readers see the partial table.
            var snapshot = new FirstSets(first, nullable, rulesByName);
            boolean changed = true;
            int iter = 0;
            while (changed && iter < MAX_ITERATIONS) {
                changed = false;
                iter++ ;
                for (var rule : grammar.rules()) {
                    var bsBefore = (BitSet) first.get(rule.name())
                                                .clone();
                    boolean nullBefore = nullable.get(rule.name());
                    var newFirst = snapshot.firstOf(rule.expression());
                    boolean newNull = snapshot.isNullable(rule.expression());
                    if (newFirst == WILDCARD) {
                        // Rule reachable as wildcard — fold the existing entry to all-bits-set
                        // sentinel reuses WILDCARD identity in the table for downstream.
                        first.put(rule.name(), WILDCARD);
                    }else {
                        var existing = first.get(rule.name());
                        if (existing == WILDCARD) {}else {
                            existing.or(newFirst);
                            if (!existing.equals(bsBefore)) changed = true;
                        }
                    }
                    if (newNull != nullBefore) {
                        nullable.put(rule.name(), newNull);
                        changed = true;
                    }
                }
            }
            // Iteration cap reached — any rule that didn't stabilize gets WILDCARD + nullable.
            // Detect by trying one more pass and seeing what would change.
            if (iter >= MAX_ITERATIONS) {
                for (var rule : grammar.rules()) {
                    var newFirst = snapshot.firstOf(rule.expression());
                    if (newFirst == WILDCARD) {
                        first.put(rule.name(), WILDCARD);
                    }
                }
            }
            return new FirstSets(java.util.Collections.unmodifiableMap(first),
                                 java.util.Collections.unmodifiableMap(nullable),
                                 java.util.Collections.unmodifiableMap(rulesByName));
        }

        /**
         * FIRST(expr): set of chars that can start a match. Returns {@link #WILDCARD} when
         * the expression can start with any char (Any, BackReference, unresolved Reference,
         * or non-ASCII content).
         */
        public BitSet firstOf(Expression expr) {
            return switch (expr) {
                case Expression.Literal lit -> firstOfLiteral(lit);
                case Expression.CharClass cc -> firstOfCharClass(cc);
                case Expression.Any __ -> WILDCARD;
                case Expression.BackReference __ -> WILDCARD;
                case Expression.Dictionary d -> firstOfDictionary(d);
                case Expression.Reference ref -> firstOfReference(ref);
                case Expression.Sequence seq -> firstOfSequence(seq);
                case Expression.Choice ch -> firstOfChoice(ch);
                case Expression.ZeroOrMore z -> firstOf(z.expression());
                case Expression.OneOrMore o -> firstOf(o.expression());
                case Expression.Optional o -> firstOf(o.expression());
                case Expression.Repetition r -> firstOf(r.expression());
                case Expression.And __ -> emptyBitSet();
                case Expression.Not __ -> emptyBitSet();
                case Expression.TokenBoundary tb -> firstOf(tb.expression());
                case Expression.Ignore ig -> firstOf(ig.expression());
                case Expression.Capture cap -> firstOf(cap.expression());
                case Expression.CaptureScope cs -> firstOf(cs.expression());
                case Expression.Group g -> firstOf(g.expression());
                case Expression.Cut __ -> emptyBitSet();
            };
        }

        /**
         * isNullable(expr): true if the expression can match the empty input.
         */
        public boolean isNullable(Expression expr) {
            return switch (expr) {
                case Expression.Literal lit -> lit.text()
                                                  .isEmpty();
                case Expression.CharClass __ -> false;
                case Expression.Any __ -> false;
                case Expression.BackReference __ -> false;
                case Expression.Dictionary d -> d.words()
                                                 .stream()
                                                 .anyMatch(String::isEmpty);
                case Expression.Reference ref -> {
                    var v = ruleNullable.get(ref.ruleName());
                    yield v != null && v;
                }
                case Expression.Sequence seq -> {
                    for (var el : seq.elements()) {
                        if (el instanceof Expression.And || el instanceof Expression.Not) continue;
                        if (el instanceof Expression.Cut) continue;
                        if (!isNullable(el)) yield false;
                    }
                    yield true;
                }
                case Expression.Choice ch -> {
                    for (var alt : ch.alternatives()) {
                        if (isNullable(alt)) yield true;
                    }
                    yield false;
                }
                case Expression.ZeroOrMore __ -> true;
                case Expression.OneOrMore o -> isNullable(o.expression());
                case Expression.Optional __ -> true;
                case Expression.Repetition r -> r.min() == 0 || isNullable(r.expression());
                case Expression.And __ -> true;
                case Expression.Not __ -> true;
                case Expression.TokenBoundary tb -> isNullable(tb.expression());
                case Expression.Ignore ig -> isNullable(ig.expression());
                case Expression.Capture cap -> isNullable(cap.expression());
                case Expression.CaptureScope cs -> isNullable(cs.expression());
                case Expression.Group g -> isNullable(g.expression());
                case Expression.Cut __ -> true;
            };
        }

        private BitSet firstOfLiteral(Expression.Literal lit) {
            if (lit.text()
                   .isEmpty()) return emptyBitSet();
            char c = lit.text()
                        .charAt(0);
            if (c >= 128) return WILDCARD;
            var bs = new BitSet(128);
            if (lit.caseInsensitive()) {
                char lo = Character.toLowerCase(c);
                char up = Character.toUpperCase(c);
                if (lo < 128) bs.set(lo);else return WILDCARD;
                if (up < 128) bs.set(up);else return WILDCARD;
            }else {
                bs.set(c);
            }
            return bs;
        }

        private BitSet firstOfCharClass(Expression.CharClass cc) {
            // Negated classes are wildcard for FIRST purposes (could match nearly any char).
            if (cc.negated()) return WILDCARD;
            var bs = new BitSet(128);
            // Parse pattern: a sequence of either single chars or ranges 'a-z'. Escapes:
            // \\ \] \[ \n \r \t \\ are common; we conservatively decode \X as X.
            String p = cc.pattern();
            int i = 0;
            while (i < p.length()) {
                char c1;
                if (p.charAt(i) == '\\' && i + 1 < p.length()) {
                    c1 = decodeEscape(p.charAt(i + 1));
                    i += 2;
                }else {
                    c1 = p.charAt(i);
                    i++ ;
                }
                // Range a-b?
                if (i < p.length() - 1 && p.charAt(i) == '-' && p.charAt(i + 1) != ']') {
                    char c2;
                    if (p.charAt(i + 1) == '\\' && i + 2 < p.length()) {
                        c2 = decodeEscape(p.charAt(i + 2));
                        i += 3;
                    }else {
                        c2 = p.charAt(i + 1);
                        i += 2;
                    }
                    int lo = Math.min(c1, c2);
                    int hi = Math.max(c1, c2);
                    if (hi >= 128) return WILDCARD;
                    for (int ch = lo; ch <= hi; ch++ ) {
                        bs.set(ch);
                        if (cc.caseInsensitive() && Character.isLetter(ch)) {
                            char up = Character.toUpperCase((char) ch);
                            char lo2 = Character.toLowerCase((char) ch);
                            if (up < 128) bs.set(up);
                            if (lo2 < 128) bs.set(lo2);
                        }
                    }
                }else {
                    if (c1 >= 128) return WILDCARD;
                    bs.set(c1);
                    if (cc.caseInsensitive() && Character.isLetter(c1)) {
                        char up = Character.toUpperCase(c1);
                        char lo2 = Character.toLowerCase(c1);
                        if (up < 128) bs.set(up);
                        if (lo2 < 128) bs.set(lo2);
                    }
                }
            }
            return bs;
        }

        private static char decodeEscape(char c) {
            return switch (c) {
                case'n' -> '\n';
                case'r' -> '\r';
                case't' -> '\t';
                case'0' -> '\0';
                case'b' -> '\b';
                case'f' -> '\f';
                default -> c;
            };
        }

        private BitSet firstOfDictionary(Expression.Dictionary d) {
            var bs = new BitSet(128);
            for (var w : d.words()) {
                if (w.isEmpty()) continue;
                char c = w.charAt(0);
                if (c >= 128) return WILDCARD;
                if (d.caseInsensitive()) {
                    char lo = Character.toLowerCase(c);
                    char up = Character.toUpperCase(c);
                    if (lo < 128) bs.set(lo);
                    if (up < 128) bs.set(up);
                }else {
                    bs.set(c);
                }
            }
            return bs;
        }

        private BitSet firstOfReference(Expression.Reference ref) {
            var bs = ruleFirst.get(ref.ruleName());
            if (bs == null) return WILDCARD;
            // unresolved reference
            return bs;
        }

        private BitSet firstOfSequence(Expression.Sequence seq) {
            var acc = new BitSet(128);
            for (var el : seq.elements()) {
                if (el instanceof Expression.And || el instanceof Expression.Not) continue;
                if (el instanceof Expression.Cut) continue;
                var f = firstOf(el);
                if (f == WILDCARD) return WILDCARD;
                acc.or(f);
                if (!isNullable(el)) return acc;
            }
            return acc;
        }

        private BitSet firstOfChoice(Expression.Choice ch) {
            var acc = new BitSet(128);
            for (var alt : ch.alternatives()) {
                var f = firstOf(alt);
                if (f == WILDCARD) return WILDCARD;
                acc.or(f);
            }
            return acc;
        }

        private static BitSet emptyBitSet() {
            return new BitSet(128);
        }

        // Visible for tests
        BitSet ruleFirstFor(String name) {
            return ruleFirst.get(name);
        }

        boolean ruleNullableFor(String name) {
            return Boolean.TRUE.equals(ruleNullable.get(name));
        }
    }

    // Visible for tests
    static Set<String> referencedRuleNames(Expression expr) {
        var out = new LinkedHashSet<String>();
        collectRefs(expr, out);
        return out;
    }

    private static void collectRefs(Expression expr, Set<String> out) {
        switch (expr) {
            case Expression.Reference r -> out.add(r.ruleName());
            case Expression.Sequence s -> s.elements()
                                           .forEach(e -> collectRefs(e, out));
            case Expression.Choice c -> c.alternatives()
                                         .forEach(e -> collectRefs(e, out));
            case Expression.ZeroOrMore z -> collectRefs(z.expression(), out);
            case Expression.OneOrMore o -> collectRefs(o.expression(), out);
            case Expression.Optional o -> collectRefs(o.expression(), out);
            case Expression.Repetition r -> collectRefs(r.expression(), out);
            case Expression.And a -> collectRefs(a.expression(), out);
            case Expression.Not n -> collectRefs(n.expression(), out);
            case Expression.TokenBoundary t -> collectRefs(t.expression(), out);
            case Expression.Ignore i -> collectRefs(i.expression(), out);
            case Expression.Capture c -> collectRefs(c.expression(), out);
            case Expression.CaptureScope c -> collectRefs(c.expression(), out);
            case Expression.Group g -> collectRefs(g.expression(), out);
            default -> {}
        }
    }
}
