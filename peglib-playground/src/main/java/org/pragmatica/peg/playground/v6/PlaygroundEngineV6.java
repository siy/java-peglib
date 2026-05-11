package org.pragmatica.peg.playground.v6;

import org.pragmatica.lang.Result;
import org.pragmatica.peg.playground.Stats;
import org.pragmatica.peg.v6.PegParser;
import org.pragmatica.peg.v6.cst.CstArray;
import org.pragmatica.peg.v6.cst.ParseResult;
import org.pragmatica.peg.v6.diagnostic.Diagnostic;

import java.util.List;

/**
 * 0.6.0 v6 facade for the playground. Wraps the
 * {@link PegParser#fromGrammar(String) generate-and-compile-in-memory} pipeline
 * and produces a {@link ParseOutcome} containing the resulting
 * {@link CstArray}, diagnostics list, and {@link Stats} record.
 *
 * <p>Parallels the legacy {@code PlaygroundEngine} 0.5.x interpreter facade;
 * lives next to it so callers can opt into v6 without breaking existing UI
 * consumers. Behavioural differences vs the legacy facade:
 *
 * <ul>
 *   <li>No packrat or cut tracing — v6 lex+parse has no packrat cache and
 *       elides cuts at lex time. The corresponding {@link Stats} fields are
 *       always {@code 0}.</li>
 *   <li>Trivia is positional in {@link org.pragmatica.peg.v6.token.TokenArray
 *       TokenArray}; the trivia counter on {@code Stats} is the count of
 *       trivia tokens in the lex output, not per-CST-node attachments.</li>
 *   <li>Tracing is purely a CST-node walk (one rule_enter / rule_success per
 *       parser-rule node). Backtracked alternatives are not visible, same as
 *       legacy.</li>
 * </ul>
 */
public final class PlaygroundEngineV6 {
    private PlaygroundEngineV6() {}

    /**
     * Compile {@code request.grammar()}, lex+parse {@code request.input()},
     * and bundle the result. The grammar compile step is cached by exact text
     * inside {@link PegParser}, so repeated calls with the same grammar pay
     * only the lex+parse cost.
     */
    public static Result<ParseOutcome> run(ParseRequest request) {
        return PegParser.fromGrammar(request.grammar())
                        .map(parser -> executeParse(parser, request));
    }

    private static ParseOutcome executeParse(org.pragmatica.peg.v6.Parser parser, ParseRequest request) {
        long startNanos = System.nanoTime();
        ParseResult parseResult = parser.parse(request.input());
        long elapsedNanos = System.nanoTime() - startNanos;
        var cst = parseResult.cst();
        int nodeCount = cst.nodeCount();
        int triviaCount = countTrivia(cst);
        var stats = new Stats(elapsedNanos / 1000L,
                              nodeCount,
                              triviaCount,
                              0,                                // ruleEntries — n/a in v6
                              0,                                // cacheHits — no packrat
                              0,                                // cacheMisses — no packrat
                              0,                                // cachePuts — no packrat
                              0,                                // cutsFired — n/a (lex-time)
                              parseResult.diagnostics().size());
        return new ParseOutcome(cst, parseResult.diagnostics(), stats);
    }

    /**
     * Tally trivia by walking the underlying token array — v6 stores trivia as
     * positional tokens (whitespace / line-comment / block-comment kinds)
     * rather than as per-node attachments.
     */
    private static int countTrivia(CstArray cst) {
        var tokens = cst.tokens();
        int count = 0;
        for (int i = 0; i < tokens.count(); i++) {
            if (tokens.isTrivia(i)) {
                count++;
            }
        }
        return count;
    }

    /**
     * Inputs to a single v6 playground parse run.
     *
     * @param grammar raw grammar text
     * @param input   raw input text to parse
     */
    public record ParseRequest(String grammar, String input) {}

    /**
     * Everything produced by a single v6 parse run.
     */
    public record ParseOutcome(CstArray cst, List<Diagnostic> diagnostics, Stats stats) {
        public boolean hasErrors() {
            return !diagnostics.isEmpty();
        }
    }
}
