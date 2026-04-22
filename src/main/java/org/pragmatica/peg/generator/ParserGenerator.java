package org.pragmatica.peg.generator;

import org.pragmatica.peg.grammar.Expression;
import org.pragmatica.peg.grammar.Grammar;
import org.pragmatica.peg.grammar.Rule;
import org.pragmatica.peg.grammar.analysis.ExpressionShape;
import org.pragmatica.peg.grammar.analysis.FirstCharAnalysis;
import org.pragmatica.peg.parser.ParserConfig;

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

    private ParserGenerator(Grammar grammar,
                            String packageName,
                            String className,
                            ErrorReporting errorReporting,
                            ParserConfig config) {
        this.grammar = grammar;
        this.packageName = packageName;
        this.className = className;
        this.errorReporting = errorReporting;
        this.config = config;
        this.inWhitespaceRuleGeneration = false;
    }

    public static ParserGenerator create(Grammar grammar, String packageName, String className) {
        return new ParserGenerator(grammar, packageName, className, ErrorReporting.BASIC, ParserConfig.DEFAULT);
    }

    public static ParserGenerator create(Grammar grammar,
                                         String packageName,
                                         String className,
                                         ErrorReporting errorReporting) {
        return new ParserGenerator(grammar, packageName, className, errorReporting, ParserConfig.DEFAULT);
    }

    public static ParserGenerator create(Grammar grammar,
                                         String packageName,
                                         String className,
                                         ParserConfig config) {
        return new ParserGenerator(grammar, packageName, className, ErrorReporting.BASIC, config);
    }

    public static ParserGenerator create(Grammar grammar,
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

            import java.util.ArrayList;
            import java.util.HashMap;
            import java.util.List;
            import java.util.Map;

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
                private String furthestExpected;

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
                    this.furthestExpected = "";
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
                        furthestExpected = expected;
                    } else if (pos == furthestPos && !furthestExpected.contains(expected)) {
                        furthestExpected = furthestExpected.isEmpty() ? expected : furthestExpected + " or " + expected;
                    }
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
                        var expected = !furthestExpected.isEmpty() ? furthestExpected : result.expected.or("valid input");
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
        // Generate action if present
        if (rule.action()
                .isPresent()) {
            var actionCode = transformActionCode(rule.action()
                                                     .unwrap());
            sb.append("            String $0 = substring(startPos, pos);\n");
            sb.append("            Object value;\n");
            sb.append("            ")
              .append(wrapActionCode(actionCode))
              .append("\n");
            sb.append("            result = ParseResult.success(value, pos, line, column);\n");
        }else {
            sb.append("            result = ParseResult.success(\n");
            sb.append("                values.isEmpty() ? substring(startPos, pos) : values.size() == 1 ? values.getFirst() : values,\n");
            sb.append("                pos, line, column);\n");
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

            import java.util.ArrayList;
            import java.util.HashMap;
            import java.util.List;
            import java.util.Map;

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
        // Nested records are implicitly permitted - no need for explicit permits clause
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
                    public static SourceLocation at(int line, int column, int offset) {
                        return new SourceLocation(line, column, offset);
                    }
                    @Override public String toString() { return line + ":" + column; }
                }

                public record SourceSpan(SourceLocation start, SourceLocation end) {
                    public static SourceSpan of(SourceLocation start, SourceLocation end) {
                        return new SourceSpan(start, end);
                    }
                    public int length() { return end.offset() - start.offset(); }
                    public String extract(String source) { return source.substring(start.offset(), end.offset()); }
                    @Override public String toString() { return start + "-" + end; }
                }

                public sealed interface Trivia {
                    SourceSpan span();
                    String text();
                    record Whitespace(SourceSpan span, String text) implements Trivia {}
                    record LineComment(SourceSpan span, String text) implements Trivia {}
                    record BlockComment(SourceSpan span, String text) implements Trivia {}
                }

                public sealed interface CstNode {
                    SourceSpan span();
                    RuleId rule();
                    List<Trivia> leadingTrivia();
                    List<Trivia> trailingTrivia();

                    record Terminal(SourceSpan span, RuleId rule, String text,
                                    List<Trivia> leadingTrivia, List<Trivia> trailingTrivia) implements CstNode {}

                    record NonTerminal(SourceSpan span, RuleId rule, List<CstNode> children,
                                       List<Trivia> leadingTrivia, List<Trivia> trailingTrivia) implements CstNode {}

                    record Token(SourceSpan span, RuleId rule, String text,
                                 List<Trivia> leadingTrivia, List<Trivia> trailingTrivia) implements CstNode {}
            """);
        // Only add Error node type for ADVANCED mode (used in error recovery)
        if (errorReporting == ErrorReporting.ADVANCED) {
            sb.append("""
                        record Error(SourceSpan span, String skippedText, String expected,
                                     List<Trivia> leadingTrivia, List<Trivia> trailingTrivia) implements CstNode {
                            @Override public RuleId rule() { return null; }
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
                        var loc = span.start();
                        sb.append("  --> ");
                        if (filename != null) {
                            sb.append(filename).append(":");
                        }
                        sb.append(loc.line()).append(":").append(loc.column()).append("\\n");

                        // Find all lines we need to display
                        int minLine = span.start().line();
                        int maxLine = span.end().line();
                        for (var label : labels) {
                            minLine = Math.min(minLine, label.span().start().line());
                            maxLine = Math.max(maxLine, label.span().end().line());
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
                        if (span.start().line() <= lineNum && span.end().line() >= lineNum) {
                            if (labels.isEmpty()) {
                                result.add(DiagnosticLabel.primary(span, ""));
                            }
                        }
                        for (var label : labels) {
                            if (label.span().start().line() <= lineNum && label.span().end().line() >= lineNum) {
                                result.add(label);
                            }
                        }
                        return result;
                    }

                    private String formatUnderlines(int lineNum, String lineContent, List<DiagnosticLabel> lineLabels) {
                        var sb = new StringBuilder();
                        int currentCol = 1;

                        var sorted = lineLabels.stream()
                            .sorted((a, b) -> Integer.compare(a.span().start().column(), b.span().start().column()))
                            .toList();

                        for (var label : sorted) {
                            int startCol = label.span().start().line() == lineNum ? label.span().start().column() : 1;
                            int endCol = label.span().end().line() == lineNum
                                ? label.span().end().column()
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
                        var loc = span.start();
                        return String.format("%s:%d:%d: %s: %s",
                            "input", loc.line(), loc.column(), severity.display(), message);
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
                private Map<String, String> captures;
                private int tokenBoundaryDepth;
                private boolean skippingWhitespace;
                private boolean packratEnabled = true;
                private Option<SourceLocation> furthestFailure;
                private Option<String> furthestExpected;

                // Pending leading trivia — trivia captured between sibling
                // sequence elements that will attach to the following sibling's
                // leadingTrivia. Backtracking combinators save/restore snapshots.
                private final ArrayList<Trivia> pendingLeadingTrivia = new ArrayList<>();

                /**
                 * Enable or disable packrat memoization.
                 * Disabling may reduce memory usage for large inputs.
                 */
                public void setPackratEnabled(boolean enabled) {
                    this.packratEnabled = enabled;
                }
            """);
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
                    this.captures = new HashMap<>();
                    this.tokenBoundaryDepth = 0;
                    this.furthestFailure = Option.none();
                    this.furthestExpected = Option.none();
                    this.pendingLeadingTrivia.clear();
            """);
        if (errorReporting == ErrorReporting.ADVANCED) {
            sb.append("""
                        this.diagnostics = new ArrayList<>();
                """);
        }
        sb.append("""
                }

                private SourceLocation location() {
                    return SourceLocation.at(line, column, pos);
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

            """);
        if (config.fastTrackFailure()) {
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
        if (errorReporting == ErrorReporting.ADVANCED) {
            // 0.2.4: emit %recover override around the top-level recovery skip
            // when the start rule carries a %recover directive. Per-rule stacks
            // are not threaded into generated code (scope kept minimal);
            // interpreter-side rule-scoped recovery is available via PegEngine.
            var startRuleOpt = grammar.effectiveStartRule();
            String topRecover = startRuleOpt.isPresent() && startRuleOpt.unwrap()
                                                                        .hasRecover()
                                ? startRuleOpt.unwrap()
                                              .recover()
                                              .unwrap()
                                : "";
            if (!topRecover.isEmpty()) {
                sb.append("        private static final String RECOVERY_OVERRIDE = \"")
                  .append(escape(topRecover))
                  .append("\";\n\n");
                sb.append("""
                        private SourceSpan skipToRecoveryPoint() {
                            var start = location();
                            while (!isAtEnd()) {
                                if (matchesRecoveryOverride()) break;
                                advance();
                            }
                            return SourceSpan.of(start, location());
                        }

                        private boolean matchesRecoveryOverride() {
                            if (remaining() < RECOVERY_OVERRIDE.length()) return false;
                            for (int i = 0; i < RECOVERY_OVERRIDE.length(); i++) {
                                if (peek(i) != RECOVERY_OVERRIDE.charAt(i)) return false;
                            }
                            return true;
                        }

                    """);
            }else {
                sb.append("""
                        private SourceSpan skipToRecoveryPoint() {
                            var start = location();
                            while (!isAtEnd()) {
                                char c = peek();
                                if (c == '\\n' || c == ';' || c == ',' || c == '}' || c == ')' || c == ']') {
                                    break;
                                }
                                advance();
                            }
                            return SourceSpan.of(start, location());
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
        sb.append("""
                // === Public Parse Methods ===

                public Result<CstNode> parse(String input) {
                    init(input);
                    var result = parse_%s();
                    if (result.isFailure()) {
                        var errorLoc = furthestFailure.or(location());
                        var expected = furthestExpected.filter(s -> !s.isEmpty()).or(result.expected.or("valid input"));
                        return Result.failure(new ParseError(errorLoc, "expected " + expected));
                    }
                    var trailingTrivia = skipWhitespace(); // Capture trailing trivia
                    if (!isAtEnd()) {
                        var errorLoc = furthestFailure.or(location());
                        return Result.failure(new ParseError(errorLoc, "unexpected input"));
                    }
                    // Attach trailing trivia to root node
                    var rootNode = attachTrailingTrivia(result.node.unwrap(), trailingTrivia);
                    return Result.success(rootNode);
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

            """.formatted(sanitizedName));
        if (errorReporting == ErrorReporting.ADVANCED) {
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
                            var errorLoc = furthestFailure.or(location());
                            var errorSpan = SourceSpan.of(errorLoc, errorLoc);
                            var expected = furthestExpected.filter(s -> !s.isEmpty()).or(result.expected.or("valid input"));
                            addDiagnostic("expected " + expected, errorSpan);

                            // Skip to recovery point and try to continue
                            var skippedSpan = skipToRecoveryPoint();
                            if (skippedSpan.length() > 0) {
                                var skippedText = skippedSpan.extract(input);
                                var errorNode = new CstNode.Error(skippedSpan, skippedText, expected, List.of(), List.of());
                                return ParseResultWithDiagnostics.withErrors(Option.some(errorNode), diagnostics, input);
                            }
                            return ParseResultWithDiagnostics.withErrors(Option.none(), diagnostics, input);
                        }

                        var trailingTrivia = skipWhitespace();
                        if (!isAtEnd()) {
                            // Unexpected trailing input - use furthest failure position for error
                            var errorLoc = furthestFailure.or(location());
                            var skippedSpan = skipToRecoveryPoint();
                            var errorSpan = SourceSpan.of(errorLoc, skippedSpan.end());
                            addDiagnostic("unexpected input", errorSpan, "expected end of input");

                            // Attach error node to result
                            var rootNode = attachTrailingTrivia(result.node.unwrap(), trailingTrivia);
                            return ParseResultWithDiagnostics.withErrors(Option.some(rootNode), diagnostics, input);
                        }

                        var rootNode = attachTrailingTrivia(result.node.unwrap(), trailingTrivia);
                        if (diagnostics.isEmpty()) {
                            return ParseResultWithDiagnostics.success(rootNode, input);
                        }
                        return ParseResultWithDiagnostics.withErrors(Option.some(rootNode), diagnostics, input);
                    }

                """.formatted(sanitizedName));
        }
    }

    private void generateCstRuleMethods(StringBuilder sb) {
        sb.append("    // === Rule Parsing Methods ===\n\n");
        int ruleId = 0;
        for (var rule : grammar.rules()) {
            generateCstRuleMethod(sb, rule, ruleId++ );
        }
    }

    private void generateCstRuleMethod(StringBuilder sb, Rule rule, int ruleId) {
        var methodName = "parse_" + sanitize(rule.name());
        var ruleName = rule.name();
        boolean inlineLocations = config.inlineLocations();
        // §7.4 selective packrat: when the flag is on AND this rule's (unsanitized) name is in
        // the skip-set, omit the cache lookup and cache put within this rule method. The cache
        // field itself and its use by other rules are preserved. When either the flag is off
        // or the rule is absent from the skip-set, emission is byte-identical to pre-§7.4.
        boolean skipCache = config.selectivePackrat() && config.packratSkipRules()
                                                               .contains(ruleName);
        sb.append("    private CstParseResult ")
          .append(methodName)
          .append("() {\n");
        if (inlineLocations) {
            // §7.3 option A: inline int locals at rule entry — no SourceLocation
            // allocation on the failure path. On success we still materialize a
            // SourceLocation for SourceSpan.of(...), same as before.
            sb.append("        int startOffset = pos;\n");
            sb.append("        int startLine = line;\n");
            sb.append("        int startColumn = column;\n");
        }else {
            sb.append("        var startLoc = location();\n");
        }
        sb.append("        \n");
        if (!skipCache) {
            sb.append("        // Check cache at pre-whitespace position\n");
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
            sb.append("                if (cached.isSuccess()) restoreLocation(cached.endLocation.unwrap());\n");
            sb.append("                return cached;\n");
            sb.append("            }\n");
            sb.append("        }\n");
            sb.append("        \n");
        }
        sb.append("        // Skip leading whitespace and combine with carried pending-leading.\n");
        sb.append("        var carriedLeading = takePendingLeading();\n");
        sb.append("        var localLeadingTrivia = (tokenBoundaryDepth > 0) ? List.<Trivia>of() : skipWhitespace();\n");
        sb.append("        var leadingTrivia = concatTrivia(carriedLeading, localLeadingTrivia);\n");
        var ruleIdConst = toConstantName(ruleName);
        sb.append("        var children = new ArrayList<CstNode>();\n");
        sb.append("        var __ruleName = ")
          .append(ruleIdConst)
          .append(";\n");
        sb.append("        \n");
        var counter = new int[]{0};
        // Mutable counter for unique variable names
        generateCstExpressionCode(sb, rule.expression(), "result", 2, true, counter, false);
        sb.append("        \n");
        sb.append("        CstParseResult finalResult;\n");
        sb.append("        if (result.isSuccess()) {\n");
        sb.append("            var endLoc = location();\n");
        if (inlineLocations) {
            sb.append("            var span = SourceSpan.of(new SourceLocation(startLine, startColumn, startOffset), endLoc);\n");
        }else {
            sb.append("            var span = SourceSpan.of(startLoc, endLoc);\n");
        }
        // Match interpreter's wrapWithRuleName: replace the rule name on whatever node was produced
        sb.append("            var node = wrapWithRuleName(result, children, span, ")
          .append(ruleIdConst)
          .append(", leadingTrivia);\n");
        sb.append("            finalResult = CstParseResult.success(node, result.text.or(\"\"), endLoc);\n");
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
        // combinators can roll it back to their own snapshots.
        sb.append("            if (!carriedLeading.isEmpty()) pendingLeadingTrivia.addAll(carriedLeading);\n");
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
        sb.append("        }\n");
        sb.append("        \n");
        if (!skipCache) {
            sb.append("        if (cache != null) cache.put(key, finalResult);\n");
        }
        sb.append("        return finalResult;\n");
        sb.append("    }\n\n");
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
                   .onPresent(rule -> collectVocabLiterals(rule.expression(), out));
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
            default -> {
                // Terminals without inner literals — skip.
            }
        }
    }

    /**
     * Derive the set of characters that can legally start a whitespace-rule match.
     * Used by the §6.6 skipWhitespace fast-path to short-circuit when the current
     * character cannot possibly begin trivia. Delegates to the shared analysis
     * helper so the generator and the interpreter compute the same set.
     */
    private java.util.Optional<java.util.Set<Character>> whitespaceFirstChars(Expression expr) {
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
                      .append(".node.isPresent()) {\n");
                    sb.append(pad)
                      .append("    children.add(")
                      .append(resultVar)
                      .append(".node.unwrap());\n");
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
                      .append(".node.isPresent()) {\n");
                    sb.append(pad)
                      .append("    children.add(")
                      .append(resultVar)
                      .append(".node.unwrap());\n");
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
                      .append(".node.isPresent()) {\n");
                    sb.append(pad)
                      .append("    children.add(")
                      .append(resultVar)
                      .append(".node.unwrap());\n");
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
                      .append(".node.isPresent()) {\n");
                    sb.append(pad)
                      .append("    children.add(")
                      .append(resultVar)
                      .append(".node.unwrap());\n");
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
                      .append(".node.isPresent()) {\n");
                    sb.append(pad)
                      .append("    children.add(")
                      .append(resultVar)
                      .append(".node.unwrap());\n");
                    sb.append(pad)
                      .append("}\n");
                }
            }
            case Expression.Sequence seq -> {
                var seqStart = "seqStart" + id;
                var seqPending = "seqPending" + id;
                var cutVar = "cut" + id;
                sb.append(pad)
                  .append("CstParseResult ")
                  .append(resultVar)
                  .append(" = CstParseResult.success(null, \"\", location());\n");
                sb.append(pad)
                  .append("var ")
                  .append(seqStart)
                  .append(" = location();\n");
                // Snapshot pending-leading so any between-element trivia appended
                // inside this sequence can be rolled back on failure.
                sb.append(pad)
                  .append("int ")
                  .append(seqPending)
                  .append(" = savePendingLeading();\n");
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
                    // Skip whitespace before non-predicate elements (matching interpreter behavior);
                    // captured trivia is appended to the pending-leading buffer so the following
                    // element claims it.
                    if (!inWhitespaceRule && !isPredicate(elem)) {
                        sb.append(pad)
                          .append("    if (tokenBoundaryDepth == 0) appendPending(skipWhitespace());\n");
                    }
                    var elemVar = "elem" + id + "_" + i;
                    generateCstExpressionCode(sb, elem, elemVar, indent + 1, addToChildren, counter, inWhitespaceRule);
                    // Check for cut failure propagation first
                    sb.append(pad)
                      .append("    if (")
                      .append(elemVar)
                      .append(".isCutFailure()) {\n");
                    sb.append(pad)
                      .append("        restoreLocation(")
                      .append(seqStart)
                      .append(");\n");
                    sb.append(pad)
                      .append("        restorePendingLeading(")
                      .append(seqPending)
                      .append(");\n");
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
                      .append("        restoreLocation(")
                      .append(seqStart)
                      .append(");\n");
                    sb.append(pad)
                      .append("        restorePendingLeading(")
                      .append(seqPending)
                      .append(");\n");
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
                    // Track if this element was a cut
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
                  .append(" = CstParseResult.success(null, substring(")
                  .append(seqStart)
                  .append(".offset(), pos), location());\n");
                sb.append(pad)
                  .append("}\n");
            }
            case Expression.Choice choice -> {
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
                  .append("var ")
                  .append(choiceStart)
                  .append(" = location();\n");
                // Snapshot pending-leading so failed alternatives can be rolled
                // back — captured trivia inside one alt must not leak forward
                // into sibling alternatives.
                sb.append(pad)
                  .append("int choicePending")
                  .append(id)
                  .append(" = savePendingLeading();\n");
                // Don't skip whitespace here - let alternatives capture trivia themselves
                emitChildrenSave(sb, pad, childrenState, addToChildren);
                var classified = config.choiceDispatch()
                                 ? ChoiceDispatchAnalyzer.classify(choice)
                                 : java.util.Optional.<java.util.List<ChoiceDispatchAnalyzer.AltEntry>>empty();
                if (classified.isPresent()) {
                    var grouped = ChoiceDispatchAnalyzer.groupByChar(classified.get());
                    var buckets = ChoiceDispatchAnalyzer.buckets(grouped);
                    emitCstChoiceDispatch(sb,
                                          buckets,
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
                sb.append(pad)
                  .append("    restorePendingLeading(choicePending")
                  .append(id)
                  .append(");\n");
                sb.append(pad)
                  .append("    ")
                  .append(resultVar)
                  .append(" = CstParseResult.failure(\"one of alternatives\");\n");
                sb.append(pad)
                  .append("}\n");
            }
            case Expression.ZeroOrMore zom -> {
                var zomStart = "zomStart" + id;
                var beforeLoc = "beforeLoc" + id;
                var zomElem = "zomElem" + id;
                var savedChildrenZom = "savedChildrenZom" + id;
                sb.append(pad)
                  .append("CstParseResult ")
                  .append(resultVar)
                  .append(" = CstParseResult.success(null, \"\", location());\n");
                sb.append(pad)
                  .append("var ")
                  .append(zomStart)
                  .append(" = location();\n");
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
                  .append("    var ")
                  .append(beforeLoc)
                  .append(" = location();\n");
                sb.append(pad)
                  .append("    int zomIterPending")
                  .append(id)
                  .append(" = savePendingLeading();\n");
                if (!inWhitespaceRule) {
                    sb.append(pad)
                      .append("    if (tokenBoundaryDepth == 0) appendPending(skipWhitespace());\n");
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
                  .append(".isFailure() || location().offset() == ")
                  .append(beforeLoc)
                  .append(".offset()) {\n");
                sb.append(pad)
                  .append("        restoreLocation(")
                  .append(beforeLoc)
                  .append(");\n");
                sb.append(pad)
                  .append("        restorePendingLeading(zomIterPending")
                  .append(id)
                  .append(");\n");
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
                      .append(" = SourceSpan.of(")
                      .append(zomStart)
                      .append(", location());\n");
                    sb.append(pad)
                      .append("        children.add(new CstNode.NonTerminal(zomSpan")
                      .append(id)
                      .append(", __ruleName, zomChildren")
                      .append(id)
                      .append(", List.of(), List.of()));\n");
                    sb.append(pad)
                      .append("    }\n");
                    sb.append(pad)
                      .append("    ")
                      .append(resultVar)
                      .append(" = CstParseResult.success(null, substring(")
                      .append(zomStart)
                      .append(".offset(), pos), location());\n");
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
                      .append(" = CstParseResult.success(null, substring(")
                      .append(zomStart)
                      .append(".offset(), pos), location());\n");
                    sb.append(pad)
                      .append("}\n");
                }
            }
            case Expression.OneOrMore oom -> {
                var oomFirst = "oomFirst" + id;
                var oomStart = "oomStart" + id;
                var beforeLoc = "beforeLoc" + id;
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
                  .append(" = savePendingLeading();\n");
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
                  .append("var ")
                  .append(oomStart)
                  .append(" = location();\n");
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
                sb.append(pad)
                  .append("if (")
                  .append(oomFirst)
                  .append(".isSuccess()) {\n");
                sb.append(pad)
                  .append("    while (true) {\n");
                sb.append(pad)
                  .append("        var ")
                  .append(beforeLoc)
                  .append(" = location();\n");
                sb.append(pad)
                  .append("        int oomIterPending")
                  .append(id)
                  .append(" = savePendingLeading();\n");
                if (!inWhitespaceRule) {
                    sb.append(pad)
                      .append("        if (tokenBoundaryDepth == 0) appendPending(skipWhitespace());\n");
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
                  .append(".isFailure() || location().offset() == ")
                  .append(beforeLoc)
                  .append(".offset()) {\n");
                sb.append(pad)
                  .append("            restoreLocation(")
                  .append(beforeLoc)
                  .append(");\n");
                sb.append(pad)
                  .append("            restorePendingLeading(oomIterPending")
                  .append(id)
                  .append(");\n");
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
                      .append(" = SourceSpan.of(")
                      .append(oomStart)
                      .append(", location());\n");
                    sb.append(pad)
                      .append("        children.add(new CstNode.NonTerminal(oomSpan")
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
                var optStart = "optStart" + id;
                var optElem = "optElem" + id;
                var savedChildrenOpt = "savedChildrenOpt" + id;
                sb.append(pad)
                  .append("var ")
                  .append(optStart)
                  .append(" = location();\n");
                sb.append(pad)
                  .append("int optPending")
                  .append(id)
                  .append(" = savePendingLeading();\n");
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
                  .append("    restoreLocation(")
                  .append(optStart)
                  .append(");\n");
                sb.append(pad)
                  .append("    restorePendingLeading(optPending")
                  .append(id)
                  .append(");\n");
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
                  .append("    restoreLocation(")
                  .append(optStart)
                  .append(");\n");
                sb.append(pad)
                  .append("    restorePendingLeading(optPending")
                  .append(id)
                  .append(");\n");
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
                  .append(" = CstParseResult.success(null, \"\", location());\n");
                sb.append(pad)
                  .append("}\n");
            }
            case Expression.Repetition rep -> {
                var repCount = "repCount" + id;
                var repStart = "repStart" + id;
                var beforeLoc = "beforeLoc" + id;
                var repElem = "repElem" + id;
                var repCutFailed = "repCutFailed" + id;
                var savedChildrenRep = "savedChildrenRep" + id;
                sb.append(pad)
                  .append("int ")
                  .append(repCount)
                  .append(" = 0;\n");
                sb.append(pad)
                  .append("var ")
                  .append(repStart)
                  .append(" = location();\n");
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
                  .append("    var ")
                  .append(beforeLoc)
                  .append(" = location();\n");
                sb.append(pad)
                  .append("    int repIterPending")
                  .append(id)
                  .append(" = savePendingLeading();\n");
                if (!inWhitespaceRule) {
                    sb.append(pad)
                      .append("    if (")
                      .append(repCount)
                      .append(" > 0 && tokenBoundaryDepth == 0) appendPending(skipWhitespace());\n");
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
                  .append(".isFailure() || location().offset() == ")
                  .append(beforeLoc)
                  .append(".offset()) {\n");
                sb.append(pad)
                  .append("        restoreLocation(")
                  .append(beforeLoc)
                  .append(");\n");
                sb.append(pad)
                  .append("        restorePendingLeading(repIterPending")
                  .append(id)
                  .append(");\n");
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
                      .append(" = SourceSpan.of(")
                      .append(repStart)
                      .append(", location());\n");
                    sb.append(pad)
                      .append("        children.add(new CstNode.NonTerminal(repSpan")
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
                  .append(" = CstParseResult.success(null, substring(")
                  .append(repStart)
                  .append(".offset(), pos), location());\n");
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
                  .append("    restoreLocation(")
                  .append(repStart)
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
                var andStart = "andStart" + id;
                var savedChildren = "savedChildrenAnd" + id;
                var andElem = "andElem" + id;
                sb.append(pad)
                  .append("var ")
                  .append(andStart)
                  .append(" = location();\n");
                sb.append(pad)
                  .append("int andPending")
                  .append(id)
                  .append(" = savePendingLeading();\n");
                if (addToChildren) {
                    sb.append(pad)
                      .append("var ")
                      .append(savedChildren)
                      .append(" = new ArrayList<>(children);\n");
                }
                generateCstExpressionCode(sb, and.expression(), andElem, indent, false, counter, inWhitespaceRule);
                sb.append(pad)
                  .append("restoreLocation(")
                  .append(andStart)
                  .append(");\n");
                // Predicates don't consume trivia state either.
                sb.append(pad)
                  .append("restorePendingLeading(andPending")
                  .append(id)
                  .append(");\n");
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
                  .append(".isSuccess() ? CstParseResult.success(null, \"\", location()) : ")
                  .append(andElem)
                  .append(";\n");
            }
            case Expression.Not not -> {
                var notStart = "notStart" + id;
                var savedChildren = "savedChildrenNot" + id;
                var notElem = "notElem" + id;
                sb.append(pad)
                  .append("var ")
                  .append(notStart)
                  .append(" = location();\n");
                sb.append(pad)
                  .append("int notPending")
                  .append(id)
                  .append(" = savePendingLeading();\n");
                if (addToChildren) {
                    sb.append(pad)
                      .append("var ")
                      .append(savedChildren)
                      .append(" = new ArrayList<>(children);\n");
                }
                generateCstExpressionCode(sb, not.expression(), notElem, indent, false, counter, inWhitespaceRule);
                sb.append(pad)
                  .append("restoreLocation(")
                  .append(notStart)
                  .append(");\n");
                sb.append(pad)
                  .append("restorePendingLeading(notPending")
                  .append(id)
                  .append(");\n");
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
                  .append(".isSuccess() ? CstParseResult.failure(\"not match\") : CstParseResult.success(null, \"\", location());\n");
            }
            case Expression.TokenBoundary tb -> {
                var tbStart = "tbStart" + id;
                var savedChildren = "savedChildrenTb" + id;
                var tbElem = "tbElem" + id;
                sb.append(pad)
                  .append("var ")
                  .append(tbStart)
                  .append(" = location();\n");
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
                  .append(tbStart)
                  .append(".offset(), pos);\n");
                sb.append(pad)
                  .append("    var tbSpan")
                  .append(id)
                  .append(" = SourceSpan.of(")
                  .append(tbStart)
                  .append(", location());\n");
                var tokenRuleId = inWhitespaceRule
                                  ? "RULE_PEG_TOKEN"
                                  : "__ruleName";
                sb.append(pad)
                  .append("    var tbNode")
                  .append(id)
                  .append(" = new CstNode.Token(tbSpan")
                  .append(id)
                  .append(", ")
                  .append(tokenRuleId)
                  .append(", tbText")
                  .append(id)
                  .append(", takePendingLeading(), List.of());\n");
                if (addToChildren) {
                    sb.append(pad)
                      .append("    children.add(tbNode")
                      .append(id)
                      .append(");\n");
                }
                sb.append(pad)
                  .append("    ")
                  .append(resultVar)
                  .append(" = CstParseResult.success(tbNode")
                  .append(id)
                  .append(", tbText")
                  .append(id)
                  .append(", location());\n");
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
                  .append(".isSuccess() ? CstParseResult.success(null, \"\", location()) : ")
                  .append(ignElem)
                  .append(";\n");
            }
            case Expression.Capture cap -> {
                var capStart = "capStart" + id;
                var capElem = "capElem" + id;
                sb.append(pad)
                  .append("var ")
                  .append(capStart)
                  .append(" = location();\n");
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
                  .append(capStart)
                  .append(".offset(), pos));\n");
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
                      .append(".node.isPresent()) {\n");
                    sb.append(pad)
                      .append("    children.add(")
                      .append(resultVar)
                      .append(".node.unwrap());\n");
                    sb.append(pad)
                      .append("}\n");
                }
            }
            case Expression.Cut cut -> {
                sb.append(pad)
                  .append("var ")
                  .append(resultVar)
                  .append(" = CstParseResult.success(null, \"\", location());\n");
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
          .append("    restoreLocation(")
          .append(choiceStart)
          .append(");\n");
        // Roll back any pending-leading trivia captured inside the failed alt,
        // so sibling alternatives start from the same buffer state as the Choice entry.
        sb.append(pad)
          .append("    restorePendingLeading(choicePending")
          .append(id)
          .append(");\n");
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
                var chars = charsOpt.get();
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
            sb.append("            var wsStartLoc = location();\n");
            sb.append("            var wsStartPos = pos;\n");
            generateCstExpressionCode(sb, innerExpr, "wsResult", 3, false, new int[]{0}, true);
            sb.append("            if (wsResult.isFailure() || pos == wsStartPos) break;\n");
            sb.append("            var wsText = substring(wsStartPos, pos);\n");
            sb.append("            var wsSpan = SourceSpan.of(wsStartLoc, location());\n");
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
                    if (result.node.isPresent()) {
                        var inner = result.node.unwrap();
                        return switch (inner) {
                            case CstNode.Token tok -> new CstNode.Token(span, ruleId, tok.text(), leadingTrivia, List.of());
                            case CstNode.Terminal t -> new CstNode.Terminal(span, ruleId, t.text(), leadingTrivia, List.of());
                            case CstNode.NonTerminal nt -> new CstNode.NonTerminal(span, ruleId, nt.children(), leadingTrivia, List.of());
            """);
        // Add Error case only for ADVANCED mode
        if (errorReporting == ErrorReporting.ADVANCED) {
            sb.append("""
                            case CstNode.Error err -> new CstNode.NonTerminal(span, ruleId, children, leadingTrivia, List.of());
                """);
        }
        sb.append("""
                        };
                    }
                    // No inner node — wrap children in NonTerminal
                    return new CstNode.NonTerminal(span, ruleId, children, leadingTrivia, List.of());
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

                private List<Trivia> concatTrivia(List<Trivia> first, List<Trivia> second) {
                    if (first.isEmpty()) return second;
                    if (second.isEmpty()) return first;
                    var combined = new ArrayList<Trivia>(first.size() + second.size());
                    combined.addAll(first);
                    combined.addAll(second);
                    return List.copyOf(combined);
                }

                private CstNode attachTrailingTrivia(CstNode node, List<Trivia> trailingTrivia) {
                    if (trailingTrivia.isEmpty()) {
                        return node;
                    }
                    return switch (node) {
                        case CstNode.Terminal t -> new CstNode.Terminal(
                            t.span(), t.rule(), t.text(), t.leadingTrivia(), trailingTrivia
                        );
                        case CstNode.NonTerminal nt -> new CstNode.NonTerminal(
                            nt.span(), nt.rule(), nt.children(), nt.leadingTrivia(), trailingTrivia
                        );
                        case CstNode.Token tok -> new CstNode.Token(
                            tok.span(), tok.rule(), tok.text(), tok.leadingTrivia(), trailingTrivia
                        );
            """);
        // Add Error case only for ADVANCED mode
        if (errorReporting == ErrorReporting.ADVANCED) {
            sb.append("""
                            case CstNode.Error err -> new CstNode.Error(
                                err.span(), err.skippedText(), err.expected(), err.leadingTrivia(), trailingTrivia
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

    // === Match-method emitters (phase 1 perf flags) ===
    private void emitMatchLiteralCst(StringBuilder sb) {
        var endLocVar = config.reuseEndLocation()
                        ? "endLoc"
                        : "location()";
        var endLocDecl = config.reuseEndLocation()
                         ? "        var endLoc = location();\n"
                         : "";
        sb.append("\n");
        sb.append("    private CstParseResult matchLiteralCst(String text, boolean caseInsensitive) {\n");
        if (config.literalFailureCache()) {
            sb.append("        int len = text.length();\n");
            sb.append("        if (input.length() - pos < len) {\n");
            sb.append("            var f = literalFailure(text);\n");
            sb.append("            trackFailure(f.expected.unwrap());\n");
            sb.append("            return f;\n");
            sb.append("        }\n");
            sb.append("        var startLoc = location();\n");
            sb.append("        if (caseInsensitive) {\n");
            sb.append("            for (int i = 0; i < len; i++) {\n");
            sb.append("                if (Character.toLowerCase(text.charAt(i)) != Character.toLowerCase(input.charAt(pos + i))) {\n");
            sb.append("                    var f = literalFailure(text);\n");
            sb.append("                    trackFailure(f.expected.unwrap());\n");
            sb.append("                    return f;\n");
            sb.append("                }\n");
            sb.append("            }\n");
            sb.append("        } else {\n");
            sb.append("            for (int i = 0; i < len; i++) {\n");
            sb.append("                if (text.charAt(i) != input.charAt(pos + i)) {\n");
            sb.append("                    var f = literalFailure(text);\n");
            sb.append("                    trackFailure(f.expected.unwrap());\n");
            sb.append("                    return f;\n");
            sb.append("                }\n");
            sb.append("            }\n");
            sb.append("        }\n");
        }else {
            sb.append("        if (remaining() < text.length()) {\n");
            sb.append("            trackFailure(\"'\" + text + \"'\");\n");
            sb.append("            return CstParseResult.failure(\"'\" + text + \"'\");\n");
            sb.append("        }\n");
            sb.append("        var startLoc = location();\n");
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
        sb.append(endLocDecl);
        sb.append("        var span = SourceSpan.of(startLoc, ")
          .append(endLocVar)
          .append(");\n");
        sb.append("        var node = new CstNode.Terminal(span, RULE_PEG_LITERAL, text, takePendingLeading(), List.of());\n");
        sb.append("        return CstParseResult.success(node, text, ")
          .append(endLocVar)
          .append(");\n");
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
        var endLocVar = config.reuseEndLocation()
                        ? "endLoc"
                        : "location()";
        var endLocDecl = config.reuseEndLocation()
                         ? "        var endLoc = location();\n"
                         : "";
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
        sb.append("        var startLoc = location();\n");
        sb.append("        for (int i = 0; i < longestLen; i++) {\n");
        sb.append("            advance();\n");
        sb.append("        }\n");
        sb.append(endLocDecl);
        sb.append("        var span = SourceSpan.of(startLoc, ")
          .append(endLocVar)
          .append(");\n");
        sb.append("        var node = new CstNode.Terminal(span, RULE_PEG_LITERAL, longestMatch, takePendingLeading(), List.of());\n");
        sb.append("        return CstParseResult.success(node, longestMatch, ")
          .append(endLocVar)
          .append(");\n");
        sb.append("    }\n");
        sb.append("\n");
    }

    private void emitMatchCharClassCst(StringBuilder sb) {
        var endLocVar = config.reuseEndLocation()
                        ? "endLoc"
                        : "location()";
        var endLocDecl = config.reuseEndLocation()
                         ? "        var endLoc = location();\n"
                         : "";
        sb.append("\n");
        sb.append("    private CstParseResult matchCharClassCst(String pattern, boolean negated, boolean caseInsensitive) {\n");
        if (config.charClassFailureCache()) {
            sb.append("        if (isAtEnd()) {\n");
            sb.append("            var f = charClassFailure(pattern, negated);\n");
            sb.append("            trackFailure(f.expected.unwrap());\n");
            sb.append("            return f;\n");
            sb.append("        }\n");
            sb.append("        var startLoc = location();\n");
            sb.append("        char c = peek();\n");
            sb.append("        boolean matches = matchesPattern(c, pattern, caseInsensitive);\n");
            sb.append("        if (negated) matches = !matches;\n");
            sb.append("        if (!matches) {\n");
            sb.append("            var f = charClassFailure(pattern, negated);\n");
            sb.append("            trackFailure(f.expected.unwrap());\n");
            sb.append("            return f;\n");
            sb.append("        }\n");
        }else {
            sb.append("        if (isAtEnd()) {\n");
            sb.append("            trackFailure(\"[\" + (negated ? \"^\" : \"\") + pattern + \"]\");\n");
            sb.append("            return CstParseResult.failure(\"character class\");\n");
            sb.append("        }\n");
            sb.append("        var startLoc = location();\n");
            sb.append("        char c = peek();\n");
            sb.append("        boolean matches = matchesPattern(c, pattern, caseInsensitive);\n");
            sb.append("        if (negated) matches = !matches;\n");
            sb.append("        if (!matches) {\n");
            sb.append("            trackFailure(\"[\" + (negated ? \"^\" : \"\") + pattern + \"]\");\n");
            sb.append("            return CstParseResult.failure(\"character class\");\n");
            sb.append("        }\n");
        }
        sb.append("        advance();\n");
        sb.append("        var text = String.valueOf(c);\n");
        sb.append(endLocDecl);
        sb.append("        var span = SourceSpan.of(startLoc, ")
          .append(endLocVar)
          .append(");\n");
        sb.append("        var node = new CstNode.Terminal(span, RULE_PEG_CHAR_CLASS, text, takePendingLeading(), List.of());\n");
        sb.append("        return CstParseResult.success(node, text, ")
          .append(endLocVar)
          .append(");\n");
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
        var endLocVar = config.reuseEndLocation()
                        ? "endLoc"
                        : "location()";
        var endLocDecl = config.reuseEndLocation()
                         ? "        var endLoc = location();\n"
                         : "";
        sb.append("\n");
        sb.append("    private CstParseResult matchAnyCst() {\n");
        sb.append("        if (isAtEnd()) {\n");
        sb.append("            trackFailure(\"any character\");\n");
        sb.append("            return CstParseResult.failure(\"any character\");\n");
        sb.append("        }\n");
        sb.append("        var startLoc = location();\n");
        sb.append("        char c = advance();\n");
        sb.append("        var text = String.valueOf(c);\n");
        sb.append(endLocDecl);
        sb.append("        var span = SourceSpan.of(startLoc, ")
          .append(endLocVar)
          .append(");\n");
        sb.append("        var node = new CstNode.Terminal(span, RULE_PEG_ANY, text, takePendingLeading(), List.of());\n");
        sb.append("        return CstParseResult.success(node, text, ")
          .append(endLocVar)
          .append(");\n");
        sb.append("    }\n");
        sb.append("\n");
    }
}
