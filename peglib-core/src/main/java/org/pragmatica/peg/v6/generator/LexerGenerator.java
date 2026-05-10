package org.pragmatica.peg.v6.generator;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.peg.grammar.Grammar;
import org.pragmatica.peg.v6.lexer.Dfa;
import org.pragmatica.peg.v6.lexer.DfaBuilder;
import org.pragmatica.peg.v6.lexer.RuleClassifier;

import java.util.ArrayList;
import java.util.List;

/**
 * Phase A.4 — emit a standalone Java source file that mirrors the interpreted
 * {@link org.pragmatica.peg.v6.lexer.LexerEngine LexerEngine}. The generated class
 * bakes the DFA transition and accept tables as flat {@code int[]} initializers,
 * exposes a single {@code public static TokenArray lex(String input)} entry point,
 * and depends only on {@link org.pragmatica.peg.v6.token.TokenArray TokenArray} +
 * {@link org.pragmatica.peg.v6.token.TokenArrayBuilder TokenArrayBuilder}.
 *
 * <h2>Table layout</h2>
 *
 * <p>Transitions are emitted as a single flat {@code int[]} of length
 * {@code STATE_COUNT * 256} laid out row-major (state {@code s}, char {@code c}
 * lives at index {@code s*256 + c}). The flat layout side-steps the JVM's 64KB
 * method bytecode limit hit by 2D literal initializers for grammars with hundreds
 * of states (e.g. Java25). Initialization is split into chunked filler methods
 * each populating at most {@link #ENTRIES_PER_CHUNK} entries; the public field
 * is materialised lazily via a {@code buildTransitions()} factory.
 *
 * <h2>Empty-match warning</h2>
 *
 * <p>If the DFA's start state is itself accepting (e.g. a rule body like
 * {@code [a-z]*} that matches the empty string), the generated lexer will throw
 * at run time on any input. {@link Generated#warnings} surfaces a heads-up; the
 * code is still emitted because some grammars only stumble into this for unused
 * helper rules.
 */
public final class LexerGenerator {

    static final int ENTRIES_PER_CHUNK = 4096;

    private LexerGenerator() {}

    public sealed interface LexerGenerationError extends Cause {
        record InvalidIdentifier(String component, String value) implements LexerGenerationError {
            @Override
            public String message() {
                return "Invalid Java identifier for " + component + ": '" + value + "'";
            }
        }
    }

    public record Generated(String packageName, String className, String source, List<String> warnings) {
        public String fullyQualifiedName() {
            return packageName.isEmpty() ? className : packageName + "." + className;
        }
    }

    public static Result<Generated> generate(Grammar grammar,
                                             RuleClassifier.Classification classification,
                                             Dfa dfa,
                                             DfaBuilder.TokenKindAssignment kinds,
                                             String packageName,
                                             String className) {
        if (grammar == null || classification == null || dfa == null || kinds == null) {
            throw new IllegalArgumentException("grammar/classification/dfa/kinds must not be null");
        }
        if (!isValidQualifiedPackage(packageName)) {
            return new LexerGenerationError.InvalidIdentifier("packageName", String.valueOf(packageName)).result();
        }
        if (!isValidIdentifier(className)) {
            return new LexerGenerationError.InvalidIdentifier("className", String.valueOf(className)).result();
        }
        var warnings = new ArrayList<String>();
        if (dfa.acceptKind(Dfa.START_STATE) != Dfa.NO_ACCEPT) {
            warnings.add("DFA start state is accepting — at least one LEXER rule matches the empty string;"
                + " generated lex() will throw on any input. Tighten the offending rule (e.g. '+' instead of '*').");
        }
        int whitespaceKind = grammar.whitespace().isPresent() ? DfaBuilder.KIND_WHITESPACE : -1;
        var source = renderSource(packageName, className, dfa, kinds, whitespaceKind);
        return Result.success(new Generated(packageName, className, source, List.copyOf(warnings)));
    }

