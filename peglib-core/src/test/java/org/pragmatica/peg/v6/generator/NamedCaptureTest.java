package org.pragmatica.peg.v6.generator;

import org.pragmatica.peg.v6.PegParser;
import org.pragmatica.peg.v6.analyzer.NamedCaptureCause;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 0.6.0 — guards that {@link PegParser#fromGrammar(String)} rejects grammars
 * using named captures ({@code $name<expr>}) or back-references ({@code
 * $name}). Without this guard the 0.6.0 generate-compile-cache pipeline would
 * silently emit a parser that always succeeds at the back-reference site,
 * accepting inputs that ought to fail (e.g. {@code <foo>bar</baz>} matching
 * a tag rule whose close name is supposed to mirror the open name).
 *
 * <p>Implementation roadmap: full runtime support requires the parser to
 * record the source span captured under each name and re-match that text
 * against the token stream at each back-reference — non-trivial in a
 * lex-then-parse architecture because back-references are not regular and
 * cross the lexer boundary. Tracked for a future release.
 */
class NamedCaptureTest {
    @Test
    void namedCapture_isRejectedAtCompileTime() {
        var grammar = """
            Tag <- '<' $name<[a-z]+> '>' Body '</' $name '>'
            Body <- [^<]*
            """;
        var result = PegParser.fromGrammar(grammar);
        assertThat(result.isFailure())
        .isTrue();
        var cause = result.fold(c -> c,
                                __ -> {
                                    throw new AssertionError("expected failure");
                                });
        assertThat(cause)
        .isInstanceOf(NamedCaptureCause.class);
        assertThat(cause.message())
        .contains("named captures")
        .contains("back-references")
        .contains("$name<...>")
        .contains("$name")
        .contains("0.6.0");
    }

    @Test
    void backReferenceAndCapture_areBothFlagged() {
        // A grammar with both a named capture and a matching back-reference
        // should report two occurrences (one of each kind).
        var grammar = """
            Doc <- $tag<[a-z]+> '|' $tag
            """;
        var result = PegParser.fromGrammar(grammar);
        assertThat(result.isFailure())
        .isTrue();
        var cause = (NamedCaptureCause) result.fold(c -> c,
                                                    __ -> {
                                                        throw new AssertionError("expected failure");
                                                    });
        assertThat(cause.occurrences())
        .hasSize(2);
        assertThat(cause.message())
        .contains("$tag<...>")
        .contains("$tag");
    }

    @Test
    void multipleOccurrences_areAllReported() {
        var grammar = """
            Doc  <- Pair Pair
            Pair <- $a<[a-z]+> '=' $a
            """;
        var result = PegParser.fromGrammar(grammar);
        assertThat(result.isFailure())
        .isTrue();
        var cause = (NamedCaptureCause) result.fold(c -> c,
                                                    __ -> {
                                                        throw new AssertionError("expected failure");
                                                    });
        // One Capture + one BackReference inside Pair.
        assertThat(cause.occurrences())
        .hasSize(2);
        assertThat(cause.message())
        .contains("2 unsupported features")
        .contains("named capture")
        .contains("back-reference");
    }

    @Test
    void grammarWithoutCaptures_compilesCleanly() {
        // Sanity: the rejection path does not regress ordinary grammars.
        var grammar = """
            Sum <- Number '+' Number
            Number <- [0-9]+
            """;
        var result = PegParser.fromGrammar(grammar);
        assertThat(result.isSuccess())
        .isTrue();
    }

    @Test
    void captureScopeOnly_isNotFlaggedByDetector() {
        // $(...) with no $name<...> or $name inside it is a no-op for
        // matching, so the detector intentionally lets it through. Whether
        // the rest of the v6 pipeline accepts the grammar is a separate
        // concern (and may itself reject CaptureScope today). The contract
        // we test here is: if it fails, it does NOT fail with
        // NamedCaptureCause.
        var grammar = """
            Doc <- $( 'a' 'b' ) 'c'
            """;
        var result = PegParser.fromGrammar(grammar);
        if (result.isFailure()) {
            var cause = result.fold(c -> c,
                                    __ -> {
                                        throw new AssertionError("unreachable");
                                    });
            assertThat(cause)
            .isNotInstanceOf(NamedCaptureCause.class);
        }
    }
}
