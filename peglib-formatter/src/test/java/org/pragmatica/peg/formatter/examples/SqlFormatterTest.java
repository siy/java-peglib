package org.pragmatica.peg.formatter.examples;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Demo tests for {@link SqlFormatter}.
 */
final class SqlFormatterTest {

    @Nested
    @DisplayName("Basic formatting")
    class Basics {
        @Test
        void upperCasesKeywords() {
            var fmt = SqlFormatter.create();
            var out = fmt.format("select a from t").unwrap();
            assertThat(out).contains("SELECT").contains("FROM").doesNotContainPattern("\\bselect\\b");
        }

        @Test
        void oneClausePerLine() {
            var fmt = SqlFormatter.create();
            var out = fmt.format("select a from t where a = 1").unwrap();
            assertThat(out).contains("\n");
            var lines = out.split("\n");
            assertThat(lines).hasSize(3);
            assertThat(lines[0]).startsWith("SELECT");
            assertThat(lines[1]).startsWith("FROM");
            assertThat(lines[2]).startsWith("WHERE");
        }

        @Test
        void shortColListFitsFlat() {
            var fmt = SqlFormatter.create();
            var out = fmt.format("select a, b, c from t").unwrap();
            var firstLine = out.split("\n")[0];
            assertThat(firstLine).isEqualTo("SELECT a, b, c");
        }
    }

    @Nested
    @DisplayName("Idempotency")
    class Idempotency {
        @Test
        void idempotentOnFixtures() {
            var fmt = SqlFormatter.create();
            List<String> inputs = List.of(
                "select a from t",
                "SELECT a FROM t",
                "  select  a ,  b  from  t  ",
                "select a, b, c from t",
                "select a from t where a = 1",
                "select a, b from t where x <> 'foo'",
                "select x, y, z from tbl where k >= 42"
            );
            for (var input : inputs) {
                assertIdempotent(fmt, input);
            }
        }

        @Test
        void idempotentOnFuzzedInputs() {
            var fmt = SqlFormatter.create();
            var rng = new Random(0xDEADBEEFL);
            for (int i = 0; i < 60; i++) {
                var input = randomQuery(rng);
                assertIdempotent(fmt, input);
            }
        }

        private static void assertIdempotent(SqlFormatter fmt, String input) {
            var first = fmt.format(input);
            assertThat(first.isSuccess())
                .as("first-pass format must succeed for: %s", input)
                .isTrue();
            var once = first.unwrap();
            var twice = fmt.format(once).unwrap();
            assertThat(twice).as("idempotency on: %s", input).isEqualTo(once);
        }

        private static String randomQuery(Random rng) {
            var cols = randomIdentList(rng, 1 + rng.nextInt(4));
            var tables = randomIdentList(rng, 1 + rng.nextInt(2));
            var sb = new StringBuilder();
            sb.append(rng.nextBoolean() ? "select " : "SELECT ").append(cols);
            sb.append(rng.nextBoolean() ? " from " : " FROM ").append(tables);
            if (rng.nextBoolean()) {
                sb.append(rng.nextBoolean() ? " where " : " WHERE ");
                sb.append(randomIdent(rng)).append(' ').append(randomOp(rng)).append(' ').append(randomValue(rng));
            }
            return sb.toString();
        }

        private static String randomIdentList(Random rng, int n) {
            var sb = new StringBuilder();
            for (int i = 0; i < n; i++) {
                if (i > 0) sb.append(", ");
                sb.append(randomIdent(rng));
            }
            return sb.toString();
        }

        private static String randomIdent(Random rng) {
            return "id" + rng.nextInt(100);
        }

        private static String randomOp(Random rng) {
            return new String[]{"=", "<>", "<=", ">=", "<", ">"}[rng.nextInt(6)];
        }

        private static String randomValue(Random rng) {
            return switch (rng.nextInt(3)) {
                case 0 -> String.valueOf(rng.nextInt(1000));
                case 1 -> "'s" + rng.nextInt(100) + "'";
                default -> randomIdent(rng);
            };
        }
    }
}
