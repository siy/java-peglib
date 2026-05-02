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
import static org.pragmatica.peg.formatter.Docs.hardline;
import static org.pragmatica.peg.formatter.Docs.indent;
import static org.pragmatica.peg.formatter.Docs.line;
import static org.pragmatica.peg.formatter.Docs.text;

/**
 * Demo formatter for a small SQL-like SELECT / FROM / WHERE dialect.
 *
 * <p>Grammar note: Query has three children even when Where is absent —
 * the last child is an empty Query continuation. Each clause has the shape
 * {@code [keyword-Terminal, body-NonTerminal]}. Column and ident lists are
 * right-recursive; the formatter flattens them.
 *
 * <p>Keywords are upper-cased in the output; each clause goes on its own line.
 */
public final class SqlFormatter {
    public static final String GRAMMAR = """
        Query   <- Select From Where?
        Select  <- 'select'i ColList
        From    <- 'from'i IdentList
        Where   <- 'where'i Condition
        ColList <- Col (',' Col)*
        IdentList <- Ident (',' Ident)*
        Col     <- Ident
        Condition <- Ident CompOp Value
        CompOp  <- < '=' / '<>' / '<=' / '>=' / '<' / '>' >
        Value   <- Number / Quoted / Ident
        Number  <- < '-'? [0-9]+ >
        Quoted  <- < '\\'' [^']* '\\'' >
        Ident   <- < [a-zA-Z_][a-zA-Z0-9_]* >
        %whitespace <- [ \\t\\r\\n]*
        """;

    private final Parser parser;
    private final Formatter formatter;

    private SqlFormatter(Parser parser, Formatter formatter) {
        this.parser = parser;
        this.formatter = formatter;
    }

    public static SqlFormatter create() {
        var parser = PegParser.fromGrammar(GRAMMAR).unwrap();
        var config = Formatter.builder()
            .defaultIndent(2)
            .maxLineWidth(40)
            .triviaPolicy(TriviaPolicy.DROP_ALL)
            .rule("Query", SqlFormatter::formatQuery)
            .rule("Select", (ctx, children) -> formatClause(ctx, "SELECT"))
            .rule("From", (ctx, children) -> formatClause(ctx, "FROM"))
            .rule("Where", (ctx, children) -> formatClause(ctx, "WHERE"))
            .rule("ColList", (ctx, children) -> formatListFlat(ctx, "ColList"))
            .rule("IdentList", (ctx, children) -> formatListFlat(ctx, "IdentList"))
            .rule("Condition", SqlFormatter::formatCondition)
            .build();
        return new SqlFormatter(parser, Formatter.formatter(config));
    }

    public Result<String> format(String input) {
        return parser.parseCst(input).flatMap(cst -> formatter.format(cst, input));
    }

    // --- rule handlers ------------------------------------------------------

    private static Doc formatQuery(FormatContext ctx, List<Doc> children) {
        if (!(ctx.node() instanceof CstNode.NonTerminal nt)) {
            return concat(children);
        }
        // Clauses are non-empty NonTerminal children of Query (skip continuation).
        var clauses = new ArrayList<Doc>();
        for (var child : nt.children()) {
            if (child instanceof CstNode.NonTerminal cnt && isNonEmpty(cnt)) {
                clauses.add(formatTree(ctx, cnt));
            }
        }
        if (clauses.isEmpty()) {
            return empty();
        }
        var parts = new ArrayList<Doc>(clauses.size() * 2);
        for (int i = 0; i < clauses.size(); i++) {
            if (i > 0) {
                parts.add(hardline());
            }
            parts.add(clauses.get(i));
        }
        return concat(parts);
    }

    /**
     * A clause's children: [keyword-Terminal, body-NonTerminal]. Emit
     * {@code KEYWORD body}.
     */
    private static Doc formatClause(FormatContext ctx, String keyword) {
        if (!(ctx.node() instanceof CstNode.NonTerminal nt)) {
            return text(keyword);
        }
        Doc body = empty();
        for (var child : nt.children()) {
            if (child instanceof CstNode.NonTerminal cnt) {
                body = formatTree(ctx, cnt);
                break;
            }
        }
        return concat(text(keyword), text(" "), body);
    }

    private static Doc formatListFlat(FormatContext ctx, String listRule) {
        var items = new ArrayList<Doc>();
        collectListItemsInto(ctx, ctx.node(), listRule, items);
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
        return group(indent(2, concat(parts)));
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

    private static Doc formatCondition(FormatContext ctx, List<Doc> children) {
        if (!(ctx.node() instanceof CstNode.NonTerminal nt) || nt.children().size() < 3) {
            return concat(children);
        }
        return concat(
            formatTree(ctx, nt.children().get(0)),
            text(" "),
            formatTree(ctx, nt.children().get(1)),
            text(" "),
            formatTree(ctx, nt.children().get(2))
        );
    }

    // --- utilities ----------------------------------------------------------

    private static boolean isNonEmpty(CstNode.NonTerminal nt) {
        return !nt.children().isEmpty();
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
                case "Query" -> formatQuery(childCtx, childrenDocs);
                case "Select" -> formatClause(childCtx, "SELECT");
                case "From" -> formatClause(childCtx, "FROM");
                case "Where" -> formatClause(childCtx, "WHERE");
                case "ColList" -> formatListFlat(childCtx, "ColList");
                case "IdentList" -> formatListFlat(childCtx, "IdentList");
                case "Condition" -> formatCondition(childCtx, childrenDocs);
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
