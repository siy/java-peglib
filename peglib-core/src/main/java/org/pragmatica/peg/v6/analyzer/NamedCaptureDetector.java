package org.pragmatica.peg.v6.analyzer;

import org.pragmatica.lang.Result;
import org.pragmatica.peg.grammar.Expression;
import org.pragmatica.peg.grammar.Grammar;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 0.6.0 — detector for named captures ({@code $name<expr>}) and back-references
 * ({@code $name}). The 0.6.0 generate-compile-cache pipeline does not yet
 * implement the runtime semantics for these constructs (parser generator emits
 * a no-op for {@link Expression.BackReference}, and {@link Expression.Capture}
 * is silently transparent), which would otherwise cause grammars to accept
 * inputs they should reject — for example {@code <foo>bar</baz>} matching a
 * tag rule that expects matching open/close names.
 *
 * <p>This analyzer walks every rule's expression tree and reports every
 * occurrence so callers see all offending sites in a single pass. {@link
 * org.pragmatica.peg.v6.PegParser#fromGrammar(String)} surfaces the result via
 * {@link NamedCaptureCause}, rejecting the grammar at compile time rather than
 * generating an unsound parser.
 *
 * <p>{@link Expression.CaptureScope} ({@code $(...)} — capture isolation only,
 * no name binding) is intentionally NOT flagged: it has no runtime effect on
 * matching when no captures or back-references exist inside it. The detector
 * only reports the constructs that actually carry a name.
 */
public final class NamedCaptureDetector {
    private NamedCaptureDetector() {}

    /** Single offending occurrence — either a named capture or a back-reference. */
    public record Occurrence(String ruleName, Kind kind, String name) {
        public String message() {
            return switch (kind) {
                case NAMED_CAPTURE -> "Rule '" + ruleName + "' uses named capture '$" + name + "<...>'";
                case BACK_REFERENCE -> "Rule '" + ruleName + "' uses back-reference '$" + name + "'";
            };
        }
    }

    public enum Kind {
        NAMED_CAPTURE,
        BACK_REFERENCE
    }

    public record DetectionResult(List<Occurrence> occurrences) {
        public boolean hasOccurrences() {
            return !occurrences.isEmpty();
        }
    }

    public static Result<DetectionResult> detect(Grammar grammar) {
        if (grammar == null) {
            throw new IllegalArgumentException("grammar must not be null");
        }
        var occurrences = new ArrayList<Occurrence>();
        for (var rule : grammar.rules()) {
            walk(rule.name(), rule.expression(), occurrences);
        }
        return Result.success(new DetectionResult(Collections.unmodifiableList(occurrences)));
    }

    private static void walk(String ruleName, Expression expr, List<Occurrence> out) {
        switch (expr) {
            case Expression.Capture cap -> {
                out.add(new Occurrence(ruleName, Kind.NAMED_CAPTURE, cap.name()));
                walk(ruleName, cap.expression(), out);
            }
            case Expression.BackReference br ->
            out.add(new Occurrence(ruleName, Kind.BACK_REFERENCE, br.name()));
            case Expression.CaptureScope cs -> walk(ruleName, cs.expression(), out);
            case Expression.Sequence seq -> seq.elements()
                                               .forEach(e -> walk(ruleName, e, out));
            case Expression.Choice ch -> ch.alternatives()
                                           .forEach(e -> walk(ruleName, e, out));
            case Expression.ZeroOrMore z -> walk(ruleName, z.expression(), out);
            case Expression.OneOrMore o -> walk(ruleName, o.expression(), out);
            case Expression.Optional o -> walk(ruleName, o.expression(), out);
            case Expression.Repetition r -> walk(ruleName, r.expression(), out);
            case Expression.And a -> walk(ruleName, a.expression(), out);
            case Expression.Not n -> walk(ruleName, n.expression(), out);
            case Expression.TokenBoundary tb -> walk(ruleName, tb.expression(), out);
            case Expression.Ignore ig -> walk(ruleName, ig.expression(), out);
            case Expression.Group g -> walk(ruleName, g.expression(), out);
            case Expression.Literal __ -> {}
            case Expression.CharClass __ -> {}
            case Expression.Any __ -> {}
            case Expression.Reference __ -> {}
            case Expression.Dictionary __ -> {}
            case Expression.Cut __ -> {}
        }
    }
}
