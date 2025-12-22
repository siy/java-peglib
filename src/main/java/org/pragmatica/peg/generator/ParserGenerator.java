package org.pragmatica.peg.generator;

import org.pragmatica.peg.grammar.Expression;
import org.pragmatica.peg.grammar.Grammar;
import org.pragmatica.peg.grammar.Rule;

/**
 * Generates standalone parser source code from a Grammar.
 * The generated parser depends only on pragmatica-lite:core.
 */
public final class ParserGenerator {

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

    private ParserGenerator(Grammar grammar, String packageName, String className, ErrorReporting errorReporting) {
        this.grammar = grammar;
        this.packageName = packageName;
        this.className = className;
        this.errorReporting = errorReporting;
    }

    public static ParserGenerator create(Grammar grammar, String packageName, String className) {
        return new ParserGenerator(grammar, packageName, className, ErrorReporting.BASIC);
    }

    public static ParserGenerator create(Grammar grammar, String packageName, String className, ErrorReporting errorReporting) {
        return new ParserGenerator(grammar, packageName, className, errorReporting);
    }

    public String generate() {
        var sb = new StringBuilder();

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
        var sb = new StringBuilder();

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
        sb.append("package ").append(packageName).append(";\n\n");
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
        sb.append("public final class ").append(className).append(" {\n\n");

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

                private void init(String input) {
                    this.input = input;
                    this.pos = 0;
                    this.line = 1;
                    this.column = 1;
                    this.cache = new HashMap<>();
                    this.captures = new HashMap<>();
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

            """);
    }

    private void generateParseMethods(StringBuilder sb) {
        var startRule = grammar.effectiveStartRule();
        var startRuleName = startRule.isPresent() ? startRule.unwrap().name() : grammar.rules().getFirst().name();

        sb.append("""
                // === Public Parse Methods ===

                public Result<Object> parse(String input) {
                    init(input);
                    var result = parse_%s();
                    if (result.isFailure()) {
                        return Result.failure(new ParseError(line, column, "expected " + result.expected));
                    }
                    if (!isAtEnd()) {
                        return Result.failure(new ParseError(line, column, "unexpected input"));
                    }
                    return Result.success(result.value);
                }

            """.formatted(sanitize(startRuleName)));
    }

    private void generateRuleMethods(StringBuilder sb) {
        sb.append("    // === Rule Parsing Methods ===\n\n");

        int ruleId = 0;
        for (var rule : grammar.rules()) {
            generateRuleMethod(sb, rule, ruleId++);
        }
    }

    private void generateRuleMethod(StringBuilder sb, Rule rule, int ruleId) {
        var methodName = "parse_" + sanitize(rule.name());

        sb.append("    private ParseResult ").append(methodName).append("() {\n");
        sb.append("        int startPos = pos;\n");
        sb.append("        int startLine = line;\n");
        sb.append("        int startColumn = column;\n");
        sb.append("        \n");
        sb.append("        // Check cache\n");
        sb.append("        long key = cacheKey(").append(ruleId).append(", startPos);\n");
        sb.append("        var cached = cache.get(key);\n");
        sb.append("        if (cached != null) {\n");
        sb.append("            pos = cached.endPos;\n");
        sb.append("            line = cached.endLine;\n");
        sb.append("            column = cached.endColumn;\n");
        sb.append("            return cached;\n");
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
        if (rule.action().isPresent()) {
            var actionCode = transformActionCode(rule.action().unwrap());
            sb.append("            String $0 = substring(startPos, pos);\n");
            sb.append("            Object value;\n");
            sb.append("            ").append(wrapActionCode(actionCode)).append("\n");
            sb.append("            result = ParseResult.success(value, pos, line, column);\n");
        } else {
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
            sb.append("            result = ParseResult.failure(\"").append(escape(rule.errorMessage().unwrap())).append("\");\n");
        }
        sb.append("        }\n");
        sb.append("        \n");
        sb.append("        cache.put(key, result);\n");
        sb.append("        return result;\n");
        sb.append("    }\n\n");
    }

    private void generateExpressionCode(StringBuilder sb, Expression expr, String resultVar, int indent, int[] counter) {
        var pad = "    ".repeat(indent);
        int id = counter[0]++;  // Get unique ID for this expression

        switch (expr) {
            case Expression.Literal lit -> {
                sb.append(pad).append("var ").append(resultVar).append(" = matchLiteral(\"")
                    .append(escape(lit.text())).append("\", ").append(lit.caseInsensitive()).append(");\n");
            }
            case Expression.CharClass cc -> {
                sb.append(pad).append("var ").append(resultVar).append(" = matchCharClass(\"")
                    .append(escape(cc.pattern())).append("\", ")
                    .append(cc.negated()).append(", ")
                    .append(cc.caseInsensitive()).append(");\n");
            }
            case Expression.Dictionary dict -> {
                sb.append(pad).append("var ").append(resultVar).append(" = matchDictionary(List.of(");
                var words = dict.words();
                for (int i = 0; i < words.size(); i++) {
                    if (i > 0) sb.append(", ");
                    sb.append("\"").append(escape(words.get(i))).append("\"");
                }
                sb.append("), ").append(dict.caseInsensitive()).append(");\n");
            }
            case Expression.Any any -> {
                sb.append(pad).append("var ").append(resultVar).append(" = matchAny();\n");
            }
            case Expression.Reference ref -> {
                sb.append(pad).append("var ").append(resultVar).append(" = parse_")
                    .append(sanitize(ref.ruleName())).append("();\n");
                sb.append(pad).append("if (").append(resultVar).append(".isSuccess() && ")
                    .append(resultVar).append(".value != null) {\n");
                sb.append(pad).append("    values.add(").append(resultVar).append(".value);\n");
                sb.append(pad).append("}\n");
            }
            case Expression.Sequence seq -> {
                var seqStart = "seqStart" + id;
                var cutVar = "cut" + id;
                sb.append(pad).append("ParseResult ").append(resultVar).append(" = ParseResult.success(null, pos, line, column);\n");
                sb.append(pad).append("int ").append(seqStart).append(" = pos;\n");
                sb.append(pad).append("int ").append(seqStart).append("Line = line;\n");
                sb.append(pad).append("int ").append(seqStart).append("Column = column;\n");
                sb.append(pad).append("boolean ").append(cutVar).append(" = false;\n");
                int i = 0;
                for (var elem : seq.elements()) {
                    sb.append(pad).append("skipWhitespace();\n");
                    generateExpressionCode(sb, elem, "elem" + id + "_" + i, indent, counter);
                    // Check for cut failure propagation
                    sb.append(pad).append("if (elem").append(id).append("_").append(i).append(".isCutFailure()) {\n");
                    sb.append(pad).append("    pos = ").append(seqStart).append(";\n");
                    sb.append(pad).append("    line = ").append(seqStart).append("Line;\n");
                    sb.append(pad).append("    column = ").append(seqStart).append("Column;\n");
                    sb.append(pad).append("    ").append(resultVar).append(" = elem").append(id).append("_").append(i).append(";\n");
                    sb.append(pad).append("} else if (elem").append(id).append("_").append(i).append(".isFailure()) {\n");
                    sb.append(pad).append("    pos = ").append(seqStart).append(";\n");
                    sb.append(pad).append("    line = ").append(seqStart).append("Line;\n");
                    sb.append(pad).append("    column = ").append(seqStart).append("Column;\n");
                    sb.append(pad).append("    ").append(resultVar).append(" = ").append(cutVar).append(" ? elem").append(id).append("_").append(i).append(".asCutFailure() : elem").append(id).append("_").append(i).append(";\n");
                    sb.append(pad).append("}\n");
                    // Track if this element was a cut
                    if (elem instanceof Expression.Cut) {
                        sb.append(pad).append(cutVar).append(" = true;\n");
                    }
                    i++;
                }
            }
            case Expression.Choice choice -> {
                var choiceStart = "choiceStart" + id;
                var oldVals = "oldValues" + id;
                sb.append(pad).append("ParseResult ").append(resultVar).append(" = null;\n");
                sb.append(pad).append("int ").append(choiceStart).append(" = pos;\n");
                sb.append(pad).append("int ").append(choiceStart).append("Line = line;\n");
                sb.append(pad).append("int ").append(choiceStart).append("Column = column;\n");
                int i = 0;
                for (var alt : choice.alternatives()) {
                    sb.append(pad).append("var choiceValues").append(id).append("_").append(i).append(" = new ArrayList<Object>();\n");
                    sb.append(pad).append("var ").append(oldVals).append("_").append(i).append(" = values;\n");
                    sb.append(pad).append("values = choiceValues").append(id).append("_").append(i).append(";\n");
                    generateExpressionCode(sb, alt, "alt" + id + "_" + i, indent, counter);
                    sb.append(pad).append("values = ").append(oldVals).append("_").append(i).append(";\n");
                    sb.append(pad).append("if (alt").append(id).append("_").append(i).append(".isSuccess()) {\n");
                    sb.append(pad).append("    values.addAll(choiceValues").append(id).append("_").append(i).append(");\n");
                    sb.append(pad).append("    ").append(resultVar).append(" = alt").append(id).append("_").append(i).append(";\n");
                    sb.append(pad).append("} else if (alt").append(id).append("_").append(i).append(".isCutFailure()) {\n");
                    // Convert CutFailure to regular failure for parent choices to allow backtracking at higher levels
                    sb.append(pad).append("    ").append(resultVar).append(" = alt").append(id).append("_").append(i).append(".asRegularFailure();\n");
                    sb.append(pad).append("} else {\n");
                    sb.append(pad).append("    pos = ").append(choiceStart).append(";\n");
                    sb.append(pad).append("    line = ").append(choiceStart).append("Line;\n");
                    sb.append(pad).append("    column = ").append(choiceStart).append("Column;\n");
                    i++;
                }
                // Close all else blocks
                for (int j = 0; j < choice.alternatives().size(); j++) {
                    sb.append(pad).append("}\n");
                }
                sb.append(pad).append("if (").append(resultVar).append(" == null) {\n");
                sb.append(pad).append("    ").append(resultVar).append(" = ParseResult.failure(\"one of alternatives\");\n");
                sb.append(pad).append("}\n");
            }
            case Expression.ZeroOrMore zom -> {
                var zomElem = "zomElem" + id;
                var beforePos = "beforePos" + id;
                sb.append(pad).append("ParseResult ").append(resultVar).append(" = ParseResult.success(null, pos, line, column);\n");
                sb.append(pad).append("while (true) {\n");
                sb.append(pad).append("    int ").append(beforePos).append(" = pos;\n");
                sb.append(pad).append("    int ").append(beforePos).append("Line = line;\n");
                sb.append(pad).append("    int ").append(beforePos).append("Column = column;\n");
                sb.append(pad).append("    skipWhitespace();\n");
                generateExpressionCode(sb, zom.expression(), zomElem, indent + 1, counter);
                sb.append(pad).append("    if (").append(zomElem).append(".isFailure() || pos == ").append(beforePos).append(") {\n");
                sb.append(pad).append("        pos = ").append(beforePos).append(";\n");
                sb.append(pad).append("        line = ").append(beforePos).append("Line;\n");
                sb.append(pad).append("        column = ").append(beforePos).append("Column;\n");
                sb.append(pad).append("        break;\n");
                sb.append(pad).append("    }\n");
                sb.append(pad).append("}\n");
            }
            case Expression.OneOrMore oom -> {
                var oomFirst = "oomFirst" + id;
                var oomElem = "oomElem" + id;
                var beforePos = "beforePos" + id;
                generateExpressionCode(sb, oom.expression(), oomFirst, indent, counter);
                sb.append(pad).append("var ").append(resultVar).append(" = ").append(oomFirst).append(";\n");
                sb.append(pad).append("if (").append(oomFirst).append(".isSuccess()) {\n");
                sb.append(pad).append("    while (true) {\n");
                sb.append(pad).append("        int ").append(beforePos).append(" = pos;\n");
                sb.append(pad).append("        int ").append(beforePos).append("Line = line;\n");
                sb.append(pad).append("        int ").append(beforePos).append("Column = column;\n");
                sb.append(pad).append("        skipWhitespace();\n");
                generateExpressionCode(sb, oom.expression(), oomElem, indent + 2, counter);
                sb.append(pad).append("        if (").append(oomElem).append(".isFailure() || pos == ").append(beforePos).append(") {\n");
                sb.append(pad).append("            pos = ").append(beforePos).append(";\n");
                sb.append(pad).append("            line = ").append(beforePos).append("Line;\n");
                sb.append(pad).append("            column = ").append(beforePos).append("Column;\n");
                sb.append(pad).append("            break;\n");
                sb.append(pad).append("        }\n");
                sb.append(pad).append("    }\n");
                sb.append(pad).append("}\n");
            }
            case Expression.Optional opt -> {
                var optStart = "optStart" + id;
                var optElem = "optElem" + id;
                sb.append(pad).append("int ").append(optStart).append(" = pos;\n");
                sb.append(pad).append("int ").append(optStart).append("Line = line;\n");
                sb.append(pad).append("int ").append(optStart).append("Column = column;\n");
                generateExpressionCode(sb, opt.expression(), optElem, indent, counter);
                sb.append(pad).append("var ").append(resultVar).append(" = ").append(optElem).append(".isSuccess() ? ").append(optElem).append(" : ParseResult.success(null, pos, line, column);\n");
                sb.append(pad).append("if (").append(optElem).append(".isFailure()) {\n");
                sb.append(pad).append("    pos = ").append(optStart).append(";\n");
                sb.append(pad).append("    line = ").append(optStart).append("Line;\n");
                sb.append(pad).append("    column = ").append(optStart).append("Column;\n");
                sb.append(pad).append("}\n");
            }
            case Expression.Repetition rep -> {
                var repCount = "repCount" + id;
                var repStart = "repStart" + id;
                var repElem = "repElem" + id;
                var beforePos = "beforePos" + id;
                sb.append(pad).append("int ").append(repCount).append(" = 0;\n");
                sb.append(pad).append("int ").append(repStart).append(" = pos;\n");
                sb.append(pad).append("int ").append(repStart).append("Line = line;\n");
                sb.append(pad).append("int ").append(repStart).append("Column = column;\n");
                var maxStr = rep.max().isPresent() ? String.valueOf(rep.max().unwrap()) : "Integer.MAX_VALUE";
                sb.append(pad).append("while (").append(repCount).append(" < ").append(maxStr).append(") {\n");
                sb.append(pad).append("    int ").append(beforePos).append(" = pos;\n");
                sb.append(pad).append("    int ").append(beforePos).append("Line = line;\n");
                sb.append(pad).append("    int ").append(beforePos).append("Column = column;\n");
                sb.append(pad).append("    if (").append(repCount).append(" > 0) skipWhitespace();\n");
                generateExpressionCode(sb, rep.expression(), repElem, indent + 1, counter);
                sb.append(pad).append("    if (").append(repElem).append(".isFailure() || pos == ").append(beforePos).append(") {\n");
                sb.append(pad).append("        pos = ").append(beforePos).append(";\n");
                sb.append(pad).append("        line = ").append(beforePos).append("Line;\n");
                sb.append(pad).append("        column = ").append(beforePos).append("Column;\n");
                sb.append(pad).append("        break;\n");
                sb.append(pad).append("    }\n");
                sb.append(pad).append("    ").append(repCount).append("++;\n");
                sb.append(pad).append("}\n");
                sb.append(pad).append("var ").append(resultVar).append(" = ").append(repCount).append(" >= ").append(rep.min())
                    .append(" ? ParseResult.success(null, pos, line, column) : ParseResult.failure(\"at least ")
                    .append(rep.min()).append(" repetitions\");\n");
                sb.append(pad).append("if (").append(resultVar).append(".isFailure()) {\n");
                sb.append(pad).append("    pos = ").append(repStart).append(";\n");
                sb.append(pad).append("    line = ").append(repStart).append("Line;\n");
                sb.append(pad).append("    column = ").append(repStart).append("Column;\n");
                sb.append(pad).append("}\n");
            }
            case Expression.And and -> {
                var andStart = "andStart" + id;
                var andElem = "andElem" + id;
                sb.append(pad).append("int ").append(andStart).append(" = pos;\n");
                sb.append(pad).append("int ").append(andStart).append("Line = line;\n");
                sb.append(pad).append("int ").append(andStart).append("Column = column;\n");
                generateExpressionCode(sb, and.expression(), andElem, indent, counter);
                sb.append(pad).append("pos = ").append(andStart).append(";\n");
                sb.append(pad).append("line = ").append(andStart).append("Line;\n");
                sb.append(pad).append("column = ").append(andStart).append("Column;\n");
                sb.append(pad).append("var ").append(resultVar).append(" = ").append(andElem).append(".isSuccess() ? ParseResult.success(null, pos, line, column) : ").append(andElem).append(";\n");
            }
            case Expression.Not not -> {
                var notStart = "notStart" + id;
                var notElem = "notElem" + id;
                sb.append(pad).append("int ").append(notStart).append(" = pos;\n");
                sb.append(pad).append("int ").append(notStart).append("Line = line;\n");
                sb.append(pad).append("int ").append(notStart).append("Column = column;\n");
                generateExpressionCode(sb, not.expression(), notElem, indent, counter);
                sb.append(pad).append("pos = ").append(notStart).append(";\n");
                sb.append(pad).append("line = ").append(notStart).append("Line;\n");
                sb.append(pad).append("column = ").append(notStart).append("Column;\n");
                sb.append(pad).append("var ").append(resultVar).append(" = ").append(notElem).append(".isSuccess() ? ParseResult.failure(\"not match\") : ParseResult.success(null, pos, line, column);\n");
            }
            case Expression.TokenBoundary tb -> {
                var tbStart = "tbStart" + id;
                var tbElem = "tbElem" + id;
                sb.append(pad).append("int ").append(tbStart).append(" = pos;\n");
                generateExpressionCode(sb, tb.expression(), tbElem, indent, counter);
                sb.append(pad).append("var ").append(resultVar).append(" = ").append(tbElem).append(".isSuccess() ? ParseResult.success(substring(").append(tbStart).append(", pos), pos, line, column) : ").append(tbElem).append(";\n");
            }
            case Expression.Ignore ign -> {
                var ignElem = "ignElem" + id;
                generateExpressionCode(sb, ign.expression(), ignElem, indent, counter);
                sb.append(pad).append("var ").append(resultVar).append(" = ").append(ignElem).append(".isSuccess() ? ParseResult.success(null, pos, line, column) : ").append(ignElem).append(";\n");
            }
            case Expression.Capture cap -> {
                var capStart = "capStart" + id;
                var capElem = "capElem" + id;
                sb.append(pad).append("int ").append(capStart).append(" = pos;\n");
                generateExpressionCode(sb, cap.expression(), capElem, indent, counter);
                sb.append(pad).append("if (").append(capElem).append(".isSuccess()) {\n");
                sb.append(pad).append("    captures.put(\"").append(cap.name()).append("\", substring(").append(capStart).append(", pos));\n");
                sb.append(pad).append("}\n");
                sb.append(pad).append("var ").append(resultVar).append(" = ").append(capElem).append(";\n");
            }
            case Expression.CaptureScope cs -> {
                var savedCaptures = "savedCaptures" + id;
                var csElem = "csElem" + id;
                sb.append(pad).append("var ").append(savedCaptures).append(" = new HashMap<>(captures);\n");
                generateExpressionCode(sb, cs.expression(), csElem, indent, counter);
                sb.append(pad).append("captures.clear();\n");
                sb.append(pad).append("captures.putAll(").append(savedCaptures).append(");\n");
                sb.append(pad).append("var ").append(resultVar).append(" = ").append(csElem).append(";\n");
            }
            case Expression.BackReference br -> {
                var captured = "captured" + id;
                sb.append(pad).append("var ").append(captured).append(" = captures.get(\"").append(br.name()).append("\");\n");
                sb.append(pad).append("var ").append(resultVar).append(" = ").append(captured).append(" != null ? matchLiteral(").append(captured).append(", false) : ParseResult.failure(\"capture '\");\n");
            }
            case Expression.Cut cut -> {
                sb.append(pad).append("var ").append(resultVar).append(" = ParseResult.success(null, pos, line, column);\n");
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

        if (grammar.whitespace().isPresent()) {
            sb.append("        while (!isAtEnd()) {\n");
            sb.append("            int wsBeforePos = pos;\n");
            int[] wsCounter = {0};
            generateExpressionCode(sb, grammar.whitespace().unwrap(), "wsResult", 3, wsCounter);
            sb.append("            if (wsResult.isFailure() || pos == wsBeforePos) break;\n");
            sb.append("        }\n");
        }

        sb.append("""
                }

                private ParseResult matchLiteral(String text, boolean caseInsensitive) {
                    if (remaining() < text.length()) {
                        return ParseResult.failure("'" + text + "'");
                    }
                    for (int i = 0; i < text.length(); i++) {
                        char expected = text.charAt(i);
                        char actual = peek(i);
                        if (caseInsensitive) {
                            if (Character.toLowerCase(expected) != Character.toLowerCase(actual)) {
                                return ParseResult.failure("'" + text + "'");
                            }
                        } else {
                            if (expected != actual) {
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
                        return ParseResult.failure("character class");
                    }
                    char c = peek();
                    boolean matches = matchesPattern(c, pattern, caseInsensitive);
                    if (negated) matches = !matches;
                    if (!matches) {
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
                        return ParseResult.failure("any character");
                    }
                    char c = advance();
                    return ParseResult.success(String.valueOf(c), pos, line, column);
                }

                // === Parse Result ===

                private static final class ParseResult {
                    final boolean success;
                    final Object value;
                    final String expected;
                    final int endPos;
                    final int endLine;
                    final int endColumn;
                    final boolean cutFailed;

                    private ParseResult(boolean success, Object value, String expected, int endPos, int endLine, int endColumn, boolean cutFailed) {
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
                        return new ParseResult(true, value, null, endPos, endLine, endColumn, false);
                    }

                    static ParseResult failure(String expected) {
                        return new ParseResult(false, null, expected, 0, 0, 0, false);
                    }

                    static ParseResult cutFailure(String expected) {
                        return new ParseResult(false, null, expected, 0, 0, 0, true);
                    }

                    ParseResult asCutFailure() {
                        return cutFailed ? this : new ParseResult(false, null, expected, 0, 0, 0, true);
                    }

                    ParseResult asRegularFailure() {
                        return cutFailed ? new ParseResult(false, null, expected, 0, 0, 0, false) : this;
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

    private String transformActionCode(String code) {
        var result = code.replace("$0", "$0");
        for (int i = 1; i <= 20; i++) {
            result = result.replace("$" + i, "values.get(" + (i - 1) + ")");
        }
        return result;
    }

    private String wrapActionCode(String code) {
        var trimmed = code.trim();
        if (trimmed.startsWith("return ")) {
            return "value = " + trimmed.substring(7);
        }
        if (!trimmed.contains(";") || (trimmed.endsWith(";") && !trimmed.contains("\n"))) {
            var expr = trimmed.endsWith(";") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
            return "value = " + expr + ";";
        }
        return trimmed.replace("return ", "value = ");
    }

    // === CST Generation Methods ===

    private void generateCstImports(StringBuilder sb) {
        sb.append("""
            import org.pragmatica.lang.Cause;
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
        sb.append("public final class ").append(className).append(" {\n\n");
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
            sb.append("        record ").append(ruleClassName).append("() implements RuleId {\n");
            sb.append("            public int ordinal() { return ").append(ordinal++).append("; }\n");
            sb.append("            public String name() { return \"").append(rule.name()).append("\"; }\n");
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
            sb.append("    private static final RuleId.").append(ruleClassName)
              .append(" ").append(constName).append(" = new RuleId.").append(ruleClassName).append("();\n");
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
            ruleName = ruleName.substring(1); // Remove % prefix
        }
        // Handle special characters
        var sb = new StringBuilder();
        boolean capitalizeNext = true;
        for (char c : ruleName.toCharArray()) {
            if (Character.isLetterOrDigit(c)) {
                sb.append(capitalizeNext ? Character.toUpperCase(c) : c);
                capitalizeNext = false;
            } else {
                capitalizeNext = true;
            }
        }
        return sb.toString();
    }

    private String toConstantName(String ruleName) {
        // Convert rule name to constant name (UPPER_SNAKE_CASE)
        if (ruleName.startsWith("%")) {
            ruleName = ruleName.substring(1); // Remove % prefix
        }
        var sb = new StringBuilder("RULE_");
        for (char c : ruleName.toCharArray()) {
            if (Character.isUpperCase(c) && sb.length() > 5) {
                sb.append('_');
            }
            if (Character.isLetterOrDigit(c)) {
                sb.append(Character.toUpperCase(c));
            } else {
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
                    List<String> notes
                ) {
                    public static Diagnostic error(String message, SourceSpan span) {
                        return new Diagnostic(Severity.ERROR, null, message, span, List.of(), List.of());
                    }

                    public static Diagnostic error(String code, String message, SourceSpan span) {
                        return new Diagnostic(Severity.ERROR, code, message, span, List.of(), List.of());
                    }

                    public static Diagnostic warning(String message, SourceSpan span) {
                        return new Diagnostic(Severity.WARNING, null, message, span, List.of(), List.of());
                    }

                    public Diagnostic withLabel(String labelMessage) {
                        var newLabels = new ArrayList<>(labels);
                        newLabels.add(DiagnosticLabel.primary(span, labelMessage));
                        return new Diagnostic(severity, code, message, span, List.copyOf(newLabels), notes);
                    }

                    public Diagnostic withSecondaryLabel(SourceSpan labelSpan, String labelMessage) {
                        var newLabels = new ArrayList<>(labels);
                        newLabels.add(DiagnosticLabel.secondary(labelSpan, labelMessage));
                        return new Diagnostic(severity, code, message, span, List.copyOf(newLabels), notes);
                    }

                    public Diagnostic withNote(String note) {
                        var newNotes = new ArrayList<>(notes);
                        newNotes.add(note);
                        return new Diagnostic(severity, code, message, span, labels, List.copyOf(newNotes));
                    }

                    public Diagnostic withHelp(String help) {
                        return withNote("help: " + help);
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
                    CstNode node,
                    List<Diagnostic> diagnostics,
                    String source
                ) {
                    public static ParseResultWithDiagnostics success(CstNode node, String source) {
                        return new ParseResultWithDiagnostics(node, List.of(), source);
                    }

                    public static ParseResultWithDiagnostics withErrors(CstNode node, List<Diagnostic> diagnostics, String source) {
                        return new ParseResultWithDiagnostics(node, List.copyOf(diagnostics), source);
                    }

                    public boolean isSuccess() {
                        return node != null && diagnostics.isEmpty();
                    }

                    public boolean hasErrors() {
                        return !diagnostics.isEmpty();
                    }

                    public boolean hasNode() {
                        return node != null;
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
                private boolean inTokenBoundary;
            """);

        if (errorReporting == ErrorReporting.ADVANCED) {
            sb.append("""
                    private List<Diagnostic> diagnostics;
                    private SourceLocation furthestFailure;
                    private String furthestExpected;
                """);
        }

        sb.append("""

                private void init(String input) {
                    this.input = input;
                    this.pos = 0;
                    this.line = 1;
                    this.column = 1;
                    this.cache = new HashMap<>();
                    this.captures = new HashMap<>();
                    this.inTokenBoundary = false;
            """);

        if (errorReporting == ErrorReporting.ADVANCED) {
            sb.append("""
                        this.diagnostics = new ArrayList<>();
                        this.furthestFailure = null;
                        this.furthestExpected = null;
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

        if (errorReporting == ErrorReporting.ADVANCED) {
            sb.append("""
                    private void trackFailure(String expected) {
                        var loc = location();
                        if (furthestFailure == null || loc.offset() > furthestFailure.offset()) {
                            furthestFailure = loc;
                            furthestExpected = expected;
                        }
                    }

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

                    private void addDiagnostic(String message, SourceSpan span) {
                        diagnostics.add(Diagnostic.error(message, span));
                    }

                    private void addDiagnostic(String message, SourceSpan span, String label) {
                        diagnostics.add(Diagnostic.error(message, span).withLabel(label));
                    }

                """);
        }
    }

    private void generateCstParseMethods(StringBuilder sb) {
        var startRule = grammar.effectiveStartRule();
        var startRuleName = startRule.isPresent() ? startRule.unwrap().name() : grammar.rules().getFirst().name();
        var sanitizedName = sanitize(startRuleName);

        sb.append("""
                // === Public Parse Methods ===

                public Result<CstNode> parse(String input) {
                    init(input);
                    var leadingTrivia = skipWhitespace();
                    var result = parse_%s(leadingTrivia);
                    if (result.isFailure()) {
                        return Result.failure(new ParseError(location(), "expected " + result.expected));
                    }
                    var trailingTrivia = skipWhitespace(); // Capture trailing trivia
                    if (!isAtEnd()) {
                        return Result.failure(new ParseError(location(), "unexpected input"));
                    }
                    // Attach trailing trivia to root node
                    var rootNode = attachTrailingTrivia(result.node, trailingTrivia);
                    return Result.success(rootNode);
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
                        var leadingTrivia = skipWhitespace();
                        var result = parse_%s(leadingTrivia);

                        if (result.isFailure()) {
                            // Record the failure and attempt recovery
                            var errorLoc = furthestFailure != null ? furthestFailure : location();
                            var errorSpan = SourceSpan.of(errorLoc, errorLoc);
                            addDiagnostic("expected " + (furthestExpected != null ? furthestExpected : result.expected), errorSpan);

                            // Skip to recovery point and try to continue
                            var skippedSpan = skipToRecoveryPoint();
                            if (skippedSpan.length() > 0) {
                                var skippedText = skippedSpan.extract(input);
                                var expected = furthestExpected != null ? furthestExpected : result.expected;
                                var errorNode = new CstNode.Error(skippedSpan, skippedText, expected, leadingTrivia, List.of());
                                return ParseResultWithDiagnostics.withErrors(errorNode, diagnostics, input);
                            }
                            return ParseResultWithDiagnostics.withErrors(null, diagnostics, input);
                        }

                        var trailingTrivia = skipWhitespace();
                        if (!isAtEnd()) {
                            // Unexpected trailing input
                            var errorStart = location();
                            var skippedSpan = skipToRecoveryPoint();
                            var skippedText = skippedSpan.extract(input);
                            addDiagnostic("unexpected input", skippedSpan, "expected end of input");

                            // Attach error node to result
                            var rootNode = attachTrailingTrivia(result.node, trailingTrivia);
                            return ParseResultWithDiagnostics.withErrors(rootNode, diagnostics, input);
                        }

                        var rootNode = attachTrailingTrivia(result.node, trailingTrivia);
                        if (diagnostics.isEmpty()) {
                            return ParseResultWithDiagnostics.success(rootNode, input);
                        }
                        return ParseResultWithDiagnostics.withErrors(rootNode, diagnostics, input);
                    }

                """.formatted(sanitizedName));
        }
    }

    private void generateCstRuleMethods(StringBuilder sb) {
        sb.append("    // === Rule Parsing Methods ===\n\n");

        int ruleId = 0;
        for (var rule : grammar.rules()) {
            generateCstRuleMethod(sb, rule, ruleId++);
        }
    }

    private void generateCstRuleMethod(StringBuilder sb, Rule rule, int ruleId) {
        var methodName = "parse_" + sanitize(rule.name());
        var ruleName = rule.name();

        sb.append("    private CstParseResult ").append(methodName).append("(List<Trivia> leadingTrivia) {\n");
        sb.append("        var startLoc = location();\n");
        sb.append("        \n");
        sb.append("        // Check cache\n");
        sb.append("        long key = cacheKey(").append(ruleId).append(", startLoc.offset());\n");
        sb.append("        var cached = cache.get(key);\n");
        sb.append("        if (cached != null) {\n");
        sb.append("            if (cached.isSuccess()) restoreLocation(cached.endLocation);\n");
        sb.append("            return cached;\n");
        sb.append("        }\n");
        sb.append("        \n");
        sb.append("        var children = new ArrayList<CstNode>();\n");
        sb.append("        \n");

        var counter = new int[]{0}; // Mutable counter for unique variable names
        generateCstExpressionCode(sb, rule.expression(), "result", 2, true, counter, false);

        sb.append("        \n");
        sb.append("        CstParseResult finalResult;\n");
        sb.append("        if (result.isSuccess()) {\n");
        sb.append("            var endLoc = location();\n");
        sb.append("            var span = SourceSpan.of(startLoc, endLoc);\n");

        // Check if this rule contains only a token boundary or simple terminals
        var ruleIdConst = toConstantName(ruleName);
        if (isTokenRule(rule.expression())) {
            sb.append("            var node = new CstNode.Token(span, ").append(ruleIdConst)
              .append(", result.text, leadingTrivia, List.of());\n");
        } else {
            sb.append("            var node = new CstNode.NonTerminal(span, ").append(ruleIdConst)
              .append(", children, leadingTrivia, List.of());\n");
        }
        sb.append("            finalResult = CstParseResult.success(node, result.text, endLoc);\n");
        sb.append("        } else {\n");
        sb.append("            restoreLocation(startLoc);\n");
        // Use custom error message if available
        if (rule.hasErrorMessage()) {
            sb.append("            finalResult = CstParseResult.failure(\"").append(escape(rule.errorMessage().unwrap())).append("\");\n");
        } else {
            sb.append("            finalResult = result;\n");
        }
        sb.append("        }\n");
        sb.append("        \n");
        sb.append("        cache.put(key, finalResult);\n");
        sb.append("        return finalResult;\n");
        sb.append("    }\n\n");
    }

    private boolean isTokenRule(Expression expr) {
        return switch (expr) {
            case Expression.TokenBoundary tb -> true;
            case Expression.Literal lit -> true;
            case Expression.CharClass cc -> true;
            case Expression.Sequence seq -> seq.elements().stream().allMatch(this::isTokenRule);
            case Expression.Group grp -> isTokenRule(grp.expression());
            default -> false;
        };
    }

    private boolean isReference(Expression expr) {
        return switch (expr) {
            case Expression.Reference ref -> true;
            case Expression.Group grp -> isReference(grp.expression());
            case Expression.Optional opt -> isReference(opt.expression());
            case Expression.ZeroOrMore zom -> isReference(zom.expression());
            case Expression.OneOrMore oom -> isReference(oom.expression());
            default -> false;
        };
    }

    private boolean isOptionalLike(Expression expr) {
        return switch (expr) {
            case Expression.Optional o -> true;
            case Expression.ZeroOrMore z -> true;
            case Expression.Choice c -> true;  // Choice saves position and may restore on failure
            case Expression.Group g -> isOptionalLike(g.expression());
            default -> false;
        };
    }

    /**
     * Extract inner expression from ZeroOrMore/OneOrMore for trivia matching.
     * The whitespace rule is typically `(spaces / comments)*` - we want to match
     * one element at a time for proper trivia classification.
     */
    private Expression extractInnerExpression(Expression expr) {
        return switch (expr) {
            case Expression.ZeroOrMore zom -> zom.expression();
            case Expression.OneOrMore oom -> oom.expression();
            case Expression.Group grp -> extractInnerExpression(grp.expression());
            default -> expr;  // If not wrapped in repetition, use as-is
        };
    }

    private void generateCstExpressionCode(StringBuilder sb, Expression expr, String resultVar, int indent, boolean addToChildren, int[] counter, boolean inWhitespaceRule) {
        var pad = "    ".repeat(indent);
        var id = counter[0]++;  // Get unique ID for this expression

        switch (expr) {
            case Expression.Literal lit -> {
                sb.append(pad).append("var ").append(resultVar).append(" = matchLiteralCst(\"")
                    .append(escape(lit.text())).append("\", ").append(lit.caseInsensitive()).append(");\n");
                if (addToChildren) {
                    sb.append(pad).append("if (").append(resultVar).append(".isSuccess() && ").append(resultVar).append(".node != null) {\n");
                    sb.append(pad).append("    children.add(").append(resultVar).append(".node);\n");
                    sb.append(pad).append("}\n");
                }
            }
            case Expression.CharClass cc -> {
                sb.append(pad).append("var ").append(resultVar).append(" = matchCharClassCst(\"")
                    .append(escape(cc.pattern())).append("\", ")
                    .append(cc.negated()).append(", ")
                    .append(cc.caseInsensitive()).append(");\n");
                if (addToChildren) {
                    sb.append(pad).append("if (").append(resultVar).append(".isSuccess() && ").append(resultVar).append(".node != null) {\n");
                    sb.append(pad).append("    children.add(").append(resultVar).append(".node);\n");
                    sb.append(pad).append("}\n");
                }
            }
            case Expression.Dictionary dict -> {
                sb.append(pad).append("var ").append(resultVar).append(" = matchDictionaryCst(List.of(");
                var words = dict.words();
                for (int i = 0; i < words.size(); i++) {
                    if (i > 0) sb.append(", ");
                    sb.append("\"").append(escape(words.get(i))).append("\"");
                }
                sb.append("), ").append(dict.caseInsensitive()).append(");\n");
                if (addToChildren) {
                    sb.append(pad).append("if (").append(resultVar).append(".isSuccess() && ").append(resultVar).append(".node != null) {\n");
                    sb.append(pad).append("    children.add(").append(resultVar).append(".node);\n");
                    sb.append(pad).append("}\n");
                }
            }
            case Expression.Any any -> {
                sb.append(pad).append("var ").append(resultVar).append(" = matchAnyCst();\n");
                if (addToChildren) {
                    sb.append(pad).append("if (").append(resultVar).append(".isSuccess() && ").append(resultVar).append(".node != null) {\n");
                    sb.append(pad).append("    children.add(").append(resultVar).append(".node);\n");
                    sb.append(pad).append("}\n");
                }
            }
            case Expression.Reference ref -> {
                var triviaVar = "trivia" + id;
                if (inWhitespaceRule) {
                    sb.append(pad).append("var ").append(triviaVar).append(" = List.<Trivia>of();\n");
                } else {
                    sb.append(pad).append("var ").append(triviaVar).append(" = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();\n");
                }
                sb.append(pad).append("var ").append(resultVar).append(" = parse_")
                    .append(sanitize(ref.ruleName())).append("(").append(triviaVar).append(");\n");
                if (addToChildren) {
                    sb.append(pad).append("if (").append(resultVar).append(".isSuccess() && ").append(resultVar).append(".node != null) {\n");
                    sb.append(pad).append("    children.add(").append(resultVar).append(".node);\n");
                    sb.append(pad).append("}\n");
                }
            }
            case Expression.Sequence seq -> {
                var seqStart = "seqStart" + id;
                var cutVar = "cut" + id;
                sb.append(pad).append("CstParseResult ").append(resultVar).append(" = CstParseResult.success(null, \"\", location());\n");
                sb.append(pad).append("var ").append(seqStart).append(" = location();\n");
                sb.append(pad).append("boolean ").append(cutVar).append(" = false;\n");
                int i = 0;
                for (var elem : seq.elements()) {
                    sb.append(pad).append("if (").append(resultVar).append(".isSuccess()) {\n");
                    // Skip whitespace before non-Reference elements (References capture trivia themselves)
                    // Also don't skip for Optional/ZeroOrMore/Choice - they handle trivia internally (isOptionalLike)
                    if (i > 0 && !inWhitespaceRule && !isReference(elem) && !isOptionalLike(elem)) {
                        sb.append(pad).append("    if (!inTokenBoundary) skipWhitespace();\n");
                    }
                    var elemVar = "elem" + id + "_" + i;
                    generateCstExpressionCode(sb, elem, elemVar, indent + 1, addToChildren, counter, inWhitespaceRule);
                    // Check for cut failure propagation first
                    sb.append(pad).append("    if (").append(elemVar).append(".isCutFailure()) {\n");
                    sb.append(pad).append("        restoreLocation(").append(seqStart).append(");\n");
                    sb.append(pad).append("        ").append(resultVar).append(" = ").append(elemVar).append(";\n");
                    sb.append(pad).append("    } else if (").append(elemVar).append(".isFailure()) {\n");
                    sb.append(pad).append("        restoreLocation(").append(seqStart).append(");\n");
                    sb.append(pad).append("        ").append(resultVar).append(" = ").append(cutVar).append(" ? ").append(elemVar).append(".asCutFailure() : ").append(elemVar).append(";\n");
                    sb.append(pad).append("    }\n");
                    sb.append(pad).append("}\n");
                    // Track if this element was a cut
                    if (elem instanceof Expression.Cut) {
                        sb.append(pad).append(cutVar).append(" = true;\n");
                    }
                    i++;
                }
                sb.append(pad).append("if (").append(resultVar).append(".isSuccess()) {\n");
                sb.append(pad).append("    ").append(resultVar).append(" = CstParseResult.success(null, substring(").append(seqStart).append(".offset(), pos), location());\n");
                sb.append(pad).append("}\n");
            }
            case Expression.Choice choice -> {
                var choiceStart = "choiceStart" + id;
                var savedChildren = "savedChildren" + id;
                sb.append(pad).append("CstParseResult ").append(resultVar).append(" = null;\n");
                sb.append(pad).append("var ").append(choiceStart).append(" = location();\n");
                // Don't skip whitespace here - let alternatives capture trivia themselves
                if (addToChildren) {
                    sb.append(pad).append("var ").append(savedChildren).append(" = new ArrayList<>(children);\n");
                }
                int i = 0;
                for (var alt : choice.alternatives()) {
                    if (addToChildren) {
                        sb.append(pad).append("children.clear();\n");
                        sb.append(pad).append("children.addAll(").append(savedChildren).append(");\n");
                    }
                    var altVar = "alt" + id + "_" + i;
                    generateCstExpressionCode(sb, alt, altVar, indent, addToChildren, counter, inWhitespaceRule);
                    sb.append(pad).append("if (").append(altVar).append(".isSuccess()) {\n");
                    sb.append(pad).append("    ").append(resultVar).append(" = ").append(altVar).append(";\n");
                    sb.append(pad).append("} else if (").append(altVar).append(".isCutFailure()) {\n");
                    // Convert CutFailure to regular failure for parent choices to allow backtracking at higher levels
                    sb.append(pad).append("    ").append(resultVar).append(" = ").append(altVar).append(".asRegularFailure();\n");
                    sb.append(pad).append("} else {\n");
                    sb.append(pad).append("    restoreLocation(").append(choiceStart).append(");\n");
                    i++;
                }
                for (int j = 0; j < choice.alternatives().size(); j++) {
                    sb.append(pad).append("}\n");
                }
                sb.append(pad).append("if (").append(resultVar).append(" == null) {\n");
                if (addToChildren) {
                    sb.append(pad).append("    children.clear();\n");
                    sb.append(pad).append("    children.addAll(").append(savedChildren).append(");\n");
                }
                sb.append(pad).append("    ").append(resultVar).append(" = CstParseResult.failure(\"one of alternatives\");\n");
                sb.append(pad).append("}\n");
            }
            case Expression.ZeroOrMore zom -> {
                var zomStart = "zomStart" + id;
                var beforeLoc = "beforeLoc" + id;
                var zomElem = "zomElem" + id;
                sb.append(pad).append("CstParseResult ").append(resultVar).append(" = CstParseResult.success(null, \"\", location());\n");
                sb.append(pad).append("var ").append(zomStart).append(" = location();\n");
                sb.append(pad).append("while (true) {\n");
                sb.append(pad).append("    var ").append(beforeLoc).append(" = location();\n");
                // Skip whitespace before non-Reference elements (References capture trivia themselves)
                if (!inWhitespaceRule && !isReference(zom.expression())) {
                    sb.append(pad).append("    if (!inTokenBoundary) skipWhitespace();\n");
                }
                generateCstExpressionCode(sb, zom.expression(), zomElem, indent + 1, addToChildren, counter, inWhitespaceRule);
                sb.append(pad).append("    if (").append(zomElem).append(".isFailure() || location().offset() == ").append(beforeLoc).append(".offset()) {\n");
                sb.append(pad).append("        restoreLocation(").append(beforeLoc).append(");\n");
                sb.append(pad).append("        break;\n");
                sb.append(pad).append("    }\n");
                sb.append(pad).append("}\n");
                sb.append(pad).append(resultVar).append(" = CstParseResult.success(null, substring(").append(zomStart).append(".offset(), pos), location());\n");
            }
            case Expression.OneOrMore oom -> {
                var oomFirst = "oomFirst" + id;
                var oomStart = "oomStart" + id;
                var beforeLoc = "beforeLoc" + id;
                var oomElem = "oomElem" + id;
                generateCstExpressionCode(sb, oom.expression(), oomFirst, indent, addToChildren, counter, inWhitespaceRule);
                sb.append(pad).append("var ").append(resultVar).append(" = ").append(oomFirst).append(";\n");
                sb.append(pad).append("if (").append(oomFirst).append(".isSuccess()) {\n");
                sb.append(pad).append("    var ").append(oomStart).append(" = location();\n");
                sb.append(pad).append("    while (true) {\n");
                sb.append(pad).append("        var ").append(beforeLoc).append(" = location();\n");
                // Skip whitespace before non-Reference elements (References capture trivia themselves)
                if (!inWhitespaceRule && !isReference(oom.expression())) {
                    sb.append(pad).append("        if (!inTokenBoundary) skipWhitespace();\n");
                }
                generateCstExpressionCode(sb, oom.expression(), oomElem, indent + 2, addToChildren, counter, inWhitespaceRule);
                sb.append(pad).append("        if (").append(oomElem).append(".isFailure() || location().offset() == ").append(beforeLoc).append(".offset()) {\n");
                sb.append(pad).append("            restoreLocation(").append(beforeLoc).append(");\n");
                sb.append(pad).append("            break;\n");
                sb.append(pad).append("        }\n");
                sb.append(pad).append("    }\n");
                sb.append(pad).append("}\n");
            }
            case Expression.Optional opt -> {
                var optStart = "optStart" + id;
                var optElem = "optElem" + id;
                sb.append(pad).append("var ").append(optStart).append(" = location();\n");
                // Skip whitespace before non-Reference elements (References capture trivia themselves)
                if (!inWhitespaceRule && !isReference(opt.expression())) {
                    sb.append(pad).append("if (!inTokenBoundary) skipWhitespace();\n");
                }
                generateCstExpressionCode(sb, opt.expression(), optElem, indent, addToChildren, counter, inWhitespaceRule);
                sb.append(pad).append("var ").append(resultVar).append(" = ").append(optElem).append(".isSuccess() ? ").append(optElem).append(" : CstParseResult.success(null, \"\", location());\n");
                sb.append(pad).append("if (").append(optElem).append(".isFailure()) {\n");
                sb.append(pad).append("    restoreLocation(").append(optStart).append(");\n");
                sb.append(pad).append("}\n");
            }
            case Expression.Repetition rep -> {
                var repCount = "repCount" + id;
                var repStart = "repStart" + id;
                var beforeLoc = "beforeLoc" + id;
                var repElem = "repElem" + id;
                sb.append(pad).append("int ").append(repCount).append(" = 0;\n");
                sb.append(pad).append("var ").append(repStart).append(" = location();\n");
                var maxStr = rep.max().isPresent() ? String.valueOf(rep.max().unwrap()) : "Integer.MAX_VALUE";
                sb.append(pad).append("while (").append(repCount).append(" < ").append(maxStr).append(") {\n");
                sb.append(pad).append("    var ").append(beforeLoc).append(" = location();\n");
                // Skip whitespace before non-Reference elements (References capture trivia themselves)
                if (!inWhitespaceRule && !isReference(rep.expression())) {
                    sb.append(pad).append("    if (").append(repCount).append(" > 0 && !inTokenBoundary) skipWhitespace();\n");
                }
                generateCstExpressionCode(sb, rep.expression(), repElem, indent + 1, addToChildren, counter, inWhitespaceRule);
                sb.append(pad).append("    if (").append(repElem).append(".isFailure() || location().offset() == ").append(beforeLoc).append(".offset()) {\n");
                sb.append(pad).append("        restoreLocation(").append(beforeLoc).append(");\n");
                sb.append(pad).append("        break;\n");
                sb.append(pad).append("    }\n");
                sb.append(pad).append("    ").append(repCount).append("++;\n");
                sb.append(pad).append("}\n");
                sb.append(pad).append("var ").append(resultVar).append(" = ").append(repCount).append(" >= ").append(rep.min())
                    .append(" ? CstParseResult.success(null, substring(").append(repStart).append(".offset(), pos), location()) : CstParseResult.failure(\"at least ")
                    .append(rep.min()).append(" repetitions\");\n");
                sb.append(pad).append("if (").append(resultVar).append(".isFailure()) {\n");
                sb.append(pad).append("    restoreLocation(").append(repStart).append(");\n");
                sb.append(pad).append("}\n");
            }
            case Expression.And and -> {
                var andStart = "andStart" + id;
                var savedChildren = "savedChildrenAnd" + id;
                var andElem = "andElem" + id;
                sb.append(pad).append("var ").append(andStart).append(" = location();\n");
                if (addToChildren) {
                    sb.append(pad).append("var ").append(savedChildren).append(" = new ArrayList<>(children);\n");
                }
                generateCstExpressionCode(sb, and.expression(), andElem, indent, false, counter, inWhitespaceRule);
                sb.append(pad).append("restoreLocation(").append(andStart).append(");\n");
                if (addToChildren) {
                    sb.append(pad).append("children.clear();\n");
                    sb.append(pad).append("children.addAll(").append(savedChildren).append(");\n");
                }
                sb.append(pad).append("var ").append(resultVar).append(" = ").append(andElem).append(".isSuccess() ? CstParseResult.success(null, \"\", location()) : ").append(andElem).append(";\n");
            }
            case Expression.Not not -> {
                var notStart = "notStart" + id;
                var savedChildren = "savedChildrenNot" + id;
                var notElem = "notElem" + id;
                sb.append(pad).append("var ").append(notStart).append(" = location();\n");
                if (addToChildren) {
                    sb.append(pad).append("var ").append(savedChildren).append(" = new ArrayList<>(children);\n");
                }
                generateCstExpressionCode(sb, not.expression(), notElem, indent, false, counter, inWhitespaceRule);
                sb.append(pad).append("restoreLocation(").append(notStart).append(");\n");
                if (addToChildren) {
                    sb.append(pad).append("children.clear();\n");
                    sb.append(pad).append("children.addAll(").append(savedChildren).append(");\n");
                }
                sb.append(pad).append("var ").append(resultVar).append(" = ").append(notElem).append(".isSuccess() ? CstParseResult.failure(\"not match\") : CstParseResult.success(null, \"\", location());\n");
            }
            case Expression.TokenBoundary tb -> {
                var tbStart = "tbStart" + id;
                var savedChildren = "savedChildrenTb" + id;
                var tbElem = "tbElem" + id;
                sb.append(pad).append("var ").append(tbStart).append(" = location();\n");
                sb.append(pad).append("inTokenBoundary = true;\n");
                if (addToChildren) {
                    sb.append(pad).append("var ").append(savedChildren).append(" = new ArrayList<>(children);\n");
                }
                generateCstExpressionCode(sb, tb.expression(), tbElem, indent, false, counter, inWhitespaceRule);
                sb.append(pad).append("inTokenBoundary = false;\n");
                if (addToChildren) {
                    sb.append(pad).append("children.clear();\n");
                    sb.append(pad).append("children.addAll(").append(savedChildren).append(");\n");
                }
                sb.append(pad).append("CstParseResult ").append(resultVar).append(";\n");
                sb.append(pad).append("if (").append(tbElem).append(".isSuccess()) {\n");
                sb.append(pad).append("    var tbText").append(id).append(" = substring(").append(tbStart).append(".offset(), pos);\n");
                sb.append(pad).append("    var tbSpan").append(id).append(" = SourceSpan.of(").append(tbStart).append(", location());\n");
                sb.append(pad).append("    var tbNode").append(id).append(" = new CstNode.Token(tbSpan").append(id).append(", RULE_PEG_TOKEN, tbText").append(id).append(", List.of(), List.of());\n");
                if (addToChildren) {
                    sb.append(pad).append("    children.add(tbNode").append(id).append(");\n");
                }
                sb.append(pad).append("    ").append(resultVar).append(" = CstParseResult.success(tbNode").append(id).append(", tbText").append(id).append(", location());\n");
                sb.append(pad).append("} else {\n");
                sb.append(pad).append("    ").append(resultVar).append(" = ").append(tbElem).append(";\n");
                sb.append(pad).append("}\n");
            }
            case Expression.Ignore ign -> {
                var savedChildren = "savedChildrenIgn" + id;
                var ignElem = "ignElem" + id;
                if (addToChildren) {
                    sb.append(pad).append("var ").append(savedChildren).append(" = new ArrayList<>(children);\n");
                }
                generateCstExpressionCode(sb, ign.expression(), ignElem, indent, false, counter, inWhitespaceRule);
                if (addToChildren) {
                    sb.append(pad).append("children.clear();\n");
                    sb.append(pad).append("children.addAll(").append(savedChildren).append(");\n");
                }
                sb.append(pad).append("var ").append(resultVar).append(" = ").append(ignElem).append(".isSuccess() ? CstParseResult.success(null, \"\", location()) : ").append(ignElem).append(";\n");
            }
            case Expression.Capture cap -> {
                var capStart = "capStart" + id;
                var capElem = "capElem" + id;
                sb.append(pad).append("var ").append(capStart).append(" = location();\n");
                generateCstExpressionCode(sb, cap.expression(), capElem, indent, addToChildren, counter, inWhitespaceRule);
                sb.append(pad).append("if (").append(capElem).append(".isSuccess()) {\n");
                sb.append(pad).append("    captures.put(\"").append(cap.name()).append("\", substring(").append(capStart).append(".offset(), pos));\n");
                sb.append(pad).append("}\n");
                sb.append(pad).append("var ").append(resultVar).append(" = ").append(capElem).append(";\n");
            }
            case Expression.CaptureScope cs -> {
                var savedCapturesVar = "savedCaptures" + id;
                var csElem = "csElem" + id;
                sb.append(pad).append("var ").append(savedCapturesVar).append(" = new HashMap<>(captures);\n");
                generateCstExpressionCode(sb, cs.expression(), csElem, indent, addToChildren, counter, inWhitespaceRule);
                sb.append(pad).append("captures.clear();\n");
                sb.append(pad).append("captures.putAll(").append(savedCapturesVar).append(");\n");
                sb.append(pad).append("var ").append(resultVar).append(" = ").append(csElem).append(";\n");
            }
            case Expression.BackReference br -> {
                var captured = "captured" + id;
                sb.append(pad).append("var ").append(captured).append(" = captures.get(\"").append(br.name()).append("\");\n");
                sb.append(pad).append("var ").append(resultVar).append(" = ").append(captured).append(" != null ? matchLiteralCst(").append(captured).append(", false) : CstParseResult.failure(\"capture '\");\n");
                if (addToChildren) {
                    sb.append(pad).append("if (").append(resultVar).append(".isSuccess() && ").append(resultVar).append(".node != null) {\n");
                    sb.append(pad).append("    children.add(").append(resultVar).append(".node);\n");
                    sb.append(pad).append("}\n");
                }
            }
            case Expression.Cut cut -> {
                sb.append(pad).append("var ").append(resultVar).append(" = CstParseResult.success(null, \"\", location());\n");
            }
            case Expression.Group grp -> {
                generateCstExpressionCode(sb, grp.expression(), resultVar, indent, addToChildren, counter, inWhitespaceRule);
            }
        }
    }

    private void generateCstHelperMethods(StringBuilder sb) {
        sb.append("""
                // === Helper Methods ===

                private List<Trivia> skipWhitespace() {
                    var trivia = new ArrayList<Trivia>();
                    if (inTokenBoundary) return trivia;
            """);

        if (grammar.whitespace().isPresent()) {
            // Extract inner expression from ZeroOrMore/OneOrMore to match one element at a time
            var wsExpr = grammar.whitespace().unwrap();
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
                    return trivia;
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

                private CstParseResult matchLiteralCst(String text, boolean caseInsensitive) {
                    if (remaining() < text.length()) {
                        return CstParseResult.failure("'" + text + "'");
                    }
                    var startLoc = location();
                    for (int i = 0; i < text.length(); i++) {
                        char expected = text.charAt(i);
                        char actual = peek(i);
                        if (caseInsensitive) {
                            if (Character.toLowerCase(expected) != Character.toLowerCase(actual)) {
                                return CstParseResult.failure("'" + text + "'");
                            }
                        } else {
                            if (expected != actual) {
                                return CstParseResult.failure("'" + text + "'");
                            }
                        }
                    }
                    for (int i = 0; i < text.length(); i++) {
                        advance();
                    }
                    var span = SourceSpan.of(startLoc, location());
                    var node = new CstNode.Terminal(span, RULE_PEG_LITERAL, text, List.of(), List.of());
                    return CstParseResult.success(node, text, location());
                }

                private CstParseResult matchDictionaryCst(List<String> words, boolean caseInsensitive) {
                    String longestMatch = null;
                    int longestLen = 0;
                    for (var word : words) {
                        if (matchesWord(word, caseInsensitive) && word.length() > longestLen) {
                            longestMatch = word;
                            longestLen = word.length();
                        }
                    }
                    if (longestMatch == null) {
                        return CstParseResult.failure("dictionary word");
                    }
                    var startLoc = location();
                    for (int i = 0; i < longestLen; i++) {
                        advance();
                    }
                    var span = SourceSpan.of(startLoc, location());
                    var node = new CstNode.Terminal(span, RULE_PEG_LITERAL, longestMatch, List.of(), List.of());
                    return CstParseResult.success(node, longestMatch, location());
                }

            """);
        sb.append(MATCHES_WORD_METHOD);
        sb.append("""

                private CstParseResult matchCharClassCst(String pattern, boolean negated, boolean caseInsensitive) {
                    if (isAtEnd()) {
                        return CstParseResult.failure("character class");
                    }
                    var startLoc = location();
                    char c = peek();
                    boolean matches = matchesPattern(c, pattern, caseInsensitive);
                    if (negated) matches = !matches;
                    if (!matches) {
                        return CstParseResult.failure("character class");
                    }
                    advance();
                    var text = String.valueOf(c);
                    var span = SourceSpan.of(startLoc, location());
                    var node = new CstNode.Terminal(span, RULE_PEG_CHAR_CLASS, text, List.of(), List.of());
                    return CstParseResult.success(node, text, location());
                }

            """);
        sb.append(MATCHES_PATTERN_METHOD);
        sb.append("""

                private CstParseResult matchAnyCst() {
                    if (isAtEnd()) {
                        return CstParseResult.failure("any character");
                    }
                    var startLoc = location();
                    char c = advance();
                    var text = String.valueOf(c);
                    var span = SourceSpan.of(startLoc, location());
                    var node = new CstNode.Terminal(span, RULE_PEG_ANY, text, List.of(), List.of());
                    return CstParseResult.success(node, text, location());
                }

                // === CST Parse Result ===

                private static final class CstParseResult {
                    final boolean success;
                    final CstNode node;
                    final String text;
                    final String expected;
                    final SourceLocation endLocation;
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
    }
}
