package org.pragmatica.peg.formatter.v6;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.peg.formatter.Doc;
import org.pragmatica.peg.formatter.Docs;
import org.pragmatica.peg.formatter.internal.Renderer;
import org.pragmatica.peg.v6.cst.CstArray;
import org.pragmatica.peg.v6.token.TokenArray;

import java.util.ArrayList;
import java.util.List;

/**
 * Wadler-style pretty-printer that walks the 0.6.0 flat-array CST
 * ({@link CstArray}). v6 counterpart of
 * {@link org.pragmatica.peg.formatter.Formatter}, sharing the
 * {@link Doc}/{@link Docs}/{@link Renderer} algebra.
 *
 * <p>Walk: for each node, if a user-supplied {@link V6FormatterRule} is
 * registered under its rule name (via {@link CstArray#kindNameAt(int)}) the
 * rule is invoked with the recursively-walked child docs. Otherwise the
 * default fallback drives a token-range walk over
 * {@code [firstTokenAt..lastTokenAt]}, interleaving recursive child walks with
 * inline-literal tokens (e.g. {@code 'package'}, {@code ';'}) that the
 * generated parser consumes without producing a CST node, plus any trivia
 * tokens between siblings (routed through the active {@link V6TriviaPolicy}).
 *
 * <p>Trivia ownership is positional and unique: every trivia token is emitted
 * exactly once, by the closest enclosing branch's default fallback (for
 * inter-sibling gaps) or by {@link #wrapRootWithFileTrivia} (for trivia before
 * the root's first token / after its last token). User-installed rules
 * receive child docs that are already trivia-aware via this same default
 * fallback, but a rule that wishes to drop or rewrite inter-child whitespace
 * is free to do so by ignoring/manipulating the supplied child docs.
 *
 * <p>Instances are immutable and thread-safe.
 *
 * @since 0.6.0
 */
public final class V6Formatter {
    private final V6FormatterConfig config;

    private V6Formatter(V6FormatterConfig config) {
        this.config = config;
    }

