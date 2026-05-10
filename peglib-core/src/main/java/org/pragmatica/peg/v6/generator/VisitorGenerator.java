package org.pragmatica.peg.v6.generator;

import java.util.Map;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.peg.grammar.Grammar;
import org.pragmatica.peg.v6.lexer.RuleClassifier;

/**
 * Phase E.1 — emit a per-grammar {@code GVisitor<T>} stub: one
 * {@code visit<RuleName>} method per PARSER/MIXED rule, plus the framework
 * methods ({@code visit}, {@code visitChildren}, {@code defaultResult},
 * {@code aggregateResult}). The generated class is abstract so users subclass
 * and override only the methods they care about.
 *
 * <p>Kind allocation is shared with {@link ParserGenerator#allocateParserRuleKinds}
 * to guarantee dispatch indices match the parser's {@code RULE_*_KIND}
 * constants. LEXER rules and inline-literal kinds correspond to tokens, not
 * CST nodes; they fall through the visitor's {@code default} branch.
 */
public final class VisitorGenerator {

    private VisitorGenerator() {}

    public sealed interface VisitorGenerationError extends Cause {
        record InvalidIdentifier(String component, String value) implements VisitorGenerationError {
            @Override
            public String message() {
                return "Invalid Java identifier for " + component + ": '" + value + "'";
            }
        }
    }

    public record GeneratedVisitor(String packageName, String className, String source) {
        public String fullyQualifiedName() {
            return packageName.isEmpty() ? className : packageName + "." + className;
        }
    }

    public static Result<GeneratedVisitor> generate(Grammar grammar,
                                                    RuleClassifier.Classification classification,
                                                    String packageName,
                                                    String className) {
        if (grammar == null || classification == null) {
            throw new IllegalArgumentException("grammar/classification must not be null");
        }
        if (!isValidQualifiedPackage(packageName)) {
            return new VisitorGenerationError.InvalidIdentifier("packageName", String.valueOf(packageName)).result();
        }
        if (!isValidIdentifier(className)) {
            return new VisitorGenerationError.InvalidIdentifier("className", String.valueOf(className)).result();
        }
        var ruleKinds = ParserGenerator.allocateParserRuleKinds(grammar, classification);
        return Result.success(new GeneratedVisitor(packageName, className, render(packageName, className, ruleKinds)));
    }

    private static String render(String packageName, String className, Map<String, Integer> ruleKinds) {
        var sb = new StringBuilder(2 * 1024 + ruleKinds.size() * 96);
        if (!packageName.isEmpty()) {
            sb.append("package ").append(packageName).append(";\n\n");
        }
        sb.append("import org.pragmatica.peg.v6.cst.CstArray;\n\n");
        sb.append("public abstract class ").append(className).append("<T> {\n\n");

        // Per-rule kind constants — mirror parser's RULE_<Name>_KIND so dispatch
        // matches the generated CST kinds exactly.
        for (var e : ruleKinds.entrySet()) {
            sb.append("    protected static final int RULE_").append(e.getKey())
                .append("_KIND = ").append(e.getValue()).append(";\n");
        }
        sb.append("\n");

        // Dispatch entry point.
        sb.append("    public T visit(CstArray cst, int nodeIdx) {\n");
        sb.append("        int kind = cst.kindAt(nodeIdx);\n");
        sb.append("        return switch (kind) {\n");
        for (var e : ruleKinds.entrySet()) {
            sb.append("            case RULE_").append(e.getKey()).append("_KIND -> visit")
                .append(e.getKey()).append("(cst, nodeIdx);\n");
        }
        sb.append("            default -> defaultResult();\n");
        sb.append("        };\n");
        sb.append("    }\n\n");

        // Walk-children helper.
        sb.append("    protected T visitChildren(CstArray cst, int nodeIdx) {\n");
        sb.append("        T agg = defaultResult();\n");
        sb.append("        var iter = cst.children(nodeIdx).iterator();\n");
        sb.append("        while (iter.hasNext()) {\n");
        sb.append("            int child = iter.next();\n");
        sb.append("            T childResult = visit(cst, child);\n");
        sb.append("            agg = aggregateResult(agg, childResult);\n");
        sb.append("        }\n");
        sb.append("        return agg;\n");
        sb.append("    }\n\n");

        sb.append("    protected T defaultResult() { return null; }\n\n");

        sb.append("    protected T aggregateResult(T agg, T next) { return next; }\n\n");

        // Per-rule visit stubs.
        for (var name : ruleKinds.keySet()) {
            sb.append("    public T visit").append(name).append("(CstArray cst, int nodeIdx) {\n");
            sb.append("        return visitChildren(cst, nodeIdx);\n");
            sb.append("    }\n\n");
        }

        sb.append("}\n");
        return sb.toString();
    }

    private static boolean isValidIdentifier(String s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        if (!Character.isJavaIdentifierStart(s.charAt(0))) {
            return false;
        }
        for (var i = 1; i < s.length(); i++) {
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
