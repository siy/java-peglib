package org.pragmatica.peg.playground.internal;

import org.junit.jupiter.api.Test;
import org.pragmatica.peg.playground.Stats;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class JsonEncoderTest {

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
}
