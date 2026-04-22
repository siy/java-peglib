package org.pragmatica.peg.formatter.examples;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Demo tests for {@link JsonFormatter}.
 *
 * <p>Primary correctness gate: idempotency — {@code format(format(x)) ==
 * format(x)} for every successfully parsed input. Secondary checks assert
 * specific formatting outputs on a handful of representative inputs.
 */
final class JsonFormatterTest {

    @Nested
    @DisplayName("Basic formatting")
    class Basics {
        @Test
        void formatsNull() {
            var fmt = JsonFormatter.create();
            assertThat(fmt.format("null").unwrap()).isEqualTo("null");
        }

        @Test
        void formatsBooleans() {
            var fmt = JsonFormatter.create();
            assertThat(fmt.format("true").unwrap()).isEqualTo("true");
            assertThat(fmt.format("false").unwrap()).isEqualTo("false");
        }

        @Test
        void formatsNumber() {
            var fmt = JsonFormatter.create();
            assertThat(fmt.format("42").unwrap()).isEqualTo("42");
            assertThat(fmt.format("-3.14").unwrap()).isEqualTo("-3.14");
        }

        @Test
        void formatsString() {
            var fmt = JsonFormatter.create();
            assertThat(fmt.format("\"hello\"").unwrap()).isEqualTo("\"hello\"");
        }

        @Test
        void formatsEmptyCollections() {
            var fmt = JsonFormatter.create();
            assertThat(fmt.format("[]").unwrap()).isEqualTo("[]");
            assertThat(fmt.format("{}").unwrap()).isEqualTo("{}");
        }

        @Test
        void formatsShortArrayFlat() {
            var fmt = JsonFormatter.create();
            assertThat(fmt.format("[1, 2, 3]").unwrap()).isEqualTo("[1, 2, 3]");
        }

        @Test
        void formatsShortObjectFlat() {
            var fmt = JsonFormatter.create();
            // Width 60 - short object should fit on one line
            var out = fmt.format("{\"a\": 1}").unwrap();
            assertThat(out).contains("\"a\"").contains("1");
        }

        @Test
        void formatsLongArrayWithBreaks() {
            var fmt = JsonFormatter.create();
            var input = "[\"aaaaaaaaaaaa\",\"bbbbbbbbbbbb\",\"cccccccccccc\",\"dddddddddddd\",\"eeeeeeeeeeee\"]";
            var out = fmt.format(input).unwrap();
            assertThat(out).contains("\n"); // must have broken onto multiple lines
        }
    }

    @Nested
    @DisplayName("Idempotency")
    class Idempotency {
        @Test
        void idempotentOnFixtures() {
            var fmt = JsonFormatter.create();
            List<String> inputs = List.of(
                "null",
                "true",
                "false",
                "42",
                "-17",
                "3.14",
                "\"\"",
                "\"hello world\"",
                "[]",
                "{}",
                "[1, 2, 3]",
                "[1,2,3]",
                "[ 1 , 2 , 3 ]",
                "{\"a\": 1}",
                "{\"a\":1,\"b\":2}",
                "{\"x\": [1, 2, 3]}",
                "[[1, 2], [3, 4]]",
                "{\"outer\": {\"inner\": [true, false, null]}}",
                "[\"aaaaaaaaaa\", \"bbbbbbbbbb\", \"cccccccccc\", \"dddddddddd\"]",
                "{\"name\": \"alice\", \"age\": 30, \"tags\": [\"admin\", \"user\"]}"
            );
            for (var input : inputs) {
                assertIdempotent(fmt, input);
            }
        }

        @Test
        void idempotentOnFuzzedInputs() {
            var fmt = JsonFormatter.create();
            var rng = new Random(0xB16_B00BL);
            for (int i = 0; i < 60; i++) {
                var input = randomJson(rng, 3);
                assertIdempotent(fmt, input);
            }
        }

        private static void assertIdempotent(JsonFormatter fmt, String input) {
            var first = fmt.format(input);
            assertThat(first.isSuccess())
                .as("first-pass format must succeed for: %s", input)
                .isTrue();
            var once = first.unwrap();
            var twice = fmt.format(once).unwrap();
            assertThat(twice).as("idempotency on: %s", input).isEqualTo(once);
        }

        private static String randomJson(Random rng, int depth) {
            if (depth == 0 || rng.nextInt(3) == 0) {
                return randomScalar(rng);
            }
            int branch = rng.nextInt(2);
            if (branch == 0) {
                var items = new StringBuilder("[");
                int n = rng.nextInt(5);
                for (int i = 0; i < n; i++) {
                    if (i > 0) items.append(", ");
                    items.append(randomJson(rng, depth - 1));
                }
                items.append("]");
                return items.toString();
            }
            var obj = new StringBuilder("{");
            int n = rng.nextInt(4);
            for (int i = 0; i < n; i++) {
                if (i > 0) obj.append(", ");
                obj.append('"').append("k").append(i).append('"').append(": ");
                obj.append(randomJson(rng, depth - 1));
            }
            obj.append("}");
            return obj.toString();
        }

        private static String randomScalar(Random rng) {
            return switch (rng.nextInt(5)) {
                case 0 -> "null";
                case 1 -> "true";
                case 2 -> "false";
                case 3 -> String.valueOf(rng.nextInt(1000));
                default -> "\"s" + rng.nextInt(100) + "\"";
            };
        }
    }
}
