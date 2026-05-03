package org.pragmatica.peg.playground;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.peg.PegParser;
import org.pragmatica.peg.error.Diagnostic;
import org.pragmatica.peg.error.RecoveryStrategy;
import org.pragmatica.peg.parser.ParseResultWithDiagnostics;
import org.pragmatica.peg.parser.Parser;
import org.pragmatica.peg.parser.ParserConfig;
import org.pragmatica.peg.tree.CstNode;

import java.util.List;

/**
 * Facade that combines grammar compilation, parsing, tracing, and stats
 * collection into a single call. Used by both the HTTP server and the CLI
 * REPL so behaviour stays consistent across the two surfaces.
 */
public final class PlaygroundEngine {
    private PlaygroundEngine() {}

    /**
     * Run a parse with the given grammar and input, returning everything
     * the playground surfaces need.
     */
    public static Result<ParseOutcome> run(ParseRequest request) {
        return PegParser.fromGrammar(request.grammar(), buildConfig(request))
                        .map(parser -> executeParse(parser, request));
    }

    private static ParseOutcome executeParse(Parser parser, ParseRequest request) {
        var tracer = ParseTracer.start();
        var parseResult = parseWithRecovery(parser, request);
        var nodeOption = parseResult.node();
        int nodeCount = nodeOption.map(ParseTracer::countNodes).or(0);
        int triviaCount = nodeOption.map(ParseTracer::countTrivia).or(0);
        nodeOption.onPresent(tracer::walkCst);
        var stats = tracer.stats(nodeCount, triviaCount, parseResult.diagnostics().size());
        return new ParseOutcome(nodeOption, parseResult.diagnostics(), stats, tracer, parseResult.source());
    }

    private static ParseResultWithDiagnostics parseWithRecovery(Parser parser, ParseRequest request) {
        String input = request.input();
        return request.startRule()
                      .filter(rule -> !rule.isBlank())
                      .fold(() -> parser.parseCstWithDiagnostics(input),
                            rule -> parser.parseCstWithDiagnostics(input, rule));
    }

    private static ParserConfig buildConfig(ParseRequest request) {
        return ParserConfig.parserConfig(request.packrat(), request.recovery(), request.captureTrivia());
    }

    /**
     * Inputs to a single playground parse run.
     *
     * @param grammar        raw grammar text
     * @param input          raw input text to parse
     * @param startRule      explicit start rule, or {@link Option#none()} to use the grammar's default
     * @param packrat        whether to enable the packrat cache
     * @param recovery       error recovery strategy
     * @param captureTrivia  whether to attach trivia to the CST
     * @param astMode        whether the output pane should present an AST projection (UI-only flag; the engine always returns CST)
     */
    public record ParseRequest(String grammar,
                               String input,
                               Option<String> startRule,
                               boolean packrat,
                               RecoveryStrategy recovery,
                               boolean captureTrivia,
                               boolean astMode) {}

    /**
     * Everything produced by a single parse run.
     */
    public record ParseOutcome(Option<CstNode> node,
                               List<Diagnostic> diagnostics,
                               Stats stats,
                               ParseTracer tracer,
                               String source) {

        public boolean hasNode() {
            return node.isPresent();
        }

        public boolean hasErrors() {
            return !diagnostics.isEmpty();
        }
    }
}
