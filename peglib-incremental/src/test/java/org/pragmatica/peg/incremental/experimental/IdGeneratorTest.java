package org.pragmatica.peg.incremental.experimental;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 0a foundation tests — single-threaded counter contract for
 * {@link IdGenerator.PerSessionCounter}.
 */
final class IdGeneratorTest {
    @Test
    @DisplayName("First call returns 0")
    void first_call_returns_zero() {
        var gen = new IdGenerator.PerSessionCounter();
        assertThat(gen.next()).isEqualTo(0L);
    }

    @Test
    @DisplayName("Second call returns 1")
    void second_call_returns_one() {
        var gen = new IdGenerator.PerSessionCounter();
        gen.next();
        assertThat(gen.next()).isEqualTo(1L);
    }

    @Test
    @DisplayName("1000 calls return 0..999 in strict order")
    void thousand_calls_strict_order() {
        var gen = new IdGenerator.PerSessionCounter();
        for (long expected = 0; expected < 1000; expected++) {
            assertThat(gen.next()).isEqualTo(expected);
        }
    }

    @Test
    @DisplayName("Two independent counters do not share state")
    void independent_counters_do_not_share_state() {
        var first = new IdGenerator.PerSessionCounter();
        var second = new IdGenerator.PerSessionCounter();
        for (int i = 0; i < 5; i++) {
            first.next();
        }
        assertThat(second.next()).isEqualTo(0L);
        assertThat(second.next()).isEqualTo(1L);
        assertThat(first.next()).isEqualTo(5L);
    }
}
