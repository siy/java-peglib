package org.pragmatica.peg.formatter;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.peg.formatter.internal.Renderer;
import org.pragmatica.peg.tree.CstNode;
import org.pragmatica.peg.tree.Trivia;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Immutable CST walker for a {@link Doc}-based pretty-printer.
 *
 * <p>Configuration (indent / max line width / trivia policy / per-rule
 * formatters) is captured in a {@link FormatterConfig} record built via
 * {@link FormatterConfig#builder()} (or the convenience {@link #builder()}).
 * A {@link Formatter} is then derived from the config via {@link #formatter}.
 *
 * <p>Usage pattern:
 *
 * <pre>{@code
 * import static org.pragmatica.peg.formatter.Docs.*;
 *
 * var config = FormatterConfig.builder()
 *     .defaultIndent(2)
 *     .maxLineWidth(80)
 *     .rule("Block", (ctx, children) ->
 *         group(text("{"),
 *               indent(ctx.defaultIndent(), line(), concat(children)),
 *               line(), text("}")))
 *     .build();
 *
 * var formatter = Formatter.formatter(config);
 * Result<String> out = formatter.format(cst);
 * }</pre>
 *
 * <p>Lookup happens by {@link CstNode#rule() rule name}. If no rule is
 * registered for a node, the formatter falls back to a default that emits the
 * node's text verbatim for {@link CstNode.Terminal}/{@link CstNode.Token}
 * leaves, and the concatenation of children for
 * {@link CstNode.NonTerminal} interior nodes.
 *
 * <p>Trivia handling is driven by {@link TriviaPolicy}. By default trivia is
 * preserved verbatim — each leaf's leading trivia is emitted before the
 * leaf's doc and trailing trivia after. Custom policies can strip whitespace,
 * normalise blank lines, etc.
 *
 * <p>Instances are immutable and thread-safe to use concurrently.
 *
 * @since 0.3.3
 */
public final class Formatter {
    private final FormatterConfig config;

    private Formatter(FormatterConfig config) {
        this.config = config;
    }

    /** Create a formatter from the given config. */
    public static Formatter formatter(FormatterConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("config must not be null");
        }
        return new Formatter(config);
    }

    /** Convenience entry point for {@link FormatterConfig#builder()}. */
    public static FormatterConfig.Builder builder() {
        return FormatterConfig.builder();
    }

    /** The configuration backing this formatter. */
    public FormatterConfig config() {
        return config;
    }

    /** Read the configured default indent. */
    public int defaultIndent() {
        return config.defaultIndent();
    }

    /** Read the configured maximum line width. */
    public int maxLineWidth() {
        return config.maxLineWidth();
    }

    /** Read the configured trivia policy. */
    public TriviaPolicy triviaPolicy() {
        return config.triviaPolicy();
    }

    /**
     * Format {@code cst} into a string. Uses an empty source buffer; rules
     * that rely on {@link FormatContext#nodeText()} should prefer the
     * {@link #format(CstNode, String)} overload.
     */
    public Result<String> format(CstNode cst) {
        return format(cst, "");
    }

    /**
     * Format {@code cst} into a string, providing the original {@code source}
     * the CST was parsed from. Rules can recover exact source slices via
     * {@link FormatContext#nodeText()}.
     *
     * <p>Returns {@code Result.failure} only if {@code cst} is null or a
     * user rule throws.
     */
    public Result<String> format(CstNode cst, String source) {
        if (cst == null) {
            return FormatterError.NULL_NODE.result();
        }
        if (source == null) {
            return FormatterError.NULL_SOURCE.result();
        }
        try {
            var doc = walk(cst, source);
            var out = Renderer.render(doc, config.maxLineWidth());
            return Result.success(out);
        } catch (RuntimeException e) {
            return new FormatterError.RuleFailed(cst.rule(), e).result();
        }
    }

    private Doc walk(CstNode node, String source) {
        var childDocs = collectChildDocs(node, source);
        var nodeDoc = applyRule(node, source, childDocs);
        return wrapWithTrivia(node, nodeDoc);
    }

    private List<Doc> collectChildDocs(CstNode node, String source) {
        if (node instanceof CstNode.NonTerminal nt) {
            var out = new ArrayList<Doc>(nt.children().size());
            for (var child : nt.children()) {
                out.add(walk(child, source));
            }
            return out;
        }
        return List.of();
    }

    private Doc applyRule(CstNode node, String source, List<Doc> childDocs) {
        Map<String, FormatterRule> rules = config.rules();
        var rule = rules.get(node.rule());
        if (rule != null) {
            var ctx = new FormatContext(node, source, config.defaultIndent(), config.maxLineWidth(), config.triviaPolicy());
            return rule.format(ctx, childDocs);
        }
        return defaultFallback(node, source, childDocs);
    }

    private Doc wrapWithTrivia(CstNode node, Doc nodeDoc) {
        var triviaPolicy = config.triviaPolicy();
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

    private static Doc defaultFallback(CstNode node, String source, List<Doc> childDocs) {
        return switch (node) {
            case CstNode.Terminal t -> Docs.text(t.text());
            case CstNode.Token t -> Docs.text(t.text());
            case CstNode.NonTerminal nt -> {
                // If no children contributed text, fall back to extracting the
                // node's span from the source buffer. This handles the common
                // case of rules whose CST is essentially a single literal match.
                if (childDocs.isEmpty() && !source.isEmpty()) {
                    var span = nt.span();
                    int start = Math.max(0, span.start().offset());
                    int end = Math.min(source.length(), span.end().offset());
                    if (start < end) {
                        yield Docs.text(source.substring(start, end));
                    }
                }
                yield Docs.concat(childDocs);
            }
            case CstNode.Error e -> Docs.text(e.skippedText());
        };
    }

    /** Errors a formatter may report. */
    public sealed interface FormatterError extends Cause {
        enum General implements FormatterError {
            NULL_NODE("Cannot format a null CST node"),
            NULL_SOURCE("Source buffer must not be null");

            private final String message;

            General(String message) {
                this.message = message;
            }

            @Override
            public String message() {
                return message;
            }
        }

        /** Convenience alias: null CST node. */
        FormatterError NULL_NODE = General.NULL_NODE;

        /** Convenience alias: null source buffer. */
        FormatterError NULL_SOURCE = General.NULL_SOURCE;

        record RuleFailed(String rule, Throwable cause) implements FormatterError {
            @Override
            public String message() {
                return "Formatter rule for '" + rule + "' threw: " + cause.getClass().getSimpleName()
                       + ": " + cause.getMessage();
            }
        }
    }
}
