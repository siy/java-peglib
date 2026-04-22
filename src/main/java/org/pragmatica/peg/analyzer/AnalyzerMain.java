package org.pragmatica.peg.analyzer;

import org.pragmatica.peg.grammar.Grammar;
import org.pragmatica.peg.grammar.GrammarParser;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * CLI entry point for the grammar analyzer.
 *
 * <p>Usage:
 * <pre>
 * java -cp peglib.jar org.pragmatica.peg.analyzer.AnalyzerMain &lt;grammar.peg&gt;
 * </pre>
 *
 * <p>Exits with status code 0 when no {@code ERROR} findings were produced,
 * 1 when at least one error was found, or 2 on I/O / grammar-parse failure.
 */
public final class AnalyzerMain {
    private AnalyzerMain() {}

    public static void main(String[] args) {
        if (args.length != 1) {
            System.err.println("Usage: peglib-analyze <grammar.peg>");
            System.exit(2);
            return;
        }
        var path = Path.of(args[0]);
        String grammarText;
        try {
            grammarText = Files.readString(path);
        } catch (Exception e) {
            System.err.println("error: failed to read grammar file: " + e.getMessage());
            System.exit(2);
            return;
        }
        var parsed = GrammarParser.parse(grammarText)
                                  .flatMap(Grammar::validate);
        if (parsed instanceof org.pragmatica.lang.Result.Failure<?> failure) {
            System.err.println("error: " + failure.cause()
                                                  .message());
            System.exit(2);
            return;
        }
        var grammar = parsed.unwrap();
        var report = Analyzer.analyze(grammar);
        System.out.print(report.formatRustStyle(path.toString()));
        System.exit(report.hasErrors()
                    ? 1
                    : 0);
    }
}
