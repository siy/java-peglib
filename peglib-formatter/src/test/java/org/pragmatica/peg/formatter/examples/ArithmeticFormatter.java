package org.pragmatica.peg.formatter.examples;

import org.pragmatica.lang.Result;
import org.pragmatica.peg.PegParser;
import org.pragmatica.peg.formatter.Doc;
import org.pragmatica.peg.formatter.FormatContext;
import org.pragmatica.peg.formatter.Formatter;
import org.pragmatica.peg.formatter.TriviaPolicy;
import org.pragmatica.peg.parser.Parser;
import org.pragmatica.peg.tree.CstNode;

import java.util.ArrayList;
import java.util.List;

import static org.pragmatica.peg.formatter.Docs.concat;
import static org.pragmatica.peg.formatter.Docs.empty;
import static org.pragmatica.peg.formatter.Docs.group;
import static org.pragmatica.peg.formatter.Docs.text;

/**
 * Demo formatter for a small arithmetic expression grammar with standard
 * operator precedence (+, -, *, /) and parentheses.
 *
 * <p>Grammar note: {@code Expr} and {@code Term} are right-recursive, and
 * peglib collapses single-alternative dispatches. Numbers arrive as Token
 * with rule=Factor, operators arrive as Token with rule=AddOp/MulOp,
 * parentheses arrive as NonTerminal with rule=Factor containing '(', Expr, ')'.
 *
 * <p>Formats with consistent spacing: {@code a + b * (c - d)}.
 */
public final class ArithmeticFormatter {
    public static final String GRAMMAR = """
        Expr   <- Term (AddOp Term)*
        Term   <- Factor (MulOp Factor)*
        Factor <- Number / Paren
        Paren  <- '(' Expr ')'
        Number <- < '-'? [0-9]+ ('.' [0-9]+)? >
        AddOp  <- < '+' / '-' >
        MulOp  <- < '*' / '/' >
        %whitespace <- [ \\t\\r\\n]*
        """;

    private final Parser parser;
    private final Formatter formatter;

    private ArithmeticFormatter(Parser parser, Formatter formatter) {
        this.parser = parser;
        this.formatter = formatter;
    }

    public static ArithmeticFormatter create() {
        var parser = PegParser.fromGrammar(GRAMMAR).unwrap();
        var config = Formatter.builder()
            .defaultIndent(2)
            .maxLineWidth(80)
            .triviaPolicy(TriviaPolicy.DROP_ALL)
            .rule("Expr", ArithmeticFormatter::formatBinaryChain)
            .rule("Term", ArithmeticFormatter::formatBinaryChain)
            .rule("Factor", ArithmeticFormatter::formatFactor)
            .build();
        return new ArithmeticFormatter(parser, Formatter.formatter(config));
    }

    public Result<String> format(String input) {
        return parser.parseCst(input).flatMap(cst -> formatter.format(cst, input));
    }

    // --- rule handlers ------------------------------------------------------

    private static Doc formatBinaryChain(FormatContext ctx, List<Doc> children) {
        var node = ctx.node();
        if (!(node instanceof CstNode.NonTerminal nt) || nt.children().isEmpty()) {
            return empty();
        }
        // If the node is a "continuation fragment" (first child is an operator
        // or a same-rule continuation), we are not the top of a chain — emit
        // verbatim so that re-formatted output continues to parse. The outer
        // chain rule already collected all items via collectChain.
        var first = nt.children().getFirst();
        if (isOperatorNode(first) || isSameRule(first, nt.rule())) {
            // Render children sequentially with single-space separators.
            return renderFragment(ctx, nt);
        }
        var items = new ArrayList<Doc>();
        var ops = new ArrayList<Doc>();
        collectChain(ctx, nt, items, ops);
        if (items.isEmpty()) {
            return empty();
        }
        if (items.size() == 1) {
            return items.getFirst();
        }
        var parts = new ArrayList<Doc>(items.size() * 3);
        parts.add(items.getFirst());
        int pairs = Math.min(ops.size(), items.size() - 1);
        for (int i = 0; i < pairs; i++) {
            parts.add(text(" "));
            parts.add(ops.get(i));
            parts.add(text(" "));
            parts.add(items.get(i + 1));
        }
        return group(concat(parts));
    }

