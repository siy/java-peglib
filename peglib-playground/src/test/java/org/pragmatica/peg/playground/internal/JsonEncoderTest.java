package org.pragmatica.peg.playground.internal;

import org.junit.jupiter.api.Test;
import org.pragmatica.peg.playground.Stats;
import org.pragmatica.peg.v6.PegParser;
import org.pragmatica.peg.v6.diagnostic.Diagnostic;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class JsonEncoderTest {

    private static final String GRAMMAR = """
            Sum <- Number '+' Number
            Number <- [0-9]+
            %whitespace <- [ \\t]*
            """;

    @Test
    void encodesPrimitives() {
        assertThat(JsonEncoder.encode("hello")).isEqualTo("\"hello\"");
        assertThat(JsonEncoder.encode(42L)).isEqualTo("42");
        assertThat(JsonEncoder.encode(true)).isEqualTo("true");
        assertThat(JsonEncoder.encode(null)).isEqualTo("null");
    }

    @Test
    void escapesControlChars() {
        String encoded = JsonEncoder.encode("a\nb\"c\\d");
        assertThat(encoded).isEqualTo("\"a\\nb\\\"c\\\\d\"");
    }

    @Test
    void encodesObjectPreservingKeys() {
        String encoded = JsonEncoder.encode(Map.of("k", "v", "n", 1L));
        var decoded = JsonDecoder.decodeObject(encoded);
        assertThat(decoded).containsEntry("k", "v").containsEntry("n", 1L);
    }

    @Test
    void encodesArray() {
        String encoded = JsonEncoder.encode(List.of("a", "b"));
        assertThat(encoded).isEqualTo("[\"a\",\"b\"]");
    }

    @Test
    void encodesStatsWithAllFields() {
        var stats = new Stats(123L, 5, 2, 10, 3, 4, 5, 1, 0);
        String encoded = JsonEncoder.encode(stats);
        var decoded = JsonDecoder.decodeObject(encoded);
        assertThat(decoded).containsEntry("timeMicros", 123L)
                           .containsEntry("nodeCount", 5L)
                           .containsEntry("triviaCount", 2L)
                           .containsEntry("ruleEntries", 10L)
                           .containsEntry("cacheHits", 3L)
                           .containsEntry("cutsFired", 1L);
    }

    @Test
    void encodesTreeWithFrontendNodeShape() {
        var cst = PegParser.fromGrammar(GRAMMAR)
                           .expect("grammar should compile")
                           .parse("12 + 34")
                           .cst();

        var decoded = JsonDecoder.decodeObject(JsonEncoder.encodeTree(cst));

        assertThat(decoded).containsKeys("kind", "rule", "start", "end", "line", "column");
        assertThat(decoded.get("kind")).isIn("non-terminal", "terminal", "error");
        assertThat(decoded.get("rule")).isInstanceOf(String.class);
    }

    @Test
    void encodesTreeChildrenForBranchNodes() {
        var cst = PegParser.fromGrammar(GRAMMAR)
                           .expect("grammar should compile")
                           .parse("12 + 34")
                           .cst();

        var decoded = JsonDecoder.decodeObject(JsonEncoder.encodeTree(cst));

        assertThat(decoded.get("kind")).isEqualTo("non-terminal");
        assertThat(decoded.get("children")).isInstanceOf(List.class);
        assertThat((List< ? >) decoded.get("children")).isNotEmpty();
    }

    /**
     * Trivia is only emitted where it sits outside a node's token span, so the
     * fixture leads with whitespace; trivia interior to a node span is not
     * attached to any node in 0.6.x.
     */
    @Test
    void encodesTriviaWithKindNames() {
        var cst = PegParser.fromGrammar(GRAMMAR)
                           .expect("grammar should compile")
                           .parse(" 12 + 34")
                           .cst();

        var encoded = JsonEncoder.encodeTree(cst);

        assertThat(encoded).contains("\"leadingTrivia\"")
                           .contains("\"whitespace\"");
    }

    @Test
    void encodesDiagnosticsWithComputedLineAndColumn() {
        var input = "line one\nline two\nline three";
        var diagnostic = Diagnostic.error(input.indexOf("two"), 3, "unexpected token", "';'", "two");

        var encoded = JsonEncoder.encode(JsonEncoder.Diagnostics.diagnostics(List.of(diagnostic), input));
        var decoded = (List< ? >) JsonDecoder.decode(encoded);

        assertThat(decoded).hasSize(1);
        assertThat(asJsonObject(decoded.getFirst())).containsEntry("severity", "error")
                                                    .containsEntry("message", "unexpected token")
                                                    .containsEntry("line", 2L)
                                                    .containsEntry("column", 6L)
                                                    .containsEntry("expected", "';'")
                                                    .containsEntry("found", "two");
    }

    @Test
    void encodesEmptyDiagnosticsAsEmptyArray() {
        var encoded = JsonEncoder.encode(JsonEncoder.Diagnostics.diagnostics(List.of(), "abc"));

        assertThat(encoded).isEqualTo("[]");
    }

    /** Decoded JSON objects arrive as raw {@code Object}; AssertJ needs the key type. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> asJsonObject(Object value) {
        return (Map<String, Object>) value;
    }
}
