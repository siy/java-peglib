package org.pragmatica.peg.v6.lexer;

import org.pragmatica.peg.grammar.GrammarParser;
import org.pragmatica.peg.v6.token.TokenArray;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LexerEngineTest {
    private static LexerEngine engineFor(String grammarText) {
        var grammar = GrammarParser.parse(grammarText)
                                   .unwrap();
        var classification = RuleClassifier.classify(grammar)
                                           .unwrap();
        var built = DfaBuilder.build(grammar, classification)
                              .unwrap();
        int whitespaceKind = grammar.whitespace()
                                    .isPresent()
                             ? DfaBuilder.KIND_WHITESPACE
                             : - 1;
        return new LexerEngine(built.dfa(),
                               built.kinds()
                                    .kindNameTable(),
                               whitespaceKind,
                               built.kinds()
                                    .keywordResolutions());
    }

    private static int kindOf(String grammarText, String ruleName) {
        var grammar = GrammarParser.parse(grammarText)
                                   .unwrap();
        var classification = RuleClassifier.classify(grammar)
                                           .unwrap();
        return DfaBuilder.build(grammar, classification)
                         .unwrap()
                         .kinds()
                         .ruleNameToKind()
                         .get(ruleName);
    }

    private static DfaBuilder.Built buildOf(String grammarText) {
        var grammar = GrammarParser.parse(grammarText)
                                   .unwrap();
        var classification = RuleClassifier.classify(grammar)
                                           .unwrap();
        return DfaBuilder.build(grammar, classification)
                         .unwrap();
    }

    /**
     * Phase B.5 — a rule's body literals are lifted to INLINE_<text> kinds and
     * added to the rule's alias array. The lexer emits the inline-literal kind
     * for body-literal-only rules; the parser's alias-match check accepts either
     * the rule kind or any alias kind. Assert membership in {ruleKind} ∪ aliases.
     */
    private static void assertEmittedAsRuleOrAlias(DfaBuilder.Built built,
                                                   String ruleName,
                                                   int actualKind,
                                                   int ruleKind) {
        if (actualKind == ruleKind) {
            return;
        }
        var aliasMap = built.kinds()
                            .ruleNameToAliasKinds();
        int[] aliases = aliasMap.get(ruleName);
        assertThat(aliases)
        .as("rule '%s' must have an alias set when lexer emits kind %d != ruleKind %d", ruleName, actualKind, ruleKind)
        .isNotNull();
        boolean inAliases = false;
        for (int k : aliases) {
            if (k == actualKind) {
                inAliases = true;
                break;
            }
        }
        assertThat(inAliases)
        .as("rule '%s' actual kind %d not in alias set %s nor equal to rule kind %d",
            ruleName,
            actualKind,
            java.util.Arrays.toString(aliases),
            ruleKind)
        .isTrue();
    }

    @Test
    void singleLiteralRule_lexesSingleToken() {
        var grammar = "Number <- [0-9]+\n";
        var engine = engineFor(grammar);
        int numberKind = kindOf(grammar, "Number");
        var tokens = engine.lex("123");
        assertThat(tokens.count())
        .isEqualTo(1);
        assertThat(tokens.kindAt(0))
        .isEqualTo(numberKind);
        assertThat(tokens.startAt(0))
        .isZero();
        assertThat(tokens.endAt(0))
        .isEqualTo(3);
        assertThat(tokens.textAt(0)
                         .toString())
        .isEqualTo("123");
    }

    @Test
    void multipleRulesWithWhitespace_emitsInterleavedStream() {
        var grammar = """
            Number <- [0-9]+
            Plus <- '+'
            %whitespace <- [ ]*
            """;
        var engine = engineFor(grammar);
        var built = buildOf(grammar);
        int numberKind = kindOf(grammar, "Number");
        int plusKind = kindOf(grammar, "Plus");
        var tokens = engine.lex("1 + 2");
        assertThat(tokens.count())
        .isEqualTo(5);
        assertThat(tokens.kindAt(0))
        .isEqualTo(numberKind);
        assertThat(tokens.kindAt(1))
        .isEqualTo(TokenArray.KIND_WHITESPACE);
        // Phase B.5 — Plus's body literal '+' is lifted to INLINE__PLUS and added
        // to Plus's alias array; the lexer emits the inline kind. Number has no
        // body literal, so its rule kind is emitted directly.
        assertEmittedAsRuleOrAlias(built, "Plus", tokens.kindAt(2), plusKind);
        assertThat(tokens.kindAt(3))
        .isEqualTo(TokenArray.KIND_WHITESPACE);
        assertThat(tokens.kindAt(4))
        .isEqualTo(numberKind);
        // Round-trip: concatenated token texts == input
        var rebuilt = new StringBuilder();
        for (int i = 0; i < tokens.count(); i++ ) {
            rebuilt.append(tokens.textAt(i));
        }
        assertThat(rebuilt.toString())
        .isEqualTo("1 + 2");
    }

    @Test
    void longestMatch_prefersHexOverNumberPrefix() {
        var grammar = """
            Hex <- '0x' [0-9a-fA-F]+
            Number <- [0-9]+
            """;
        var engine = engineFor(grammar);
        int hexKind = kindOf(grammar, "Hex");
        var tokens = engine.lex("0xff");
        assertThat(tokens.count())
        .isEqualTo(1);
        assertThat(tokens.kindAt(0))
        .isEqualTo(hexKind);
        assertThat(tokens.endAt(0))
        .isEqualTo(4);
    }

    @Test
    void firstMatchWins_keywordBeforeIdentifier() {
        var grammar = """
            Keyword <- 'if'
            Identifier <- [a-zA-Z]+
            """;
        var engine = engineFor(grammar);
        var built = buildOf(grammar);
        int keywordKind = kindOf(grammar, "Keyword");
        int identifierKind = kindOf(grammar, "Identifier");
        // "if" alone — both rules accept length 2. Keyword (priority 0) wins.
        // Phase B.5 — Keyword's body literal 'if' is lifted to INLINE_if and added
        // to Keyword's alias array; the lexer emits the inline kind, which the
        // parser's alias-match check accepts as a Keyword token.
        var ifTokens = engine.lex("if");
        assertThat(ifTokens.count())
        .isEqualTo(1);
        assertEmittedAsRuleOrAlias(built, "Keyword", ifTokens.kindAt(0), keywordKind);
        // "ifoo" — Identifier matches 4, Keyword only 2. Longest-match picks Identifier.
        // Identifier has no body literal, so its rule kind is emitted directly.
        var idTokens = engine.lex("ifoo");
        assertThat(idTokens.count())
        .isEqualTo(1);
        assertThat(idTokens.kindAt(0))
        .isEqualTo(identifierKind);
        assertThat(idTokens.endAt(0))
        .isEqualTo(4);
    }

    @Test
    void noMatch_throwsWithOffsetAndCharacter() {
        var grammar = "Number <- [0-9]+\n";
        var engine = engineFor(grammar);
        assertThatThrownBy(() -> engine.lex("12@45"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("offset 2")
        .hasMessageContaining("'@'");
    }

    @Test
    void noMatch_atStart_reportsOffsetZero() {
        var grammar = "Number <- [0-9]+\n";
        var engine = engineFor(grammar);
        assertThatThrownBy(() -> engine.lex("@"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("offset 0")
        .hasMessageContaining("'@'");
    }

    @Test
    void roundTripInvariant_concatenatedTokenTextEqualsInput() {
        var grammar = """
            Identifier <- [a-zA-Z_][a-zA-Z0-9_]*
            Number <- [0-9]+
            Punct <- [,;.]
            %whitespace <- [ \\t\\n]*
            """;
        var engine = engineFor(grammar);
        var input = "abc 42, foo;\nx0 99.";
        var tokens = engine.lex(input);
        var rebuilt = new StringBuilder();
        for (int i = 0; i < tokens.count(); i++ ) {
            rebuilt.append(tokens.textAt(i));
        }
        assertThat(rebuilt.toString())
        .isEqualTo(input);
    }

    @Test
    void whitespaceRule_producesKindZero() {
        var grammar = """
            Number <- [0-9]+
            %whitespace <- [ \\t]+
            """;
        var engine = engineFor(grammar);
        var tokens = engine.lex("  42");
        assertThat(tokens.count())
        .isGreaterThanOrEqualTo(2);
        // First emitted token is whitespace.
        assertThat(tokens.kindAt(0))
        .isEqualTo(TokenArray.KIND_WHITESPACE);
        assertThat(tokens.isTrivia(0))
        .isTrue();
        assertThat(engine.whitespaceKind())
        .isEqualTo(TokenArray.KIND_WHITESPACE);
    }

    @Test
    void emptyInput_yieldsZeroTokens() {
        var grammar = "Number <- [0-9]+\n";
        var engine = engineFor(grammar);
        var tokens = engine.lex("");
        assertThat(tokens.count())
        .isZero();
    }

    @Test
    void emptyMatchingRule_failsAtLexTime() {
        // [a-z]* matches empty -> DFA start state is accepting -> longest match
        // is zero-length on a non-matching char like '!', which we detect and throw.
        var grammar = "Word <- [a-z]*\n";
        var engine = engineFor(grammar);
        assertThatThrownBy(() -> engine.lex("!"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("offset 0");
    }
}
