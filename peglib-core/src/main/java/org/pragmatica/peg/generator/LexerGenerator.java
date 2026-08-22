package org.pragmatica.peg.generator;

import java.util.ArrayList;
import java.util.List;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.peg.grammar.Grammar;
import org.pragmatica.peg.grammar.NestingPair;
import org.pragmatica.peg.lexer.Dfa;
import org.pragmatica.peg.lexer.DfaBuilder;
import org.pragmatica.peg.lexer.RuleClassifier;


/**
 * Phase A.4 — emit a standalone Java source file that mirrors the interpreted
 * {@link org.pragmatica.peg.lexer.LexerEngine LexerEngine}. The generated class
 * bakes the DFA transition and accept tables as flat {@code int[]} initializers,
 * exposes a single {@code public static TokenArray lex(String input)} entry point,
 * and depends only on {@link org.pragmatica.peg.token.TokenArray TokenArray} +
 * {@link org.pragmatica.peg.token.TokenArrayBuilder TokenArrayBuilder}.
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
 * {@code [a-z]*} that matches the empty string), the generated lexer falls
 * back to emitting a synthetic 1-char {@code WHITESPACE} token on every
 * no-progress stall — it does not throw. The parser then surfaces those
 * synthetic tokens as trailing-input diagnostics. This is usually fine: many
 * grammars hit it on unused helper rules or rules that are also reachable
 * via longer-match alternatives. {@link Generated#warnings} names the
 * offending rule so authors can tighten it (e.g. {@code +} instead of
 * {@code *}) if the synthetic-token fallback isn't acceptable.
 */
public final class LexerGenerator {
    static final int ENTRIES_PER_CHUNK = 4096;
    /** Chars per emitted string literal; Base64 is ASCII so this is also bytes, well under the 65535 cap. */
    static final int BASE64_CHUNK = 50_000;

    private LexerGenerator() {}

    public sealed interface LexerGenerationError extends Cause permits LexerGenerationError.InvalidIdentifier {
        record InvalidIdentifier(String component, String value) implements LexerGenerationError {
            @Override
            public String message() {
                return "Invalid Java identifier for " + component + ": '" + value + "'";
            }
        }
    }

    public record Generated(String packageName, String className, String source, List<String> warnings) {
        public String fullyQualifiedName() {
            return packageName.isEmpty()
                   ? className
                   : packageName + "." + className;
        }
    }

    public static Result<Generated> generate(Grammar grammar,
                                             RuleClassifier.Classification classification,
                                             Dfa dfa,
                                             DfaBuilder.TokenKindAssignment kinds,
                                             String packageName,
                                             String className) {
        // Internal entry: callers are PegParser/tests, validated inputs.
        if (!isValidQualifiedPackage(packageName)) {
            return new LexerGenerationError.InvalidIdentifier("packageName", String.valueOf(packageName)).result();
        }

        if (!isValidIdentifier(className)) {
            return new LexerGenerationError.InvalidIdentifier("className", String.valueOf(className)).result();
        }

        var warnings = new ArrayList<String>();
        var startAcceptKind = dfa.acceptKind(Dfa.START_STATE);

        if (startAcceptKind != Dfa.NO_ACCEPT) {
            var nameTable = kinds.kindNameTable();
            var ruleName = (startAcceptKind >= 0 && startAcceptKind < nameTable.length)
                           ? nameTable[startAcceptKind]
                           : ("<kind:" + startAcceptKind + ">");

            warnings.add("LEXER rule '" + ruleName
                        + "' matches the empty string (DFA start state accepts kind " + startAcceptKind
                        + "). The generated lexer will not throw — on a no-progress stall it emits a"
                        + " synthetic 1-char WHITESPACE token and continues. Tighten the rule"
                        + " (e.g. '+' instead of '*') if the synthetic-token fallback is unacceptable.");
        }

        int whitespaceKind = grammar.whitespace().isPresent()
                             ? DfaBuilder.KIND_WHITESPACE
                             : -1;
        var source = renderSource(packageName, className, dfa, kinds, whitespaceKind, grammar.nestingTrivia());

        return Result.success(new Generated(packageName, className, source, List.copyOf(warnings)));
    }

