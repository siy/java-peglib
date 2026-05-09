package org.pragmatica.peg.generator;

import org.pragmatica.peg.grammar.Expression;
import org.pragmatica.peg.grammar.Grammar;
import org.pragmatica.peg.grammar.Rule;
import org.pragmatica.peg.grammar.analysis.ExpressionShape;
import org.pragmatica.peg.grammar.analysis.FirstCharAnalysis;
import org.pragmatica.peg.parser.ParserConfig;

import java.util.Set;

/**
 * Generates standalone parser source code from a Grammar.
 * The generated parser depends only on pragmatica-lite:core.
 */
public final class ParserGenerator {
    private static final int INITIAL_BUFFER_SIZE = 32_000;
    private static final int MAX_RECURSION_DEPTH = 100;

    // Shared generated code fragments
    private static final String MATCHES_WORD_METHOD = """
            private boolean matchesWord(String word, boolean caseInsensitive) {
                if (remaining() < word.length()) return false;
                for (int i = 0; i < word.length(); i++) {
                    char expected = word.charAt(i);
                    char actual = peek(i);
                    if (caseInsensitive) {
                        if (Character.toLowerCase(expected) != Character.toLowerCase(actual)) return false;
                    } else {
                        if (expected != actual) return false;
                    }
                }
                return true;
            }
        """;

    private static final String MATCHES_PATTERN_METHOD = """
            private boolean matchesPattern(char c, String pattern, boolean caseInsensitive) {
                char testChar = caseInsensitive ? Character.toLowerCase(c) : c;
                int i = 0;
                while (i < pattern.length()) {
                    char start = pattern.charAt(i);
                    if (start == '\\\\' && i + 1 < pattern.length()) {
                        char escaped = pattern.charAt(i + 1);
                        int consumed = 2;
                        char expected = switch (escaped) {
                            case 'n' -> '\\n';
                            case 'r' -> '\\r';
                            case 't' -> '\\t';
                            case '\\\\' -> '\\\\';
                            case ']' -> ']';
                            case '-' -> '-';
                            case 'x' -> {
                                if (i + 4 <= pattern.length()) {
                                    try {
                                        var hex = pattern.substring(i + 2, i + 4);
                                        consumed = 4;
                                        yield (char) Integer.parseInt(hex, 16);
                                    } catch (NumberFormatException e) { yield 'x'; }
                                }
                                yield 'x';
                            }
                            case 'u' -> {
                                if (i + 6 <= pattern.length()) {
                                    try {
                                        var hex = pattern.substring(i + 2, i + 6);
                                        consumed = 6;
                                        yield (char) Integer.parseInt(hex, 16);
                                    } catch (NumberFormatException e) { yield 'u'; }
                                }
                                yield 'u';
                            }
                            default -> escaped;
                        };
                        if (caseInsensitive) expected = Character.toLowerCase(expected);
                        if (testChar == expected) return true;
                        i += consumed;
                        continue;
                    }
                    if (i + 2 < pattern.length() && pattern.charAt(i + 1) == '-') {
                        char end = pattern.charAt(i + 2);
                        if (caseInsensitive) {
                            start = Character.toLowerCase(start);
                            end = Character.toLowerCase(end);
                        }
                        if (testChar >= start && testChar <= end) return true;
                        i += 3;
                    } else {
                        if (caseInsensitive) start = Character.toLowerCase(start);
                        if (testChar == start) return true;
                        i++;
                    }
                }
                return false;
            }
        """;

    private final Grammar grammar;
    private final String packageName;
    private final String className;
    private final ErrorReporting errorReporting;
    private final ParserConfig config;
    private boolean inWhitespaceRuleGeneration;

    /**
     * Phase 1.8: effective set of rule names that bypass the packrat cache. When
     * {@link ParserConfig#selectivePackrat()} is on AND the caller-supplied
     * {@link ParserConfig#packratSkipRules()} is empty, this field is populated by
     * {@link PackratAnalyzer#autoSkipPackratRules(Grammar)}. When the caller supplied an
     * explicit non-empty skip set, that set is honoured verbatim (no auto augmentation).
     * When {@code selectivePackrat} is off, this field is empty and the cache is consulted
     * for every non-LR rule as before.
     */
    private final Set<String> effectivePackratSkipRules;

    /** Lazily-computed grammar-wide FIRST sets for §7.1F dispatch. */
    private ChoiceDispatchAnalyzer.FirstSets firstSets;

    // ============================================================
    // Phase G2: Sequence chunking (split long Sequence bodies into
    // per-chunk helper methods to keep emitted methods under the
    // HotSpot FreqInlineSize threshold; companion to Phase G's
    // per-alternative helper extraction).
    // ============================================================
    /** Phase G2 thresholds — see {@link #shouldChunkSequence(Expression.Sequence)}. */
    private static final int SEQ_CHUNK_ELEMENT_THRESHOLD = 4;
    private static final int SEQ_CHUNK_BYTE_THRESHOLD = 1200;

    /** Phase G2: maximum cumulative element-weight packed into a single chunk
     *  before a new chunk is started. Bounds chunk-method bytecode size. */
    private static final int SEQ_CHUNK_MAX_WEIGHT = 1200;

    /** Phase G2: maximum elements per chunk (cap regardless of weight). */
    private static final int SEQ_CHUNK_MAX_ELEMENTS = 3;

    /**
     * Phase G2: per-rule-emission list of chunk-helper method bodies that need
     * to be emitted at class scope. Each helper has its OWN StringBuilder so
     * that nested chunked Sequences (a chunk helper containing a chunked
     * sub-Sequence) don't interleave bodies — the outer helper finishes its
     * own buffer while inner helpers occupy separate sibling buffers. All
     * buffers are appended to {@code sb} in insertion order at rule-emission
     * end by {@link #generateCstRuleMethods(StringBuilder)}.
     */
    private java.util.List<StringBuilder> pendingChunkHelpers;

    /**
     * Phase G2: counter assigning unique sequence ids within the currently-
     * emitting rule — used to name {@code parse_<rule>_seq<N>_chunk<L>} helpers.
     */
    private int currentRuleSequenceCounter;

    /**
     * Phase G2: name of the rule currently being emitted (sanitized form used
     * in helper method names). Set by {@link #generateCstRuleMethods} around
     * each rule's emission.
     */
    private String currentRuleName;

    /**
     * Phase G2: rule-id constant name for the currently-emitting rule. Chunk
     * helpers re-declare {@code __ruleName} locally to make in-scope what the
     * inner emissions reference (TokenBoundary, ZeroOrMore wrap-as-NonTerminal).
     */
    private String currentRuleIdConst;

    // ============================================================
    // Phase H: nested-Choice extraction (extracts heavy Choices that
    // appear inside Sequence/repetition contexts into per-rule helper
    // methods). Companion to Phase G (top-level rule Choice extraction)
    // and Phase G2 (Sequence chunking).
    // ============================================================
    /** Phase H: per-rule unique counter for nested Choice helpers. */
    private int currentRuleChoiceCounter;

    /** Phase H thresholds — extract a nested Choice when EITHER applies. */
    private static final int NESTED_CHOICE_ALT_THRESHOLD = 8;
    private static final int NESTED_CHOICE_BYTE_THRESHOLD = 2500;

    /** Phase 1F: lazy FIRST set computation (per-generator, single pass over grammar). */
    private ChoiceDispatchAnalyzer.FirstSets firstSets() {
        if (firstSets == null) {
            firstSets = ChoiceDispatchAnalyzer.FirstSets.compute(grammar);
        }
        return firstSets;
    }

    private ParserGenerator(Grammar grammar,
                            String packageName,
                            String className,
                            ErrorReporting errorReporting,
                            ParserConfig config) {
        validateConfig(grammar, config);
        this.grammar = grammar;
        this.packageName = packageName;
        this.className = className;
        this.errorReporting = errorReporting;
        this.config = config;
        this.inWhitespaceRuleGeneration = false;
        this.effectivePackratSkipRules = computeEffectiveSkipRules(grammar, config);
    }

    /**
     * Phase 1.8 (selective packrat auto-skip). Resolves {@link ParserConfig#packratSkipRules()}
     * into the final skip-set used during emission.
     *
     * <ul>
     *   <li>{@code selectivePackrat=false} → empty set (cache used for every non-LR rule).</li>
     *   <li>{@code selectivePackrat=true} with non-empty {@code packratSkipRules} → the caller's
     *       explicit set, verbatim (no auto-augmentation; honors the existing 0.2.9 contract).</li>
     *   <li>{@code selectivePackrat=true} with empty {@code packratSkipRules} → the auto-detected
     *       set from {@link PackratAnalyzer#autoSkipPackratRules(Grammar)}. Left-recursive rules
     *       are excluded by the analyzer.</li>
     * </ul>
     */
    private static Set<String> computeEffectiveSkipRules(Grammar grammar, ParserConfig config) {
        if (!config.selectivePackrat()) {
            return Set.of();
        }
        if (!config.packratSkipRules()
                   .isEmpty()) {
            return Set.copyOf(config.packratSkipRules());
        }
        return PackratAnalyzer.autoSkipPackratRules(grammar);
    }

    /**
     * 0.2.9 — reject configurations that would produce incorrect generated code
     * for left-recursive grammars. Currently enforces the rule that LR rules
     * cannot appear in {@link ParserConfig#packratSkipRules()}: the Warth
     * seed-and-grow loop requires the packrat cache to persist seeds across
     * iterations.
     */
    private static void validateConfig(Grammar grammar, ParserConfig config) {
        if (!config.selectivePackrat() || config.packratSkipRules()
                                                .isEmpty()) {
            return;
        }
        var lrRules = grammar.leftRecursiveRules();
        if (lrRules.isEmpty()) {
            return;
        }
        for (var skip : config.packratSkipRules()) {
            if (lrRules.contains(skip)) {
                throw new IllegalArgumentException(
                "rule '" + skip + "' is left-recursive; cannot be in packratSkipRules");
            }
        }
    }

    // === Spike A: emission helpers for raw-nullable vs Option CstParseResult ===
    private String nodeUnwrap(String varName) {
        return config.mutableParseResult()
               ? varName + ".node"
               : varName + ".node.unwrap()";
    }

    private String nodeIsPresent(String varName) {
        return config.mutableParseResult()
               ? varName + ".node != null"
               : varName + ".node.isPresent()";
    }

    private String textOrEmpty(String varName) {
        return config.mutableParseResult()
               ? "(" + varName + ".text != null ? " + varName + ".text : \"\")"
               : varName + ".text.or(\"\")";
    }

    private String endLocationUnwrap(String varName) {
        return config.mutableParseResult()
               ? varName + ".endLocation"
               : varName + ".endLocation.unwrap()";
    }

    private String expectedUnwrap(String varName) {
        return config.mutableParseResult()
               ? varName + ".expected"
               : varName + ".expected.unwrap()";
    }

    /**
     * Cleanup A: returns the inline expression for {@code takePendingLeading()}.
     * Under {@code triviaPostPass=true}, the helper is a no-op returning
     * {@code List.of()}; emitting the literal here at gen-time eliminates the
     * dead method dispatch at runtime.
     */
    private String takePendingExpr() {
        return config.triviaPostPass()
               ? "List.<Trivia>of()"
               : "takePendingLeading()";
    }

    /**
     * Cleanup A: returns the inline expression for {@code savePendingLeading()}.
     * Under flag-ON, save returns {@code 0} (the sentinel); we just emit it.
     */
    private String savePendingExpr() {
        return config.triviaPostPass()
               ? "0"
               : "savePendingLeading()";
    }

    /**
     * Cleanup A: emit a {@code restorePendingLeading(<snapshotVar>)} statement.
     * Under flag-ON, emits nothing — the call would be a no-op anyway.
     */
    private void emitRestorePending(StringBuilder sb, String indent, String snapshotVar) {
        if (config.triviaPostPass()) {
            return;
        }
        sb.append(indent)
          .append("restorePendingLeading(")
          .append(snapshotVar)
          .append(");\n");
    }

    /**
     * Cleanup A: emit an {@code appendPending(<expr>)} statement, evaluating
     * {@code expr} eagerly (so any side-effect like {@code skipWhitespace()}
     * still runs). Under flag-ON, emits {@code expr;} (a statement) so the
     * skip still occurs but the buffer append is elided.
     */
    private void emitAppendPendingFromExpr(StringBuilder sb, String indent, String expr) {
        if (config.triviaPostPass()) {
            sb.append(indent)
              .append(expr)
              .append(";\n");
            return;
        }
        sb.append(indent)
          .append("appendPending(")
          .append(expr)
          .append(");\n");
    }

    /**
     * Cleanup A: emit a {@code pendingLeadingTrivia.addAll(<expr>)} statement
     * for the rule-failure re-deposit path. Under flag-ON, the generated
     * parser's pendingLeadingTrivia field is unused; skip the statement.
     */
    private void emitPendingAddAll(StringBuilder sb, String indent, String expr) {
        if (config.triviaPostPass()) {
            return;
        }
        sb.append(indent)
          .append("pendingLeadingTrivia.addAll(")
          .append(expr)
          .append(");\n");
    }

    public static ParserGenerator parserGenerator(Grammar grammar, String packageName, String className) {
        return new ParserGenerator(grammar, packageName, className, ErrorReporting.BASIC, ParserConfig.DEFAULT);
    }

    public static ParserGenerator parserGenerator(Grammar grammar,
                                                  String packageName,
                                                  String className,
                                                  ErrorReporting errorReporting) {
        return new ParserGenerator(grammar, packageName, className, errorReporting, ParserConfig.DEFAULT);
    }

    public static ParserGenerator parserGenerator(Grammar grammar,
                                                  String packageName,
                                                  String className,
                                                  ParserConfig config) {
        return new ParserGenerator(grammar, packageName, className, ErrorReporting.BASIC, config);
    }

    public static ParserGenerator parserGenerator(Grammar grammar,
                                                  String packageName,
                                                  String className,
                                                  ErrorReporting errorReporting,
                                                  ParserConfig config) {
        return new ParserGenerator(grammar, packageName, className, errorReporting, config);
    }

    public String generate() {
        var sb = new StringBuilder(INITIAL_BUFFER_SIZE);
        generatePackage(sb);
        generateImports(sb);
        generateClassStart(sb);
        generateRuleIdInterface(sb);
        generateActionsField(sb);
        generateParseContext(sb);
        generateParseMethods(sb);
        generateRuleMethods(sb);
        generateHelperMethods(sb);
        generateClassEnd(sb);
        return sb.toString();
    }

    /**
     * Generate a standalone parser that returns CST (Concrete Syntax Tree) with trivia.
     * The generated parser preserves all source information including whitespace and comments.
     */
    public String generateCst() {
        var sb = new StringBuilder(INITIAL_BUFFER_SIZE);
        generatePackage(sb);
        generateCstImports(sb);
        generateCstClassStart(sb);
        generateRuleIdInterface(sb);
        generateCstTypes(sb);
        generateCstParseContext(sb);
        generateCstParseMethods(sb);
        generateCstRuleMethods(sb);
        generateCstHelperMethods(sb);
        if (config.triviaPostPass()) {
            generateCstTriviaPostPass(sb);
        }
        generateClassEnd(sb);
        return sb.toString();
    }

    private void generatePackage(StringBuilder sb) {
        sb.append("package ")
          .append(packageName)
          .append(";\n\n");
    }

    private void generateImports(StringBuilder sb) {
        sb.append("""
            import org.pragmatica.lang.Cause;
            import org.pragmatica.lang.Option;
            import org.pragmatica.lang.Result;

            import java.util.ArrayDeque;
            import java.util.ArrayList;
            import java.util.HashMap;
            import java.util.LinkedHashSet;
            import java.util.List;
            import java.util.Map;
            import java.util.function.Function;
            import java.util.function.Supplier;

            """);
    }

    private void generateClassStart(StringBuilder sb) {
        sb.append("/**\n");
        sb.append(" * Generated PEG parser.\n");
        sb.append(" * This parser was generated from a PEG grammar and depends only on pragmatica-lite:core.\n");
        sb.append(" */\n");
        sb.append("public final class ")
          .append(className)
          .append(" {\n\n");
        // Add ParseError type
        sb.append("""
                // === Parse Error ===

                public record ParseError(int line, int column, String reason) implements Cause {
                    @Override
                    public String message() {
                        return reason + " at " + line + ":" + column;
                    }
                }

            """);
    }

    private void generateParseContext(StringBuilder sb) {
        sb.append("""
                // === Parse Context ===

                private String input;
                private int pos;
                private int line;
                private int column;
                private Map<Long, ParseResult> cache;
                private Map<String, String> captures;
                private boolean packratEnabled = true;
                private int furthestPos;
                private int furthestLine;
                private int furthestColumn;
                private final LinkedHashSet<String> furthestExpected = new LinkedHashSet<>();
                private String furthestExpectedJoined;

                /**
                 * Enable or disable packrat memoization.
                 * Disabling may reduce memory usage for large inputs.
                 */
                public void setPackratEnabled(boolean enabled) {
                    this.packratEnabled = enabled;
                }

                private void init(String input) {
                    this.input = input;
                    this.pos = 0;
                    this.line = 1;
                    this.column = 1;
                    this.cache = packratEnabled ? new HashMap<>() : null;
                    this.captures = new HashMap<>();
                    this.furthestPos = 0;
                    this.furthestLine = 1;
                    this.furthestColumn = 1;
                    this.furthestExpected.clear();
                    this.furthestExpectedJoined = null;
                }

                private boolean isAtEnd() {
                    return pos >= input.length();
                }

                private char peek() {
                    return input.charAt(pos);
                }

                private char peek(int offset) {
                    return input.charAt(pos + offset);
                }

                private char advance() {
                    char c = input.charAt(pos++);
                    if (c == '\\n') {
                        line++;
                        column = 1;
                    } else {
                        column++;
                    }
                    return c;
                }

                private int remaining() {
                    return input.length() - pos;
                }

                private String substring(int start, int end) {
                    return input.substring(start, end);
                }

                private long cacheKey(int ruleId, int position) {
                    return ((long) ruleId << 32) | position;
                }

                private void trackFailure(String expected) {
                    if (pos > furthestPos) {
                        furthestPos = pos;
                        furthestLine = line;
                        furthestColumn = column;
                        furthestExpected.clear();
                        furthestExpected.add(expected);
                        furthestExpectedJoined = null;
                    } else if (pos == furthestPos && furthestExpected.add(expected)) {
                        furthestExpectedJoined = null;
                    }
                }

                private String furthestExpectedJoined() {
                    if (furthestExpectedJoined == null) {
                        furthestExpectedJoined = String.join(" or ", furthestExpected);
                    }
                    return furthestExpectedJoined;
                }

            """);
    }

    private void generateParseMethods(StringBuilder sb) {
        var startRule = grammar.effectiveStartRule();
        var startRuleName = startRule.isPresent()
                            ? startRule.unwrap()
                                       .name()
                            : grammar.rules()
                                     .getFirst()
                                     .name();
        sb.append("""
                // === Public Parse Methods ===

                public Result<Object> parse(String input) {
                    init(input);
                    var result = parse_%s();
                    if (result.isFailure()) {
                        var errorLine = furthestPos > 0 ? furthestLine : line;
                        var errorColumn = furthestPos > 0 ? furthestColumn : column;
                        var expected = !furthestExpected.isEmpty() ? furthestExpectedJoined() : result.expected.or("valid input");
                        return Result.failure(new ParseError(errorLine, errorColumn, "expected " + expected));
                    }
                    if (!isAtEnd()) {
                        var errorLine = furthestPos > 0 ? furthestLine : line;
                        var errorColumn = furthestPos > 0 ? furthestColumn : column;
                        return Result.failure(new ParseError(errorLine, errorColumn, "unexpected input"));
                    }
                    return Result.success(result.value.or(null));
                }

            """.formatted(sanitize(startRuleName)));
    }

    private void generateRuleMethods(StringBuilder sb) {
        sb.append("    // === Rule Parsing Methods ===\n\n");
        int ruleId = 0;
        for (var rule : grammar.rules()) {
            generateRuleMethod(sb, rule, ruleId++ );
        }
    }

    private void generateRuleMethod(StringBuilder sb, Rule rule, int ruleId) {
        var methodName = "parse_" + sanitize(rule.name());
        sb.append("    private ParseResult ")
          .append(methodName)
          .append("() {\n");
        sb.append("        int startPos = pos;\n");
        sb.append("        int startLine = line;\n");
        sb.append("        int startColumn = column;\n");
        sb.append("        \n");
        sb.append("        // Check cache\n");
        sb.append("        long key = cacheKey(")
          .append(ruleId)
          .append(", startPos);\n");
        sb.append("        if (cache != null) {\n");
        sb.append("            var cached = cache.get(key);\n");
        sb.append("            if (cached != null) {\n");
        sb.append("                pos = cached.endPos;\n");
        sb.append("                line = cached.endLine;\n");
        sb.append("                column = cached.endColumn;\n");
        sb.append("                return cached;\n");
        sb.append("            }\n");
        sb.append("        }\n");
        sb.append("        \n");
        sb.append("        skipWhitespace();\n");
        sb.append("        var values = new ArrayList<Object>();\n");
        sb.append("        \n");
        int[] counter = {0};
        generateExpressionCode(sb, rule.expression(), "result", 2, counter);
        sb.append("        \n");
        sb.append("        if (result.isSuccess()) {\n");
        // 0.2.6 — lambda override dispatch. If a lambda is attached for this rule's
        // RuleId class (keyed by simple class name), it wins over any inline action.
        var ruleClassName = toClassName(rule.name());
        sb.append("            var __lambda = lambdaActions.get(\"")
          .append(ruleClassName)
          .append("\");\n");
        sb.append("            if (__lambda != null) {\n");
        sb.append("                var __sv = new SemanticValues(substring(startPos, pos), values);\n");
        sb.append("                result = ParseResult.success(__lambda.apply(__sv), pos, line, column);\n");
        sb.append("            } else ");
        // Generate action if present
        if (rule.action()
                .isPresent()) {
            var actionCode = transformActionCode(rule.action()
                                                     .unwrap());
            sb.append("{\n");
            sb.append("            String $0 = substring(startPos, pos);\n");
            sb.append("            Object value;\n");
            sb.append("            ")
              .append(wrapActionCode(actionCode))
              .append("\n");
            sb.append("            result = ParseResult.success(value, pos, line, column);\n");
            sb.append("            }\n");
        }else {
            sb.append("{\n");
            sb.append("            result = ParseResult.success(\n");
            sb.append("                values.isEmpty() ? substring(startPos, pos) : values.size() == 1 ? values.getFirst() : values,\n");
            sb.append("                pos, line, column);\n");
            sb.append("            }\n");
        }
        sb.append("        } else {\n");
        sb.append("            pos = startPos;\n");
        sb.append("            line = startLine;\n");
        sb.append("            column = startColumn;\n");
        // Use custom error message if available
        if (rule.hasErrorMessage()) {
            sb.append("            result = ParseResult.failure(\"")
              .append(escape(rule.errorMessage()
                                 .unwrap()))
              .append("\");\n");
        }else if (rule.hasExpected()) {
            // 0.2.4: %expected semantic label replaces raw-token join at this
            // rule's failure site so diagnostics read naturally.
            sb.append("            trackFailure(\"")
              .append(escape(rule.expected()
                                 .unwrap()))
              .append("\");\n");
            sb.append("            result = ParseResult.failure(\"")
              .append(escape(rule.expected()
                                 .unwrap()))
              .append("\");\n");
        }
        sb.append("        }\n");
        sb.append("        \n");
        sb.append("        if (cache != null) cache.put(key, result);\n");
        sb.append("        return result;\n");
        sb.append("    }\n\n");
    }

    private void generateExpressionCode(StringBuilder sb,
                                        Expression expr,
                                        String resultVar,
                                        int indent,
                                        int[] counter) {
        if (indent > MAX_RECURSION_DEPTH) {
            throw new IllegalStateException("Grammar expression nesting exceeds maximum depth of " + MAX_RECURSION_DEPTH);
        }
        var pad = "    ".repeat(indent);
        int id = counter[0]++ ;
        // Get unique ID for this expression
        switch (expr) {
            case Expression.Literal lit -> {
                sb.append(pad)
                  .append("var ")
                  .append(resultVar)
                  .append(" = matchLiteral(\"")
                  .append(escape(lit.text()))
                  .append("\", ")
                  .append(lit.caseInsensitive())
                  .append(");\n");
            }
            case Expression.CharClass cc -> {
                sb.append(pad)
                  .append("var ")
                  .append(resultVar)
                  .append(" = matchCharClass(\"")
                  .append(escape(cc.pattern()))
                  .append("\", ")
                  .append(cc.negated())
                  .append(", ")
                  .append(cc.caseInsensitive())
                  .append(");\n");
            }
            case Expression.Dictionary dict -> {
                sb.append(pad)
                  .append("var ")
                  .append(resultVar)
                  .append(" = matchDictionary(List.of(");
                var words = dict.words();
                for (int i = 0; i < words.size(); i++ ) {
                    if (i > 0) sb.append(", ");
                    sb.append("\"")
                      .append(escape(words.get(i)))
                      .append("\"");
                }
                sb.append("), ")
                  .append(dict.caseInsensitive())
                  .append(");\n");
            }
            case Expression.Any any -> {
                sb.append(pad)
                  .append("var ")
                  .append(resultVar)
                  .append(" = matchAny();\n");
            }
            case Expression.Reference ref -> {
                sb.append(pad)
                  .append("var ")
                  .append(resultVar)
                  .append(" = parse_")
                  .append(sanitize(ref.ruleName()))
                  .append("();\n");
                sb.append(pad)
                  .append("if (")
                  .append(resultVar)
                  .append(".isSuccess() && ")
                  .append(resultVar)
                  .append(".value.isPresent()) {\n");
                sb.append(pad)
                  .append("    values.add(")
                  .append(resultVar)
                  .append(".value.unwrap());\n");
                sb.append(pad)
                  .append("}\n");
            }
            case Expression.Sequence seq -> {
                var seqStart = "seqStart" + id;
                var cutVar = "cut" + id;
                sb.append(pad)
                  .append("ParseResult ")
                  .append(resultVar)
                  .append(" = ParseResult.success(null, pos, line, column);\n");
                sb.append(pad)
                  .append("int ")
                  .append(seqStart)
                  .append(" = pos;\n");
                sb.append(pad)
                  .append("int ")
                  .append(seqStart)
                  .append("Line = line;\n");
                sb.append(pad)
                  .append("int ")
                  .append(seqStart)
                  .append("Column = column;\n");
                sb.append(pad)
                  .append("boolean ")
                  .append(cutVar)
                  .append(" = false;\n");
                int i = 0;
                for (var elem : seq.elements()) {
                    if (!inWhitespaceRuleGeneration && !isPredicate(elem)) {
                        sb.append(pad)
                          .append("skipWhitespace();\n");
                    }
                    generateExpressionCode(sb, elem, "elem" + id + "_" + i, indent, counter);
                    // Check for cut failure propagation
                    sb.append(pad)
                      .append("if (elem")
                      .append(id)
                      .append("_")
                      .append(i)
                      .append(".isCutFailure()) {\n");
                    sb.append(pad)
                      .append("    pos = ")
                      .append(seqStart)
                      .append(";\n");
                    sb.append(pad)
                      .append("    line = ")
                      .append(seqStart)
                      .append("Line;\n");
                    sb.append(pad)
                      .append("    column = ")
                      .append(seqStart)
                      .append("Column;\n");
                    sb.append(pad)
                      .append("    ")
                      .append(resultVar)
                      .append(" = elem")
                      .append(id)
                      .append("_")
                      .append(i)
                      .append(";\n");
                    sb.append(pad)
                      .append("} else if (elem")
                      .append(id)
                      .append("_")
                      .append(i)
                      .append(".isFailure()) {\n");
                    sb.append(pad)
                      .append("    pos = ")
                      .append(seqStart)
                      .append(";\n");
                    sb.append(pad)
                      .append("    line = ")
                      .append(seqStart)
                      .append("Line;\n");
                    sb.append(pad)
                      .append("    column = ")
                      .append(seqStart)
                      .append("Column;\n");
                    sb.append(pad)
                      .append("    ")
                      .append(resultVar)
                      .append(" = ")
                      .append(cutVar)
                      .append(" ? elem")
                      .append(id)
                      .append("_")
                      .append(i)
                      .append(".asCutFailure() : elem")
                      .append(id)
                      .append("_")
                      .append(i)
                      .append(";\n");
                    sb.append(pad)
                      .append("}\n");
                    // Track if this element was a cut
                    if (elem instanceof Expression.Cut) {
                        sb.append(pad)
                          .append(cutVar)
                          .append(" = true;\n");
                    }
                    i++ ;
                }
            }
            case Expression.Choice choice -> {
                var choiceStart = "choiceStart" + id;
                var oldVals = "oldValues" + id;
                sb.append(pad)
                  .append("ParseResult ")
                  .append(resultVar)
                  .append(" = null;\n");
                sb.append(pad)
                  .append("int ")
                  .append(choiceStart)
                  .append(" = pos;\n");
                sb.append(pad)
                  .append("int ")
                  .append(choiceStart)
                  .append("Line = line;\n");
                sb.append(pad)
                  .append("int ")
                  .append(choiceStart)
                  .append("Column = column;\n");
                int i = 0;
                for (var alt : choice.alternatives()) {
                    sb.append(pad)
                      .append("var choiceValues")
                      .append(id)
                      .append("_")
                      .append(i)
                      .append(" = new ArrayList<Object>();\n");
                    sb.append(pad)
                      .append("var ")
                      .append(oldVals)
                      .append("_")
                      .append(i)
                      .append(" = values;\n");
                    sb.append(pad)
                      .append("values = choiceValues")
                      .append(id)
                      .append("_")
                      .append(i)
                      .append(";\n");
                    generateExpressionCode(sb, alt, "alt" + id + "_" + i, indent, counter);
                    sb.append(pad)
                      .append("values = ")
                      .append(oldVals)
                      .append("_")
                      .append(i)
                      .append(";\n");
                    sb.append(pad)
                      .append("if (alt")
                      .append(id)
                      .append("_")
                      .append(i)
                      .append(".isSuccess()) {\n");
                    sb.append(pad)
                      .append("    values.addAll(choiceValues")
                      .append(id)
                      .append("_")
                      .append(i)
                      .append(");\n");
                    sb.append(pad)
                      .append("    ")
                      .append(resultVar)
                      .append(" = alt")
                      .append(id)
                      .append("_")
                      .append(i)
                      .append(";\n");
                    sb.append(pad)
                      .append("} else if (alt")
                      .append(id)
                      .append("_")
                      .append(i)
                      .append(".isCutFailure()) {\n");
                    // Convert CutFailure to regular failure for parent choices to allow backtracking at higher levels
                    sb.append(pad)
                      .append("    ")
                      .append(resultVar)
                      .append(" = alt")
                      .append(id)
                      .append("_")
                      .append(i)
                      .append(".asRegularFailure();\n");
                    sb.append(pad)
                      .append("} else {\n");
                    sb.append(pad)
                      .append("    pos = ")
                      .append(choiceStart)
                      .append(";\n");
                    sb.append(pad)
                      .append("    line = ")
                      .append(choiceStart)
                      .append("Line;\n");
                    sb.append(pad)
                      .append("    column = ")
                      .append(choiceStart)
                      .append("Column;\n");
                    i++ ;
                }
                // Close all else blocks
                for (int j = 0; j < choice.alternatives()
                                          .size(); j++ ) {
                    sb.append(pad)
                      .append("}\n");
                }
                sb.append(pad)
                  .append("if (")
                  .append(resultVar)
                  .append(" == null) {\n");
                sb.append(pad)
                  .append("    ")
                  .append(resultVar)
                  .append(" = ParseResult.failure(\"one of alternatives\");\n");
                sb.append(pad)
                  .append("}\n");
            }
            case Expression.ZeroOrMore zom -> {
                var zomElem = "zomElem" + id;
                var beforePos = "beforePos" + id;
                sb.append(pad)
                  .append("ParseResult ")
                  .append(resultVar)
                  .append(" = ParseResult.success(null, pos, line, column);\n");
                sb.append(pad)
                  .append("while (true) {\n");
                sb.append(pad)
                  .append("    int ")
                  .append(beforePos)
                  .append(" = pos;\n");
                sb.append(pad)
                  .append("    int ")
                  .append(beforePos)
                  .append("Line = line;\n");
                sb.append(pad)
                  .append("    int ")
                  .append(beforePos)
                  .append("Column = column;\n");
                if (!inWhitespaceRuleGeneration) {
                    sb.append(pad)
                      .append("    skipWhitespace();\n");
                }
                generateExpressionCode(sb, zom.expression(), zomElem, indent + 1, counter);
                // CutFailure must propagate
                sb.append(pad)
                  .append("    if (")
                  .append(zomElem)
                  .append(".isCutFailure()) { ")
                  .append(resultVar)
                  .append(" = ")
                  .append(zomElem)
                  .append("; break; }\n");
                sb.append(pad)
                  .append("    if (")
                  .append(zomElem)
                  .append(".isFailure() || pos == ")
                  .append(beforePos)
                  .append(") {\n");
                sb.append(pad)
                  .append("        pos = ")
                  .append(beforePos)
                  .append(";\n");
                sb.append(pad)
                  .append("        line = ")
                  .append(beforePos)
                  .append("Line;\n");
                sb.append(pad)
                  .append("        column = ")
                  .append(beforePos)
                  .append("Column;\n");
                sb.append(pad)
                  .append("        break;\n");
                sb.append(pad)
                  .append("    }\n");
                sb.append(pad)
                  .append("}\n");
            }
            case Expression.OneOrMore oom -> {
                var oomFirst = "oomFirst" + id;
                var oomElem = "oomElem" + id;
                var beforePos = "beforePos" + id;
                generateExpressionCode(sb, oom.expression(), oomFirst, indent, counter);
                sb.append(pad)
                  .append("var ")
                  .append(resultVar)
                  .append(" = ")
                  .append(oomFirst)
                  .append(";\n");
                sb.append(pad)
                  .append("if (")
                  .append(oomFirst)
                  .append(".isSuccess()) {\n");
                sb.append(pad)
                  .append("    while (true) {\n");
                sb.append(pad)
                  .append("        int ")
                  .append(beforePos)
                  .append(" = pos;\n");
                sb.append(pad)
                  .append("        int ")
                  .append(beforePos)
                  .append("Line = line;\n");
                sb.append(pad)
                  .append("        int ")
                  .append(beforePos)
                  .append("Column = column;\n");
                if (!inWhitespaceRuleGeneration) {
                    sb.append(pad)
                      .append("        skipWhitespace();\n");
                }
                generateExpressionCode(sb, oom.expression(), oomElem, indent + 2, counter);
                // CutFailure must propagate
                sb.append(pad)
                  .append("        if (")
                  .append(oomElem)
                  .append(".isCutFailure()) { ")
                  .append(resultVar)
                  .append(" = ")
                  .append(oomElem)
                  .append("; break; }\n");
                sb.append(pad)
                  .append("        if (")
                  .append(oomElem)
                  .append(".isFailure() || pos == ")
                  .append(beforePos)
                  .append(") {\n");
                sb.append(pad)
                  .append("            pos = ")
                  .append(beforePos)
                  .append(";\n");
                sb.append(pad)
                  .append("            line = ")
                  .append(beforePos)
                  .append("Line;\n");
                sb.append(pad)
                  .append("            column = ")
                  .append(beforePos)
                  .append("Column;\n");
                sb.append(pad)
                  .append("            break;\n");
                sb.append(pad)
                  .append("        }\n");
                sb.append(pad)
                  .append("    }\n");
                sb.append(pad)
                  .append("}\n");
            }
            case Expression.Optional opt -> {
                var optStart = "optStart" + id;
                var optElem = "optElem" + id;
                sb.append(pad)
                  .append("int ")
                  .append(optStart)
                  .append(" = pos;\n");
                sb.append(pad)
                  .append("int ")
                  .append(optStart)
                  .append("Line = line;\n");
                sb.append(pad)
                  .append("int ")
                  .append(optStart)
                  .append("Column = column;\n");
                generateExpressionCode(sb, opt.expression(), optElem, indent, counter);
                // CutFailure must propagate - don't treat as success
                sb.append(pad)
                  .append("ParseResult ")
                  .append(resultVar)
                  .append(";\n");
                sb.append(pad)
                  .append("if (")
                  .append(optElem)
                  .append(".isCutFailure()) {\n");
                sb.append(pad)
                  .append("    pos = ")
                  .append(optStart)
                  .append("; line = ")
                  .append(optStart)
                  .append("Line; column = ")
                  .append(optStart)
                  .append("Column;\n");
                sb.append(pad)
                  .append("    ")
                  .append(resultVar)
                  .append(" = ")
                  .append(optElem)
                  .append(";\n");
                sb.append(pad)
                  .append("} else if (")
                  .append(optElem)
                  .append(".isSuccess()) {\n");
                sb.append(pad)
                  .append("    ")
                  .append(resultVar)
                  .append(" = ")
                  .append(optElem)
                  .append(";\n");
                sb.append(pad)
                  .append("} else {\n");
                sb.append(pad)
                  .append("    pos = ")
                  .append(optStart)
                  .append("; line = ")
                  .append(optStart)
                  .append("Line; column = ")
                  .append(optStart)
                  .append("Column;\n");
                sb.append(pad)
                  .append("    ")
                  .append(resultVar)
                  .append(" = ParseResult.success(null, pos, line, column);\n");
                sb.append(pad)
                  .append("}\n");
            }
            case Expression.Repetition rep -> {
                var repCount = "repCount" + id;
                var repStart = "repStart" + id;
                var repElem = "repElem" + id;
                var beforePos = "beforePos" + id;
                var repCutFailed = "repCutFailed" + id;
                sb.append(pad)
                  .append("int ")
                  .append(repCount)
                  .append(" = 0;\n");
                sb.append(pad)
                  .append("int ")
                  .append(repStart)
                  .append(" = pos;\n");
                sb.append(pad)
                  .append("int ")
                  .append(repStart)
                  .append("Line = line;\n");
                sb.append(pad)
                  .append("int ")
                  .append(repStart)
                  .append("Column = column;\n");
                sb.append(pad)
                  .append("ParseResult ")
                  .append(repCutFailed)
                  .append(" = null;\n");
                var maxStr = rep.max()
                                .isPresent()
                             ? String.valueOf(rep.max()
                                                 .unwrap())
                             : "Integer.MAX_VALUE";
                sb.append(pad)
                  .append("while (")
                  .append(repCount)
                  .append(" < ")
                  .append(maxStr)
                  .append(") {\n");
                sb.append(pad)
                  .append("    int ")
                  .append(beforePos)
                  .append(" = pos;\n");
                sb.append(pad)
                  .append("    int ")
                  .append(beforePos)
                  .append("Line = line;\n");
                sb.append(pad)
                  .append("    int ")
                  .append(beforePos)
                  .append("Column = column;\n");
                if (!inWhitespaceRuleGeneration) {
                    sb.append(pad)
                      .append("    if (")
                      .append(repCount)
                      .append(" > 0) skipWhitespace();\n");
                }
                generateExpressionCode(sb, rep.expression(), repElem, indent + 1, counter);
                // CutFailure must propagate
                sb.append(pad)
                  .append("    if (")
                  .append(repElem)
                  .append(".isCutFailure()) { ")
                  .append(repCutFailed)
                  .append(" = ")
                  .append(repElem)
                  .append("; break; }\n");
                sb.append(pad)
                  .append("    if (")
                  .append(repElem)
                  .append(".isFailure() || pos == ")
                  .append(beforePos)
                  .append(") {\n");
                sb.append(pad)
                  .append("        pos = ")
                  .append(beforePos)
                  .append(";\n");
                sb.append(pad)
                  .append("        line = ")
                  .append(beforePos)
                  .append("Line;\n");
                sb.append(pad)
                  .append("        column = ")
                  .append(beforePos)
                  .append("Column;\n");
                sb.append(pad)
                  .append("        break;\n");
                sb.append(pad)
                  .append("    }\n");
                sb.append(pad)
                  .append("    ")
                  .append(repCount)
                  .append("++;\n");
                sb.append(pad)
                  .append("}\n");
                sb.append(pad)
                  .append("ParseResult ")
                  .append(resultVar)
                  .append(";\n");
                sb.append(pad)
                  .append("if (")
                  .append(repCutFailed)
                  .append(" != null) {\n");
                sb.append(pad)
                  .append("    ")
                  .append(resultVar)
                  .append(" = ")
                  .append(repCutFailed)
                  .append(";\n");
                sb.append(pad)
                  .append("} else if (")
                  .append(repCount)
                  .append(" >= ")
                  .append(rep.min())
                  .append(") {\n");
                sb.append(pad)
                  .append("    ")
                  .append(resultVar)
                  .append(" = ParseResult.success(null, pos, line, column);\n");
                sb.append(pad)
                  .append("} else {\n");
                sb.append(pad)
                  .append("    pos = ")
                  .append(repStart)
                  .append("; line = ")
                  .append(repStart)
                  .append("Line; column = ")
                  .append(repStart)
                  .append("Column;\n");
                sb.append(pad)
                  .append("    ")
                  .append(resultVar)
                  .append(" = ParseResult.failure(\"at least ")
                  .append(rep.min())
                  .append(" repetitions\");\n");
                sb.append(pad)
                  .append("}\n");
            }
            case Expression.And and -> {
                var andStart = "andStart" + id;
                var andElem = "andElem" + id;
                sb.append(pad)
                  .append("int ")
                  .append(andStart)
                  .append(" = pos;\n");
                sb.append(pad)
                  .append("int ")
                  .append(andStart)
                  .append("Line = line;\n");
                sb.append(pad)
                  .append("int ")
                  .append(andStart)
                  .append("Column = column;\n");
                generateExpressionCode(sb, and.expression(), andElem, indent, counter);
                sb.append(pad)
                  .append("pos = ")
                  .append(andStart)
                  .append(";\n");
                sb.append(pad)
                  .append("line = ")
                  .append(andStart)
                  .append("Line;\n");
                sb.append(pad)
                  .append("column = ")
                  .append(andStart)
                  .append("Column;\n");
                sb.append(pad)
                  .append("var ")
                  .append(resultVar)
                  .append(" = ")
                  .append(andElem)
                  .append(".isSuccess() ? ParseResult.success(null, pos, line, column) : ")
                  .append(andElem)
                  .append(";\n");
            }
            case Expression.Not not -> {
                var notStart = "notStart" + id;
                var notElem = "notElem" + id;
                sb.append(pad)
                  .append("int ")
                  .append(notStart)
                  .append(" = pos;\n");
                sb.append(pad)
                  .append("int ")
                  .append(notStart)
                  .append("Line = line;\n");
                sb.append(pad)
                  .append("int ")
                  .append(notStart)
                  .append("Column = column;\n");
                generateExpressionCode(sb, not.expression(), notElem, indent, counter);
                sb.append(pad)
                  .append("pos = ")
                  .append(notStart)
                  .append(";\n");
                sb.append(pad)
                  .append("line = ")
                  .append(notStart)
                  .append("Line;\n");
                sb.append(pad)
                  .append("column = ")
                  .append(notStart)
                  .append("Column;\n");
                sb.append(pad)
                  .append("var ")
                  .append(resultVar)
                  .append(" = ")
                  .append(notElem)
                  .append(".isSuccess() ? ParseResult.failure(\"not match\") : ParseResult.success(null, pos, line, column);\n");
            }
            case Expression.TokenBoundary tb -> {
                var tbStart = "tbStart" + id;
                var tbElem = "tbElem" + id;
                sb.append(pad)
                  .append("int ")
                  .append(tbStart)
                  .append(" = pos;\n");
                generateExpressionCode(sb, tb.expression(), tbElem, indent, counter);
                sb.append(pad)
                  .append("var ")
                  .append(resultVar)
                  .append(" = ")
                  .append(tbElem)
                  .append(".isSuccess() ? ParseResult.success(substring(")
                  .append(tbStart)
                  .append(", pos), pos, line, column) : ")
                  .append(tbElem)
                  .append(";\n");
            }
            case Expression.Ignore ign -> {
                var ignElem = "ignElem" + id;
                generateExpressionCode(sb, ign.expression(), ignElem, indent, counter);
                sb.append(pad)
                  .append("var ")
                  .append(resultVar)
                  .append(" = ")
                  .append(ignElem)
                  .append(".isSuccess() ? ParseResult.success(null, pos, line, column) : ")
                  .append(ignElem)
                  .append(";\n");
            }
            case Expression.Capture cap -> {
                var capStart = "capStart" + id;
                var capElem = "capElem" + id;
                sb.append(pad)
                  .append("int ")
                  .append(capStart)
                  .append(" = pos;\n");
                generateExpressionCode(sb, cap.expression(), capElem, indent, counter);
                sb.append(pad)
                  .append("if (")
                  .append(capElem)
                  .append(".isSuccess()) {\n");
                sb.append(pad)
                  .append("    captures.put(\"")
                  .append(cap.name())
                  .append("\", substring(")
                  .append(capStart)
                  .append(", pos));\n");
                sb.append(pad)
                  .append("}\n");
                sb.append(pad)
                  .append("var ")
                  .append(resultVar)
                  .append(" = ")
                  .append(capElem)
                  .append(";\n");
            }
            case Expression.CaptureScope cs -> {
                var savedCaptures = "savedCaptures" + id;
                var csElem = "csElem" + id;
                sb.append(pad)
                  .append("var ")
                  .append(savedCaptures)
                  .append(" = new HashMap<>(captures);\n");
                generateExpressionCode(sb, cs.expression(), csElem, indent, counter);
                sb.append(pad)
                  .append("captures.clear();\n");
                sb.append(pad)
                  .append("captures.putAll(")
                  .append(savedCaptures)
                  .append(");\n");
                sb.append(pad)
                  .append("var ")
                  .append(resultVar)
                  .append(" = ")
                  .append(csElem)
                  .append(";\n");
            }
            case Expression.BackReference br -> {
                var captured = "captured" + id;
                sb.append(pad)
                  .append("var ")
                  .append(captured)
                  .append(" = captures.get(\"")
                  .append(br.name())
                  .append("\");\n");
                sb.append(pad)
                  .append("var ")
                  .append(resultVar)
                  .append(" = ")
                  .append(captured)
                  .append(" != null ? matchLiteral(")
                  .append(captured)
                  .append(", false) : ParseResult.failure(\"capture '\");\n");
            }
            case Expression.Cut cut -> {
                sb.append(pad)
                  .append("var ")
                  .append(resultVar)
                  .append(" = ParseResult.success(null, pos, line, column);\n");
            }
            case Expression.Group grp -> {
                generateExpressionCode(sb, grp.expression(), resultVar, indent, counter);
            }
        }
    }

