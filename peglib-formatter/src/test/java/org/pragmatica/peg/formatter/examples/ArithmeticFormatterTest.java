package org.pragmatica.peg.formatter.examples;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Demo tests for {@link ArithmeticFormatter}.
 */
final class ArithmeticFormatterTest {

    @Nested
    @DisplayName("Basic formatting")
    class Basics {
        @Test
        void singleNumber() {
            var fmt = ArithmeticFormatter.create();
            assertThat(fmt.format("42").unwrap()).isEqualTo("42");
        }

        @Test
        void simpleAddition() {
            var fmt = ArithmeticFormatter.create();
            assertThat(fmt.format("1+2").unwrap()).isEqualTo("1 + 2");
        }

        @Test
        void mixedPrecedenceKeepsParens() {
            var fmt = ArithmeticFormatter.create();
            assertThat(fmt.format("(1+2)*3").unwrap()).isEqualTo("(1 + 2) * 3");
        }

        @Test
        void stripsRedundantWhitespace() {
            var fmt = ArithmeticFormatter.create();
            assertThat(fmt.format("  1   +   2  ").unwrap()).isEqualTo("1 + 2");
        }
    }

    @Nested
    @DisplayName("Idempotency")
    class Idempotency {
        @Test
        void idempotentOnFixtures() {
            var fmt = ArithmeticFormatter.create();
            List<String> inputs = List.of(
                "1",
                "-42",
                "3.14",
                "1+2",
                "1 + 2",
                "1+2+3",
                "1*2+3",
                "1+2*3",
                "(1+2)",
                "(1+2)*3",
                "1/(2-3)",
                "(1+2)*(3+4)",
                "1+2+3+4+5",
                "((1+2)*(3-4))/5"
            );
            for (var input : inputs) {
                assertIdempotent(fmt, input);
            }
        }

        @Test
        void idempotentOnFuzzedInputs() {
            var fmt = ArithmeticFormatter.create();
            var rng = new Random(0xC0FFEEL);
            for (int i = 0; i < 80; i++) {
                var input = randomExpr(rng, 3);
                assertIdempotent(fmt, input);
            }
        }

        private static void assertIdempotent(ArithmeticFormatter fmt, String input) {
            var first = fmt.format(input);
            assertThat(first.isSuccess())
                .as("first-pass format must succeed for: %s", input)
                .isTrue();
            var once = first.unwrap();
            var twice = fmt.format(once).unwrap();
            assertThat(twice).as("idempotency on: %s", input).isEqualTo(once);
        }

        private static String randomExpr(Random rng, int depth) {
            if (depth == 0 || rng.nextInt(3) == 0) {
                return String.valueOf(rng.nextInt(100));
            }
            char op = "+-*/".charAt(rng.nextInt(4));
            var left = randomExpr(rng, depth - 1);
            var right = randomExpr(rng, depth - 1);
            if (rng.nextBoolean()) {
                return "(" + left + op + right + ")";
            }
            return left + op + right;
        }
    }
}
