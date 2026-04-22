package org.pragmatica.peg.formatter;

import org.junit.jupiter.api.Test;
import org.pragmatica.peg.formatter.examples.ArithmeticFormatter;
import org.pragmatica.peg.formatter.examples.JsonFormatter;
import org.pragmatica.peg.formatter.examples.SqlFormatter;

import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cross-demo idempotency harness: for each demo formatter, fuzz ~50-100
 * random inputs and assert {@code format(format(x)) == format(x)}.
 *
 * <p>Per-demo tests also assert idempotency on fixed fixtures in their own
 * test classes — this test is the aggregate smoke to make sure the property
 * is preserved as a cross-cutting framework-level invariant.
 */
final class IdempotencyTest {

    @Test
    void jsonIsIdempotent() {
        var fmt = JsonFormatter.create();
        var rng = new Random(0x1234_5678L);
        for (int i = 0; i < 50; i++) {
            var input = randomJson(rng, 3);
            var first = fmt.format(input).unwrap();
            var second = fmt.format(first).unwrap();
            assertThat(second).isEqualTo(first);
        }
    }

    @Test
    void arithmeticIsIdempotent() {
        var fmt = ArithmeticFormatter.create();
        var rng = new Random(0xABCD_EF01L);
        for (int i = 0; i < 50; i++) {
            var input = randomExpr(rng, 3);
            var first = fmt.format(input).unwrap();
            var second = fmt.format(first).unwrap();
            assertThat(second).isEqualTo(first);
        }
    }

    @Test
    void sqlIsIdempotent() {
        var fmt = SqlFormatter.create();
        var rng = new Random(0xF00DBABEL);
        for (int i = 0; i < 50; i++) {
            var input = randomSql(rng);
            var first = fmt.format(input).unwrap();
            var second = fmt.format(first).unwrap();
            assertThat(second).isEqualTo(first);
        }
    }

    // --- generators ---------------------------------------------------------

    private static String randomJson(Random rng, int depth) {
        if (depth == 0 || rng.nextInt(3) == 0) {
            return switch (rng.nextInt(5)) {
                case 0 -> "null";
                case 1 -> "true";
                case 2 -> "false";
                case 3 -> String.valueOf(rng.nextInt(1000));
                default -> "\"s" + rng.nextInt(100) + "\"";
            };
        }
        if (rng.nextBoolean()) {
            var n = rng.nextInt(4);
            var sb = new StringBuilder("[");
            for (int i = 0; i < n; i++) {
                if (i > 0) sb.append(", ");
                sb.append(randomJson(rng, depth - 1));
            }
            sb.append("]");
            return sb.toString();
        }
        var n = rng.nextInt(3);
        var sb = new StringBuilder("{");
        for (int i = 0; i < n; i++) {
            if (i > 0) sb.append(", ");
            sb.append('"').append("k").append(i).append('"').append(": ");
            sb.append(randomJson(rng, depth - 1));
        }
        sb.append("}");
        return sb.toString();
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

    private static String randomSql(Random rng) {
        var n = 1 + rng.nextInt(3);
        var cols = new StringBuilder();
        for (int i = 0; i < n; i++) {
            if (i > 0) cols.append(", ");
            cols.append("id").append(rng.nextInt(100));
        }
        var out = new StringBuilder("select ").append(cols).append(" from t").append(rng.nextInt(10));
        if (rng.nextBoolean()) {
            out.append(" where c").append(rng.nextInt(10)).append(" = ").append(rng.nextInt(1000));
        }
        return out.toString();
    }
}
