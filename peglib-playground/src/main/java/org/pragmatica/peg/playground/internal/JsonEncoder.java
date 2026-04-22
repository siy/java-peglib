package org.pragmatica.peg.playground.internal;

import org.pragmatica.peg.error.Diagnostic;
import org.pragmatica.peg.playground.ParseTracer;
import org.pragmatica.peg.playground.Stats;
import org.pragmatica.peg.tree.CstNode;
import org.pragmatica.peg.tree.Trivia;

import java.util.List;
import java.util.Map;

/**
 * Minimal JSON encoder tailored for the playground server's response
 * payloads. No external dependencies; handles the specific shapes used by
 * {@code PlaygroundServer.parseEndpoint} — CST trees, diagnostic lists,
 * stats, and plain {@code Map<String, Object>} response envelopes.
 *
 * <p>Produced output is RFC 8259 compliant for the shapes we emit.
 * Strings are escaped per spec (quotes, backslashes, control chars).
 * Numbers use Java default toString(); booleans/null are literal.
 */
public final class JsonEncoder {
    private JsonEncoder() {}

    public static String encode(Object value) {
        var sb = new StringBuilder();
        write(sb, value);
        return sb.toString();
    }

    public static String encodeTree(CstNode node) {
        var sb = new StringBuilder();
        writeNode(sb, node);
        return sb.toString();
    }

    private static void write(StringBuilder sb, Object value) {
        switch (value) {
            case null -> sb.append("null");
            case String s -> writeString(sb, s);
            case Boolean b -> sb.append(b);
            case Number n -> sb.append(n);
            case CstNode node -> writeNode(sb, node);
            case Trivia t -> writeTrivia(sb, t);
            case Diagnostic d -> writeDiagnostic(sb, d);
            case Stats s -> writeStats(sb, s);
            case Map<?, ?> map -> writeObject(sb, map);
            case List<?> list -> writeArray(sb, list);
            default -> writeString(sb, value.toString());
        }
    }

    private static void writeObject(StringBuilder sb, Map<?, ?> map) {
        sb.append('{');
        boolean first = true;
        for (var entry : map.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            writeString(sb, String.valueOf(entry.getKey()));
            sb.append(':');
            write(sb, entry.getValue());
        }
        sb.append('}');
    }

    private static void writeArray(StringBuilder sb, List<?> list) {
        sb.append('[');
        boolean first = true;
        for (var item : list) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            write(sb, item);
        }
        sb.append(']');
    }

    private static void writeNode(StringBuilder sb, CstNode node) {
        if (node == null) {
            sb.append("null");
            return;
        }
        sb.append('{');
        sb.append("\"kind\":");
        writeString(sb, nodeKind(node));
        sb.append(",\"rule\":");
        writeString(sb, node.rule());
        sb.append(",\"start\":").append(node.span().start().offset());
        sb.append(",\"end\":").append(node.span().end().offset());
        sb.append(",\"line\":").append(node.span().start().line());
        sb.append(",\"column\":").append(node.span().start().column());
        if (!node.leadingTrivia().isEmpty()) {
            sb.append(",\"leadingTrivia\":");
            writeTriviaList(sb, node.leadingTrivia());
        }
        if (!node.trailingTrivia().isEmpty()) {
            sb.append(",\"trailingTrivia\":");
            writeTriviaList(sb, node.trailingTrivia());
        }
        switch (node) {
            case CstNode.Terminal t -> {
                sb.append(",\"text\":");
                writeString(sb, t.text());
            }
            case CstNode.Token tk -> {
                sb.append(",\"text\":");
                writeString(sb, tk.text());
            }
            case CstNode.NonTerminal nt -> {
                sb.append(",\"children\":[");
                boolean first = true;
                for (var child : nt.children()) {
                    if (!first) {
                        sb.append(',');
                    }
                    first = false;
                    writeNode(sb, child);
                }
                sb.append(']');
            }
            case CstNode.Error err -> {
                sb.append(",\"skipped\":");
                writeString(sb, err.skippedText());
                sb.append(",\"expected\":");
                writeString(sb, err.expected());
            }
        }
        sb.append('}');
    }

    private static String nodeKind(CstNode node) {
        return switch (node) {
            case CstNode.Terminal _ -> "terminal";
            case CstNode.NonTerminal _ -> "non-terminal";
            case CstNode.Token _ -> "token";
            case CstNode.Error _ -> "error";
        };
    }

    private static void writeTriviaList(StringBuilder sb, List<Trivia> trivia) {
        sb.append('[');
        boolean first = true;
        for (var t : trivia) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            writeTrivia(sb, t);
        }
        sb.append(']');
    }

    private static void writeTrivia(StringBuilder sb, Trivia trivia) {
        sb.append('{');
        sb.append("\"kind\":");
        writeString(sb, ParseTracer.triviaKind(trivia));
        sb.append(",\"start\":").append(trivia.span().start().offset());
        sb.append(",\"end\":").append(trivia.span().end().offset());
        sb.append(",\"text\":");
        writeString(sb, trivia.text());
        sb.append('}');
    }

    private static void writeDiagnostic(StringBuilder sb, Diagnostic diag) {
        sb.append('{');
        sb.append("\"severity\":");
        writeString(sb, diag.severity().display());
        sb.append(",\"message\":");
        writeString(sb, diag.message());
        sb.append(",\"line\":").append(diag.span().start().line());
        sb.append(",\"column\":").append(diag.span().start().column());
        sb.append(",\"start\":").append(diag.span().start().offset());
        sb.append(",\"end\":").append(diag.span().end().offset());
        if (diag.tag().isPresent()) {
            sb.append(",\"tag\":");
            writeString(sb, diag.tag().unwrap());
        }
        if (!diag.notes().isEmpty()) {
            sb.append(",\"notes\":[");
            boolean first = true;
            for (var note : diag.notes()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                writeString(sb, note);
            }
            sb.append(']');
        }
        sb.append('}');
    }

    private static void writeStats(StringBuilder sb, Stats stats) {
        sb.append('{');
        sb.append("\"timeMicros\":").append(stats.timeMicros());
        sb.append(",\"nodeCount\":").append(stats.nodeCount());
        sb.append(",\"triviaCount\":").append(stats.triviaCount());
        sb.append(",\"ruleEntries\":").append(stats.ruleEntries());
        sb.append(",\"cacheHits\":").append(stats.cacheHits());
        sb.append(",\"cacheMisses\":").append(stats.cacheMisses());
        sb.append(",\"cachePuts\":").append(stats.cachePuts());
        sb.append(",\"cutsFired\":").append(stats.cutsFired());
        sb.append(",\"diagnosticCount\":").append(stats.diagnosticCount());
        sb.append('}');
    }

    private static void writeString(StringBuilder sb, String s) {
        if (s == null) {
            sb.append("null");
            return;
        }
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }
}
