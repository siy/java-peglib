import org.pragmatica.peg.PegParser;
import org.pragmatica.peg.Parser;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs the peglib Java grammar over the OpenJDK langtools javac test corpus.
 *
 * Usage: java LangtoolsRunner <grammar.peg> <file-list> <mode:positive|negative> [limit]
 *
 * positive: files expected to parse cleanly; any diagnostic is a finding.
 * negative: files that javac rejects; here we only measure how many we also reject,
 *           which is informational -- a permissive parser legitimately accepts some.
 */
public final class LangtoolsRunner {

    public static void main(String[] args) throws Exception {
        var grammar = Files.readString(Path.of(args[0]));
        var listFile = Path.of(args[1]);
        var mode = args[2];
        var limit = args.length > 3 ? Integer.parseInt(args[3]) : Integer.MAX_VALUE;

        Parser parser = PegParser.fromGrammar(grammar).unwrap();

        var files = Files.readAllLines(listFile).stream().filter(s -> !s.isBlank()).limit(limit).toList();

        int ok = 0, bad = 0, unreadable = 0, crashed = 0;
        var byFirstMessage = new LinkedHashMap<String, Integer>();
        var failures = new ArrayList<String>();

        long t0 = System.currentTimeMillis();

        for (var f : files) {
            String src;
            try {
                src = Files.readString(Path.of(f));
            } catch (Exception e) {
                unreadable++ ;
                continue;
            }

            try {
                var r = parser.parse(src);
                if (r.diagnostics().isEmpty()) {
                    ok++ ;
                } else {
                    bad++ ;
                    var msg = r.diagnostics().get(0).message();
                    byFirstMessage.merge(msg, 1, Integer::sum);
                    if (failures.size() < 4000) {
                        failures.add(f + "\t" + r.diagnostics().size() + "\t" + msg);
                    }
                }
            } catch (Throwable t) {
                crashed++ ;
                failures.add(f + "\tCRASH\t" + t.getClass().getSimpleName() + ": " + t.getMessage());
            }
        }

        long ms = System.currentTimeMillis() - t0;

        System.out.println("=== langtools " + mode + " ===");
        System.out.println("files=" + files.size() + " parsedClean=" + ok + " withDiagnostics=" + bad
                           + " crashed=" + crashed + " unreadable=" + unreadable
                           + "  (" + ms + " ms)");
        if (!files.isEmpty()) {
            System.out.printf("clean rate: %.1f%%%n", 100.0 * ok / files.size());
        }

        System.out.println("--- top first-diagnostic messages ---");
        byFirstMessage.entrySet()
                      .stream()
                      .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                      .limit(15)
                      .forEach(e -> System.out.printf("  %5d  %s%n", e.getValue(), e.getKey()));

        Files.write(Path.of(System.getProperty("out", "/tmp/langtools-failures.tsv")), failures);
        System.out.println("failure detail -> " + System.getProperty("out", "/tmp/langtools-failures.tsv"));
    }
}
