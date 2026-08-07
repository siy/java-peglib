package org.pragmatica.peg.lexer;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

import org.pragmatica.peg.PegParser;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * JLS 3.3 — Unicode escapes are translated BEFORE lexing, and token spans still point at the
 * original source.
 *
 * <p>Java substitutes {@code \\uXXXX} in a pre-pass over the whole file, so an escape can sit in
 * the middle of an identifier, end a line comment, or produce a character the lexer then treats
 * structurally. No grammar can express that — by the time the lexer runs the substitution has
 * already happened.
 *
 * <p>The round-trip assertions are the point of the design. Translating the source and lexing
 * that would be easy; the formatter would then reconstruct translated text and silently rewrite
 * the user's escapes. Spans are remapped onto the original string so what comes back out is
 * exactly what went in.
 */
class UnicodeEscapeTranslationTest {

    private static org.pragmatica.peg.Parser javaParser() throws Exception {
        return PegParser.fromGrammar(Files.readString(Path.of("src/test/resources/java25.peg"))).unwrap();
    }

    private static void parsesCleanlyAndRoundTrips(String src) throws Exception {
        var result = javaParser().parse(src);

        assertThat(result.diagnostics()).as("source: %s", src).isEmpty();
        assertThat(result.cst().reconstruct())
        .as("token spans must point at the ORIGINAL text, escapes and all")
        .isEqualTo(src);
    }

    @Test
    void escapeInsideIdentifier() throws Exception {
        // \u0061 is 'a', so the field is named 'abc'.
        parsesCleanlyAndRoundTrips("class A { int \\u0061bc = 1; }");
    }

    @Test
    void escapeAsLineTerminatorEndsLineComment() throws Exception {
        // The escape below denotes a real newline, so it ENDS the comment and 'int x = 1;' is
        // live code. If it were left untranslated the rest of the class would be swallowed.
        //
        // NB: this comment deliberately does NOT spell the escape out. javac applies the same
        // pre-pass to THIS file, so a literal backslash-u000a here would end the comment line
        // and break compilation — which is precisely the behaviour under test.
        parsesCleanlyAndRoundTrips("class A { void m() { // c \\u000a int x = 1; } }");
    }

    @Test
    void multipleUsAreLegal() throws Exception {
        parsesCleanlyAndRoundTrips("class A { int \\uuuu0061bc = 1; }");
    }

    @Test
    void evenBackslashesDoNotStartAnEscape() throws Exception {
        // "\\u0041" is a literal backslash followed by u0041 — NOT an 'A'. Translating it would
        // corrupt the string's contents.
        parsesCleanlyAndRoundTrips("class A { String s = \"\\\\u0041\"; }");
    }

    @Test
    void escapeInsideStringLiteral() throws Exception {
        parsesCleanlyAndRoundTrips("class A { String s = \"\\u0041\"; }");
    }

    @Test
    void sourceWithoutEscapesIsUntouched() throws Exception {
        parsesCleanlyAndRoundTrips("class A { int x = 1; String s = \"a\\nb\\tc\"; }");
    }

    @Test
    void translateReturnsNullWhenThereIsNothingToDo() {
        assertThat(UnicodeEscapes.translate("class A { int x = 1; }").isPresent())
        .as("the common case must not allocate a translated copy or an offset map")
        .isFalse();
        assertThat(UnicodeEscapes.translate("\"a\\nb\"").isPresent())
        .as("a backslash that is not followed by 'u' is not an escape")
        .isFalse();
    }

    @Test
    void offsetMapCoversEveryTranslatedCharacterPlusTerminator() {
        var translated = UnicodeEscapes.translate("a\\u0041b").or((UnicodeEscapes.Translated) null);

        assertThat(translated).isNotNull();
        assertThat(translated.text()).isEqualTo("aAb");
        // 'a' at 0, the escape spans [1,7) and yields 'A', 'b' at 7, terminator at 8.
        assertThat(translated.offsetMap()).containsExactly(0, 1, 7, 8);
    }

    @Test
    void malformedEscapeIsLeftAlone() throws Exception {
        // '\\uZZZZ' is not a well-formed escape. javac reports illegal.unicode.esc, which is a
        // lexical-validity concern; we must at least not corrupt the text or crash.
        var translated = UnicodeEscapes.translate("\\uZZZZ").or((UnicodeEscapes.Translated) null);

        assertThat(translated).isNotNull();
        assertThat(translated.text()).isEqualTo("\\uZZZZ");
    }
}