    /**
     * Render an internal continuation fragment (an Expr/Term whose first
     * child is an operator or another same-rule sub-fragment). The outer
     * top-level chain rule consumes these fragments via {@link #collectChain},
     * so the value returned here is only used when the formatter walker
     * unconditionally calls this rule on internal nodes — we render the
     * subtree as a flat string the parser will re-accept.
     */
    private static Doc renderFragment(FormatContext ctx, CstNode.NonTerminal nt) {
        var items = new ArrayList<Doc>();
        var ops = new ArrayList<Doc>();
        collectChain(ctx, nt, items, ops);
        if (items.isEmpty() && ops.isEmpty()) {
            return empty();
        }
        // Operator-pair fragment: 1 op, 1 operand → " op operand".
        if (items.size() == ops.size()) {
            var parts = new ArrayList<Doc>(items.size() * 4);
            for (int i = 0; i < items.size(); i++) {
                if (i > 0) {
                    parts.add(text(" "));
                }
                parts.add(ops.get(i));
                parts.add(text(" "));
                parts.add(items.get(i));
            }
            return concat(parts);
        }
        // Mixed shape: best-effort interleave.
        var parts = new ArrayList<Doc>(items.size() * 4);
        int o = 0;
        for (int i = 0; i < items.size(); i++) {
            if (i > 0 && o < ops.size()) {
                parts.add(text(" "));
                parts.add(ops.get(o++));
                parts.add(text(" "));
            }
            parts.add(items.get(i));
        }
        while (o < ops.size()) {
            parts.add(text(" "));
            parts.add(ops.get(o++));
        }
        return concat(parts);
    }

    /**
     * Flatten Expr/Term chains. Two child shapes exist:
     * <ul>
     *   <li>Operand form: first child is an operand (non-operator). Children:
     *       {@code [operand, continuation...]} where each continuation is a
     *       same-rule NonTerminal that may be either another operand form or
     *       a group of operator-pair continuations.</li>
     *   <li>Operator-pair form: first child is an operator Token. Children:
     *       {@code [op, operand]} — emits exactly one op/operand pair.</li>
     * </ul>
     * A same-rule NonTerminal whose children are themselves all same-rule
     * NonTerminals acts as a "group" — we walk it to collect the pairs inside.
     */
    private static void collectChain(FormatContext ctx, CstNode node, List<Doc> items, List<Doc> ops) {
        if (!(node instanceof CstNode.NonTerminal nt) || nt.children().isEmpty()) {
            return;
        }
        var children = nt.children();
        var first = children.getFirst();
        if (isOperatorNode(first)) {
            // Operator-pair: [op, operand]
            ops.add(formatTree(ctx, first));
            if (children.size() >= 2) {
                items.add(formatTree(ctx, children.get(1)));
            }
            // Any extra children after the pair (rare): walk them.
            for (int i = 2; i < children.size(); i++) {
                collectChain(ctx, children.get(i), items, ops);
            }
        } else if (isSameRule(first, nt.rule())) {
            // Group form: every child is a same-rule continuation.
            for (var child : children) {
                collectChain(ctx, child, items, ops);
            }
        } else {
            // Operand form: [operand, continuation...]
            items.add(formatTree(ctx, first));
            for (int i = 1; i < children.size(); i++) {
                collectChain(ctx, children.get(i), items, ops);
            }
        }
    }

    private static boolean isSameRule(CstNode node, String rule) {
        return node instanceof CstNode.NonTerminal nt && rule.equals(nt.rule());
    }

    private static Doc formatFactor(FormatContext ctx, List<Doc> children) {
        var node = ctx.node();
        if (node instanceof CstNode.Terminal t) {
            return text(t.text());
        }
        if (node instanceof CstNode.Token t) {
            return text(t.text());
        }
        if (node instanceof CstNode.NonTerminal nt) {
            // Paren form: '(' Expr ')'
            var inner = new ArrayList<Doc>();
            for (var child : nt.children()) {
                var t = terminalText(child);
                if ("(".equals(t) || ")".equals(t)) {
                    continue;
                }
                inner.add(formatTree(ctx, child));
            }
            return concat(text("("), concat(inner), text(")"));
        }
        return concat(children);
    }

    // --- utilities ----------------------------------------------------------

    private static boolean isOperatorNode(CstNode node) {
        if (!(node instanceof CstNode.Token t)) {
            return false;
        }
        return "AddOp".equals(t.rule()) || "MulOp".equals(t.rule());
    }

    private static Doc formatTree(FormatContext ctx, CstNode node) {
        if (node instanceof CstNode.Terminal t) {
            return text(t.text());
        }
        if (node instanceof CstNode.Token t) {
            return text(t.text());
        }
        if (node instanceof CstNode.NonTerminal nt) {
            var childCtx = ctx.forNode(nt);
            var childrenDocs = new ArrayList<Doc>(nt.children().size());
            for (var c : nt.children()) {
                childrenDocs.add(formatTree(ctx, c));
            }
            return switch (nt.rule()) {
                case "Expr", "Term" -> formatBinaryChain(childCtx, childrenDocs);
                case "Factor" -> formatFactor(childCtx, childrenDocs);
                default -> concat(childrenDocs);
            };
        }
        return empty();
    }

    private static String terminalText(CstNode node) {
        return switch (node) {
            case CstNode.Terminal t -> t.text();
            case CstNode.Token t -> t.text();
            default -> null;
        };
    }
}
