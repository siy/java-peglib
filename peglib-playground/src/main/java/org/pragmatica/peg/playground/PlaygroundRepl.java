package org.pragmatica.peg.playground;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.peg.playground.PlaygroundEngine.ParseOutcome;
import org.pragmatica.peg.playground.PlaygroundEngine.ParseRequest;
import org.pragmatica.peg.diagnostic.Diagnostic;


/**
 * 0.6.0 CLI REPL: generate-and-compile-in-memory REPL, and the only REPL
 * since 0.7.0 removed the legacy {@code PlaygroundRepl}.
 * Watches the grammar file and parses each non-meta input line through the
 * lex+parse pipeline.
 *
 * <p>Usage:
 * <pre>
 *   peglib-playground-cli grammar.peg
 * </pre>
 *
 * <p>Commands at the prompt:
 * <ul>
 *   <li>{@code :reload} — force grammar reload</li>
 *   <li>{@code :status} — show current settings</li>
 *   <li>{@code :quit} — exit</li>
 * </ul>
 *
 * <p>Compared to the 0.5.x REPL, this has no packrat / recovery / start-rule
 * configuration knobs — the lex-then-parse pipeline does not surface those
 * concepts at the user's edge. Behaviour is fixed and deterministic.
 */
public final class PlaygroundRepl {
    private final Path grammarPath;
    private final BufferedReader reader;
    private final PrintStream out;
    private String grammarCache = "";
    private long grammarMtime = -1L;

    public PlaygroundRepl(Path grammarPath, BufferedReader reader, PrintStream out) {
        this.grammarPath = grammarPath;
        this.reader = reader;
        this.out = out;
    }

    /**
     * JBCT boundary: CLI entry point invoked by the JVM. The interactive loop
     * runs in {@link #run()}; failures from the engine surface through the
     * monadic {@code Result} channel into {@link #runParse(String)}.
     */
    @SuppressWarnings({"JBCT-RET-01", "JBCT-EX-01"})  // JVM entry-point contract: main is void and may declare throws.
    public static void main(String[] args) throws IOException {
        if (args.length < 1) {
            System.err.println("usage: PlaygroundRepl <grammar.peg>");
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
        var repl = new PlaygroundRepl(grammar, reader, System.out);

        repl.run();
    }

    public Result<Unit> run() {
        var loaded = loadGrammarIfChanged();

        if (loaded instanceof Result.Failure<Unit>) {
            return loaded;
        }

        banner();
        while (true) {
            out.print("peg> ");
            out.flush();
            var read = readLine();

            if (read instanceof Result.Failure<Option<String>>) {
                return read.mapToUnit();
            }

            var line = read.unwrap();

            if (line.isEmpty()) {
                return Result.unitResult();
            }

            var text = line.unwrap();

            if (text.isBlank()) {
                continue;
            }

            var handled = handleCommand(text.trim());

            if (handled instanceof Result.Failure<Boolean>) {
                return handled.mapToUnit();
            }

            if (handled.unwrap()) {
                return Result.unitResult();
            }
        }
    }

    /** Empty option means end of stream. */
    private Result<Option<String>> readLine() {
        return Result.lift(Causes::fromThrowable,
                           () -> Option.option(reader.readLine()));
    }

    private void banner() {
        out.println("peglib playground REPL (grammar: " + grammarPath.toAbsolutePath() + ")");
        out.println("type ':help' for commands, ':quit' to exit.");
    }

    /** Handle a single input line. Success value is {@code true} iff the REPL should exit. */
    Result<Boolean> handleCommand(String line) {
        if (line.startsWith(":")) {
            return handleMetaCommand(line);
        }

        return loadGrammarIfChanged().flatMap(__ -> runParse(line))
                                   .map(__ -> Boolean.FALSE);
    }

    private Result<Boolean> handleMetaCommand(String line) {
        String[] parts = line.split("\\s+", 2);
        String cmd = parts[0];

        return switch (cmd) {
            case ":quit", ":q", ":exit" -> Result.success(Boolean.TRUE);
            case ":help" -> {
                printHelp();
                yield Result.success(Boolean.FALSE);
            }
            case ":reload" -> forceReload().map(__ -> Boolean.FALSE);
            case ":status" -> {
                printStatus();
                yield Result.success(Boolean.FALSE);
            }
            default -> {
                out.println("unknown command: " + cmd + " (try :help)");
                yield Result.success(Boolean.FALSE);
            }
        };
    }

    private void printHelp() {
        out.println("commands:");
        out.println("  :reload   force grammar reload");
        out.println("  :status   show current settings");
        out.println("  :quit     exit");
        out.println("any other line is parsed as input.");
    }

    private void printStatus() {
        out.println(String.format("grammar: %s (mtime=%d, %d chars)", grammarPath, grammarMtime, grammarCache.length()));
    }

    private Result<Unit> forceReload() {
        grammarMtime = -1L;

        return loadGrammarIfChanged();
    }

    private Result<Unit> loadGrammarIfChanged() {
        return Result.lift(Causes::fromThrowable, this::reloadIfStale);
    }

    // JDK-API adapter: the body of a Result.lift(...) throwing lambda. Files.getLastModifiedTime
    // and Files.readString declare IOException, which lift() exists to capture.
    @SuppressWarnings("JBCT-EX-01")
    private Unit reloadIfStale() throws IOException {
        long mtime = Files.getLastModifiedTime(grammarPath).to(TimeUnit.MILLISECONDS);

        if (mtime == grammarMtime) {
            return Unit.unit();
        }

        grammarMtime = mtime;
        grammarCache = Files.readString(grammarPath, StandardCharsets.UTF_8);
        out.println("(grammar loaded: " + grammarCache.length() + " chars)");

        return Unit.unit();
    }

    /**
     * Render one parse. Returns the terminal Result so the chain is consumed
     * rather than silently dropped (JBCT-RET-07); both branches have already
     * been reported to the console by the time it is returned.
     */
    private Result<Unit> runParse(String input) {
        var request = new ParseRequest(grammarCache, input);

        return PlaygroundEngine.run(request)
                               .onFailure(cause -> out.println("grammar error: " + cause.message()))
                               .onSuccess(this::reportOutcome)
                               .mapToUnit();
    }

    private void reportOutcome(ParseOutcome outcome) {
        var stats = outcome.stats();
        String status = outcome.hasErrors()
                        ? "FAIL"
                        : "OK";

        out.println(String.format("%s  nodes=%d trivia=%d  %.3f ms",
                                  status,
                                  stats.nodeCount(),
                                  stats.triviaCount(),
                                  stats.timeMicros() / 1000.0));
        if (outcome.hasErrors()) {
            for (Diagnostic diag : outcome.diagnostics()) {
                out.println("  " + diag.severity().label() + ": " + diag.message() + " (offset=" + diag.offset() + ")");
            }
        }
    }
}
