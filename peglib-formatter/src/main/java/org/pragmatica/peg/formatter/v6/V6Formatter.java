package org.pragmatica.peg.formatter.v6;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.peg.formatter.Doc;
import org.pragmatica.peg.formatter.Docs;
import org.pragmatica.peg.formatter.internal.Renderer;
import org.pragmatica.peg.v6.cst.CstArray;

import java.util.ArrayList;
import java.util.List;

/**
 * Wadler-style pretty-printer that walks the 0.6.0 flat-array CST
 * ({@link CstArray}). v6 counterpart of
 * {@link org.pragmatica.peg.formatter.Formatter}, sharing the
 * {@link Doc}/{@link Docs}/{@link Renderer} algebra.
 *
 * <p>Walk: depth-first over node indices via {@link CstArray#children(int)}.
 * For each node, child docs are computed first, then the rule registered for
 * that node's rule name (via {@link CstArray#kindNameAt(int)}) is invoked. If
 * no rule is registered, the default fallback emits the node's source text
 * verbatim for leaves and the concatenation of child docs for branches.
 *
 * <p>Trivia handling is fully positional: each non-root node's leading and
 * trailing trivia tokens (per {@link CstArray#leadingTriviaTokens(int)} /
 * {@link CstArray#trailingTriviaTokens(int)}) are passed through the active
 * {@link V6TriviaPolicy}. To avoid double-emitting trivia at branch nodes
 * (whose first/last tokens equal those of their leftmost/rightmost descendant
 * leaf), only leaves emit their leading/trailing trivia. The root node also
 * emits any prefix trivia that precedes its first token and any suffix trivia
 * that follows its last token.
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
        var childDocs = collectChildDocs(cst, nodeIdx, rootIdx);
        var nodeDoc = applyRule(cst, nodeIdx, childDocs);
        if (nodeIdx == rootIdx) {
            // Root's leading/trailing trivia is handled exclusively by
            // wrapRootWithFileTrivia to avoid double emission.
            return nodeDoc;
        }
        return wrapNodeWithLeafTrivia(cst, nodeIdx, nodeDoc);
    }

    private List<Doc> collectChildDocs(CstArray cst, int nodeIdx, int rootIdx) {
        if (cst.firstChildAt(nodeIdx) == CstArray.NO_NODE) {
            return List.of();
        }
        var out = new ArrayList<Doc>();
        cst.children(nodeIdx).forEach(child -> out.add(walk(cst, child, rootIdx)));
        return out;
    }

    private Doc applyRule(CstArray cst, int nodeIdx, List<Doc> childDocs) {
        var rule = config.rules().get(cst.kindNameAt(nodeIdx));
        if (rule != null) {
            var ctx = new V6FormatContext(cst, nodeIdx,
                config.defaultIndent(), config.maxLineWidth(), config.triviaPolicy());
            return rule.format(ctx, childDocs);
        }
        return defaultFallback(cst, nodeIdx, childDocs);
    }

    private Doc wrapNodeWithLeafTrivia(CstArray cst, int nodeIdx, Doc nodeDoc) {
        if (cst.firstChildAt(nodeIdx) != CstArray.NO_NODE) {
            return nodeDoc;
        }
        var leadingDoc = config.triviaPolicy().render(cst.tokens(), cst.leadingTriviaTokens(nodeIdx));
        var trailingDoc = config.triviaPolicy().render(cst.tokens(), cst.trailingTriviaTokens(nodeIdx));
        if (leadingDoc instanceof Doc.Empty && trailingDoc instanceof Doc.Empty) {
            return nodeDoc;
        }
        return Docs.concat(leadingDoc, nodeDoc, trailingDoc);
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

    private static Doc defaultFallback(CstArray cst, int nodeIdx, List<Doc> childDocs) {
        if (cst.firstChildAt(nodeIdx) == CstArray.NO_NODE) {
            var raw = cst.textAt(nodeIdx).toString();
            return raw.isEmpty() ? Docs.empty() : Docs.text(raw);
        }
        if (childDocs.isEmpty()) {
            var raw = cst.textAt(nodeIdx).toString();
            return raw.isEmpty() ? Docs.empty() : Docs.text(raw);
        }
        return Docs.concat(childDocs);
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
