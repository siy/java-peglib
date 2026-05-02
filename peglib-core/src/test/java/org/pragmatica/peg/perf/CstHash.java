package org.pragmatica.peg.perf;

import java.lang.reflect.Method;
import java.util.List;

/**
 * Deterministic structural hash over a CST.
 *
 * <p>Uses reflection so it can consume both the library's {@code org.pragmatica.peg.tree.CstNode}
 * and the inner {@code CstNode} type emitted by {@code ParserGenerator} (which is a separate,
 * but structurally compatible, sealed interface).
 *
 * <p>Hash is deterministic across JVM runs: pre-order walk, 64-bit accumulator mixed with an
 * fmix64-style mixer, output as lowercase hex (16 chars).
 *
 * <p>Excluded from the hash by design (per PERF-REWORK-SPEC.md §4.1 / §6.5):
 * <ul>
 *   <li>Trivia attached to any node (CST parity is about tree shape; reconstruction covers trivia)</li>
 *   <li>Line/column in spans — only the integer offset is mixed in</li>
 *   <li>The {@code expected} string on error nodes — diagnostic messages must not influence parity</li>
 * </ul>
 */
public final class CstHash {

    private static final long MIX = 0x9E3779B97F4A7C15L;

    // Node kind ordinals — Terminal=0, NonTerminal=1, Token=2, Error=3.
    private static final int KIND_TERMINAL = 0;
    private static final int KIND_NONTERMINAL = 1;
    private static final int KIND_TOKEN = 2;
    private static final int KIND_ERROR = 3;

    private CstHash() {}

    /**
     * Compute the structural hash of the given CST root.
     *
     * @param root node with the generator/interpreter shape (Terminal/NonTerminal/Token/Error)
     * @return lowercase 16-character hex digest
     */
    public static String cstHash(Object root) {
        var h = new long[]{MIX};
        mix(h, root);
        return String.format("%016x", h[0]);
    }

    private static void mix(long[] h, Object node) {
        if (node == null) {
            mixLong(h, 0xDEADBEEFCAFEL);
            return;
        }
        var simple = node.getClass().getSimpleName();
        switch (simple) {
            case "Terminal" -> mixTerminal(h, node);
            case "NonTerminal" -> mixNonTerminal(h, node);
            case "Token" -> mixToken(h, node);
            case "Error" -> mixError(h, node);
            default -> throw new IllegalArgumentException("Unexpected CST node type: " + node.getClass().getName());
        }
    }

    private static void mixTerminal(long[] h, Object node) {
        mixInt(h, KIND_TERMINAL);
        mixRule(h, invokeString(node, "rule"));
        mixSpan(h, invoke(node, "span"));
        mixString(h, invokeString(node, "text"));
    }

    private static void mixToken(long[] h, Object node) {
        mixInt(h, KIND_TOKEN);
        mixRule(h, invokeString(node, "rule"));
        mixSpan(h, invoke(node, "span"));
        mixString(h, invokeString(node, "text"));
    }

    private static void mixNonTerminal(long[] h, Object node) {
        mixInt(h, KIND_NONTERMINAL);
        mixRule(h, invokeString(node, "rule"));
        mixSpan(h, invoke(node, "span"));
        var children = (List<?>) invoke(node, "children");
        mixInt(h, children.size());
        for (var child : children) {
            mix(h, child);
        }
    }

    private static void mixError(long[] h, Object node) {
        mixInt(h, KIND_ERROR);
        mixRule(h, "<error>");
        mixSpan(h, invoke(node, "span"));
        mixString(h, invokeString(node, "skippedText"));
        // expected intentionally excluded
    }

    /**
     * Invokes the {@code rule()} method and converts the result to a String name.
     * For interpreter nodes, {@code rule()} returns a String directly.
     * For generated parser nodes, {@code rule()} returns a {@code RuleId} object whose
     * {@code name()} method yields the rule name. For Error nodes, {@code rule()} returns
     * null; the caller passes {@code "<error>"} explicitly.
     */
    private static String invokeString(Object node, String methodName) {
        var result = invoke(node, methodName);
        if (result == null) {
            return "";
        }
        if (result instanceof String s) {
            return s;
        }
        var nameMethod = findMethod(result.getClass(), "name");
        try {
            return (String) nameMethod.invoke(result);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("Failed to resolve name() on " + result.getClass(), e);
        }
    }

    private static Object invoke(Object target, String methodName) {
        var method = findMethod(target.getClass(), methodName);
        try {
            return method.invoke(target);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("Failed to invoke " + methodName + "() on " + target.getClass(), e);
        }
    }

    private static Method findMethod(Class<?> cls, String name) {
        try {
            var m = cls.getMethod(name);
            m.setAccessible(true);
            return m;
        } catch (NoSuchMethodException e) {
            throw new RuntimeException("No method " + name + "() on " + cls, e);
        }
    }

    private static void mixSpan(long[] h, Object span) {
        var start = invoke(span, "start");
        var end = invoke(span, "end");
        mixInt(h, (Integer) invoke(start, "offset"));
        mixInt(h, (Integer) invoke(end, "offset"));
    }

    private static void mixRule(long[] h, String ruleName) {
        mixString(h, ruleName == null ? "" : ruleName);
    }

    private static void mixString(long[] h, String s) {
        if (s == null) {
            mixInt(h, -1);
            return;
        }
        mixInt(h, s.length());
        for (int i = 0; i < s.length(); i++) {
            mixInt(h, s.charAt(i));
        }
    }

    private static void mixInt(long[] h, int v) {
        mixLong(h, v & 0xFFFFFFFFL);
    }

    private static void mixLong(long[] h, long v) {
        long x = h[0] ^ v;
        x *= MIX;
        x ^= x >>> 33;
        x *= MIX;
        x ^= x >>> 33;
        h[0] = x;
    }
}
