package org.pragmatica.peg.perf;

import java.lang.reflect.Method;
import java.util.List;

/**
 * Rebuild the original source text from a CST.
 *
 * <p>Walks the CST in source order. At each leaf (Terminal, Token) emits the node's
 * leading trivia followed by the node's {@code text}. For Error nodes, emits the
 * leading trivia followed by the {@code skippedText}. NonTerminal recurses into
 * children (its own leading/trailing trivia is generally empty because children
 * own their trivia). After the full walk, the root's trailing trivia is appended.
 *
 * <p>If the grammar preserves all trivia, {@code reconstruct(parse(src)).equals(src)}
 * byte-for-byte.
 */
public final class CstReconstruct {

    private CstReconstruct() {}

    public static String reconstruct(Object root) {
        var sb = new StringBuilder();
        emitLeading(sb, root);
        emit(sb, root);
        emitTrailing(sb, root);
        return sb.toString();
    }

    private static void emit(StringBuilder sb, Object node) {
        if (node == null) return;
        var simple = node.getClass().getSimpleName();
        switch (simple) {
            case "Terminal", "Token" -> sb.append(invokeString(node, "text"));
            case "NonTerminal" -> {
                var children = (List<?>) invoke(node, "children");
                for (int i = 0; i < children.size(); i++) {
                    var child = children.get(i);
                    emitLeading(sb, child);
                    emit(sb, child);
                    // Trailing trivia on interior children is emitted only for the last child;
                    // for all others, the next child's leading trivia covers the gap. This
                    // matches how the parser attributes trivia: leading to the upcoming node.
                    if (i == children.size() - 1) {
                        emitTrailing(sb, child);
                    }
                }
            }
            case "Error" -> sb.append(invokeString(node, "skippedText"));
            default -> throw new IllegalArgumentException("Unexpected CST node type: " + node.getClass().getName());
        }
    }

    private static void emitLeading(StringBuilder sb, Object node) {
        var list = (List<?>) invoke(node, "leadingTrivia");
        emitTrivia(sb, list);
    }

    private static void emitTrailing(StringBuilder sb, Object node) {
        var list = (List<?>) invoke(node, "trailingTrivia");
        emitTrivia(sb, list);
    }

    private static void emitTrivia(StringBuilder sb, List<?> list) {
        if (list == null) return;
        for (var t : list) {
            sb.append(invokeString(t, "text"));
        }
    }

    private static String invokeString(Object target, String methodName) {
        var result = invoke(target, methodName);
        return result == null ? "" : result.toString();
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
}
