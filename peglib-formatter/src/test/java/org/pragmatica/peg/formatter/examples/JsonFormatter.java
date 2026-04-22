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
import static org.pragmatica.peg.formatter.Docs.indent;
import static org.pragmatica.peg.formatter.Docs.line;
import static org.pragmatica.peg.formatter.Docs.softline;
import static org.pragmatica.peg.formatter.Docs.text;

/**
 * Demo JSON pretty-printer built on the peglib-formatter framework.
 *
 * <p>Grammar note: peglib collapses single-child alternation, so scalar values
 * (null / true / false / number / string) arrive at the formatter with
 * node rule {@code Value} — either as a {@link CstNode.Terminal} (for
 * keywords) or a {@link CstNode.Token} (for numbers and strings which use
 * the {@code < >} token boundary). Objects and arrays stay as NonTerminal.
 *
 * <p>Strips all whitespace/comments via {@link TriviaPolicy#DROP_ALL};
 * spacing is supplied entirely by the rules.
 */
public final class JsonFormatter {
    public static final String GRAMMAR = """
        Value   <- Object / Array / String / Number / True / False / Null
        Object  <- '{' MemberList? '}'
        MemberList <- Member (',' Member)*
        Member  <- String ':' Value
        Array   <- '[' ValueList? ']'
        ValueList <- Value (',' Value)*
        String  <- < '"' [^"]* '"' >
        Number  <- < '-'? [0-9]+ ('.' [0-9]+)? >
        True    <- 'true'
        False   <- 'false'
        Null    <- 'null'
        %whitespace <- [ \\t\\r\\n]*
        """;

    private final Parser parser;
    private final Formatter formatter;

    private JsonFormatter(Parser parser, Formatter formatter) {
        this.parser = parser;
        this.formatter = formatter;
    }

    public static JsonFormatter create() {
        var parser = PegParser.fromGrammar(GRAMMAR).unwrap();
        var fmt = new Formatter()
            .defaultIndent(2)
            .maxLineWidth(60)
            .triviaPolicy(TriviaPolicy.DROP_ALL)
            .rule("Value", JsonFormatter::formatValue)
            .rule("ValueList", JsonFormatter::formatValueList)
            .rule("MemberList", JsonFormatter::formatMemberList)
            .rule("Member", JsonFormatter::formatMember);
        return new JsonFormatter(parser, fmt);
    }

    public Result<String> format(String input) {
        return parser.parseCst(input).flatMap(cst -> formatter.format(cst, input));
    }

    // --- rule handlers ------------------------------------------------------

    private static Doc formatValue(FormatContext ctx, List<Doc> children) {
        var node = ctx.node();
        // Leaves (null/true/false/number/string) arrive here as Terminal/Token.
        if (node instanceof CstNode.Terminal t) {
            return text(t.text());
        }
        if (node instanceof CstNode.Token t) {
            return text(t.text());
        }
        if (node instanceof CstNode.NonTerminal nt && !nt.children().isEmpty()) {
            var firstText = terminalText(nt.children().getFirst());
            if ("{".equals(firstText)) {
                return formatObject(ctx, nt);
            }
            if ("[".equals(firstText)) {
                return formatArray(ctx, nt);
            }
        }
        return concat(children);
    }

    private static Doc formatObject(FormatContext ctx, CstNode.NonTerminal nt) {
        Doc body = empty();
        for (var child : nt.children()) {
            if (child instanceof CstNode.NonTerminal mlnt && "MemberList".equals(mlnt.rule())) {
                body = formatTree(ctx, mlnt);
                break;
            }
        }
        if (body instanceof Doc.Empty) {
            return text("{}");
        }
        return group(
            text("{"),
            indent(ctx.defaultIndent(), concat(line(), body)),
            line(),
            text("}")
        );
    }

    private static Doc formatArray(FormatContext ctx, CstNode.NonTerminal nt) {
        Doc body = empty();
        for (var child : nt.children()) {
            if (child instanceof CstNode.NonTerminal vlnt && "ValueList".equals(vlnt.rule())) {
                body = formatTree(ctx, vlnt);
                break;
            }
        }
        if (body instanceof Doc.Empty) {
            return text("[]");
        }
        return group(
            text("["),
            indent(ctx.defaultIndent(), concat(softline(), body)),
            softline(),
            text("]")
        );
    }

    /**
     * ValueList is right-recursive: first child = Value, second = ',' then
     * (optional) next ValueList. Flatten and join with "," + line() so the
     * surrounding group decides flat vs. break.
     */
    private static Doc formatValueList(FormatContext ctx, List<Doc> children) {
        var items = new ArrayList<Doc>();
        collectListItemsInto(ctx, ctx.node(), "ValueList", items);
        return joinWithCommaLine(items);
    }

    private static Doc formatMemberList(FormatContext ctx, List<Doc> children) {
        var items = new ArrayList<Doc>();
        collectListItemsInto(ctx, ctx.node(), "MemberList", items);
        return joinWithCommaLine(items);
    }

    private static void collectListItemsInto(FormatContext ctx, CstNode node, String listRule, List<Doc> out) {
        if (!(node instanceof CstNode.NonTerminal nt)) {
            return;
        }
        for (var child : nt.children()) {
            if (child instanceof CstNode.NonTerminal cnt && listRule.equals(cnt.rule())) {
                collectListItemsInto(ctx, cnt, listRule, out);
            } else if (!",".equals(terminalText(child))) {
                out.add(formatTree(ctx, child));
            }
        }
    }

    private static Doc formatMember(FormatContext ctx, List<Doc> children) {
        if (!(ctx.node() instanceof CstNode.NonTerminal nt)) {
            return concat(children);
        }
        Doc key = empty();
        Doc value = empty();
        boolean seenColon = false;
        for (var child : nt.children()) {
            if (":".equals(terminalText(child))) {
                seenColon = true;
                continue;
            }
            if (!seenColon) {
                key = formatTree(ctx, child);
            } else {
                value = formatTree(ctx, child);
            }
        }
        return concat(key, text(": "), value);
    }

    // --- utilities ----------------------------------------------------------

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
                case "Value" -> formatValue(childCtx, childrenDocs);
                case "Member" -> formatMember(childCtx, childrenDocs);
                case "MemberList" -> formatMemberList(childCtx, childrenDocs);
                case "ValueList" -> formatValueList(childCtx, childrenDocs);
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

    private static Doc joinWithCommaLine(List<Doc> items) {
        if (items.isEmpty()) {
            return empty();
        }
        if (items.size() == 1) {
            return items.getFirst();
        }
        var parts = new ArrayList<Doc>(items.size() * 2);
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) {
                parts.add(text(","));
                parts.add(line());
            }
            parts.add(items.get(i));
        }
        return concat(parts);
    }
}
