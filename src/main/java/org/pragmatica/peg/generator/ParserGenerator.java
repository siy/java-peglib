package org.pragmatica.peg.generator;

import org.pragmatica.lang.Option;
import org.pragmatica.peg.grammar.Expression;
import org.pragmatica.peg.grammar.Grammar;
import org.pragmatica.peg.grammar.Rule;

import java.util.stream.Collectors;

/**
 * Generates standalone parser source code from a Grammar.
 * The generated parser depends only on pragmatica-lite:core.
 */
public final class ParserGenerator {

    private final Grammar grammar;
    private final String packageName;
    private final String className;

    private ParserGenerator(Grammar grammar, String packageName, String className) {
        this.grammar = grammar;
        this.packageName = packageName;
        this.className = className;
    }

    public static ParserGenerator create(Grammar grammar, String packageName, String className) {
        return new ParserGenerator(grammar, packageName, className);
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

    private void generatePackage(StringBuilder sb) {
        sb.append("package ").append(packageName).append(";\n\n");
    }

    private void generateImports(StringBuilder sb) {
        sb.append("""
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
                        return Result.failure(new RuntimeException(
                            "Parse error at " + line + ":" + column + ": " + result.expected));
                    }
                    if (!isAtEnd()) {
                        return Result.failure(new RuntimeException(
                            "Unexpected input at " + line + ":" + column));
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

        generateExpressionCode(sb, rule.expression(), "result", 2);

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
        sb.append("        }\n");
        sb.append("        \n");
        sb.append("        cache.put(key, result);\n");
        sb.append("        return result;\n");
        sb.append("    }\n\n");
    }

    private void generateExpressionCode(StringBuilder sb, Expression expr, String resultVar, int indent) {
        var pad = "    ".repeat(indent);

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
                sb.append(pad).append("ParseResult ").append(resultVar).append(" = ParseResult.success(null, pos, line, column);\n");
                sb.append(pad).append("int seqStart = pos;\n");
                sb.append(pad).append("int seqStartLine = line;\n");
                sb.append(pad).append("int seqStartColumn = column;\n");
                int i = 0;
                for (var elem : seq.elements()) {
                    sb.append(pad).append("skipWhitespace();\n");
                    generateExpressionCode(sb, elem, "elem" + i, indent);
                    sb.append(pad).append("if (elem").append(i).append(".isFailure()) {\n");
                    sb.append(pad).append("    pos = seqStart;\n");
                    sb.append(pad).append("    line = seqStartLine;\n");
                    sb.append(pad).append("    column = seqStartColumn;\n");
                    sb.append(pad).append("    ").append(resultVar).append(" = elem").append(i).append(";\n");
                    sb.append(pad).append("}\n");
                    i++;
                }
            }
            case Expression.Choice choice -> {
                sb.append(pad).append("ParseResult ").append(resultVar).append(" = null;\n");
                sb.append(pad).append("int choiceStart = pos;\n");
                sb.append(pad).append("int choiceStartLine = line;\n");
                sb.append(pad).append("int choiceStartColumn = column;\n");
                int i = 0;
                for (var alt : choice.alternatives()) {
                    sb.append(pad).append("var choiceValues").append(i).append(" = new ArrayList<Object>();\n");
                    sb.append(pad).append("var oldValues = values;\n");
                    sb.append(pad).append("values = choiceValues").append(i).append(";\n");
                    generateExpressionCode(sb, alt, "alt" + i, indent);
                    sb.append(pad).append("values = oldValues;\n");
                    sb.append(pad).append("if (alt").append(i).append(".isSuccess()) {\n");
                    sb.append(pad).append("    values.addAll(choiceValues").append(i).append(");\n");
                    sb.append(pad).append("    ").append(resultVar).append(" = alt").append(i).append(";\n");
                    sb.append(pad).append("} else {\n");
                    sb.append(pad).append("    pos = choiceStart;\n");
                    sb.append(pad).append("    line = choiceStartLine;\n");
                    sb.append(pad).append("    column = choiceStartColumn;\n");
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
                sb.append(pad).append("ParseResult ").append(resultVar).append(" = ParseResult.success(null, pos, line, column);\n");
                sb.append(pad).append("while (true) {\n");
                sb.append(pad).append("    int beforePos = pos;\n");
                sb.append(pad).append("    int beforeLine = line;\n");
                sb.append(pad).append("    int beforeColumn = column;\n");
                sb.append(pad).append("    skipWhitespace();\n");
                generateExpressionCode(sb, zom.expression(), "zomElem", indent + 1);
                sb.append(pad).append("    if (zomElem.isFailure() || pos == beforePos) {\n");
                sb.append(pad).append("        pos = beforePos;\n");
                sb.append(pad).append("        line = beforeLine;\n");
                sb.append(pad).append("        column = beforeColumn;\n");
                sb.append(pad).append("        break;\n");
                sb.append(pad).append("    }\n");
                sb.append(pad).append("}\n");
            }
            case Expression.OneOrMore oom -> {
                generateExpressionCode(sb, oom.expression(), "oomFirst", indent);
                sb.append(pad).append("var ").append(resultVar).append(" = oomFirst;\n");
                sb.append(pad).append("if (oomFirst.isSuccess()) {\n");
                sb.append(pad).append("    while (true) {\n");
                sb.append(pad).append("        int beforePos = pos;\n");
                sb.append(pad).append("        int beforeLine = line;\n");
                sb.append(pad).append("        int beforeColumn = column;\n");
                sb.append(pad).append("        skipWhitespace();\n");
                generateExpressionCode(sb, oom.expression(), "oomElem", indent + 2);
                sb.append(pad).append("        if (oomElem.isFailure() || pos == beforePos) {\n");
                sb.append(pad).append("            pos = beforePos;\n");
                sb.append(pad).append("            line = beforeLine;\n");
                sb.append(pad).append("            column = beforeColumn;\n");
                sb.append(pad).append("            break;\n");
                sb.append(pad).append("        }\n");
                sb.append(pad).append("    }\n");
                sb.append(pad).append("}\n");
            }
            case Expression.Optional opt -> {
                sb.append(pad).append("int optStart = pos;\n");
                sb.append(pad).append("int optStartLine = line;\n");
                sb.append(pad).append("int optStartColumn = column;\n");
                generateExpressionCode(sb, opt.expression(), "optElem", indent);
                sb.append(pad).append("var ").append(resultVar).append(" = optElem.isSuccess() ? optElem : ParseResult.success(null, pos, line, column);\n");
                sb.append(pad).append("if (optElem.isFailure()) {\n");
                sb.append(pad).append("    pos = optStart;\n");
                sb.append(pad).append("    line = optStartLine;\n");
                sb.append(pad).append("    column = optStartColumn;\n");
                sb.append(pad).append("}\n");
            }
            case Expression.Repetition rep -> {
                sb.append(pad).append("int repCount = 0;\n");
                sb.append(pad).append("int repStart = pos;\n");
                sb.append(pad).append("int repStartLine = line;\n");
                sb.append(pad).append("int repStartColumn = column;\n");
                var maxStr = rep.max().isPresent() ? String.valueOf(rep.max().unwrap()) : "Integer.MAX_VALUE";
                sb.append(pad).append("while (repCount < ").append(maxStr).append(") {\n");
                sb.append(pad).append("    int beforePos = pos;\n");
                sb.append(pad).append("    int beforeLine = line;\n");
                sb.append(pad).append("    int beforeColumn = column;\n");
                sb.append(pad).append("    if (repCount > 0) skipWhitespace();\n");
                generateExpressionCode(sb, rep.expression(), "repElem", indent + 1);
                sb.append(pad).append("    if (repElem.isFailure() || pos == beforePos) {\n");
                sb.append(pad).append("        pos = beforePos;\n");
                sb.append(pad).append("        line = beforeLine;\n");
                sb.append(pad).append("        column = beforeColumn;\n");
                sb.append(pad).append("        break;\n");
                sb.append(pad).append("    }\n");
                sb.append(pad).append("    repCount++;\n");
                sb.append(pad).append("}\n");
                sb.append(pad).append("var ").append(resultVar).append(" = repCount >= ").append(rep.min())
                    .append(" ? ParseResult.success(null, pos, line, column) : ParseResult.failure(\"at least ")
                    .append(rep.min()).append(" repetitions\");\n");
                sb.append(pad).append("if (").append(resultVar).append(".isFailure()) {\n");
                sb.append(pad).append("    pos = repStart;\n");
                sb.append(pad).append("    line = repStartLine;\n");
                sb.append(pad).append("    column = repStartColumn;\n");
                sb.append(pad).append("}\n");
            }
            case Expression.And and -> {
                sb.append(pad).append("int andStart = pos;\n");
                sb.append(pad).append("int andStartLine = line;\n");
                sb.append(pad).append("int andStartColumn = column;\n");
                generateExpressionCode(sb, and.expression(), "andElem", indent);
                sb.append(pad).append("pos = andStart;\n");
                sb.append(pad).append("line = andStartLine;\n");
                sb.append(pad).append("column = andStartColumn;\n");
                sb.append(pad).append("var ").append(resultVar).append(" = andElem.isSuccess() ? ParseResult.success(null, pos, line, column) : andElem;\n");
            }
            case Expression.Not not -> {
                sb.append(pad).append("int notStart = pos;\n");
                sb.append(pad).append("int notStartLine = line;\n");
                sb.append(pad).append("int notStartColumn = column;\n");
                generateExpressionCode(sb, not.expression(), "notElem", indent);
                sb.append(pad).append("pos = notStart;\n");
                sb.append(pad).append("line = notStartLine;\n");
                sb.append(pad).append("column = notStartColumn;\n");
                sb.append(pad).append("var ").append(resultVar).append(" = notElem.isSuccess() ? ParseResult.failure(\"not match\") : ParseResult.success(null, pos, line, column);\n");
            }
            case Expression.TokenBoundary tb -> {
                sb.append(pad).append("int tbStart = pos;\n");
                generateExpressionCode(sb, tb.expression(), "tbElem", indent);
                sb.append(pad).append("var ").append(resultVar).append(" = tbElem.isSuccess() ? ParseResult.success(substring(tbStart, pos), pos, line, column) : tbElem;\n");
            }
            case Expression.Ignore ign -> {
                generateExpressionCode(sb, ign.expression(), "ignElem", indent);
                sb.append(pad).append("var ").append(resultVar).append(" = ignElem.isSuccess() ? ParseResult.success(null, pos, line, column) : ignElem;\n");
            }
            case Expression.Capture cap -> {
                sb.append(pad).append("int capStart = pos;\n");
                generateExpressionCode(sb, cap.expression(), "capElem", indent);
                sb.append(pad).append("if (capElem.isSuccess()) {\n");
                sb.append(pad).append("    captures.put(\"").append(cap.name()).append("\", substring(capStart, pos));\n");
                sb.append(pad).append("}\n");
                sb.append(pad).append("var ").append(resultVar).append(" = capElem;\n");
            }
            case Expression.BackReference br -> {
                sb.append(pad).append("var captured = captures.get(\"").append(br.name()).append("\");\n");
                sb.append(pad).append("var ").append(resultVar).append(" = captured != null ? matchLiteral(captured, false) : ParseResult.failure(\"capture '\");\n");
            }
            case Expression.Cut cut -> {
                sb.append(pad).append("var ").append(resultVar).append(" = ParseResult.success(null, pos, line, column);\n");
            }
            case Expression.Group grp -> {
                generateExpressionCode(sb, grp.expression(), resultVar, indent);
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
            sb.append("            int beforePos = pos;\n");
            generateExpressionCode(sb, grammar.whitespace().unwrap(), "wsResult", 3);
            sb.append("            if (wsResult.isFailure() || pos == beforePos) break;\n");
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

                private boolean matchesPattern(char c, String pattern, boolean caseInsensitive) {
                    char testChar = caseInsensitive ? Character.toLowerCase(c) : c;
                    int i = 0;
                    while (i < pattern.length()) {
                        char start = pattern.charAt(i);
                        if (start == '\\\\' && i + 1 < pattern.length()) {
                            char escaped = pattern.charAt(i + 1);
                            char expected = switch (escaped) {
                                case 'n' -> '\\n';
                                case 'r' -> '\\r';
                                case 't' -> '\\t';
                                case '\\\\' -> '\\\\';
                                case ']' -> ']';
                                case '-' -> '-';
                                default -> escaped;
                            };
                            if (caseInsensitive) expected = Character.toLowerCase(expected);
                            if (testChar == expected) return true;
                            i += 2;
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

                    private ParseResult(boolean success, Object value, String expected, int endPos, int endLine, int endColumn) {
                        this.success = success;
                        this.value = value;
                        this.expected = expected;
                        this.endPos = endPos;
                        this.endLine = endLine;
                        this.endColumn = endColumn;
                    }

                    boolean isSuccess() { return success; }
                    boolean isFailure() { return !success; }

                    static ParseResult success(Object value, int endPos, int endLine, int endColumn) {
                        return new ParseResult(true, value, null, endPos, endLine, endColumn);
                    }

                    static ParseResult failure(String expected) {
                        return new ParseResult(false, null, expected, 0, 0, 0);
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
}
