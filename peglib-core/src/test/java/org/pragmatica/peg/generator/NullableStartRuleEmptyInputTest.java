package org.pragmatica.peg.generator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.assertj.core.api.Assertions.assertThat;

import org.pragmatica.peg.PegParser;

import java.util.concurrent.TimeUnit;

/**
 * 0.7.1 — empty / all-trivia input is only an error when the start rule cannot match empty.
 *
 * <p>The generated {@code parseWithRecovery} used to emit an {@code "empty input"} diagnostic
 * whenever the token stream was empty or consisted purely of trivia, without ever attempting
 * the start rule. That is wrong for a nullable start rule: Java's {@code CompilationUnit} is
 * nullable, so an empty file and a file containing only a license header are both legal — and
 * 71 files in the OpenJDK langtools corpus are exactly that.
 *
 * <p>The fix attempts the start rule once and reports only on genuine failure. Both directions
 * are pinned here, because the cheap way to "fix" this bug is to drop the diagnostic entirely,
 * which would silently accept empty input under every grammar.
 *
 * <p>The timeouts are not incidental. The original unconditional {@code break} guarded against
 * a nullable start rule matching empty forever at end-of-input; the fix keeps that break
 * unconditional, and these timeouts fail loudly if a later change reintroduces the loop.
 */
class NullableStartRuleEmptyInputTest {

    // Recursive Expr forces PARSER classification: a regular grammar would be folded into the
    // lexer and could not serve as a start rule at all.
    private static final String NULLABLE = """
        %whitespace <- ([ \\t\\r\\n] / "//" [^\\n]*)*
        Start <- Expr?
        Expr  <- Word "(" Expr ")" / Word
        Word  <- [a-z]+
        """;

    private static final String NON_NULLABLE = """
        %whitespace <- ([ \\t\\r\\n] / "//" [^\\n]*)*
        Start <- Expr
        Expr  <- Word "(" Expr ")" / Word
        Word  <- [a-z]+
        """;

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void nullableStartRule_acceptsEmptyInput() {
        var result = PegParser.fromGrammar(NULLABLE).unwrap().parse("");

        assertThat(result.diagnostics())
        .as("a nullable start rule matches empty input; no diagnostic is warranted")
        .isEmpty();
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void nullableStartRule_acceptsAllTriviaInput() {
        var result = PegParser.fromGrammar(NULLABLE).unwrap().parse("  // only a comment\n\n");

        assertThat(result.diagnostics())
        .as("all-trivia input is empty input as far as the start rule is concerned")
        .isEmpty();
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void nullableStartRule_stillParsesRealInput() {
        var result = PegParser.fromGrammar(NULLABLE).unwrap().parse("f(x)");

        assertThat(result.diagnostics()).isEmpty();
        assertThat(result.cst().nodeCount()).isGreaterThan(1);
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void nonNullableStartRule_stillRejectsEmptyInput() {
        var result = PegParser.fromGrammar(NON_NULLABLE).unwrap().parse("");

        assertThat(result.diagnostics())
        .as("the start rule requires an Expr, so empty input must still be reported")
        .hasSize(1);
        assertThat(result.diagnostics().get(0).message()).isEqualTo("empty input");
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void nonNullableStartRule_stillRejectsAllTriviaInput() {
        var result = PegParser.fromGrammar(NON_NULLABLE).unwrap().parse("// nothing but a comment\n");

        assertThat(result.diagnostics())
        .as("trivia does not satisfy a non-nullable start rule")
        .hasSize(1);
        assertThat(result.diagnostics().get(0).message()).isEqualTo("empty input");
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void allTriviaInput_roundTripsByteIdentically() {
        var input = "// a comment\n\n   \n";
        var result = PegParser.fromGrammar(NULLABLE).unwrap().parse(input);

        assertThat(result.diagnostics()).isEmpty();
        assertThat(result.cst().reconstruct())
        .as("accepting an all-trivia file must not drop its trivia tokens; the formatter "
            + "reconstructs source from the CST and would silently emit an empty file")
        .isEqualTo(input);
    }
}
