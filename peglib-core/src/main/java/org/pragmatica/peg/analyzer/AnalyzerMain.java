package org.pragmatica.peg.analyzer;

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

    /**
     * JBCT boundary: the JVM launches CLI tools through {@code main}, an
     * untyped {@code void} entry point. This method maps each failure-prone
     * step (file read, grammar parse) onto a process exit code and prints
     * any reported diagnostics to stderr/stdout. Keep the body free of
     * business logic — delegate to {@link Analyzer} and let
     * {@link GrammarParser#parse} surface validation errors as
     * {@code Result.failure}.
     */
    public static void main(String[] args) {
        if (args.length != 1) {
            System.err.println("Usage: peglib-analyze <grammar.peg>");
            System.exit(2);
            return;
        }
        var path = Path.of(args[0]);
        String grammarText;
        try{
            grammarText = Files.readString(path);
        } catch (Exception e) {
            System.err.println("error: failed to read grammar file: " + e.getMessage());
            System.exit(2);
            return;
        }
        // 0.4.0 — GrammarParser.parse(...) routes through Grammar.grammar(...)
        // so validation is performed at construction time.
        var parsed = GrammarParser.parse(grammarText);
        if (parsed instanceof org.pragmatica.lang.Result.Failure< ? > failure) {
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
