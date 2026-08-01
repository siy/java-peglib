package org.pragmatica.peg.playground.internal;

import org.pragmatica.peg.playground.ParseTracer;
import org.pragmatica.peg.playground.Stats;
import org.pragmatica.peg.v6.cst.CstArray;
import org.pragmatica.peg.v6.cst.CstNode;
import org.pragmatica.peg.v6.diagnostic.Diagnostic;
import org.pragmatica.peg.v6.token.TokenArray;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

/**
 * Minimal JSON encoder tailored for the playground server's response
 * payloads. No external dependencies; handles the specific shapes used by
 * {@code PlaygroundServer.handleParse} — 0.6.x flat CSTs, diagnostic lists,
 * stats, and plain {@code Map<String, Object>} response envelopes.
 *
 * <p>Produced output is RFC 8259 compliant for the shapes we emit.
 * Strings are escaped per spec (quotes, backslashes, control chars).
 * Numbers use Java default toString(); booleans/null are literal.
 *
 * <p>Node kinds in the emitted tree map the {@link CstNode} sealed views onto
 * the wire names the frontend already understands: {@code Branch} →
 * {@code "non-terminal"}, {@code Leaf} → {@code "terminal"}, {@code Error} →
 * {@code "error"}. The 0.5.x {@code "token"} kind has no 0.6.x counterpart —
 * token-boundary captures are ordinary leaves in the flat CST.
 */
public final class JsonEncoder {
    private JsonEncoder() {}

    /**
     * Carrier pairing a diagnostic list with the source text it refers to.
     * 0.6.x {@link Diagnostic} records carry a byte offset but no line/column,
     * so the encoder needs the input to resolve them; bundling the whole list
     * lets one {@link LineIndex} serve every diagnostic.
     *
     * @param items the diagnostics to encode, in engine order
     * @param input the source text the offsets index into
     */
    public record Diagnostics(List<Diagnostic> items, String input) {
        public static Diagnostics diagnostics(List<Diagnostic> items, String input) {
            return new Diagnostics(items, input);
        }
    }

    public static String encode(Object value) {
        var sb = new StringBuilder();
        write(sb, value);
        return sb.toString();
    }

    public static String encodeTree(CstArray cst) {
        var sb = new StringBuilder();
        writeTree(sb, cst);
        return sb.toString();
    }

    private static void write(StringBuilder sb, Object value) {
        switch (value) {
            case null -> sb.append("null");
            case String s -> writeString(sb, s);
            case Boolean b -> sb.append(b);
            case Number n -> sb.append(n);
            case CstArray cst -> writeTree(sb, cst);
            case Diagnostics diagnostics -> writeDiagnostics(sb, diagnostics);
            case Stats s -> writeStats(sb, s);
            case Map< ? , ? > map -> writeObject(sb, map);
            case List< ? > list -> writeArray(sb, list);
            default -> writeString(sb, value.toString());
        }
    }

    private static void writeObject(StringBuilder sb, Map< ? , ? > map) {
        sb.append('{');
        boolean first = true;
        for (var entry : map.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            writeString(sb,
                        String.valueOf(entry.getKey()));
            sb.append(':');
            write(sb, entry.getValue());
        }
        sb.append('}');
    }

    private static void writeArray(StringBuilder sb, List< ? > list) {
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

    /**
     * Encode a whole CST. An empty array (no nodes, or no root) encodes as
     * JSON {@code null} so the frontend's "(no tree)" branch fires.
     */
    private static void writeTree(StringBuilder sb, CstArray cst) {
        if (cst.nodeCount() == 0 || cst.rootIndex() == CstArray.NO_NODE) {
            sb.append("null");
            return;
        }
        writeNode(sb,
                  cst,
                  LineIndex.lineIndex(cst.input()),
                  cst.rootIndex());
    }

    private static void writeNode(StringBuilder sb, CstArray cst, LineIndex lines, int nodeIdx) {
        int start = cst.spanStart(nodeIdx);
        sb.append('{');
        sb.append("\"kind\":");
        writeString(sb, nodeKind(cst, nodeIdx));
        sb.append(",\"rule\":");
        writeString(sb, cst.kindNameAt(nodeIdx));
        sb.append(",\"start\":")
          .append(start);
        sb.append(",\"end\":")
          .append(cst.spanEnd(nodeIdx));
        sb.append(",\"line\":")
          .append(lines.lineAt(start));
        sb.append(",\"column\":")
          .append(lines.columnAt(start));
        writeTriviaSection(sb, cst, "leadingTrivia", cst.leadingTriviaTokens(nodeIdx));
        writeTriviaSection(sb, cst, "trailingTrivia", cst.trailingTriviaTokens(nodeIdx));
        writeNodeBody(sb, cst, lines, nodeIdx);
        sb.append('}');
    }

    private static void writeNodeBody(StringBuilder sb, CstArray cst, LineIndex lines, int nodeIdx) {
        switch (cst.viewAt(nodeIdx)) {
            case CstNode.Branch _ -> writeChildren(sb, cst, lines, nodeIdx);
            case CstNode.Leaf _ -> writeText(sb, cst, nodeIdx);
            case CstNode.Error _ -> writeErrorBody(sb, cst, nodeIdx);
        }
    }

    private static String nodeKind(CstArray cst, int nodeIdx) {
        return switch (cst.viewAt(nodeIdx)) {
            case CstNode.Branch _ -> "non-terminal";
            case CstNode.Leaf _ -> "terminal";
            case CstNode.Error _ -> "error";
        };
    }

    private static void writeChildren(StringBuilder sb, CstArray cst, LineIndex lines, int nodeIdx) {
        sb.append(",\"children\":[");
        boolean first = true;
        for (var child : cst.children(nodeIdx)
                            .toArray()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            writeNode(sb, cst, lines, child);
        }
        sb.append(']');
    }

    private static void writeText(StringBuilder sb, CstArray cst, int nodeIdx) {
        sb.append(",\"text\":");
        writeString(sb,
                    cst.textAt(nodeIdx)
                       .toString());
    }

    /**
     * 0.6.x error nodes carry the skipped span but no per-node "expected" set —
     * that information lives on the accompanying {@link Diagnostic}. The key is
     * still emitted (empty) so the frontend's error branch keeps its shape.
     */
    private static void writeErrorBody(StringBuilder sb, CstArray cst, int nodeIdx) {
        sb.append(",\"skipped\":");
        writeString(sb,
                    cst.textAt(nodeIdx)
                       .toString());
        sb.append(",\"expected\":");
        writeString(sb, "");
    }

    private static void writeTriviaSection(StringBuilder sb, CstArray cst, String key, IntStream tokens) {
        int[] indices = tokens.toArray();
        if (indices.length == 0) {
            return;
        }
        sb.append(',');
        writeString(sb, key);
        sb.append(':');
        writeTriviaArray(sb,
                         cst.tokens(),
                         indices);
    }

    private static void writeTriviaArray(StringBuilder sb, TokenArray tokens, int[] indices) {
        sb.append('[');
        boolean first = true;
        for (var index : indices) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            writeTrivia(sb, tokens, index);
        }
        sb.append(']');
    }

