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

    /**
     * Generate a standalone parser that returns CST (Concrete Syntax Tree) with trivia.
     * The generated parser preserves all source information including whitespace and comments.
     */
    public String generateCst() {
        var sb = new StringBuilder();

        generatePackage(sb);
        generateCstImports(sb);
        generateCstClassStart(sb);
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
                    String rule();
                    List<Trivia> leadingTrivia();
                    List<Trivia> trailingTrivia();

                    record Terminal(SourceSpan span, String rule, String text,
                                    List<Trivia> leadingTrivia, List<Trivia> trailingTrivia) implements CstNode {}

                    record NonTerminal(SourceSpan span, String rule, List<CstNode> children,
                                       List<Trivia> leadingTrivia, List<Trivia> trailingTrivia) implements CstNode {}

                    record Token(SourceSpan span, String rule, String text,
                                 List<Trivia> leadingTrivia, List<Trivia> trailingTrivia) implements CstNode {}
                }

                public record ParseError(SourceLocation location, String reason) implements Cause {
                    @Override
                    public String message() {
                        return reason + " at " + location;
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

                private void init(String input) {
                    this.input = input;
                    this.pos = 0;
                    this.line = 1;
                    this.column = 1;
                    this.cache = new HashMap<>();
                    this.captures = new HashMap<>();
                    this.inTokenBoundary = false;
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
    }

    private void generateCstParseMethods(StringBuilder sb) {
        var startRule = grammar.effectiveStartRule();
        var startRuleName = startRule.isPresent() ? startRule.unwrap().name() : grammar.rules().getFirst().name();

        sb.append("""
                // === Public Parse Methods ===

                public Result<CstNode> parse(String input) {
                    init(input);
                    var leadingTrivia = skipWhitespace();
                    var result = parse_%s(leadingTrivia);
                    if (result.isFailure()) {
                        return Result.failure(new ParseError(location(), "expected " + result.expected));
                    }
                    skipWhitespace(); // Consume trailing whitespace
                    if (!isAtEnd()) {
                        return Result.failure(new ParseError(location(), "unexpected input"));
                    }
                    return Result.success(result.node);
                }

            """.formatted(sanitize(startRuleName)));
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
        if (isTokenRule(rule.expression())) {
            sb.append("            var node = new CstNode.Token(span, \"").append(ruleName)
              .append("\", result.text, leadingTrivia, List.of());\n");
        } else {
            sb.append("            var node = new CstNode.NonTerminal(span, \"").append(ruleName)
              .append("\", children, leadingTrivia, List.of());\n");
        }
        sb.append("            finalResult = CstParseResult.success(node, result.text, endLoc);\n");
        sb.append("        } else {\n");
        sb.append("            restoreLocation(startLoc);\n");
        sb.append("            finalResult = result;\n");
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
                sb.append(pad).append("CstParseResult ").append(resultVar).append(" = CstParseResult.success(null, \"\", location());\n");
                sb.append(pad).append("var ").append(seqStart).append(" = location();\n");
                int i = 0;
                for (var elem : seq.elements()) {
                    sb.append(pad).append("if (").append(resultVar).append(".isSuccess()) {\n");
                    if (i > 0 && !inWhitespaceRule) {
                        sb.append(pad).append("    if (!inTokenBoundary) skipWhitespace();\n");
                    }
                    var elemVar = "elem" + id + "_" + i;
                    generateCstExpressionCode(sb, elem, elemVar, indent + 1, addToChildren, counter, inWhitespaceRule);
                    sb.append(pad).append("    if (").append(elemVar).append(".isFailure()) {\n");
                    sb.append(pad).append("        restoreLocation(").append(seqStart).append(");\n");
                    sb.append(pad).append("        ").append(resultVar).append(" = ").append(elemVar).append(";\n");
                    sb.append(pad).append("    }\n");
                    sb.append(pad).append("}\n");
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
                if (!inWhitespaceRule) {
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
                if (!inWhitespaceRule) {
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
                if (!inWhitespaceRule) {
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
                sb.append(pad).append("    var tbNode").append(id).append(" = new CstNode.Token(tbSpan").append(id).append(", \"token\", tbText").append(id).append(", List.of(), List.of());\n");
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
                    var node = new CstNode.Terminal(span, "literal", text, List.of(), List.of());
                    return CstParseResult.success(node, text, location());
                }

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
                    var node = new CstNode.Terminal(span, "char", text, List.of(), List.of());
                    return CstParseResult.success(node, text, location());
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

                private CstParseResult matchAnyCst() {
                    if (isAtEnd()) {
                        return CstParseResult.failure("any character");
                    }
                    var startLoc = location();
                    char c = advance();
                    var text = String.valueOf(c);
                    var span = SourceSpan.of(startLoc, location());
                    var node = new CstNode.Terminal(span, "any", text, List.of(), List.of());
                    return CstParseResult.success(node, text, location());
                }

                // === CST Parse Result ===

                private static final class CstParseResult {
                    final boolean success;
                    final CstNode node;
                    final String text;
                    final String expected;
                    final SourceLocation endLocation;

                    private CstParseResult(boolean success, CstNode node, String text, String expected, SourceLocation endLocation) {
                        this.success = success;
                        this.node = node;
                        this.text = text;
                        this.expected = expected;
                        this.endLocation = endLocation;
                    }

                    boolean isSuccess() { return success; }
                    boolean isFailure() { return !success; }

                    static CstParseResult success(CstNode node, String text, SourceLocation endLocation) {
                        return new CstParseResult(true, node, text, null, endLocation);
                    }

                    static CstParseResult failure(String expected) {
                        return new CstParseResult(false, null, null, expected, null);
                    }
                }
            """);
    }
}