    private static String renderSource(String packageName,
                                       String className,
                                       Dfa dfa,
                                       DfaBuilder.TokenKindAssignment kinds,
                                       int whitespaceKind) {
        int stateCount = dfa.stateCount();
        int alphabet = dfa.alphabetSize();
        int[][] transitions = dfa.transitionTable();
        int[] acceptKinds = dfa.acceptKinds();
        var sb = new StringBuilder(stateCount * alphabet * 6);
        if (!packageName.isEmpty()) {
            sb.append("package ").append(packageName).append(";\n\n");
        }
        sb.append("import org.pragmatica.peg.v6.token.TokenArray;\n");
        sb.append("import org.pragmatica.peg.v6.token.TokenArrayBuilder;\n\n");
        sb.append("public final class ").append(className).append(" {\n\n");
        sb.append("    private ").append(className).append("() {}\n\n");
        sb.append("    public static final int STATE_COUNT = ").append(stateCount).append(";\n");
        sb.append("    public static final int ALPHABET_SIZE = ").append(alphabet).append(";\n");
        sb.append("    public static final int WHITESPACE_KIND = ").append(whitespaceKind).append(";\n\n");
        renderKindNames(sb, kinds);
        renderAcceptKinds(sb, acceptKinds);
        renderTransitions(sb, transitions, stateCount, alphabet);
        renderResolvers(sb, kinds);
        renderLexMethod(sb, alphabet, !kinds.keywordResolutions().isEmpty());
        sb.append("}\n");
        return sb.toString();
    }

    /**
     * Phase B.0 — emit a per-kind {@code RESOLVERS} table indexed by token kind.
     * Each non-null entry is a {@code HashMap<String,Integer>} mapping matched
     * keyword text to the override kind. The lex loop consults this table after
     * a match to remap identifier kinds to keyword kinds.
     */
    private static void renderResolvers(StringBuilder sb, DfaBuilder.TokenKindAssignment kinds) {
        var resolutions = kinds.keywordResolutions();
        int nameTableLen = kinds.kindNameTable().length;
        sb.append("    @SuppressWarnings({\"unchecked\", \"rawtypes\"})\n");
        sb.append("    private static final java.util.HashMap<String, Integer>[] RESOLVERS = new java.util.HashMap[")
            .append(nameTableLen).append("];\n");
        if (resolutions.isEmpty()) {
            sb.append("\n");
            return;
        }
        sb.append("    static {\n");
        int idx = 0;
        for (var entry : resolutions.entrySet()) {
            var local = "r" + idx;
            sb.append("        java.util.HashMap<String, Integer> ").append(local)
                .append(" = new java.util.HashMap<>();\n");
            for (var textEntry : entry.getValue().textToKind().entrySet()) {
                sb.append("        ").append(local).append(".put(\"")
                    .append(escapeJavaString(textEntry.getKey()))
                    .append("\", ").append(textEntry.getValue()).append(");\n");
            }
            sb.append("        RESOLVERS[").append(entry.getKey()).append("] = ").append(local).append(";\n");
            idx++;
        }
        sb.append("    }\n\n");
    }

    private static void renderKindNames(StringBuilder sb, DfaBuilder.TokenKindAssignment kinds) {
        var names = kinds.kindNameTable();
        sb.append("    public static final String[] KIND_NAMES = {");
        for (int i = 0; i < names.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append('"').append(escapeJavaString(names[i])).append('"');
        }
        sb.append("};\n\n");
    }

    private static void renderAcceptKinds(StringBuilder sb, int[] acceptKinds) {
        sb.append("    private static final int[] ACCEPT_KIND = new int[] {");
        for (int i = 0; i < acceptKinds.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(acceptKinds[i]);
        }
        sb.append("};\n\n");
    }