    private static void writeTrivia(StringBuilder sb, TokenArray tokens, int index) {
        sb.append('{');
        sb.append("\"kind\":");
        writeString(sb,
                    ParseTracer.triviaKind(tokens.kindAt(index)));
        sb.append(",\"start\":")
          .append(tokens.startAt(index));
        sb.append(",\"end\":")
          .append(tokens.endAt(index));
        sb.append(",\"text\":");
        writeString(sb,
                    tokens.textAt(index)
                          .toString());
        sb.append('}');
    }

    private static void writeDiagnostics(StringBuilder sb, Diagnostics diagnostics) {
        var lines = LineIndex.lineIndex(diagnostics.input());
        sb.append('[');
        boolean first = true;
        for (var diag : diagnostics.items()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            writeDiagnostic(sb, diag, lines);
        }
        sb.append(']');
    }

    private static void writeDiagnostic(StringBuilder sb, Diagnostic diag, LineIndex lines) {
        sb.append('{');
        sb.append("\"severity\":");
        writeString(sb,
                    diag.severity()
                        .label());
        sb.append(",\"message\":");
        writeString(sb, diag.message());
        sb.append(",\"line\":")
          .append(lines.lineAt(diag.offset()));
        sb.append(",\"column\":")
          .append(lines.columnAt(diag.offset()));
        sb.append(",\"start\":")
          .append(diag.offset());
        sb.append(",\"end\":")
          .append(diag.offset() + diag.length());
        sb.append(",\"expected\":");
        writeString(sb, diag.expected());
        sb.append(",\"found\":");
        writeString(sb, diag.found());
        sb.append('}');
    }

    private static void writeStats(StringBuilder sb, Stats stats) {
        sb.append('{');
        sb.append("\"timeMicros\":")
          .append(stats.timeMicros());
        sb.append(",\"nodeCount\":")
          .append(stats.nodeCount());
        sb.append(",\"triviaCount\":")
          .append(stats.triviaCount());
        sb.append(",\"ruleEntries\":")
          .append(stats.ruleEntries());
        sb.append(",\"cacheHits\":")
          .append(stats.cacheHits());
        sb.append(",\"cacheMisses\":")
          .append(stats.cacheMisses());
        sb.append(",\"cachePuts\":")
          .append(stats.cachePuts());
        sb.append(",\"cutsFired\":")
          .append(stats.cutsFired());
        sb.append(",\"diagnosticCount\":")
          .append(stats.diagnosticCount());
        sb.append('}');
    }

    private static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++ ) {
            char c = s.charAt(i);
            switch (c) {
                case'"' -> sb.append("\\\"");
                case'\\' -> sb.append("\\\\");
                case'\n' -> sb.append("\\n");
                case'\r' -> sb.append("\\r");
                case'\t' -> sb.append("\\t");
                case'\b' -> sb.append("\\b");
                case'\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    }else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    /**
     * Precomputed line-start offsets for a source text. Built once per encoded
     * tree / diagnostic list, then answers line and column in O(log n) per
     * lookup — a naive per-node rescan would be O(input) per node and therefore
     * quadratic on the large files the playground is expected to handle.
     *
     * @param lineStarts offset of the first character of each line, ascending
     */
    private record LineIndex(int[] lineStarts) {
        static LineIndex lineIndex(String input) {
            return new LineIndex(IntStream.concat(IntStream.of(0),
                                                  IntStream.range(0, input.length())
                                                           .filter(i -> input.charAt(i) == '\n')
                                                           .map(i -> i + 1))
                                          .toArray());
        }

        int lineAt(int offset) {
            return lineOrdinal(offset) + 1;
        }

        int columnAt(int offset) {
            return offset - lineStarts[lineOrdinal(offset)] + 1;
        }

        /** Zero-based index of the line containing {@code offset}. */
        private int lineOrdinal(int offset) {
            int found = Arrays.binarySearch(lineStarts, offset);
            return found >= 0
                   ? found
                   : - found - 2;
        }
    }
}
