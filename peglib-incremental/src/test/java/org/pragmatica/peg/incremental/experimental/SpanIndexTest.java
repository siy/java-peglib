package org.pragmatica.peg.incremental.experimental;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link SpanIndex} — the path-A external offset store.
 *
 * <p>Validates the round-trip, the in-place shift semantics, copy isolation,
 * and stress under random workloads.
 */
final class SpanIndexTest {

    @Nested
    @DisplayName("put / accessors")
    class PutTests {

        @Test
        @DisplayName("Round-trip: put then read back start/end")
        void put_round_trip() {
            var index = new SpanIndex(8);
            index.put(42L, 100, 200);

            assertThat(index.contains(42L)).isTrue();
            assertThat(index.startOffset(42L)).isEqualTo(100);
            assertThat(index.endOffset(42L)).isEqualTo(200);
            assertThat(index.size()).isEqualTo(1);
        }

        @Test
        @DisplayName("Multiple distinct ids: each round-trips independently")
        void multiple_ids_independent() {
            var index = new SpanIndex(8);
            index.put(1L, 0, 10);
            index.put(2L, 10, 20);
            index.put(3L, 20, 30);

            assertThat(index.startOffset(1L)).isEqualTo(0);
            assertThat(index.endOffset(1L)).isEqualTo(10);
            assertThat(index.startOffset(2L)).isEqualTo(10);
            assertThat(index.endOffset(2L)).isEqualTo(20);
            assertThat(index.startOffset(3L)).isEqualTo(20);
            assertThat(index.endOffset(3L)).isEqualTo(30);
            assertThat(index.size()).isEqualTo(3);
        }

        @Test
        @DisplayName("Overwrite same id replaces the offsets, size unchanged")
        void overwrite_same_id() {
            var index = new SpanIndex(8);
            index.put(1L, 0, 10);
            index.put(1L, 100, 200);

            assertThat(index.startOffset(1L)).isEqualTo(100);
            assertThat(index.endOffset(1L)).isEqualTo(200);
            assertThat(index.size()).isEqualTo(1);
        }