    /** Create a formatter from the given config. */
    public static V6Formatter formatter(V6FormatterConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("config must not be null");
        }
        return new V6Formatter(config);
    }

    /** Convenience entry point for {@link V6FormatterConfig#builder()}. */
    public static V6FormatterConfig.Builder builder() {
        return V6FormatterConfig.builder();
    }

    public V6FormatterConfig config() {
        return config;
    }

    public int defaultIndent() {
        return config.defaultIndent();
    }

    public int maxLineWidth() {
        return config.maxLineWidth();
    }

    public V6TriviaPolicy triviaPolicy() {
        return config.triviaPolicy();
    }

    /**
     * Format the entire CST. Returns {@code Result.failure} only if {@code cst}
     * is null, has no root, or a user rule throws.
     */
    public Result<String> format(CstArray cst) {
        if (cst == null) {
            return V6FormatterError.NULL_CST.result();
        }
        var root = cst.rootIndex();
        if (root == CstArray.NO_NODE) {
            return Result.success("");
        }
        try {
            var doc = walk(cst, root, root);
            var withPrefix = wrapRootWithFileTrivia(cst, root, doc);
            var out = Renderer.render(withPrefix, config.maxLineWidth());
            return Result.success(out);
        } catch (RuntimeException e) {
            return new V6FormatterError.RuleFailed(cst.kindNameAt(root), e).result();
        }
    }

    private Doc walk(CstArray cst, int nodeIdx, int rootIdx) {
        var rule = config.rules().get(cst.kindNameAt(nodeIdx));
        if (rule != null) {
            return applyUserRule(cst, nodeIdx, collectChildDocs(cst, nodeIdx, rootIdx), rule);
        }
        return defaultFallback(cst, nodeIdx, rootIdx);
    }

    private List<Doc> collectChildDocs(CstArray cst, int nodeIdx, int rootIdx) {
        if (cst.firstChildAt(nodeIdx) == CstArray.NO_NODE) {
            return List.of();
        }
        var out = new ArrayList<Doc>();
        cst.children(nodeIdx).forEach(child -> out.add(walk(cst, child, rootIdx)));
        return out;
    }

    private Doc applyUserRule(CstArray cst, int nodeIdx, List<Doc> childDocs, V6FormatterRule rule) {
        var ctx = new V6FormatContext(cst, nodeIdx,
            config.defaultIndent(), config.maxLineWidth(), config.triviaPolicy());
        return rule.format(ctx, childDocs);
    }

    /**
     * Default fallback used when no user-supplied rule is registered for a node.
     * Walks the node's token range {@code [firstTokenAt..lastTokenAt]} and
     * interleaves child docs (recursing through {@link #walk}) with the source
     * text of inline tokens and trivia tokens that fall in the gaps between
     * children. This is essential because the v6 generated parser consumes
     * inline literals (e.g. {@code 'package'}, {@code ';'}) without wrapping
     * them as child CST nodes — a naive {@code concat(childDocs)} would silently
     * drop them. Trivia tokens in the gaps are routed through the active
     * {@link V6TriviaPolicy} for whitespace/comment handling.
     *
     * <p>Note: trivia immediately preceding the node's first token and following
     * its last token is intentionally NOT emitted here; the caller (the parent
     * branch's own {@code defaultFallback}, or {@link #wrapRootWithFileTrivia}
     * for the root) is responsible for those edges. This keeps every trivia
     * token emitted exactly once across the whole tree.
     */
    private Doc defaultFallback(CstArray cst, int nodeIdx, int rootIdx) {
        var first = cst.firstTokenAt(nodeIdx);
        var last = cst.lastTokenAt(nodeIdx);
        if (first < 0 || last < 0 || last < first) {
            return Docs.empty();
        }
        var tokens = cst.tokens();
        var policy = config.triviaPolicy();
        var parts = new ArrayList<Doc>();
        var cursor = first;
        for (var iter = cst.children(nodeIdx).iterator(); iter.hasNext(); ) {
            var childIdx = iter.nextInt();
            var childFirst = cst.firstTokenAt(childIdx);
            var childLast = cst.lastTokenAt(childIdx);
            emitGapTokens(parts, tokens, policy, cursor, childFirst);
            parts.add(walk(cst, childIdx, rootIdx));
            cursor = (childLast < 0) ? cursor : childLast + 1;
        }
        emitGapTokens(parts, tokens, policy, cursor, last + 1);
        if (parts.isEmpty()) {
            return Docs.empty();
        }
        return Docs.concat(parts);
    }

    /**
     * Emit every token in the half-open range {@code [from, to)} that has not
     * already been claimed by a child node. Trivia tokens go through
     * {@code policy}; content (inline-literal) tokens emit their text directly,
     * splitting embedded newlines into hard line breaks.
     */
    private static void emitGapTokens(List<Doc> parts,
                                      TokenArray tokens,
                                      V6TriviaPolicy policy,
                                      int from,
                                      int to) {
        if (from >= to) {
            return;
        }
        // Coalesce contiguous runs of trivia into a single policy invocation —
        // this lets the policy see whitespace + comment runs as a unit so it can
        // collapse blank lines, strip whitespace, etc. coherently.
        var i = from;
        while (i < to) {
            if (tokens.isTrivia(i)) {
                var runStart = i;
                while (i < to && tokens.isTrivia(i)) {
                    i++;
                }
                var runEnd = i;
                var triviaDoc = policy.render(tokens,
                    java.util.stream.IntStream.range(runStart, runEnd));
                if (!(triviaDoc instanceof Doc.Empty)) {
                    parts.add(triviaDoc);
                }
            } else {
                appendTokenText(parts, tokens, i);
                i++;
            }
        }
    }

    /**
     * Append a single content (non-trivia) token's text to {@code parts}, splitting
     * embedded newlines into hard breaks because {@link Doc.Text} forbids newlines.
     * Java text blocks and multi-line annotations / character escapes can produce
     * tokens whose lexed text contains real {@code \n} characters; preserving them
     * via {@link Doc.HardLine} keeps the round-trip token stream intact.
     */
    private static void appendTokenText(List<Doc> parts, TokenArray tokens, int idx) {
        var raw = tokens.textAt(idx).toString();
        if (raw.isEmpty()) {
            return;
        }
        if (raw.indexOf('\n') < 0) {
            parts.add(Docs.text(raw));
            return;
        }
        var lines = raw.split("\n", -1);
        for (var i = 0; i < lines.length; i++) {
            if (i > 0) {
                parts.add(new Doc.HardLine());
            }
            if (!lines[i].isEmpty()) {
                parts.add(Docs.text(lines[i]));
            }
        }
    }

    private Doc wrapRootWithFileTrivia(CstArray cst, int rootIdx, Doc rootDoc) {
        var tokens = cst.tokens();
        var firstTok = cst.firstTokenAt(rootIdx);
        var lastTok = cst.lastTokenAt(rootIdx);
        var prefixIndices = (firstTok > 0)
            ? java.util.stream.IntStream.range(0, firstTok).filter(tokens::isTrivia)
            : java.util.stream.IntStream.empty();
        var suffixStart = (lastTok < 0) ? 0 : lastTok + 1;
        var suffixIndices = (suffixStart < tokens.count())
            ? java.util.stream.IntStream.range(suffixStart, tokens.count()).filter(tokens::isTrivia)
            : java.util.stream.IntStream.empty();
        var prefix = config.triviaPolicy().render(tokens, prefixIndices);
        var suffix = config.triviaPolicy().render(tokens, suffixIndices);
        if (prefix instanceof Doc.Empty && suffix instanceof Doc.Empty) {
            return rootDoc;
        }
        return Docs.concat(prefix, rootDoc, suffix);
    }

    /** Errors a v6 formatter may report. */
    public sealed interface V6FormatterError extends Cause {
        enum General implements V6FormatterError {
            NULL_CST("Cannot format a null CstArray");

            private final String message;

            General(String message) {
                this.message = message;
            }

            @Override
            public String message() {
                return message;
            }
        }

        V6FormatterError NULL_CST = General.NULL_CST;

        record RuleFailed(String rule, Throwable cause) implements V6FormatterError {
            @Override
            public String message() {
                return "V6 formatter rule for '" + rule + "' threw: "
                    + cause.getClass().getSimpleName() + ": " + cause.getMessage();
            }
        }
    }
}
