package org.pragmatica.peg.v6.generator;

import org.pragmatica.peg.v6.PegParser;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 0.6.1 — Item D — runtime semantics for named captures
 * ({@code $name<expr>}), back-references ({@code $name}), and capture scopes
 * ({@code $(...)}).
 *
 * <p>Back-references match by SOURCE-SPAN EQUALITY: the captured substring
 * (bytes between the first and last consumed token's source spans) is
 * re-compared against the next bytes of input at the back-reference site.
 * This mirrors 0.5.x {@code PegEngine.parseBackReference} which iterates the
 * captured text character-by-character.
 *
 * <p>CaptureScope ({@code $(...)}) UNCONDITIONALLY restores the captures map
 * on exit, regardless of inner success/failure. This mirrors 0.5.x
 * {@code PegEngine.parseCaptureScope} which calls {@code restoreCaptures}
 * after delegating to the inner expression, with no conditional.
 *
 * <p>Choice does NOT save/restore captures per alternative — captures from a
 * failed alternative persist into the next alternative. This is intentional:
 * 0.5.x {@code parseCapture} only sets a capture on inner-expression success,
 * so partial alternatives never leak captures that "shouldn't" be set
 * (semantically). Captures of fully-matched inner sub-expressions in a failed
 * alternative DO leak — matches 0.5.x by faithful translation.
 */
class NamedCaptureRuntimeTest {

    @Nested
    class HtmlTagRoundTrip {
        // Grammar mirrors 0.5.x-style tag matching. Tag captures the open name,
        // then back-references it for the close. Identifier and BodyText are
        // separate LEXER-classified rules so the inner-expression of $name<...>
        // produces real consumed tokens (and a non-empty source span).
        // Use whitespace as the body so BodyText doesn't compete with NameTok
        // on the same characters. Whitespace inside angle-brackets is fine
        // because the grammar has no %whitespace skip; the lexer emits the
        // explicit Space token.
        private static final String GRAMMAR = """
            Tag <- '<' $name<NameTok> '>' Body '</' $name '>'
            NameTok <- [a-zA-Z]+
            Body <- [0-9]+
            """;

        @Test void matchingOpenClose_parsesCleanly() {
            var parser = PegParser.fromGrammar(GRAMMAR).unwrap();
            var result = parser.parse("<foo>123</foo>");
            assertThat(result.diagnostics()).isEmpty();
        }

        @Test void mismatchedClose_emitsDiagnostics() {
            var parser = PegParser.fromGrammar(GRAMMAR).unwrap();
            var result = parser.parse("<foo>123</baz>");
            assertThat(result.diagnostics()).isNotEmpty();
        }
    }

    @Nested
    class EmptyCapture {
        // Wrap the capture's inner expression in an Optional at the parser
        // level: $x<Word?>. When Word is absent, the capture span is empty
        // (start==end). The empty back-reference then trivially succeeds
        // without advancing pos. When Word is present, the capture is the
        // word text (digits) and the back-ref must match it again.
        // Using digits for the captured word and '|' as separator avoids
        // overlap with the lexer's longest-match preference.
        private static final String GRAMMAR = """
            Maybe <- $x<Word?> '|' $x
            Word <- [0-9]+
            """;

        @Test void absentWord_emptyCaptureRoundTrips() {
            var parser = PegParser.fromGrammar(GRAMMAR).unwrap();
            var result = parser.parse("|");
            // Empty capture: "" then '|' matches, then empty back-ref matches nothing.
            assertThat(result.diagnostics()).isEmpty();
        }

        @Test void presentWord_capturesAndMatches() {
            var parser = PegParser.fromGrammar(GRAMMAR).unwrap();
            var result = parser.parse("123|123");
            // Capture is '123', then '|', then back-ref expects '123' — match.
            assertThat(result.diagnostics()).isEmpty();
        }
    }

    @Nested
    class BackrefWithoutCapture {
        // $x is referenced but never captured: at runtime captures.get("x")
        // returns null, fail() fires, and a diagnostic is emitted.
        private static final String GRAMMAR = """
            Bad <- $x Tail
            Tail <- 'a'
            """;

        @Test void absentCapture_failsAtRuntime() {
            var parser = PegParser.fromGrammar(GRAMMAR).unwrap();
            var result = parser.parse("a");
            assertThat(result.diagnostics()).isNotEmpty();
        }
    }

    @Nested
    class CaptureScopeIsolation {
        // Outer captures $x as "alpha"; the inner $(...) re-binds $x to "beta"
        // but the scope rolls back on exit (regardless of inner result). After
        // the scope, $x is back to "alpha". Then the trailing $x must match
        // "alpha", not "beta".
        private static final String GRAMMAR = """
            Outer <- $x<First> Mid $($x<Second>) Tail $x
            First <- 'alpha'
            Second <- 'beta'
            Mid <- '|'
            Tail <- '|'
            """;

        @Test void afterScope_outerCapturePersists() {
            var parser = PegParser.fromGrammar(GRAMMAR).unwrap();
            var result = parser.parse("alpha|beta|alpha");
            assertThat(result.diagnostics()).isEmpty();
        }

        @Test void afterScope_innerCaptureNotVisible() {
            var parser = PegParser.fromGrammar(GRAMMAR).unwrap();
            var result = parser.parse("alpha|beta|beta");
            // After scope rollback, $x is "alpha"; trailing $x against "beta" fails.
            assertThat(result.diagnostics()).isNotEmpty();
        }
    }

    @Nested
    class ChoiceBacktrack {
        // First alternative captures $x then back-refs it (eg "aa"); if that fails
        // the parser tries the second alternative ("foo"). We don't rely on
        // captures being preserved/cleared across alternatives — both
        // alternatives are independent paths for the test inputs.
        private static final String GRAMMAR = """
            Try <- FirstAlt / 'foo'
            FirstAlt <- $x<A> $x
            A <- 'a'
            """;

        @Test void firstAlternative_matchesViaCapture() {
            var parser = PegParser.fromGrammar(GRAMMAR).unwrap();
            var result = parser.parse("aa");
            assertThat(result.diagnostics()).isEmpty();
        }

        @Test void secondAlternative_matchesAfterFirstFails() {
            var parser = PegParser.fromGrammar(GRAMMAR).unwrap();
            var result = parser.parse("foo");
            // First alt fails at the very first 'a' check (input is 'foo'), so
            // the capture is never even attempted. Second alt 'foo' matches.
            assertThat(result.diagnostics()).isEmpty();
        }
    }
}
