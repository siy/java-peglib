package org.pragmatica.peg.incremental.internal;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Production-side tests for {@link LinearProbingLongLongMap}. The experimental
 * sibling has the broader battery; this file exists primarily to lock in the
 * tombstone-accumulation regression that hung {@code IncrementalTriviaParityTest}
 * after the Phase 1.5 promotion of the map into {@link NodeIndex}.
 */
final class LongLongMapTest {
    @Test
    @DisplayName("put then get returns the stored value")
    void put_then_get() {
        LongLongMap map = new LinearProbingLongLongMap();
        map.put(5L, 100L);
        assertThat(map.get(5L)).isEqualTo(100L);
        assertThat(map.containsKey(5L)).isTrue();
        assertThat(map.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("remove then re-put same key returns new value")
    void remove_then_put_same_key() {
        LongLongMap map = new LinearProbingLongLongMap();
        map.put(7L, 70L);
        map.remove(7L);
        map.put(7L, 700L);
        assertThat(map.get(7L)).isEqualTo(700L);
        assertThat(map.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("Tombstone accumulation does NOT hang put (regression: Phase 1.5)")
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void doesNotHangOnTombstoneAccumulation() {
        // Pattern: insert N keys, remove half, insert another N. Without the
        // tombstone-aware resize, this hangs (the second insert phase finds
        // no EMPTY slot once tombstones + occupied saturate the table and the
        // put-probe loop can only stop at EMPTY).
        var map = new LinearProbingLongLongMap(16);
        // small table to stress
        int n = 200;
        for (int i = 0; i < n; i++) {
            map.put(i, i);
        }
        for (int i = 0; i < n; i += 2) {
            map.remove(i);
        }
        for (int i = n; i < 2 * n; i++) {
            map.put(i, i);
        }
        assertThat(map.size()).isEqualTo(n + n / 2);
        // n surviving + n/2 from second batch
        // And every survivor remains retrievable.
        for (int i = 1; i < n; i += 2) {
            assertThat(map.get(i)).isEqualTo(i);
        }
        for (int i = n; i < 2 * n; i++) {
            assertThat(map.get(i)).isEqualTo(i);
        }
    }

    @Test
    @DisplayName("Long alternating put/remove stress does not hang and stays consistent")
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void alternating_put_remove_stress() {
        var map = new LinearProbingLongLongMap(16);
        // Repeatedly fill + drain to age the tombstone count well past threshold.
        for (int cycle = 0; cycle < 50; cycle++) {
            for (int i = 0; i < 100; i++) {
                map.put(i, (long) cycle * 1000 + i);
            }
            for (int i = 0; i < 100; i++) {
                map.remove(i);
            }
        }
        assertThat(map.size()).isZero();
        // Map remains usable after the storm.
        map.put(42L, 42L);
        assertThat(map.get(42L)).isEqualTo(42L);
    }
}