    private void generateHelperMethods(StringBuilder sb) {
        sb.append("""
                // === Helper Methods ===

                private void skipWhitespace() {
            """);
        if (grammar.whitespace()
                   .isPresent()) {
            sb.append("        while (!isAtEnd()) {\n");
            sb.append("            int wsBeforePos = pos;\n");
            int[] wsCounter = {0};
            inWhitespaceRuleGeneration = true;
            generateExpressionCode(sb,
                                   grammar.whitespace()
                                          .unwrap(),
                                   "wsResult",
                                   3,
                                   wsCounter);
            inWhitespaceRuleGeneration = false;
            sb.append("            if (wsResult.isFailure() || pos == wsBeforePos) break;\n");
            sb.append("        }\n");
        }
        sb.append("""
                }

                private ParseResult matchLiteral(String text, boolean caseInsensitive) {
                    if (remaining() < text.length()) {
                        trackFailure("'" + text + "'");
                        return ParseResult.failure("'" + text + "'");
                    }
                    for (int i = 0; i < text.length(); i++) {
                        char expected = text.charAt(i);
                        char actual = peek(i);
                        if (caseInsensitive) {
                            if (Character.toLowerCase(expected) != Character.toLowerCase(actual)) {
                                trackFailure("'" + text + "'");
                                return ParseResult.failure("'" + text + "'");
                            }
                        } else {
                            if (expected != actual) {
                                trackFailure("'" + text + "'");
                                return ParseResult.failure("'" + text + "'");
                            }
                        }
                    }
                    for (int i = 0; i < text.length(); i++) {
                        advance();
                    }
                    return ParseResult.success(text, pos, line, column);
                }

                private ParseResult matchDictionary(List<String> words, boolean caseInsensitive) {
                    String longestMatch = null;
                    int longestLen = 0;
                    for (var word : words) {
                        if (matchesWord(word, caseInsensitive) && word.length() > longestLen) {
                            longestMatch = word;
                            longestLen = word.length();
                        }
                    }
                    if (longestMatch == null) {
                        trackFailure("dictionary word");
                        return ParseResult.failure("dictionary word");
                    }
                    for (int i = 0; i < longestLen; i++) {
                        advance();
                    }
                    return ParseResult.success(longestMatch, pos, line, column);
                }

            """);
        sb.append(MATCHES_WORD_METHOD);
        sb.append("""

                private ParseResult matchCharClass(String pattern, boolean negated, boolean caseInsensitive) {
                    if (isAtEnd()) {
                        trackFailure("[" + (negated ? "^" : "") + pattern + "]");
                        return ParseResult.failure("character class");
                    }
                    char c = peek();
                    boolean matches = matchesPattern(c, pattern, caseInsensitive);
                    if (negated) matches = !matches;
                    if (!matches) {
                        trackFailure("[" + (negated ? "^" : "") + pattern + "]");
                        return ParseResult.failure("character class");
                    }
                    advance();
                    return ParseResult.success(String.valueOf(c), pos, line, column);
                }

            """);
        sb.append(MATCHES_PATTERN_METHOD);
        sb.append("""

                private ParseResult matchAny() {
                    if (isAtEnd()) {
                        trackFailure("any character");
                        return ParseResult.failure("any character");
                    }
                    char c = advance();
                    return ParseResult.success(String.valueOf(c), pos, line, column);
                }

                // === Parse Result ===

                private static final class ParseResult {
                    final boolean success;
                    final Option<Object> value;
                    final Option<String> expected;
                    final int endPos;
                    final int endLine;
                    final int endColumn;
                    final boolean cutFailed;

                    private ParseResult(boolean success, Option<Object> value, Option<String> expected, int endPos, int endLine, int endColumn, boolean cutFailed) {
                        this.success = success;
                        this.value = value;
                        this.expected = expected;
                        this.endPos = endPos;
                        this.endLine = endLine;
                        this.endColumn = endColumn;
                        this.cutFailed = cutFailed;
                    }

                    boolean isSuccess() { return success; }
                    boolean isFailure() { return !success; }
                    boolean isCutFailure() { return !success && cutFailed; }

                    static ParseResult success(Object value, int endPos, int endLine, int endColumn) {
                        return new ParseResult(true, Option.some(value), Option.none(), endPos, endLine, endColumn, false);
                    }

                    static ParseResult failure(String expected) {
                        return new ParseResult(false, Option.none(), Option.some(expected), 0, 0, 0, false);
                    }

                    static ParseResult cutFailure(String expected) {
                        return new ParseResult(false, Option.none(), Option.some(expected), 0, 0, 0, true);
                    }

                    ParseResult asCutFailure() {
                        return cutFailed ? this : new ParseResult(false, Option.none(), expected, 0, 0, 0, true);
                    }

                    ParseResult asRegularFailure() {
                        return cutFailed ? new ParseResult(false, Option.none(), expected, 0, 0, 0, false) : this;
                    }
                }
            """);
    }

    /**
     * 0.2.6 — emit the Actions dispatch field + setter + nested SemanticValues.
     * Used only by the AST path (generate()). Lambdas attached here override any
     * inline grammar actions for the matching rule class. Generated parsers
     * continue to depend only on pragmatica-lite:core.
     */
    private void generateActionsField(StringBuilder sb) {
        sb.append("""
                // === Programmatic Action Attachment (0.2.6) ===

                /**
                 * Values exposed to lambda actions. Mirrors peglib's SemanticValues
                 * surface so generated parsers stay self-contained.
                 */
                public static final class SemanticValues {
                    private final String token;
                    private final List<Object> values;
                    private SemanticValues(String token, List<Object> values) {
                        this.token = token;
                        this.values = values;
                    }
                    public String token() { return token; }
                    public String str() { return token; }
                    public int toInt() { return Integer.parseInt(token.trim()); }
                    public long toLong() { return Long.parseLong(token.trim()); }
                    public double toDouble() { return Double.parseDouble(token.trim()); }
                    public int size() { return values.size(); }
                    public boolean isEmpty() { return values.isEmpty(); }
                    @SuppressWarnings("unchecked")
                    public <T> T get(int index) { return (T) values.get(index); }
                    public List<Object> values() { return List.copyOf(values); }
                }

                private final Map<String, Function<SemanticValues, Object>> lambdaActions = new HashMap<>();

                /**
                 * Attach a lambda action for the given RuleId class. The lambda runs in
                 * place of any inline grammar action for the matching rule. Returns this
                 * parser for chaining.
                 */
                public %s withAction(Class<? extends RuleId> ruleIdClass,
                                     Function<SemanticValues, Object> action) {
                    lambdaActions.put(ruleIdClass.getSimpleName(), action);
                    return this;
                }

            """.formatted(className));
    }

    private void generateClassEnd(StringBuilder sb) {
        sb.append("}\n");
    }

    private String sanitize(String name) {
        return name.replaceAll("[^a-zA-Z0-9_]", "_");
    }