        @Test
        @DisplayName("Absent id: contains() false, accessors throw IllegalStateException")
        void absent_id_throws() {
            var index = new SpanIndex(8);
            assertThat(index.contains(99L)).isFalse();
            assertThatThrownBy(() -> index.startOffset(99L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("99");
            assertThatThrownBy(() -> index.endOffset(99L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("99");
        }

        @Test
        @DisplayName("Zero offsets are valid (not confused with MISSING sentinel)")
        void zero_offsets_valid() {
            var index = new SpanIndex(8);
            index.put(1L, 0, 0);

            assertThat(index.contains(1L)).isTrue();
            assertThat(index.startOffset(1L)).isEqualTo(0);
            assertThat(index.endOffset(1L)).isEqualTo(0);
        }
    }

    @Nested
    @DisplayName("shift")
    class ShiftTests {

        @Test
        @DisplayName("Updates entries with start >= afterOffset, leaves others untouched")
        void shift_partition() {
            var index = new SpanIndex(8);
            index.put(1L, 0, 5);    // entirely before edit
            index.put(2L, 5, 10);   // start equals edit boundary — INCLUDED
            index.put(3L, 10, 20);  // entirely after edit

            index.shift(5, 100);

            // id=1: untouched
            assertThat(index.startOffset(1L)).isEqualTo(0);
            assertThat(index.endOffset(1L)).isEqualTo(5);
            // id=2: shifted
            assertThat(index.startOffset(2L)).isEqualTo(105);
            assertThat(index.endOffset(2L)).isEqualTo(110);
            // id=3: shifted
            assertThat(index.startOffset(3L)).isEqualTo(110);
            assertThat(index.endOffset(3L)).isEqualTo(120);
        }

        @Test
        @DisplayName("delta=0 is a no-op (no walk performed; values unchanged)")
        void shift_zero_delta_noop() {
            var index = new SpanIndex(8);
            index.put(1L, 0, 5);
            index.put(2L, 5, 10);

            index.shift(0, 0);

            assertThat(index.startOffset(1L)).isEqualTo(0);
            assertThat(index.endOffset(1L)).isEqualTo(5);
            assertThat(index.startOffset(2L)).isEqualTo(5);
            assertThat(index.endOffset(2L)).isEqualTo(10);
        }

        @Test
        @DisplayName("Negative delta shifts left (deletion case)")
        void shift_negative_delta() {
            var index = new SpanIndex(8);
            index.put(1L, 0, 5);
            index.put(2L, 10, 20);

            index.shift(10, -3);

            assertThat(index.startOffset(1L)).isEqualTo(0);
            assertThat(index.endOffset(1L)).isEqualTo(5);
            assertThat(index.startOffset(2L)).isEqualTo(7);
            assertThat(index.endOffset(2L)).isEqualTo(17);
        }

        @Test
        @DisplayName("All entries before afterOffset → no entry touched")
        void shift_all_before_no_op() {
            var index = new SpanIndex(8);
            index.put(1L, 0, 5);
            index.put(2L, 5, 8);

            index.shift(100, 50);

            assertThat(index.startOffset(1L)).isEqualTo(0);
            assertThat(index.endOffset(1L)).isEqualTo(5);
            assertThat(index.startOffset(2L)).isEqualTo(5);
            assertThat(index.endOffset(2L)).isEqualTo(8);
        }
    }

    @Nested
    @DisplayName("copy")
    class CopyTests {

        @Test
        @DisplayName("Independent state: mutations on copy do not affect original")
        void copy_independence_forward() {
            var original = new SpanIndex(8);
            original.put(1L, 0, 5);
            original.put(2L, 5, 10);

            var copy = original.copy();
            copy.put(3L, 10, 20);
            copy.put(1L, 999, 1000);
            copy.shift(0, 100);

            // Original unchanged
            assertThat(original.size()).isEqualTo(2);
            assertThat(original.startOffset(1L)).isEqualTo(0);
            assertThat(original.endOffset(1L)).isEqualTo(5);
            assertThat(original.startOffset(2L)).isEqualTo(5);
            assertThat(original.endOffset(2L)).isEqualTo(10);
            assertThat(original.contains(3L)).isFalse();
        }

        @Test
        @DisplayName("Independent state: mutations on original do not affect copy")
        void copy_independence_reverse() {
            var original = new SpanIndex(8);
            original.put(1L, 0, 5);

            var copy = original.copy();
            original.put(1L, 100, 200);

            // Copy retains the snapshot.
            assertThat(copy.startOffset(1L)).isEqualTo(0);
            assertThat(copy.endOffset(1L)).isEqualTo(5);
        }
    }

    @Nested
    @DisplayName("stress")
    class StressTests {

        @Test
        @DisplayName("1000 distinct random ids: insert, shift, verify each readback")
        void stress_thousand_entries() {
            var rng = new Random(0xC0FFEEL);
            var index = new SpanIndex(2048);
            var oracle = new HashMap<Long, int[]>(); // id -> [start, end]

            // Insert 1000 distinct ids with random non-overlapping-ish offsets.
            // (Overlap doesn't matter for the index — it's a flat map — but
            // we want predictable shift partitioning.)
            for (int i = 0; i < 1000; i++) {
                long id;
                do {
                    id = rng.nextLong();
                } while (oracle.containsKey(id));
                int start = i * 10;
                int end = start + 5 + rng.nextInt(5);
                index.put(id, start, end);
                oracle.put(id, new int[]{start, end});
            }
            assertThat(index.size()).isEqualTo(1000);

            // Verify all readbacks pre-shift.
            for (Map.Entry<Long, int[]> e : oracle.entrySet()) {
                assertThat(index.startOffset(e.getKey())).isEqualTo(e.getValue()[0]);
                assertThat(index.endOffset(e.getKey())).isEqualTo(e.getValue()[1]);
            }

            // Shift at offset 5000 (mid-buffer) by +1000.
            int afterOffset = 5000;
            int delta = 1000;
            index.shift(afterOffset, delta);
            for (Map.Entry<Long, int[]> e : oracle.entrySet()) {
                int s = e.getValue()[0];
                if (s >= afterOffset) {
                    e.getValue()[0] = s + delta;
                    e.getValue()[1] = e.getValue()[1] + delta;
                }
            }

            // Verify all readbacks post-shift.
            for (Map.Entry<Long, int[]> e : oracle.entrySet()) {
                assertThat(index.startOffset(e.getKey())).isEqualTo(e.getValue()[0]);
                assertThat(index.endOffset(e.getKey())).isEqualTo(e.getValue()[1]);
            }
        }
    }
}