    private static String renderSource(String packageName,
                                       String className,
                                       Dfa dfa,
                                       DfaBuilder.TokenKindAssignment kinds,
                                       int whitespaceKind,
                                       List<NestingPair> nestingTrivia) {
        int stateCount = dfa.stateCount();
        int alphabet = dfa.alphabetSize();
        int[][] transitions = dfa.transitionTable();
        int[] acceptKinds = dfa.acceptKinds();
        var sb = new StringBuilder(stateCount * alphabet * 6);
        // 0.7.3 — every %nest-dependent emission below is guarded on this flag, so the source
        // generated for a grammar that declares none is byte-identical to what 0.7.2 emitted.
        // That is worth preserving deliberately: consumers cache generated sources and detect
        // staleness by comparing them, so a gratuitous diff would force every downstream
        // grammar to regenerate for a feature it does not use.
        boolean hasNesting = !nestingTrivia.isEmpty();

        if (!packageName.isEmpty()) {
            sb.append("package ").append(packageName).append(";\n\n");
        }

        sb.append("import org.pragmatica.peg.token.TokenArray;\n");
        sb.append("import org.pragmatica.peg.token.TokenArrayBuilder;\n");
        if (hasNesting) {
            sb.append("import org.pragmatica.peg.token.NestingScanner;\n");
        }

        sb.append("\n");
        sb.append("public final class ").append(className).append(" {\n\n");
        sb.append("    private ").append(className).append("() {}\n\n");
        sb.append("    public static final int STATE_COUNT = ").append(stateCount).append(";\n");
        sb.append("    public static final int ALPHABET_SIZE = ").append(alphabet).append(";\n");
        sb.append("    public static final int WHITESPACE_KIND = ").append(whitespaceKind).append(";\n\n");
        renderKindNames(sb, kinds);
        renderAcceptKinds(sb, acceptKinds);
        renderFollowConstraints(sb, dfa.acceptFollows(), dfa.followTable(), alphabet);
        renderTransitions(sb, transitions, stateCount, alphabet);
        renderNonAsciiTransitions(sb, dfa.nonAsciiTransitions());
        renderResolvers(sb, kinds);
        renderNestingTables(sb, nestingTrivia);
        renderLexMethod(sb,
                        alphabet,
                        !kinds.keywordResolutions().isEmpty(),
                        dfa.followTable().length > 0,
                        hasNesting);
        sb.append("}\n");

        return sb.toString();
    }