    private String escape(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private static final java.util.regex.Pattern POSITIONAL_VAR = java.util.regex.Pattern.compile("\\$(\\d+)");

    private String transformActionCode(String code) {
        // $0 stays as $0 (it's the matched text, handled separately)
        // Replace $N (N > 0) with values.get(N-1) using regex for unlimited support
        return POSITIONAL_VAR.matcher(code)
                             .replaceAll(match -> {
                                             int n = Integer.parseInt(match.group(1));
                                             return n == 0
                                                    ? "\\$0"
                                                    : "values.get(" + (n - 1) + ")";
                                         });
    }

    private String wrapActionCode(String code) {
        var trimmed = code.trim();
        if (trimmed.startsWith("return ")) {
            return "value = " + trimmed.substring(7);
        }
        if (!trimmed.contains(";") || (trimmed.endsWith(";") && !trimmed.contains("\n"))) {
            var expr = trimmed.endsWith(";")
                       ? trimmed.substring(0, trimmed.length() - 1)
                       : trimmed;
            return "value = " + expr + ";";
        }
        return trimmed.replace("return ", "value = ");
    }

    // === CST Generation Methods ===
    private void generateCstImports(StringBuilder sb) {
        sb.append("""
            import org.pragmatica.lang.Cause;
            import org.pragmatica.lang.Option;
            import org.pragmatica.lang.Result;

            import java.util.ArrayDeque;
            import java.util.ArrayList;
            import java.util.HashMap;
            import java.util.LinkedHashSet;
            import java.util.List;
            import java.util.Map;
            import java.util.function.Function;
            import java.util.function.Supplier;

            """);
    }

    private void generateCstClassStart(StringBuilder sb) {
        sb.append("/**\n");
        sb.append(" * Generated PEG parser with CST (Concrete Syntax Tree) output.\n");
        sb.append(" * This parser preserves all source information including trivia (whitespace/comments).\n");
        sb.append(" * Depends only on pragmatica-lite:core for Result type.\n");
        sb.append(" */\n");
        sb.append("public final class ")
          .append(className)
          .append(" {\n\n");
    }

    private void generateRuleIdInterface(StringBuilder sb) {
        var rules = grammar.rules();
        sb.append("    // === Rule ID Types ===\n\n");
        // Nested records are implicitly permitted - no need for explicit permits clause.
        // Standalone marker interface — generated parsers do not depend on peglib at runtime.
        sb.append("    public sealed interface RuleId {\n");
        sb.append("        int ordinal();\n");
        sb.append("        String name();\n\n");
        // Generate record for each rule
        int ordinal = 0;
        for (var rule : rules) {
            var ruleClassName = toClassName(rule.name());
            sb.append("        record ")
              .append(ruleClassName)
              .append("() implements RuleId {\n");
            sb.append("            public int ordinal() { return ")
              .append(ordinal++ )
              .append("; }\n");
            sb.append("            public String name() { return \"")
              .append(rule.name())
              .append("\"; }\n");
            sb.append("        }\n");
        }
        // Generate built-in types for anonymous terminals (prefixed to avoid collision with grammar rules)
        sb.append("        // Built-in types for anonymous terminals\n");
        sb.append("        record PegLiteral() implements RuleId {\n");
        sb.append("            public int ordinal() { return -1; }\n");
        sb.append("            public String name() { return \"literal\"; }\n");
        sb.append("        }\n");
        sb.append("        record PegCharClass() implements RuleId {\n");
        sb.append("            public int ordinal() { return -2; }\n");
        sb.append("            public String name() { return \"char\"; }\n");
        sb.append("        }\n");
        sb.append("        record PegAny() implements RuleId {\n");
        sb.append("            public int ordinal() { return -3; }\n");
        sb.append("            public String name() { return \"any\"; }\n");
        sb.append("        }\n");
        sb.append("        record PegToken() implements RuleId {\n");
        sb.append("            public int ordinal() { return -4; }\n");
        sb.append("            public String name() { return \"token\"; }\n");
        sb.append("        }\n");
        sb.append("    }\n\n");
        // Generate singleton instances
        sb.append("    // Rule ID singleton instances\n");
        for (var rule : rules) {
            var ruleClassName = toClassName(rule.name());
            var constName = toConstantName(rule.name());
            sb.append("    private static final RuleId.")
              .append(ruleClassName)
              .append(" ")
              .append(constName)
              .append(" = new RuleId.")
              .append(ruleClassName)
              .append("();\n");
        }
        // Built-in singletons
        sb.append("    private static final RuleId.PegLiteral RULE_PEG_LITERAL = new RuleId.PegLiteral();\n");
        sb.append("    private static final RuleId.PegCharClass RULE_PEG_CHAR_CLASS = new RuleId.PegCharClass();\n");
        sb.append("    private static final RuleId.PegAny RULE_PEG_ANY = new RuleId.PegAny();\n");
        sb.append("    private static final RuleId.PegToken RULE_PEG_TOKEN = new RuleId.PegToken();\n");
        // Candidate #4: ASCII single-char String pool — eliminates String.valueOf(c) alloc
        // per match in matchCharClassCst / matchAnyCst on the hot path. ~3 KB total at class load.
        sb.append("    private static final String[] ASCII_CHAR_STRINGS = new String[128];\n");
        sb.append("    static {\n");
        sb.append("        for (int __i = 0; __i < 128; __i++) {\n");
        sb.append("            ASCII_CHAR_STRINGS[__i] = String.valueOf((char) __i);\n");
        sb.append("        }\n");
        sb.append("    }\n");
        sb.append("\n");
    }

    private String toClassName(String ruleName) {
        // Convert rule name to valid Java class name (PascalCase)
        if (ruleName.startsWith("%")) {
            ruleName = ruleName.substring(1);
        }
        // Handle special characters
        var sb = new StringBuilder();
        boolean capitalizeNext = true;
        for (char c : ruleName.toCharArray()) {
            if (Character.isLetterOrDigit(c)) {
                sb.append(capitalizeNext
                          ? Character.toUpperCase(c)
                          : c);
                capitalizeNext = false;
            }else {
                capitalizeNext = true;
            }
        }
        return sb.toString();
    }

    private String toConstantName(String ruleName) {
        // Convert rule name to constant name (UPPER_SNAKE_CASE)
        if (ruleName.startsWith("%")) {
            ruleName = ruleName.substring(1);
        }
        var sb = new StringBuilder("RULE_");
        for (char c : ruleName.toCharArray()) {
            if (Character.isUpperCase(c) && sb.length() > 5) {
                sb.append('_');
            }
            if (Character.isLetterOrDigit(c)) {
                sb.append(Character.toUpperCase(c));
            }else {
                sb.append('_');
            }
        }
        return sb.toString();
    }

    private void generateCstTypes(StringBuilder sb) {
        sb.append("""
                // === CST Types ===

                public record SourceLocation(int line, int column, int offset) {
                    public static final SourceLocation START = new SourceLocation(1, 1, 0);
                    public static SourceLocation sourceLocation(int line, int column, int offset) {
                        return new SourceLocation(line, column, offset);
                    }
                    @Override public String toString() { return line + ":" + column; }
                }

                public record SourceSpan(int startLine, int startColumn, int startOffset,
                                         int endLine, int endColumn, int endOffset) {
                    public static SourceSpan sourceSpan(SourceLocation start, SourceLocation end) {
                        return new SourceSpan(start.line(), start.column(), start.offset(),
                                              end.line(), end.column(), end.offset());
                    }
                    public static SourceSpan sourceSpan(SourceLocation location) {
                        return sourceSpan(location, location);
                    }
                    public SourceLocation start() {
                        return SourceLocation.sourceLocation(startLine, startColumn, startOffset);
                    }
                    public SourceLocation end() {
                        return SourceLocation.sourceLocation(endLine, endColumn, endOffset);
                    }
                    public int length() { return endOffset - startOffset; }
                    public String extract(String source) { return source.substring(startOffset, endOffset); }
                    public SourceSpan merge(SourceSpan other) {
                        int nsl, nsc, nso, nel, nec, neo;
                        if (startOffset <= other.startOffset) {
                            nsl = startLine; nsc = startColumn; nso = startOffset;
                        } else {
                            nsl = other.startLine; nsc = other.startColumn; nso = other.startOffset;
                        }
                        if (endOffset >= other.endOffset) {
                            nel = endLine; nec = endColumn; neo = endOffset;
                        } else {
                            nel = other.endLine; nec = other.endColumn; neo = other.endOffset;
                        }
                        return new SourceSpan(nsl, nsc, nso, nel, nec, neo);
                    }
                    @Override public String toString() {
                        return startLine + ":" + startColumn + "-" + endLine + ":" + endColumn;
                    }
                }

                public sealed interface Trivia {
                    SourceSpan span();
                    String text();
                    record Whitespace(SourceSpan span, String text) implements Trivia {}
                    record LineComment(SourceSpan span, String text) implements Trivia {}
                    record BlockComment(SourceSpan span, String text) implements Trivia {}
                }

                /**
                 * v0.5.0 Phase 1.2 (Lever A): per-parse stable-ID source for CST nodes.
                 * IDs are unique within a parse-session lineage and excluded from
                 * structural equality (see CstNode equals/hashCode).
                 */
                public sealed interface IdGenerator permits IdGenerator.PerSessionCounter {
                    long next();

                    final class PerSessionCounter implements IdGenerator {
                        private long next = 0L;
                        @Override public long next() { return next++; }
                    }
                }

                public sealed interface CstNode {
                    long id();
                    SourceSpan span();
                    RuleId rule();
                    List<Trivia> leadingTrivia();
                    List<Trivia> trailingTrivia();

                    record Terminal(long id, SourceSpan span, RuleId rule, String text,
                                    List<Trivia> leadingTrivia, List<Trivia> trailingTrivia) implements CstNode {
                        @Override
                        public boolean equals(Object other) {
                            return other instanceof Terminal that
                                && java.util.Objects.equals(span, that.span)
                                && java.util.Objects.equals(rule, that.rule)
                                && java.util.Objects.equals(text, that.text)
                                && java.util.Objects.equals(leadingTrivia, that.leadingTrivia)
                                && java.util.Objects.equals(trailingTrivia, that.trailingTrivia);
                        }
                        @Override
                        public int hashCode() {
                            return java.util.Objects.hash(Terminal.class, span, rule, text, leadingTrivia, trailingTrivia);
                        }
                    }

                    record NonTerminal(long id, SourceSpan span, RuleId rule, List<CstNode> children,
                                       List<Trivia> leadingTrivia, List<Trivia> trailingTrivia) implements CstNode {
                        @Override
                        public boolean equals(Object other) {
                            return other instanceof NonTerminal that
                                && java.util.Objects.equals(span, that.span)
                                && java.util.Objects.equals(rule, that.rule)
                                && java.util.Objects.equals(children, that.children)
                                && java.util.Objects.equals(leadingTrivia, that.leadingTrivia)
                                && java.util.Objects.equals(trailingTrivia, that.trailingTrivia);
                        }
                        @Override
                        public int hashCode() {
                            return java.util.Objects.hash(NonTerminal.class, span, rule, children, leadingTrivia, trailingTrivia);
                        }
                    }

                    record Token(long id, SourceSpan span, RuleId rule, String text,
                                 List<Trivia> leadingTrivia, List<Trivia> trailingTrivia) implements CstNode {
                        @Override
                        public boolean equals(Object other) {
                            return other instanceof Token that
                                && java.util.Objects.equals(span, that.span)
                                && java.util.Objects.equals(rule, that.rule)
                                && java.util.Objects.equals(text, that.text)
                                && java.util.Objects.equals(leadingTrivia, that.leadingTrivia)
                                && java.util.Objects.equals(trailingTrivia, that.trailingTrivia);
                        }
                        @Override
                        public int hashCode() {
                            return java.util.Objects.hash(Token.class, span, rule, text, leadingTrivia, trailingTrivia);
                        }
                    }
            """);
        // Only add Error node type for ADVANCED mode (used in error recovery)
        if (errorReporting == ErrorReporting.ADVANCED) {
            sb.append("""
                        record Error(long id, SourceSpan span, String skippedText, String expected,
                                     List<Trivia> leadingTrivia, List<Trivia> trailingTrivia) implements CstNode {
                            @Override public RuleId rule() { return null; }
                            @Override
                            public boolean equals(Object other) {
                                return other instanceof Error that
                                    && java.util.Objects.equals(span, that.span)
                                    && java.util.Objects.equals(skippedText, that.skippedText)
                                    && java.util.Objects.equals(expected, that.expected)
                                    && java.util.Objects.equals(leadingTrivia, that.leadingTrivia)
                                    && java.util.Objects.equals(trailingTrivia, that.trailingTrivia);
                            }
                            @Override
                            public int hashCode() {
                                return java.util.Objects.hash(Error.class, span, skippedText, expected, leadingTrivia, trailingTrivia);
                            }
                        }
                """);
        }
        sb.append("""
                }

                public sealed interface AstNode {
                    SourceSpan span();
                    String rule();

                    record Terminal(SourceSpan span, String rule, String text) implements AstNode {}
                    record NonTerminal(SourceSpan span, String rule, List<AstNode> children) implements AstNode {}
                }

                public record ParseError(SourceLocation location, String reason) implements Cause {
                    @Override
                    public String message() {
                        return reason + " at " + location;
                    }
                }

            """);
        if (errorReporting == ErrorReporting.ADVANCED) {
            generateAdvancedDiagnosticTypes(sb);
        }
    }

    private void generateAdvancedDiagnosticTypes(StringBuilder sb) {
        sb.append("""
                // === Advanced Diagnostic Types ===

                public enum Severity {
                    ERROR("error"),
                    WARNING("warning"),
                    INFO("info"),
                    HINT("hint");

                    private final String display;

                    Severity(String display) {
                        this.display = display;
                    }

                    public String display() {
                        return display;
                    }
                }

                public record DiagnosticLabel(SourceSpan span, String message, boolean primary) {
                    public static DiagnosticLabel primary(SourceSpan span, String message) {
                        return new DiagnosticLabel(span, message, true);
                    }
                    public static DiagnosticLabel secondary(SourceSpan span, String message) {
                        return new DiagnosticLabel(span, message, false);
                    }
                }

                public record Diagnostic(
                    Severity severity,
                    String code,
                    String message,
                    SourceSpan span,
                    List<DiagnosticLabel> labels,
                    List<String> notes,
                    String tag
                ) {
                    public static Diagnostic error(String message, SourceSpan span) {
                        return new Diagnostic(Severity.ERROR, null, message, span, List.of(), List.of(), null);
                    }

                    public static Diagnostic error(String code, String message, SourceSpan span) {
                        return new Diagnostic(Severity.ERROR, code, message, span, List.of(), List.of(), null);
                    }

                    public static Diagnostic warning(String message, SourceSpan span) {
                        return new Diagnostic(Severity.WARNING, null, message, span, List.of(), List.of(), null);
                    }

                    public Diagnostic withLabel(String labelMessage) {
                        var newLabels = new ArrayList<>(labels);
                        newLabels.add(DiagnosticLabel.primary(span, labelMessage));
                        return new Diagnostic(severity, code, message, span, List.copyOf(newLabels), notes, tag);
                    }

                    public Diagnostic withSecondaryLabel(SourceSpan labelSpan, String labelMessage) {
                        var newLabels = new ArrayList<>(labels);
                        newLabels.add(DiagnosticLabel.secondary(labelSpan, labelMessage));
                        return new Diagnostic(severity, code, message, span, List.copyOf(newLabels), notes, tag);
                    }

                    public Diagnostic withNote(String note) {
                        var newNotes = new ArrayList<>(notes);
                        newNotes.add(note);
                        return new Diagnostic(severity, code, message, span, labels, List.copyOf(newNotes), tag);
                    }

                    public Diagnostic withHelp(String help) {
                        return withNote("help: " + help);
                    }

                    public Diagnostic withTag(String newTag) {
                        return new Diagnostic(severity, code, message, span, labels, notes, newTag);
                    }

                    public String format(String source, String filename) {
                        var sb = new StringBuilder();
                        var lines = source.split("\\n", -1);

                        // Header: error[E0001]: message
                        sb.append(severity.display());
                        if (code != null) {
                            sb.append("[").append(code).append("]");
                        }
                        sb.append(": ").append(message).append("\\n");

                        // Location: --> filename:line:column
                        sb.append("  --> ");
                        if (filename != null) {
                            sb.append(filename).append(":");
                        }
                        sb.append(span.startLine()).append(":").append(span.startColumn()).append("\\n");

                        // Find all lines we need to display
                        int minLine = span.startLine();
                        int maxLine = span.endLine();
                        for (var label : labels) {
                            minLine = Math.min(minLine, label.span().startLine());
                            maxLine = Math.max(maxLine, label.span().endLine());
                        }

                        // Calculate gutter width
                        int gutterWidth = String.valueOf(maxLine).length();

                        // Empty line before source
                        sb.append(" ".repeat(gutterWidth + 1)).append("|\\n");

                        // Display source lines with labels
                        for (int lineNum = minLine; lineNum <= maxLine; lineNum++) {
                            if (lineNum < 1 || lineNum > lines.length) continue;

                            String lineContent = lines[lineNum - 1];
                            String lineNumStr = String.format("%" + gutterWidth + "d", lineNum);

                            // Source line
                            sb.append(lineNumStr).append(" | ").append(lineContent).append("\\n");

                            // Underline labels on this line
                            var lineLabels = getLabelsOnLine(lineNum);
                            if (!lineLabels.isEmpty()) {
                                sb.append(" ".repeat(gutterWidth)).append(" | ");
                                sb.append(formatUnderlines(lineNum, lineContent, lineLabels));
                                sb.append("\\n");
                            }
                        }

                        // Empty line after source
                        sb.append(" ".repeat(gutterWidth + 1)).append("|\\n");

                        // Notes
                        for (var note : notes) {
                            sb.append(" ".repeat(gutterWidth + 1)).append("= ").append(note).append("\\n");
                        }

                        return sb.toString();
                    }

                    private List<DiagnosticLabel> getLabelsOnLine(int lineNum) {
                        var result = new ArrayList<DiagnosticLabel>();
                        if (span.startLine() <= lineNum && span.endLine() >= lineNum) {
                            if (labels.isEmpty()) {
                                result.add(DiagnosticLabel.primary(span, ""));
                            }
                        }
                        for (var label : labels) {
                            if (label.span().startLine() <= lineNum && label.span().endLine() >= lineNum) {
                                result.add(label);
                            }
                        }
                        return result;
                    }

                    private String formatUnderlines(int lineNum, String lineContent, List<DiagnosticLabel> lineLabels) {
                        var sb = new StringBuilder();
                        int currentCol = 1;

                        var sorted = lineLabels.stream()
                            .sorted((a, b) -> Integer.compare(a.span().startColumn(), b.span().startColumn()))
                            .toList();

                        for (var label : sorted) {
                            int startCol = label.span().startLine() == lineNum ? label.span().startColumn() : 1;
                            int endCol = label.span().endLine() == lineNum
                                ? label.span().endColumn()
                                : lineContent.length() + 1;

                            while (currentCol < startCol) {
                                sb.append(" ");
                                currentCol++;
                            }

                            char underlineChar = label.primary() ? '^' : '-';
                            int underlineLen = Math.max(1, endCol - startCol);
                            sb.append(String.valueOf(underlineChar).repeat(underlineLen));
                            currentCol += underlineLen;

                            if (!label.message().isEmpty()) {
                                sb.append(" ").append(label.message());
                            }
                        }

                        return sb.toString();
                    }

                    public String formatSimple() {
                        return String.format("%s:%d:%d: %s: %s",
                            "input", span.startLine(), span.startColumn(), severity.display(), message);
                    }
                }

                public record ParseResultWithDiagnostics(
                    Option<CstNode> node,
                    List<Diagnostic> diagnostics,
                    String source
                ) {
                    public static ParseResultWithDiagnostics success(CstNode node, String source) {
                        return new ParseResultWithDiagnostics(Option.some(node), List.of(), source);
                    }

                    public static ParseResultWithDiagnostics withErrors(Option<CstNode> node, List<Diagnostic> diagnostics, String source) {
                        return new ParseResultWithDiagnostics(node, List.copyOf(diagnostics), source);
                    }

                    public boolean isSuccess() {
                        return node.isPresent() && diagnostics.isEmpty();
                    }

                    public boolean hasErrors() {
                        return !diagnostics.isEmpty();
                    }

                    public boolean hasNode() {
                        return node.isPresent();
                    }

                    public String formatDiagnostics(String filename) {
                        if (diagnostics.isEmpty()) {
                            return "";
                        }
                        var sb = new StringBuilder();
                        for (var diag : diagnostics) {
                            sb.append(diag.format(source, filename));
                            sb.append("\\n");
                        }
                        return sb.toString();
                    }

                    public String formatDiagnostics() {
                        return formatDiagnostics("input");
                    }

                    public int errorCount() {
                        return (int) diagnostics.stream()
                            .filter(d -> d.severity() == Severity.ERROR)
                            .count();
                    }

                    public int warningCount() {
                        return (int) diagnostics.stream()
                            .filter(d -> d.severity() == Severity.WARNING)
                            .count();
                    }
                }

            """);
    }

    private void generateCstParseContext(StringBuilder sb) {
        sb.append("""
                // === Parse Context ===

                private String input;
                private int pos;
                private int line;
                private int column;
                private Map<Long, CstParseResult> cache;
                // 0.2.9: in-flight growing seeds for direct left-recursive rules.
                // Keyed by the same (ruleId, position) encoding as cache. When an
                // entry is present, a Warth seed-and-grow loop is active for that
                // rule at that position and self-recursive calls should return the
                // current seed instead of re-entering the body.
                private Map<Long, CstParseResult> growingSeeds;
                private Map<String, String> captures;
                private int tokenBoundaryDepth;
                private boolean skippingWhitespace;
                private boolean packratEnabled = true;
            """);
        if (config.mutableParseResult()) {
            // A coverage extension: raw nullable for hot-path failure tracking.
            // trackFailure() runs on every failed match (millions of times per
            // parse on a large input); Option.some(loc)/Option.some(expected)
            // dominated the residual Option$Some samples after the spike.
            sb.append("""
                    private SourceLocation furthestFailure;   // raw nullable
                    private String furthestExpected;          // raw nullable
                """);
        }else {
            sb.append("""
                    private Option<SourceLocation> furthestFailure;
                    private Option<String> furthestExpected;
                """);
        }
        sb.append("""

                // Pending leading trivia — trivia captured between sibling
                // sequence elements that will attach to the following sibling's
                // leadingTrivia. Backtracking combinators save/restore snapshots.
                private final ArrayList<Trivia> pendingLeadingTrivia = new ArrayList<>();

                // v0.5.0 Phase 1.2 (Lever A): per-parse stable-ID allocator.
                // Reset on every init() so each parse session has its own
                // monotonically increasing ID lineage starting from 0.
                private IdGenerator idGen = new IdGenerator.PerSessionCounter();

                /**
                 * Enable or disable packrat memoization.
                 * Disabling may reduce memory usage for large inputs.
                 */
                public void setPackratEnabled(boolean enabled) {
                    this.packratEnabled = enabled;
                }
            """);
        if (errorReporting == ErrorReporting.ADVANCED) {
            // 0.3.6: per-rule %recover override stack and pending-failure field.
            // Mirrors ParsingContext's stack/pending pair. Rules carrying a
            // %recover directive push their terminator onto the stack at body
            // entry and capture it into the pending field on body failure
            // before popping. skipToRecoveryPoint consults the pending field
            // first (the stack is unwound by the time recovery runs), then
            // the live stack, then the global default char-set.
            if (config.mutableParseResult()) {
                sb.append("""
                        private final ArrayDeque<String> recoveryOverrideStack = new ArrayDeque<>();
                        private String pendingFailureRecoveryOverride;   // raw nullable

                        private void pushRecoveryOverride(String terminator) {
                            recoveryOverrideStack.push(terminator);
                        }

                        private void popRecoveryOverride() {
                            if (!recoveryOverrideStack.isEmpty()) {
                                recoveryOverrideStack.pop();
                            }
                        }

                        private void recordFailureRecoveryOverride(String terminator) {
                            if (terminator != null && !terminator.isEmpty() && pendingFailureRecoveryOverride == null) {
                                pendingFailureRecoveryOverride = terminator;
                            }
                        }

                        private void clearPendingRecoveryOverride() {
                            pendingFailureRecoveryOverride = null;
                        }

                        private boolean matchesOverrideAt(String term) {
                            if (remaining() < term.length()) return false;
                            for (int i = 0; i < term.length(); i++) {
                                if (peek(i) != term.charAt(i)) return false;
                            }
                            return true;
                        }

                    """);
            }else {
                sb.append("""
                        private final ArrayDeque<String> recoveryOverrideStack = new ArrayDeque<>();
                        private Option<String> pendingFailureRecoveryOverride = Option.none();

                        private void pushRecoveryOverride(String terminator) {
                            recoveryOverrideStack.push(terminator);
                        }

                        private void popRecoveryOverride() {
                            if (!recoveryOverrideStack.isEmpty()) {
                                recoveryOverrideStack.pop();
                            }
                        }

                        private void recordFailureRecoveryOverride(String terminator) {
                            if (terminator != null && !terminator.isEmpty() && pendingFailureRecoveryOverride.isEmpty()) {
                                pendingFailureRecoveryOverride = Option.some(terminator);
                            }
                        }

                        private void clearPendingRecoveryOverride() {
                            pendingFailureRecoveryOverride = Option.none();
                        }

                        private boolean matchesOverrideAt(String term) {
                            if (remaining() < term.length()) return false;
                            for (int i = 0; i < term.length(); i++) {
                                if (peek(i) != term.charAt(i)) return false;
                            }
                            return true;
                        }

                    """);
            }
        }
        if (errorReporting == ErrorReporting.ADVANCED) {
            sb.append("""
                    private List<Diagnostic> diagnostics;
                """);
        }
        if (config.literalFailureCache()) {
            sb.append("""
                    private final Map<String, CstParseResult> literalFailureCache = new HashMap<>();
                """);
        }
        if (config.charClassFailureCache()) {
            sb.append("""
                    private final Map<String, CstParseResult> charClassFailureCache = new HashMap<>();
                """);
        }
        sb.append("""

                private void init(String input) {
                    this.input = input;
                    this.pos = 0;
                    this.line = 1;
                    this.column = 1;
                    this.cache = packratEnabled ? new HashMap<>() : null;
                    this.growingSeeds = new HashMap<>();
                    this.captures = new HashMap<>();
                    this.tokenBoundaryDepth = 0;
            """);
        if (config.mutableParseResult()) {
            sb.append("""
                        this.furthestFailure = null;
                        this.furthestExpected = null;
                """);
        }else {
            sb.append("""
                        this.furthestFailure = Option.none();
                        this.furthestExpected = Option.none();
                """);
        }
        sb.append("""
                    this.pendingLeadingTrivia.clear();
                    this.idGen = new IdGenerator.PerSessionCounter();
            """);
        if (errorReporting == ErrorReporting.ADVANCED) {
            if (config.mutableParseResult()) {
                sb.append("""
                            this.diagnostics = new ArrayList<>();
                            this.recoveryOverrideStack.clear();
                            this.pendingFailureRecoveryOverride = null;
                    """);
            }else {
                sb.append("""
                            this.diagnostics = new ArrayList<>();
                            this.recoveryOverrideStack.clear();
                            this.pendingFailureRecoveryOverride = Option.none();
                    """);
            }
        }
        sb.append("""
                }

                private SourceLocation location() {
                    return SourceLocation.sourceLocation(line, column, pos);
                }

                private boolean isAtEnd() {
                    return pos >= input.length();
                }

                private char peek() {
                    return input.charAt(pos);
                }

                private char peek(int offset) {
                    return input.charAt(pos + offset);
                }

                private char advance() {
                    char c = input.charAt(pos++);
                    if (c == '\\n') {
                        line++;
                        column = 1;
                    } else {
                        column++;
                    }
                    return c;
                }

                private int remaining() {
                    return input.length() - pos;
                }

                private String substring(int start, int end) {
                    return input.substring(start, end);
                }

                private long cacheKey(int ruleId, int position) {
                    return ((long) ruleId << 32) | position;
                }

                private void restoreLocation(SourceLocation loc) {
                    this.pos = loc.offset();
                    this.line = loc.line();
                    this.column = loc.column();
                }

                // v0.5.0 phase 1.7 (D): raw-int location restore. Skips
                // SourceLocation allocation in the hot path; called from emission
                // sites that capture (pos,line,column) as int locals at entry.
                private void restoreLocationRaw(int pos, int line, int column) {
                    this.pos = pos;
                    this.line = line;
                    this.column = column;
                }

            """);
        if (config.fastTrackFailure()) {
            if (config.mutableParseResult()) {
                // A coverage extension: raw-nullable trackFailure — no Option boxing
                // on the hot path. Equivalent semantics to the Option version.
                sb.append("""
                        private void trackFailure(String expected) {
                            if (furthestFailure != null) {
                                int furthestOffset = furthestFailure.offset();
                                if (pos < furthestOffset) return;
                                if (pos == furthestOffset) {
                                    String existing = furthestExpected != null ? furthestExpected : "";
                                    if (existing.contains(expected)) return;
                                    furthestExpected = existing.isEmpty() ? expected : existing + " or " + expected;
                                    return;
                                }
                            }
                            furthestFailure = location();
                            furthestExpected = expected;
                        }

                    """);
            }else {
                sb.append("""
                        private void trackFailure(String expected) {
                            if (!furthestFailure.isEmpty()) {
                                int furthestOffset = furthestFailure.unwrap().offset();
                                if (pos < furthestOffset) return;
                                if (pos == furthestOffset) {
                                    String existing = furthestExpected.or("");
                                    if (existing.contains(expected)) return;
                                    furthestExpected = Option.some(
                                        existing.isEmpty() ? expected : existing + " or " + expected);
                                    return;
                                }
                            }
                            furthestFailure = Option.some(location());
                            furthestExpected = Option.some(expected);
                        }

                    """);
            }
        }else {
            if (config.mutableParseResult()) {
                sb.append("""
                        private void trackFailure(String expected) {
                            var loc = location();
                            if (furthestFailure == null || loc.offset() > furthestFailure.offset()) {
                                furthestFailure = loc;
                                furthestExpected = expected;
                            } else if (loc.offset() == furthestFailure.offset() && !(furthestExpected != null ? furthestExpected : "").contains(expected)) {
                                String existing = furthestExpected != null ? furthestExpected : "";
                                furthestExpected = existing.isEmpty() ? expected : existing + " or " + expected;
                            }
                        }

                    """);
            }else {
                sb.append("""
                        private void trackFailure(String expected) {
                            var loc = location();
                            if (furthestFailure.isEmpty() || loc.offset() > furthestFailure.unwrap().offset()) {
                                furthestFailure = Option.some(loc);
                                furthestExpected = Option.some(expected);
                            } else if (loc.offset() == furthestFailure.unwrap().offset() && !furthestExpected.or("").contains(expected)) {
                                furthestExpected = Option.some(furthestExpected.or("").isEmpty() ? expected : furthestExpected.or("") + " or " + expected);
                            }
                        }

                    """);
            }
        }
        if (errorReporting == ErrorReporting.ADVANCED) {
            // 0.3.6: stack-aware recovery — per-rule %recover overrides are
            // pushed onto recoveryOverrideStack at rule body entry and
            // captured into pendingFailureRecoveryOverride before popping on
            // failure. skipToRecoveryPoint consults the pending field first
            // (the stack is unwound by the time recovery runs), then the
            // live stack, then the global default char-set. Mirrors
            // ParsingContext.skipToRecoveryPoint.
            if (config.mutableParseResult()) {
                sb.append("""
                        private SourceSpan skipToRecoveryPoint() {
                            var start = location();
                            String override = null;
                            if (pendingFailureRecoveryOverride != null) {
                                override = pendingFailureRecoveryOverride;
                                pendingFailureRecoveryOverride = null;
                            } else if (!recoveryOverrideStack.isEmpty()) {
                                override = recoveryOverrideStack.peek();
                            }
                            if (override != null && !override.isEmpty()) {
                                while (!isAtEnd() && !matchesOverrideAt(override)) {
                                    advance();
                                }
                                return SourceSpan.sourceSpan(start, location());
                            }
                            while (!isAtEnd()) {
                                char c = peek();
                                if (c == '\\n' || c == ';' || c == ',' || c == '}' || c == ')' || c == ']') {
                                    break;
                                }
                                advance();
                            }
                            return SourceSpan.sourceSpan(start, location());
                        }

                    """);
            }else {
                sb.append("""
                        private SourceSpan skipToRecoveryPoint() {
                            var start = location();
                            String override = null;
                            if (pendingFailureRecoveryOverride.isPresent()) {
                                override = pendingFailureRecoveryOverride.unwrap();
                                pendingFailureRecoveryOverride = Option.none();
                            } else if (!recoveryOverrideStack.isEmpty()) {
                                override = recoveryOverrideStack.peek();
                            }
                            if (override != null && !override.isEmpty()) {
                                while (!isAtEnd() && !matchesOverrideAt(override)) {
                                    advance();
                                }
                                return SourceSpan.sourceSpan(start, location());
                            }
                            while (!isAtEnd()) {
                                char c = peek();
                                if (c == '\\n' || c == ';' || c == ',' || c == '}' || c == ')' || c == ']') {
                                    break;
                                }
                                advance();
                            }
                            return SourceSpan.sourceSpan(start, location());
                        }

                    """);
            }
            sb.append("""
                    private void addDiagnostic(String message, SourceSpan span) {
                        diagnostics.add(Diagnostic.error(message, span).withTag("error.unexpected-input"));
                    }

                    private void addDiagnostic(String message, SourceSpan span, String label) {
                        diagnostics.add(Diagnostic.error(message, span).withLabel(label).withTag("error.unexpected-input"));
                    }

                """);
            // 0.2.4: suggestion vocabulary + Levenshtein helper. Emitted only
            // when %suggest designates at least one rule with literal
            // alternatives. When empty, nothing extra is emitted and the
            // generated parser's hot paths stay byte-identical to pre-0.2.4.
            var vocab = computeSuggestionVocabulary(grammar);
            if (!vocab.isEmpty()) {
                sb.append("        private static final String[] SUGGESTION_VOCAB = new String[] {\n");
                for (int i = 0; i < vocab.size(); i++ ) {
                    sb.append("            \"")
                      .append(escape(vocab.get(i)))
                      .append("\"");
                    if (i + 1 < vocab.size()) {
                        sb.append(",");
                    }
                    sb.append("\n");
                }
                sb.append("        };\n\n");
                sb.append("""
                        private String readIdentifierLikeAt(int offset) {
                            var sb = new StringBuilder();
                            int i = offset;
                            while (i < input.length()) {
                                char c = input.charAt(i);
                                if (Character.isLetterOrDigit(c) || c == '_') { sb.append(c); i++; }
                                else break;
                            }
                            return sb.toString();
                        }

                        private Option<String> findBestSuggestion(String word) {
                            String best = null;
                            int bestDist = Integer.MAX_VALUE;
                            for (var candidate : SUGGESTION_VOCAB) {
                                if (candidate.equals(word)) return Option.none();
                                int d = levenshtein(word, candidate);
                                if (d < bestDist) { bestDist = d; best = candidate; }
                            }
                            return (best != null && bestDist <= 2) ? Option.some(best) : Option.none();
                        }

                        private int levenshtein(String a, String b) {
                            int m = a.length(), n = b.length();
                            if (m == 0) return n;
                            if (n == 0) return m;
                            int[] prev = new int[n + 1];
                            int[] curr = new int[n + 1];
                            for (int j = 0; j <= n; j++) prev[j] = j;
                            for (int i = 1; i <= m; i++) {
                                curr[0] = i;
                                for (int j = 1; j <= n; j++) {
                                    int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                                    curr[j] = Math.min(Math.min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
                                }
                                var tmp = prev; prev = curr; curr = tmp;
                            }
                            return prev[n];
                        }

                    """);
            }
        }
    }

    private void generateCstParseMethods(StringBuilder sb) {
        var startRule = grammar.effectiveStartRule();
        var startRuleName = startRule.isPresent()
                            ? startRule.unwrap()
                                       .name()
                            : grammar.rules()
                                     .getFirst()
                                     .name();
        var sanitizedName = sanitize(startRuleName);
        var resultNodeUnwrapExpr = config.mutableParseResult()
                                   ? "result.node"
                                   : "result.node.unwrap()";
        // Read-site exprs for furthestFailure / furthestExpected. Under
        // mutableParseResult these are raw nullable; emit fallback expressions
        // that mirror Option.or(...) semantics without re-introducing boxing.
        // The legacy variant continues to use Option.or(...) calls.
        var furthestFailureOrLoc = config.mutableParseResult()
                                   ? "(furthestFailure != null ? furthestFailure : location())"
                                   : "furthestFailure.or(location())";
        // expectedExpr matches `furthestExpected.filter(s -> !s.isEmpty()).or(result.expected.or("valid input"))`.
        var expectedExpr = config.mutableParseResult()
                           ? "((furthestExpected != null && !furthestExpected.isEmpty()) ? furthestExpected : (result.expected != null ? result.expected : \"valid input\"))"
                           : "furthestExpected.filter(s -> !s.isEmpty()).or(result.expected.or(\"valid input\"))";
        var postPassParseLine = config.triviaPostPass()
                                ? "                    rootNode = TriviaPostPass.assignTrivia(input, rootNode, 0);\n"
                                : "";
        sb.append("""
                // === Public Parse Methods ===

                public Result<CstNode> parse(String input) {
                    init(input);
                    var result = parse_%s();
                    if (result.isFailure()) {
                        var errorLoc = %s;
                        var expected = %s;
                        return Result.failure(new ParseError(errorLoc, "expected " + expected));
                    }
                    var trailingTrivia = skipWhitespace(); // Capture trailing trivia
                    if (!isAtEnd()) {
                        var errorLoc = %s;
                        return Result.failure(new ParseError(errorLoc, "unexpected input"));
                    }
                    // Attach trailing trivia to root node
                    var rootNode = attachTrailingTrivia(%s, trailingTrivia);
                %s    return Result.success(rootNode);
                }

                /**
                 * Parse input and return AST (Abstract Syntax Tree).
                 * The AST is a simplified tree without trivia (whitespace/comments).
                 */
                public Result<AstNode> parseAst(String input) {
                    return parse(input).map(this::toAst);
                }

                private AstNode toAst(CstNode cst) {
                    return switch (cst) {
                        case CstNode.Terminal t -> new AstNode.Terminal(t.span(), t.rule().name(), t.text());
                        case CstNode.Token tok -> new AstNode.Terminal(tok.span(), tok.rule().name(), tok.text());
                        case CstNode.NonTerminal nt -> new AstNode.NonTerminal(
                            nt.span(), nt.rule().name(),
                            nt.children().stream().map(this::toAst).toList()
                        );
                        default -> new AstNode.Terminal(cst.span(), "error", "");
                    };
                }

            """.formatted(sanitizedName,
                          furthestFailureOrLoc,
                          expectedExpr,
                          furthestFailureOrLoc,
                          resultNodeUnwrapExpr,
                          postPassParseLine));
        if (errorReporting == ErrorReporting.ADVANCED) {
            var postPassDiagBranchLine = config.triviaPostPass()
                                         ? "                            rootNode = TriviaPostPass.assignTrivia(input, rootNode, 0);\n"
                                         : "";
            var postPassDiagSuccessLine = config.triviaPostPass()
                                          ? "                        rootNode = TriviaPostPass.assignTrivia(input, rootNode, 0);\n"
                                          : "";
            sb.append("""
                    /**
                     * Parse with advanced error recovery and Rust-style diagnostics.
                     * Returns a result containing the CST (with Error nodes for unparseable regions)
                     * and a list of diagnostics.
                     */
                    public ParseResultWithDiagnostics parseWithDiagnostics(String input) {
                        init(input);
                        var result = parse_%s();

                        if (result.isFailure()) {
                            // Record the failure and attempt recovery
                            var errorLoc = %s;
                            var errorSpan = SourceSpan.sourceSpan(errorLoc, errorLoc);
                            var expected = %s;
                            addDiagnostic("expected " + expected, errorSpan);

                            // Skip to recovery point and try to continue
                            var skippedSpan = skipToRecoveryPoint();
                            if (skippedSpan.length() > 0) {
                                var skippedText = skippedSpan.extract(input);
                                var errorNode = new CstNode.Error(idGen.next(), skippedSpan, skippedText, expected, List.of(), List.of());
                                return ParseResultWithDiagnostics.withErrors(Option.some(errorNode), diagnostics, input);
                            }
                            return ParseResultWithDiagnostics.withErrors(Option.none(), diagnostics, input);
                        }

                        var trailingTrivia = skipWhitespace();
                        if (!isAtEnd()) {
                            // Unexpected trailing input - use furthest failure position for error
                            var errorLoc = %s;
                            var skippedSpan = skipToRecoveryPoint();
                            var errorSpan = new SourceSpan(errorLoc.line(), errorLoc.column(), errorLoc.offset(),
                                                           skippedSpan.endLine(), skippedSpan.endColumn(), skippedSpan.endOffset());
                            addDiagnostic("unexpected input", errorSpan, "expected end of input");

                            // Attach error node to result
                            var rootNode = attachTrailingTrivia(%s, trailingTrivia);
                %s            return ParseResultWithDiagnostics.withErrors(Option.some(rootNode), diagnostics, input);
                        }

                        var rootNode = attachTrailingTrivia(%s, trailingTrivia);
                %s        if (diagnostics.isEmpty()) {
                            return ParseResultWithDiagnostics.success(rootNode, input);
                        }
                        return ParseResultWithDiagnostics.withErrors(Option.some(rootNode), diagnostics, input);
                    }

                """.formatted(sanitizedName,
                              furthestFailureOrLoc,
                              expectedExpr,
                              furthestFailureOrLoc,
                              resultNodeUnwrapExpr,
                              postPassDiagBranchLine,
                              resultNodeUnwrapExpr,
                              postPassDiagSuccessLine));
        }
        generateCstParseRuleAt(sb);
    }

    /**
     * 0.3.0 — emit {@code parseRuleAt(Class&lt;? extends RuleId&gt;, String, int)}
     * plus its supporting dispatch table for the generated CST parser. Each
     * grammar rule contributes a map entry keyed by its {@code RuleId} marker
     * record class; invocation seeks the parsing context to {@code offset}
     * (computing {@code line}/{@code column} from the prefix) and calls the
     * rule's generated {@code parse_<name>()} method.
     */
    private void generateCstParseRuleAt(StringBuilder sb) {
        var resultNodeUnwrapExpr = config.mutableParseResult()
                                   ? "result.node"
                                   : "result.node.unwrap()";
        var furthestFailureOrLoc = config.mutableParseResult()
                                   ? "(furthestFailure != null ? furthestFailure : location())"
                                   : "furthestFailure.or(location())";
        var expectedExpr = config.mutableParseResult()
                           ? "((furthestExpected != null && !furthestExpected.isEmpty()) ? furthestExpected : (result.expected != null ? result.expected : \"valid input\"))"
                           : "furthestExpected.filter(s -> !s.isEmpty()).or(result.expected.or(\"valid input\"))";
        var ruleAtBlock = new StringBuilder();
        ruleAtBlock.append("""
                // === PartialParse (0.3.0) ===

                /**
                 * Result of a partial parse via
                 * {@link #parseRuleAt(Class, String, int)}. Pairs the produced CST
                 * subtree with the absolute offset where parsing stopped.
                 */
                public record PartialParse(CstNode node, int endOffset) {}

                // Dispatch table: RuleId marker class -> rule method.
                private final Map<Class<? extends RuleId>,
                                  Supplier<CstParseResult>> ruleDispatch = buildRuleDispatch();

                private Map<Class<? extends RuleId>,
                            Supplier<CstParseResult>> buildRuleDispatch() {
                    var m = new HashMap<Class<? extends RuleId>,
                                        Supplier<CstParseResult>>();
                """);
        for (var rule : grammar.rules()) {
            var ruleClassName = toClassName(rule.name());
            var methodName = "parse_" + sanitize(rule.name());
            ruleAtBlock.append("        m.put(RuleId.")
                       .append(ruleClassName)
                       .append(".class, this::")
                       .append(methodName)
                       .append(");\n");
        }
        ruleAtBlock.append("""
                    return Map.copyOf(m);
                }

                /**
                 * Parse the rule identified by {@code ruleId} starting at {@code offset}
                 * in {@code input}. Unlike {@link #parse(String)}, the matched rule is
                 * not required to consume all remaining input — parsing stops when the
                 * rule itself finishes, and the returned {@link PartialParse#endOffset()}
                 * reports the absolute offset at which it stopped.
                 *
                 * @since 0.3.0
                 */
                public Result<PartialParse> parseRuleAt(
                        Class<? extends RuleId> ruleId,
                        String input, int offset) {
                    if (ruleId == null) {
                        return Result.failure(new ParseError(SourceLocation.START, "Rule id class is null"));
                    }
                    if (input == null) {
                        return Result.failure(new ParseError(SourceLocation.START, "Input is null"));
                    }
                    if (offset < 0 || offset > input.length()) {
                        return Result.failure(new ParseError(SourceLocation.START,
                                "Offset " + offset + " out of range [0, " + input.length() + "]"));
                    }
                    var supplier = ruleDispatch.get(ruleId);
                    if (supplier == null) {
                        return Result.failure(new ParseError(SourceLocation.START,
                                "Unknown rule for class " + ruleId.getSimpleName()));
                    }
                    init(input);
                    seekTo(offset);
                    var result = supplier.get();
                    if (result.isFailure()) {
                        var errorLoc = __FURTHEST_FAILURE_OR_LOC__;
                        var expected = __EXPECTED_EXPR__;
                        return Result.failure(new ParseError(errorLoc, "expected " + expected));
                    }
                    return Result.success(new PartialParse(__POST_PASS_WRAP__, pos));
                }

                /**
                 * Seek the parsing context to {@code offset}, computing
                 * {@code line}/{@code column} from the prefix. Shared by
                 * {@link #parseRuleAt(Class, String, int)}.
                 */
                private void seekTo(int offset) {
                    this.pos = 0;
                    this.line = 1;
                    this.column = 1;
                    for (int i = 0; i < offset; i++) {
                        if (input.charAt(i) == '\\n') {
                            line++;
                            column = 1;
                        } else {
                            column++;
                        }
                    }
                    this.pos = offset;
                }

            """);
        var postPassWrapExpr = config.triviaPostPass()
                               ? "TriviaPostPass.assignTrivia(input, " + resultNodeUnwrapExpr + ", offset)"
                               : resultNodeUnwrapExpr;
        sb.append(ruleAtBlock.toString()
                             .replace("__FURTHEST_FAILURE_OR_LOC__", furthestFailureOrLoc)
                             .replace("__EXPECTED_EXPR__", expectedExpr)
                             .replace("__POST_PASS_WRAP__", postPassWrapExpr)
                             .replace("__RESULT_NODE__", resultNodeUnwrapExpr));
    }

    private void generateCstRuleMethods(StringBuilder sb) {
        sb.append("    // === Rule Parsing Methods ===\n\n");
        int ruleId = 0;
        for (var rule : grammar.rules()) {
            // Phase G2: per-rule chunk-helper context. Reset before each rule so chunk ids
            // restart and the buffer list is empty; flush deferred helpers (declared at
            // class scope) after the rule's primary method emission completes.
            pendingChunkHelpers = new java.util.ArrayList<>();
            currentRuleSequenceCounter = 0;
            currentRuleChoiceCounter = 0;
            currentRuleName = sanitize(rule.name());
            currentRuleIdConst = toConstantName(rule.name());
            generateCstRuleMethod(sb, rule, ruleId++ );
            for (var helper : pendingChunkHelpers) {
                sb.append(helper);
            }
            pendingChunkHelpers = null;
            currentRuleName = null;
            currentRuleIdConst = null;
        }
    }

    private void generateCstRuleMethod(StringBuilder sb, Rule rule, int ruleId) {
        var methodName = "parse_" + sanitize(rule.name());
        var ruleName = rule.name();
        boolean inlineLocations = config.inlineLocations();
        // 0.2.9: direct left-recursive rules are emitted as a thin seed-and-grow
        // wrapper around the original body emission. The wrapper dispatches to
        // parse_<rule>_body() whose contents are the unchanged rule method.
        boolean leftRecursive = grammar.leftRecursiveRules()
                                       .contains(ruleName);
        if (leftRecursive) {
            emitCstLeftRecursiveWrapper(sb, rule, ruleId);
            generateCstRuleBodyMethod(sb, rule, ruleId);
            return;
        }
        // Phase G: when the rule expression is a top-level Choice, emit each
        // alternative as a private helper method (Leaf pattern). The rule body
        // becomes a thin dispatcher (potentially using FIRST-set switch). This
        // keeps every emitted method small enough for HotSpot's FreqInlineSize
        // (325 bytes) and lets C2 inline the per-alt helpers.
        if (rule.expression() instanceof Expression.Choice topChoice) {
            emitCstChoiceRule(sb, rule, ruleId, topChoice);
            return;
        }
        // §7.4 selective packrat: when the flag is on AND this rule's (unsanitized) name is in
        // the effective skip-set (caller-supplied or auto-detected), omit the cache lookup and
        // cache put within this rule method. The cache field itself and its use by other rules
        // are preserved. When either the flag is off or the rule is absent from the skip-set,
        // emission is byte-identical to pre-§7.4. Phase 1.8: skip-set may be auto-derived from
        // grammar shape — see {@link PackratAnalyzer}.
        boolean skipCache = effectivePackratSkipRules.contains(ruleName);
        sb.append("    private CstParseResult ")
          .append(methodName)
          .append("() {\n");
        if (inlineLocations) {
            // §7.3 option A: inline int locals at rule entry — no SourceLocation
            // allocation on the failure path. On success we still materialize a
            // SourceLocation for SourceSpan.sourceSpan(...), same as before.
            sb.append("        int startOffset = pos;\n");
            sb.append("        int startLine = line;\n");
            sb.append("        int startColumn = column;\n");
        }else {
            sb.append("        var startLoc = location();\n");
        }
        sb.append("        \n");
        if (!skipCache) {
            sb.append("        // Check cache at pre-whitespace position. Bug B fix:\n");
            sb.append("        // on a settled-success cache hit we must reproduce the first-parse\n");
            sb.append("        // trivia attribution — drain pending-leading, run skipWhitespace at\n");
            sb.append("        // the same pre-WS position, then jump pos to the cached body-end and\n");
            sb.append("        // attach the rebuilt leading trivia. Failure hits return as-is.\n");
            sb.append("        long key = cacheKey(")
              .append(ruleId)
              .append(", ")
              .append(inlineLocations
                      ? "startOffset"
                      : "startLoc.offset()")
              .append(");\n");
            sb.append("        if (cache != null) {\n");
            sb.append("            var cached = cache.get(key);\n");
            sb.append("            if (cached != null) {\n");
            sb.append("                if (cached.isSuccess()) {\n");
            sb.append("                    var hitCarriedLeading = ")
              .append(takePendingExpr())
              .append(";\n");
            sb.append("                    var hitLocalLeading = (tokenBoundaryDepth > 0) ? List.<Trivia>of() : skipWhitespace();\n");
            sb.append("                    var hitLeading = concatTrivia(hitCarriedLeading, hitLocalLeading);\n");
            sb.append("                    var hitEndLoc = ")
              .append(endLocationUnwrap("cached"))
              .append(";\n");
            sb.append("                    restoreLocation(hitEndLoc);\n");
            sb.append("                    var hitNode = attachLeadingTrivia(")
              .append(nodeUnwrap("cached"))
              .append(", hitLeading);\n");
            sb.append("                    return CstParseResult.success(hitNode, ")
              .append(textOrEmpty("cached"))
              .append(", hitEndLoc);\n");
            sb.append("                }\n");
            sb.append("                return cached;\n");
            sb.append("            }\n");
            sb.append("        }\n");
            sb.append("        \n");
        }
        sb.append("        // Skip leading whitespace and combine with carried pending-leading.\n");
        sb.append("        var carriedLeading = ")
          .append(takePendingExpr())
          .append(";\n");
        sb.append("        var localLeadingTrivia = (tokenBoundaryDepth > 0) ? List.<Trivia>of() : skipWhitespace();\n");
        sb.append("        var leadingTrivia = concatTrivia(carriedLeading, localLeadingTrivia);\n");
        var ruleIdConst = toConstantName(ruleName);
        sb.append("        var children = new ArrayList<CstNode>();\n");
        sb.append("        var __ruleName = ")
          .append(ruleIdConst)
          .append(";\n");
        sb.append("        \n");
        // 0.3.6: emit %recover override push before body parse (ADVANCED only).
        // Mirrors PegEngine.parseRule. Popped on the failure-recording line below.
        boolean emitRecoverHooks = errorReporting == ErrorReporting.ADVANCED && rule.hasRecover();
        if (emitRecoverHooks) {
            sb.append("        pushRecoveryOverride(\"")
              .append(escape(rule.recover()
                                 .unwrap()))
              .append("\");\n");
        }
        var counter = new int[]{0};
        // Mutable counter for unique variable names
        generateCstExpressionCode(sb, rule.expression(), "result", 2, true, counter, false);
        sb.append("        \n");
        if (emitRecoverHooks) {
            // 0.3.6: capture failure override BEFORE pop — skipToRecoveryPoint
            // runs after this method returns, by which point the live stack
            // has been unwound. Mirrors PegEngine.parseRule finally block.
            sb.append("        if (!result.isSuccess()) {\n");
            sb.append("            recordFailureRecoveryOverride(\"")
              .append(escape(rule.recover()
                                 .unwrap()))
              .append("\");\n");
            sb.append("        }\n");
            sb.append("        popRecoveryOverride();\n");
        }
        sb.append("        CstParseResult finalResult;\n");
        sb.append("        CstParseResult cacheableResult;\n");
        sb.append("        if (result.isSuccess()) {\n");
        if (emitRecoverHooks) {
            // 0.3.6: clear pending override on success so a backtracked
            // alternative's recorded override does not leak into a later
            // recovery cycle. Mirrors PegEngine.parseRule.
            sb.append("            clearPendingRecoveryOverride();\n");
        }
        sb.append("            var endLoc = location();\n");
        if (inlineLocations) {
            // Phase 1.7 (D2): build SourceSpan from inline ints + endLoc primitives —
            // skip the sourceSpan(SourceLocation, SourceLocation) factory which would
            // re-allocate via SourceLocation::new on the start side.
            sb.append("            var span = new SourceSpan(startLine, startColumn, startOffset, endLoc.line(), endLoc.column(), endLoc.offset());\n");
        }else {
            sb.append("            var span = SourceSpan.sourceSpan(startLoc, endLoc);\n");
        }
        // 0.3.5 (Bug C') rule-exit trailing-trivia attribution: capture trivia
        // accumulated in pending-leading by the body that wasn't claimed by any
        // child (typically inter-element skipWhitespace before a zero-width tail
        // element). Pos is NOT rewound — predicate combinators rely on pos being
        // past consumed whitespace. Mirrors PegEngine.parseRule.
        // Cleanup A: under triviaPostPass=true, takePendingLeading returns an
        // empty list, so the trailing-attach block is statically dead — elide.
        if (!config.triviaPostPass()) {
            sb.append("            var pendingAtExit = takePendingLeading();\n");
        }
        // Bug C fix: cache the empty-leading wrapped node so cache hits don't
        // preserve stale leading trivia and duplicate it on outer nodes. The
        // returned node carries the actual leadingTrivia for this call site.
        sb.append("            var cacheNode = wrapWithRuleName(result, children, span, ")
          .append(ruleIdConst)
          .append(", List.<Trivia>of());\n");
        // Match interpreter's wrapWithRuleName: replace the rule name on whatever node was produced
        sb.append("            var node = wrapWithRuleName(result, children, span, ")
          .append(ruleIdConst)
          .append(", leadingTrivia);\n");
        if (!config.triviaPostPass()) {
            sb.append("            if (!pendingAtExit.isEmpty()) {\n");
            sb.append("                cacheNode = attachTrailingToTail(cacheNode, pendingAtExit);\n");
            sb.append("                node = attachTrailingToTail(node, pendingAtExit);\n");
            sb.append("            }\n");
        }
        sb.append("            cacheableResult = CstParseResult.success(cacheNode, ")
          .append(textOrEmpty("result"))
          .append(", endLoc);\n");
        sb.append("            finalResult = CstParseResult.success(node, ")
          .append(textOrEmpty("result"))
          .append(", endLoc);\n");
        sb.append("        } else {\n");
        if (inlineLocations) {
            // Inline field restore — no SourceLocation allocation on failure path.
            sb.append("            this.pos = startOffset;\n");
            sb.append("            this.line = startLine;\n");
            sb.append("            this.column = startColumn;\n");
        }else {
            sb.append("            restoreLocation(startLoc);\n");
        }
        // Re-deposit the caller's pending-leading so enclosing backtracking
        // combinators can roll it back to their own snapshots. Cleanup A:
        // under flag-ON, the buffer is unused; skip the addAll entirely.
        if (!config.triviaPostPass()) {
            sb.append("            if (!carriedLeading.isEmpty()) pendingLeadingTrivia.addAll(carriedLeading);\n");
        }
        // Use custom error message if available
        if (rule.hasErrorMessage()) {
            sb.append("            finalResult = CstParseResult.failure(\"")
              .append(escape(rule.errorMessage()
                                 .unwrap()))
              .append("\");\n");
        }else if (rule.hasExpected()) {
            // 0.2.4: %expected — see AST path above.
            sb.append("            trackFailure(\"")
              .append(escape(rule.expected()
                                 .unwrap()))
              .append("\");\n");
            sb.append("            finalResult = CstParseResult.failure(\"")
              .append(escape(rule.expected()
                                 .unwrap()))
              .append("\");\n");
        }else {
            sb.append("            finalResult = result;\n");
        }
        sb.append("            cacheableResult = finalResult;\n");
        sb.append("        }\n");
        sb.append("        \n");
        if (!skipCache) {
            sb.append("        if (cache != null) cache.put(key, cacheableResult);\n");
        }
        sb.append("        return finalResult;\n");
        sb.append("    }\n\n");
    }

    /**
     * 0.2.9 — emit a Warth-style seed-and-grow wrapper for a directly left-recursive
     * rule. The wrapper handles cache lookup, seed-and-grow iteration, leading-trivia
     * attribution, and delegates the rule body parsing to {@code parse_<rule>_body()}.
     *
     * <p>Cut inside an LR rule freezes the current seed: when the body returns a
     * cut-failure during a growth iteration, the wrapper stops growing and uses the
     * last successful seed.
     */
    private void emitCstLeftRecursiveWrapper(StringBuilder sb, Rule rule, int ruleId) {
        var methodName = "parse_" + sanitize(rule.name());
        var bodyMethodName = methodName + "_body";
        var ruleName = rule.name();
        var ruleIdConst = toConstantName(ruleName);
        boolean inlineLocations = config.inlineLocations();
        sb.append("    // 0.2.9: Warth seed-and-grow wrapper for left-recursive rule ")
          .append(ruleName)
          .append("\n");
        sb.append("    private CstParseResult ")
          .append(methodName)
          .append("() {\n");
        if (inlineLocations) {
            sb.append("        int startOffset = pos;\n");
            sb.append("        int startLine = line;\n");
            sb.append("        int startColumn = column;\n");
        }else {
            sb.append("        var startLoc = location();\n");
        }
        sb.append("        long key = cacheKey(")
          .append(ruleId)
          .append(", ")
          .append(inlineLocations
                  ? "startOffset"
                  : "startLoc.offset()")
          .append(");\n");
        // Settled cache hit — Bug B fix: rebuild leading trivia at the hit site
        // (drain pending + skipWhitespace + reattach) so the returned node is
        // byte-for-byte equivalent to a fresh parse. Failure hits return as-is.
        sb.append("        if (cache != null) {\n");
        sb.append("            var cached = cache.get(key);\n");
        sb.append("            if (cached != null) {\n");
        sb.append("                if (cached.isSuccess()) {\n");
        sb.append("                    var hitCarriedLeading = ")
          .append(takePendingExpr())
          .append(";\n");
        sb.append("                    var hitLocalLeading = (tokenBoundaryDepth > 0) ? List.<Trivia>of() : skipWhitespace();\n");
        sb.append("                    var hitLeading = concatTrivia(hitCarriedLeading, hitLocalLeading);\n");
        sb.append("                    var hitEndLoc = ")
          .append(endLocationUnwrap("cached"))
          .append(";\n");
        sb.append("                    restoreLocation(hitEndLoc);\n");
        sb.append("                    var hitNode = attachLeadingTrivia(")
          .append(nodeUnwrap("cached"))
          .append(", hitLeading);\n");
        sb.append("                    return CstParseResult.success(hitNode, ")
          .append(textOrEmpty("cached"))
          .append(", hitEndLoc);\n");
        sb.append("                }\n");
        sb.append("                return cached;\n");
        sb.append("            }\n");
        sb.append("        }\n");
        // Growing-seed hit — self-recursive call observes the current seed.
        // Trivia is applied once at the outer settle path; do NOT rebuild here
        // for the in-progress seed, otherwise the self-reference would allocate
        // duplicate leading-trivia per growth iteration.
        sb.append("        var activeSeed = growingSeeds.get(key);\n");
        sb.append("        if (activeSeed != null) {\n");
        sb.append("            if (activeSeed.isSuccess()) restoreLocation(")
          .append(endLocationUnwrap("activeSeed"))
          .append(");\n");
        sb.append("            return activeSeed;\n");
        sb.append("        }\n");
        // Capture leading whitespace & carried pending at the outer level.
        sb.append("        var carriedLeading = ")
          .append(takePendingExpr())
          .append(";\n");
        sb.append("        var localLeadingTrivia = (tokenBoundaryDepth > 0) ? List.<Trivia>of() : skipWhitespace();\n");
        sb.append("        var leadingTrivia = concatTrivia(carriedLeading, localLeadingTrivia);\n");
        sb.append("        int bodyStartPos = pos;\n");
        sb.append("        int bodyStartLine = line;\n");
        sb.append("        int bodyStartColumn = column;\n");
        sb.append("        // Seed with Failure so the first recursive self-invocation sees a failing base.\n");
        sb.append("        CstParseResult lastSeed = CstParseResult.failure(\"left-recursion seed\");\n");
        sb.append("        growingSeeds.put(key, lastSeed);\n");
        sb.append("        boolean cutFired = false;\n");
        // 0.3.6: emit %recover override push for LR rule (ADVANCED only).
        // Mirrors PegEngine.parseRuleWithLeftRecursion. Pushed before the
        // grow loop, captured + popped after.
        boolean emitLrRecoverHooks = errorReporting == ErrorReporting.ADVANCED && rule.hasRecover();
        if (emitLrRecoverHooks) {
            sb.append("        pushRecoveryOverride(\"")
              .append(escape(rule.recover()
                                 .unwrap()))
              .append("\");\n");
        }
        sb.append("        while (true) {\n");
        sb.append("            pos = bodyStartPos;\n");
        sb.append("            line = bodyStartLine;\n");
        sb.append("            column = bodyStartColumn;\n");
        sb.append("            var iter = ")
          .append(bodyMethodName)
          .append("();\n");
        sb.append("            if (iter.isCutFailure()) { cutFired = true; break; }\n");
        sb.append("            if (iter.isFailure()) break;\n");
        sb.append("            int newEnd = ")
          .append(endLocationUnwrap("iter"))
          .append(".offset();\n");
        sb.append("            int prevEnd = lastSeed.isSuccess() ? ")
          .append(endLocationUnwrap("lastSeed"))
          .append(".offset() : bodyStartPos;\n");
        sb.append("            if (newEnd <= prevEnd) break;\n");
        sb.append("            lastSeed = iter;\n");
        sb.append("            growingSeeds.put(key, lastSeed);\n");
        sb.append("        }\n");
        if (emitLrRecoverHooks) {
            // 0.3.6: capture failure override BEFORE pop. Treat any non-Success
            // as a failure for recovery purposes (matches PegEngine.parseRuleWithLeftRecursion).
            sb.append("        if (!lastSeed.isSuccess()) {\n");
            sb.append("            recordFailureRecoveryOverride(\"")
              .append(escape(rule.recover()
                                 .unwrap()))
              .append("\");\n");
            sb.append("        }\n");
            sb.append("        popRecoveryOverride();\n");
        }
        sb.append("        growingSeeds.remove(key);\n");
        sb.append("        CstParseResult finalResult;\n");
        sb.append("        CstParseResult cacheableResult;\n");
        sb.append("        if (lastSeed.isSuccess()) {\n");
        if (emitLrRecoverHooks) {
            // 0.3.6: clear pending override on success path.
            sb.append("            clearPendingRecoveryOverride();\n");
        }
        sb.append("            restoreLocation(")
          .append(endLocationUnwrap("lastSeed"))
          .append(");\n");
        sb.append("            var node = attachLeadingTrivia(")
          .append(nodeUnwrap("lastSeed"))
          .append(", leadingTrivia);\n");
        sb.append("            finalResult = CstParseResult.success(node, ")
          .append(textOrEmpty("lastSeed"))
          .append(", ")
          .append(endLocationUnwrap("lastSeed"))
          .append(");\n");
        // Bug C fix: cache the empty-leading lastSeed (body emission attaches no
        // leading trivia). The wrapped node above is for return only. Bug C' is
        // intentionally NOT applied at the LR settle path: PegEngine's LR path
        // does not apply rule-exit trailing-trivia attribution either, and the
        // interpreter's parseRule (non-LR) fix alone is sufficient for full
        // round-trip equivalence on the corpus.
        sb.append("            cacheableResult = lastSeed;\n");
        sb.append("        } else {\n");
        if (inlineLocations) {
            sb.append("            this.pos = startOffset;\n");
            sb.append("            this.line = startLine;\n");
            sb.append("            this.column = startColumn;\n");
        }else {
            sb.append("            restoreLocation(startLoc);\n");
        }
        if (!config.triviaPostPass()) {
            sb.append("            if (!carriedLeading.isEmpty()) pendingLeadingTrivia.addAll(carriedLeading);\n");
        }
        if (rule.hasErrorMessage()) {
            sb.append("            finalResult = CstParseResult.failure(\"")
              .append(escape(rule.errorMessage()
                                 .unwrap()))
              .append("\");\n");
        }else if (rule.hasExpected()) {
            sb.append("            trackFailure(\"")
              .append(escape(rule.expected()
                                 .unwrap()))
              .append("\");\n");
            sb.append("            finalResult = CstParseResult.failure(\"")
              .append(escape(rule.expected()
                                 .unwrap()))
              .append("\");\n");
        }else if (rule.hasTag()) {
            sb.append("            finalResult = cutFired ? CstParseResult.cutFailure(\"cut inside left-recursive rule '")
              .append(escape(ruleName))
              .append("'\") : lastSeed;\n");
        }else {
            sb.append("            finalResult = cutFired ? CstParseResult.cutFailure(\"cut inside left-recursive rule '")
              .append(escape(ruleName))
              .append("'\") : lastSeed;\n");
        }
        sb.append("            cacheableResult = finalResult;\n");
        sb.append("        }\n");
        sb.append("        if (cache != null) cache.put(key, cacheableResult);\n");
        sb.append("        return finalResult;\n");
        sb.append("    }\n\n");
    }

    /**
     * 0.2.9 — emit the body helper for an LR rule. This is the equivalent of
     * {@link #generateCstRuleMethod} but:
     *   - uses the {@code _body} method name
     *   - skips cache lookup/store (handled by the wrapper)
     *   - skips leading-trivia capture (handled by the wrapper)
     *   - skips custom %expected / %error substitution (handled by the wrapper)
     *   - returns the raw body result so the wrapper can decide growth
     */
    private void generateCstRuleBodyMethod(StringBuilder sb, Rule rule, int ruleId) {
        var bodyMethodName = "parse_" + sanitize(rule.name()) + "_body";
        var ruleName = rule.name();
        var ruleIdConst = toConstantName(ruleName);
        sb.append("    private CstParseResult ")
          .append(bodyMethodName)
          .append("() {\n");
        // Phase 1.7 (D): inline int captures — no SourceLocation allocation on entry.
        sb.append("        int startOffset = pos;\n");
        sb.append("        int startLine = line;\n");
        sb.append("        int startColumn = column;\n");
        sb.append("        var children = new ArrayList<CstNode>();\n");
        sb.append("        var __ruleName = ")
          .append(ruleIdConst)
          .append(";\n");
        sb.append("        \n");
        var counter = new int[]{0};
        generateCstExpressionCode(sb, rule.expression(), "result", 2, true, counter, false);
        sb.append("        \n");
        sb.append("        if (result.isSuccess()) {\n");
        sb.append("            var endLoc = location();\n");
        sb.append("            var span = new SourceSpan(startLine, startColumn, startOffset, endLoc.line(), endLoc.column(), endLoc.offset());\n");
        sb.append("            var node = wrapWithRuleName(result, children, span, ")
          .append(ruleIdConst)
          .append(", List.<Trivia>of());\n");
        sb.append("            return CstParseResult.success(node, ")
          .append(textOrEmpty("result"))
          .append(", endLoc);\n");
        sb.append("        }\n");
        sb.append("        return result;\n");
        sb.append("    }\n\n");
    }

    /**
     * Phase G: emit a rule whose top-level expression is a {@link Expression.Choice}
     * with each alternative split into its own private helper method. The rule body
     * becomes a thin dispatcher; helpers carry all per-alt parsing logic. This
     * shrinks every emitted method below HotSpot's FreqInlineSize (325 bytes) and
     * lets C2 inline through the dispatcher.
     *
     * <p>Helper signature contract: each helper takes the ambient {@code children}
     * list (so adds/removes are visible to the caller's epilogue) plus the snapshot
     * state captured at choice entry (start pos/line/col, pendingLeading snapshot,
     * children-state). The helper restores children to the snapshot at entry,
     * runs the alternative's body, and on regular failure restores pos/line/col
     * + pendingLeading. On cut-failure it returns the cut-failure unchanged so the
     * dispatcher can convert it to a regular failure for sibling-skipping.
     *
     * <p>PEG semantics preserved: cut inside an alt converts to regular failure at
     * the choice level (matches inline emission). FIRST-set dispatch falls through
     * to the default tail when no dispatched alt succeeds.
     */
    private void emitCstChoiceRule(StringBuilder sb, Rule rule, int ruleId, Expression.Choice choice) {
        var methodName = "parse_" + sanitize(rule.name());
        var ruleName = rule.name();
        boolean inlineLocations = config.inlineLocations();
        boolean skipCache = effectivePackratSkipRules.contains(ruleName);
        var ruleIdConst = toConstantName(ruleName);
        // === Rule body (dispatcher) ===
        sb.append("    private CstParseResult ")
          .append(methodName)
          .append("() {\n");
        if (inlineLocations) {
            sb.append("        int startOffset = pos;\n");
            sb.append("        int startLine = line;\n");
            sb.append("        int startColumn = column;\n");
        }else {
            sb.append("        var startLoc = location();\n");
        }
        sb.append("        \n");
        if (!skipCache) {
            sb.append("        long key = cacheKey(")
              .append(ruleId)
              .append(", ")
              .append(inlineLocations
                      ? "startOffset"
                      : "startLoc.offset()")
              .append(");\n");
            sb.append("        if (cache != null) {\n");
            sb.append("            var cached = cache.get(key);\n");
            sb.append("            if (cached != null) {\n");
            sb.append("                if (cached.isSuccess()) {\n");
            sb.append("                    var hitCarriedLeading = ")
              .append(takePendingExpr())
              .append(";\n");
            sb.append("                    var hitLocalLeading = (tokenBoundaryDepth > 0) ? List.<Trivia>of() : skipWhitespace();\n");
            sb.append("                    var hitLeading = concatTrivia(hitCarriedLeading, hitLocalLeading);\n");
            sb.append("                    var hitEndLoc = ")
              .append(endLocationUnwrap("cached"))
              .append(";\n");
            sb.append("                    restoreLocation(hitEndLoc);\n");
            sb.append("                    var hitNode = attachLeadingTrivia(")
              .append(nodeUnwrap("cached"))
              .append(", hitLeading);\n");
            sb.append("                    return CstParseResult.success(hitNode, ")
              .append(textOrEmpty("cached"))
              .append(", hitEndLoc);\n");
            sb.append("                }\n");
            sb.append("                return cached;\n");
            sb.append("            }\n");
            sb.append("        }\n");
            sb.append("        \n");
        }
        sb.append("        var carriedLeading = ")
          .append(takePendingExpr())
          .append(";\n");
        sb.append("        var localLeadingTrivia = (tokenBoundaryDepth > 0) ? List.<Trivia>of() : skipWhitespace();\n");
        sb.append("        var leadingTrivia = concatTrivia(carriedLeading, localLeadingTrivia);\n");
        sb.append("        var children = new ArrayList<CstNode>();\n");
        sb.append("        var __ruleName = ")
          .append(ruleIdConst)
          .append(";\n");
        sb.append("        \n");
        boolean emitRecoverHooks = errorReporting == ErrorReporting.ADVANCED && rule.hasRecover();
        if (emitRecoverHooks) {
            sb.append("        pushRecoveryOverride(\"")
              .append(escape(rule.recover()
                                 .unwrap()))
              .append("\");\n");
        }
        // Choice-entry snapshot state (shared across helper calls).
        sb.append("        CstParseResult result = null;\n");
        sb.append("        int choiceStart0Pos = pos;\n");
        sb.append("        int choiceStart0Line = line;\n");
        sb.append("        int choiceStart0Column = column;\n");
        // Cleanup A: under triviaPostPass=true, savePendingLeading() returns 0
        // (sentinel) and restorePendingLeading is a no-op; we still emit the
        // declaration since helpers receive choicePending0 as a parameter, and
        // the JIT trivially elides the dead local.
        sb.append("        int choicePending0 = ")
          .append(savePendingExpr())
          .append(";\n");
        var childrenStateName = config.markResetChildren()
                                ? "childrenMark0"
                                : "savedChildren0";
        if (config.markResetChildren()) {
            sb.append("        int ")
              .append(childrenStateName)
              .append(" = children.size();\n");
        }else {
            sb.append("        var ")
              .append(childrenStateName)
              .append(" = new ArrayList<>(children);\n");
        }
        // Dispatcher: FIRST-set switch (when classifiable) or PEG-order chain.
        var classifiedF = config.choiceDispatch()
                          ? ChoiceDispatchAnalyzer.classify(choice, firstSets())
                          : java.util.Optional.<ChoiceDispatchAnalyzer.Classified>empty();
        if (classifiedF.isPresent()) {
            var c = classifiedF.get();
            var buckets = ChoiceDispatchAnalyzer.bucketsForClassified(c);
            emitChoiceRuleDispatcherF(sb, rule, buckets, c.defaults(), childrenStateName);
        }else {
            emitChoiceRuleDispatcherPeg(sb, rule, choice, childrenStateName);
        }
        // All-alts-failed epilogue: synthesize a "one of alternatives" failure if
        // no helper produced a result. This matches the inline emission's tail.
        sb.append("        if (result == null) {\n");
        if (config.markResetChildren()) {
            sb.append("            if (children.size() > ")
              .append(childrenStateName)
              .append(") {\n");
            sb.append("                children.subList(")
              .append(childrenStateName)
              .append(", children.size()).clear();\n");
            sb.append("            }\n");
        }else {
            sb.append("            children.clear();\n");
            sb.append("            children.addAll(")
              .append(childrenStateName)
              .append(");\n");
        }
        if (!config.triviaPostPass()) {
            sb.append("            restorePendingLeading(choicePending0);\n");
        }
        sb.append("            result = CstParseResult.failure(\"one of alternatives\");\n");
        sb.append("        }\n");
        sb.append("        \n");
        if (emitRecoverHooks) {
            sb.append("        if (!result.isSuccess()) {\n");
            sb.append("            recordFailureRecoveryOverride(\"")
              .append(escape(rule.recover()
                                 .unwrap()))
              .append("\");\n");
            sb.append("        }\n");
            sb.append("        popRecoveryOverride();\n");
        }
        sb.append("        CstParseResult finalResult;\n");
        sb.append("        CstParseResult cacheableResult;\n");
        sb.append("        if (result.isSuccess()) {\n");
        if (emitRecoverHooks) {
            sb.append("            clearPendingRecoveryOverride();\n");
        }
        sb.append("            var endLoc = location();\n");
        if (inlineLocations) {
            sb.append("            var span = new SourceSpan(startLine, startColumn, startOffset, endLoc.line(), endLoc.column(), endLoc.offset());\n");
        }else {
            sb.append("            var span = SourceSpan.sourceSpan(startLoc, endLoc);\n");
        }
        if (!config.triviaPostPass()) {
            sb.append("            var pendingAtExit = takePendingLeading();\n");
        }
        sb.append("            var cacheNode = wrapWithRuleName(result, children, span, ")
          .append(ruleIdConst)
          .append(", List.<Trivia>of());\n");
        sb.append("            var node = wrapWithRuleName(result, children, span, ")
          .append(ruleIdConst)
          .append(", leadingTrivia);\n");
        if (!config.triviaPostPass()) {
            sb.append("            if (!pendingAtExit.isEmpty()) {\n");
            sb.append("                cacheNode = attachTrailingToTail(cacheNode, pendingAtExit);\n");
            sb.append("                node = attachTrailingToTail(node, pendingAtExit);\n");
            sb.append("            }\n");
        }
        sb.append("            cacheableResult = CstParseResult.success(cacheNode, ")
          .append(textOrEmpty("result"))
          .append(", endLoc);\n");
        sb.append("            finalResult = CstParseResult.success(node, ")
          .append(textOrEmpty("result"))
          .append(", endLoc);\n");
        sb.append("        } else {\n");
        if (inlineLocations) {
            sb.append("            this.pos = startOffset;\n");
            sb.append("            this.line = startLine;\n");
            sb.append("            this.column = startColumn;\n");
        }else {
            sb.append("            restoreLocation(startLoc);\n");
        }
        if (!config.triviaPostPass()) {
            sb.append("            if (!carriedLeading.isEmpty()) pendingLeadingTrivia.addAll(carriedLeading);\n");
        }
        if (rule.hasErrorMessage()) {
            sb.append("            finalResult = CstParseResult.failure(\"")
              .append(escape(rule.errorMessage()
                                 .unwrap()))
              .append("\");\n");
        }else if (rule.hasExpected()) {
            sb.append("            trackFailure(\"")
              .append(escape(rule.expected()
                                 .unwrap()))
              .append("\");\n");
            sb.append("            finalResult = CstParseResult.failure(\"")
              .append(escape(rule.expected()
                                 .unwrap()))
              .append("\");\n");
        }else {
            sb.append("            finalResult = result;\n");
        }
        sb.append("            cacheableResult = finalResult;\n");
        sb.append("        }\n");
        sb.append("        \n");
        if (!skipCache) {
            sb.append("        if (cache != null) cache.put(key, cacheableResult);\n");
        }
        sb.append("        return finalResult;\n");
        sb.append("    }\n\n");
        // === Per-alternative helper methods ===
        for (int i = 0; i < choice.alternatives()
                                  .size(); i++ ) {
            emitCstChoiceAltHelper(sb,
                                   rule,
                                   choice.alternatives()
                                         .get(i),
                                   i);
        }
    }

    /**
     * Phase G: emit dispatcher body using FIRST-set classification. Each {@code case}
     * in the switch calls the helpers for the dispatched alternatives in PEG order;
     * after a case body, falls through to the defaults tail. The {@code default:}
     * branch runs the defaults tail directly.
     */
    private void emitChoiceRuleDispatcherF(StringBuilder sb,
                                           Rule rule,
                                           java.util.List<ChoiceDispatchAnalyzer.DispatchBucket> buckets,
                                           java.util.List<ChoiceDispatchAnalyzer.AltEntry> defaults,
                                           String childrenStateName) {
        sb.append("        if (pos < input.length()) {\n");
        sb.append("            char dispatchChar0 = input.charAt(pos);\n");
        sb.append("            switch (dispatchChar0) {\n");
        for (var bucket : buckets) {
            for (var c : bucket.chars()) {
                sb.append("                case ")
                  .append(literalChar(c))
                  .append(":\n");
            }
            sb.append("                {\n");
            emitHelperCallChain(sb, rule, bucket.alts(), childrenStateName, "                    ");
            sb.append("                    break;\n");
            sb.append("                }\n");
        }
        sb.append("            }\n");
        sb.append("        }\n");
        if (!defaults.isEmpty()) {
            sb.append("        if (result == null || (!result.isSuccess() && !result.isCutFailure())) {\n");
            // Restore pos for defaults tail (a dispatched alt may have advanced pos before failing).
            sb.append("            this.pos = choiceStart0Pos;\n");
            sb.append("            this.line = choiceStart0Line;\n");
            sb.append("            this.column = choiceStart0Column;\n");
            if (!config.triviaPostPass()) {
                sb.append("            restorePendingLeading(choicePending0);\n");
            }
            sb.append("            result = null;\n");
            emitHelperCallChain(sb, rule, defaults, childrenStateName, "            ");
            sb.append("        }\n");
        }
    }

    /**
     * Phase G: emit dispatcher body as a PEG-order chain of helper calls (no
     * FIRST-set classification). Each helper is tried in original grammar order.
     */
    private void emitChoiceRuleDispatcherPeg(StringBuilder sb,
                                             Rule rule,
                                             Expression.Choice choice,
                                             String childrenStateName) {
        var entries = new java.util.ArrayList<ChoiceDispatchAnalyzer.AltEntry>();
        for (int i = 0; i < choice.alternatives()
                                  .size(); i++ ) {
            entries.add(new ChoiceDispatchAnalyzer.AltEntry(i,
                                                            choice.alternatives()
                                                                  .get(i),
                                                            null));
        }
        emitHelperCallChain(sb, rule, entries, childrenStateName, "        ");
    }

    /**
     * Emit a chain of helper calls for the given alternatives. The first call
     * is unconditional; subsequent calls are guarded by {@code !isSuccess() && !isCutFailure()}.
     * After every call, cut-failure is converted to regular failure (siblings of THIS choice
     * are skipped via the guard, but enclosing choices see a regular failure).
     */
    private void emitHelperCallChain(StringBuilder sb,
                                     Rule rule,
                                     java.util.List<ChoiceDispatchAnalyzer.AltEntry> alts,
                                     String childrenStateName,
                                     String pad) {
        var methodBase = "parse_" + sanitize(rule.name()) + "_alt";
        for (int k = 0; k < alts.size(); k++ ) {
            var entry = alts.get(k);
            int origIndex = entry.originalIndex();
            if (k == 0) {
                sb.append(pad)
                  .append("result = ")
                  .append(methodBase)
                  .append(origIndex)
                  .append("(children, choiceStart0Pos, choiceStart0Line, choiceStart0Column, choicePending0, ")
                  .append(childrenStateName)
                  .append(");\n");
            }else {
                sb.append(pad)
                  .append("if (!result.isSuccess() && !result.isCutFailure()) {\n");
                sb.append(pad)
                  .append("    result = ")
                  .append(methodBase)
                  .append(origIndex)
                  .append("(children, choiceStart0Pos, choiceStart0Line, choiceStart0Column, choicePending0, ")
                  .append(childrenStateName)
                  .append(");\n");
                sb.append(pad)
                  .append("}\n");
            }
        }
        // Cut-failure conversion: cut commits to choice-failure; siblings won't be tried (guarded
        // above), but enclosing constructs see a regular failure.
        sb.append(pad)
          .append("if (result != null && result.isCutFailure()) result = result.asRegularFailure();\n");
    }

    /**
     * Emit a single per-alternative helper method. The helper takes {@code children}
     * (passed by reference from the caller's local list) plus the choice-entry
     * snapshot state. On entry it restores children to the snapshot (clearing any
     * residue from a previously-failed alt). It then runs the alternative's body
     * via {@link #generateCstExpressionCode}. On success it returns the result.
     * On regular failure it restores pos/line/col + pendingLeading and returns
     * the failure. On cut-failure it returns the cut-failure unchanged for the
     * dispatcher to convert.
     */
    private void emitCstChoiceAltHelper(StringBuilder sb, Rule rule, Expression alt, int origIndex) {
        var methodName = "parse_" + sanitize(rule.name()) + "_alt" + origIndex;
        var altVar = "alt0_" + origIndex;
        var ruleIdConst = toConstantName(rule.name());
        sb.append("    private CstParseResult ")
          .append(methodName)
          .append("(ArrayList<CstNode> children, int choiceStartPos, int choiceStartLine, int choiceStartColumn, int choicePending, ");
        if (config.markResetChildren()) {
            sb.append("int childrenState");
        }else {
            sb.append("ArrayList<CstNode> childrenState");
        }
        sb.append(") {\n");
        // Helper-local __ruleName: scoped to this method body so emission templates
        // that reference __ruleName (TokenBoundary, ZeroOrMore, OneOrMore, Repetition
        // wrap-as-NonTerminal sites) compile after extraction from the parent rule.
        sb.append("        var __ruleName = ")
          .append(ruleIdConst)
          .append(";\n");
        // Restore children to snapshot at entry (clears residue from previous failed alt).
        if (config.markResetChildren()) {
            sb.append("        if (children.size() > childrenState) {\n");
            sb.append("            children.subList(childrenState, children.size()).clear();\n");
            sb.append("        }\n");
        }else {
            sb.append("        children.clear();\n");
            sb.append("        children.addAll(childrenState);\n");
        }
        // Run alternative body. Counter is local to this helper (restarting at 0
        // is safe — variable names are scoped to this method).
        var counter = new int[]{0};
        // The helper-local result variable name must match what generateCstExpressionCode
        // emits for the alt expression. We use the standard pattern alt0_<origIndex>.
        generateCstExpressionCode(sb, alt, altVar, 2, true, counter, false);
        sb.append("        if (")
          .append(altVar)
          .append(".isSuccess()) {\n");
        sb.append("            return ")
          .append(altVar)
          .append(";\n");
        sb.append("        }\n");
        // Cut-failure: return as-is so dispatcher can convert.
        sb.append("        if (")
          .append(altVar)
          .append(".isCutFailure()) {\n");
        sb.append("            return ")
          .append(altVar)
          .append(";\n");
        sb.append("        }\n");
        // Regular failure: restore pos/line/col + pendingLeading; return failure.
        sb.append("        this.pos = choiceStartPos;\n");
        sb.append("        this.line = choiceStartLine;\n");
        sb.append("        this.column = choiceStartColumn;\n");
        if (!config.triviaPostPass()) {
            sb.append("        restorePendingLeading(choicePending);\n");
        }
        sb.append("        return ")
          .append(altVar)
          .append(";\n");
        sb.append("    }\n\n");
    }

    // ============================================================
    // Phase G2: Sequence chunking
    // ============================================================
    /**
     * Phase G2: Emit a CST {@link Expression.Sequence} body, splitting into
     * per-chunk helper methods when the sequence is large enough to benefit.
     *
     * <p>Chunking criteria (see {@link #shouldChunkSequence(Expression.Sequence)}):
     * <ul>
     *   <li>Element count exceeds {@link #SEQ_CHUNK_ELEMENT_THRESHOLD}, OR</li>
     *   <li>Estimated emitted-byte weight exceeds {@link #SEQ_CHUNK_BYTE_THRESHOLD}.</li>
     * </ul>
     *
     * <p>When chunking, the dispatcher emitted in-place captures sequence-entry
     * state (pos/line/column/pending/children-snapshot) and calls each chunk
     * helper in order. Each helper takes that state as parameters; on element
     * failure within the helper, the helper performs the same per-element
     * rollback as the inline emission and returns the failure result.
     *
     * <p>PEG semantics:
     * <ul>
     *   <li>Sequence atomicity — failure of any element rolls back to sequence
     *       entry. Each chunk's element-failure handler restores location +
     *       pending + children to the sequence-start snapshot.</li>
     *   <li>Cut propagation — once an element succeeds that is a {@code Cut},
     *       subsequent failures convert to cut-failure. Cut state is propagated
     *       across chunks <i>statically</i>: chunk N's emission knows whether
     *       any prior-chunk element was a cut, and within the chunk a runtime
     *       boolean tracks cuts that occur in-chunk. The chunk's failure-handler
     *       decision uses {@code priorChunkCut || localCut} per element.</li>
     * </ul>
     *
     * <p>When chunking is not triggered, this method emits the original byte-
     * identical inline body so the existing parity tests stay green.
     */
    private void emitCstSequenceMaybeChunked(StringBuilder sb,
                                             Expression.Sequence seq,
                                             String resultVar,
                                             int indent,
                                             boolean addToChildren,
                                             int[] counter,
                                             boolean inWhitespaceRule,
                                             int id) {
        if (pendingChunkHelpers != null && currentRuleName != null && shouldChunkSequence(seq)) {
            emitCstSequenceChunkedDispatcher(sb, seq, resultVar, indent, addToChildren, counter, inWhitespaceRule, id);
            return;
        }
        emitCstSequenceInline(sb, seq, resultVar, indent, addToChildren, counter, inWhitespaceRule, id);
    }

    /**
     * Phase G2: chunking heuristic. Triggered when either the element count
     * exceeds {@link #SEQ_CHUNK_ELEMENT_THRESHOLD} or the estimated emitted-byte
     * weight exceeds {@link #SEQ_CHUNK_BYTE_THRESHOLD}. The byte estimate is
     * a rough proxy summed via {@link #estimateExpressionWeight(Expression)};
     * it doesn't need to be exact — it's a pre-filter to keep small Sequences
     * byte-identical to the pre-G2 emission.
     */
    private static boolean shouldChunkSequence(Expression.Sequence seq) {
        var elements = seq.elements();
        if (elements.size() > SEQ_CHUNK_ELEMENT_THRESHOLD) {
            return true;
        }
        int weight = 0;
        for (var elem : elements) {
            weight += estimateExpressionWeight(elem);
            if (weight > SEQ_CHUNK_BYTE_THRESHOLD) {
                return true;
            }
        }
        return false;
    }

    /**
     * Phase G2: pack elements into chunks. Each chunk takes elements until either
     * the cumulative weight exceeds {@link #SEQ_CHUNK_MAX_WEIGHT} or the count
     * reaches {@link #SEQ_CHUNK_MAX_ELEMENTS}. A single heavy element (weight ≥
     * {@code SEQ_CHUNK_MAX_WEIGHT}) gets its own chunk. Returns a list of
     * {@code [startInclusive, endExclusive]} pairs.
     */
    private static java.util.List<int[] > computeChunkBounds(java.util.List<Expression> elements) {
        var bounds = new java.util.ArrayList<int[] >();
        int n = elements.size();
        int i = 0;
        while (i < n) {
            int start = i;
            int weight = 0;
            int count = 0;
            while (i < n && count < SEQ_CHUNK_MAX_ELEMENTS) {
                int w = estimateExpressionWeight(elements.get(i));
                // Heavy element: emit it as its own chunk (or end the current chunk
                // here if we already have elements buffered).
                if (count > 0 && weight + w > SEQ_CHUNK_MAX_WEIGHT) {
                    break;
                }
                weight += w;
                count++ ;
                i++ ;
                if (weight > SEQ_CHUNK_MAX_WEIGHT) {
                    break;
                }
            }
            bounds.add(new int[]{start, i});
        }
        return bounds;
    }

    /**
     * Phase G2: rough byte-weight estimate of an Expression's emitted code.
     * Tuned against observed emissions; the absolute scale doesn't matter — only
     * the ranking does (so the pre-filter doesn't chunk trivially small sequences).
     */
    private static int estimateExpressionWeight(Expression expr) {
        return switch (expr) {
            case Expression.Literal lit -> 80 + lit.text()
                                                  .length() * 2;
            case Expression.CharClass ignored -> 120;
            case Expression.Any ignored -> 60;
            case Expression.Reference ignored -> 60;
            case Expression.Cut ignored -> 20;
            case Expression.BackReference ignored -> 80;
            case Expression.Dictionary ignored -> 200;
            case Expression.TokenBoundary tb -> 200 + estimateExpressionWeight(tb.expression());
            case Expression.Ignore ig -> 60 + estimateExpressionWeight(ig.expression());
            case Expression.Capture cap -> 80 + estimateExpressionWeight(cap.expression());
            case Expression.CaptureScope cs -> 80 + estimateExpressionWeight(cs.expression());
            case Expression.Group grp -> estimateExpressionWeight(grp.expression());
            case Expression.And and -> 200 + estimateExpressionWeight(and.expression());
            case Expression.Not not -> 200 + estimateExpressionWeight(not.expression());
            case Expression.Optional opt -> 250 + estimateExpressionWeight(opt.expression());
            case Expression.ZeroOrMore zom -> 350 + estimateExpressionWeight(zom.expression());
            case Expression.OneOrMore oom -> 400 + estimateExpressionWeight(oom.expression());
            case Expression.Repetition rep -> 400 + estimateExpressionWeight(rep.expression());
            case Expression.Sequence sub -> {
                int w = 100;
                for (var e : sub.elements()) {
                    w += 200 + estimateExpressionWeight(e);
                }
                yield w;
            }
            case Expression.Choice choice -> {
                int w = 200;
                for (var alt : choice.alternatives()) {
                    w += 250 + estimateExpressionWeight(alt);
                }
                yield w;
            }
        };
    }

    /**
     * Phase G2: emit the dispatcher portion of a chunked Sequence (in-place,
     * inside the calling method's body). The dispatcher captures sequence-entry
     * state, then calls each chunk helper sequentially, short-circuiting on
     * non-success. Helper method bodies are appended to {@link #pendingChunkHelpers}
     * for later flush at class scope.
     */
    private void emitCstSequenceChunkedDispatcher(StringBuilder sb,
                                                  Expression.Sequence seq,
                                                  String resultVar,
                                                  int indent,
                                                  boolean addToChildren,
                                                  int[] counter,
                                                  boolean inWhitespaceRule,
                                                  int id) {
        var pad = "    ".repeat(indent);
        var seqStartPos = "seqStartPos" + id;
        var seqStartLine = "seqStartLine" + id;
        var seqStartColumn = "seqStartColumn" + id;
        var seqPending = "seqPending" + id;
        var seqChildren = "seqChildren" + id;
        var elements = seq.elements();
        int seqOrdinal = currentRuleSequenceCounter++ ;
        sb.append(pad)
          .append("CstParseResult ")
          .append(resultVar)
          .append(" = CstParseResult.successNoLoc(null, \"\");\n");
        sb.append(pad)
          .append("int ")
          .append(seqStartPos)
          .append(" = pos;\n");
        sb.append(pad)
          .append("int ")
          .append(seqStartLine)
          .append(" = line;\n");
        sb.append(pad)
          .append("int ")
          .append(seqStartColumn)
          .append(" = column;\n");
        sb.append(pad)
          .append("int ")
          .append(seqPending)
          .append(" = ")
          .append(savePendingExpr())
          .append(";\n");
        if (addToChildren) {
            sb.append(pad)
              .append("var ")
              .append(seqChildren)
              .append(" = new ArrayList<>(children);\n");
        }
        // Walk elements grouped into chunks. Each chunk packs elements until either
        // SEQ_CHUNK_MAX_ELEMENTS is reached OR cumulative weight exceeds
        // SEQ_CHUNK_MAX_WEIGHT (whichever comes first). A single very heavy element
        // always gets its own chunk (so it doesn't drag in additional elements).
        var chunkBounds = computeChunkBounds(elements);
        boolean priorCut = false;
        int chunkIndex = 0;
        for (int chunkIdx = 0; chunkIdx < chunkBounds.size(); chunkIdx++ ) {
            int start = chunkBounds.get(chunkIdx) [0];
            int end = chunkBounds.get(chunkIdx) [1];
            // Emit dispatcher call to this chunk.
            String chunkMethod = "parse_" + currentRuleName + "_seq" + seqOrdinal + "_chunk" + chunkIndex;
            if (chunkIndex == 0) {
                sb.append(pad)
                  .append(resultVar)
                  .append(" = ")
                  .append(chunkMethod)
                  .append("(");
            }else {
                sb.append(pad)
                  .append("if (")
                  .append(resultVar)
                  .append(".isSuccess()) ")
                  .append(resultVar)
                  .append(" = ")
                  .append(chunkMethod)
                  .append("(");
            }
            // Args: children (if addToChildren), seqStartPos, seqStartLine, seqStartColumn, seqPending,
            //       seqChildren (if addToChildren).
            if (addToChildren) {
                sb.append("children, ");
            }
            sb.append(seqStartPos)
              .append(", ")
              .append(seqStartLine)
              .append(", ")
              .append(seqStartColumn)
              .append(", ")
              .append(seqPending);
            if (addToChildren) {
                sb.append(", ")
                  .append(seqChildren);
            }
            sb.append(");\n");
            // Emit the chunk helper method (deferred to pendingChunkHelpers — class-scope sibling).
            emitCstSequenceChunkHelper(seq,
                                       elements,
                                       start,
                                       end,
                                       seqOrdinal,
                                       chunkIndex,
                                       priorCut,
                                       addToChildren,
                                       inWhitespaceRule);
            // Update priorCut for next chunk (any Cut element in this chunk implies cut is set
            // statically at next-chunk entry).
            for (int i = start; i < end; i++ ) {
                if (elements.get(i) instanceof Expression.Cut) {
                    priorCut = true;
                    break;
                }
            }
            chunkIndex++ ;
        }
        // After all chunks: success epilogue (matches inline emission's trailing `if isSuccess` block).
        sb.append(pad)
          .append("if (")
          .append(resultVar)
          .append(".isSuccess()) {\n");
        sb.append(pad)
          .append("    ")
          .append(resultVar)
          .append(" = CstParseResult.successNoLoc(null, substring(")
          .append(seqStartPos)
          .append(", pos));\n");
        sb.append(pad)
          .append("}\n");
    }

    /**
     * Phase G2: append a chunk helper method body to {@link #pendingChunkHelpers}.
     * The helper takes sequence-entry state and runs its element subset with the
     * same per-element rollback shape as the inline emission. Static {@code priorCut}
     * encodes whether any earlier-chunk element was a Cut (so failures in this chunk
     * convert to cut-failures even before any in-chunk Cut runs).
     */
    private void emitCstSequenceChunkHelper(Expression.Sequence seq,
                                            java.util.List<Expression> elements,
                                            int start,
                                            int end,
                                            int seqOrdinal,
                                            int chunkIndex,
                                            boolean priorCut,
                                            boolean addToChildren,
                                            boolean inWhitespaceRule) {
        // Each chunk helper writes to its OWN StringBuilder so nested chunked Sequences
        // (chunk helper containing a chunked sub-Sequence) don't have their bodies
        // interleaved. The list is flushed in insertion order; helpers appear as
        // sibling class-level methods.
        var sb = new StringBuilder();
        pendingChunkHelpers.add(sb);
        String methodName = "parse_" + currentRuleName + "_seq" + seqOrdinal + "_chunk" + chunkIndex;
        sb.append("    private CstParseResult ")
          .append(methodName)
          .append("(");
        if (addToChildren) {
            sb.append("ArrayList<CstNode> children, ");
        }
        sb.append("int seqStartPos, int seqStartLine, int seqStartColumn, int seqPending");
        if (addToChildren) {
            sb.append(", ArrayList<CstNode> seqChildren");
        }
        sb.append(") {\n");
        // Helper-local __ruleName so inner emissions referencing it (TokenBoundary, ZeroOrMore
        // wrap-as-NonTerminal sites) compile. Mirrors Phase G alt helper convention.
        sb.append("        var __ruleName = ")
          .append(currentRuleIdConst)
          .append(";\n");
        // Helper-local sequence result variable. Initialized to success so the per-element
        // `if (result.isSuccess())` short-circuit works correctly within the chunk.
        sb.append("        CstParseResult result = CstParseResult.successNoLoc(null, \"\");\n");
        // In-chunk cut tracking: starts at the static priorCut value, may flip to true
        // mid-chunk when a Cut element is encountered.
        sb.append("        boolean cut = ")
          .append(priorCut)
          .append(";\n");
        // Helper-local counter for sub-emission unique-name suffixes; restarting at 0 is safe
        // since the helper's variable names are scoped to this method.
        var counter = new int[]{0};
        int indent = 2;
        var pad = "    ".repeat(indent);
        for (int i = start; i < end; i++ ) {
            var elem = elements.get(i);
            sb.append(pad)
              .append("if (result.isSuccess()) {\n");
            if (!inWhitespaceRule && !isPredicate(elem)) {
                sb.append(pad)
                  .append(config.triviaPostPass()
                          ? "    if (tokenBoundaryDepth == 0) skipWhitespace();\n"
                          : "    if (tokenBoundaryDepth == 0) appendPending(skipWhitespace());\n");
            }
            // Element-local result variable name. The leading `chunkIdx_` prefix is just
            // a unique-name disambiguator across elements within the chunk.
            int globalElemIdx = i;
            var elemVar = "elem_" + globalElemIdx;
            generateCstExpressionCode(sb, elem, elemVar, indent + 1, addToChildren, counter, inWhitespaceRule);
            // Cut-failure propagation.
            sb.append(pad)
              .append("    if (")
              .append(elemVar)
              .append(".isCutFailure()) {\n");
            sb.append(pad)
              .append("        restoreLocationRaw(seqStartPos, seqStartLine, seqStartColumn);\n");
            sb.append(pad)
              .append(config.triviaPostPass()
                      ? ""
                      : "        restorePendingLeading(seqPending);\n");
            if (addToChildren) {
                sb.append(pad)
                  .append("        children.clear();\n");
                sb.append(pad)
                  .append("        children.addAll(seqChildren);\n");
            }
            sb.append(pad)
              .append("        result = ")
              .append(elemVar)
              .append(";\n");
            sb.append(pad)
              .append("    } else if (")
              .append(elemVar)
              .append(".isFailure()) {\n");
            sb.append(pad)
              .append("        restoreLocationRaw(seqStartPos, seqStartLine, seqStartColumn);\n");
            sb.append(pad)
              .append(config.triviaPostPass()
                      ? ""
                      : "        restorePendingLeading(seqPending);\n");
            if (addToChildren) {
                sb.append(pad)
                  .append("        children.clear();\n");
                sb.append(pad)
                  .append("        children.addAll(seqChildren);\n");
            }
            sb.append(pad)
              .append("        result = cut ? ")
              .append(elemVar)
              .append(".asCutFailure() : ")
              .append(elemVar)
              .append(";\n");
            sb.append(pad)
              .append("    }\n");
            sb.append(pad)
              .append("}\n");
            if (elem instanceof Expression.Cut) {
                sb.append(pad)
                  .append("cut = true;\n");
            }
        }
        sb.append("        return result;\n");
        sb.append("    }\n\n");
    }

    /**
     * Phase G2: byte-identical inline CST Sequence emission (the original code,
     * preserved for small sequences so existing parity tests stay green).
     */
    private void emitCstSequenceInline(StringBuilder sb,
                                       Expression.Sequence seq,
                                       String resultVar,
                                       int indent,
                                       boolean addToChildren,
                                       int[] counter,
                                       boolean inWhitespaceRule,
                                       int id) {
        var pad = "    ".repeat(indent);
        var seqStartPos = "seqStartPos" + id;
        var seqStartLine = "seqStartLine" + id;
        var seqStartColumn = "seqStartColumn" + id;
        var seqPending = "seqPending" + id;
        var seqChildren = "seqChildren" + id;
        var cutVar = "cut" + id;
        sb.append(pad)
          .append("CstParseResult ")
          .append(resultVar)
          .append(" = CstParseResult.successNoLoc(null, \"\");\n");
        sb.append(pad)
          .append("int ")
          .append(seqStartPos)
          .append(" = pos;\n");
        sb.append(pad)
          .append("int ")
          .append(seqStartLine)
          .append(" = line;\n");
        sb.append(pad)
          .append("int ")
          .append(seqStartColumn)
          .append(" = column;\n");
        sb.append(pad)
          .append("int ")
          .append(seqPending)
          .append(" = ")
          .append(savePendingExpr())
          .append(";\n");
        if (addToChildren) {
            sb.append(pad)
              .append("var ")
              .append(seqChildren)
              .append(" = new ArrayList<>(children);\n");
        }
        sb.append(pad)
          .append("boolean ")
          .append(cutVar)
          .append(" = false;\n");
        int i = 0;
        for (var elem : seq.elements()) {
            sb.append(pad)
              .append("if (")
              .append(resultVar)
              .append(".isSuccess()) {\n");
            if (!inWhitespaceRule && !isPredicate(elem)) {
                sb.append(pad)
                  .append(config.triviaPostPass()
                          ? "    if (tokenBoundaryDepth == 0) skipWhitespace();\n"
                          : "    if (tokenBoundaryDepth == 0) appendPending(skipWhitespace());\n");
            }
            var elemVar = "elem" + id + "_" + i;
            generateCstExpressionCode(sb, elem, elemVar, indent + 1, addToChildren, counter, inWhitespaceRule);
            sb.append(pad)
              .append("    if (")
              .append(elemVar)
              .append(".isCutFailure()) {\n");
            sb.append(pad)
              .append("        restoreLocationRaw(")
              .append(seqStartPos)
              .append(", ")
              .append(seqStartLine)
              .append(", ")
              .append(seqStartColumn)
              .append(");\n");
            if (!config.triviaPostPass()) {
                sb.append(pad)
                  .append("        restorePendingLeading(")
                  .append(seqPending)
                  .append(");\n");
            }
            if (addToChildren) {
                sb.append(pad)
                  .append("        children.clear();\n");
                sb.append(pad)
                  .append("        children.addAll(")
                  .append(seqChildren)
                  .append(");\n");
            }
            sb.append(pad)
              .append("        ")
              .append(resultVar)
              .append(" = ")
              .append(elemVar)
              .append(";\n");
            sb.append(pad)
              .append("    } else if (")
              .append(elemVar)
              .append(".isFailure()) {\n");
            sb.append(pad)
              .append("        restoreLocationRaw(")
              .append(seqStartPos)
              .append(", ")
              .append(seqStartLine)
              .append(", ")
              .append(seqStartColumn)
              .append(");\n");
            if (!config.triviaPostPass()) {
                sb.append(pad)
                  .append("        restorePendingLeading(")
                  .append(seqPending)
                  .append(");\n");
            }
            if (addToChildren) {
                sb.append(pad)
                  .append("        children.clear();\n");
                sb.append(pad)
                  .append("        children.addAll(")
                  .append(seqChildren)
                  .append(");\n");
            }
            sb.append(pad)
              .append("        ")
              .append(resultVar)
              .append(" = ")
              .append(cutVar)
              .append(" ? ")
              .append(elemVar)
              .append(".asCutFailure() : ")
              .append(elemVar)
              .append(";\n");
            sb.append(pad)
              .append("    }\n");
            sb.append(pad)
              .append("}\n");
            if (elem instanceof Expression.Cut) {
                sb.append(pad)
                  .append(cutVar)
                  .append(" = true;\n");
            }
            i++ ;
        }
        sb.append(pad)
          .append("if (")
          .append(resultVar)
          .append(".isSuccess()) {\n");
        sb.append(pad)
          .append("    ")
          .append(resultVar)
          .append(" = CstParseResult.successNoLoc(null, substring(")
          .append(seqStartPos)
          .append(", pos));\n");
        sb.append(pad)
          .append("}\n");
    }

    // ============================================================
    // Phase H: nested-Choice extraction
    // ============================================================
    /**
     * Phase H: emit a CST {@link Expression.Choice} body, optionally extracting
     * heavy nested Choices into per-rule helper methods. When the Choice exceeds
     * either the alt-count or estimated-byte threshold, the inline emission is
     * deferred to a sibling helper {@code parse_<rule>_choice<N>} and the call
     * site emits a thin "var resultVar = helper(children)" call.
     *
     * <p>Helper signature contract: takes the ambient {@code children} list
     * (when {@code addToChildren} is true) so any in-Choice additions survive
     * across the helper boundary. Returns the result of the inline Choice
     * emission, including the "all alts failed" epilogue. Caller binds it to
     * {@code resultVar} unchanged.
     *
     * <p>PEG semantics preserved — the helper body is the SAME inline emission
     * that {@link #emitCstChoiceInline} would produce; only its lexical home
     * moves. Cut-failure conversion, whitespace handling, and the "one of
     * alternatives" failure all happen identically.
     *
     * <p>When extraction is not triggered (small Choice OR no per-rule chunk
     * helper context — e.g. inside an ad-hoc emit path that didn't set up
     * {@link #pendingChunkHelpers}), the inline emission runs directly.
     */
    private void emitCstChoiceMaybeExtracted(StringBuilder sb,
                                             Expression.Choice choice,
                                             String resultVar,
                                             int indent,
                                             boolean addToChildren,
                                             int[] counter,
                                             boolean inWhitespaceRule,
                                             int id) {
        if (pendingChunkHelpers != null && currentRuleName != null && shouldExtractNestedChoice(choice)) {
            emitCstChoiceExtractedDispatcher(sb, choice, resultVar, indent, addToChildren, inWhitespaceRule);
            return;
        }
        emitCstChoiceInline(sb, choice, resultVar, indent, addToChildren, counter, inWhitespaceRule, id);
    }

    /**
     * Phase H: extraction heuristic. Trigger when the Choice has at least
     * {@link #NESTED_CHOICE_ALT_THRESHOLD} alternatives OR its estimated emitted-byte
     * weight exceeds {@link #NESTED_CHOICE_BYTE_THRESHOLD}. Pre-filter; the byte
     * estimate is rough and only needs to rank correctly relative to small-Choice
     * cases that should remain inline.
     */
    private static boolean shouldExtractNestedChoice(Expression.Choice choice) {
        if (choice.alternatives()
                  .size() >= NESTED_CHOICE_ALT_THRESHOLD) {
            return true;
        }
        return estimateExpressionWeight(choice) >= NESTED_CHOICE_BYTE_THRESHOLD;
    }

    /**
     * Phase H: emit the dispatcher portion of an extracted Choice (in-place,
     * inside the calling method's body). The dispatcher emits a single helper
     * call; the helper body is appended to {@link #pendingChunkHelpers} for
     * later flush at class scope.
     */
    private void emitCstChoiceExtractedDispatcher(StringBuilder sb,
                                                  Expression.Choice choice,
                                                  String resultVar,
                                                  int indent,
                                                  boolean addToChildren,
                                                  boolean inWhitespaceRule) {
        var pad = "    ".repeat(indent);
        int choiceOrdinal = currentRuleChoiceCounter++ ;
        String helperName = "parse_" + currentRuleName + "_choice" + choiceOrdinal;
        sb.append(pad)
          .append("CstParseResult ")
          .append(resultVar)
          .append(" = ")
          .append(helperName)
          .append("(");
        if (addToChildren) {
            sb.append("children");
        }
        sb.append(");\n");
        emitCstChoiceExtractedHelper(choice, choiceOrdinal, addToChildren, inWhitespaceRule);
    }

    /**
     * Phase H: append a Choice helper method body to {@link #pendingChunkHelpers}.
     * The helper takes {@code children} (when applicable), declares helper-local
     * {@code __ruleName} and a fresh emission counter starting at 0, then runs the
     * standard inline Choice emission against an internal result variable, and
     * returns it. Mirrors the chunk-helper convention from Phase G2 so nested
     * Choice extractions inside chunk helpers (or vice-versa) compose correctly.
     */
    private void emitCstChoiceExtractedHelper(Expression.Choice choice,
                                              int choiceOrdinal,
                                              boolean addToChildren,
                                              boolean inWhitespaceRule) {
        // Each Choice helper writes to its OWN StringBuilder so nested extractions
        // (helper containing another extracted Choice or chunked Sequence) don't
        // interleave bodies. The list is flushed in insertion order; helpers appear
        // as sibling class-level methods.
        var sb = new StringBuilder();
        pendingChunkHelpers.add(sb);
        String methodName = "parse_" + currentRuleName + "_choice" + choiceOrdinal;
        sb.append("    private CstParseResult ")
          .append(methodName)
          .append("(");
        if (addToChildren) {
            sb.append("ArrayList<CstNode> children");
        }
        sb.append(") {\n");
        // Helper-local __ruleName so inner emissions referencing it (TokenBoundary,
        // ZeroOrMore wrap-as-NonTerminal sites) compile after extraction. Mirrors
        // Phase G alt-helper and Phase G2 chunk-helper conventions.
        sb.append("        var __ruleName = ")
          .append(currentRuleIdConst)
          .append(";\n");
        // Run the standard inline Choice emission. Counter is local to this helper
        // (restarting at 0 is safe — variable names are scoped to this method body).
        var counter = new int[]{0};
        int innerId = counter[0]++ ;
        emitCstChoiceInline(sb, choice, "result", 2, addToChildren, counter, inWhitespaceRule, innerId);
        sb.append("        return result;\n");
        sb.append("    }\n\n");
    }

    /**
     * Phase H: byte-identical inline CST Choice emission (the original code,
     * preserved so existing parity tests stay green when extraction is not
     * triggered, and reused by the helper body when extraction IS triggered).
     */
    private void emitCstChoiceInline(StringBuilder sb,
                                     Expression.Choice choice,
                                     String resultVar,
                                     int indent,
                                     boolean addToChildren,
                                     int[] counter,
                                     boolean inWhitespaceRule,
                                     int id) {
        var pad = "    ".repeat(indent);
        // Phase 1.7 (D): inline int captures — Choice restore is hot under
        // backtracking. The base name is reused across helpers; suffixes
        // Pos/Line/Column denote the 3 inline ints.
        var choiceStart = "choiceStart" + id;
        // §7.2: the child-state variable is either a pre-cloned ArrayList
        // (legacy path, flag off) or an int size mark (optimized path, flag on).
        // Emitted identifier differs so byte-identical output is preserved when
        // the flag is off.
        var childrenState = config.markResetChildren()
                            ? "childrenMark" + id
                            : "savedChildren" + id;
        sb.append(pad)
          .append("CstParseResult ")
          .append(resultVar)
          .append(" = null;\n");
        sb.append(pad)
          .append("int ")
          .append(choiceStart)
          .append("Pos = pos;\n");
        sb.append(pad)
          .append("int ")
          .append(choiceStart)
          .append("Line = line;\n");
        sb.append(pad)
          .append("int ")
          .append(choiceStart)
          .append("Column = column;\n");
        // Snapshot pending-leading so failed alternatives can be rolled
        // back — captured trivia inside one alt must not leak forward
        // into sibling alternatives.
        sb.append(pad)
          .append("int choicePending")
          .append(id)
          .append(" = ")
          .append(savePendingExpr())
          .append(";\n");
        // Don't skip whitespace here - let alternatives capture trivia themselves
        emitChildrenSave(sb, pad, childrenState, addToChildren);
        // Phase 1F: try extended FIRST-set classification first (covers Reference,
        // CharClass, mixed choices). Falls back to legacy literal-only classifier when
        // the extended path can't dispatch any alt.
        var classifiedF = config.choiceDispatch()
                          ? ChoiceDispatchAnalyzer.classify(choice, firstSets())
                          : java.util.Optional.<ChoiceDispatchAnalyzer.Classified>empty();
        if (classifiedF.isPresent()) {
            var c = classifiedF.get();
            var buckets = ChoiceDispatchAnalyzer.bucketsForClassified(c);
            emitCstChoiceDispatchF(sb,
                                   buckets,
                                   c.defaults(),
                                   id,
                                   indent,
                                   addToChildren,
                                   counter,
                                   inWhitespaceRule,
                                   resultVar,
                                   choiceStart,
                                   childrenState);
        }else {
            int i = 0;
            for (var alt : choice.alternatives()) {
                emitCstChoiceAlt(sb,
                                 alt,
                                 id,
                                 i,
                                 indent,
                                 addToChildren,
                                 counter,
                                 inWhitespaceRule,
                                 resultVar,
                                 choiceStart,
                                 childrenState,
                                 pad);
                i++ ;
            }
            for (int j = 0; j < choice.alternatives()
                                      .size(); j++ ) {
                sb.append(pad)
                  .append("}\n");
            }
        }
        sb.append(pad)
          .append("if (")
          .append(resultVar)
          .append(" == null) {\n");
        emitChildrenRestore(sb, pad + "    ", childrenState, addToChildren);
        if (!config.triviaPostPass()) {
            sb.append(pad)
              .append("    restorePendingLeading(choicePending")
              .append(id)
              .append(");\n");
        }
        sb.append(pad)
          .append("    ")
          .append(resultVar)
          .append(" = CstParseResult.failure(\"one of alternatives\");\n");
        sb.append(pad)
          .append("}\n");
    }

    private boolean isTokenRule(Expression expr) {
        return switch (expr) {
            case Expression.TokenBoundary tb -> true;
            case Expression.Literal lit -> true;
            case Expression.CharClass cc -> true;
            case Expression.Sequence seq -> seq.elements()
                                               .stream()
                                               .allMatch(this::isTokenRule);
            case Expression.Group grp -> isTokenRule(grp.expression());
            default -> false;
        };
    }

    // ============================================================
    // Phase 1.9 (DFA spike) — token fast-path detection & emission.
    //
    // When a TokenBoundary wraps a Sequence(CharClass, ZeroOrMore(CharClass))
    // (with optional Group wrappers), and both classes are pure ASCII range
    // descriptors (no \\u/\\x escapes outside ASCII, not case-insensitive),
    // emit a tight inline scanner instead of going through matchCharClassCst.
    //
    // Identifier-shaped rules in real grammars (e.g. Java25's
    //   Identifier <- !Keyword < [a-zA-Z_$] [a-zA-Z0-9_$]* >
    // ) are extremely high-volume; the framework loop costs many cycles per
    // character (failure cache, trackFailure, advance, build a Terminal node
    // per character, ZeroOrMore quantifier overhead). This fast-path skips
    // all of that and emits a plain `while (i < end && isCont(c)) i++;`
    // ============================================================
    private static Expression unwrapGroup(Expression e) {
        while (e instanceof Expression.Group g) {
            e = g.expression();
        }
        return e;
    }

    private boolean isFastPathTokenBoundary(Expression.TokenBoundary tb) {
        var inner = unwrapGroup(tb.expression());
        if (! (inner instanceof Expression.Sequence seq)) {
            return false;
        }
        var elements = seq.elements();
        if (elements.size() != 2) {
            return false;
        }
        var first = unwrapGroup(elements.get(0));
        var second = unwrapGroup(elements.get(1));
        if (! (first instanceof Expression.CharClass startCls)) {
            return false;
        }
        if (! (second instanceof Expression.ZeroOrMore zom)) {
            return false;
        }
        var contInner = unwrapGroup(zom.expression());
        if (! (contInner instanceof Expression.CharClass contCls)) {
            return false;
        }
        // Conservatively reject case-insensitive and negated classes — the slow
        // path's matchesPattern handles them, but our gen-time decoder is
        // ASCII/range-only.
        if (startCls.caseInsensitive() || contCls.caseInsensitive()) {
            return false;
        }
        if (startCls.negated() || contCls.negated()) {
            return false;
        }
        return decodeAsciiCharClass(startCls.pattern()) != null && decodeAsciiCharClass(contCls.pattern()) != null;
    }

    /**
     * Decode a CharClass pattern (the raw text between [ and ]) into a list of
     * inclusive ASCII ranges. Returns null when the pattern uses any feature
     * the fast path doesn't handle (non-ASCII chars, \\u/\\x escapes that
     * resolve outside ASCII).
     *
     * <p>Mirrors the runtime {@code matchesPattern} parsing rules: {@code \\}
     * escapes ({@code n,r,t,\\,],-}), single chars, and {@code c1-c2} ranges.
     * Non-ASCII (>= 128) characters and outside-ASCII unicode/hex escapes
     * disqualify the pattern.
     */
    /** Inclusive ASCII range used by the fast-path char-class decoder. */
    private record AsciiRange(int lo, int hi) {}

    private java.util.List<AsciiRange> decodeAsciiCharClass(String pattern) {
        var ranges = new java.util.ArrayList<AsciiRange>();
        int i = 0;
        int len = pattern.length();
        while (i < len) {
            int decodedStart;
            int consumed;
            int next = decodePatternChar(pattern, i, len);
            if (next < 0) {
                return null;
            }
            decodedStart = next & 0xFF;
            consumed = next>>> 8;
            if (decodedStart > 127) {
                return null;
            }
            int rangeMid = i + consumed;
            if (rangeMid < len && pattern.charAt(rangeMid) == '-' && rangeMid + 1 < len) {
                int next2 = decodePatternChar(pattern, rangeMid + 1, len);
                if (next2 < 0) {
                    return null;
                }
                int decodedEnd = next2 & 0xFF;
                int endConsumed = next2>>> 8;
                if (decodedEnd > 127) {
                    return null;
                }
                ranges.add(new AsciiRange(decodedStart, decodedEnd));
                i = rangeMid + 1 + endConsumed;
            }else {
                ranges.add(new AsciiRange(decodedStart, decodedStart));
                i = rangeMid;
            }
        }
        if (ranges.isEmpty()) {
            return null;
        }
        return ranges;
    }

    /**
     * Decode a single character from a CharClass pattern at offset {@code i}.
     * Returns a packed int: low byte = decoded char value (0-127 for ASCII,
     * else the caller should reject), high bits = consumed length. Returns
     * a negative value to signal failure (malformed escape, non-ASCII unicode
     * escape).
     */
    private int decodePatternChar(String pattern, int i, int len) {
        char c = pattern.charAt(i);
        if (c != '\\' || i + 1 >= len) {
            return (c & 0xFF) | (1<< 8);
        }
        char esc = pattern.charAt(i + 1);
        return switch (esc) {
            case'n' -> ('\n' & 0xFF) | (2<< 8);
            case'r' -> ('\r' & 0xFF) | (2<< 8);
            case't' -> ('\t' & 0xFF) | (2<< 8);
            case'\\' -> ('\\' & 0xFF) | (2<< 8);
            case']' -> (']' & 0xFF) | (2<< 8);
            case'-' -> ('-' & 0xFF) | (2<< 8);
            case'x' -> decodeHexEscape(pattern, i + 2, 2, len, 4);
            case'u' -> decodeHexEscape(pattern, i + 2, 4, len, 6);
            default -> (esc & 0xFF) | (2<< 8);
        };
    }

    private int decodeHexEscape(String pattern, int hexStart, int hexDigits, int len, int totalConsumed) {
        if (hexStart + hexDigits > len) {
            return - 1;
        }
        try{
            int value = Integer.parseInt(pattern.substring(hexStart, hexStart + hexDigits), 16);
            if (value < 0 || value > 255) {
                return - 1;
            }
            return (value & 0xFF) | (totalConsumed<< 8);
        } catch (NumberFormatException nfe) {
            return - 1;
        }
    }

    /**
     * Build a Java boolean expression that tests whether {@code varName} (a
     * char) is in the supplied ASCII range set. Single-element ranges become
     * {@code v == 'x'}; multi-element ranges become {@code v >= 'a' && v <= 'z'}.
     */
    private String charClassExpr(String varName, java.util.List<AsciiRange> ranges) {
        var sb = new StringBuilder();
        for (int idx = 0; idx < ranges.size(); idx++ ) {
            if (idx > 0) sb.append(" || ");
            var r = ranges.get(idx);
            if (r.lo() == r.hi()) {
                sb.append("(")
                  .append(varName)
                  .append(" == ")
                  .append(asciiLiteral(r.lo()))
                  .append(")");
            }else {
                sb.append("(")
                  .append(varName)
                  .append(" >= ")
                  .append(asciiLiteral(r.lo()))
                  .append(" && ")
                  .append(varName)
                  .append(" <= ")
                  .append(asciiLiteral(r.hi()))
                  .append(")");
            }
        }
        return sb.toString();
    }

    private String asciiLiteral(int v) {
        // Always emit numeric for safety (no quoting issues with $, ', etc.).
        return "(char) " + v;
    }

    private void emitCstFastPathTokenBoundary(StringBuilder sb,
                                              Expression.TokenBoundary tb,
                                              String resultVar,
                                              int indent,
                                              boolean addToChildren,
                                              int id,
                                              boolean inWhitespaceRule) {
        var pad = "        ".substring(0, Math.min(8, indent * 4)) + (indent > 2
                                                                      ? " ".repeat((indent - 2) * 4)
                                                                      : "");
        // Use the canonical 4-space indent matching neighbouring emissions.
        pad = " ".repeat(indent * 4);
        var inner = unwrapGroup(tb.expression());
        var seq = (Expression.Sequence) inner;
        var startCls = (Expression.CharClass) unwrapGroup(seq.elements()
                                                             .get(0));
        var contCls = (Expression.CharClass) unwrapGroup(((Expression.ZeroOrMore) unwrapGroup(seq.elements()
                                                                                                 .get(1))).expression());
        var startRanges = decodeAsciiCharClass(startCls.pattern());
        var contRanges = decodeAsciiCharClass(contCls.pattern());
        var tbStartPos = "tbStartPos" + id;
        var tbStartLine = "tbStartLine" + id;
        var tbStartColumn = "tbStartColumn" + id;
        var fpEnd = "fpEnd" + id;
        var fpInput = "fpInput" + id;
        var fpFirst = "fpFirst" + id;
        sb.append(pad)
          .append("int ")
          .append(tbStartPos)
          .append(" = pos;\n");
        sb.append(pad)
          .append("int ")
          .append(tbStartLine)
          .append(" = line;\n");
        sb.append(pad)
          .append("int ")
          .append(tbStartColumn)
          .append(" = column;\n");
        sb.append(pad)
          .append("CstParseResult ")
          .append(resultVar)
          .append(";\n");
        // Bounds + first-char test: the rule body's first CharClass must match,
        // mirroring matchCharClassCst's isAtEnd / matches semantics. On miss
        // we set a failure result identical in shape to the slow path's failure
        // (no trivia/state mutation).
        sb.append(pad)
          .append("if (pos >= input.length()) {\n");
        sb.append(pad)
          .append("    trackFailure(\"[")
          .append(escape(startCls.pattern()))
          .append("]\");\n");
        sb.append(pad)
          .append("    ")
          .append(resultVar)
          .append(" = CstParseResult.failure(\"character class\");\n");
        sb.append(pad)
          .append("} else {\n");
        sb.append(pad)
          .append("    String ")
          .append(fpInput)
          .append(" = input;\n");
        sb.append(pad)
          .append("    char ")
          .append(fpFirst)
          .append(" = ")
          .append(fpInput)
          .append(".charAt(pos);\n");
        sb.append(pad)
          .append("    if (!(")
          .append(charClassExpr(fpFirst, startRanges))
          .append(")) {\n");
        sb.append(pad)
          .append("        trackFailure(\"[")
          .append(escape(startCls.pattern()))
          .append("]\");\n");
        sb.append(pad)
          .append("        ")
          .append(resultVar)
          .append(" = CstParseResult.failure(\"character class\");\n");
        sb.append(pad)
          .append("    } else {\n");
        // Tight scanner: advance through start char + zero-or-more cont chars.
        // line/column update mirrors advance(): newline → line++, column=1, else column++.
        // We update line/column in the loop only if any of the ranges include
        // '\n' / '\r'; otherwise we can skip the per-char branching. For
        // Identifier this is the common case.
        boolean startCanCrLf = rangesContainNewlineOrCr(startRanges);
        boolean contCanCrLf = rangesContainNewlineOrCr(contRanges);
        sb.append(pad)
          .append("        int ")
          .append(fpEnd)
          .append(" = pos + 1;\n");
        sb.append(pad)
          .append("        int fpLine")
          .append(id)
          .append(" = line;\n");
        sb.append(pad)
          .append("        int fpCol")
          .append(id)
          .append(" = column + 1;\n");
        if (startCanCrLf) {
            sb.append(pad)
              .append("        if (")
              .append(fpFirst)
              .append(" == '\\n') { fpLine")
              .append(id)
              .append("++; fpCol")
              .append(id)
              .append(" = 1; }\n");
        }
        sb.append(pad)
          .append("        int fpLen")
          .append(id)
          .append(" = ")
          .append(fpInput)
          .append(".length();\n");
        sb.append(pad)
          .append("        while (")
          .append(fpEnd)
          .append(" < fpLen")
          .append(id)
          .append(") {\n");
        sb.append(pad)
          .append("            char fpC")
          .append(id)
          .append(" = ")
          .append(fpInput)
          .append(".charAt(")
          .append(fpEnd)
          .append(");\n");
        sb.append(pad)
          .append("            if (!(")
          .append(charClassExpr("fpC" + id, contRanges))
          .append(")) break;\n");
        sb.append(pad)
          .append("            ")
          .append(fpEnd)
          .append("++;\n");
        if (contCanCrLf) {
            sb.append(pad)
              .append("            if (fpC")
              .append(id)
              .append(" == '\\n') { fpLine")
              .append(id)
              .append("++; fpCol")
              .append(id)
              .append(" = 1; } else { fpCol")
              .append(id)
              .append("++; }\n");
        }else {
            sb.append(pad)
              .append("            fpCol")
              .append(id)
              .append("++;\n");
        }
        sb.append(pad)
          .append("        }\n");
        // Commit position state and build the Token node identical to the
        // slow-path tbNode shape (id, span, ruleName, text, leading, trailing).
        sb.append(pad)
          .append("        pos = ")
          .append(fpEnd)
          .append(";\n");
        sb.append(pad)
          .append("        line = fpLine")
          .append(id)
          .append(";\n");
        sb.append(pad)
          .append("        column = fpCol")
          .append(id)
          .append(";\n");
        sb.append(pad)
          .append("        var tbText")
          .append(id)
          .append(" = ")
          .append(fpInput)
          .append(".substring(")
          .append(tbStartPos)
          .append(", ")
          .append(fpEnd)
          .append(");\n");
        sb.append(pad)
          .append("        var tbSpan")
          .append(id)
          .append(" = new SourceSpan(")
          .append(tbStartLine)
          .append(", ")
          .append(tbStartColumn)
          .append(", ")
          .append(tbStartPos)
          .append(", line, column, pos);\n");
        var tokenRuleId = inWhitespaceRule
                          ? "RULE_PEG_TOKEN"
                          : "__ruleName";
        sb.append(pad)
          .append("        var tbNode")
          .append(id)
          .append(" = new CstNode.Token(idGen.next(), tbSpan")
          .append(id)
          .append(", ")
          .append(tokenRuleId)
          .append(", tbText")
          .append(id)
          .append(", ")
          .append(takePendingExpr())
          .append(", List.of());\n");
        if (addToChildren) {
            sb.append(pad)
              .append("        children.add(tbNode")
              .append(id)
              .append(");\n");
        }
        sb.append(pad)
          .append("        ")
          .append(resultVar)
          .append(" = CstParseResult.successNoLoc(tbNode")
          .append(id)
          .append(", tbText")
          .append(id)
          .append(");\n");
        sb.append(pad)
          .append("    }\n");
        sb.append(pad)
          .append("}\n");
    }

    private boolean rangesContainNewlineOrCr(java.util.List<AsciiRange> ranges) {
        for (var r : ranges) {
            if (r.lo() <= '\n' && '\n' <= r.hi()) return true;
            if (r.lo() <= '\r' && '\r' <= r.hi()) return true;
        }
        return false;
    }

    private boolean isPredicate(Expression expr) {
        return switch (expr) {
            case Expression.And ignored -> true;
            case Expression.Not ignored -> true;
            case Expression.Group grp -> isPredicate(grp.expression());
            default -> false;
        };
    }

    /**
     * Extract inner expression from ZeroOrMore/OneOrMore for trivia matching.
     * Delegates to {@link ExpressionShape#extractInnerExpression(Expression)}
     * so the generator and {@code PegEngine} interpreter agree on the shape.
     */
    private Expression extractInnerExpression(Expression expr) {
        return ExpressionShape.extractInnerExpression(expr);
    }

    /**
     * 0.2.4 — compute the suggestion vocabulary from rules designated by
     * {@code %suggest}. Walks literal alternatives recursively. Result is used
     * by the generator to emit a {@code SUGGESTION_VOCAB} constant inside the
     * generated ADVANCED parser.
     */
    private static java.util.List<String> computeSuggestionVocabulary(org.pragmatica.peg.grammar.Grammar grammar) {
        if (grammar.suggestRules()
                   .isEmpty()) {
            return java.util.List.of();
        }
        var out = new java.util.ArrayList<String>();
        for (var ruleName : grammar.suggestRules()) {
            grammar.rule(ruleName)
                   .onPresent(rule -> collectVocabLiterals(rule.expression(),
                                                           out));
        }
        return java.util.List.copyOf(out);
    }

    private static void collectVocabLiterals(Expression expr, java.util.List<String> out) {
        switch (expr) {
            case Expression.Literal lit -> {
                if (!out.contains(lit.text())) {
                    out.add(lit.text());
                }
            }
            case Expression.Dictionary dict -> {
                for (var w : dict.words()) {
                    if (!out.contains(w)) {
                        out.add(w);
                    }
                }
            }
            case Expression.Choice c -> c.alternatives()
                                         .forEach(e -> collectVocabLiterals(e, out));
            case Expression.Sequence s -> s.elements()
                                           .forEach(e -> collectVocabLiterals(e, out));
            case Expression.Group g -> collectVocabLiterals(g.expression(), out);
            case Expression.Optional o -> collectVocabLiterals(o.expression(), out);
            case Expression.ZeroOrMore zm -> collectVocabLiterals(zm.expression(), out);
            case Expression.OneOrMore om -> collectVocabLiterals(om.expression(), out);
            case Expression.Repetition r -> collectVocabLiterals(r.expression(), out);
            case Expression.TokenBoundary tb -> collectVocabLiterals(tb.expression(), out);
            case Expression.Ignore ig -> collectVocabLiterals(ig.expression(), out);
            case Expression.Capture cap -> collectVocabLiterals(cap.expression(), out);
            case Expression.CaptureScope cs -> collectVocabLiterals(cs.expression(), out);
            default -> {}
        }
    }

    /**
     * Derive the set of characters that can legally start a whitespace-rule match.
     * Used by the §6.6 skipWhitespace fast-path to short-circuit when the current
     * character cannot possibly begin trivia. Delegates to the shared analysis
     * helper so the generator and the interpreter compute the same set.
     */
    private org.pragmatica.lang.Option<java.util.Set<Character>> whitespaceFirstChars(Expression expr) {
        return FirstCharAnalysis.whitespaceFirstChars(grammar, expr);
    }

    /**
     * Render a boolean expression that is {@code true} when {@code c} is NOT in
     * the fast-path set — i.e. the current char cannot start trivia and
     * {@code skipWhitespace} should return early.
     */
    private String renderNotInSetCheck(java.util.Set<Character> chars) {
        var parts = new java.util.ArrayList<String>();
        for (char c : chars) {
            parts.add("c != " + charLiteral(c));
        }
        return String.join(" && ", parts);
    }

    private static String charLiteral(char c) {
        return switch (c) {
            case'\n' -> "'\\n'";
            case'\r' -> "'\\r'";
            case'\t' -> "'\\t'";
            case'\\' -> "'\\\\'";
            case'\'' -> "'\\''";
            default -> "'" + c + "'";
        };
    }

    private void generateCstExpressionCode(StringBuilder sb,
                                           Expression expr,
                                           String resultVar,
                                           int indent,
                                           boolean addToChildren,
                                           int[] counter,
                                           boolean inWhitespaceRule) {
        if (indent > MAX_RECURSION_DEPTH) {
            throw new IllegalStateException("Grammar expression nesting exceeds maximum depth of " + MAX_RECURSION_DEPTH);
        }
        var pad = "    ".repeat(indent);
        var id = counter[0]++ ;
        // Get unique ID for this expression
        switch (expr) {
            case Expression.Literal lit -> {
                sb.append(pad)
                  .append("var ")
                  .append(resultVar)
                  .append(" = matchLiteralCst(\"")
                  .append(escape(lit.text()))
                  .append("\", ")
                  .append(lit.caseInsensitive())
                  .append(");\n");
                if (addToChildren) {
                    sb.append(pad)
                      .append("if (")
                      .append(resultVar)
                      .append(".isSuccess() && ")
                      .append(resultVar)
                      .append(config.mutableParseResult()
                              ? ".node != null) {\n"
                              : ".node.isPresent()) {\n");
                    sb.append(pad)
                      .append("    children.add(")
                      .append(resultVar)
                      .append(config.mutableParseResult()
                              ? ".node);\n"
                              : ".node.unwrap());\n");
                    sb.append(pad)
                      .append("}\n");
                }
            }
            case Expression.CharClass cc -> {
                sb.append(pad)
                  .append("var ")
                  .append(resultVar)
                  .append(" = matchCharClassCst(\"")
                  .append(escape(cc.pattern()))
                  .append("\", ")
                  .append(cc.negated())
                  .append(", ")
                  .append(cc.caseInsensitive())
                  .append(");\n");
                if (addToChildren) {
                    sb.append(pad)
                      .append("if (")
                      .append(resultVar)
                      .append(".isSuccess() && ")
                      .append(resultVar)
                      .append(config.mutableParseResult()
                              ? ".node != null) {\n"
                              : ".node.isPresent()) {\n");
                    sb.append(pad)
                      .append("    children.add(")
                      .append(resultVar)
                      .append(config.mutableParseResult()
                              ? ".node);\n"
                              : ".node.unwrap());\n");
                    sb.append(pad)
                      .append("}\n");
                }
            }
            case Expression.Dictionary dict -> {
                sb.append(pad)
                  .append("var ")
                  .append(resultVar)
                  .append(" = matchDictionaryCst(List.of(");
                var words = dict.words();
                for (int i = 0; i < words.size(); i++ ) {
                    if (i > 0) sb.append(", ");
                    sb.append("\"")
                      .append(escape(words.get(i)))
                      .append("\"");
                }
                sb.append("), ")
                  .append(dict.caseInsensitive())
                  .append(");\n");
                if (addToChildren) {
                    sb.append(pad)
                      .append("if (")
                      .append(resultVar)
                      .append(".isSuccess() && ")
                      .append(resultVar)
                      .append(config.mutableParseResult()
                              ? ".node != null) {\n"
                              : ".node.isPresent()) {\n");
                    sb.append(pad)
                      .append("    children.add(")
                      .append(resultVar)
                      .append(config.mutableParseResult()
                              ? ".node);\n"
                              : ".node.unwrap());\n");
                    sb.append(pad)
                      .append("}\n");
                }
            }
            case Expression.Any any -> {
                sb.append(pad)
                  .append("var ")
                  .append(resultVar)
                  .append(" = matchAnyCst();\n");
                if (addToChildren) {
                    sb.append(pad)
                      .append("if (")
                      .append(resultVar)
                      .append(".isSuccess() && ")
                      .append(resultVar)
                      .append(config.mutableParseResult()
                              ? ".node != null) {\n"
                              : ".node.isPresent()) {\n");
                    sb.append(pad)
                      .append("    children.add(")
                      .append(resultVar)
                      .append(config.mutableParseResult()
                              ? ".node);\n"
                              : ".node.unwrap());\n");
                    sb.append(pad)
                      .append("}\n");
                }
            }
            case Expression.Reference ref -> {
                sb.append(pad)
                  .append("var ")
                  .append(resultVar)
                  .append(" = parse_")
                  .append(sanitize(ref.ruleName()))
                  .append("();\n");
                if (addToChildren) {
                    sb.append(pad)
                      .append("if (")
                      .append(resultVar)
                      .append(".isSuccess() && ")
                      .append(resultVar)
                      .append(config.mutableParseResult()
                              ? ".node != null) {\n"
                              : ".node.isPresent()) {\n");
                    sb.append(pad)
                      .append("    children.add(")
                      .append(resultVar)
                      .append(config.mutableParseResult()
                              ? ".node);\n"
                              : ".node.unwrap());\n");
                    sb.append(pad)
                      .append("}\n");
                }
            }
            case Expression.Sequence seq -> {
                emitCstSequenceMaybeChunked(sb, seq, resultVar, indent, addToChildren, counter, inWhitespaceRule, id);
            }
            case Expression.Choice choice -> {
                emitCstChoiceMaybeExtracted(sb, choice, resultVar, indent, addToChildren, counter, inWhitespaceRule, id);
            }
            case Expression.ZeroOrMore zom -> {
                // Phase 1.7 (D): inline int captures — no SourceLocation per iteration.
                var zomStartPos = "zomStartPos" + id;
                var zomStartLine = "zomStartLine" + id;
                var zomStartColumn = "zomStartColumn" + id;
                var beforePos = "beforePos" + id;
                var beforeLine = "beforeLine" + id;
                var beforeColumn = "beforeColumn" + id;
                var zomElem = "zomElem" + id;
                var savedChildrenZom = "savedChildrenZom" + id;
                sb.append(pad)
                  .append("CstParseResult ")
                  .append(resultVar)
                  .append(" = CstParseResult.successNoLoc(null, \"\");\n");
                sb.append(pad)
                  .append("int ")
                  .append(zomStartPos)
                  .append(" = pos;\n");
                sb.append(pad)
                  .append("int ")
                  .append(zomStartLine)
                  .append(" = line;\n");
                sb.append(pad)
                  .append("int ")
                  .append(zomStartColumn)
                  .append(" = column;\n");
                // Save parent children and collect loop children separately
                if (addToChildren) {
                    sb.append(pad)
                      .append("var ")
                      .append(savedChildrenZom)
                      .append(" = new ArrayList<>(children);\n");
                    sb.append(pad)
                      .append("children.clear();\n");
                }
                sb.append(pad)
                  .append("while (true) {\n");
                sb.append(pad)
                  .append("    int ")
                  .append(beforePos)
                  .append(" = pos;\n");
                sb.append(pad)
                  .append("    int ")
                  .append(beforeLine)
                  .append(" = line;\n");
                sb.append(pad)
                  .append("    int ")
                  .append(beforeColumn)
                  .append(" = column;\n");
                sb.append(pad)
                  .append("    int zomIterPending")
                  .append(id)
                  .append(" = ")
                  .append(savePendingExpr())
                  .append(";\n");
                if (!inWhitespaceRule) {
                    sb.append(pad)
                      .append(config.triviaPostPass()
                              ? "    if (tokenBoundaryDepth == 0) skipWhitespace();\n"
                              : "    if (tokenBoundaryDepth == 0) appendPending(skipWhitespace());\n");
                }
                generateCstExpressionCode(sb,
                                          zom.expression(),
                                          zomElem,
                                          indent + 1,
                                          addToChildren,
                                          counter,
                                          inWhitespaceRule);
                // CutFailure must propagate
                sb.append(pad)
                  .append("    if (")
                  .append(zomElem)
                  .append(".isCutFailure()) {\n");
                sb.append(pad)
                  .append("        ")
                  .append(resultVar)
                  .append(" = ")
                  .append(zomElem)
                  .append(";\n");
                sb.append(pad)
                  .append("        break;\n");
                sb.append(pad)
                  .append("    }\n");
                sb.append(pad)
                  .append("    if (")
                  .append(zomElem)
                  .append(".isFailure() || pos == ")
                  .append(beforePos)
                  .append(") {\n");
                sb.append(pad)
                  .append("        restoreLocationRaw(")
                  .append(beforePos)
                  .append(", ")
                  .append(beforeLine)
                  .append(", ")
                  .append(beforeColumn)
                  .append(");\n");
                if (!config.triviaPostPass()) {
                    sb.append(pad)
                      .append("        restorePendingLeading(zomIterPending")
                      .append(id)
                      .append(");\n");
                }
                sb.append(pad)
                  .append("        break;\n");
                sb.append(pad)
                  .append("    }\n");
                sb.append(pad)
                  .append("}\n");
                // Wrap collected children and restore parent children
                if (addToChildren) {
                    sb.append(pad)
                      .append("if (!")
                      .append(resultVar)
                      .append(".isCutFailure()) {\n");
                    sb.append(pad)
                      .append("    var zomChildren")
                      .append(id)
                      .append(" = new ArrayList<>(children);\n");
                    sb.append(pad)
                      .append("    children.clear();\n");
                    sb.append(pad)
                      .append("    children.addAll(")
                      .append(savedChildrenZom)
                      .append(");\n");
                    sb.append(pad)
                      .append("    if (zomChildren")
                      .append(id)
                      .append(".size() == 1) {\n");
                    sb.append(pad)
                      .append("        children.add(zomChildren")
                      .append(id)
                      .append(".getFirst());\n");
                    sb.append(pad)
                      .append("    } else if (!zomChildren")
                      .append(id)
                      .append(".isEmpty()) {\n");
                    sb.append(pad)
                      .append("        var zomSpan")
                      .append(id)
                      .append(" = new SourceSpan(")
                      .append(zomStartLine)
                      .append(", ")
                      .append(zomStartColumn)
                      .append(", ")
                      .append(zomStartPos)
                      .append(", line, column, pos);\n");
                    sb.append(pad)
                      .append("        children.add(new CstNode.NonTerminal(idGen.next(), zomSpan")
                      .append(id)
                      .append(", __ruleName, zomChildren")
                      .append(id)
                      .append(", List.of(), List.of()));\n");
                    sb.append(pad)
                      .append("    }\n");
                    sb.append(pad)
                      .append("    ")
                      .append(resultVar)
                      .append(" = CstParseResult.successNoLoc(null, substring(")
                      .append(zomStartPos)
                      .append(", pos));\n");
                    sb.append(pad)
                      .append("} else {\n");
                    sb.append(pad)
                      .append("    children.clear();\n");
                    sb.append(pad)
                      .append("    children.addAll(")
                      .append(savedChildrenZom)
                      .append(");\n");
                    sb.append(pad)
                      .append("}\n");
                }else {
                    // If loop ended due to CutFailure, propagate it
                    sb.append(pad)
                      .append("if (!")
                      .append(resultVar)
                      .append(".isCutFailure()) {\n");
                    sb.append(pad)
                      .append("    ")
                      .append(resultVar)
                      .append(" = CstParseResult.successNoLoc(null, substring(")
                      .append(zomStartPos)
                      .append(", pos));\n");
                    sb.append(pad)
                      .append("}\n");
                }
            }
            case Expression.OneOrMore oom -> {
                // Phase 1.7 (D): inline int captures — no SourceLocation per iteration.
                var oomFirst = "oomFirst" + id;
                var oomStartPos = "oomStartPos" + id;
                var oomStartLine = "oomStartLine" + id;
                var oomStartColumn = "oomStartColumn" + id;
                var beforePos = "beforePos" + id;
                var beforeLine = "beforeLine" + id;
                var beforeColumn = "beforeColumn" + id;
                var oomElem = "oomElem" + id;
                var savedChildrenOom = "savedChildrenOom" + id;
                // Save parent children before first match
                if (addToChildren) {
                    sb.append(pad)
                      .append("var ")
                      .append(savedChildrenOom)
                      .append(" = new ArrayList<>(children);\n");
                    sb.append(pad)
                      .append("children.clear();\n");
                }
                sb.append(pad)
                  .append("int oomEntryPending")
                  .append(id)
                  .append(" = ")
                  .append(savePendingExpr())
                  .append(";\n");
                generateCstExpressionCode(sb,
                                          oom.expression(),
                                          oomFirst,
                                          indent,
                                          addToChildren,
                                          counter,
                                          inWhitespaceRule);
                sb.append(pad)
                  .append("CstParseResult ")
                  .append(resultVar)
                  .append(" = ")
                  .append(oomFirst)
                  .append(";\n");
                sb.append(pad)
                  .append("int ")
                  .append(oomStartPos)
                  .append(" = pos;\n");
                sb.append(pad)
                  .append("int ")
                  .append(oomStartLine)
                  .append(" = line;\n");
                sb.append(pad)
                  .append("int ")
                  .append(oomStartColumn)
                  .append(" = column;\n");
                if (!config.triviaPostPass()) {
                    sb.append(pad)
                      .append("if (!")
                      .append(oomFirst)
                      .append(".isSuccess()) {\n");
                    sb.append(pad)
                      .append("    restorePendingLeading(oomEntryPending")
                      .append(id)
                      .append(");\n");
                    sb.append(pad)
                      .append("}\n");
                }
                sb.append(pad)
                  .append("if (")
                  .append(oomFirst)
                  .append(".isSuccess()) {\n");
                sb.append(pad)
                  .append("    while (true) {\n");
                sb.append(pad)
                  .append("        int ")
                  .append(beforePos)
                  .append(" = pos;\n");
                sb.append(pad)
                  .append("        int ")
                  .append(beforeLine)
                  .append(" = line;\n");
                sb.append(pad)
                  .append("        int ")
                  .append(beforeColumn)
                  .append(" = column;\n");
                sb.append(pad)
                  .append("        int oomIterPending")
                  .append(id)
                  .append(" = ")
                  .append(savePendingExpr())
                  .append(";\n");
                if (!inWhitespaceRule) {
                    sb.append(pad)
                      .append(config.triviaPostPass()
                              ? "        if (tokenBoundaryDepth == 0) skipWhitespace();\n"
                              : "        if (tokenBoundaryDepth == 0) appendPending(skipWhitespace());\n");
                }
                generateCstExpressionCode(sb,
                                          oom.expression(),
                                          oomElem,
                                          indent + 2,
                                          addToChildren,
                                          counter,
                                          inWhitespaceRule);
                // CutFailure must propagate
                sb.append(pad)
                  .append("        if (")
                  .append(oomElem)
                  .append(".isCutFailure()) {\n");
                sb.append(pad)
                  .append("            ")
                  .append(resultVar)
                  .append(" = ")
                  .append(oomElem)
                  .append(";\n");
                sb.append(pad)
                  .append("            break;\n");
                sb.append(pad)
                  .append("        }\n");
                sb.append(pad)
                  .append("        if (")
                  .append(oomElem)
                  .append(".isFailure() || pos == ")
                  .append(beforePos)
                  .append(") {\n");
                sb.append(pad)
                  .append("            restoreLocationRaw(")
                  .append(beforePos)
                  .append(", ")
                  .append(beforeLine)
                  .append(", ")
                  .append(beforeColumn)
                  .append(");\n");
                if (!config.triviaPostPass()) {
                    sb.append(pad)
                      .append("            restorePendingLeading(oomIterPending")
                      .append(id)
                      .append(");\n");
                }
                sb.append(pad)
                  .append("            break;\n");
                sb.append(pad)
                  .append("        }\n");
                sb.append(pad)
                  .append("    }\n");
                sb.append(pad)
                  .append("}\n");
                // Wrap collected children and restore parent children
                if (addToChildren) {
                    sb.append(pad)
                      .append("if (")
                      .append(resultVar)
                      .append(".isSuccess()) {\n");
                    sb.append(pad)
                      .append("    var oomChildren")
                      .append(id)
                      .append(" = new ArrayList<>(children);\n");
                    sb.append(pad)
                      .append("    children.clear();\n");
                    sb.append(pad)
                      .append("    children.addAll(")
                      .append(savedChildrenOom)
                      .append(");\n");
                    sb.append(pad)
                      .append("    if (oomChildren")
                      .append(id)
                      .append(".size() == 1) {\n");
                    sb.append(pad)
                      .append("        children.add(oomChildren")
                      .append(id)
                      .append(".getFirst());\n");
                    sb.append(pad)
                      .append("    } else if (!oomChildren")
                      .append(id)
                      .append(".isEmpty()) {\n");
                    sb.append(pad)
                      .append("        var oomSpan")
                      .append(id)
                      .append(" = new SourceSpan(")
                      .append(oomStartLine)
                      .append(", ")
                      .append(oomStartColumn)
                      .append(", ")
                      .append(oomStartPos)
                      .append(", line, column, pos);\n");
                    sb.append(pad)
                      .append("        children.add(new CstNode.NonTerminal(idGen.next(), oomSpan")
                      .append(id)
                      .append(", __ruleName, oomChildren")
                      .append(id)
                      .append(", List.of(), List.of()));\n");
                    sb.append(pad)
                      .append("    }\n");
                    sb.append(pad)
                      .append("} else {\n");
                    sb.append(pad)
                      .append("    children.clear();\n");
                    sb.append(pad)
                      .append("    children.addAll(")
                      .append(savedChildrenOom)
                      .append(");\n");
                    sb.append(pad)
                      .append("}\n");
                }
            }
            case Expression.Optional opt -> {
                // Phase 1.7 (D): inline int captures for restore-on-failure path.
                var optStartPos = "optStartPos" + id;
                var optStartLine = "optStartLine" + id;
                var optStartColumn = "optStartColumn" + id;
                var optElem = "optElem" + id;
                var savedChildrenOpt = "savedChildrenOpt" + id;
                sb.append(pad)
                  .append("int ")
                  .append(optStartPos)
                  .append(" = pos;\n");
                sb.append(pad)
                  .append("int ")
                  .append(optStartLine)
                  .append(" = line;\n");
                sb.append(pad)
                  .append("int ")
                  .append(optStartColumn)
                  .append(" = column;\n");
                sb.append(pad)
                  .append("int optPending")
                  .append(id)
                  .append(" = ")
                  .append(savePendingExpr())
                  .append(";\n");
                // Save parent children before inner expression
                if (addToChildren) {
                    sb.append(pad)
                      .append("var ")
                      .append(savedChildrenOpt)
                      .append(" = new ArrayList<>(children);\n");
                    sb.append(pad)
                      .append("children.clear();\n");
                }
                // No whitespace skip in Optional (matching interpreter behavior)
                generateCstExpressionCode(sb,
                                          opt.expression(),
                                          optElem,
                                          indent,
                                          addToChildren,
                                          counter,
                                          inWhitespaceRule);
                // CutFailure must propagate - don't treat as success
                sb.append(pad)
                  .append("CstParseResult ")
                  .append(resultVar)
                  .append(";\n");
                sb.append(pad)
                  .append("if (")
                  .append(optElem)
                  .append(".isCutFailure()) {\n");
                sb.append(pad)
                  .append("    restoreLocationRaw(")
                  .append(optStartPos)
                  .append(", ")
                  .append(optStartLine)
                  .append(", ")
                  .append(optStartColumn)
                  .append(");\n");
                if (!config.triviaPostPass()) {
                    sb.append(pad)
                      .append("    restorePendingLeading(optPending")
                      .append(id)
                      .append(");\n");
                }
                if (addToChildren) {
                    sb.append(pad)
                      .append("    children.clear();\n");
                    sb.append(pad)
                      .append("    children.addAll(")
                      .append(savedChildrenOpt)
                      .append(");\n");
                }
                sb.append(pad)
                  .append("    ")
                  .append(resultVar)
                  .append(" = ")
                  .append(optElem)
                  .append(";\n");
                sb.append(pad)
                  .append("} else if (")
                  .append(optElem)
                  .append(".isSuccess()) {\n");
                if (addToChildren) {
                    // Collect inner children, restore parent, add inner result
                    sb.append(pad)
                      .append("    var optChildren")
                      .append(id)
                      .append(" = new ArrayList<>(children);\n");
                    sb.append(pad)
                      .append("    children.clear();\n");
                    sb.append(pad)
                      .append("    children.addAll(")
                      .append(savedChildrenOpt)
                      .append(");\n");
                    // Add collected children — matching interpreter which returns inner result directly
                    sb.append(pad)
                      .append("    children.addAll(optChildren")
                      .append(id)
                      .append(");\n");
                }
                sb.append(pad)
                  .append("    ")
                  .append(resultVar)
                  .append(" = ")
                  .append(optElem)
                  .append(";\n");
                sb.append(pad)
                  .append("} else {\n");
                sb.append(pad)
                  .append("    restoreLocationRaw(")
                  .append(optStartPos)
                  .append(", ")
                  .append(optStartLine)
                  .append(", ")
                  .append(optStartColumn)
                  .append(");\n");
                if (!config.triviaPostPass()) {
                    sb.append(pad)
                      .append("    restorePendingLeading(optPending")
                      .append(id)
                      .append(");\n");
                }
                if (addToChildren) {
                    sb.append(pad)
                      .append("    children.clear();\n");
                    sb.append(pad)
                      .append("    children.addAll(")
                      .append(savedChildrenOpt)
                      .append(");\n");
                }
                sb.append(pad)
                  .append("    ")
                  .append(resultVar)
                  .append(" = CstParseResult.successNoLoc(null, \"\");\n");
                sb.append(pad)
                  .append("}\n");
            }
            case Expression.Repetition rep -> {
                // Phase 1.7 (D): inline int captures — no SourceLocation per iteration.
                var repCount = "repCount" + id;
                var repStartPos = "repStartPos" + id;
                var repStartLine = "repStartLine" + id;
                var repStartColumn = "repStartColumn" + id;
                var beforePos = "beforePos" + id;
                var beforeLine = "beforeLine" + id;
                var beforeColumn = "beforeColumn" + id;
                var repElem = "repElem" + id;
                var repCutFailed = "repCutFailed" + id;
                var savedChildrenRep = "savedChildrenRep" + id;
                sb.append(pad)
                  .append("int ")
                  .append(repCount)
                  .append(" = 0;\n");
                sb.append(pad)
                  .append("int ")
                  .append(repStartPos)
                  .append(" = pos;\n");
                sb.append(pad)
                  .append("int ")
                  .append(repStartLine)
                  .append(" = line;\n");
                sb.append(pad)
                  .append("int ")
                  .append(repStartColumn)
                  .append(" = column;\n");
                sb.append(pad)
                  .append("CstParseResult ")
                  .append(repCutFailed)
                  .append(" = null;\n");
                // Save parent children and collect loop children separately
                if (addToChildren) {
                    sb.append(pad)
                      .append("var ")
                      .append(savedChildrenRep)
                      .append(" = new ArrayList<>(children);\n");
                    sb.append(pad)
                      .append("children.clear();\n");
                }
                var maxStr = rep.max()
                                .isPresent()
                             ? String.valueOf(rep.max()
                                                 .unwrap())
                             : "Integer.MAX_VALUE";
                sb.append(pad)
                  .append("while (")
                  .append(repCount)
                  .append(" < ")
                  .append(maxStr)
                  .append(") {\n");
                sb.append(pad)
                  .append("    int ")
                  .append(beforePos)
                  .append(" = pos;\n");
                sb.append(pad)
                  .append("    int ")
                  .append(beforeLine)
                  .append(" = line;\n");
                sb.append(pad)
                  .append("    int ")
                  .append(beforeColumn)
                  .append(" = column;\n");
                sb.append(pad)
                  .append("    int repIterPending")
                  .append(id)
                  .append(" = ")
                  .append(savePendingExpr())
                  .append(";\n");
                if (!inWhitespaceRule) {
                    sb.append(pad)
                      .append("    if (")
                      .append(repCount)
                      .append(config.triviaPostPass()
                              ? " > 0 && tokenBoundaryDepth == 0) skipWhitespace();\n"
                              : " > 0 && tokenBoundaryDepth == 0) appendPending(skipWhitespace());\n");
                }
                generateCstExpressionCode(sb,
                                          rep.expression(),
                                          repElem,
                                          indent + 1,
                                          addToChildren,
                                          counter,
                                          inWhitespaceRule);
                // CutFailure must propagate
                sb.append(pad)
                  .append("    if (")
                  .append(repElem)
                  .append(".isCutFailure()) {\n");
                sb.append(pad)
                  .append("        ")
                  .append(repCutFailed)
                  .append(" = ")
                  .append(repElem)
                  .append(";\n");
                sb.append(pad)
                  .append("        break;\n");
                sb.append(pad)
                  .append("    }\n");
                sb.append(pad)
                  .append("    if (")
                  .append(repElem)
                  .append(".isFailure() || pos == ")
                  .append(beforePos)
                  .append(") {\n");
                sb.append(pad)
                  .append("        restoreLocationRaw(")
                  .append(beforePos)
                  .append(", ")
                  .append(beforeLine)
                  .append(", ")
                  .append(beforeColumn)
                  .append(");\n");
                if (!config.triviaPostPass()) {
                    sb.append(pad)
                      .append("        restorePendingLeading(repIterPending")
                      .append(id)
                      .append(");\n");
                }
                sb.append(pad)
                  .append("        break;\n");
                sb.append(pad)
                  .append("    }\n");
                sb.append(pad)
                  .append("    ")
                  .append(repCount)
                  .append("++;\n");
                sb.append(pad)
                  .append("}\n");
                sb.append(pad)
                  .append("CstParseResult ")
                  .append(resultVar)
                  .append(";\n");
                sb.append(pad)
                  .append("if (")
                  .append(repCutFailed)
                  .append(" != null) {\n");
                if (addToChildren) {
                    sb.append(pad)
                      .append("    children.clear();\n");
                    sb.append(pad)
                      .append("    children.addAll(")
                      .append(savedChildrenRep)
                      .append(");\n");
                }
                sb.append(pad)
                  .append("    ")
                  .append(resultVar)
                  .append(" = ")
                  .append(repCutFailed)
                  .append(";\n");
                sb.append(pad)
                  .append("} else if (")
                  .append(repCount)
                  .append(" >= ")
                  .append(rep.min())
                  .append(") {\n");
                if (addToChildren) {
                    sb.append(pad)
                      .append("    var repChildren")
                      .append(id)
                      .append(" = new ArrayList<>(children);\n");
                    sb.append(pad)
                      .append("    children.clear();\n");
                    sb.append(pad)
                      .append("    children.addAll(")
                      .append(savedChildrenRep)
                      .append(");\n");
                    sb.append(pad)
                      .append("    if (repChildren")
                      .append(id)
                      .append(".size() == 1) {\n");
                    sb.append(pad)
                      .append("        children.add(repChildren")
                      .append(id)
                      .append(".getFirst());\n");
                    sb.append(pad)
                      .append("    } else if (!repChildren")
                      .append(id)
                      .append(".isEmpty()) {\n");
                    sb.append(pad)
                      .append("        var repSpan")
                      .append(id)
                      .append(" = new SourceSpan(")
                      .append(repStartLine)
                      .append(", ")
                      .append(repStartColumn)
                      .append(", ")
                      .append(repStartPos)
                      .append(", line, column, pos);\n");
                    sb.append(pad)
                      .append("        children.add(new CstNode.NonTerminal(idGen.next(), repSpan")
                      .append(id)
                      .append(", __ruleName, repChildren")
                      .append(id)
                      .append(", List.of(), List.of()));\n");
                    sb.append(pad)
                      .append("    }\n");
                }
                sb.append(pad)
                  .append("    ")
                  .append(resultVar)
                  .append(" = CstParseResult.successNoLoc(null, substring(")
                  .append(repStartPos)
                  .append(", pos));\n");
                sb.append(pad)
                  .append("} else {\n");
                if (addToChildren) {
                    sb.append(pad)
                      .append("    children.clear();\n");
                    sb.append(pad)
                      .append("    children.addAll(")
                      .append(savedChildrenRep)
                      .append(");\n");
                }
                sb.append(pad)
                  .append("    restoreLocationRaw(")
                  .append(repStartPos)
                  .append(", ")
                  .append(repStartLine)
                  .append(", ")
                  .append(repStartColumn)
                  .append(");\n");
                sb.append(pad)
                  .append("    ")
                  .append(resultVar)
                  .append(" = CstParseResult.failure(\"at least ")
                  .append(rep.min())
                  .append(" repetitions\");\n");
                sb.append(pad)
                  .append("}\n");
            }
            case Expression.And and -> {
                // Phase 1.7 (D): inline int captures for predicate restore.
                var andStartPos = "andStartPos" + id;
                var andStartLine = "andStartLine" + id;
                var andStartColumn = "andStartColumn" + id;
                var savedChildren = "savedChildrenAnd" + id;
                var andElem = "andElem" + id;
                sb.append(pad)
                  .append("int ")
                  .append(andStartPos)
                  .append(" = pos;\n");
                sb.append(pad)
                  .append("int ")
                  .append(andStartLine)
                  .append(" = line;\n");
                sb.append(pad)
                  .append("int ")
                  .append(andStartColumn)
                  .append(" = column;\n");
                if (!config.triviaPostPass()) {
                    sb.append(pad)
                      .append("int andPending")
                      .append(id)
                      .append(" = savePendingLeading();\n");
                }
                if (addToChildren) {
                    sb.append(pad)
                      .append("var ")
                      .append(savedChildren)
                      .append(" = new ArrayList<>(children);\n");
                }
                generateCstExpressionCode(sb, and.expression(), andElem, indent, false, counter, inWhitespaceRule);
                sb.append(pad)
                  .append("restoreLocationRaw(")
                  .append(andStartPos)
                  .append(", ")
                  .append(andStartLine)
                  .append(", ")
                  .append(andStartColumn)
                  .append(");\n");
                // Predicates don't consume trivia state either.
                if (!config.triviaPostPass()) {
                    sb.append(pad)
                      .append("restorePendingLeading(andPending")
                      .append(id)
                      .append(");\n");
                }
                if (addToChildren) {
                    sb.append(pad)
                      .append("children.clear();\n");
                    sb.append(pad)
                      .append("children.addAll(")
                      .append(savedChildren)
                      .append(");\n");
                }
                sb.append(pad)
                  .append("var ")
                  .append(resultVar)
                  .append(" = ")
                  .append(andElem)
                  .append(".isSuccess() ? CstParseResult.successNoLoc(null, \"\") : ")
                  .append(andElem)
                  .append(";\n");
            }
            case Expression.Not not -> {
                // Phase 1.7 (D): inline int captures for predicate restore.
                var notStartPos = "notStartPos" + id;
                var notStartLine = "notStartLine" + id;
                var notStartColumn = "notStartColumn" + id;
                var savedChildren = "savedChildrenNot" + id;
                var notElem = "notElem" + id;
                sb.append(pad)
                  .append("int ")
                  .append(notStartPos)
                  .append(" = pos;\n");
                sb.append(pad)
                  .append("int ")
                  .append(notStartLine)
                  .append(" = line;\n");
                sb.append(pad)
                  .append("int ")
                  .append(notStartColumn)
                  .append(" = column;\n");
                if (!config.triviaPostPass()) {
                    sb.append(pad)
                      .append("int notPending")
                      .append(id)
                      .append(" = savePendingLeading();\n");
                }
                if (addToChildren) {
                    sb.append(pad)
                      .append("var ")
                      .append(savedChildren)
                      .append(" = new ArrayList<>(children);\n");
                }
                generateCstExpressionCode(sb, not.expression(), notElem, indent, false, counter, inWhitespaceRule);
                sb.append(pad)
                  .append("restoreLocationRaw(")
                  .append(notStartPos)
                  .append(", ")
                  .append(notStartLine)
                  .append(", ")
                  .append(notStartColumn)
                  .append(");\n");
                if (!config.triviaPostPass()) {
                    sb.append(pad)
                      .append("restorePendingLeading(notPending")
                      .append(id)
                      .append(");\n");
                }
                if (addToChildren) {
                    sb.append(pad)
                      .append("children.clear();\n");
                    sb.append(pad)
                      .append("children.addAll(")
                      .append(savedChildren)
                      .append(");\n");
                }
                sb.append(pad)
                  .append("var ")
                  .append(resultVar)
                  .append(" = ")
                  .append(notElem)
                  .append(".isSuccess() ? CstParseResult.failure(\"not match\") : CstParseResult.successNoLoc(null, \"\");\n");
            }
            case Expression.TokenBoundary tb -> {
                // Phase 1.9 (DFA spike): fast-path detection for token-shaped rules
                // — emit a tight inline character scanner instead of the framework's
                // matchCharClassCst loop. Triggered only when (a) the flag is on,
                // (b) the inner expression is Sequence(CharClass, ZeroOrMore(CharClass))
                // (with optional Group wrappers), and (c) both classes are pure ASCII
                // (non-case-insensitive, no escapes that escape ASCII). Failures fall
                // through to the slow path identically.
                if (config.tokenFastPath() && isFastPathTokenBoundary(tb)) {
                    emitCstFastPathTokenBoundary(sb, tb, resultVar, indent, addToChildren, id, inWhitespaceRule);
                    break;
                }
                // Phase 1.7 (D): inline int captures — avoid SourceLocation per token.
                var tbStartPos = "tbStartPos" + id;
                var tbStartLine = "tbStartLine" + id;
                var tbStartColumn = "tbStartColumn" + id;
                var savedChildren = "savedChildrenTb" + id;
                var tbElem = "tbElem" + id;
                sb.append(pad)
                  .append("int ")
                  .append(tbStartPos)
                  .append(" = pos;\n");
                sb.append(pad)
                  .append("int ")
                  .append(tbStartLine)
                  .append(" = line;\n");
                sb.append(pad)
                  .append("int ")
                  .append(tbStartColumn)
                  .append(" = column;\n");
                sb.append(pad)
                  .append("tokenBoundaryDepth++;\n");
                if (addToChildren) {
                    sb.append(pad)
                      .append("var ")
                      .append(savedChildren)
                      .append(" = new ArrayList<>(children);\n");
                }
                generateCstExpressionCode(sb, tb.expression(), tbElem, indent, false, counter, inWhitespaceRule);
                sb.append(pad)
                  .append("tokenBoundaryDepth--;\n");
                if (addToChildren) {
                    sb.append(pad)
                      .append("children.clear();\n");
                    sb.append(pad)
                      .append("children.addAll(")
                      .append(savedChildren)
                      .append(");\n");
                }
                sb.append(pad)
                  .append("CstParseResult ")
                  .append(resultVar)
                  .append(";\n");
                sb.append(pad)
                  .append("if (")
                  .append(tbElem)
                  .append(".isSuccess()) {\n");
                sb.append(pad)
                  .append("    var tbText")
                  .append(id)
                  .append(" = substring(")
                  .append(tbStartPos)
                  .append(", pos);\n");
                sb.append(pad)
                  .append("    var tbSpan")
                  .append(id)
                  .append(" = new SourceSpan(")
                  .append(tbStartLine)
                  .append(", ")
                  .append(tbStartColumn)
                  .append(", ")
                  .append(tbStartPos)
                  .append(", line, column, pos);\n");
                var tokenRuleId = inWhitespaceRule
                                  ? "RULE_PEG_TOKEN"
                                  : "__ruleName";
                sb.append(pad)
                  .append("    var tbNode")
                  .append(id)
                  .append(" = new CstNode.Token(idGen.next(), tbSpan")
                  .append(id)
                  .append(", ")
                  .append(tokenRuleId)
                  .append(", tbText")
                  .append(id)
                  .append(", ")
                  .append(takePendingExpr())
                  .append(", List.of());\n");
                if (addToChildren) {
                    sb.append(pad)
                      .append("    children.add(tbNode")
                      .append(id)
                      .append(");\n");
                }
                sb.append(pad)
                  .append("    ")
                  .append(resultVar)
                  .append(" = CstParseResult.successNoLoc(tbNode")
                  .append(id)
                  .append(", tbText")
                  .append(id)
                  .append(");\n");
                sb.append(pad)
                  .append("} else {\n");
                sb.append(pad)
                  .append("    ")
                  .append(resultVar)
                  .append(" = ")
                  .append(tbElem)
                  .append(";\n");
                sb.append(pad)
                  .append("}\n");
            }
            case Expression.Ignore ign -> {
                var savedChildren = "savedChildrenIgn" + id;
                var ignElem = "ignElem" + id;
                if (addToChildren) {
                    sb.append(pad)
                      .append("var ")
                      .append(savedChildren)
                      .append(" = new ArrayList<>(children);\n");
                }
                generateCstExpressionCode(sb, ign.expression(), ignElem, indent, false, counter, inWhitespaceRule);
                if (addToChildren) {
                    sb.append(pad)
                      .append("children.clear();\n");
                    sb.append(pad)
                      .append("children.addAll(")
                      .append(savedChildren)
                      .append(");\n");
                }
                sb.append(pad)
                  .append("var ")
                  .append(resultVar)
                  .append(" = ")
                  .append(ignElem)
                  .append(".isSuccess() ? CstParseResult.successNoLoc(null, \"\") : ")
                  .append(ignElem)
                  .append(";\n");
            }
            case Expression.Capture cap -> {
                // Phase 1.7 (D): inline int capture — only pos is needed (substring offset).
                var capStartPos = "capStartPos" + id;
                var capElem = "capElem" + id;
                sb.append(pad)
                  .append("int ")
                  .append(capStartPos)
                  .append(" = pos;\n");
                generateCstExpressionCode(sb,
                                          cap.expression(),
                                          capElem,
                                          indent,
                                          addToChildren,
                                          counter,
                                          inWhitespaceRule);
                sb.append(pad)
                  .append("if (")
                  .append(capElem)
                  .append(".isSuccess()) {\n");
                sb.append(pad)
                  .append("    captures.put(\"")
                  .append(cap.name())
                  .append("\", substring(")
                  .append(capStartPos)
                  .append(", pos));\n");
                sb.append(pad)
                  .append("}\n");
                sb.append(pad)
                  .append("var ")
                  .append(resultVar)
                  .append(" = ")
                  .append(capElem)
                  .append(";\n");
            }
            case Expression.CaptureScope cs -> {
                var savedCapturesVar = "savedCaptures" + id;
                var csElem = "csElem" + id;
                sb.append(pad)
                  .append("var ")
                  .append(savedCapturesVar)
                  .append(" = new HashMap<>(captures);\n");
                generateCstExpressionCode(sb, cs.expression(), csElem, indent, addToChildren, counter, inWhitespaceRule);
                sb.append(pad)
                  .append("captures.clear();\n");
                sb.append(pad)
                  .append("captures.putAll(")
                  .append(savedCapturesVar)
                  .append(");\n");
                sb.append(pad)
                  .append("var ")
                  .append(resultVar)
                  .append(" = ")
                  .append(csElem)
                  .append(";\n");
            }
            case Expression.BackReference br -> {
                var captured = "captured" + id;
                sb.append(pad)
                  .append("var ")
                  .append(captured)
                  .append(" = captures.get(\"")
                  .append(br.name())
                  .append("\");\n");
                sb.append(pad)
                  .append("var ")
                  .append(resultVar)
                  .append(" = ")
                  .append(captured)
                  .append(" != null ? matchLiteralCst(")
                  .append(captured)
                  .append(", false) : CstParseResult.failure(\"capture '\");\n");
                if (addToChildren) {
                    sb.append(pad)
                      .append("if (")
                      .append(resultVar)
                      .append(".isSuccess() && ")
                      .append(resultVar)
                      .append(config.mutableParseResult()
                              ? ".node != null) {\n"
                              : ".node.isPresent()) {\n");
                    sb.append(pad)
                      .append("    children.add(")
                      .append(resultVar)
                      .append(config.mutableParseResult()
                              ? ".node);\n"
                              : ".node.unwrap());\n");
                    sb.append(pad)
                      .append("}\n");
                }
            }
            case Expression.Cut cut -> {
                sb.append(pad)
                  .append("var ")
                  .append(resultVar)
                  .append(" = CstParseResult.successNoLoc(null, \"\");\n");
            }
            case Expression.Group grp -> {
                generateCstExpressionCode(sb,
                                          grp.expression(),
                                          resultVar,
                                          indent,
                                          addToChildren,
                                          counter,
                                          inWhitespaceRule);
            }
        }
    }

    /**
     * Emit one alternative's body in the slow-chain CST Choice format. Opens an
     * {@code if/else if/else} that leaves an open {@code else} brace for the caller
     * (or the next alt) to nest inside — closing braces are emitted in a loop by
     * the Choice block. See §7.1 for the dispatch variant.
     */
    private void emitCstChoiceAlt(StringBuilder sb,
                                  Expression alt,
                                  int id,
                                  int origIndex,
                                  int indent,
                                  boolean addToChildren,
                                  int[] counter,
                                  boolean inWhitespaceRule,
                                  String resultVar,
                                  String choiceStart,
                                  String childrenState,
                                  String pad) {
        emitChildrenRestore(sb, pad, childrenState, addToChildren);
        var altVar = "alt" + id + "_" + origIndex;
        generateCstExpressionCode(sb, alt, altVar, indent, addToChildren, counter, inWhitespaceRule);
        sb.append(pad)
          .append("if (")
          .append(altVar)
          .append(".isSuccess()) {\n");
        sb.append(pad)
          .append("    ")
          .append(resultVar)
          .append(" = ")
          .append(altVar)
          .append(";\n");
        sb.append(pad)
          .append("} else if (")
          .append(altVar)
          .append(".isCutFailure()) {\n");
        // Convert CutFailure to regular failure so enclosing choices can still fail-forward.
        sb.append(pad)
          .append("    ")
          .append(resultVar)
          .append(" = ")
          .append(altVar)
          .append(".asRegularFailure();\n");
        sb.append(pad)
          .append("} else {\n");
        sb.append(pad)
          .append("    restoreLocationRaw(")
          .append(choiceStart)
          .append("Pos, ")
          .append(choiceStart)
          .append("Line, ")
          .append(choiceStart)
          .append("Column);\n");
        // Roll back any pending-leading trivia captured inside the failed alt,
        // so sibling alternatives start from the same buffer state as the Choice entry.
        if (!config.triviaPostPass()) {
            sb.append(pad)
              .append("    restorePendingLeading(choicePending")
              .append(id)
              .append(");\n");
        }
    }

    /**
     * Emit a per-alternative chain (same shape as {@link #emitCstChoiceAlt}) self-closing:
     * declares all {@code if/else if/else} braces and closes them before returning. Used
     * inside each {@code case} block of the dispatch switch, where control never falls
     * through to a sibling case.
     */
    private void emitCstChoiceAltChainClosed(StringBuilder sb,
                                             java.util.List<ChoiceDispatchAnalyzer.AltEntry> alts,
                                             int id,
                                             int indent,
                                             boolean addToChildren,
                                             int[] counter,
                                             boolean inWhitespaceRule,
                                             String resultVar,
                                             String choiceStart,
                                             String childrenState,
                                             String pad) {
        for (var entry : alts) {
            emitCstChoiceAlt(sb,
                             entry.alt(),
                             id,
                             entry.originalIndex(),
                             indent,
                             addToChildren,
                             counter,
                             inWhitespaceRule,
                             resultVar,
                             choiceStart,
                             childrenState,
                             pad);
        }
        for (int j = 0; j < alts.size(); j++ ) {
            sb.append(pad)
              .append("}\n");
        }
    }

    /**
     * Emit the §7.1 character-dispatch variant for a classifiable Choice. Per-bucket
     * case-blocks try their (one or more) alternatives using the same if/else-if/else
     * chain as the slow path. When no alt in the matched bucket succeeds, {@code resultVar}
     * stays null and control flows to the standard "all failed" epilogue emitted by the
     * caller.
     */
    private void emitCstChoiceDispatch(StringBuilder sb,
                                       java.util.List<ChoiceDispatchAnalyzer.DispatchBucket> buckets,
                                       int id,
                                       int indent,
                                       boolean addToChildren,
                                       int[] counter,
                                       boolean inWhitespaceRule,
                                       String resultVar,
                                       String choiceStart,
                                       String childrenState) {
        var pad = "    ".repeat(indent);
        var dispatchVar = "dispatchChar" + id;
        sb.append(pad)
          .append("if (pos < input.length()) {\n");
        sb.append(pad)
          .append("    char ")
          .append(dispatchVar)
          .append(" = input.charAt(pos);\n");
        sb.append(pad)
          .append("    switch (")
          .append(dispatchVar)
          .append(") {\n");
        var casePad = pad + "        ";
        var bodyPad = pad + "            ";
        for (var bucket : buckets) {
            for (var c : bucket.chars()) {
                sb.append(casePad)
                  .append("case ")
                  .append(literalChar(c))
                  .append(":\n");
            }
            sb.append(casePad)
              .append("{\n");
            emitCstChoiceAltChainClosed(sb,
                                        bucket.alts(),
                                        id,
                                        indent + 3,
                                        addToChildren,
                                        counter,
                                        inWhitespaceRule,
                                        resultVar,
                                        choiceStart,
                                        childrenState,
                                        bodyPad);
            sb.append(bodyPad)
              .append("break;\n");
            sb.append(casePad)
              .append("}\n");
        }
        sb.append(pad)
          .append("    }\n");
        sb.append(pad)
          .append("}\n");
    }

    /**
     * §7.1F: Phase 1F dispatch emission supporting partial dispatch with default fallback.
     * Emits a switch over {@code input.charAt(pos)} where each case contains the dispatched
     * alts that match that char (in PEG order); after a case's dispatched alts all fail, the
     * default tail (alternatives with unknown FIRST) is tried in PEG order. The {@code default:}
     * branch runs ONLY the default tail.
     *
     * <p>PEG-correctness invariant: defaults form a contiguous tail of the original alternative
     * list (enforced by {@link ChoiceDispatchAnalyzer#classify(Expression.Choice, ChoiceDispatchAnalyzer.FirstSets)}).
     * This means every dispatched alt has a smaller original-index than every default alt, so
     * trying dispatched alts first preserves PEG ordered-choice semantics.
     */
    private void emitCstChoiceDispatchF(StringBuilder sb,
                                        java.util.List<ChoiceDispatchAnalyzer.DispatchBucket> buckets,
                                        java.util.List<ChoiceDispatchAnalyzer.AltEntry> defaults,
                                        int id,
                                        int indent,
                                        boolean addToChildren,
                                        int[] counter,
                                        boolean inWhitespaceRule,
                                        String resultVar,
                                        String choiceStart,
                                        String childrenState) {
        var pad = "    ".repeat(indent);
        var dispatchVar = "dispatchChar" + id;
        // Generate a small inner method-style block: "if (pos < input.length()) { switch ... }"
        // followed (only if defaults present) by an unconditional "if (resultVar == null)" tail
        // running the defaults in PEG order. When pos is at EOF, only defaults run (any literal-
        // bearing dispatched alt would fail on EOF anyway, so this preserves correctness).
        sb.append(pad)
          .append("if (pos < input.length()) {\n");
        sb.append(pad)
          .append("    char ")
          .append(dispatchVar)
          .append(" = input.charAt(pos);\n");
        sb.append(pad)
          .append("    switch (")
          .append(dispatchVar)
          .append(") {\n");
        var casePad = pad + "        ";
        var bodyPad = pad + "            ";
        for (var bucket : buckets) {
            for (var c : bucket.chars()) {
                sb.append(casePad)
                  .append("case ")
                  .append(literalChar(c))
                  .append(":\n");
            }
            sb.append(casePad)
              .append("{\n");
            emitCstChoiceAltChainClosed(sb,
                                        bucket.alts(),
                                        id,
                                        indent + 3,
                                        addToChildren,
                                        counter,
                                        inWhitespaceRule,
                                        resultVar,
                                        choiceStart,
                                        childrenState,
                                        bodyPad);
            sb.append(bodyPad)
              .append("break;\n");
            sb.append(casePad)
              .append("}\n");
        }
        sb.append(pad)
          .append("    }\n");
        sb.append(pad)
          .append("}\n");
        // Default fallback: run after switch (if no dispatched alt succeeded) AND on EOF / unmatched
        // dispatch char. Must restore choiceStart/children/pending-leading because a dispatched alt
        // may have advanced state before failing.
        if (!defaults.isEmpty()) {
            sb.append(pad)
              .append("if (")
              .append(resultVar)
              .append(" == null) {\n");
            // Restore parser position so default alts start from the choice's entry point.
            sb.append(pad)
              .append("    restoreLocationRaw(")
              .append(choiceStart)
              .append("Pos, ")
              .append(choiceStart)
              .append("Line, ")
              .append(choiceStart)
              .append("Column);\n");
            if (!config.triviaPostPass()) {
                sb.append(pad)
                  .append("    restorePendingLeading(choicePending")
                  .append(id)
                  .append(");\n");
            }
            emitCstChoiceAltChainClosed(sb,
                                        defaults,
                                        id,
                                        indent + 1,
                                        addToChildren,
                                        counter,
                                        inWhitespaceRule,
                                        resultVar,
                                        choiceStart,
                                        childrenState,
                                        pad + "    ");
            sb.append(pad)
              .append("}\n");
        }
    }

    /**
     * §7.2: Emit the per-Choice child-state save. With {@code markResetChildren} off,
     * clones {@code children} into an ArrayList (legacy path). With the flag on, records
     * only the current size as an int mark — O(1) save, no copy.
     */
    private void emitChildrenSave(StringBuilder sb, String pad, String stateVar, boolean addToChildren) {
        if (!addToChildren) return;
        if (config.markResetChildren()) {
            sb.append(pad)
              .append("int ")
              .append(stateVar)
              .append(" = children.size();\n");
        }else {
            sb.append(pad)
              .append("var ")
              .append(stateVar)
              .append(" = new ArrayList<>(children);\n");
        }
    }

    /**
     * §7.2: Emit the per-Choice child-state restore. With {@code markResetChildren} off,
     * clears and re-populates {@code children} from the saved ArrayList (legacy path).
     * With the flag on, trims {@code children} back to the recorded mark using
     * {@code subList(mark, size).clear()} — O(delta) restore, no full-list addAll.
     */
    private void emitChildrenRestore(StringBuilder sb, String pad, String stateVar, boolean addToChildren) {
        if (!addToChildren) return;
        if (config.markResetChildren()) {
            sb.append(pad)
              .append("if (children.size() > ")
              .append(stateVar)
              .append(") {\n");
            sb.append(pad)
              .append("    children.subList(")
              .append(stateVar)
              .append(", children.size()).clear();\n");
            sb.append(pad)
              .append("}\n");
        }else {
            sb.append(pad)
              .append("children.clear();\n");
            sb.append(pad)
              .append("children.addAll(")
              .append(stateVar)
              .append(");\n");
        }
    }

    private static String literalChar(char c) {
        return switch (c) {
            case'\n' -> "'\\n'";
            case'\r' -> "'\\r'";
            case'\t' -> "'\\t'";
            case'\\' -> "'\\\\'";
            case'\'' -> "'\\''";
            default -> "'" + c + "'";
        };
    }

    private void generateCstHelperMethods(StringBuilder sb) {
        sb.append("""
                // === Helper Methods ===

                private List<Trivia> skipWhitespace() {
            """);
        // §6.6 fast-path: short-circuit when the current char cannot start trivia.
        // Only emitted when the flag is on AND the grammar has a %whitespace rule
        // AND the inner expression is analyzable (otherwise fall through to the
        // existing unconditional slow-path setup).
        if (config.skipWhitespaceFastPath() && grammar.whitespace()
                                                      .isPresent()) {
            var wsInner = extractInnerExpression(grammar.whitespace()
                                                        .unwrap());
            var charsOpt = whitespaceFirstChars(wsInner);
            if (charsOpt.isPresent()) {
                var chars = charsOpt.unwrap();
                sb.append("        if (skippingWhitespace || tokenBoundaryDepth > 0 || pos >= input.length()) return List.of();\n");
                sb.append("        char c = input.charAt(pos);\n");
                sb.append("        if (")
                  .append(renderNotInSetCheck(chars))
                  .append(") return List.of();\n");
            }
        }
        sb.append("""
                    var trivia = new ArrayList<Trivia>();
                    if (skippingWhitespace || tokenBoundaryDepth > 0) return trivia;
                    skippingWhitespace = true;
                    try {
            """);
        if (grammar.whitespace()
                   .isPresent()) {
            // Extract inner expression from ZeroOrMore/OneOrMore to match one element at a time
            var wsExpr = grammar.whitespace()
                                .unwrap();
            var innerExpr = extractInnerExpression(wsExpr);
            sb.append("        while (!isAtEnd()) {\n");
            // Phase 1.7 (D): inline int captures — no SourceLocation allocation per trivia element.
            sb.append("            int wsStartLine = line;\n");
            sb.append("            int wsStartColumn = column;\n");
            sb.append("            int wsStartPos = pos;\n");
            generateCstExpressionCode(sb, innerExpr, "wsResult", 3, false, new int[]{0}, true);
            sb.append("            if (wsResult.isFailure() || pos == wsStartPos) break;\n");
            sb.append("            var wsText = substring(wsStartPos, pos);\n");
            sb.append("            var wsSpan = new SourceSpan(wsStartLine, wsStartColumn, wsStartPos, line, column, pos);\n");
            sb.append("            trivia.add(classifyTrivia(wsSpan, wsText));\n");
            sb.append("        }\n");
        }
        sb.append("""
                    } finally {
                        skippingWhitespace = false;
                    }
                    return trivia;
                }

                private CstNode wrapWithRuleName(CstParseResult result, List<CstNode> children, SourceSpan span, RuleId ruleId, List<Trivia> leadingTrivia) {
                    // If result produced a single node (Token or Terminal), re-wrap with rule name and trivia
                    // This matches PegEngine.wrapWithRuleName behavior
                    if (__NODE_PRESENT__) {
                        var inner = __NODE_UNWRAP__;
                        return switch (inner) {
                            case CstNode.Token tok -> new CstNode.Token(tok.id(), span, ruleId, tok.text(), leadingTrivia, List.of());
                            case CstNode.Terminal t -> new CstNode.Terminal(t.id(), span, ruleId, t.text(), leadingTrivia, List.of());
                            case CstNode.NonTerminal nt -> new CstNode.NonTerminal(nt.id(), span, ruleId, nt.children(), leadingTrivia, List.of());
            """.replace("__NODE_PRESENT__",
                        config.mutableParseResult()
                        ? "result.node != null"
                        : "result.node.isPresent()")
                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                .replace("__NODE_UNWRAP__",
                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                         config.mutableParseResult()
                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                         ? "result.node"
                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                         : "result.node.unwrap()"));
        // Add Error case only for ADVANCED mode
        if (errorReporting == ErrorReporting.ADVANCED) {
            sb.append("""
                            case CstNode.Error err -> new CstNode.NonTerminal(err.id(), span, ruleId, children, leadingTrivia, List.of());
                """);
        }
        sb.append("""
                        };
                    }
                    // No inner node — wrap children in NonTerminal
                    return new CstNode.NonTerminal(idGen.next(), span, ruleId, children, leadingTrivia, List.of());
                }

                private Trivia classifyTrivia(SourceSpan span, String text) {
                    if (text.startsWith("//")) {
                        return new Trivia.LineComment(span, text);
                    } else if (text.startsWith("/*")) {
                        return new Trivia.BlockComment(span, text);
                    } else {
                        return new Trivia.Whitespace(span, text);
                    }
                }

                // === Pending-leading trivia helpers ===
                // See docs/TRIVIA-ATTRIBUTION.md for the attribution rule.

            """);
        // Step 4 commit 5: Under triviaPostPass=true, the post-pass produces correct
        // attribution from the trivia stream. The buffer machinery is dead work — the
        // call sites still call these helpers, but the helpers no-op (and the buffer
        // field stays declared but unused — see PR description for rationale).
        if (config.triviaPostPass()) {
            sb.append("""
                                private void appendPending(List<Trivia> captured) {
                                    // no-op: triviaPostPass=true; attribution handled by post-pass.
                                }

                                private List<Trivia> takePendingLeading() {
                                    return List.of();
                                }

                                private int savePendingLeading() {
                                    return 0;
                                }

                                private void restorePendingLeading(int snapshot) {
                                    // no-op: triviaPostPass=true; attribution handled by post-pass.
                                }

                            """);
        }else {
            sb.append("""
                                private void appendPending(List<Trivia> captured) {
                                    if (!captured.isEmpty()) {
                                        pendingLeadingTrivia.addAll(captured);
                                    }
                                }

                                private List<Trivia> takePendingLeading() {
                                    if (pendingLeadingTrivia.isEmpty()) {
                                        return List.of();
                                    }
                                    var snapshot = List.copyOf(pendingLeadingTrivia);
                                    pendingLeadingTrivia.clear();
                                    return snapshot;
                                }

                                private int savePendingLeading() {
                                    return pendingLeadingTrivia.size();
                                }

                                private void restorePendingLeading(int snapshot) {
                                    if (pendingLeadingTrivia.size() > snapshot) {
                                        pendingLeadingTrivia.subList(snapshot, pendingLeadingTrivia.size()).clear();
                                    }
                                }

                            """);
        }
        sb.append("""
                private List<Trivia> concatTrivia(List<Trivia> first, List<Trivia> second) {
                    if (first.isEmpty()) return second;
                    if (second.isEmpty()) return first;
                    var combined = new ArrayList<Trivia>(first.size() + second.size());
                    combined.addAll(first);
                    combined.addAll(second);
                    return List.copyOf(combined);
                }

                private CstNode attachLeadingTrivia(CstNode node, List<Trivia> leadingTrivia) {
                    if (leadingTrivia.isEmpty()) {
                        return node;
                    }
                    return switch (node) {
                        case CstNode.Terminal t -> new CstNode.Terminal(
                            t.id(), t.span(), t.rule(), t.text(), leadingTrivia, t.trailingTrivia()
                        );
                        case CstNode.NonTerminal nt -> new CstNode.NonTerminal(
                            nt.id(), nt.span(), nt.rule(), nt.children(), leadingTrivia, nt.trailingTrivia()
                        );
                        case CstNode.Token tok -> new CstNode.Token(
                            tok.id(), tok.span(), tok.rule(), tok.text(), leadingTrivia, tok.trailingTrivia()
                        );
            """);
        if (errorReporting == ErrorReporting.ADVANCED) {
            sb.append("""
                            case CstNode.Error err -> new CstNode.Error(
                                err.id(), err.span(), err.skippedText(), err.expected(), leadingTrivia, err.trailingTrivia()
                            );
                """);
        }
        sb.append("""
                    };
                }

                private CstNode attachTrailingTrivia(CstNode node, List<Trivia> trailingTrivia) {
                    if (trailingTrivia.isEmpty()) {
                        return node;
                    }
                    return switch (node) {
                        case CstNode.Terminal t -> new CstNode.Terminal(
                            t.id(), t.span(), t.rule(), t.text(), t.leadingTrivia(), trailingTrivia
                        );
                        case CstNode.NonTerminal nt -> new CstNode.NonTerminal(
                            nt.id(), nt.span(), nt.rule(), nt.children(), nt.leadingTrivia(), trailingTrivia
                        );
                        case CstNode.Token tok -> new CstNode.Token(
                            tok.id(), tok.span(), tok.rule(), tok.text(), tok.leadingTrivia(), trailingTrivia
                        );
            """);
        // Add Error case only for ADVANCED mode
        if (errorReporting == ErrorReporting.ADVANCED) {
            sb.append("""
                            case CstNode.Error err -> new CstNode.Error(
                                err.id(), err.span(), err.skippedText(), err.expected(), err.leadingTrivia(), trailingTrivia
                            );
                """);
        }
        sb.append("""
                    };
                }

            """);
        // 0.3.5 (Bug C') — rule-exit trailing-trivia attribution. When a rule
        // body accumulates pending-leading trivia not claimed by any child
        // (typically inter-element skipWhitespace before a zero-width tail
        // element such as an empty ZoM/Optional), attach the trivia to the
        // last child's trailingTrivia so reconstruction includes it. Pos is
        // NOT rewound — predicate combinators rely on pos being past any
        // whitespace already consumed. Mirrors PegEngine.attachTrailingToTail.
        sb.append("""
                private CstNode attachTrailingToTail(CstNode node, List<Trivia> trivia) {
                    if (trivia.isEmpty()) return node;
                    return switch (node) {
                        case CstNode.NonTerminal nt -> {
                            var ntChildren = nt.children();
                            if (ntChildren.isEmpty()) {
                                var combined = concatTrivia(nt.trailingTrivia(), trivia);
                                yield new CstNode.NonTerminal(
                                    nt.id(), nt.span(), nt.rule(), ntChildren, nt.leadingTrivia(), combined
                                );
                            }
                            var newChildren = new ArrayList<CstNode>(ntChildren);
                            var lastIdx = newChildren.size() - 1;
                            var lastChild = newChildren.get(lastIdx);
                            newChildren.set(lastIdx, attachTrailingToTail(lastChild, trivia));
                            yield new CstNode.NonTerminal(
                                nt.id(), nt.span(), nt.rule(), List.copyOf(newChildren), nt.leadingTrivia(), nt.trailingTrivia()
                            );
                        }
                        case CstNode.Terminal t -> new CstNode.Terminal(
                            t.id(), t.span(), t.rule(), t.text(), t.leadingTrivia(), concatTrivia(t.trailingTrivia(), trivia)
                        );
                        case CstNode.Token tok -> new CstNode.Token(
                            tok.id(), tok.span(), tok.rule(), tok.text(), tok.leadingTrivia(), concatTrivia(tok.trailingTrivia(), trivia)
                        );
            """);
        if (errorReporting == ErrorReporting.ADVANCED) {
            sb.append("""
                            case CstNode.Error err -> new CstNode.Error(
                                err.id(), err.span(), err.skippedText(), err.expected(), err.leadingTrivia(), concatTrivia(err.trailingTrivia(), trivia)
                            );
                """);
        }
        sb.append("""
                    };
                }

            """);
        // Generate match methods (identical for BASIC and ADVANCED modes;
        // trackFailure itself is now emitted unconditionally outside this block).
        emitMatchLiteralCst(sb);
        emitMatchDictionaryCst(sb);
        sb.append(MATCHES_WORD_METHOD);
        // trackFailure is now always available (moved out of ADVANCED-only block)
        emitMatchCharClassCst(sb);
        sb.append(MATCHES_PATTERN_METHOD);
        emitMatchAnyCst(sb);
        sb.append("""
                // === CST Parse Result ===
            """);
        if (config.mutableParseResult()) {
            // Spike A: raw nullable fields — no Option boxing on the parse hot path.
            // Field names match the Option-typed variant ('node', 'text', 'expected',
            // 'endLocation') so emission sites can be uniformly rewritten with raw
            // access patterns. CstParseResult instances are still freshly allocated
            // per call (cache safety), but the per-call Option$Some allocations are
            // gone — that's the spike's primary target.
            sb.append("""

                    private static final class CstParseResult {
                        final boolean success;
                        final CstNode node;          // raw nullable
                        final String text;           // raw nullable
                        final String expected;       // raw nullable
                        final SourceLocation endLocation; // raw nullable
                        final boolean cutFailed;

                        private CstParseResult(boolean success, CstNode node, String text, String expected, SourceLocation endLocation, boolean cutFailed) {
                            this.success = success;
                            this.node = node;
                            this.text = text;
                            this.expected = expected;
                            this.endLocation = endLocation;
                            this.cutFailed = cutFailed;
                        }

                        boolean isSuccess() { return success; }
                        boolean isFailure() { return !success; }
                        boolean isCutFailure() { return !success && cutFailed; }

                        static CstParseResult success(CstNode node, String text, SourceLocation endLocation) {
                            return new CstParseResult(true, node, text, null, endLocation, false);
                        }

                        // Phase 1.7 (D2): intermediate-result success that omits endLocation.
                        // Only rule-body and LR-body successes need endLocation (consumed by
                        // cache hits and LR grow-loop). All in-rule expression results have
                        // their endLocation field discarded by callers, so skipping the
                        // location() call here removes the dominant SourceLocation allocator
                        // on the self-host fixture.
                        static CstParseResult successNoLoc(CstNode node, String text) {
                            return new CstParseResult(true, node, text, null, null, false);
                        }

                        static CstParseResult failure(String expected) {
                            return new CstParseResult(false, null, null, expected, null, false);
                        }

                        static CstParseResult cutFailure(String expected) {
                            return new CstParseResult(false, null, null, expected, null, true);
                        }

                        CstParseResult asCutFailure() {
                            return cutFailed ? this : new CstParseResult(false, null, null, expected, null, true);
                        }

                        CstParseResult asRegularFailure() {
                            return cutFailed ? new CstParseResult(false, null, null, expected, null, false) : this;
                        }
                    }
                """);
        }else {
            sb.append("""

                    private static final class CstParseResult {
                        final boolean success;
                        final Option<CstNode> node;
                        final Option<String> text;
                        final Option<String> expected;
                        final Option<SourceLocation> endLocation;
                        final boolean cutFailed;

                        private CstParseResult(boolean success, Option<CstNode> node, Option<String> text, Option<String> expected, Option<SourceLocation> endLocation, boolean cutFailed) {
                            this.success = success;
                            this.node = node;
                            this.text = text;
                            this.expected = expected;
                            this.endLocation = endLocation;
                            this.cutFailed = cutFailed;
                        }

                        boolean isSuccess() { return success; }
                        boolean isFailure() { return !success; }
                        boolean isCutFailure() { return !success && cutFailed; }

                        static CstParseResult success(CstNode node, String text, SourceLocation endLocation) {
                            return new CstParseResult(true, Option.option(node), Option.some(text), Option.none(), Option.some(endLocation), false);
                        }

                        // Phase 1.7 (D2): intermediate-result success that omits endLocation.
                        // See mutable variant above for rationale.
                        static CstParseResult successNoLoc(CstNode node, String text) {
                            return new CstParseResult(true, Option.option(node), Option.some(text), Option.none(), Option.none(), false);
                        }

                        static CstParseResult failure(String expected) {
                            return new CstParseResult(false, Option.none(), Option.none(), Option.some(expected), Option.none(), false);
                        }

                        static CstParseResult cutFailure(String expected) {
                            return new CstParseResult(false, Option.none(), Option.none(), Option.some(expected), Option.none(), true);
                        }

                        CstParseResult asCutFailure() {
                            return cutFailed ? this : new CstParseResult(false, Option.none(), Option.none(), expected, Option.none(), true);
                        }

                        CstParseResult asRegularFailure() {
                            return cutFailed ? new CstParseResult(false, Option.none(), Option.none(), expected, Option.none(), false) : this;
                        }
                    }
                """);
        }
    }

    // === Step 4 commit 4: embedded TriviaPostPass (flag-ON only) ===
    /**
     * Emit a {@code private static final class TriviaPostPass} embedded in the
     * generated parser. Provides {@code assignTrivia(input, cst, leadingScanFrom)}
     * with the same semantics as
     * {@link org.pragmatica.peg.tree.TriviaPostPass#assignTrivia(String, CstNode, org.pragmatica.peg.grammar.Grammar, int)}.
     *
     * <p>The embedded class is fully self-contained: it depends only on the
     * generated parser's own embedded {@code CstNode}, {@code Trivia}, and
     * {@code SourceSpan} types (which themselves depend only on
     * {@code pragmatica-lite:core}). It never imports any peglib runtime class,
     * preserving the standalone-parser invariant.
     *
     * <p>The whitespace matcher is generated from the grammar's
     * {@code %whitespace} expression at generation time, walking the expression
     * tree and emitting Java code per Expression subtype. This avoids needing
     * the runtime {@code Grammar} object at parse time.
     */
    private void generateCstTriviaPostPass(StringBuilder sb) {
        var hasWhitespace = grammar.whitespace()
                                   .isPresent();
        sb.append("    // === Embedded TriviaPostPass (Step 4 commit 4 — flag-ON) ===\n");
        sb.append("    /**\n");
        sb.append("     * Re-derive leading/trailing trivia attribution from\n");
        sb.append("     * (input, span, %whitespace) after the engine returns its CST.\n");
        sb.append("     * Mirrors org.pragmatica.peg.tree.TriviaPostPass at the runtime.\n");
        sb.append("     */\n");
        sb.append("    private static final class TriviaPostPass {\n");
        sb.append("        private TriviaPostPass() {}\n\n");
        sb.append("        static CstNode assignTrivia(String input, CstNode cst, int leadingScanFrom) {\n");
        sb.append("            int rootStart = cst.span().startOffset();\n");
        sb.append("            if (leadingScanFrom < 0 || leadingScanFrom > rootStart) {\n");
        sb.append("                // Generator span semantics: rootStart may be 0 (span includes\n");
        sb.append("                // leading whitespace). Clamp leadingScanFrom rather than reject.\n");
        sb.append("                if (leadingScanFrom > 0 && rootStart == 0) {\n");
        sb.append("                    leadingScanFrom = 0;\n");
        sb.append("                } else {\n");
        sb.append("                    throw new IllegalArgumentException(\n");
        sb.append("                        \"leadingScanFrom \" + leadingScanFrom + \" out of range [0, \" + rootStart + \"]\");\n");
        sb.append("                }\n");
        sb.append("            }\n");
        sb.append("            // Build line-start table once per pass (O(N)) so that per-chunk\n");
        sb.append("            // computeSpan is O(log N) instead of O(from). Eliminates an O(N²)\n");
        sb.append("            // hotspot for whitespace-rich source.\n");
        sb.append("            int[] lineStarts = buildLineStarts(input);\n");
        sb.append("            return rebuildRoot(input, cst, lineStarts, leadingScanFrom);\n");
        sb.append("        }\n\n");
        sb.append("        private static int[] buildLineStarts(String input) {\n");
        sb.append("            int len = input.length();\n");
        sb.append("            int[] starts = new int[Math.max(8, len / 16 + 4)];\n");
        sb.append("            int n = 0;\n");
        sb.append("            starts[n++] = 0;\n");
        sb.append("            for (int i = 0; i < len; i++) {\n");
        sb.append("                if (input.charAt(i) == '\\n') {\n");
        sb.append("                    if (n == starts.length) {\n");
        sb.append("                        starts = java.util.Arrays.copyOf(starts, starts.length * 2);\n");
        sb.append("                    }\n");
        sb.append("                    starts[n++] = i + 1;\n");
        sb.append("                }\n");
        sb.append("            }\n");
        sb.append("            return java.util.Arrays.copyOf(starts, n);\n");
        sb.append("        }\n\n");
        sb.append("        private static int[] lineColAt(int[] lineStarts, int offset) {\n");
        sb.append("            int idx = java.util.Arrays.binarySearch(lineStarts, offset);\n");
        sb.append("            if (idx < 0) idx = -idx - 2;\n");
        sb.append("            int line = idx + 1;\n");
        sb.append("            int col = offset - lineStarts[idx] + 1;\n");
        sb.append("            return new int[]{line, col};\n");
        sb.append("        }\n\n");
        sb.append("        static List<Trivia> scanWhitespace(String input, int from, int to, int[] lineStarts) {\n");
        sb.append("            if (from >= to) return List.of();\n");
        if (!hasWhitespace) {
            sb.append("            return List.of();\n");
        }else {
            sb.append("            var trivia = new ArrayList<Trivia>();\n");
            sb.append("            int pos = from;\n");
            sb.append("            while (pos < to) {\n");
            sb.append("                int matched = matchWsInner(input, pos, to);\n");
            sb.append("                if (matched < 0 || matched == pos) break;\n");
            sb.append("                var text = input.substring(pos, matched);\n");
            sb.append("                var span = computeSpan(lineStarts, pos, matched);\n");
            sb.append("                trivia.add(classify(span, text));\n");
            sb.append("                pos = matched;\n");
            sb.append("            }\n");
            sb.append("            return List.copyOf(trivia);\n");
        }
        sb.append("        }\n\n");
        // === Tree rebuild ===
        // Span-semantics adapter: the generated parser's rule wrappers may use
        // a span whose startOffset INCLUDES leading whitespace (rule entry
        // captures startOffset BEFORE skipWhitespace, then the leading trivia
        // is attached as the wrapper's leadingTrivia). The runtime engine, by
        // contrast, captures startOffset AFTER skipWhitespace. Detect this by
        // measuring whether the root's existing leadingTrivia text overlaps
        // [rootStart, rootStart + leadingLen). If so, use rootStart as the
        // "leading-end" boundary AND leadingScanFrom + (rootStart - leadingScanFrom)
        // = rootStart for the leading scan. Otherwise (runtime semantics),
        // scan up to rootStart as the runtime post-pass does.
        sb.append("        private static CstNode rebuildRoot(String input, CstNode root, int[] lineStarts, int leadingScanFrom) {\n");
        sb.append("            int rootStart = root.span().startOffset();\n");
        sb.append("            int rootEnd = root.span().endOffset();\n");
        sb.append("            int leadingTextLen = totalTriviaLength(root.leadingTrivia());\n");
        sb.append("            // Generator semantics: span includes leading WS. The actual content\n");
        sb.append("            // begins at (rootStart + leadingTextLen), and the leading WS lives\n");
        sb.append("            // in [rootStart, rootStart + leadingTextLen).\n");
        sb.append("            // Runtime semantics: span excludes leading WS. leadingTextLen is in\n");
        sb.append("            // the gap [leadingScanFrom, rootStart). We distinguish by checking\n");
        sb.append("            // whether scanning the pre-rootStart gap yields the leading.\n");
        sb.append("            int leadingScanEnd;\n");
        sb.append("            if (leadingTextLen > 0 && rootStart + leadingTextLen <= input.length()) {\n");
        sb.append("                // Hypothesis: generator semantics — leading occupies [rootStart, rootStart + leadingTextLen).\n");
        sb.append("                // Verify by re-scanning [rootStart, rootStart + leadingTextLen) and checking it consumes exactly that range.\n");
        sb.append("                int probeEnd = rootStart + leadingTextLen;\n");
        sb.append("                var probe = scanWhitespace(input, rootStart, probeEnd, lineStarts);\n");
        sb.append("                if (totalTriviaLength(probe) == leadingTextLen) {\n");
        sb.append("                    leadingScanEnd = probeEnd;\n");
        sb.append("                } else {\n");
        sb.append("                    leadingScanEnd = rootStart;\n");
        sb.append("                }\n");
        sb.append("            } else {\n");
        sb.append("                leadingScanEnd = rootStart;\n");
        sb.append("            }\n");
        sb.append("            var leading = scanWhitespace(input, leadingScanFrom, leadingScanEnd, lineStarts);\n");
        sb.append("            var trailingExternal = scanWhitespace(input, rootEnd, input.length(), lineStarts);\n");
        sb.append("            return rebuildSelf(input, root, lineStarts, leading, trailingExternal);\n");
        sb.append("        }\n\n");
        sb.append("        private static int totalTriviaLength(List<Trivia> trivia) {\n");
        sb.append("            int total = 0;\n");
        sb.append("            for (var t : trivia) total += t.text().length();\n");
        sb.append("            return total;\n");
        sb.append("        }\n\n");
        sb.append("        private static CstNode rebuildSelf(String input, CstNode node, int[] lineStarts,\n");
        sb.append("                                           List<Trivia> leading, List<Trivia> extraTrailing) {\n");
        sb.append("            return switch (node) {\n");
        sb.append("                case CstNode.NonTerminal nt -> rebuildNonTerminal(input, nt, lineStarts, leading, extraTrailing);\n");
        sb.append("                case CstNode.Terminal t -> new CstNode.Terminal(t.id(), t.span(), t.rule(), t.text(), leading, extraTrailing);\n");
        sb.append("                case CstNode.Token tk -> new CstNode.Token(tk.id(), tk.span(), tk.rule(), tk.text(), leading, extraTrailing);\n");
        if (errorReporting == ErrorReporting.ADVANCED) {
            sb.append("                case CstNode.Error e -> new CstNode.Error(e.id(), e.span(), e.skippedText(), e.expected(), leading, extraTrailing);\n");
        }
        sb.append("            };\n");
        sb.append("        }\n\n");
        sb.append("        private static CstNode rebuildChild(String input, CstNode child, int[] lineStarts, int prevEnd) {\n");
        sb.append("            int childStart = child.span().startOffset();\n");
        sb.append("            var leading = scanWhitespace(input, prevEnd, childStart, lineStarts);\n");
        sb.append("            return switch (child) {\n");
        sb.append("                case CstNode.NonTerminal nt -> rebuildNonTerminal(input, nt, lineStarts, leading, List.of());\n");
        sb.append("                case CstNode.Terminal t -> new CstNode.Terminal(t.id(), t.span(), t.rule(), t.text(), leading, List.of());\n");
        sb.append("                case CstNode.Token tk -> new CstNode.Token(tk.id(), tk.span(), tk.rule(), tk.text(), leading, List.of());\n");
        if (errorReporting == ErrorReporting.ADVANCED) {
            sb.append("                case CstNode.Error e -> new CstNode.Error(e.id(), e.span(), e.skippedText(), e.expected(), leading, List.of());\n");
        }
        sb.append("            };\n");
        sb.append("        }\n\n");
        sb.append("        private static CstNode.NonTerminal rebuildNonTerminal(String input, CstNode.NonTerminal nt, int[] lineStarts,\n");
        sb.append("                                                              List<Trivia> leading, List<Trivia> extraTrailing) {\n");
        sb.append("            int spanStart = nt.span().startOffset();\n");
        sb.append("            int spanEnd = nt.span().endOffset();\n");
        sb.append("            // Generator semantics adapter: the wrapper's span may INCLUDE its\n");
        sb.append("            // own leading trivia (rule entry captures startOffset BEFORE\n");
        sb.append("            // skipWhitespace). If the caller-supplied leading occupies\n");
        sb.append("            // [spanStart, spanStart+leadingLen), advance cursor past it so the\n");
        sb.append("            // first child's leading scan does not re-emit the same trivia.\n");
        sb.append("            int leadingLen = totalTriviaLength(leading);\n");
        sb.append("            int cursor = spanStart;\n");
        sb.append("            if (leadingLen > 0 && spanStart + leadingLen <= input.length()) {\n");
        sb.append("                var probe = scanWhitespace(input, spanStart, spanStart + leadingLen, lineStarts);\n");
        sb.append("                if (totalTriviaLength(probe) == leadingLen) {\n");
        sb.append("                    cursor = spanStart + leadingLen;\n");
        sb.append("                }\n");
        sb.append("            }\n");
        sb.append("            var newChildren = new ArrayList<CstNode>(nt.children().size());\n");
        sb.append("            for (var c : nt.children()) {\n");
        sb.append("                var rebuilt = rebuildChild(input, c, lineStarts, cursor);\n");
        sb.append("                newChildren.add(rebuilt);\n");
        sb.append("                cursor = c.span().endOffset();\n");
        sb.append("            }\n");
        sb.append("            // Bug C' compensation — drain orphan trailing into the last terminal\n");
        sb.append("            // descendant so CstReconstruct.emit (last-child-trailing-only) stays\n");
        sb.append("            // byte-equal even when this wrapper is not its parent's last child.\n");
        sb.append("            var internalTrailing = scanWhitespace(input, cursor, spanEnd, lineStarts);\n");
        sb.append("            List<Trivia> wrapperTrailing = extraTrailing;\n");
        sb.append("            if (!internalTrailing.isEmpty() && !newChildren.isEmpty()) {\n");
        sb.append("                int lastIdx = newChildren.size() - 1;\n");
        sb.append("                newChildren.set(lastIdx, postPassAttachTrailingToTail(newChildren.get(lastIdx), internalTrailing));\n");
        sb.append("            } else {\n");
        sb.append("                wrapperTrailing = combine(internalTrailing, extraTrailing);\n");
        sb.append("            }\n");
        sb.append("            return new CstNode.NonTerminal(nt.id(), nt.span(), nt.rule(), List.copyOf(newChildren), leading, wrapperTrailing);\n");
        sb.append("        }\n\n");
        sb.append("        private static CstNode postPassAttachTrailingToTail(CstNode node, List<Trivia> extra) {\n");
        sb.append("            if (extra.isEmpty()) return node;\n");
        sb.append("            return switch (node) {\n");
        sb.append("                case CstNode.NonTerminal nt -> {\n");
        sb.append("                    var children = nt.children();\n");
        sb.append("                    if (children.isEmpty()) {\n");
        sb.append("                        yield new CstNode.NonTerminal(nt.id(), nt.span(), nt.rule(), children, nt.leadingTrivia(), combine(nt.trailingTrivia(), extra));\n");
        sb.append("                    }\n");
        sb.append("                    var newChildren = new ArrayList<CstNode>(children);\n");
        sb.append("                    int lastIdx = newChildren.size() - 1;\n");
        sb.append("                    newChildren.set(lastIdx, postPassAttachTrailingToTail(newChildren.get(lastIdx), extra));\n");
        sb.append("                    yield new CstNode.NonTerminal(nt.id(), nt.span(), nt.rule(), List.copyOf(newChildren), nt.leadingTrivia(), nt.trailingTrivia());\n");
        sb.append("                }\n");
        sb.append("                case CstNode.Terminal t -> new CstNode.Terminal(t.id(), t.span(), t.rule(), t.text(), t.leadingTrivia(), combine(t.trailingTrivia(), extra));\n");
        sb.append("                case CstNode.Token tk -> new CstNode.Token(tk.id(), tk.span(), tk.rule(), tk.text(), tk.leadingTrivia(), combine(tk.trailingTrivia(), extra));\n");
        if (errorReporting == ErrorReporting.ADVANCED) {
            sb.append("                case CstNode.Error e -> new CstNode.Error(e.id(), e.span(), e.skippedText(), e.expected(), e.leadingTrivia(), combine(e.trailingTrivia(), extra));\n");
        }
        sb.append("            };\n");
        sb.append("        }\n\n");
        sb.append("        private static List<Trivia> combine(List<Trivia> a, List<Trivia> b) {\n");
        sb.append("            if (a.isEmpty()) return b;\n");
        sb.append("            if (b.isEmpty()) return a;\n");
        sb.append("            var combined = new ArrayList<Trivia>(a.size() + b.size());\n");
        sb.append("            combined.addAll(a);\n");
        sb.append("            combined.addAll(b);\n");
        sb.append("            return List.copyOf(combined);\n");
        sb.append("        }\n\n");
        sb.append("        private static Trivia classify(SourceSpan span, String text) {\n");
        sb.append("            if (text.startsWith(\"//\")) {\n");
        sb.append("                return new Trivia.LineComment(span, text);\n");
        sb.append("            } else if (text.startsWith(\"/*\")) {\n");
        sb.append("                return new Trivia.BlockComment(span, text);\n");
        sb.append("            } else {\n");
        sb.append("                return new Trivia.Whitespace(span, text);\n");
        sb.append("            }\n");
        sb.append("        }\n\n");
        // Static copy of matchesPattern for char-class matching inside the post-pass.
        // Mirrors the instance-method MATCHES_PATTERN_METHOD emitted elsewhere; kept
        // as a separate static copy because the embedded class cannot reach instance
        // methods of the enclosing parser.
        sb.append("        private static boolean matchesPattern(char c, String pattern, boolean caseInsensitive) {\n");
        sb.append("            char testChar = caseInsensitive ? Character.toLowerCase(c) : c;\n");
        sb.append("            int i = 0;\n");
        sb.append("            while (i < pattern.length()) {\n");
        sb.append("                char start = pattern.charAt(i);\n");
        sb.append("                if (start == '\\\\' && i + 1 < pattern.length()) {\n");
        sb.append("                    char escaped = pattern.charAt(i + 1);\n");
        sb.append("                    int consumed = 2;\n");
        sb.append("                    char expected = switch (escaped) {\n");
        sb.append("                        case 'n' -> '\\n';\n");
        sb.append("                        case 'r' -> '\\r';\n");
        sb.append("                        case 't' -> '\\t';\n");
        sb.append("                        case '\\\\' -> '\\\\';\n");
        sb.append("                        case ']' -> ']';\n");
        sb.append("                        case '-' -> '-';\n");
        sb.append("                        case 'x' -> {\n");
        sb.append("                            if (i + 4 <= pattern.length()) {\n");
        sb.append("                                try {\n");
        sb.append("                                    var hex = pattern.substring(i + 2, i + 4);\n");
        sb.append("                                    consumed = 4;\n");
        sb.append("                                    yield (char) Integer.parseInt(hex, 16);\n");
        sb.append("                                } catch (NumberFormatException e) { yield 'x'; }\n");
        sb.append("                            }\n");
        sb.append("                            yield 'x';\n");
        sb.append("                        }\n");
        sb.append("                        case 'u' -> {\n");
        sb.append("                            if (i + 6 <= pattern.length()) {\n");
        sb.append("                                try {\n");
        sb.append("                                    var hex = pattern.substring(i + 2, i + 6);\n");
        sb.append("                                    consumed = 6;\n");
        sb.append("                                    yield (char) Integer.parseInt(hex, 16);\n");
        sb.append("                                } catch (NumberFormatException e) { yield 'u'; }\n");
        sb.append("                            }\n");
        sb.append("                            yield 'u';\n");
        sb.append("                        }\n");
        sb.append("                        default -> escaped;\n");
        sb.append("                    };\n");
        sb.append("                    if (caseInsensitive) expected = Character.toLowerCase(expected);\n");
        sb.append("                    if (testChar == expected) return true;\n");
        sb.append("                    i += consumed;\n");
        sb.append("                    continue;\n");
        sb.append("                }\n");
        sb.append("                if (i + 2 < pattern.length() && pattern.charAt(i + 1) == '-') {\n");
        sb.append("                    char end = pattern.charAt(i + 2);\n");
        sb.append("                    if (caseInsensitive) {\n");
        sb.append("                        start = Character.toLowerCase(start);\n");
        sb.append("                        end = Character.toLowerCase(end);\n");
        sb.append("                    }\n");
        sb.append("                    if (testChar >= start && testChar <= end) return true;\n");
        sb.append("                    i += 3;\n");
        sb.append("                } else {\n");
        sb.append("                    if (caseInsensitive) start = Character.toLowerCase(start);\n");
        sb.append("                    if (testChar == start) return true;\n");
        sb.append("                    i++;\n");
        sb.append("                }\n");
        sb.append("            }\n");
        sb.append("            return false;\n");
        sb.append("        }\n\n");
        sb.append("        private static SourceSpan computeSpan(int[] lineStarts, int from, int to) {\n");
        sb.append("            int[] startLc = lineColAt(lineStarts, from);\n");
        sb.append("            int[] endLc = lineColAt(lineStarts, to);\n");
        sb.append("            return new SourceSpan(startLc[0], startLc[1], from, endLc[0], endLc[1], to);\n");
        sb.append("        }\n\n");
        // Emit per-expression matcher methods.
        if (hasWhitespace) {
            var wsExpr = grammar.whitespace()
                                .unwrap();
            var inner = ExpressionShape.extractInnerExpression(wsExpr);
            var ctx = new TriviaPostPassEmitContext();
            // Reserve method id 0 for the entry point.
            int entryId = ctx.allocate(inner);
            // Emit the entry method as `matchWsInner`; rename later via direct emission.
            sb.append("        private static int matchWsInner(String input, int pos, int limit) {\n");
            sb.append("            return ")
              .append(ctx.callName(entryId))
              .append("(input, pos, limit);\n");
            sb.append("        }\n\n");
            // Emit all queued methods.
            while (ctx.hasPending()) {
                var entry = ctx.nextPending();
                emitTriviaPostPassMatcher(sb, entry.expr(), entry.id(), ctx);
            }
        }
        sb.append("    }\n\n");
    }

    /**
     * Per-emission state for the embedded TriviaPostPass matcher generation.
     * Allocates unique method ids per expression, deduplicating identical
     * Reference targets and queueing each newly seen expression for emission.
     */
    private static final class TriviaPostPassEmitContext {
        private final java.util.IdentityHashMap<Expression, Integer> idByExpr = new java.util.IdentityHashMap<>();
        private final java.util.HashMap<String, Integer> idByRule = new java.util.HashMap<>();
        private final java.util.ArrayDeque<TriviaPostPassEntry> queue = new java.util.ArrayDeque<>();
        private int nextId = 0;

        int allocate(Expression expr) {
            var existing = idByExpr.get(expr);
            if (existing != null) return existing;
            int id = nextId++ ;
            idByExpr.put(expr, id);
            queue.add(new TriviaPostPassEntry(id, expr));
            return id;
        }

        int allocateForRule(String ruleName, Expression body) {
            var existing = idByRule.get(ruleName);
            if (existing != null) return existing;
            int id = allocate(body);
            idByRule.put(ruleName, id);
            return id;
        }

        boolean hasPending() {
            return !queue.isEmpty();
        }

        TriviaPostPassEntry nextPending() {
            return queue.removeFirst();
        }

        String callName(int id) {
            return "matchWs_" + id;
        }
    }

    private record TriviaPostPassEntry(int id, Expression expr) {}

    /**
     * Emit a single matcher method for {@code expr} into {@code sb}, naming it
     * {@code matchWs_<id>(String input, int pos, int limit)}. Returns the next
     * absolute offset on a successful match or {@code -1} on failure. Sub-
     * expressions are queued via {@link TriviaPostPassEmitContext#allocate} and
     * emitted in subsequent iterations.
     */
    private void emitTriviaPostPassMatcher(StringBuilder sb, Expression expr, int id, TriviaPostPassEmitContext ctx) {
        sb.append("        private static int ")
          .append(ctx.callName(id))
          .append("(String input, int pos, int limit) {\n");
        emitTriviaPostPassMatchBody(sb, expr, ctx, "            ");
        sb.append("        }\n\n");
    }

    /**
     * Emit the body of a matcher method for {@code expr}. The body must
     * {@code return} an int value (next position or -1) on every control path.
     */
    private void emitTriviaPostPassMatchBody(StringBuilder sb,
                                             Expression expr,
                                             TriviaPostPassEmitContext ctx,
                                             String indent) {
        switch (expr) {
            case Expression.Literal lit -> emitTppLiteral(sb, lit, indent);
            case Expression.CharClass cc -> emitTppCharClass(sb, cc, indent);
            case Expression.Any ignored -> {
                sb.append(indent)
                  .append("return pos < limit ? pos + 1 : -1;\n");
            }
            case Expression.Reference ref -> emitTppReference(sb, ref, ctx, indent);
            case Expression.Sequence seq -> emitTppSequence(sb, seq, ctx, indent);
            case Expression.Choice ch -> emitTppChoice(sb, ch, ctx, indent);
            case Expression.ZeroOrMore zom -> emitTppZeroOrMore(sb, zom.expression(), ctx, indent);
            case Expression.OneOrMore oom -> emitTppOneOrMore(sb, oom.expression(), ctx, indent);
            case Expression.Optional opt -> emitTppOptional(sb, opt.expression(), ctx, indent);
            case Expression.Repetition rep -> emitTppRepetition(sb, rep, ctx, indent);
            case Expression.And and -> {
                int sub = ctx.allocate(and.expression());
                sb.append(indent)
                  .append("return ")
                  .append(ctx.callName(sub))
                  .append("(input, pos, limit) >= 0 ? pos : -1;\n");
            }
            case Expression.Not not -> {
                int sub = ctx.allocate(not.expression());
                sb.append(indent)
                  .append("return ")
                  .append(ctx.callName(sub))
                  .append("(input, pos, limit) < 0 ? pos : -1;\n");
            }
            case Expression.Group g -> emitTriviaPostPassMatchBody(sb, g.expression(), ctx, indent);
            case Expression.TokenBoundary tb -> emitTriviaPostPassMatchBody(sb, tb.expression(), ctx, indent);
            case Expression.Ignore ig -> emitTriviaPostPassMatchBody(sb, ig.expression(), ctx, indent);
            case Expression.Capture cap -> emitTriviaPostPassMatchBody(sb, cap.expression(), ctx, indent);
            case Expression.CaptureScope cs -> emitTriviaPostPassMatchBody(sb, cs.expression(), ctx, indent);
            case Expression.BackReference ignored -> sb.append(indent)
                                                       .append("return -1;\n");
            case Expression.Dictionary ignored -> sb.append(indent)
                                                    .append("return -1;\n");
            case Expression.Cut ignored -> sb.append(indent)
                                             .append("return pos;\n");
        }
    }

    private void emitTppLiteral(StringBuilder sb, Expression.Literal lit, String indent) {
        var literalStr = lit.text();
        sb.append(indent)
          .append("String text = ")
          .append(quoteJavaString(literalStr))
          .append(";\n");
        sb.append(indent)
          .append("int len = text.length();\n");
        sb.append(indent)
          .append("if (pos + len > limit) return -1;\n");
        if (lit.caseInsensitive()) {
            sb.append(indent)
              .append("for (int i = 0; i < len; i++) {\n");
            sb.append(indent)
              .append("    if (Character.toLowerCase(input.charAt(pos + i)) != Character.toLowerCase(text.charAt(i))) return -1;\n");
            sb.append(indent)
              .append("}\n");
        }else {
            sb.append(indent)
              .append("for (int i = 0; i < len; i++) {\n");
            sb.append(indent)
              .append("    if (input.charAt(pos + i) != text.charAt(i)) return -1;\n");
            sb.append(indent)
              .append("}\n");
        }
        sb.append(indent)
          .append("return pos + len;\n");
    }

    private void emitTppCharClass(StringBuilder sb, Expression.CharClass cc, String indent) {
        sb.append(indent)
          .append("if (pos >= limit) return -1;\n");
        sb.append(indent)
          .append("char c = input.charAt(pos);\n");
        sb.append(indent)
          .append("boolean inClass = matchesPattern(c, ")
          .append(quoteJavaString(cc.pattern()))
          .append(", ")
          .append(cc.caseInsensitive())
          .append(");\n");
        sb.append(indent)
          .append("boolean ok = ")
          .append(cc.negated()
                  ? "!inClass"
                  : "inClass")
          .append(";\n");
        sb.append(indent)
          .append("return ok ? pos + 1 : -1;\n");
    }

    private void emitTppReference(StringBuilder sb,
                                  Expression.Reference ref,
                                  TriviaPostPassEmitContext ctx,
                                  String indent) {
        var ruleOpt = grammar.rule(ref.ruleName());
        if (ruleOpt.isEmpty()) {
            sb.append(indent)
              .append("return -1;\n");
            return;
        }
        int sub = ctx.allocateForRule(ref.ruleName(),
                                      ruleOpt.unwrap()
                                             .expression());
        sb.append(indent)
          .append("return ")
          .append(ctx.callName(sub))
          .append("(input, pos, limit);\n");
    }

    private void emitTppSequence(StringBuilder sb,
                                 Expression.Sequence seq,
                                 TriviaPostPassEmitContext ctx,
                                 String indent) {
        sb.append(indent)
          .append("int cursor = pos;\n");
        for (var elt : seq.elements()) {
            int sub = ctx.allocate(elt);
            sb.append(indent)
              .append("cursor = ")
              .append(ctx.callName(sub))
              .append("(input, cursor, limit);\n");
            sb.append(indent)
              .append("if (cursor < 0) return -1;\n");
        }
        sb.append(indent)
          .append("return cursor;\n");
    }

    private void emitTppChoice(StringBuilder sb, Expression.Choice ch, TriviaPostPassEmitContext ctx, String indent) {
        for (var alt : ch.alternatives()) {
            int sub = ctx.allocate(alt);
            sb.append(indent)
              .append("{\n");
            sb.append(indent)
              .append("    int r = ")
              .append(ctx.callName(sub))
              .append("(input, pos, limit);\n");
            sb.append(indent)
              .append("    if (r >= 0) return r;\n");
            sb.append(indent)
              .append("}\n");
        }
        sb.append(indent)
          .append("return -1;\n");
    }

    private void emitTppZeroOrMore(StringBuilder sb, Expression body, TriviaPostPassEmitContext ctx, String indent) {
        int sub = ctx.allocate(body);
        sb.append(indent)
          .append("int cursor = pos;\n");
        sb.append(indent)
          .append("while (true) {\n");
        sb.append(indent)
          .append("    int next = ")
          .append(ctx.callName(sub))
          .append("(input, cursor, limit);\n");
        sb.append(indent)
          .append("    if (next < 0 || next == cursor) break;\n");
        sb.append(indent)
          .append("    cursor = next;\n");
        sb.append(indent)
          .append("}\n");
        sb.append(indent)
          .append("return cursor;\n");
    }

    private void emitTppOneOrMore(StringBuilder sb, Expression body, TriviaPostPassEmitContext ctx, String indent) {
        int sub = ctx.allocate(body);
        sb.append(indent)
          .append("int first = ")
          .append(ctx.callName(sub))
          .append("(input, pos, limit);\n");
        sb.append(indent)
          .append("if (first < 0 || first == pos) return -1;\n");
        sb.append(indent)
          .append("int cursor = first;\n");
        sb.append(indent)
          .append("while (true) {\n");
        sb.append(indent)
          .append("    int next = ")
          .append(ctx.callName(sub))
          .append("(input, cursor, limit);\n");
        sb.append(indent)
          .append("    if (next < 0 || next == cursor) break;\n");
        sb.append(indent)
          .append("    cursor = next;\n");
        sb.append(indent)
          .append("}\n");
        sb.append(indent)
          .append("return cursor;\n");
    }

    private void emitTppOptional(StringBuilder sb, Expression body, TriviaPostPassEmitContext ctx, String indent) {
        int sub = ctx.allocate(body);
        sb.append(indent)
          .append("int next = ")
          .append(ctx.callName(sub))
          .append("(input, pos, limit);\n");
        sb.append(indent)
          .append("return next < 0 ? pos : next;\n");
    }

    private void emitTppRepetition(StringBuilder sb,
                                   Expression.Repetition rep,
                                   TriviaPostPassEmitContext ctx,
                                   String indent) {
        int sub = ctx.allocate(rep.expression());
        var maxExpr = rep.max()
                         .isPresent()
                      ? String.valueOf(rep.max()
                                          .unwrap())
                      : "Integer.MAX_VALUE";
        sb.append(indent)
          .append("int cursor = pos;\n");
        sb.append(indent)
          .append("int count = 0;\n");
        sb.append(indent)
          .append("int max = ")
          .append(maxExpr)
          .append(";\n");
        sb.append(indent)
          .append("while (count < max) {\n");
        sb.append(indent)
          .append("    int next = ")
          .append(ctx.callName(sub))
          .append("(input, cursor, limit);\n");
        sb.append(indent)
          .append("    if (next < 0 || next == cursor) break;\n");
        sb.append(indent)
          .append("    cursor = next;\n");
        sb.append(indent)
          .append("    count++;\n");
        sb.append(indent)
          .append("}\n");
        sb.append(indent)
          .append("return count >= ")
          .append(rep.min())
          .append(" ? cursor : -1;\n");
    }

    /**
     * Quote a string for embedding into Java source, escaping special chars.
     * Mirrors the conventions used elsewhere in {@code ParserGenerator}.
     */
    private static String quoteJavaString(String s) {
        var out = new StringBuilder(s.length() + 8);
        out.append('"');
        for (int i = 0; i < s.length(); i++ ) {
            char c = s.charAt(i);
            switch (c) {
                case'"' -> out.append("\\\"");
                case'\\' -> out.append("\\\\");
                case'\n' -> out.append("\\n");
                case'\r' -> out.append("\\r");
                case'\t' -> out.append("\\t");
                case'\b' -> out.append("\\b");
                case'\f' -> out.append("\\f");
                default -> {
                    if (c < 0x20 || c == 0x7f) {
                        out.append(String.format("\\u%04x", (int) c));
                    }else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
        return out.toString();
    }

    // === Match-method emitters (phase 1 perf flags) ===
    // Phase 1.7 (D2): match helpers return successNoLoc — endLocation field on
    // intermediate (per-leaf) results is never read by callers. The
    // reuseEndLocation flag is now a no-op for these emitters since the span
    // is built from primitive ints and no SourceLocation is materialized.
    private void emitMatchLiteralCst(StringBuilder sb) {
        sb.append("\n");
        sb.append("    private CstParseResult matchLiteralCst(String text, boolean caseInsensitive) {\n");
        if (config.literalFailureCache()) {
            sb.append("        int len = text.length();\n");
            sb.append("        if (input.length() - pos < len) {\n");
            sb.append("            var f = literalFailure(text);\n");
            sb.append("            trackFailure(")
              .append(expectedUnwrap("f"))
              .append(");\n");
            sb.append("            return f;\n");
            sb.append("        }\n");
            // Phase 1.7 (D): inline int captures — no SourceLocation per literal.
            sb.append("        int startPos = pos;\n");
            sb.append("        int startLine = line;\n");
            sb.append("        int startColumn = column;\n");
            sb.append("        if (caseInsensitive) {\n");
            sb.append("            for (int i = 0; i < len; i++) {\n");
            sb.append("                if (Character.toLowerCase(text.charAt(i)) != Character.toLowerCase(input.charAt(pos + i))) {\n");
            sb.append("                    var f = literalFailure(text);\n");
            sb.append("                    trackFailure(")
              .append(expectedUnwrap("f"))
              .append(");\n");
            sb.append("                    return f;\n");
            sb.append("                }\n");
            sb.append("            }\n");
            sb.append("        } else {\n");
            sb.append("            for (int i = 0; i < len; i++) {\n");
            sb.append("                if (text.charAt(i) != input.charAt(pos + i)) {\n");
            sb.append("                    var f = literalFailure(text);\n");
            sb.append("                    trackFailure(")
              .append(expectedUnwrap("f"))
              .append(");\n");
            sb.append("                    return f;\n");
            sb.append("                }\n");
            sb.append("            }\n");
            sb.append("        }\n");
        }else {
            sb.append("        if (remaining() < text.length()) {\n");
            sb.append("            trackFailure(\"'\" + text + \"'\");\n");
            sb.append("            return CstParseResult.failure(\"'\" + text + \"'\");\n");
            sb.append("        }\n");
            // Phase 1.7 (D): inline int captures — no SourceLocation per literal.
            sb.append("        int startPos = pos;\n");
            sb.append("        int startLine = line;\n");
            sb.append("        int startColumn = column;\n");
            sb.append("        for (int i = 0; i < text.length(); i++) {\n");
            sb.append("            char expected = text.charAt(i);\n");
            sb.append("            char actual = peek(i);\n");
            sb.append("            if (caseInsensitive) {\n");
            sb.append("                if (Character.toLowerCase(expected) != Character.toLowerCase(actual)) {\n");
            sb.append("                    trackFailure(\"'\" + text + \"'\");\n");
            sb.append("                    return CstParseResult.failure(\"'\" + text + \"'\");\n");
            sb.append("                }\n");
            sb.append("            } else {\n");
            sb.append("                if (expected != actual) {\n");
            sb.append("                    trackFailure(\"'\" + text + \"'\");\n");
            sb.append("                    return CstParseResult.failure(\"'\" + text + \"'\");\n");
            sb.append("                }\n");
            sb.append("            }\n");
            sb.append("        }\n");
        }
        // success-path advance
        if (config.bulkAdvanceLiteral()) {
            var lenExpr = config.literalFailureCache()
                          ? "len"
                          : "text.length()";
            sb.append("        if (text.indexOf('\\n') < 0) {\n");
            sb.append("            pos += ")
              .append(lenExpr)
              .append(";\n");
            sb.append("            column += ")
              .append(lenExpr)
              .append(";\n");
            sb.append("        } else {\n");
            sb.append("            for (int i = 0; i < ")
              .append(lenExpr)
              .append("; i++) advance();\n");
            sb.append("        }\n");
        }else {
            sb.append("        for (int i = 0; i < text.length(); i++) {\n");
            sb.append("            advance();\n");
            sb.append("        }\n");
        }
        // Phase 1.7 (D2): build SourceSpan from inline ints — no SourceLocation alloc.
        sb.append("        var span = new SourceSpan(startLine, startColumn, startPos, line, column, pos);\n");
        sb.append("        var node = new CstNode.Terminal(idGen.next(), span, RULE_PEG_LITERAL, text, ")
          .append(takePendingExpr())
          .append(", List.of());\n");
        sb.append("        return CstParseResult.successNoLoc(node, text);\n");
        sb.append("    }\n");
        sb.append("\n");
        if (config.literalFailureCache()) {
            sb.append("    private CstParseResult literalFailure(String text) {\n");
            sb.append("        CstParseResult r = literalFailureCache.get(text);\n");
            sb.append("        if (r == null) {\n");
            sb.append("            r = CstParseResult.failure(\"'\" + text + \"'\");\n");
            sb.append("            literalFailureCache.put(text, r);\n");
            sb.append("        }\n");
            sb.append("        return r;\n");
            sb.append("    }\n");
            sb.append("\n");
        }
    }

    private void emitMatchDictionaryCst(StringBuilder sb) {
        sb.append("    private CstParseResult matchDictionaryCst(List<String> words, boolean caseInsensitive) {\n");
        sb.append("        String longestMatch = null;\n");
        sb.append("        int longestLen = 0;\n");
        sb.append("        for (var word : words) {\n");
        sb.append("            if (matchesWord(word, caseInsensitive) && word.length() > longestLen) {\n");
        sb.append("                longestMatch = word;\n");
        sb.append("                longestLen = word.length();\n");
        sb.append("            }\n");
        sb.append("        }\n");
        sb.append("        if (longestMatch == null) {\n");
        sb.append("            trackFailure(\"dictionary word\");\n");
        sb.append("            return CstParseResult.failure(\"dictionary word\");\n");
        sb.append("        }\n");
        // Phase 1.7 (D): inline int captures.
        sb.append("        int startPos = pos;\n");
        sb.append("        int startLine = line;\n");
        sb.append("        int startColumn = column;\n");
        sb.append("        for (int i = 0; i < longestLen; i++) {\n");
        sb.append("            advance();\n");
        sb.append("        }\n");
        // Phase 1.7 (D2): primitive-int span; successNoLoc result.
        sb.append("        var span = new SourceSpan(startLine, startColumn, startPos, line, column, pos);\n");
        sb.append("        var node = new CstNode.Terminal(idGen.next(), span, RULE_PEG_LITERAL, longestMatch, ")
          .append(takePendingExpr())
          .append(", List.of());\n");
        sb.append("        return CstParseResult.successNoLoc(node, longestMatch);\n");
        sb.append("    }\n");
        sb.append("\n");
    }

    private void emitMatchCharClassCst(StringBuilder sb) {
        sb.append("\n");
        sb.append("    private CstParseResult matchCharClassCst(String pattern, boolean negated, boolean caseInsensitive) {\n");
        if (config.charClassFailureCache()) {
            sb.append("        if (isAtEnd()) {\n");
            sb.append("            var f = charClassFailure(pattern, negated);\n");
            sb.append("            trackFailure(")
              .append(expectedUnwrap("f"))
              .append(");\n");
            sb.append("            return f;\n");
            sb.append("        }\n");
            // Phase 1.7 (D): inline int captures — no SourceLocation per char-class match.
            sb.append("        int startPos = pos;\n");
            sb.append("        int startLine = line;\n");
            sb.append("        int startColumn = column;\n");
            sb.append("        char c = peek();\n");
            sb.append("        boolean matches = matchesPattern(c, pattern, caseInsensitive);\n");
            sb.append("        if (negated) matches = !matches;\n");
            sb.append("        if (!matches) {\n");
            sb.append("            var f = charClassFailure(pattern, negated);\n");
            sb.append("            trackFailure(")
              .append(expectedUnwrap("f"))
              .append(");\n");
            sb.append("            return f;\n");
            sb.append("        }\n");
        }else {
            sb.append("        if (isAtEnd()) {\n");
            sb.append("            trackFailure(\"[\" + (negated ? \"^\" : \"\") + pattern + \"]\");\n");
            sb.append("            return CstParseResult.failure(\"character class\");\n");
            sb.append("        }\n");
            // Phase 1.7 (D): inline int captures.
            sb.append("        int startPos = pos;\n");
            sb.append("        int startLine = line;\n");
            sb.append("        int startColumn = column;\n");
            sb.append("        char c = peek();\n");
            sb.append("        boolean matches = matchesPattern(c, pattern, caseInsensitive);\n");
            sb.append("        if (negated) matches = !matches;\n");
            sb.append("        if (!matches) {\n");
            sb.append("            trackFailure(\"[\" + (negated ? \"^\" : \"\") + pattern + \"]\");\n");
            sb.append("            return CstParseResult.failure(\"character class\");\n");
            sb.append("        }\n");
        }
        sb.append("        advance();\n");
        // Candidate #4: ASCII pool eliminates fresh 1-char String alloc per match.
        sb.append("        var text = c < 128 ? ASCII_CHAR_STRINGS[c] : String.valueOf(c);\n");
        // Phase 1.7 (D2): primitive-int span; successNoLoc result.
        sb.append("        var span = new SourceSpan(startLine, startColumn, startPos, line, column, pos);\n");
        sb.append("        var node = new CstNode.Terminal(idGen.next(), span, RULE_PEG_CHAR_CLASS, text, ")
          .append(takePendingExpr())
          .append(", List.of());\n");
        sb.append("        return CstParseResult.successNoLoc(node, text);\n");
        sb.append("    }\n");
        sb.append("\n");
        if (config.charClassFailureCache()) {
            sb.append("    private CstParseResult charClassFailure(String pattern, boolean negated) {\n");
            sb.append("        String key = negated ? \"^\" + pattern : pattern;\n");
            sb.append("        CstParseResult r = charClassFailureCache.get(key);\n");
            sb.append("        if (r == null) {\n");
            sb.append("            r = CstParseResult.failure(\"[\" + (negated ? \"^\" : \"\") + pattern + \"]\");\n");
            sb.append("            charClassFailureCache.put(key, r);\n");
            sb.append("        }\n");
            sb.append("        return r;\n");
            sb.append("    }\n");
            sb.append("\n");
        }
    }

    private void emitMatchAnyCst(StringBuilder sb) {
        sb.append("\n");
        sb.append("    private CstParseResult matchAnyCst() {\n");
        sb.append("        if (isAtEnd()) {\n");
        sb.append("            trackFailure(\"any character\");\n");
        sb.append("            return CstParseResult.failure(\"any character\");\n");
        sb.append("        }\n");
        // Phase 1.7 (D): inline int captures — no SourceLocation per any-match.
        sb.append("        int startPos = pos;\n");
        sb.append("        int startLine = line;\n");
        sb.append("        int startColumn = column;\n");
        sb.append("        char c = advance();\n");
        // Candidate #4: ASCII pool eliminates fresh 1-char String alloc per match.
        sb.append("        var text = c < 128 ? ASCII_CHAR_STRINGS[c] : String.valueOf(c);\n");
        // Phase 1.7 (D2): primitive-int span; successNoLoc result.
        sb.append("        var span = new SourceSpan(startLine, startColumn, startPos, line, column, pos);\n");
        sb.append("        var node = new CstNode.Terminal(idGen.next(), span, RULE_PEG_ANY, text, ")
          .append(takePendingExpr())
          .append(", List.of());\n");
        sb.append("        return CstParseResult.successNoLoc(node, text);\n");
        sb.append("    }\n");
        sb.append("\n");
    }
}
