package org.pragmatica.peg.playground.v6;

import org.pragmatica.lang.Result;
import org.pragmatica.peg.playground.v6.PlaygroundEngineV6.ParseOutcome;
import org.pragmatica.peg.playground.v6.PlaygroundEngineV6.ParseRequest;
import org.pragmatica.peg.v6.diagnostic.Diagnostic;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * 0.6.0 v6 CLI REPL: generate-and-compile-in-memory variant of the legacy
 * {@link org.pragmatica.peg.playground.PlaygroundRepl PlaygroundRepl}.
 * Watches the grammar file and parses each non-meta input line through the v6
 * lex+parse pipeline.
 *
 * <p>Usage:
 * <pre>
 *   peglib-playground-cli-v6 grammar.peg
 * </pre>
 *
 * <p>Commands at the prompt:
 * <ul>
 *   <li>{@code :reload} — force grammar reload</li>
 *   <li>{@code :status} — show current settings</li>
 *   <li>{@code :quit} — exit</li>
 * </ul>
 *
 * <p>Compared to the 0.5.x REPL, v6 has no packrat / recovery / start-rule
 * configuration knobs — the lex-then-parse pipeline does not surface those
 * concepts at the user's edge. Behaviour is fixed and deterministic.
 */
public final class PlaygroundReplV6 {
    private final Path grammarPath;
    private final BufferedReader reader;
    private final PrintStream out;

    private String grammarCache = "";
    private long grammarMtime = -1L;

    public PlaygroundReplV6(Path grammarPath, BufferedReader reader, PrintStream out) {
        this.grammarPath = grammarPath;
        this.reader = reader;
        this.out = out;
    }

    /**
     * JBCT boundary: CLI entry point invoked by the JVM. The interactive loop
     * runs in {@link #run()}; failures from the engine surface through the
     * monadic {@code Result} channel into {@link #runParse(String)}.
     */
    public static void main(String[] args) throws IOException {
        if (args.length < 1) {
            System.err.println("usage: PlaygroundReplV6 <grammar.peg>");
            System.exit(2);
            return;
        }
        Path grammar = Path.of(args[0]);
        if (!Files.exists(grammar)) {
            System.err.println("grammar not found: " + grammar);
            System.exit(2);
            return;
        }
        var reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        var repl = new PlaygroundReplV6(grammar, reader, System.out);
        repl.run();
    }

    public void run() throws IOException {
        loadGrammarIfChanged();
        banner();
        while (true) {
            out.print("peg-v6> ");
            out.flush();
            String line = reader.readLine();
            if (line == null) {
                return;
            }
            if (line.isBlank()) {
                continue;
            }
            if (handleCommand(line.trim())) {
                return;
            }
        }
    }

    private void banner() {
        out.println("peglib v6 playground REPL (grammar: " + grammarPath.toAbsolutePath() + ")");
        out.println("type ':help' for commands, ':quit' to exit.");
    }

    /** Handle a single input line. Returns {@code true} iff the REPL should exit. */
    boolean handleCommand(String line) throws IOException {
        if (line.startsWith(":")) {
            return handleMetaCommand(line);
        }
        loadGrammarIfChanged();
        runParse(line);
        return false;
    }

    private boolean handleMetaCommand(String line) throws IOException {
        String[] parts = line.split("\\s+", 2);
        String cmd = parts[0];
        switch (cmd) {
            case ":quit", ":q", ":exit" -> {
                return true;
            }
            case ":help" -> printHelp();
            case ":reload" -> forceReload();
            case ":status" -> printStatus();
            default -> out.println("unknown command: " + cmd + " (try :help)");
        }
        return false;
    }

    private void printHelp() {
        out.println("commands:");
        out.println("  :reload   force grammar reload");
        out.println("  :status   show current settings");
        out.println("  :quit     exit");
        out.println("any other line is parsed as input.");
    }

    private void printStatus() {
        out.println(String.format("grammar: %s (mtime=%d, %d chars)",
                                  grammarPath, grammarMtime, grammarCache.length()));
    }

    private void forceReload() throws IOException {
        grammarMtime = -1L;
        loadGrammarIfChanged();
    }

    private void loadGrammarIfChanged() throws IOException {
        long mtime = Files.getLastModifiedTime(grammarPath)
                          .to(TimeUnit.MILLISECONDS);
        if (mtime == grammarMtime) {
            return;
        }
        grammarMtime = mtime;
        grammarCache = Files.readString(grammarPath, StandardCharsets.UTF_8);
        out.println("(grammar loaded: " + grammarCache.length() + " chars)");
    }

    private void runParse(String input) {
        var request = new ParseRequest(grammarCache, input);
        Result<ParseOutcome> result = PlaygroundEngineV6.run(request);
        result.onFailure(cause -> out.println("grammar error: " + cause.message()))
              .onSuccess(this::reportOutcome);
    }

    private void reportOutcome(ParseOutcome outcome) {
        var stats = outcome.stats();
        String status = outcome.hasErrors() ? "FAIL" : "OK";
        out.println(String.format("%s  nodes=%d trivia=%d  %.3f ms",
                                  status,
                                  stats.nodeCount(),
                                  stats.triviaCount(),
                                  stats.timeMicros() / 1000.0));
        if (outcome.hasErrors()) {
            for (Diagnostic diag : outcome.diagnostics()) {
                out.println("  " + diag.severity().label() + ": " + diag.message()
                    + " (offset=" + diag.offset() + ")");
            }
        }
    }
}
