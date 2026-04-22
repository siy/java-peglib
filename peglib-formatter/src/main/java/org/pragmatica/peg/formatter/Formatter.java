package org.pragmatica.peg.formatter;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.peg.formatter.internal.Renderer;
import org.pragmatica.peg.tree.CstNode;
import org.pragmatica.peg.tree.Trivia;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Fluent builder + CST walker for a {@link Doc}-based pretty-printer.
 *
 * <p>Usage pattern:
 *
 * <pre>{@code
 * import static org.pragmatica.peg.formatter.Docs.*;
 *
 * var formatter = new Formatter()
 *     .defaultIndent(2)
 *     .maxLineWidth(80)
 *     .rule("Block", (ctx, children) ->
 *         group(text("{"),
 *               indent(ctx.defaultIndent(), line(), concat(children)),
 *               line(), text("}")));
 *
 * Result<String> out = formatter.format(cst);
 * }</pre>
 *
 * <p>Lookup happens by {@link CstNode#rule() rule name}. If no rule is
 * registered for a node, the formatter falls back to
 * {@link #defaultFallback(CstNode, List)} which emits the node's text verbatim
 * for {@link CstNode.Terminal}/{@link CstNode.Token} leaves, and the
 * concatenation of children for {@link CstNode.NonTerminal} interior nodes.
 *
 * <p>Trivia handling is driven by {@link TriviaPolicy}. By default trivia is
 * preserved verbatim — each leaf's leading trivia is emitted before the
 * leaf's doc and trailing trivia after. Custom policies can strip whitespace,
 * normalise blank lines, etc.
 *
 * <p>Instances are mutable during configuration and immutable after
 * {@link #format(CstNode)} is invoked (the map is read-only thereafter).
 * Not thread-safe during configuration; thread-safe to call
 * {@link #format(CstNode)} concurrently once configured.
 *
 * @since 0.3.3
 */
public final class Formatter {
    private final Map<String, FormatterRule> rules = new HashMap<>();
    private int defaultIndent = 2;
    private int maxLineWidth = 80;
    private TriviaPolicy triviaPolicy = TriviaPolicy.PRESERVE;

    public Formatter() {}

    /** Register a rule for the given CST rule name. Last registration wins. */
    public Formatter rule(String ruleName, FormatterRule rule) {
        if (ruleName == null || ruleName.isEmpty()) {
            throw new IllegalArgumentException("ruleName must be non-empty");
        }
        if (rule == null) {
            throw new IllegalArgumentException("rule must not be null");
        }
        rules.put(ruleName, rule);
        return this;
    }

    /** Default indent width in columns (default: 2). */
    public Formatter defaultIndent(int amount) {
        if (amount < 0) {
            throw new IllegalArgumentException("defaultIndent must be >= 0");
        }
        this.defaultIndent = amount;
        return this;
    }

    /** Target maximum line width in columns (default: 80). */
    public Formatter maxLineWidth(int width) {
        if (width <= 0) {
            throw new IllegalArgumentException("maxLineWidth must be > 0");
        }
        this.maxLineWidth = width;
        return this;
    }

    /** Set the trivia emission policy (default: {@link TriviaPolicy#PRESERVE}). */
    public Formatter triviaPolicy(TriviaPolicy policy) {
        if (policy == null) {
            throw new IllegalArgumentException("triviaPolicy must not be null");
        }
        this.triviaPolicy = policy;
        return this;
    }

    /** Read the currently configured default indent. */
    public int defaultIndent() {
        return defaultIndent;
    }

    /** Read the currently configured maximum line width. */
    public int maxLineWidth() {
        return maxLineWidth;
    }

    /** Read the currently configured trivia policy. */
    public TriviaPolicy triviaPolicy() {
        return triviaPolicy;
    }

    /**
     * Format {@code cst} into a string. Returns {@code Result.failure} only
     * if {@code cst} is null or a user rule throws.
     */
    public Result<String> format(CstNode cst) {
        if (cst == null) {
            return FormatterError.NULL_NODE.result();
        }
        try {
            var doc = walk(cst);
            var out = Renderer.render(doc, maxLineWidth);
            return Result.success(out);
        } catch (RuntimeException e) {
            return new FormatterError.RuleFailed(cst.rule(), e).result();
        }
    }

    private Doc walk(CstNode node) {
        var childDocs = collectChildDocs(node);
        var nodeDoc = applyRule(node, childDocs);
        return wrapWithTrivia(node, nodeDoc);
    }

    private List<Doc> collectChildDocs(CstNode node) {
        if (node instanceof CstNode.NonTerminal nt) {
            var out = new ArrayList<Doc>(nt.children().size());
            for (var child : nt.children()) {
                out.add(walk(child));
            }
            return out;
        }
        return List.of();
    }

    private Doc applyRule(CstNode node, List<Doc> childDocs) {
        var rule = rules.get(node.rule());
        if (rule != null) {
            var ctx = new FormatContext(node, defaultIndent, maxLineWidth, triviaPolicy);
            return rule.format(ctx, childDocs);
        }
        return defaultFallback(node, childDocs);
    }

    private Doc wrapWithTrivia(CstNode node, Doc nodeDoc) {
        var leading = triviaPolicy.transform(node.leadingTrivia());
        var trailing = triviaPolicy.transform(node.trailingTrivia());
        if (leading.isEmpty() && trailing.isEmpty()) {
            return nodeDoc;
        }
        var parts = new ArrayList<Doc>(leading.size() + 1 + trailing.size());
        for (var t : leading) {
            parts.add(triviaDoc(t));
        }
        parts.add(nodeDoc);
        for (var t : trailing) {
            parts.add(triviaDoc(t));
        }
        return Docs.concat(parts);
    }

    private static Doc triviaDoc(Trivia trivia) {
        return switch (trivia) {
            case Trivia.Whitespace ws -> whitespaceDoc(ws.text());
            case Trivia.LineComment lc -> Docs.concat(Docs.text(stripTrailingNewline(lc.text())), Docs.hardline());
            case Trivia.BlockComment bc -> blockCommentDoc(bc.text());
        };
    }

    private static String stripTrailingNewline(String text) {
        if (text.endsWith("\n")) {
            return text.substring(0, text.length() - 1);
        }
        return text;
    }

    /**
     * Convert raw whitespace text into a doc: newlines become hard breaks,
     * runs of spaces become text. Tabs are preserved verbatim.
     */
    private static Doc whitespaceDoc(String text) {
        if (text.isEmpty()) {
            return Docs.empty();
        }
        var parts = new ArrayList<Doc>();
        var run = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            var c = text.charAt(i);
            if (c == '\n') {
                if (!run.isEmpty()) {
                    parts.add(Docs.text(run.toString()));
                    run.setLength(0);
                }
                parts.add(hardBreak());
            } else {
                run.append(c);
            }
        }
        if (!run.isEmpty()) {
            parts.add(Docs.text(run.toString()));
        }
        return Docs.concat(parts);
    }

    private static Doc blockCommentDoc(String text) {
        if (text.indexOf('\n') < 0) {
            return Docs.text(text);
        }
        var lines = text.split("\n", -1);
        var parts = new ArrayList<Doc>(lines.length * 2 - 1);
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                parts.add(hardBreak());
            }
            parts.add(Docs.text(lines[i]));
        }
        return Docs.concat(parts);
    }

    private static Doc hardBreak() {
        return new Doc.HardLine();
    }

    private static Doc defaultFallback(CstNode node, List<Doc> childDocs) {
        return switch (node) {
            case CstNode.Terminal t -> Docs.text(t.text());
            case CstNode.Token t -> Docs.text(t.text());
            case CstNode.NonTerminal nt -> Docs.concat(childDocs);
            case CstNode.Error e -> Docs.text(e.skippedText());
        };
    }

    /** Errors a formatter may report. */
    public sealed interface FormatterError extends Cause {
        enum General implements FormatterError {
            NULL_NODE("Cannot format a null CST node");

            private final String message;

            General(String message) {
                this.message = message;
            }

            @Override
            public String message() {
                return message;
            }
        }

        /** Convenience alias. */
        FormatterError NULL_NODE = General.NULL_NODE;

        record RuleFailed(String rule, Throwable cause) implements FormatterError {
            @Override
            public String message() {
                return "Formatter rule for '" + rule + "' threw: " + cause.getClass().getSimpleName()
                       + ": " + cause.getMessage();
            }
        }
    }
}
