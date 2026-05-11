package org.pragmatica.peg.playground.v6;

import org.junit.jupiter.api.Test;
import org.pragmatica.peg.playground.v6.PlaygroundEngineV6.ParseRequest;

import static org.assertj.core.api.Assertions.assertThat;

class PlaygroundEngineV6Test {

    private static final String GRAMMAR = """
            Sum <- Number '+' Number
            Number <- [0-9]+
            %whitespace <- [ \\t]*
            """;

    @Test
    void run_validInput_returnsOutcomeWithCstAndStats() {
        var request = new ParseRequest(GRAMMAR, "12 + 34");
        var result = PlaygroundEngineV6.run(request);

        assertThat(result.isSuccess()).as("engine should compile and parse: %s", result)
                                      .isTrue();
        var outcome = result.unwrap();
        assertThat(outcome.cst()).isNotNull();
        assertThat(outcome.cst().nodeCount()).isGreaterThan(0);
        assertThat(outcome.stats().nodeCount()).isEqualTo(outcome.cst().nodeCount());
        assertThat(outcome.stats().timeMicros()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void run_invalidGrammar_returnsFailure() {
        var request = new ParseRequest("Broken <- [unclosed", "x");
        var result = PlaygroundEngineV6.run(request);
        assertThat(result.isFailure()).isTrue();
    }

    @Test
    void run_inputWithErrors_reportsDiagnostics() {
        // 'a#x' fails the second position — recovery emits at least one diagnostic.
        var grammar = """
                Pair <- Head 'b'
                Head <- 'a' '#'
                """;
        var request = new ParseRequest(grammar, "a#x");
        var result = PlaygroundEngineV6.run(request);

        assertThat(result.isSuccess()).isTrue();
        var outcome = result.unwrap();
        assertThat(outcome.hasErrors()).isTrue();
        assertThat(outcome.stats().diagnosticCount()).isGreaterThan(0);
    }

    @Test
    void run_triviaCount_reflectsLexedWhitespace() {
        var request = new ParseRequest(GRAMMAR, "1 + 2");
        var outcome = PlaygroundEngineV6.run(request).unwrap();
        // Two whitespace runs lexed as trivia tokens.
        assertThat(outcome.stats().triviaCount()).isGreaterThan(0);
    }
}
