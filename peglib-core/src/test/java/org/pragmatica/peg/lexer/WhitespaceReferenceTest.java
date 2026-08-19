package org.pragmatica.peg.lexer;

import org.junit.jupiter.api.Test;
import org.pragmatica.peg.PegParser;
import org.pragmatica.peg.grammar.GrammarParser;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 0.7.2 — a rule named from {@code %whitespace} is trivia.
 *
 * <p>Each alternative of the {@code %whitespace} body is compiled on its own so a mixed run does
 * not collapse into one token. An alternative that NAMES a rule could not be compiled — the DFA
 * has no call stack — and was silently dropped, leaving the standalone rule to match the same text
 * under its own ordinary lexer kind. The token then read as content, the parser's trivia skip
 * never advanced past it, and the first comment in the file killed the parse.
 *
 * <p>The C-family shapes ({@code //}, {@code /*}) were unaffected because they are spelled as
 * inline literals, which is why `java25.peg` never saw this. Any grammar that factors its comment
 * syntax into named rules did — SQL's {@code --}, Lua's {@code --}, shell and Python's {@code #}.
 */
class WhitespaceReferenceTest {

    private static final String G = """
        Doc          <- Ident+
        Ident        <- < [a-zA-Z_] [a-zA-Z0-9_]* >
        LineComment  <- '--' [^\\n]*
        BlockComment <- '/*' (!'*/' .)* '*/'
        %whitespace  <- ([ \\t\\r\\n]+ / LineComment / BlockComment)*
        """;

    private static boolean allCommentsAreTrivia(String input) {
        var grammar = GrammarParser.parse(G)
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

        for (int i = 0; i < tokens.count(); i++) {
            var text = tokens.textAt(i)
                             .toString();

            if ((text.startsWith("--") || text.startsWith("/*")) && !tokens.isTrivia(i)) {
                return false;
            }
        }

        return true;
    }

    @Test
    void lineCommentNamedFromWhitespaceIsTrivia() {
        assertThat(allCommentsAreTrivia("a -- note\nb"))
        .withFailMessage("a '--' comment named from %whitespace must lex as trivia")
        .isTrue();
    }

    @Test
    void blockCommentNamedFromWhitespaceIsTrivia() {
        assertThat(allCommentsAreTrivia("a /* note */ b"))
        .withFailMessage("a '/* */' comment named from %whitespace must lex as trivia")
        .isTrue();
    }

    @Test
    void parseSkipsCommentsNamedFromWhitespace() {
        var parser = PegParser.fromGrammar(G)
                              .unwrap();

        assertThat(parser.parse("a -- note\nb")
                         .diagnostics())
        .withFailMessage("a line comment must not end the parse")
        .isEmpty();
        assertThat(parser.parse("a /* note */ b")
                         .diagnostics())
        .withFailMessage("a block comment must not end the parse")
        .isEmpty();
        assertThat(parser.parse("a b")
                         .diagnostics())
        .isEmpty();
    }
}
