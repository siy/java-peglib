package org.pragmatica.peg.v6.lexer;

import org.junit.jupiter.api.Test;
import org.pragmatica.peg.grammar.GrammarParser;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase B.0 — token-granularity tests for the {@code !KeywordSet body}
 * skip-prefix pattern. The classifier detects the pattern, the DFA builds the
 * body alone, and the engine performs post-match keyword resolution.
 */
class KeywordResolutionTest {

    private static LexerEngine engineFor(String grammarText) {
        var grammar = GrammarParser.parse(grammarText).unwrap();
        var classification = RuleClassifier.classify(grammar).unwrap();
        var built = DfaBuilder.build(grammar, classification).unwrap();
        int wsKind = grammar.whitespace().isPresent() ? DfaBuilder.KIND_WHITESPACE : -1;
        return new LexerEngine(built.dfa(), built.kinds().kindNameTable(), wsKind,
            built.kinds().keywordResolutions());
    }

    private static DfaBuilder.Built buildFor(String grammarText) {
        var grammar = GrammarParser.parse(grammarText).unwrap();
        var classification = RuleClassifier.classify(grammar).unwrap();
        return DfaBuilder.build(grammar, classification).unwrap();
    }

    private static RuleClassifier.Classification classify(String grammarText) {
        var grammar = GrammarParser.parse(grammarText).unwrap();
        return RuleClassifier.classify(grammar).unwrap();
    }

    @Test
    void skipPrefixDetected_whenIdentifierLeadsWithNotKeyword() {
        var grammar = """
            MiniIdent <- !MiniKw [a-z]+
            MiniKw <- 'if' / 'while'
            """;
        var classification = classify(grammar);
        assertThat(classification.keywordSkip()).containsKey("MiniIdent");
        var info = classification.keywordSkip().get("MiniIdent");
        assertThat(info.keywordRuleName()).isEqualTo("MiniKw");
    }

    @Test
    void skipPrefixNotDetected_whenRuleHasNoNegativeLookaheadHead() {
        var grammar = """
            JustIdent <- [a-z]+
            """;
        var classification = classify(grammar);
        assertThat(classification.keywordSkip()).isEmpty();
    }

    @Test
    void skipPrefixNotDetected_whenReferencedRuleIsNotLiteralSet() {
        var grammar = """
            MaybeIdent <- !Digit [a-z]+
            Digit <- [0-9]
            """;
        var classification = classify(grammar);
        assertThat(classification.keywordSkip()).isEmpty();
    }

    @Test
    void skipPrefixDetected_throughTokenBoundaryAndCaptureWrappers() {
        var grammar = """
            MiniIdent <- !MiniKw < [a-z]+ >
            MiniKw <- 'if' / 'while'
            """;
        var classification = classify(grammar);
        assertThat(classification.keywordSkip()).containsKey("MiniIdent");
    }

    @Test
    void lexer_emitsKeywordKindForKnownText_andIdentKindOtherwise() {
        var grammar = """
            MiniIdent <- !MiniKw [a-z]+
            MiniKw <- 'if' / 'while'
            """;
        var built = buildFor(grammar);
        var engine = engineFor(grammar);

        int identKind = built.kinds().ruleNameToKind().get("MiniIdent");
        var resolutions = built.kinds().keywordResolutions();
        assertThat(resolutions).containsKey(identKind);
        var resolver = resolutions.get(identKind);
        int ifKind = resolver.textToKind().get("if");
        int whileKind = resolver.textToKind().get("while");
        assertThat(ifKind).isNotEqualTo(identKind);
        assertThat(whileKind).isNotEqualTo(identKind);
        assertThat(ifKind).isNotEqualTo(whileKind);

        var ifTokens = engine.lex("if");
        assertThat(ifTokens.count()).isEqualTo(1);
        assertThat(ifTokens.kindAt(0)).isEqualTo(ifKind);

        var whileTokens = engine.lex("while");
        assertThat(whileTokens.count()).isEqualTo(1);
        assertThat(whileTokens.kindAt(0)).isEqualTo(whileKind);

        var iffTokens = engine.lex("iff");
        assertThat(iffTokens.count()).isEqualTo(1);
        assertThat(iffTokens.kindAt(0)).isEqualTo(identKind);

        var fooTokens = engine.lex("foo");
        assertThat(fooTokens.count()).isEqualTo(1);
        assertThat(fooTokens.kindAt(0)).isEqualTo(identKind);
    }

    @Test
    void lexer_reusesDedicatedKWRuleKindWhenAvailable() {
        // Mirrors the Java25 pattern: dedicated IfKW rule co-exists with the
        // Keyword literal set; resolver maps "if" to a kind that the parser's
        // alias-match check accepts when it sees a reference to IfKW.
        //
        // Phase B.5 — alias detection lifts IfKW's body literal 'if' to an
        // INLINE_if kind and adds it to IfKW's alias array. The resolver picks
        // the canonical inline-literal kind (semantically equivalent to the
        // dedicated IfKW rule kind: both are accepted wherever a reference to
        // IfKW appears in the grammar).
        var grammar = """
            Ident <- !Kw [a-z]+
            Kw <- 'if' / 'while'
            IfKW <- 'if'
            """;
        var built = buildFor(grammar);
        int identKind = built.kinds().ruleNameToKind().get("Ident");
        int ifKwKind = built.kinds().ruleNameToKind().get("IfKW");
        var resolver = built.kinds().keywordResolutions().get(identKind);
        assertThat(resolver).isNotNull();
        Integer resolved = resolver.textToKind().get("if");
        assertThat(resolved).isNotNull();
        // Acceptable: the dedicated IfKW rule kind itself, or any kind in IfKW's
        // alias set (which under B.5 always includes the canonical INLINE_if kind).
        if (resolved != ifKwKind) {
            int[] aliases = built.kinds().ruleNameToAliasKinds().get("IfKW");
            assertThat(aliases)
                .as("IfKW must have an alias set when resolver returns kind %d != IfKW kind %d",
                    resolved, ifKwKind)
                .isNotNull();
            boolean found = false;
            for (int k : aliases) {
                if (k == resolved) { found = true; break; }
            }
            assertThat(found)
                .as("resolver returned kind %d for 'if'; not equal to IfKW kind %d nor in IfKW alias set %s",
                    resolved, ifKwKind, java.util.Arrays.toString(aliases))
                .isTrue();
        }
    }

    @Test
    void roundTripPreserved_evenAfterKeywordResolution() {
        var grammar = """
            MiniIdent <- !MiniKw [a-z]+
            MiniKw <- 'if' / 'while'
            %whitespace <- [ \\t]*
            """;
        var engine = engineFor(grammar);
        var input = "if foo while bar";
        var tokens = engine.lex(input);
        var sb = new StringBuilder();
        for (int i = 0; i < tokens.count(); i++) {
            sb.append(tokens.textAt(i));
        }
        assertThat(sb.toString()).isEqualTo(input);
    }
}
