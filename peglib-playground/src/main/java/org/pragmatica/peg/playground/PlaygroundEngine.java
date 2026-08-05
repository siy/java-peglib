package org.pragmatica.peg.playground;

import java.util.List;

import org.pragmatica.lang.Result;
import org.pragmatica.peg.playground.ParseTracer;
import org.pragmatica.peg.playground.Stats;
import org.pragmatica.peg.PegParser;
import org.pragmatica.peg.cst.CstArray;
import org.pragmatica.peg.cst.ParseResult;
import org.pragmatica.peg.diagnostic.Diagnostic;


/**
 * 0.6.0 facade for the playground. Wraps the
 * {@link PegParser#fromGrammar(String) generate-and-compile-in-memory} pipeline
 * and produces a {@link ParseOutcome} containing the resulting
 * {@link CstArray}, diagnostics list, and {@link Stats} record.
 *
 * <p>Parallels the legacy {@code PlaygroundEngine} 0.5.x interpreter facade;
 * lives next to it so callers can opt in without breaking existing UI
 * consumers. Behavioural differences vs the legacy facade:
 *
 * <ul>
 *   <li>No packrat or cut tracing — lex+parse has no packrat cache and
 *       elides cuts at lex time. The corresponding {@link Stats} fields are
 *       always {@code 0}.</li>
 *   <li>Trivia is positional in {@link org.pragmatica.peg.token.TokenArray
 *       TokenArray}; the trivia counter on {@code Stats} is the count of
 *       trivia tokens in the lex output, not per-CST-node attachments.</li>
 *   <li>Tracing is purely a CST-node walk (one rule_enter / rule_success per
 *       parser-rule node). Backtracked alternatives are not visible, same as
 *       legacy.</li>
 * </ul>
 */
public final class PlaygroundEngine {
    private PlaygroundEngine() {}

    /**
     * Compile {@code request.grammar()}, lex+parse {@code request.input()},
     * and bundle the result. The grammar compile step is cached by exact text
     * inside {@link PegParser}, so repeated calls with the same grammar pay
     * only the lex+parse cost.
     */
    public static Result<ParseOutcome> run(ParseRequest request) {
        return PegParser.fromGrammar(request.grammar()).map(parser -> executeParse(parser, request));
    }

    private static ParseOutcome executeParse(org.pragmatica.peg.Parser parser, ParseRequest request) {
        long startNanos = System.nanoTime();
        ParseResult parseResult = parser.parse(request.input());
        long elapsedNanos = System.nanoTime() - startNanos;
        var cst = parseResult.cst();
        int nodeCount = cst.nodeCount();
        int triviaCount = ParseTracer.countTrivia(cst);
        var stats = new Stats(elapsedNanos / 1000L,
                              nodeCount,
                              triviaCount,
                              0,

        // ruleEntries — n/a
        0,

        // cacheHits — no packrat
        0,

        // cacheMisses — no packrat
        0,

        // cachePuts — no packrat
        0,

        // cutsFired — n/a (lex-time)
        parseResult.diagnostics().size());

        return new ParseOutcome(cst, parseResult.diagnostics(), stats);
    }

    /**
     * Inputs to a single playground parse run.
     *
     * @param grammar raw grammar text
     * @param input   raw input text to parse
     */
    public record ParseRequest(String grammar, String input) {}

    /**
     * Everything produced by a single parse run.
     */
    public record ParseOutcome(CstArray cst, List<Diagnostic> diagnostics, Stats stats) {
        public boolean hasErrors() {
            return ! diagnostics.isEmpty();
        }
    }
}
