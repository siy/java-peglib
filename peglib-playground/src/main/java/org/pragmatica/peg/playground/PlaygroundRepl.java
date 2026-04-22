package org.pragmatica.peg.playground;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.peg.error.RecoveryStrategy;
import org.pragmatica.peg.playground.PlaygroundEngine.ParseOutcome;
import org.pragmatica.peg.playground.PlaygroundEngine.ParseRequest;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * Command-line REPL for experimenting with a peglib grammar. Given a grammar
 * file, watches its modification time; on each prompt, re-reads the grammar
 * if it has changed. Each non-empty input line is treated as a full parse
 * request — results are printed inline.
 *
 * <p>Usage:
 * <pre>
 *   peglib-playground-cli grammar.peg
 *   peglib-playground-cli grammar.peg --trace
 * </pre>
 *
 * <p>Commands at the prompt:
 * <ul>
 *   <li>{@code :trace on|off} — toggle trace output</li>
 *   <li>{@code :packrat on|off} — toggle packrat cache</li>
 *   <li>{@code :recovery NONE|BASIC|ADVANCED} — switch recovery strategy</li>
 *   <li>{@code :start <rule>} — override the start rule</li>
 *   <li>{@code :reload} — force grammar reload</li>
 *   <li>{@code :quit} — exit</li>
 * </ul>
 */
public final class PlaygroundRepl {
    private final Path grammarPath;
    private final BufferedReader reader;
    private final PrintStream out;

    private boolean trace;
    private boolean packrat = true;
    private boolean captureTrivia = true;
    private RecoveryStrategy recovery = RecoveryStrategy.BASIC;
    private Option<String> startRule = Option.none();
    private String grammarCache = "";
    private long grammarMtime = -1L;

    public PlaygroundRepl(Path grammarPath, BufferedReader reader, PrintStream out, boolean trace) {
        this.grammarPath = grammarPath;
        this.reader = reader;
        this.out = out;
        this.trace = trace;
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 1) {
            System.err.println("usage: PlaygroundRepl <grammar.peg> [--trace]");
            System.exit(2);
            return;
        }
        Path grammar = Path.of(args[0]);
        if (!Files.exists(grammar)) {
            System.err.println("grammar not found: " + grammar);
            System.exit(2);
            return;
        }
        boolean trace = false;
        for (int i = 1; i < args.length; i++) {
            if ("--trace".equals(args[i])) {
                trace = true;
            }
        }
        var reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        var repl = new PlaygroundRepl(grammar, reader, System.out, trace);
        repl.run();
    }

    public void run() throws IOException {
        loadGrammarIfChanged();
        banner();
        while (true) {
            out.print("peg> ");
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
        out.println("peglib playground REPL (grammar: " + grammarPath.toAbsolutePath() + ")");
        out.println("type ':help' for commands, ':quit' to exit.");
    }

    /**
     * Handle a single input line. Returns {@code true} iff the REPL should exit.
     */
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
        String arg = parts.length > 1 ? parts[1].trim() : "";
        switch (cmd) {
            case ":quit", ":q", ":exit" -> {
                return true;
            }
            case ":help" -> printHelp();
            case ":trace" -> trace = parseOnOff(arg, trace);
            case ":packrat" -> packrat = parseOnOff(arg, packrat);
            case ":trivia" -> captureTrivia = parseOnOff(arg, captureTrivia);
            case ":recovery" -> recovery = parseRecovery(arg, recovery);
            case ":start" -> startRule = arg.isEmpty() ? Option.none() : Option.some(arg);
            case ":reload" -> forceReload();
            case ":status" -> printStatus();
            default -> out.println("unknown command: " + cmd + " (try :help)");
        }
        return false;
    }

    private void printHelp() {
        out.println("commands:");
        out.println("  :trace on|off         toggle trace output (current: " + trace + ")");
        out.println("  :packrat on|off       toggle packrat cache (current: " + packrat + ")");
        out.println("  :trivia on|off        toggle trivia capture (current: " + captureTrivia + ")");
        out.println("  :recovery NONE|BASIC|ADVANCED   switch recovery strategy (current: " + recovery + ")");
        out.println("  :start <rule>         override start rule (current: " + startRule.or("<default>") + ")");
        out.println("  :reload               force grammar reload");
        out.println("  :status               show current settings");
        out.println("  :quit                 exit");
        out.println("any other line is parsed as input.");
    }

    private void printStatus() {
        out.println(String.format("grammar: %s (mtime=%d, %d chars)", grammarPath, grammarMtime, grammarCache.length()));
        out.println(String.format("packrat=%s trivia=%s recovery=%s trace=%s start=%s",
                                  packrat, captureTrivia, recovery, trace, startRule.or("<default>")));
    }

    private static boolean parseOnOff(String arg, boolean current) {
        if (arg.isEmpty()) {
            return !current;
        }
        return switch (arg.toLowerCase()) {
            case "on", "true", "yes", "1" -> true;
            case "off", "false", "no", "0" -> false;
            default -> current;
        };
    }

    private static RecoveryStrategy parseRecovery(String arg, RecoveryStrategy current) {
        if (arg.isEmpty()) {
            return current;
        }
        try {
            return RecoveryStrategy.valueOf(arg.toUpperCase());
        } catch (IllegalArgumentException ex) {
            return current;
        }
    }

    private void forceReload() throws IOException {
        grammarMtime = -1L;
        loadGrammarIfChanged();
    }

    private void loadGrammarIfChanged() throws IOException {
        long mtime = Files.getLastModifiedTime(grammarPath).to(TimeUnit.MILLISECONDS);
        if (mtime == grammarMtime) {
            return;
        }
        grammarMtime = mtime;
        grammarCache = Files.readString(grammarPath, StandardCharsets.UTF_8);
        out.println("(grammar loaded: " + grammarCache.length() + " chars)");
    }

    private void runParse(String input) {
        var request = new ParseRequest(grammarCache, input, startRule, packrat, recovery, captureTrivia, false);
        Result<ParseOutcome> result = PlaygroundEngine.run(request);
        result.onFailure(cause -> out.println("grammar error: " + cause.message()))
              .onSuccess(this::reportOutcome);
    }

    private void reportOutcome(ParseOutcome outcome) {
        if (!outcome.hasNode() && !outcome.hasErrors()) {
            out.println("FAIL (no result)");
            return;
        }
        var stats = outcome.stats();
        String status = outcome.hasErrors() ? "FAIL" : "OK";
        out.println(String.format("%s  nodes=%d trivia=%d  %.3f ms",
                                  status,
                                  stats.nodeCount(),
                                  stats.triviaCount(),
                                  stats.timeMicros() / 1000.0));
        if (outcome.hasErrors()) {
            for (var diag : outcome.diagnostics()) {
                out.println("  " + diag.formatSimple());
            }
        }
        if (trace) {
            out.print(outcome.tracer().pretty());
        }
    }
}
