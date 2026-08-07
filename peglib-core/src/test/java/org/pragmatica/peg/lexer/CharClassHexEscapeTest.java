package org.pragmatica.peg.lexer;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

import org.pragmatica.peg.PegParser;

/**
 * Hex and Unicode escapes inside a character class are decoded in full.
 *
 * <p>{@code GrammarLexer} preserves the whole escape text ({@code \\x20}, {@code \\u00e9}) so the
 * DFA builder can decode it. The builder used to read only the single character after the
 * backslash, so {@code [\\x20]} became the three members {@code 'x'}, {@code '2'}, {@code '0'}.
 *
 * <p>The failure was disguised: {@code [\\x61-\\x7a]} appeared to work because the leftover
 * {@code '-'} formed the bogus range {@code '-'..'x'}, which happens to cover lowercase letters.
 * Negated classes had no such luck — {@code [^\\x41-\\x5A]} matched nothing at all. Both
 * directions are asserted here for that reason.
 */
class CharClassHexEscapeTest {

    private static String firstTokenText(String charClass, String input) {
        // Assembled with String.join: the grammar contains '%whitespace', so String.formatted
        // would read '%w' as a format conversion.
        var grammar = String.join("\n",
                                  "%whitespace <- [ \\t\\r\\n]*",
                                  "Start <- Expr",
                                  "Expr <- Word '(' Expr ')' / Word",
                                  "Word <- " + charClass + "+");
        var tokens = PegParser.fromGrammar(grammar).unwrap().lexer().lex(input);

        return input.substring(tokens.startAt(0), tokens.endAt(0));
    }

    @Test
    void positiveHexRangeMatchesExactlyItsRange() {
        assertThat(firstTokenText("[\\x61-\\x7a]", "foo bar")).isEqualTo("foo");
    }

    @Test
    void negatedSingleHexEscapeExcludesThatCharacter() {
        assertThat(firstTokenText("[^\\x20]", "foo bar"))
        .as("space is \\x20 and must be excluded, so the token stops before it")
        .isEqualTo("foo");
    }

    @Test
    void negatedHexRangeExcludesExactlyThatRange() {
        // A-Z excluded; lowercase and space are not, so the run continues across the space.
        assertThat(firstTokenText("[^\\x41-\\x5A]", "foo bar")).isEqualTo("foo bar");
    }

    @Test
    void negatedRangeFromNulExcludesControlsAndSpace() {
        assertThat(firstTokenText("[^\\x00-\\x20]", "foo bar")).isEqualTo("foo");
    }

    @Test
    void hexAndLiteralFormsAgree() {
        assertThat(firstTokenText("[\\x61-\\x7a]", "foo bar"))
        .isEqualTo(firstTokenText("[a-z]", "foo bar"));
        assertThat(firstTokenText("[^\\x20]", "foo bar"))
        .isEqualTo(firstTokenText("[^ ]", "foo bar"));
    }
}
