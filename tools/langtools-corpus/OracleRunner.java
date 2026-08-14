import com.sun.source.util.JavacTask;
import org.pragmatica.peg.PegParser;
import org.pragmatica.peg.Parser;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import java.io.Writer;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Differential corpus check: peglib's Java grammar vs javac's own parse phase.
 *
 * <p>{@link JavacTask#parse()} runs the scanner and parser and nothing else -- no enter,
 * no attribute, no flow. So its ERROR diagnostics are exactly the syntax errors, which
 * makes it ground truth for the only question a parser can be held to. This removes the
 * need for a positive/negative heuristic split: every file is labelled by the oracle.
 *
 * <p>Four verdicts:
 * <ul>
 *   <li>AGREE_CLEAN   -- both accept. Good.
 *   <li>AGREE_REJECT  -- both reject. Good; this is the negative set working.
 *   <li>FALSE_REJECT  -- peglib errors, javac is clean. A grammar or engine gap.
 *   <li>FALSE_ACCEPT  -- peglib is clean, javac reports a syntax error. Grammar too permissive.
 * </ul>
 *
 * Usage: java OracleRunner &lt;grammar.peg&gt; &lt;corpus-root&gt; [limit]
 */
public final class OracleRunner {

    record Verdict(String kind, String file, int javacErrs, String javacCode, long javacLine,
                   String javacMsg, int pegDiags, String pegMsg) {}

    /**
     * Constructs where peglib is intentionally AHEAD of the javac running as oracle.
     *
     * <p>JEP 401 value classes are a shipped 0.7.0 feature targeting JDK 28 preview, gated by
     * {@code ModernJavaSyntaxProbe} and documented in the CHANGELOG. A javac older than JDK 28
     * cannot parse them at any flag setting, so it reports a syntax error and the differential
     * scores the file as a false accept.
     *
     * <p>This exclusion exists because the naive way to reach 100% agreement would be to DELETE
     * JEP 401 support to match an outdated oracle — silently regressing a shipped feature and
     * six probe cases. Excluded files are reported separately and dropped from the agreement
     * denominator rather than counted as passes.
     *
     * <p>When the oracle JDK gains value classes, delete this and the files should become
     * AGREE_CLEAN on their own. If they do not, that is a real gap worth knowing about.
     */
    private static final java.util.regex.Pattern VALUE_CLASS =
        java.util.regex.Pattern.compile("\\bvalue\\s+(class|record|interface)\\b");

    private static boolean oracleTooOld(String src, String javacCode) {
        return VALUE_CLASS.matcher(src).find()
               && (javacCode.equals("compiler.err.class.method.or.field.expected")
                   || javacCode.equals("compiler.err.expected"));
    }

    public static void main(String[] args) throws Exception {
        var grammarPath = Path.of(args[0]);
        var root = Path.of(args[1]);
        var limit = args.length > 2 ? Integer.parseInt(args[2]) : Integer.MAX_VALUE;

        List<Path> files;
        try (var walk = Files.walk(root)) {
            files = walk.filter(p -> p.toString().endsWith(".java"))
                        .sorted()
                        .limit(limit)
                        .toList();
        }

        var parser = PegParser.fromGrammar(Files.readString(grammarPath)).unwrap();
        var compiler = ToolProvider.getSystemJavaCompiler();

        if (compiler == null) {
            throw new IllegalStateException("No JavaCompiler -- JDK (not JRE) required");
        }

        // Preview syntax appears throughout langtools. Enabling it keeps javac from
        // reporting a preview-gated construct as a syntax error, which would show up
        // as a bogus FALSE_ACCEPT on our side.
        var options = List.of("--enable-preview", "-source", String.valueOf(Runtime.version().feature()));
        Writer devNull = Writer.nullWriter();

        var verdicts = new ArrayList<Verdict>();
        int unreadable = 0, pegCrash = 0, javacCrash = 0;

        long t0 = System.currentTimeMillis();

        for (var path : files) {
            String src;
            try {
                src = Files.readString(path);
            } catch (Exception e) {
                unreadable++;
                continue;
            }

            // --- oracle: javac parse phase only ---
            var collector = new DiagnosticCollector<JavaFileObject>();
            int javacErrs = 0;
            String javacCode = "", javacMsg = "";
            long javacLine = -1;

            try {
                var unit = new SimpleJavaFileObject(URI.create("mem:///" + path.getFileName()),
                                                    JavaFileObject.Kind.SOURCE) {
                    @Override
                    public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                        return src;
                    }
                };
                var task = (JavacTask) compiler.getTask(devNull, null, collector, options, null,
                                                        List.of(unit));
                task.parse();

                for (var d : collector.getDiagnostics()) {
                    if (d.getKind() == Diagnostic.Kind.ERROR) {
                        if (javacErrs == 0) {
                            javacCode = String.valueOf(d.getCode());
                            javacLine = d.getLineNumber();
                            javacMsg = String.valueOf(d.getMessage(null)).replace('\n', ' ');
                        }
                        javacErrs++;
                    }
                }
            } catch (Throwable t) {
                javacCrash++;
                continue;
            }

            // --- peglib ---
            int pegDiags = 0;
            String pegMsg = "";

            try {
                var r = parser.parse(src);
                pegDiags = r.diagnostics().size();
                if (pegDiags > 0) {
                    pegMsg = r.diagnostics().get(0).message();
                }
            } catch (Throwable t) {
                pegCrash++;
                pegDiags = -1;
                pegMsg = "CRASH " + t.getClass().getSimpleName() + ": " + t.getMessage();
            }

            boolean javacOk = javacErrs == 0;
            boolean pegOk = pegDiags == 0;

            String kind;

            if (!javacOk && pegOk && oracleTooOld(src, javacCode)) {
                kind = "EXCLUDED_ORACLE_OLD";
            } else {
                kind = javacOk
                       ? (pegOk ? "AGREE_CLEAN" : "FALSE_REJECT")
                       : (pegOk ? "FALSE_ACCEPT" : "AGREE_REJECT");
            }

            verdicts.add(new Verdict(kind, path.toString(), javacErrs, javacCode, javacLine,
                                     javacMsg, pegDiags, pegMsg));
        }

        long ms = System.currentTimeMillis() - t0;

        var byKind = new TreeMap<String, Integer>();
        verdicts.forEach(v -> byKind.merge(v.kind(), 1, Integer::sum));

        int total = verdicts.size();
        int excluded = byKind.getOrDefault("EXCLUDED_ORACLE_OLD", 0);
        int scored = total - excluded;
        int agree = byKind.getOrDefault("AGREE_CLEAN", 0) + byKind.getOrDefault("AGREE_REJECT", 0);

        System.out.println("=== peglib vs javac-parse differential ===");
        System.out.println("files=" + total + "  unreadable=" + unreadable
                           + "  pegCrash=" + pegCrash + "  javacCrash=" + javacCrash
                           + "  (" + ms + " ms)");
        byKind.forEach((k, n) -> System.out.printf("  %-20s %5d%n", k, n));

        if (excluded > 0) {
            System.out.println("  (" + excluded + " excluded: JEP 401 value classes, which the "
                               + "oracle javac " + Runtime.version().feature()
                               + " cannot parse; peglib is ahead on purpose)");
        }

        System.out.printf("agreement: %.2f%% (%d/%d scored, %d excluded)%n",
                          100.0 * agree / scored, agree, scored, excluded);

        cluster(verdicts, "FALSE_REJECT", Verdict::pegMsg, "peglib first diagnostic");
        cluster(verdicts, "FALSE_ACCEPT", Verdict::javacCode, "javac error code");

        var out = Path.of(System.getProperty("out", "/tmp/oracle.tsv"));
        var lines = new ArrayList<String>();
        lines.add("verdict\tfile\tjavacErrs\tjavacCode\tjavacLine\tjavacMsg\tpegDiags\tpegMsg");
        verdicts.stream()
                .filter(v -> v.kind().startsWith("FALSE"))
                .forEach(v -> lines.add(String.join("\t", v.kind(), v.file(),
                                                    String.valueOf(v.javacErrs()), v.javacCode(),
                                                    String.valueOf(v.javacLine()), v.javacMsg(),
                                                    String.valueOf(v.pegDiags()), v.pegMsg())));
        Files.write(out, lines);
        System.out.println("\ndisagreement detail -> " + out + " (" + (lines.size() - 1) + " rows)");
    }

    private static void cluster(List<Verdict> verdicts, String kind,
                                java.util.function.Function<Verdict, String> key, String label) {
        var counts = new LinkedHashMap<String, Integer>();
        verdicts.stream().filter(v -> v.kind().equals(kind))
                .forEach(v -> counts.merge(key.apply(v), 1, Integer::sum));

        if (counts.isEmpty()) {
            return;
        }

        System.out.println("\n--- " + kind + " by " + label + " ---");
        counts.entrySet().stream()
              .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
              .limit(20)
              .forEach(e -> System.out.printf("  %5d  %s%n", e.getValue(), e.getKey()));
    }
}
