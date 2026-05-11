package org.pragmatica.peg.v6.lexer;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.peg.grammar.Expression;
import org.pragmatica.peg.grammar.Grammar;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Phase A.3 — build a {@link Dfa} from the LEXER-classified rules of a grammar.
 *
 * <h2>Algorithm</h2>
 *
 * <ol>
 *   <li><b>Token-kind allocation.</b> User-defined LEXER rules in source order
 *       receive kinds starting at {@link #FIRST_USER_KIND}. The grammar's
 *       {@code %whitespace} body (when present) is added as kind
 *       {@link #KIND_WHITESPACE}.</li>
 *   <li><b>Inline-literal extraction (Phase A.5).</b> Walk every PARSER and
 *       MIXED rule, collect every unique {@link Expression.Literal} node
 *       (deduplicated by {@code (text, caseInsensitive)}), and synthesise an
 *       anonymous lexer rule per literal. Synthetic rules win over user
 *       LEXER rules on tie (lower priority value).</li>
 *   <li><b>ANY_CHAR fallback.</b> A reserved single-character lexer rule with
 *       the lowest priority. Ensures byte-level round-trip on inputs that
 *       contain characters not covered by any explicit or inline rule
 *       (Phase A safety net; tightened in Phase B).</li>
 *   <li><b>Thompson NFA.</b> Each rule's expression is compiled into an NFA
 *       fragment with epsilon transitions and char-class edges. A global start
 *       state has an epsilon edge into each rule's start; each rule's accept
 *       state remembers its kind ID + priority.</li>
 *   <li><b>Subset construction.</b> Powerset construction on the combined NFA
 *       produces the DFA. A DFA state is accepting if any NFA state in its
 *       set is accepting; ties are broken by lowest priority value
 *       (= rule defined earliest, matching PEG first-match-wins).</li>
 *   <li><b>Minimization.</b> Deferred for Phase A. The non-minimized DFA is
 *       emitted directly.</li>
 * </ol>
 */
public final class DfaBuilder {
    public static final int KIND_WHITESPACE = 0;
    public static final int KIND_LINE_COMMENT = 1;
    public static final int KIND_BLOCK_COMMENT = 2;
    public static final int FIRST_USER_KIND = 3;

    private static final int ALPHABET = Dfa.ALPHABET_SIZE;
    private static final int REPETITION_CAP = 256;

    private DfaBuilder() {}

    /**
     * Phase B.0 — keyword resolution table for an identifier-shaped LEXER rule
     * whose grammar source was {@code !KeywordSet body}. The lexer engine first
     * matches the body, then consults this map to remap the token kind from the
     * generic identifier kind to a specific keyword kind when the matched text
     * is a known keyword.
     *
     * @param identKind   token kind allocated to the identifier-shaped rule
     * @param textToKind  matched text → keyword kind (existing inline-literal kind,
     *                    existing dedicated KW rule kind, or a freshly synthesised kind)
     */
    public record KeywordResolution(int identKind, Map<String, Integer> textToKind){}

    /**
     * Phase B.5 — token kind assignment, extended with rule-to-alias mapping.
     *
     * @param ruleNameToAliasKinds rule name → set of acceptable token kinds for that
     *        rule. Populated when a LEXER/MIXED rule's body simplifies to a single
     *        literal or a choice-of-literals (after stripping wrappers and the
     *        trailing word-boundary {@code !CharClass} guard). Such rules are not
     *        compiled as standalone DFA accepts: instead the parser accepts any
     *        token kind in the alias set when it sees a {@code Reference} to that
     *        rule. The alias kinds reuse the inline-literal kinds emitted by the
     *        lexer for the corresponding text.
     * @param identifierFallbackKinds identifier-rule name → sorted array of inline-literal
     *        token kinds whose matched text is identifier-shaped (matches the regex
     *        {@code [a-zA-Z_$][a-zA-Z0-9_$]*}) but is NOT in the hard-keyword set
     *        referenced by the identifier rule's {@code !Keyword} skip-prefix head.
     *        Generated parser code that consumes a token of the identifier rule
     *        also accepts any kind in this fallback set — this preserves the
     *        "contextual keywords fall through to Identifier elsewhere" behavior
     *        that the 0.5.x interpreter got for free via PEG ordered choice.
     */
    public record TokenKindAssignment(Map<String, Integer> ruleNameToKind,
                                      Map<String, Integer> inlineLiteralToKind,
                                      Map<Integer, KeywordResolution> keywordResolutions,
                                      Map<String, int[]> ruleNameToAliasKinds,
                                      Map<String, int[]> identifierFallbackKinds,
                                      int anyCharKind,
                                      String[] kindNameTable){}

    public record SkippedRule(String ruleName, String reason){}

    public record Built(Dfa dfa, TokenKindAssignment kinds, List<SkippedRule> skipped){}

    public sealed interface DfaBuildError extends Cause {
        record UnsupportedExpression(String ruleName, String expressionKind, String detail) implements DfaBuildError {
            @Override public String message() {
                return "Cannot compile rule '" + ruleName + "' to DFA: unsupported expression kind " + expressionKind + " (" + detail + ")";
            }
        }

        record NoLexerRules() implements DfaBuildError {
            @Override public String message() {
                return "Grammar has no LEXER-classified rules and no inline literals; nothing to compile to DFA";
            }
        }
    }

    public static Result<Built> build(Grammar grammar, RuleClassifier.Classification classification) {
        var inlineLiterals = extractInlineLiterals(grammar, classification);
        var aliasLiterals = new ArrayList<InlineLiteral>();
        return assignKinds(grammar, classification, inlineLiterals, aliasLiterals)
        .flatMap(assignment -> {
                     var skipped = new ArrayList<SkippedRule>();
                     // Phase B.5 — alias-detected rules (e.g. ReturnKW <- < 'return' ![..] >)
        // had their bodies absorbed as new inline literals during assignKinds.
        // Their NFA accept states must be added so the lexer can actually emit
        // the corresponding INLINE_<text> kinds. Without this step the kind
        // table contains the names but no DFA path produces them, and the
        // generated parser's alias-match check would never see a matching
        // token kind (the lexer would fall back to either the unaliased
        // *KW kind via keyword resolution or to the catch-all ANY_CHAR /
        // Identifier path, neither of which is in the alias array).
        var combinedLiterals = new ArrayList<InlineLiteral>(inlineLiterals.size() + aliasLiterals.size());
                     combinedLiterals.addAll(inlineLiterals);
                     combinedLiterals.addAll(aliasLiterals);
                     var nfa = buildNfaWithSkips(grammar, classification, assignment, combinedLiterals, skipped);
                     return Result.success(new Built(subsetConstruction(nfa), assignment, List.copyOf(skipped)));
                 });
    }

    /**
     * Walk every PARSER- and MIXED-classified rule and collect every distinct
     * {@link Expression.Literal} node. Insertion order = first-occurrence in
     * source order. Deduplication key is {@code (text, caseInsensitive)} —
     * the same text with a different case-sensitivity flag is a different
     * inline token.
     */
    private static List<InlineLiteral> extractInlineLiterals(Grammar grammar,
                                                             RuleClassifier.Classification classification) {
        var seen = new LinkedHashMap<String, InlineLiteral>();
        for ( var rule : grammar.rules()) {
            var kind = classification.kinds().get(rule.name());
            if ( kind == RuleKind.PARSER || kind == RuleKind.MIXED) {
            collectLiterals(rule.expression(), seen);}
        }
        var ordered = new ArrayList<>(seen.values());
        ordered.sort((a, b) -> {
                         int byLen = Integer.compare(b.text.length(), a.text.length());
                         if ( byLen != 0) {
        return byLen;}
                         return Integer.compare(a.firstOccurrence, b.firstOccurrence);
                     });
        return ordered;
    }

    private static void collectLiterals(Expression expr, LinkedHashMap<String, InlineLiteral> seen) {
        switch ( expr) {
            case Expression.Literal lit -> {
                if ( lit.text().isEmpty()) {
                return;}
                var key = lit.text() + (lit.caseInsensitive()
                                        ? "/i"
                                        : "/cs");
                seen.computeIfAbsent(key,
                                     k -> new InlineLiteral(lit.text(), lit.caseInsensitive(), seen.size()));
            }
            case Expression.Sequence seq -> seq.elements().forEach(e -> collectLiterals(e, seen));
            case Expression.Choice ch -> ch.alternatives().forEach(e -> collectLiterals(e, seen));
            case Expression.ZeroOrMore z -> collectLiterals(z.expression(), seen);
            case Expression.OneOrMore o -> collectLiterals(o.expression(), seen);
            case Expression.Optional o -> collectLiterals(o.expression(), seen);
            case Expression.Repetition r -> collectLiterals(r.expression(), seen);
            case Expression.And a -> collectLiterals(a.expression(), seen);
            case Expression.Not n -> collectLiterals(n.expression(), seen);
            case Expression.TokenBoundary tb -> collectLiterals(tb.expression(), seen);
            case Expression.Ignore ig -> collectLiterals(ig.expression(), seen);
            case Expression.Capture cap -> collectLiterals(cap.expression(), seen);
            case Expression.CaptureScope cs -> collectLiterals(cs.expression(), seen);
            case Expression.Group g -> collectLiterals(g.expression(), seen);
            case Expression.CharClass __ -> {}
            case Expression.Any __ -> {}
            case Expression.Reference __ -> {}
            case Expression.BackReference __ -> {}
            case Expression.Cut __ -> {}
            case Expression.Dictionary __ -> {}
        }
    }

    private static Result<TokenKindAssignment> assignKinds(Grammar grammar,
                                                           RuleClassifier.Classification classification,
                                                           List<InlineLiteral> inlineLiterals,
                                                           List<InlineLiteral> aliasLiteralsOut) {
        var ruleNameToKind = new LinkedHashMap<String, Integer>();
        var inlineLiteralToKind = new LinkedHashMap<String, Integer>();
        var kindNames = new ArrayList<String>();
        kindNames.add("WHITESPACE");
        kindNames.add("LINE_COMMENT");
        kindNames.add("BLOCK_COMMENT");
        int[] nextKindRef = {FIRST_USER_KIND};
        for ( var rule : grammar.rules()) {
        if ( classification.kinds().get(rule.name()) == RuleKind.LEXER) {
            ruleNameToKind.put(rule.name(), nextKindRef[0]);
            kindNames.add(rule.name());
            nextKindRef[0]++;
        }}
        var usedNames = new HashSet<String>(kindNames);
        for ( var lit : inlineLiterals) {
            var name = uniqueInlineName(lit, usedNames);
            inlineLiteralToKind.put(literalKey(lit), nextKindRef[0]);
            kindNames.add(name);
            usedNames.add(name);
            nextKindRef[0]++;
        }
        // Phase B.5 — alias detection runs BEFORE keyword resolution so that any
        // INLINE_<text> kinds allocated here are visible to {@link #resolveKeywordKind}.
        // Otherwise keyword resolution for an aliased *KW rule (e.g. ReturnKW) would
        // map the matched text to the rule's own LEXER kind — but that rule has no
        // DFA accept state (alias detection skipped its NFA build), so the parser's
        // alias-match check would reject the token kind keyword resolution emits.
        var ruleNameToAliasKinds = buildAliasMap(grammar,
                                                 classification,
                                                 inlineLiteralToKind,
                                                 kindNames,
                                                 usedNames,
                                                 nextKindRef,
                                                 aliasLiteralsOut);
        // Phase B.0 — keyword resolution. For each skip-prefix rule, walk the
        // referenced literal-set rule and map every keyword text to a token
        // kind. Reuse existing kinds where possible (inline literals or
        // dedicated *KW rules); allocate synthetic kinds for the rest.
        var keywordResolutions = buildKeywordResolutions(grammar,
                                                         classification,
                                                         ruleNameToKind,
                                                         inlineLiteralToKind,
                                                         kindNames,
                                                         usedNames,
                                                         nextKindRef,
                                                         ruleNameToAliasKinds);
        // ANY_CHAR is the catch-all fallback for characters not covered by any
        // explicit LEXER rule or inline literal. It is required only when the
        // grammar mixes lex + parse (i.e. inline literals were extracted). For
        // grammars that consist entirely of LEXER rules, no fallback is needed
        // — and adding one would silently mask under-specified rules in tests.
        int anyCharKind = - 1;
        if ( !inlineLiterals.isEmpty()) {
            anyCharKind = nextKindRef[0];
            kindNames.add("ANY_CHAR");
            nextKindRef[0]++;
        }
        if ( ruleNameToKind.isEmpty() && inlineLiterals.isEmpty() && grammar.whitespace().isEmpty()) {
        return new DfaBuildError.NoLexerRules().result();}
        // Phase 0.6.0 — identifier fallback set. For each skip-prefix rule
        // (e.g. {@code Identifier <- !Keyword [a-zA-Z_$] [a-zA-Z0-9_$]*}),
        // collect every inline-literal kind whose text is identifier-shaped
        // and whose text is NOT in the hard-keyword set referenced by the
        // skip-prefix head. The generated parser accepts these kinds at
        // identifier positions so contextual keywords (record, sealed,
        // permits, module, open, etc.) continue to fall through to
        // Identifier when used as method/field/parameter names.
        var identifierFallbackKinds = buildIdentifierFallbacks(grammar,
                                                               classification,
                                                               ruleNameToKind,
                                                               inlineLiteralToKind);
        return Result.success(new TokenKindAssignment(Map.copyOf(ruleNameToKind),
                                                      Map.copyOf(inlineLiteralToKind),
                                                      Map.copyOf(keywordResolutions),
                                                      Map.copyOf(ruleNameToAliasKinds),
                                                      Map.copyOf(identifierFallbackKinds),
                                                      anyCharKind,
                                                      kindNames.toArray(new String[0])));
    }

    /**
     * Phase 0.6.0 — for each skip-prefix LEXER rule (rule whose body is
     * {@code !KeywordRule <body>}), collect inline-literal kinds whose
     * matched text is identifier-shaped AND not in the hard-keyword set.
     * Returns map keyed by the skip-prefix rule's name, value = sorted
     * array of acceptable fallback kinds.
     *
     * <p>The hard-keyword set is exactly the literal texts in the
     * {@code KeywordRule} referenced by the skip-prefix head. Identifier
     * shape is the regex {@code [a-zA-Z_$][a-zA-Z0-9_$]*}.
     *
     * <p>If a skip-prefix rule's keyword resolution table assigns a
     * dedicated *KW kind to a contextual keyword, that kind is added to
     * the fallback set instead of the original inline-literal kind: the
     * lexer emits the *KW kind for those texts, and the parser must
     * accept it when an identifier is expected.
     */
    private static Map<String, int[]> buildIdentifierFallbacks(
        Grammar grammar,
        RuleClassifier.Classification classification,
        Map<String, Integer> ruleNameToKind,
        Map<String, Integer> inlineLiteralToKind) {
        var result = new LinkedHashMap<String, int[]>();
        var ruleMap = grammar.ruleMap();
        for ( var entry : classification.keywordSkip().entrySet()) {
            var idRuleName = entry.getKey();
            var info = entry.getValue();
            var keywordRule = ruleMap.get(info.keywordRuleName());
            if ( keywordRule == null) {
            continue;}
            var hardKeywords = new HashSet<>(RuleClassifier.extractLiteralSet(keywordRule.expression()));
            if ( hardKeywords.isEmpty()) {
            continue;}
            var fallback = new java.util.TreeSet<Integer>();
            // Walk every inline literal whose text is identifier-shaped and not
            // a hard keyword. Include the corresponding inline-literal kind so
            // that texts like 'module', 'record', 'sealed', 'permits', 'open',
            // etc. remain acceptable wherever Identifier is expected.
            for ( var litEntry : inlineLiteralToKind.entrySet()) {
                var key = litEntry.getKey();
                // Skip case-insensitive inline literals — identifier fallback only
                // applies to case-sensitive matches (Java keywords are case-sensitive).
                if ( !key.endsWith("/cs")) {
                continue;}
                var text = key.substring(0, key.length() - "/cs".length());
                if ( !isIdentifierShape(text)) {
                continue;}
                if ( hardKeywords.contains(text)) {
                continue;}
                fallback.add(litEntry.getValue());
            }
            // Also include any LEXER rule kind whose name ends with "KW" and
            // whose underlying text (rule name minus "KW", lowercased first
            // letter — matching {@link #resolveKeywordKind}) is identifier-shaped
            // and not a hard keyword. Keyword resolution may have remapped
            // contextual keywords to these kinds; the parser must still treat
            // them as identifiers when found in identifier position.
            for ( var ruleEntry : ruleNameToKind.entrySet()) {
                var ruleName = ruleEntry.getKey();
                if ( !ruleName.endsWith("KW") || ruleName.length() <= 2) {
                continue;}
                var stem = ruleName.substring(0, ruleName.length() - 2);
                if ( stem.isEmpty()) {
                continue;}
                var lowered = Character.toLowerCase(stem.charAt(0)) + stem.substring(1);
                if ( !isIdentifierShape(lowered)) {
                continue;}
                if ( hardKeywords.contains(lowered)) {
                continue;}
                fallback.add(ruleEntry.getValue());
            }
            if ( fallback.isEmpty()) {
            continue;}
            int[] sorted = fallback.stream().mapToInt(Integer::intValue).toArray();
            result.put(idRuleName, sorted);
        }
        return result;
    }

    /**
     * True iff {@code text} matches the regex {@code [a-zA-Z_$][a-zA-Z0-9_$]*}.
     * Empty string returns false.
     */
    private static boolean isIdentifierShape(String text) {
        if ( text.isEmpty()) {
        return false;}
        char first = text.charAt(0);
        if ( ! (isAsciiLetter(first) || first == '_' || first == '$')) {
        return false;}
        for ( int i = 1; i < text.length(); i++) {
            char c = text.charAt(i);
            if ( ! (isAsciiLetter(c) || (c >= '0' && c <= '9') || c == '_' || c == '$')) {
            return false;}
        }
        return true;
    }

    /**
     * Phase B.5 — for each LEXER/MIXED rule whose body simplifies to a single
     * literal or a choice of literals (optionally wrapped by Group/Capture/
     * TokenBoundary/Ignore/CaptureScope and optionally followed by a trailing
     * {@code !CharClass} word-boundary guard), record the rule name → set of
     * inline-literal kinds. Allocates new inline-literal kinds for any literal
     * texts that don't already have one.
     *
     * <p>This avoids the DFA-can't-build-{@code Not} pitfall for rules like
     * {@code ClassKW <- < 'class' ![a-zA-Z0-9_$] >} or
     * {@code Modifier <- < ('public' / 'private' / ...) ![a-zA-Z0-9_$] >}.
     * The body is matched not by a dedicated DFA accept state but by accepting
     * any of the per-text inline-literal kinds at parser time.
     */
    private static Map<String, int[]> buildAliasMap(Grammar grammar,
                                                    RuleClassifier.Classification classification,
                                                    Map<String, Integer> inlineLiteralToKind,
                                                    List<String> kindNames,
                                                    Set<String> usedNames,
                                                    int[] nextKindRef,
                                                    List<InlineLiteral> aliasLiteralsOut) {
        var aliases = new LinkedHashMap<String, int[]>();
        for ( var rule : grammar.rules()) {
            var kind = classification.kinds().get(rule.name());
            if ( kind != RuleKind.LEXER && kind != RuleKind.MIXED) {
            continue;}
            // Skip-prefix rules use a dedicated DFA + post-match keyword resolution
            // path; they are not aliasable by literal text.
            if ( classification.keywordSkip().containsKey(rule.name())) {
            continue;}
            var literalsOpt = collectAliasLiterals(rule.expression());
            if ( literalsOpt.isEmpty()) {
            continue;}
            var literals = literalsOpt.unwrap();
            if ( literals.isEmpty()) {
            continue;}
            var kinds = new int[literals.size()];
            int i = 0;
            for ( var lit : literals) {
            kinds[i++] = ensureInlineKind(lit, inlineLiteralToKind, kindNames, usedNames, nextKindRef, aliasLiteralsOut);}
            // De-duplicate alias kinds (different aliases of the same text from
            // multiple alternatives would otherwise show up as duplicates) and
            // sort ascending so generated parser code can use binarySearch.
            int[] sorted = Arrays.stream(kinds).distinct()
                                        .sorted()
                                        .toArray();
            aliases.put(rule.name(), sorted);
        }
        return aliases;
    }

    /**
     * Strip outer wrappers from {@code expr} and, if the result is
     * {@code Sequence(actualBody, Not(CharClass))}, return {@code actualBody}.
     * Otherwise return the unwrapped expression unchanged.
     */
    private static Expression simplifyAliasBody(Expression expr) {
        var cur = unwrapAcceptableWrappers(expr);
        if ( cur instanceof Expression.Sequence seq && seq.elements().size() >= 2) {
            var last = unwrapAcceptableWrappers(seq.elements().get(seq.elements().size() - 1));
            if ( last instanceof Expression.Not not) {
                var notInner = unwrapAcceptableWrappers(not.expression());
                if ( notInner instanceof Expression.CharClass) {
                    var head = seq.elements().subList(0,
                                                      seq.elements().size() - 1);
                    if ( head.size() == 1) {
                    return unwrapAcceptableWrappers(head.get(0));}
                    return new Expression.Sequence(seq.span(), List.copyOf(head));
                }
            }
        }
        return cur;
    }

    /**
     * Return the list of literals if {@code expr} simplifies to either a single
     * {@link Expression.Literal} or a {@link Expression.Choice} every alternative
     * of which simplifies to a {@link Expression.Literal}. Returns {@code Option.none()}
     * if the shape doesn't match.
     */
    private static Option<List<Expression.Literal>> collectAliasLiterals(Expression expr) {
        var simplified = simplifyAliasBody(expr);
        if ( simplified instanceof Expression.Literal lit) {
            if ( lit.text().isEmpty()) {
            return Option.none();}
            return Option.some(List.of(lit));
        }
        if ( simplified instanceof Expression.Choice choice) {
            var out = new ArrayList<Expression.Literal>(choice.alternatives().size());
            for ( var alt : choice.alternatives()) {
                var altSimplified = simplifyAliasBody(alt);
                if ( altSimplified instanceof Expression.Literal altLit && !altLit.text().isEmpty()) {
                out.add(altLit);} else
                {
                return Option.none();}
            }
            return Option.some(out);
        }
        return Option.none();
    }

    /**
     * Ensure {@code lit} has an inline-literal kind allocated. Returns the kind
     * (existing or newly allocated). Side-effects {@code inlineLiteralToKind},
     * {@code kindNames}, {@code usedNames}, and {@code nextKindRef}.
     */
    private static int ensureInlineKind(Expression.Literal lit,
                                        Map<String, Integer> inlineLiteralToKind,
                                        List<String> kindNames,
                                        Set<String> usedNames,
                                        int[] nextKindRef,
                                        List<InlineLiteral> aliasLiteralsOut) {
        var inlineLit = new InlineLiteral(lit.text(), lit.caseInsensitive(), inlineLiteralToKind.size());
        var key = literalKey(inlineLit);
        var existing = inlineLiteralToKind.get(key);
        if ( existing != null) {
        return existing;}
        var name = uniqueInlineName(inlineLit, usedNames);
        int kind = nextKindRef[0];
        inlineLiteralToKind.put(key, kind);
        kindNames.add(name);
        usedNames.add(name);
        nextKindRef[0]++;
        // Record the literal so the caller can append its NFA accept fragment to
        // the lexer (otherwise the kind exists in the table but no DFA path
        // produces tokens of that kind).
        if ( aliasLiteralsOut != null) {
        aliasLiteralsOut.add(inlineLit);}
        return kind;
    }

    /**
     * Phase B.0 — for each skip-prefix LEXER rule, resolve every keyword text
     * in the referenced literal-set rule to a token kind, allocating new kinds
     * as needed. Order of preference per keyword:
     * <ol>
     *   <li>existing inline literal (case-sensitive) — exact match.</li>
     *   <li>existing dedicated lexer rule {@code <keyword>KW} — common Java25 pattern.</li>
     *   <li>freshly synthesised kind, recorded in {@link #inlineLiteralToKind}.</li>
     * </ol>
     */
    private static Map<Integer, KeywordResolution> buildKeywordResolutions(
    Grammar grammar,
    RuleClassifier.Classification classification,
    Map<String, Integer> ruleNameToKind,
    Map<String, Integer> inlineLiteralToKind,
    List<String> kindNames,
    Set<String> usedNames,
    int[] nextKindRef,
    Map<String, int[]> ruleNameToAliasKinds) {
        var result = new LinkedHashMap<Integer, KeywordResolution>();
        var ruleMap = grammar.ruleMap();
        for ( var entry : classification.keywordSkip().entrySet()) {
            var ruleName = entry.getKey();
            var info = entry.getValue();
            var identKind = ruleNameToKind.get(ruleName);
            if ( identKind == null) {
            continue;}
            var keywordRule = ruleMap.get(info.keywordRuleName());
            if ( keywordRule == null) {
            continue;}
            var keywordTexts = RuleClassifier.extractLiteralSet(keywordRule.expression());
            if ( keywordTexts.isEmpty()) {
            continue;}
            var textToKind = new LinkedHashMap<String, Integer>();
            for ( var text : keywordTexts) {
                int kw = resolveKeywordKind(text,
                                            ruleNameToKind,
                                            inlineLiteralToKind,
                                            kindNames,
                                            usedNames,
                                            nextKindRef,
                                            ruleNameToAliasKinds);
                textToKind.put(text, kw);
            }
            result.put(identKind, new KeywordResolution(identKind, Map.copyOf(textToKind)));
        }
        return result;
    }

    private static int resolveKeywordKind(String text,
                                          Map<String, Integer> ruleNameToKind,
                                          Map<String, Integer> inlineLiteralToKind,
                                          List<String> kindNames,
                                          Set<String> usedNames,
                                          int[] nextKindRef,
                                          Map<String, int[]> ruleNameToAliasKinds) {
        // 1. Prefer an existing inline literal kind (case-sensitive). Phase B.5
        //    aliasing populates these for *KW rules' bodies before we get here.
        var literalKey = text + "/cs";
        var existing = inlineLiteralToKind.get(literalKey);
        if ( existing != null) {
        return existing;}
        // 2. Try an existing dedicated *KW rule. If the rule has been aliased,
        //    its own LEXER kind no longer has a DFA accept state — falling back
        //    to it would silently emit tokens that the parser's alias-match
        //    check rejects. In that case skip to the synthesise-new path.
        var kwName = text + "KW";
        // Capitalise first letter (most KW rules are PascalCase like "IfKW").
        if ( !text.isEmpty()) {
        kwName = Character.toUpperCase(text.charAt(0)) + text.substring(1) + "KW";}
        var kwKind = ruleNameToKind.get(kwName);
        if ( kwKind != null && !ruleNameToAliasKinds.containsKey(kwName)) {
        return kwKind;}
        // 3. Allocate a synthetic kind and record it as a virtual inline literal.
        var lit = new InlineLiteral(text, false, inlineLiteralToKind.size());
        var name = uniqueInlineName(lit, usedNames);
        int kind = nextKindRef[0];
        inlineLiteralToKind.put(literalKey, kind);
        kindNames.add(name);
        usedNames.add(name);
        nextKindRef[0]++;
        return kind;
    }

    private static String literalKey(InlineLiteral lit) {
        return lit.text + (lit.caseInsensitive
                           ? "/i"
                           : "/cs");
    }

    /**
     * Build the combined NFA for all LEXER-classified rules, all inline literals
     * extracted from PARSER/MIXED bodies, and the ANY_CHAR fallback. Priority is
     * a single counter that orders alternatives at PEG first-match-wins time:
     * <ol>
     *   <li>inline literals (longest first) — priorities {@code 0..N-1}</li>
     *   <li>{@code %whitespace} body (if any) — priority {@code N}</li>
     *   <li>user LEXER rules in source order — priorities {@code N+1..N+M}</li>
     *   <li>ANY_CHAR fallback — priority {@code N+M+1} (lowest)</li>
     * </ol>
     * Rules that the DFA path can't compile (e.g. negative lookahead) are
     * recorded in {@code skipped} and omitted; any input they would have matched
     * falls through to ANY_CHAR.
     */
    private static Nfa buildNfaWithSkips(Grammar grammar,
                                         RuleClassifier.Classification classification,
                                         TokenKindAssignment assignment,
                                         List<InlineLiteral> inlineLiterals,
                                         List<SkippedRule> skipped) {
        var nfa = new Nfa();
        int globalStart = nfa.newState();
        nfa.start = globalStart;
        var priorityRef = new int[]{0};
        for ( var lit : inlineLiterals) {
            int kind = assignment.inlineLiteralToKind().get(literalKey(lit));
            absorbLiteralFragment(nfa, lit, kind, priorityRef, globalStart);
        }
        if ( grammar.whitespace().isPresent()) {
        tryAbsorb(nfa,
                  "%whitespace",
                  grammar.whitespace().unwrap(),
                  KIND_WHITESPACE,
                  priorityRef,
                  globalStart,
                  skipped);}
        for ( var rule : grammar.rules()) {
            if ( classification.kinds().get(rule.name()) != RuleKind.LEXER) {
            continue;}
            // Phase B.5 — aliased rules don't need a dedicated DFA accept state;
            // the parser accepts any of the alias kinds when it sees a Reference
            // to them. Compiling them anyway would waste states and (more
            // importantly) the DFA build would fail on the !CharClass guard.
            if ( assignment.ruleNameToAliasKinds().containsKey(rule.name())) {
            continue;}
            int kind = assignment.ruleNameToKind().get(rule.name());
            // Phase B.0 — for skip-prefix rules, compile the body expression only.
            // The Not(Reference) head is replaced by post-match keyword resolution
            // performed by the lexer engine.
            var skipInfo = classification.keywordSkip().get(rule.name());
            var expr = skipInfo != null
                       ? skipInfo.bodyExpression()
                       : rule.expression();
            tryAbsorb(nfa, rule.name(), expr, kind, priorityRef, globalStart, skipped);
        }
        if ( assignment.anyCharKind() >= 0) {
        absorbAnyCharFallback(nfa, assignment.anyCharKind(), priorityRef, globalStart);}
        return nfa;
    }

    private static void absorbLiteralFragment(Nfa nfa,
                                              InlineLiteral lit,
                                              int kind,
                                              int[] priorityRef,
                                              int globalStart) {
        var fragment = compileLiteral(nfa, new Expression.Literal(null, lit.text, lit.caseInsensitive));
        nfa.addEpsilon(globalStart, fragment.start);
        nfa.markAccept(fragment.accept, kind, priorityRef[0]);
        priorityRef[0]++;
    }

    private static void absorbAnyCharFallback(Nfa nfa, int kind, int[] priorityRef, int globalStart) {
        int start = nfa.newState();
        int accept = nfa.newState();
        for ( int c = 0; c < ALPHABET; c++) {
        nfa.addCharEdge(start, c, accept);}
        // 0.6.0 — ANY_CHAR is a catch-all; also accept non-ASCII chars so the
        // generated lexer covers the full input byte stream.
        nfa.addNonAsciiEdge(start, accept);
        nfa.addEpsilon(globalStart, start);
        nfa.markAccept(accept, kind, priorityRef[0]);
        priorityRef[0]++;
    }

    private static void tryAbsorb(Nfa nfa,
                                  String ruleName,
                                  Expression expr,
                                  int kind,
                                  int[] priorityRef,
                                  int globalStart,
                                  List<SkippedRule> skipped) {
        var result = compileExpression(nfa, expr, ruleName);
        if ( result.isSuccess()) {
            var fragment = result.unwrap();
            nfa.addEpsilon(globalStart, fragment.start);
            nfa.markAccept(fragment.accept, kind, priorityRef[0]);
            priorityRef[0]++;
            return;
        }
        // Phase B.0 — partial-choice fallback. If the rule body has shape
        // {@code (alt1 / alt2 / ...)+} or {@code (alt1 / alt2 / ...)*} and one or
        // more alternatives fail (typically the BlockComment pattern with
        // {@code !'*/' .}), absorb only the alternatives that compile. This
        // recovers whitespace/comment lexing for grammars whose %whitespace
        // mixes simple char classes with not-yet-supported "until" patterns.
        var fallbackOpt = tryPartialChoice(nfa, ruleName, expr);
        if ( fallbackOpt.isPresent()) {
            var fallback = fallbackOpt.unwrap();
            nfa.addEpsilon(globalStart, fallback.start);
            nfa.markAccept(fallback.accept, kind, priorityRef[0]);
            priorityRef[0]++;
            return;
        }
        // Phase B.6 — top-level Choice partial fallback. Some grammar rules
        // (e.g. {@code StringLit <- < triplequoted ... > / < quoted ... >})
        // have a Choice body whose first alternative uses constructs that the
        // DFA path can't compile (Not predicate over '"""'), but later
        // alternatives are fully regular. Without this fallback the entire
        // rule would be skipped, leaving its kind unallocated and the
        // characters it would have matched falling through to ANY_CHAR.
        // Absorb each top-level alternative independently so the regular
        // alternatives still produce tokens of the rule's kind.
        var unwrapped = unwrapAcceptableWrappers(expr);
        if ( unwrapped instanceof Expression.Choice choice) {
            int absorbed = absorbChoiceAlternatives(nfa, ruleName, choice, kind, priorityRef, globalStart);
            if ( absorbed > 0) {
            return;}
        }
        skipped.add(new SkippedRule(ruleName, result.toString()));
    }

    /**
     * Phase B.6 — for a top-level Choice rule body, attempt to compile each
     * alternative independently. Each successful alternative becomes its own
     * NFA accept fragment for the rule's kind (sharing the global start via
     * an epsilon edge). Failing alternatives are silently dropped; the caller
     * decides what to do based on the return value.
     *
     * @return number of alternatives successfully absorbed (0 on total failure)
     */
    private static int absorbChoiceAlternatives(Nfa nfa,
                                                String ruleName,
                                                Expression.Choice choice,
                                                int kind,
                                                int[] priorityRef,
                                                int globalStart) {
        int absorbed = 0;
        for ( var alt : choice.alternatives()) {
            var altResult = compileExpression(nfa, alt, ruleName);
            if ( !altResult.isSuccess()) {
            continue;}
            var fragment = altResult.unwrap();
            nfa.addEpsilon(globalStart, fragment.start);
            nfa.markAccept(fragment.accept, kind, priorityRef[0]);
            priorityRef[0]++;
            absorbed++;
        }
        return absorbed;
    }

    /**
     * Attempt to compile a rule whose direct body is a kleene closure over a
     * choice. Successful alternatives are absorbed; failing ones are dropped.
     * Returns {@code Option.none()} if the body shape doesn't match or every
     * alternative failed.
     */
    private static Option<Fragment> tryPartialChoice(Nfa nfa, String ruleName, Expression expr) {
        Expression inner;
        boolean kleenePlus = false;
        var unwrapped = unwrapAcceptableWrappers(expr);
        if ( unwrapped instanceof Expression.ZeroOrMore zom) {
        inner = unwrapAcceptableWrappers(zom.expression());} else
        if ( unwrapped instanceof Expression.OneOrMore oom) {
            inner = unwrapAcceptableWrappers(oom.expression());
            kleenePlus = true;
        } else {
        return Option.none();}
        if ( ! (inner instanceof Expression.Choice choice)) {
        return Option.none();}
        var compiled = new ArrayList<Fragment>();
        for ( var alt : choice.alternatives()) {
            var altResult = compileExpression(nfa, alt, ruleName);
            if ( altResult.isSuccess()) {
            compiled.add(altResult.unwrap());}
        }
        if ( compiled.isEmpty()) {
        return Option.none();}
        int choiceStart = nfa.newState();
        int choiceAccept = nfa.newState();
        for ( var f : compiled) {
            nfa.addEpsilon(choiceStart, f.start);
            nfa.addEpsilon(f.accept, choiceAccept);
        }
        var choiceFrag = new Fragment(choiceStart, choiceAccept);
        return Option.some(kleenePlus
                           ? wrapOneOrMore(nfa, choiceFrag)
                           : wrapZeroOrMore(nfa, choiceFrag));
    }

    /**
     * Phase B.6 — recognise and compile the "delimited block" pattern
     * {@code Literal(L) ZeroOrMore(Sequence(Not(Literal(L)), Any)) Literal(L)}.
     *
     * <p>Classical use: triple-quoted text blocks {@code """ ... """} or
     * block comments {@code /* ... *​/}. The body's {@code !L .} negative
     * lookahead is what makes the regular DFA path fail; here we side-step
     * the lookahead and emit a small DFA that:
     * <ol>
     *   <li>matches L byte-for-byte (opening delimiter),</li>
     *   <li>enters a body loop that consumes any character, but tracks how
     *       many of the opening characters of L are consecutively visible
     *       at the input head,</li>
     *   <li>accepts the moment all bytes of L have been seen consecutively
     *       (closing delimiter).</li>
     * </ol>
     *
     * <p>Returns {@code Option.none()} if {@code seq} doesn't match the expected
     * three-element shape with literal-equality between opening, body
     * negation, and closing delimiters.
     */
    private static Option<Fragment> compileDelimitedBlock(Nfa nfa, Expression.Sequence seq) {
        var elements = seq.elements();
        if ( elements.size() != 3) {
        return Option.none();}
        var open = unwrapAcceptableWrappers(elements.get(0));
        var body = unwrapAcceptableWrappers(elements.get(1));
        var close = unwrapAcceptableWrappers(elements.get(2));
        if ( ! (open instanceof Expression.Literal openLit) || !(close instanceof Expression.Literal closeLit) || !(body instanceof Expression.ZeroOrMore zom)) {
        return Option.none();}
        var bodyInner = unwrapAcceptableWrappers(zom.expression());
        if ( ! (bodyInner instanceof Expression.Sequence bodySeq) || bodySeq.elements().size() != 2) {
        return Option.none();}
        var notExpr = unwrapAcceptableWrappers(bodySeq.elements().get(0));
        var anyExpr = unwrapAcceptableWrappers(bodySeq.elements().get(1));
        if ( ! (notExpr instanceof Expression.Not not) || !(anyExpr instanceof Expression.Any)) {
        return Option.none();}
        var notInner = unwrapAcceptableWrappers(not.expression());
        if ( ! (notInner instanceof Expression.Literal notLit)) {
        return Option.none();}
        // 0.6.0 — the open/close delimiters need NOT be equal. For block
        // comments {@code /* ... */} they differ. The KMP body loop only tracks
        // partial matches of the CLOSE delimiter; the Not-predicate guards the
        // body against accidentally consuming the close. Required invariant:
        // {@code notInner == closeLit}.
        var openText = openLit.text();
        var closeText = closeLit.text();
        if ( openText.isEmpty() || closeText.isEmpty() || !closeText.equals(notLit.text())) {
        return Option.none();}
        if ( openLit.caseInsensitive() || closeLit.caseInsensitive() || notLit.caseInsensitive()) {
        return Option.none();}
        return Option.some(buildDelimitedBlockFragment(nfa, openText, closeText));
    }

    /**
     * Build the NFA fragment for a delimited-block scanner with the given
     * {@code delimiter} string. The fragment opens with a chain of edges
     * matching the delimiter, then enters a body loop where each non-final
     * delimiter prefix state has a self-loop for any byte that doesn't
     * advance the prefix (and an explicit edge for the byte that does
     * advance it). Reaching the end-of-delimiter state accepts.
     *
     * <p>The body's "any byte that doesn't advance the prefix" handling
     * uses the partial-match collapse from the KMP failure function: at
     * any prefix-length state {@code k}, a byte {@code b} causes a
     * transition to state {@code k+1} when {@code b == delim[k]}, otherwise
     * to the longest proper suffix of {@code delim[0..k] + b} that is also
     * a prefix of {@code delim} (computed via the standard prefix function).
     */
    private static Fragment buildDelimitedBlockFragment(Nfa nfa, String openDelim, String closeDelim) {
        int n = closeDelim.length();
        // KMP-style failure function on the CLOSING delimiter — the body loop
        // only ever tracks how close we are to seeing the close.
        int[] fail = new int[n];
        for ( int i = 1, k = 0; i < n; i++) {
            while ( k > 0 && closeDelim.charAt(k) != closeDelim.charAt(i)) {
            k = fail[k - 1];}
            if ( closeDelim.charAt(k) == closeDelim.charAt(i)) {
            k++;}
            fail[i] = k;
        }
        // Allocate one NFA state per "partial-match length" 0..n of the close
        // delimiter. State n is the accept (full close delim consumed).
        int[] state = new int[n + 1];
        for ( int i = 0; i <= n; i++) {
        state[i] = nfa.newState();}
        // Opening delimiter: a chain from a fresh start state up to a state
        // representing "0 partial matches of close delim observed yet". For
        // asymmetric delimiters like {@code /* ... */}, the first byte of the
        // body might also be the first byte of the close — handle the seed
        // state by starting at state[0].
        int chainStart = nfa.newState();
        int cur = chainStart;
        for ( int i = 0; i < openDelim.length(); i++) {
            int next = nfa.newState();
            nfa.addCharEdge(cur, openDelim.charAt(i), next);
            cur = next;
        }
        // After the opening chain we're in the body's state[0].
        nfa.addEpsilon(cur, state[0]);
        // For each body state k in 0..n-1, install transitions for every
        // ASCII byte b: advance to state[k+1] if b matches closeDelim[k],
        // otherwise collapse via the failure function.
        for ( int k = 0; k < n; k++) {
        for ( int b = 0; b < ALPHABET; b++) {
            int target = nextDelimitedState(closeDelim, fail, k, (char) b);
            nfa.addCharEdge(state[k], b, state[target]);
        }}
        // 0.6.0 — non-ASCII characters never match any byte of the close
        // delimiter (the delimiter is ASCII text), so they always reset the
        // partial match count to 0 (or stay at 0). Add a non-ASCII edge from
        // every body state back to state[0] so block comments containing
        // em-dashes, smart quotes, CJK characters, etc. lex correctly.
        for ( int k = 0; k < n; k++) {
        nfa.addNonAsciiEdge(state[k], state[0]);}
        // state[n] is the accept; it has no outgoing edges from here so
        // the longest-match scan terminates as soon as the closing
        // delimiter is fully consumed.
        return new Fragment(chainStart, state[n]);
    }

    /**
     * KMP-style next-state for a delimited-block scanner. Given the current
     * partial-match length {@code k} and a new byte {@code b}, return the
     * new partial-match length in {@code 0..delimiter.length()}.
     */
    private static int nextDelimitedState(String delimiter, int[] fail, int k, char b) {
        int n = delimiter.length();
        int j = k;
        while ( true) {
            if ( j < n && delimiter.charAt(j) == b) {
            return j + 1;}
            if ( j == 0) {
            return 0;}
            j = fail[j - 1];
        }
    }

    private static Expression unwrapAcceptableWrappers(Expression expr) {
        Expression cur = expr;
        while ( true) {
        switch ( cur) {
            case Expression.Group g -> cur = g.expression();
            case Expression.TokenBoundary tb -> cur = tb.expression();
            case Expression.Capture cap -> cur = cap.expression();
            case Expression.CaptureScope cs -> cur = cs.expression();
            case Expression.Ignore ig -> cur = ig.expression();
            default -> {
                return cur;
            }
        }}
    }

    private static Result<Fragment> compileExpression(Nfa nfa, Expression expr, String ruleName) {
        return switch (expr) {case Expression.Literal lit -> Result.success(compileLiteral(nfa, lit));case Expression.CharClass cc -> Result.success(compileCharClass(nfa,
                                                                                                                                                                      cc));case Expression.Any __ -> Result.success(compileAny(nfa));case Expression.Sequence seq -> {
            // Phase B.6 — special-case "delimited block" pattern:
            //   Literal(L) ZeroOrMore(Sequence(Not(Literal(L)), Any)) Literal(L)
            // This is the canonical "match L, then anything not containing L,
            // then L" pattern (text blocks, block comments, etc.). The DFA
            // path otherwise can't compile the Not-predicate. Compile to a
            // counting DFA over the L bytes.
            var delimitedOpt = compileDelimitedBlock(nfa, seq);
            if ( delimitedOpt.isPresent()) {
            yield Result.success(delimitedOpt.unwrap());}
            yield compileSequence(nfa, seq.elements(), ruleName);
        }case Expression.Choice ch -> compileChoice(nfa, ch.alternatives(), ruleName);case Expression.ZeroOrMore zom -> compileExpression(nfa,
                                                                                                                                          zom.expression(),
                                                                                                                                          ruleName)
        .map(inner -> wrapZeroOrMore(nfa, inner));case Expression.OneOrMore oom -> compileExpression(nfa,
                                                                                                     oom.expression(),
                                                                                                     ruleName)
        .map(inner -> wrapOneOrMore(nfa, inner));case Expression.Optional opt -> compileExpression(nfa,
                                                                                                   opt.expression(),
                                                                                                   ruleName)
        .map(inner -> wrapOptional(nfa, inner));case Expression.Repetition rep -> compileRepetition(nfa, rep, ruleName);case Expression.TokenBoundary tb -> compileExpression(nfa,
                                                                                                                                                                              tb.expression(),
                                                                                                                                                                              ruleName);case Expression.Ignore ig -> compileExpression(nfa,
                                                                                                                                                                                                                                       ig.expression(),
                                                                                                                                                                                                                                       ruleName);case Expression.Capture cap -> compileExpression(nfa,
                                                                                                                                                                                                                                                                                                  cap.expression(),
                                                                                                                                                                                                                                                                                                  ruleName);case Expression.CaptureScope cs -> compileExpression(nfa,
                                                                                                                                                                                                                                                                                                                                                                 cs.expression(),
                                                                                                                                                                                                                                                                                                                                                                 ruleName);case Expression.Group g -> compileExpression(nfa,
                                                                                                                                                                                                                                                                                                                                                                                                                        g.expression(),
                                                                                                                                                                                                                                                                                                                                                                                                                        ruleName);case Expression.Cut __ -> Result.success(emptyFragment(nfa));case Expression.And __ -> unsupported(ruleName,
                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                     "And",
                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                     "lookahead in lexer rules requires NFA-with-lookahead; not implemented in Phase A");case Expression.Not __ -> unsupported(ruleName,
                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                               "Not",
                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                               "lookahead in lexer rules requires NFA-with-lookahead; not implemented in Phase A");case Expression.Reference ref -> unsupported(ruleName,
                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                "Reference",
                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                "rule reference '" + ref.ruleName() + "' in lexer rule — classifier should have demoted");case Expression.BackReference __ -> unsupported(ruleName,
                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                          "BackReference",
                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                          "back-references are not regular");case Expression.Dictionary __ -> unsupported(ruleName,
                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                          "Dictionary",
                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                          "dictionary not yet supported in DFA path");};
    }

    private static Result<Fragment> unsupported(String ruleName, String kind, String detail) {
        return new DfaBuildError.UnsupportedExpression(ruleName, kind, detail).result();
    }

    private static Fragment compileLiteral(Nfa nfa, Expression.Literal lit) {
        var text = lit.text();
        if ( text.isEmpty()) {
        return emptyFragment(nfa);}
        int start = nfa.newState();
        int current = start;
        for ( int i = 0; i < text.length(); i++) {
            int next = nfa.newState();
            char c = text.charAt(i);
            if ( lit.caseInsensitive() && isAsciiLetter(c)) {
                nfa.addCharEdge(current, Character.toLowerCase(c), next);
                nfa.addCharEdge(current, Character.toUpperCase(c), next);
            } else



            {
            nfa.addCharEdge(current, c, next);}
            current = next;
        }
        return new Fragment(start, current);
    }

    private static Fragment compileCharClass(Nfa nfa, Expression.CharClass cc) {
        var mask = parseCharClassPattern(cc.pattern(), cc.negated(), cc.caseInsensitive());
        int start = nfa.newState();
        int accept = nfa.newState();
        for ( int c = mask.nextSetBit(0); c >= 0; c = mask.nextSetBit(c + 1)) {
        nfa.addCharEdge(start, c, accept);}
        // 0.6.0 — negated classes like [^abc] accept ALL non-ASCII chars too. Positive
        // classes [a-z] stay ASCII-only by definition. The mask only tracks 0..255 ASCII
        // bits; the non-ASCII branch is encoded as a separate per-state edge consumed
        // by subset construction (see Dfa.nonAsciiTransition).
        if ( cc.negated()) {
        nfa.addNonAsciiEdge(start, accept);}
        return new Fragment(start, accept);
    }

    private static Fragment compileAny(Nfa nfa) {
        int start = nfa.newState();
        int accept = nfa.newState();
        for ( int c = 0; c < ALPHABET; c++) {
        nfa.addCharEdge(start, c, accept);}
        // 0.6.0 — '.' (Any) accepts any character including non-ASCII / BMP-plus.
        nfa.addNonAsciiEdge(start, accept);
        return new Fragment(start, accept);
    }

    private static Result<Fragment> compileSequence(Nfa nfa, List<Expression> elements, String ruleName) {
        if ( elements.isEmpty()) {
        return Result.success(emptyFragment(nfa));}
        int start = nfa.newState();
        int current = start;
        for ( var element : elements) {
            var fragmentResult = compileExpression(nfa, element, ruleName);
            if ( !fragmentResult.isSuccess()) {
            return fragmentResult;}
            var fragment = fragmentResult.unwrap();
            nfa.addEpsilon(current, fragment.start);
            current = fragment.accept;
        }
        return Result.success(new Fragment(start, current));
    }

    private static Result<Fragment> compileChoice(Nfa nfa, List<Expression> alternatives, String ruleName) {
        if ( alternatives.isEmpty()) {
        return Result.success(emptyFragment(nfa));}
        int start = nfa.newState();
        int accept = nfa.newState();
        for ( var alt : alternatives) {
            var fragmentResult = compileExpression(nfa, alt, ruleName);
            if ( !fragmentResult.isSuccess()) {
            return fragmentResult;}
            var fragment = fragmentResult.unwrap();
            nfa.addEpsilon(start, fragment.start);
            nfa.addEpsilon(fragment.accept, accept);
        }
        return Result.success(new Fragment(start, accept));
    }

    private static Fragment wrapZeroOrMore(Nfa nfa, Fragment inner) {
        int start = nfa.newState();
        int accept = nfa.newState();
        nfa.addEpsilon(start, inner.start);
        nfa.addEpsilon(start, accept);
        nfa.addEpsilon(inner.accept, inner.start);
        nfa.addEpsilon(inner.accept, accept);
        return new Fragment(start, accept);
    }

    private static Fragment wrapOneOrMore(Nfa nfa, Fragment inner) {
        int start = nfa.newState();
        int accept = nfa.newState();
        nfa.addEpsilon(start, inner.start);
        nfa.addEpsilon(inner.accept, inner.start);
        nfa.addEpsilon(inner.accept, accept);
        return new Fragment(start, accept);
    }

    private static Fragment wrapOptional(Nfa nfa, Fragment inner) {
        int start = nfa.newState();
        int accept = nfa.newState();
        nfa.addEpsilon(start, inner.start);
        nfa.addEpsilon(start, accept);
        nfa.addEpsilon(inner.accept, accept);
        return new Fragment(start, accept);
    }

    private static Result<Fragment> compileRepetition(Nfa nfa, Expression.Repetition rep, String ruleName) {
        int min = rep.min();
        int max = rep.max().fold(() -> - 1, m -> m);
        int unrolledMax = max < 0
                          ? min
                          : Math.min(max, REPETITION_CAP);
        int start = nfa.newState();
        int current = start;
        for ( int i = 0; i < min; i++) {
            var fragmentResult = compileExpression(nfa, rep.expression(), ruleName);
            if ( !fragmentResult.isSuccess()) {
            return fragmentResult;}
            var fragment = fragmentResult.unwrap();
            nfa.addEpsilon(current, fragment.start);
            current = fragment.accept;
        }
        if ( max < 0) {
            var tailResult = compileExpression(nfa, rep.expression(), ruleName);
            if ( !tailResult.isSuccess()) {
            return tailResult;}
            var tail = wrapZeroOrMore(nfa, tailResult.unwrap());
            nfa.addEpsilon(current, tail.start);
            current = tail.accept;
        } else



        {
        for ( int i = min; i < unrolledMax; i++) {
            var fragmentResult = compileExpression(nfa, rep.expression(), ruleName);
            if ( !fragmentResult.isSuccess()) {
            return fragmentResult;}
            var optional = wrapOptional(nfa, fragmentResult.unwrap());
            nfa.addEpsilon(current, optional.start);
            current = optional.accept;
        }}
        return Result.success(new Fragment(start, current));
    }

    private static Fragment emptyFragment(Nfa nfa) {
        int s = nfa.newState();
        int a = nfa.newState();
        nfa.addEpsilon(s, a);
        return new Fragment(s, a);
    }

    private static BitSet parseCharClassPattern(String pattern, boolean negated, boolean caseInsensitive) {
        var mask = new BitSet(ALPHABET);
        int i = 0;
        int n = pattern.length();
        while ( i < n) {
            char c1 = pattern.charAt(i);
            int firstChar;
            int afterFirst;
            if ( c1 == '\\' && i + 1 < n) {
                firstChar = decodeEscape(pattern.charAt(i + 1));
                afterFirst = i + 2;
            } else



            {
                firstChar = c1;
                afterFirst = i + 1;
            }
            if ( afterFirst < n && pattern.charAt(afterFirst) == '-' && afterFirst + 1 < n) {
                int rangeEndStart = afterFirst + 1;
                char endChar = pattern.charAt(rangeEndStart);
                int endDecoded;
                int advance;
                if ( endChar == '\\' && rangeEndStart + 1 < n) {
                    endDecoded = decodeEscape(pattern.charAt(rangeEndStart + 1));
                    advance = (rangeEndStart + 2) - i;
                } else



                {
                    endDecoded = endChar;
                    advance = (rangeEndStart + 1) - i;
                }
                addRange(mask, firstChar, endDecoded, caseInsensitive);
                i += advance;
            } else



            {
                addChar(mask, firstChar, caseInsensitive);
                i = afterFirst;
            }
        }
        if ( negated) {
            var negatedMask = new BitSet(ALPHABET);
            negatedMask.set(0, ALPHABET);
            negatedMask.andNot(mask);
            return negatedMask;
        }
        return mask;
    }

    private static int decodeEscape(char esc) {
        return switch (esc) {case 'n' -> '\n'; case 'r' -> '\r'; case 't' -> '\t'; case '0' -> '\0'; case 'f' -> '\f'; case 'b' -> '\b'; default -> esc;};
    }

    private static void addChar(BitSet mask, int c, boolean caseInsensitive) {
        if ( c < 0 || c >= ALPHABET) {
        return;}
        mask.set(c);
        if ( caseInsensitive && isAsciiLetter((char) c)) {
            mask.set(Character.toLowerCase((char) c));
            mask.set(Character.toUpperCase((char) c));
        }
    }

    private static void addRange(BitSet mask, int start, int end, boolean caseInsensitive) {
        int lo = Math.max(0, Math.min(start, end));
        int hi = Math.min(ALPHABET - 1, Math.max(start, end));
        for ( int c = lo; c <= hi; c++) {
            mask.set(c);
            if ( caseInsensitive && isAsciiLetter((char) c)) {
                mask.set(Character.toLowerCase((char) c));
                mask.set(Character.toUpperCase((char) c));
            }
        }
    }

    private static boolean isAsciiLetter(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    /**
     * Synthesise a unique, valid Java identifier {@code INLINE_<encoded>} for an
     * inline literal so the generated lexer's KIND_NAMES table is debuggable.
     * Special chars are spelled out (e.g. {@code +} → {@code _PLUS_}); on collision
     * (two literals normalise to the same name) a numeric suffix is appended.
     */
    private static String uniqueInlineName(InlineLiteral lit, Set<String> taken) {
        var base = "INLINE_" + encodeForIdentifier(lit.text);
        if ( lit.caseInsensitive) {
        base = base + "_CI";}
        if ( !taken.contains(base)) {
        return base;}
        int n = 2;
        while ( taken.contains(base + "_" + n)) {
        n++;}
        return base + "_" + n;
    }

    private static String encodeForIdentifier(String text) {
        var sb = new StringBuilder(text.length() + 4);
        for ( int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ( (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_') {
                sb.append(c);
                continue;
            }
            sb.append('_');
            sb.append(switch (c) {case '+' -> "PLUS"; case '-' -> "MINUS"; case '*' -> "STAR"; case '/' -> "SLASH"; case '%' -> "PERCENT"; case '.' -> "DOT"; case ',' -> "COMMA"; case ';' -> "SEMI"; case ':' -> "COLON"; case '?' -> "QMARK"; case '!' -> "BANG"; case '~' -> "TILDE"; case '&' -> "AMP"; case '|' -> "PIPE"; case '^' -> "CARET"; case '=' -> "EQ"; case '<' -> "LT"; case '>' -> "GT"; case '(' -> "LPAREN"; case ')' -> "RPAREN"; case '[' -> "LBRACK"; case ']' -> "RBRACK"; case '{' -> "LBRACE"; case '}' -> "RBRACE"; case '@' -> "AT"; case '#' -> "HASH"; case '$' -> "DOLLAR"; case '\\' -> "BSLASH"; case '\'' -> "SQUOTE"; case '"' -> "DQUOTE"; case ' ' -> "SP"; case '\t' -> "TAB"; case '\n' -> "NL"; case '\r' -> "CR"; default -> "U" + String.format("%04x",
                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                           (int) c);});
        }
        return sb.toString();
    }

    private static Dfa subsetConstruction(Nfa nfa) {
        var startSet = new BitSet(nfa.stateCount());
        startSet.set(nfa.start);
        epsilonClosure(nfa, startSet);
        var stateMap = new HashMap<BitSet, Integer>();
        var dfaStates = new ArrayList<BitSet>();
        var transitions = new ArrayList<int[]>();
        var acceptKindList = new ArrayList<Integer>();
        var acceptPriorityList = new ArrayList<Integer>();
        var nonAsciiTargets = new ArrayList<Integer>();
        var queue = new ArrayDeque<BitSet>();
        registerState(stateMap, dfaStates, transitions, acceptKindList, acceptPriorityList, nonAsciiTargets, queue, startSet, nfa);
        while ( !queue.isEmpty()) {
            var currentSet = queue.poll();
            int currentId = stateMap.get(currentSet);
            int[] currentTransitions = transitions.get(currentId);
            for ( int ch = 0; ch < ALPHABET; ch++) {
                var moveSet = move(nfa, currentSet, ch);
                if ( moveSet.isEmpty()) {
                continue;}
                epsilonClosure(nfa, moveSet);
                int targetId = registerState(stateMap,
                                             dfaStates,
                                             transitions,
                                             acceptKindList,
                                             acceptPriorityList,
                                             nonAsciiTargets,
                                             queue,
                                             moveSet,
                                             nfa);
                currentTransitions[ch] = targetId;
            }
            // 0.6.0 — also compute the non-ASCII destination DFA state for this
            // closure. Union all per-NFA-state non-ASCII targets, epsilon-close,
            // and register (or reuse) the resulting DFA state id.
            var nonAsciiMoveSet = moveNonAscii(nfa, currentSet);
            if ( !nonAsciiMoveSet.isEmpty()) {
                epsilonClosure(nfa, nonAsciiMoveSet);
                int nonAsciiTargetId = registerState(stateMap,
                                                     dfaStates,
                                                     transitions,
                                                     acceptKindList,
                                                     acceptPriorityList,
                                                     nonAsciiTargets,
                                                     queue,
                                                     nonAsciiMoveSet,
                                                     nfa);
                nonAsciiTargets.set(currentId, nonAsciiTargetId);
            }
        }
        int stateCount = dfaStates.size();
        int[][] transitionTable = transitions.toArray(new int[0][]);
        int[] acceptKindArray = new int[stateCount];
        int[] acceptPriorityArray = new int[stateCount];
        int[] nonAsciiArray = new int[stateCount];
        for ( int i = 0; i < stateCount; i++) {
            acceptKindArray[i] = acceptKindList.get(i);
            acceptPriorityArray[i] = acceptPriorityList.get(i);
            nonAsciiArray[i] = nonAsciiTargets.get(i);
        }
        return new Dfa(transitionTable, acceptKindArray, acceptPriorityArray, nonAsciiArray);
    }

    private static int registerState(Map<BitSet, Integer> stateMap,
                                     List<BitSet> dfaStates,
                                     List<int[]> transitions,
                                     List<Integer> acceptKindList,
                                     List<Integer> acceptPriorityList,
                                     List<Integer> nonAsciiTargets,
                                     Deque<BitSet> queue,
                                     BitSet stateSet,
                                     Nfa nfa) {
        var existing = stateMap.get(stateSet);
        if ( existing != null) {
        return existing;}
        int id = dfaStates.size();
        stateMap.put(stateSet, id);
        dfaStates.add(stateSet);
        var row = new int[ALPHABET];
        Arrays.fill(row, Dfa.NO_TRANSITION);
        transitions.add(row);
        var accept = chooseAccept(nfa, stateSet);
        acceptKindList.add(accept.kind);
        acceptPriorityList.add(accept.priority);
        nonAsciiTargets.add(Dfa.NO_TRANSITION);
        queue.add(stateSet);
        return id;
    }

    private record AcceptInfo(int kind, int priority){}

    private static AcceptInfo chooseAccept(Nfa nfa, BitSet stateSet) {
        int bestKind = Dfa.NO_ACCEPT;
        int bestPriority = Integer.MAX_VALUE;
        for ( int s = stateSet.nextSetBit(0); s >= 0; s = stateSet.nextSetBit(s + 1)) {
            int kind = nfa.acceptKind[s];
            if ( kind == Dfa.NO_ACCEPT) {
            continue;}
            int priority = nfa.acceptPriority[s];
            if ( priority < bestPriority) {
                bestPriority = priority;
                bestKind = kind;
            }
        }
        return new AcceptInfo(bestKind, bestKind == Dfa.NO_ACCEPT
                                       ? - 1
                                       : bestPriority);
    }

    private static void epsilonClosure(Nfa nfa, BitSet states) {
        var stack = new ArrayDeque<Integer>();
        for ( int s = states.nextSetBit(0); s >= 0; s = states.nextSetBit(s + 1)) {
        stack.push(s);}
        while ( !stack.isEmpty()) {
            int s = stack.pop();
            int len = nfa.epsilonLen[s];
            int[] arr = nfa.epsilon[s];
            for ( int i = 0; i < len; i++) {
                int target = arr[i];
                if ( !states.get(target)) {
                    states.set(target);
                    stack.push(target);
                }
            }
        }
    }

    private static BitSet move(Nfa nfa, BitSet states, int ch) {
        var result = new BitSet(nfa.stateCount());
        for ( int s = states.nextSetBit(0); s >= 0; s = states.nextSetBit(s + 1)) {
            int[][] perChar = nfa.charEdges[s];
            if ( perChar == null) {
            continue;}
            int[] arr = perChar[ch];
            if ( arr == null) {
            continue;}
            int len = nfa.charEdgeLens[s][ch];
            for ( int i = 0; i < len; i++) {
            result.set(arr[i]);}
        }
        return result;
    }

    /**
     * 0.6.0 — union all non-ASCII targets across the NFA states in {@code states}.
     * The caller then runs epsilon-closure over the result and registers the DFA
     * state id; that id becomes the non-ASCII transition target for the current
     * DFA state.
     */
    private static BitSet moveNonAscii(Nfa nfa, BitSet states) {
        var result = new BitSet(nfa.stateCount());
        for ( int s = states.nextSetBit(0); s >= 0; s = states.nextSetBit(s + 1)) {
            int[] arr = nfa.nonAsciiEdges[s];
            if ( arr == null) {
            continue;}
            int len = nfa.nonAsciiEdgeLens[s];
            for ( int i = 0; i < len; i++) {
            result.set(arr[i]);}
        }
        return result;
    }

    private record Fragment(int start, int accept){}

    /** A literal collected from a PARSER/MIXED rule body. Keyed by {@code (text, caseInsensitive)}. */
    private record InlineLiteral(String text, boolean caseInsensitive, int firstOccurrence){}

    private static final class Nfa {
        int start = - 1;
        int[] acceptKind = new int[16];
        int[] acceptPriority = new int[16];
        int[][] epsilon = new int[16][];
        int[] epsilonLen = new int[16];
        int[][][] charEdges = new int[16][][];
        int[][] charEdgeLens = new int[16][];
        /**
         * 0.6.0 — per-NFA-state list of targets reachable via a non-ASCII (code &ge; 256)
         * input character. Populated by {@link #compileAny(Nfa)} and by
         * {@link #compileCharClass(Nfa, Expression.CharClass)} when the class is negated
         * (positive classes like {@code [a-z]} stay ASCII-only). The subset
         * construction unions these per closure to produce the DFA's
         * {@code nonAsciiTransition} table.
         */
        int[][] nonAsciiEdges = new int[16][];
        int[] nonAsciiEdgeLens = new int[16];
        int stateCount;

        Nfa() {
            Arrays.fill(acceptKind, Dfa.NO_ACCEPT);
        }

        int stateCount() {
            return stateCount;
        }

        int newState() {
            ensureCapacity(stateCount + 1);
            int id = stateCount++;
            acceptKind[id] = Dfa.NO_ACCEPT;
            acceptPriority[id] = - 1;
            return id;
        }

        void ensureCapacity(int needed) {
            if ( needed <= acceptKind.length) {
            return;}
            int newCap = Math.max(needed, acceptKind.length * 2);
            acceptKind = Arrays.copyOf(acceptKind, newCap);
            acceptPriority = Arrays.copyOf(acceptPriority, newCap);
            epsilon = Arrays.copyOf(epsilon, newCap);
            epsilonLen = Arrays.copyOf(epsilonLen, newCap);
            charEdges = Arrays.copyOf(charEdges, newCap);
            charEdgeLens = Arrays.copyOf(charEdgeLens, newCap);
            nonAsciiEdges = Arrays.copyOf(nonAsciiEdges, newCap);
            nonAsciiEdgeLens = Arrays.copyOf(nonAsciiEdgeLens, newCap);
        }

        void markAccept(int state, int kind, int priority) {
            if ( acceptKind[state] == Dfa.NO_ACCEPT || priority < acceptPriority[state]) {
                acceptKind[state] = kind;
                acceptPriority[state] = priority;
            }
        }

        void addEpsilon(int from, int to) {
            int[] arr = epsilon[from];
            if ( arr == null) {
                arr = new int[2];
                epsilon[from] = arr;
            }
            int len = epsilonLen[from];
            if ( len == arr.length) {
                arr = Arrays.copyOf(arr, arr.length * 2);
                epsilon[from] = arr;
            }
            arr[len] = to;
            epsilonLen[from] = len + 1;
        }

        void addCharEdge(int from, int ch, int to) {
            int[][] perChar = charEdges[from];
            if ( perChar == null) {
                perChar = new int[ALPHABET][];
                charEdges[from] = perChar;
                charEdgeLens[from] = new int[ALPHABET];
            }
            int[] arr = perChar[ch];
            int len = charEdgeLens[from][ch];
            if ( arr == null) {
                arr = new int[2];
                perChar[ch] = arr;
            }
            if ( len == arr.length) {
                arr = Arrays.copyOf(arr, arr.length * 2);
                perChar[ch] = arr;
            }
            arr[len] = to;
            charEdgeLens[from][ch] = len + 1;
        }

        /**
         * 0.6.0 — add a non-ASCII edge {@code from --> to}. The lexer follows this
         * transition when the input character is &ge; {@link Dfa#ALPHABET_SIZE}.
         */
        void addNonAsciiEdge(int from, int to) {
            int[] arr = nonAsciiEdges[from];
            int len = nonAsciiEdgeLens[from];
            if ( arr == null) {
                arr = new int[2];
                nonAsciiEdges[from] = arr;
            }
            if ( len == arr.length) {
                arr = Arrays.copyOf(arr, arr.length * 2);
                nonAsciiEdges[from] = arr;
            }
            arr[len] = to;
            nonAsciiEdgeLens[from] = len + 1;
        }
    }
}
