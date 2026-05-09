package org.pragmatica.peg.tree;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StringSpanTest {

    @Nested
    class Construction {
        @Test
        void of_constructsValidSpan() {
            var span = StringSpan.of("hello world", 0, 5);

            assertEquals(5, span.length());
            assertEquals("hello", span.toString());
        }

        @Test
        void of_emptySpan_isLengthZero() {
            var span = StringSpan.of("hello", 2, 2);

            assertEquals(0, span.length());
            assertEquals("", span.toString());
        }

        @Test
        void of_negativeStart_throws() {
            assertThrows(IndexOutOfBoundsException.class,
                         () -> StringSpan.of("hello", -1, 3));
        }

        @Test
        void of_endLessThanStart_throws() {
            assertThrows(IndexOutOfBoundsException.class,
                         () -> StringSpan.of("hello", 3, 1));
        }

        @Test
        void of_endBeyondLength_throws() {
            assertThrows(IndexOutOfBoundsException.class,
                         () -> StringSpan.of("hello", 0, 10));
        }

        @Test
        void ofString_capturesStringDirectly() {
            var src = "hello";
            var span = StringSpan.ofString(src);

            assertEquals(5, span.length());
            // toString() should return the SAME instance — no substring taken
            assertSame(src, span.toString());
        }
    }

    @Nested
    class CharSequenceContract {
        @Test
        void length_matchesEndMinusStart() {
            var span = StringSpan.of("abcdefghij", 2, 7);

            assertEquals(5, span.length());
        }

        @Test
        void charAt_returnsCharsFromOffsetSlice() {
            var span = StringSpan.of("hello world", 6, 11);

            assertEquals('w', span.charAt(0));
            assertEquals('o', span.charAt(1));
            assertEquals('r', span.charAt(2));
            assertEquals('l', span.charAt(3));
            assertEquals('d', span.charAt(4));
        }

        @Test
        void charAt_negativeIndex_throws() {
            var span = StringSpan.of("hello", 0, 5);

            assertThrows(IndexOutOfBoundsException.class, () -> span.charAt(-1));
        }

        @Test
        void charAt_atOrPastLength_throws() {
            var span = StringSpan.of("hello", 0, 5);

            assertThrows(IndexOutOfBoundsException.class, () -> span.charAt(5));
        }

        @Test
        void subSequence_returnsNestedSpan() {
            var outer = StringSpan.of("hello world", 0, 11);
            var inner = outer.subSequence(6, 11);

            assertEquals("world", inner.toString());
        }

        @Test
        void subSequence_outOfBounds_throws() {
            var span = StringSpan.of("hello", 0, 5);

            assertThrows(IndexOutOfBoundsException.class, () -> span.subSequence(0, 10));
        }
    }

    @Nested
    class Materialization {
        @Test
        void toString_idempotent_returnsSameInstance() {
            var span = StringSpan.of("hello world", 0, 5);

            var first = span.toString();
            var second = span.toString();

            assertSame(first, second);
        }

        @Test
        void toString_matchesSubstring() {
            var src = "the quick brown fox";
            var span = StringSpan.of(src, 4, 9);

            assertEquals(src.substring(4, 9), span.toString());
        }
    }

    @Nested
    class EqualityAndHash {
        @Test
        void equals_sameTextDifferentSources_returnsTrue() {
            var a = StringSpan.of("hello", 0, 5);
            var b = StringSpan.of("xxhelloxx", 2, 7);

            assertEquals(a, b);
            assertEquals(b, a);
        }

        @Test
        void equals_differentText_returnsFalse() {
            var a = StringSpan.of("hello", 0, 5);
            var b = StringSpan.of("world", 0, 5);

            assertNotEquals(a, b);
        }

        @Test
        void equals_differentLength_returnsFalse() {
            var a = StringSpan.of("hello", 0, 5);
            var b = StringSpan.of("hello", 0, 3);

            assertNotEquals(a, b);
        }

        @Test
        void equals_self_returnsTrue() {
            var span = StringSpan.of("hello", 0, 5);

            assertEquals(span, span);
        }

        @Test
        void equals_nonStringSpan_returnsFalse() {
            var span = StringSpan.of("hello", 0, 5);

            assertNotEquals(span, "hello");
        }

        @Test
        void hashCode_matchesStringHashCode() {
            var src = "hello world";
            var span = StringSpan.of(src, 6, 11);

            // hashCode() must equal "world".hashCode() so consumers can
            // compare StringSpan-by-content with String-keyed collections.
            assertEquals("world".hashCode(), span.hashCode());
        }

        @Test
        void hashCode_consistentBeforeAndAfterMaterialization() {
            var span = StringSpan.of("hello world", 6, 11);

            int before = span.hashCode();
            span.toString(); // materialize
            int after = span.hashCode();

            assertEquals(before, after);
        }

        @Test
        void hashCode_equalSpans_haveEqualHash() {
            var a = StringSpan.of("hello", 0, 5);
            var b = StringSpan.of("xxhelloxx", 2, 7);

            assertEquals(a.hashCode(), b.hashCode());
        }
    }

    @Nested
    class RoundTrip {
        @Test
        void roundTrip_emptyString() {
            var span = StringSpan.of("", 0, 0);

            assertEquals("", span.toString());
            assertEquals(0, span.length());
        }

        @Test
        void roundTrip_singleChar() {
            var span = StringSpan.of("x", 0, 1);

            assertEquals("x", span.toString());
            assertEquals(1, span.length());
        }

        @Test
        void roundTrip_multilineSource() {
            var src = "line1\nline2\nline3";
            var span = StringSpan.of(src, 6, 11);

            assertEquals("line2", span.toString());
        }

        @Test
        void roundTrip_largeInput_doesNotCopyEagerly() {
            // 100KB string; constructing a span should not materialize the substring
            var huge = "x".repeat(100_000);
            assertDoesNotThrow(() -> StringSpan.of(huge, 50_000, 50_010));
        }

        @Test
        void source_returnsBackingString() {
            var src = "hello world";
            var span = StringSpan.of(src, 6, 11);

            assertSame(src, span.source());
        }

        @Test
        void startEnd_accessors() {
            var span = StringSpan.of("hello world", 6, 11);

            assertEquals(6, span.start());
            assertEquals(11, span.end());
        }
    }

    @Nested
    class CharSequenceCompat {
        @Test
        void usableAsCharSequence() {
            CharSequence cs = StringSpan.of("hello world", 0, 5);

            assertEquals("hello", cs.toString());
            assertEquals(5, cs.length());
            assertEquals('h', cs.charAt(0));
        }

        @Test
        void subSequence_returnsCharSequence() {
            var span = StringSpan.of("hello world", 0, 11);
            CharSequence inner = span.subSequence(0, 5);

            assertTrue(inner instanceof StringSpan);
            assertEquals("hello", inner.toString());
        }
    }
}