    private static void renderTransitions(StringBuilder sb, int[][] transitions, int stateCount, int alphabet) {
        long total = (long) stateCount * alphabet;
        int chunkCount = (int) ((total + ENTRIES_PER_CHUNK - 1) / ENTRIES_PER_CHUNK);
        sb.append("    private static final int[] TRANSITIONS = buildTransitions();\n\n");
        sb.append("    private static int[] buildTransitions() {\n");
        sb.append("        int[] t = new int[STATE_COUNT * ALPHABET_SIZE];\n");
        sb.append("        java.util.Arrays.fill(t, -1);\n");
        for (int chunk = 0; chunk < chunkCount; chunk++) {
            sb.append("        fillT").append(chunk).append("(t);\n");
        }
        sb.append("        return t;\n");
        sb.append("    }\n\n");
        long position = 0;
        for (int chunk = 0; chunk < chunkCount; chunk++) {
            long start = (long) chunk * ENTRIES_PER_CHUNK;
            long end = Math.min(start + ENTRIES_PER_CHUNK, total);
            sb.append("    private static void fillT").append(chunk).append("(int[] t) {\n");
            for (long i = start; i < end; i++) {
                int state = (int) (i / alphabet);
                int ch = (int) (i % alphabet);
                int v = transitions[state][ch];
                if (v == Dfa.NO_TRANSITION) {
                    position = i + 1;
                    continue;
                }
                sb.append("        t[").append(i).append("]=").append(v).append(";\n");
                position = i + 1;
            }
            sb.append("    }\n\n");
        }
        if (total == 0) {
            // unreachable in practice (StateCount>0 always after a successful build), but keep
            // the compiler happy: position must be defined for any future use.
            assert position == 0;
        }
    }

    private static void renderLexMethod(StringBuilder sb, int alphabet, boolean hasResolvers) {
        sb.append("    public static TokenArray lex(String input) {\n");
        sb.append("        if (input == null) {\n");
        sb.append("            throw new IllegalArgumentException(\"input must not be null\");\n");
        sb.append("        }\n");
        sb.append("        TokenArrayBuilder builder = new TokenArrayBuilder(input);\n");
        sb.append("        int len = input.length();\n");
        sb.append("        int pos = 0;\n");
        sb.append("        while (pos < len) {\n");
        sb.append("            int state = 0;\n");
        sb.append("            int lastAcceptEnd = -1;\n");
        sb.append("            int lastAcceptKind = -1;\n");
        sb.append("            int cur = pos;\n");
        sb.append("            while (cur < len) {\n");
        sb.append("                int ch = input.charAt(cur);\n");
        sb.append("                if (ch >= ").append(alphabet).append(") break;\n");
        sb.append("                int next = TRANSITIONS[state * ").append(alphabet).append(" + ch];\n");
        sb.append("                if (next < 0) break;\n");
        sb.append("                state = next;\n");
        sb.append("                cur++;\n");
        sb.append("                int ak = ACCEPT_KIND[state];\n");
        sb.append("                if (ak >= 0) {\n");
        sb.append("                    lastAcceptEnd = cur;\n");
        sb.append("                    lastAcceptKind = ak;\n");
        sb.append("                }\n");
        sb.append("            }\n");
        sb.append("            if (lastAcceptEnd <= pos) {\n");
        sb.append("                throw new IllegalArgumentException(\n");
        sb.append("                    \"lex error at offset \" + pos + \": '\" + input.charAt(pos) + \"'\");\n");
        sb.append("            }\n");
        if (hasResolvers) {
            sb.append("            if (lastAcceptKind >= 0 && lastAcceptKind < RESOLVERS.length) {\n");
            sb.append("                java.util.HashMap<String, Integer> r = RESOLVERS[lastAcceptKind];\n");
            sb.append("                if (r != null) {\n");
            sb.append("                    Integer ovr = r.get(input.substring(pos, lastAcceptEnd));\n");
            sb.append("                    if (ovr != null) lastAcceptKind = ovr;\n");
            sb.append("                }\n");
            sb.append("            }\n");
        }
        sb.append("            builder.append(lastAcceptKind, pos, lastAcceptEnd);\n");
        sb.append("            pos = lastAcceptEnd;\n");
        sb.append("        }\n");
        sb.append("        return builder.build(KIND_NAMES);\n");
        sb.append("    }\n");
    }

    private static String escapeJavaString(String s) {
        var out = new StringBuilder(s.length() + 4);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '"' -> out.append("\\\"");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (c < 0x20 || c == 0x7f) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }

    private static boolean isValidIdentifier(String s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        if (!Character.isJavaIdentifierStart(s.charAt(0))) {
            return false;
        }
        for (int i = 1; i < s.length(); i++) {
            if (!Character.isJavaIdentifierPart(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isValidQualifiedPackage(String s) {
        if (s == null) {
            return false;
        }
        if (s.isEmpty()) {
            return true;
        }
        for (var part : s.split("\\.", -1)) {
            if (!isValidIdentifier(part)) {
                return false;
            }
        }
        return true;
    }
}