    /**
     * 0.7.3 — emit the {@code %nest} delimiter tables and the per-token reject test.
     *
     * <p>Emits nothing at all when the grammar declares no {@code %nest}, which keeps both the
     * generated source and the generated lexer's per-token cost exactly what they were.
     *
     * <p>The trivia kind is resolved HERE, at generation time, by the same
     * {@link DfaBuilder#triviaKindForPrefix} the interpreted engine asks — so a delimiter
     * names the same kind on both paths, and the generated lexer does no kind classification
     * at run time.
     */
    private static void renderNestingTables(StringBuilder sb, List<NestingPair> nestingTrivia) {
        if (nestingTrivia.isEmpty()) {
            return;
        }

        var first = new StringBuilder();
        var open = new StringBuilder();
        var close = new StringBuilder();
        var kind = new StringBuilder();

        for (int i = 0; i < nestingTrivia.size(); i++) {
            var pair = nestingTrivia.get(i);

            if (i > 0) {
                first.append(", ");
                open.append(", ");
                close.append(", ");
                kind.append(", ");
            }

            first.append("'").append(escapeJavaChar(pair.open().charAt(0))).append("'");
            open.append("\"").append(escapeJavaString(pair.open())).append("\"");
            close.append("\"").append(escapeJavaString(pair.close())).append("\"");
            kind.append(DfaBuilder.triviaKindForPrefix(pair.open()));
        }

        sb.append("    private static final char[] NEST_FIRST = {").append(first).append("};\n");
        sb.append("    private static final String[] NEST_OPEN = {").append(open).append("};\n");
        sb.append("    private static final String[] NEST_CLOSE = {").append(close).append("};\n");
        sb.append("    private static final int[] NEST_KIND = {").append(kind).append("};\n\n");
        // Reject on a single char compare; startsWith is reached only at a position whose
        // first character already matched a delimiter.
        sb.append("    private static int nestingOpenAt(String input, int pos) {\n");
        sb.append("        char c = input.charAt(pos);\n");
        sb.append("        for (int i = 0; i < NEST_FIRST.length; i++) {\n");
        sb.append("            if (NEST_FIRST[i] == c && input.startsWith(NEST_OPEN[i], pos)) return i;\n");
        sb.append("        }\n");
        sb.append("        return -1;\n");
        sb.append("    }\n\n");
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
          .append(nameTableLen)
          .append("];\n");
        if (resolutions.isEmpty()) {
            sb.append("\n");

            return;
        }

        sb.append("    static {\n");
        int idx = 0;

        for (var entry : resolutions.entrySet()) {
            var local = "r" + idx;

            sb.append("        java.util.HashMap<String, Integer> ")
              .append(local)
              .append(" = new java.util.HashMap<>();\n");
            for (var textEntry : entry.getValue().textToKind().entrySet()) {
                sb.append("        ")
                  .append(local)
                  .append(".put(\"")
                  .append(escapeJavaString(textEntry.getKey()))
                  .append("\", ")
                  .append(textEntry.getValue())
                  .append(");\n");
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

    /**
     * 0.6.0 — emit a flat {@code int[STATE_COUNT] NON_ASCII_TRANSITIONS} table.
     * Entry {@code i} is the DFA state the lexer transitions to when state {@code i}
     * sees a non-ASCII (code &ge; 256) input character, or {@code -1} if no such
     * transition exists. Mirrors {@link Dfa#nonAsciiTransition(int)} in the engine.
     */
    private static void renderNonAsciiTransitions(StringBuilder sb, int[] nonAsciiTransitions) {
        var data = new java.io.ByteArrayOutputStream();

        for (var i = 0; i < nonAsciiTransitions.length; i++) {
            if (nonAsciiTransitions[i] == Dfa.NO_TRANSITION) {
                continue;
            }

            writeInt(data, i);
            writeInt(data, nonAsciiTransitions[i]);
        }

        renderEncodedIntTable(sb, "NON_ASCII_TRANSITIONS", "STATE_COUNT", data.toByteArray());
    }

    /**
     * Emit the DFA transition table as Base64 string constants decoded in a static
     * initialiser, rather than as inline {@code t[i]=v;} assignments.
     *
     * <p>Inline assignments put every index and value outside the {@code sipush} range into the
     * class-file constant pool, which is capped at 65535 entries. A 1288-state DFA needs roughly
     * 75k entries and simply cannot be compiled — {@code error: too many constants} — so grammar
     * size was silently bounded at around 1100 states. Each Base64 chunk costs ONE pool entry
     * regardless of length, so the table no longer scales with the pool.
     *
     * <p>Only non-default transitions are encoded (the array is pre-filled with
     * {@code NO_TRANSITION}), four bytes of index followed by four of value. Chunks stay well
     * under the 65535-byte ceiling on a single string literal; Base64 is ASCII, so one char is
     * one byte of modified UTF-8 and no escaping is involved.
     *
     * @since 0.7.2
     */
    private static void renderTransitions(StringBuilder sb, int[][] transitions, int stateCount, int alphabet) {
        var data = new java.io.ByteArrayOutputStream();

        for (var state = 0; state < stateCount; state++) {
            for (var ch = 0; ch < alphabet; ch++) {
                var v = transitions[state][ch];

                if (v == Dfa.NO_TRANSITION) {
                    continue;
                }

                writeInt(data, state * alphabet + ch);
                writeInt(data, v);
            }
        }

        renderEncodedIntTable(sb, "TRANSITIONS", "STATE_COUNT * ALPHABET_SIZE", data.toByteArray());
    }

    private static void writeInt(java.io.ByteArrayOutputStream out, int v) {
        out.write((v >>> 24) & 0xFF);
        out.write((v >>> 16) & 0xFF);
        out.write((v >>> 8) & 0xFF);
        out.write(v & 0xFF);
    }

    /** Emit {@code name} as a -1-filled int[] patched from Base64 (index, value) pairs. */
    private static void renderEncodedIntTable(StringBuilder sb, String name, String sizeExpr, byte[] data) {
        var encoded = java.util.Base64.getEncoder().encodeToString(data);

        sb.append("    private static final String[] ").append(name).append("_DATA = {\n");
        for (var i = 0; i < encoded.length(); i += BASE64_CHUNK) {
            sb.append("        \"")
              .append(encoded,
                      i,
                      Math.min(i + BASE64_CHUNK,
                               encoded.length()))
              .append("\"")
              .append(i + BASE64_CHUNK< encoded.length()
                      ? ","
                      : "")
              .append("\n");
        }

        sb.append("    };\n\n");
        sb.append("    private static final int[] ").append(name).append(" = decode").append(name).append("();\n\n");
        sb.append("    private static int[] decode").append(name).append("() {\n");
        sb.append("        int[] t = new int[").append(sizeExpr).append("];\n");
        sb.append("        java.util.Arrays.fill(t, -1);\n");
        sb.append("        StringBuilder joined = new StringBuilder();\n");
        sb.append("        for (String part : ").append(name).append("_DATA) { joined.append(part); }\n");
        sb.append("        byte[] d = java.util.Base64.getDecoder().decode(joined.toString());\n");
        sb.append("        for (int i = 0; i < d.length; i += 8) {\n");
        sb.append("            int idx = ((d[i] & 0xFF) << 24) | ((d[i + 1] & 0xFF) << 16) | ((d[i + 2] & 0xFF) << 8) | (d[i + 3] & 0xFF);\n");
        sb.append("            int val = ((d[i + 4] & 0xFF) << 24) | ((d[i + 5] & 0xFF) << 16) | ((d[i + 6] & 0xFF) << 8) | (d[i + 7] & 0xFF);\n");
        sb.append("            t[idx] = val;\n");
        sb.append("        }\n");
        sb.append("        return t;\n");
        sb.append("    }\n\n");
    }

    /**
     * Emit the follow-constraint tables and the predicate that reads them.
     *
     * <p>Emitted only for a grammar that has at least one guarded rule, so the generated lexer of
     * a grammar without one is unchanged — both to keep the hot loop free of a check it can never
     * need, and to keep output stable for grammars that predate the feature.
     */
    private static void renderFollowConstraints(StringBuilder sb,
                                                int[] acceptFollows,
                                                int[][] followTable,
                                                int alphabet) {
        if (followTable.length == 0) {
            return;
        }

        int row = alphabet + 2;

        sb.append("    private static final int FOLLOW_ROW = ").append(row).append(";\n");
        sb.append("    private static final int[] ACCEPT_FOLLOW = new int[] {");
        for (int i = 0; i < acceptFollows.length; i++) {
            if (i > 0) {
                sb.append(',');
            }

            sb.append(acceptFollows[i]);
        }

        sb.append("};\n");
        sb.append("    private static final int[] FOLLOW_TABLE = new int[] {");
        for (int c = 0; c < followTable.length; c++) {
            for (int i = 0; i < row; i++) {
                if (c > 0 || i > 0) {
                    sb.append(',');
                }

                sb.append(followTable[c][i]);
            }
        }

        sb.append("};\n\n");
        sb.append("    private static boolean allowsFollower(int state, int follower) {\n");
        sb.append("        int constraint = ACCEPT_FOLLOW[state];\n");
        sb.append("        if (constraint < 0) return true;\n");
        sb.append("        int base = constraint * FOLLOW_ROW;\n");
        sb.append("        if (follower < 0) return FOLLOW_TABLE[base + ").append(alphabet + 1).append("] != 0;\n");
        sb.append("        if (follower >= ")
          .append(alphabet)
          .append(") return FOLLOW_TABLE[base + ")
          .append(alphabet)
          .append("] != 0;\n");
        sb.append("        return FOLLOW_TABLE[base + follower] != 0;\n");
        sb.append("    }\n\n");
    }

    private static void renderLexMethod(StringBuilder sb,
                                        int alphabet,
                                        boolean hasResolvers,
                                        boolean hasFollowConstraints,
                                        boolean hasNesting) {
        sb.append("    public static TokenArray lex(String input) {\n");
        // No defensive null check on input: the only caller path is
        // CompiledLexer.lex(String), which is invoked from Parser.parse(String)
        // after the parser's own null discipline; passing null here is a
        // programmer error that the JVM's NPE on input.length() catches.
        sb.append("        TokenArrayBuilder builder = new TokenArrayBuilder(input);\n");
        sb.append("        int len = input.length();\n");
        sb.append("        int pos = 0;\n");
        sb.append("        while (pos < len) {\n");
        if (hasNesting) {
            // Mirrors LexerEngine.lex: a %nest open delimiter at a token start is consumed by
            // the depth counter INSTEAD OF the DFA, so a nesting comment is scanned once. An
            // unterminated block leaves lastAcceptEnd at -1 and falls through to the DFA,
            // which keeps malformed input reading exactly as it did without the directive.
            sb.append("            int lastAcceptEnd = -1;\n");
            sb.append("            int lastAcceptKind = -1;\n");
            sb.append("            int nested = nestingOpenAt(input, pos);\n");
            sb.append("            if (nested >= 0) {\n");
            sb.append("                int nestEnd = NestingScanner.scanEnd(input, pos, NEST_OPEN[nested], NEST_CLOSE[nested]);\n");
            sb.append("                if (nestEnd != NestingScanner.UNTERMINATED) {\n");
            sb.append("                    lastAcceptEnd = nestEnd;\n");
            sb.append("                    lastAcceptKind = NEST_KIND[nested];\n");
            sb.append("                }\n");
            sb.append("            }\n");
            sb.append("            if (lastAcceptEnd < 0) {\n");
            sb.append("            int state = 0;\n");
            sb.append("            int cur = pos;\n");
        } else {
            sb.append("            int state = 0;\n");
            sb.append("            int lastAcceptEnd = -1;\n");
            sb.append("            int lastAcceptKind = -1;\n");
            sb.append("            int cur = pos;\n");
        }

        sb.append("            while (cur < len) {\n");
        sb.append("                int ch = input.charAt(cur);\n");
        sb.append("                int next;\n");
        sb.append("                if (ch >= ").append(alphabet).append(") {\n");
        sb.append("                    next = NON_ASCII_TRANSITIONS[state];\n");
        sb.append("                } else {\n");
        sb.append("                    next = TRANSITIONS[state * ").append(alphabet).append(" + ch];\n");
        sb.append("                }\n");
        sb.append("                if (next < 0) break;\n");
        sb.append("                state = next;\n");
        sb.append("                cur++;\n");
        sb.append("                int ak = ACCEPT_KIND[state];\n");
        // A guarded accept is recorded only when the following character permits it; maximal
        // munch then carries on from the last accept that was allowed.
        sb.append(hasFollowConstraints
                  ? "                if (ak >= 0 && allowsFollower(state, cur < len ? input.charAt(cur) : -1)) {\n"
                  : "                if (ak >= 0) {\n");
        sb.append("                    lastAcceptEnd = cur;\n");
        sb.append("                    lastAcceptKind = ak;\n");
        sb.append("                }\n");
        sb.append("            }\n");
        if (hasNesting) {
            // Close the `if (lastAcceptEnd < 0)` that skips the DFA when the counting scanner
            // already produced a token.
            sb.append("            }\n");
        }
        // No-DFA-transition stall: emit a 1-char synthetic WHITESPACE token to
        // make progress; the parser surfaces this as a trailing-input diagnostic.
        // Matches the recovery contract LexerEngine.lex() uses for the
        // interpreted (non-codegen) path.
        sb.append("            if (lastAcceptEnd <= pos) {\n");
        sb.append("                builder.append(TokenArray.KIND_WHITESPACE, pos, pos + 1);\n");
        sb.append("                pos++;\n");
        sb.append("                continue;\n");
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
        // Phase A.6 / 0.6.1 / 0.6.2 — content-based trivia refinement (mirrors LexerEngine).
        // Fires for the legacy coalesced WHITESPACE token AND for structurally-assigned
        // LINE_COMMENT / BLOCK_COMMENT base kinds whose doc variant can only be told
        // apart by the matched text.
        // //         → LINE_COMMENT          (also // without third '/')
        // ///        → DOC_LINE_COMMENT      (3+ slashes)
        // /* ... */  → BLOCK_COMMENT
        // /** ... */ → DOC_BLOCK_COMMENT     (NOT '/**/' — smallest empty block)
        sb.append("            if ((lastAcceptKind == TokenArray.KIND_WHITESPACE\n");
        sb.append("                 || lastAcceptKind == TokenArray.KIND_LINE_COMMENT\n");
        sb.append("                 || lastAcceptKind == TokenArray.KIND_BLOCK_COMMENT)\n");
        sb.append("                && lastAcceptEnd > pos + 1) {\n");
        sb.append("                char c0 = input.charAt(pos);\n");
        sb.append("                char c1 = input.charAt(pos + 1);\n");
        sb.append("                if (c0 == '/') {\n");
        sb.append("                    if (c1 == '/') {\n");
        sb.append("                        if (lastAcceptEnd > pos + 2 && input.charAt(pos + 2) == '/') {\n");
        sb.append("                            lastAcceptKind = TokenArray.KIND_DOC_LINE_COMMENT;\n");
        sb.append("                        } else {\n");
        sb.append("                            lastAcceptKind = TokenArray.KIND_LINE_COMMENT;\n");
        sb.append("                        }\n");
        sb.append("                    } else if (c1 == '*') {\n");
        sb.append("                        boolean isDoc = lastAcceptEnd > pos + 2\n");
        sb.append("                                        && input.charAt(pos + 2) == '*'\n");
        sb.append("                                        && !(lastAcceptEnd == pos + 4 && input.charAt(pos + 3) == '/');\n");
        sb.append("                        lastAcceptKind = isDoc ? TokenArray.KIND_DOC_BLOCK_COMMENT : TokenArray.KIND_BLOCK_COMMENT;\n");
        sb.append("                    }\n");
        sb.append("                }\n");
        sb.append("            }\n");
        sb.append("            builder.append(lastAcceptKind, pos, lastAcceptEnd);\n");
        sb.append("            pos = lastAcceptEnd;\n");
        sb.append("        }\n");
        sb.append("        return builder.build(KIND_NAMES);\n");
        sb.append("    }\n");
    }

    /**
     * Escape one character for emission inside a Java CHARACTER literal.
     *
     * <p>Not the same job as {@link #escapeJavaString}, which escapes the double quote because
     * its output lands inside a string literal, and deliberately leaves the apostrophe alone.
     * Passing an apostrophe through that one and wrapping the result in single quotes emits
     * three apostrophes in a row, which does not compile. A {@code %nest} delimiter beginning
     * with an apostrophe is unusual but perfectly legal, and the generated lexer has to be
     * valid Java for every grammar that parses.
     *
     * @since 0.7.3
     */
    private static String escapeJavaChar(char c) {
        return switch (c) {
            case '\\' -> "\\\\";
            case '\'' -> "\\'";
            case '\n' -> "\\n";
            case '\r' -> "\\r";
            case '\t' -> "\\t";
            case '\b' -> "\\b";
            case '\f' -> "\\f";
            default -> (c < 0x20 || c == 0x7f)
                       ? String.format("\\u%04x", (int) c)
                       : String.valueOf(c);
        };
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
        if (s.isEmpty()) {
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
        // Required parameter — the package name always comes from generator config.
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
