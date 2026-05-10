package org.pragmatica.peg.v6.token;

import org.pragmatica.peg.grammar.GrammarParser;
import org.pragmatica.peg.v6.lexer.DfaBuilder;
import org.pragmatica.peg.v6.lexer.LexerEngine;
import org.pragmatica.peg.v6.lexer.RuleClassifier;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TokenArraySpliceTest {
    private static final String GRAMMAR = """
        Number <- [0-9]+
        Word <- [a-z]+
        %whitespace <- [ ]*
        """;

    private static LexerEngine engine() {
        var grammar = GrammarParser.parse(GRAMMAR)
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

    private static void assertEquivalent(TokenArray actual, TokenArray expected) {
        assertThat(actual.input())
        .isEqualTo(expected.input());
        assertThat(actual.count())
        .isEqualTo(expected.count());
        for (var i = 0; i < expected.count(); i++ ) {
            assertThat(actual.kindAt(i))
            .as("kindAt(%d)",
                i)
            .isEqualTo(expected.kindAt(i));
            assertThat(actual.startAt(i))
            .as("startAt(%d)",
                i)
            .isEqualTo(expected.startAt(i));
            assertThat(actual.endAt(i))
            .as("endAt(%d)",
                i)
            .isEqualTo(expected.endAt(i));
        }
    }

    @Test
    void insertAtStart_producesTokensForCombinedInput() {
        var engine = engine();
        var original = engine.lex("bar");
        var spliced = original.spliceLex(engine, 0, 0, "x ");
        assertEquivalent(spliced, engine.lex("x bar"));
        assertThat(spliced.input())
        .isEqualTo("x bar");
    }

    @Test
    void insertAtEnd_producesTokensForCombinedInput() {
        var engine = engine();
        var original = engine.lex("bar");
        var spliced = original.spliceLex(engine,
                                         original.input()
                                                 .length(),
                                         0,
                                         " baz");
        assertEquivalent(spliced, engine.lex("bar baz"));
        assertThat(spliced.input())
        .isEqualTo("bar baz");
    }

    @Test
    void replaceMiddle_producesTokensForReplacedInput() {
        var engine = engine();
        var original = engine.lex("bar");
        var spliced = original.spliceLex(engine, 1, 1, "oo");
        assertEquivalent(spliced, engine.lex("boor"));
        assertThat(spliced.input())
        .isEqualTo("boor");
    }

    @Test
    void delete_shortensInputAndTokens() {
        var engine = engine();
        var original = engine.lex("foo bar");
        var spliced = original.spliceLex(engine, 3, 4, "");
        assertEquivalent(spliced, engine.lex("foo"));
        assertThat(spliced.input())
        .isEqualTo("foo");
    }

    @Test
    void noOpEdit_returnsEquivalentTokens() {
        var engine = engine();
        var original = engine.lex("hello 42");
        var spliced = original.spliceLex(engine, 3, 0, "");
        assertEquivalent(spliced, original);
        assertThat(spliced.input())
        .isEqualTo(original.input());
    }

    @Test
    void everyTokenSpan_readsCorrectlyFromNewInput() {
        var engine = engine();
        var original = engine.lex("abc 12");
        var spliced = original.spliceLex(engine, 4, 2, "999");
        assertThat(spliced.input())
        .isEqualTo("abc 999");
        for (var i = 0; i < spliced.count(); i++ ) {
            var s = spliced.startAt(i);
            var e = spliced.endAt(i);
            assertThat(spliced.input()
                              .substring(s, e))
            .as("token[%d] span [%d,%d)", i, s, e)
            .isEqualTo(spliced.textAt(i)
                              .toString());
        }
    }

    @Test
    void splice_isByteForByteEqualToFreshLex() {
        var engine = engine();
        var original = engine.lex("abc def 12");
        var spliced = original.spliceLex(engine, 4, 3, "zzz");
        var fresh = engine.lex("abc zzz 12");
        assertEquivalent(spliced, fresh);
    }

    @Test
    void mergeAdjacentTokens_viaInsertion_isHandled() {
        var engine = engine();
        var original = engine.lex("ab cd");
        var spliced = original.spliceLex(engine, 2, 1, "");
        assertEquivalent(spliced, engine.lex("abcd"));
        assertThat(spliced.count())
        .isEqualTo(1);
    }

    @Test
    void splitToken_viaWhitespaceInsertion_isHandled() {
        var engine = engine();
        var original = engine.lex("abcd");
        var spliced = original.spliceLex(engine, 2, 0, " ");
        assertEquivalent(spliced, engine.lex("ab cd"));
    }

    @Test
    void invalidArgs_negativeOffset_throws() {
        var engine = engine();
        var original = engine.lex("foo");
        assertThatThrownBy(() -> original.spliceLex(engine, - 1, 0, "x"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("offset");
    }

    @Test
    void invalidArgs_negativeOldLen_throws() {
        var engine = engine();
        var original = engine.lex("foo");
        assertThatThrownBy(() -> original.spliceLex(engine, 0, - 1, "x"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("oldLen");
    }

    @Test
    void invalidArgs_rangeBeyondInput_throws() {
        var engine = engine();
        var original = engine.lex("foo");
        assertThatThrownBy(() -> original.spliceLex(engine, 2, 5, "x"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("exceeds input length");
    }

    @Test
    void invalidArgs_nullEngine_throws() {
        var engine = engine();
        var original = engine.lex("foo");
        assertThatThrownBy(() -> original.spliceLex((org.pragmatica.peg.v6.lexer.LexerEngine) null,
                                                    0,
                                                    0,
                                                    "x"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("engine");
    }

    @Test
    void invalidArgs_nullNewText_throws() {
        var engine = engine();
        var original = engine.lex("foo");
        assertThatThrownBy(() -> original.spliceLex(engine, 0, 0, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("newText");
    }

    @Test
    void editAtTokenBoundary_noMerge_isHandled() {
        var engine = engine();
        var original = engine.lex("ab cd");
        var spliced = original.spliceLex(engine, 3, 0, "ef ");
        assertEquivalent(spliced, engine.lex("ab ef cd"));
    }

    @Test
    void windowedSplice_overLargerInput_matchesFreshLex() {
        // 20-token input; small mid-edit. Verifies the windowed re-lex covers a small
        // window (not the whole input) yet the result still matches a fresh full lex.
        var engine = engine();
        var input = "aa bb cc dd ee ff gg hh ii jj kk ll mm nn oo pp qq rr ss tt";
        var original = engine.lex(input);
        // Replace token "gg" (offset 18..20) with "ggg".
        var spliced = original.spliceLex(engine, 18, 2, "ggg");
        var expected = engine.lex(input.substring(0, 18) + "ggg" + input.substring(20));
        assertEquivalent(spliced, expected);
    }

    @Test
    void windowedSplice_consecutiveEdits_eachRemainsConsistentWithFreshLex() {
        var engine = engine();
        var current = engine.lex("alpha beta gamma");
        current = current.spliceLex(engine, 6, 4, "beta");
        assertEquivalent(current, engine.lex("alpha beta gamma"));
        current = current.spliceLex(engine, 0, 5, "alpha");
        assertEquivalent(current, engine.lex("alpha beta gamma"));
        current = current.spliceLex(engine,
                                    current.input()
                                           .length(),
                                    0,
                                    " delta");
        assertEquivalent(current, engine.lex("alpha beta gamma delta"));
        current = current.spliceLex(engine, 11, 5, "gamma");
        assertEquivalent(current, engine.lex("alpha beta gamma delta"));
    }

    @Test
    void windowedSplice_viaLexFnOverload_isAlsoCorrect() {
        var engine = engine();
        var original = engine.lex("ab cd");
        var spliced = original.spliceLex((org.pragmatica.peg.v6.token.LexFn) engine::lex, 2, 1, "");
        assertEquivalent(spliced, engine.lex("abcd"));
    }
}
