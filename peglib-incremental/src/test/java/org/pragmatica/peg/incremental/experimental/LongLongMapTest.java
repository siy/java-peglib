package org.pragmatica.peg.incremental.experimental;

import java.util.HashMap;
import java.util.Random;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 0a foundation tests for {@link LinearProbingLongLongMap}. Covers the
 * whole {@link LongLongMap} contract plus implementation-specific concerns:
 * resize correctness, tombstone behaviour, hash-collision stress.
 */
final class LongLongMapTest {
    @Test
    @DisplayName("Empty map: size 0, no key contained, get returns MISSING")
    void empty_map_state() {
        LongLongMap map = new LinearProbingLongLongMap();
        assertThat(map.size()).isZero();
        assertThat(map.containsKey(0L)).isFalse();
        assertThat(map.get(0L)).isEqualTo(LongLongMap.MISSING);
        assertThat(map.containsKey(Long.MAX_VALUE)).isFalse();
        assertThat(map.containsKey(- 1L)).isFalse();
    }

    @Test
    @DisplayName("put then get returns the stored value, containsKey true")
    void put_then_get() {
        LongLongMap map = new LinearProbingLongLongMap();
        map.put(5L, 100L);
        assertThat(map.get(5L)).isEqualTo(100L);
        assertThat(map.containsKey(5L)).isTrue();
        assertThat(map.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("put on existing key overwrites, size unchanged")
    void put_overwrites_existing_key() {
        LongLongMap map = new LinearProbingLongLongMap();
        map.put(5L, 100L);
        map.put(5L, 200L);
        assertThat(map.get(5L)).isEqualTo(200L);
        assertThat(map.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("1000 random distinct keys all retrievable")
    void thousand_random_keys() {
        LongLongMap map = new LinearProbingLongLongMap();
        var rng = new Random(0xCAFEBABEL);
        var oracle = new HashMap<Long, Long>();
        while (oracle.size() < 1000) {
            long k = Math.abs(rng.nextLong() % Long.MAX_VALUE);
            if (oracle.containsKey(k)) {
                continue;
            }
            long v = rng.nextLong();
            oracle.put(k, v);
            map.put(k, v);
        }
        assertThat(map.size()).isEqualTo(1000);
        for (var entry : oracle.entrySet()) {
            assertThat(map.containsKey(entry.getKey())).isTrue();
            assertThat(map.get(entry.getKey())).isEqualTo(entry.getValue());
        }
    }

    @Test
    @DisplayName("Pre-sized to 16 still grows correctly to hold 100 entries")
    void resize_from_small_initial_capacity() {
        LongLongMap map = new LinearProbingLongLongMap(16);
        for (long k = 0; k < 100; k++) {
            map.put(k, k * 10);
        }
        assertThat(map.size()).isEqualTo(100);
        for (long k = 0; k < 100; k++) {
            assertThat(map.containsKey(k)).isTrue();
            assertThat(map.get(k)).isEqualTo(k * 10);
        }
    }

    @Test
    @DisplayName("remove on present key clears it, size decremented, neighbours intact")
    void remove_present_key() {
        LongLongMap map = new LinearProbingLongLongMap();
        map.put(1L, 10L);
        map.put(2L, 20L);
        map.put(3L, 30L);
        map.remove(2L);
        assertThat(map.containsKey(2L)).isFalse();
        assertThat(map.get(2L)).isEqualTo(LongLongMap.MISSING);
        assertThat(map.size()).isEqualTo(2);
        assertThat(map.get(1L)).isEqualTo(10L);
        assertThat(map.get(3L)).isEqualTo(30L);
    }

    @Test
    @DisplayName("remove on absent key is a no-op")
    void remove_absent_key_is_noop() {
        LongLongMap map = new LinearProbingLongLongMap();
        map.put(1L, 10L);
        map.remove(999L);
        assertThat(map.size()).isEqualTo(1);
        assertThat(map.get(1L)).isEqualTo(10L);
    }

    @Test
    @DisplayName("remove then put same key returns new value")
    void remove_then_put_same_key() {
        LongLongMap map = new LinearProbingLongLongMap();
        map.put(7L, 70L);
        map.remove(7L);
        map.put(7L, 700L);
        assertThat(map.get(7L)).isEqualTo(700L);
        assertThat(map.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("Tombstone correctness: alternating put/remove of 1000 distinct keys")
    void tombstone_correctness_under_alternating_ops() {
        LongLongMap map = new LinearProbingLongLongMap(16);
        var rng = new Random(0xDEADBEEFL);
        var oracle = new HashMap<Long, Long>();
        long[] keys = new long[1000];
        for (int i = 0; i < keys.length; i++) {
            keys[i] = (long) i * 31 + rng.nextInt(7);
        }
        for (int i = 0; i < keys.length; i++) {
            long k = keys[i];
            if ((i & 1) == 0) {
                long v = rng.nextLong();
                map.put(k, v);
                oracle.put(k, v);
            } else {
                map.remove(k);
                oracle.remove(k);
            }
        }
        // Drive a final put on every key so the surviving state is well-defined.
        for (long k : keys) {
            map.put(k, k + 1);
            oracle.put(k, k + 1);
        }
        assertThat(map.size()).isEqualTo(oracle.size());
        for (var entry : oracle.entrySet()) {
            assertThat(map.get(entry.getKey())).isEqualTo(entry.getValue());
        }
    }

    @Test
    @DisplayName("clear empties the map; keys no longer contained")
    void clear_empties_map() {
        LongLongMap map = new LinearProbingLongLongMap();
        for (long k = 0; k < 50; k++) {
            map.put(k, k);
        }
        map.clear();
        assertThat(map.size()).isZero();
        for (long k = 0; k < 50; k++) {
            assertThat(map.containsKey(k)).isFalse();
            assertThat(map.get(k)).isEqualTo(LongLongMap.MISSING);
        }
        // Map remains usable after clear.
        map.put(123L, 456L);
        assertThat(map.get(123L)).isEqualTo(456L);
    }

    @Test
    @DisplayName("copy is independent: mutations on original do not affect copy")
    void copy_independence() {
        LongLongMap original = new LinearProbingLongLongMap();
        original.put(1L, 10L);
        original.put(2L, 20L);
        original.put(3L, 30L);
        LongLongMap copy = original.copy();
        original.put(1L, 999L);
        original.remove(2L);
        original.put(99L, 9900L);
        assertThat(copy.size()).isEqualTo(3);
        assertThat(copy.get(1L)).isEqualTo(10L);
        assertThat(copy.get(2L)).isEqualTo(20L);
        assertThat(copy.get(3L)).isEqualTo(30L);
        assertThat(copy.containsKey(99L)).isFalse();
        // Mutating the copy must not leak back into original either.
        copy.put(7L, 70L);
        assertThat(original.containsKey(7L)).isFalse();
    }

    @Test
    @DisplayName("Hash-collision stress: keys hashing to identical slot all retrievable")
    void hash_collision_stress() {
        // capacity is rounded up to power-of-two >= 16; for capacity 16, slot = hash & 15.
        // Long.hashCode(k) = (int)(k ^ (k >>> 32)). For k = 0, 16, 32, 48 (small longs)
        // the upper 32 bits are 0 so hashCode == (int) k, and (int) k & 15 == 0.
        // All four keys collide in slot 0 — exercises linear probing.
        LongLongMap map = new LinearProbingLongLongMap(16);
        long[] colliders = {0L, 16L, 32L, 48L, 64L, 80L, 96L};
        for (int i = 0; i < colliders.length; i++) {
            map.put(colliders[i], (long)(i + 1) * 1000L);
        }
        assertThat(map.size()).isEqualTo(colliders.length);
        for (int i = 0; i < colliders.length; i++) {
            assertThat(map.containsKey(colliders[i])).isTrue();
            assertThat(map.get(colliders[i])).isEqualTo((long)(i + 1) * 1000L);
        }
        // Remove from the middle of the probe chain; remaining keys must stay reachable.
        map.remove(32L);
        assertThat(map.containsKey(32L)).isFalse();
        for (long k : colliders) {
            if (k == 32L) {
                continue;
            }
            assertThat(map.containsKey(k)).as("key %d after removing 32", k)
                      .isTrue();
        }
        // Re-insert across the tombstone — still finds itself.
        map.put(32L, 12345L);
        assertThat(map.get(32L)).isEqualTo(12345L);
    }

    @Test
    @DisplayName("Negative keys behave like any other long")
    void negative_keys_supported() {
        LongLongMap map = new LinearProbingLongLongMap();
        map.put(- 1L, 100L);
        map.put(- 1000000L, 200L);
        map.put(Long.MIN_VALUE + 1, 300L);
        // avoid colliding with MISSING semantics
        assertThat(map.get(- 1L)).isEqualTo(100L);
        assertThat(map.get(- 1000000L)).isEqualTo(200L);
        assertThat(map.get(Long.MIN_VALUE + 1)).isEqualTo(300L);
        assertThat(map.size()).isEqualTo(3);
        map.remove(- 1000000L);
        assertThat(map.containsKey(- 1000000L)).isFalse();
        assertThat(map.size()).isEqualTo(2);
    }
}
