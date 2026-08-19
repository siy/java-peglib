package org.pragmatica.peg.lexer;

import org.junit.jupiter.api.Test;
import org.pragmatica.peg.PegParser;
import org.pragmatica.peg.grammar.GrammarParser;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 0.7.2 — a lexer rule may end in a lookahead over a character class.
 *
 * <p>The DFA has no lookahead mechanism, so {@code X ![c]} was uncompilable and such a rule was
 * skipped. Two narrow shapes escaped that: a trailing guard after a single literal or a choice of
 * literals (aliasing strips it) and a single leading {@code !Rule} guard (skip-prefix). A
 * multi-word lexeme fits neither, and maximal munch cannot stand in for the guard either —
 * nothing else in the grammar matches further than {@code GROUPING SETS}, so absent the guard the
 * keyword wins even when an identifier character follows.
 *
 * <p>These assert the TOKEN STREAM rather than a parse outcome. An earlier version of this test
 * asserted "the parse reports diagnostics", which held whether or not the guard was honoured —
 * both readings fail to parse, for different reasons — and so passed against the very defect it
 * was written for.
 */
class TrailingLookaheadTest {

    private static final String GRAMMAR = """
        Stmt         <- GroupingSets ';' / Ident ';'
        GroupingSets <- < 'GROUPING'i [ \\t]+ 'SETS'i ![a-zA-Z0-9_$] >
        Ident        <- < [a-zA-Z_] [a-zA-Z0-9_$]* >
        %whitespace  <- [ \\t\\r\\n]*
        """;

    /** Non-trivia token texts, in order. */
    private static List<String> lex(String grammarText, String input) {
        var grammar = GrammarParser.parse(grammarText)
                                   .unwrap();
        var classification = RuleClassifier.classify(grammar)
                                           .unwrap();
        var built = DfaBuilder.build(grammar, classification)
                              .unwrap();
        var engine = new LexerEngine(built.dfa(),
                                     built.kinds()
                                          .kindNameTable(),
                                     DfaBuilder.KIND_WHITESPACE,
                                     built.kinds()
                                          .keywordResolutions());
        var tokens = engine.lex(input);
        var out = new ArrayList<String>();

        for (int i = 0; i < tokens.count(); i++) {
            if (!tokens.isTrivia(i)) {
                out.add(tokens.textAt(i)
                              .toString());
            }
        }

        return out;
    }

    @Test
    void multiWordLexemeWithTrailingGuardCompiles() {
        var built = PegParser.fromGrammar(GRAMMAR);

        assertThat(built.isSuccess())
        .withFailMessage("grammar must compile: %s", built)
        .isTrue();
    }

    @Test
    void guardSatisfied_keywordIsOneToken() {
        assertThat(lex(GRAMMAR, "GROUPING SETS;"))
        .containsExactly("GROUPING SETS", ";");
    }

    /**
     * The discriminating case. An identifier character after the keyword denies the accept, so
     * maximal munch falls back to the longest accept that was allowed — {@code GROUPING} as an
     * identifier. Ignore the guard and the keyword swallows {@code GROUPING SETS}, stranding a
     * bare {@code X}.
     */
    @Test
    void guardDenied_keywordDoesNotMatchAndInputLexesAsIdentifiers() {
        assertThat(lex(GRAMMAR, "GROUPING SETSX;"))
        .containsExactly("GROUPING", "SETSX", ";");
    }

    /**
     * The generated lexer must reach the same verdict as the interpreted one — it, not
     * {@link LexerEngine}, is what {@code PegParser} actually runs.
     *
     * <p>The grammar is built so the two readings differ in OUTCOME, not merely in cause: with the
     * guard honoured {@code GROUPING SETSX;} is two identifiers and parses cleanly; ignored, the
     * keyword swallows {@code GROUPING SETS} and the stray {@code X} matches no alternative.
     */
    @Test
    void generatedLexerHonoursTheGuardToo() {
        var grammar = """
            Stmt         <- GroupingSets ';' / Ident Ident ';'
            GroupingSets <- < 'GROUPING'i [ \\t]+ 'SETS'i ![a-zA-Z0-9_$] >
            Ident        <- < [a-zA-Z_] [a-zA-Z0-9_$]* >
            %whitespace  <- [ \\t\\r\\n]*
            """;
        var parser = PegParser.fromGrammar(grammar)
                              .unwrap();

        assertThat(parser.parse("GROUPING SETSX;")
                         .diagnostics())
        .withFailMessage("the guard must deny the keyword, leaving two identifiers")
        .isEmpty();
        // And the guard must not deny it when the follower is legal.
        assertThat(parser.parse("GROUPING SETS;")
                         .diagnostics())
        .isEmpty();
    }

    /** End of input satisfies a negative guard: there is no following character to forbid. */
    @Test
    void negativeGuardIsSatisfiedAtEndOfInput() {
        assertThat(lex(GRAMMAR, "GROUPING SETS"))
        .containsExactly("GROUPING SETS");
    }
}
