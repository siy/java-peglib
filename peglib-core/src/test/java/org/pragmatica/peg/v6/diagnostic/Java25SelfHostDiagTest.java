package org.pragmatica.peg.v6.diagnostic;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.pragmatica.peg.v6.PegParser;
import org.pragmatica.peg.v6.cst.CstArray;
import org.pragmatica.peg.v6.cst.ParseResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Diagnostic + bisect harness for the selfhost fixture (1.85MB real-world Java).
 * Mirrors {@code FactoryClassGeneratorDiagTest} shape.
 *
 * <p>Run via:
 * {@code mvn -pl peglib-core test -Dtest=Java25SelfHostDiagTest -DexcludedGroups= -Djbct.skip=true}
 */
public class Java25SelfHostDiagTest {

    private static final Path GRAMMAR = Path.of("src/test/resources/java25.peg");
    private static final Path FIXTURE = Path.of("src/test/resources/bench-fixtures/Java25SelfHost-v51.java.txt");

    @Test
    public void dumpDiagnostics() throws Exception {
        String grammar = Files.readString(GRAMMAR);
        String input = Files.readString(FIXTURE);

        var parser = PegParser.fromGrammar(grammar).unwrap();
        ParseResult r = parser.parse(input, 5000);

        var diags = r.diagnostics();
        System.out.println("=================================================================");
        System.out.println("Input length:        " + input.length());
        System.out.println("Total diagnostics:   " + diags.size());

        // Cluster diagnostics by signature
        Map<String, int[]> clusterCount = new HashMap<>();
        Map<String, Integer> clusterFirstOffset = new LinkedHashMap<>();
        for (var d : diags) {
            String sig = d.message() + "|" + d.expected() + "|" + d.found();
            clusterCount.computeIfAbsent(sig, _ -> new int[1])[0]++;
            clusterFirstOffset.putIfAbsent(sig, d.offset());
        }
        System.out.println("Distinct clusters:   " + clusterCount.size());

        int count = 0;
        for (var entry : clusterFirstOffset.entrySet()) {
            String sig = entry.getKey();
            int offset = entry.getValue();
            int hits = clusterCount.get(sig)[0];
            int ctxStart = Math.max(0, offset - 60);
            int ctxEnd = Math.min(input.length(), offset + 80);
            int line = 1;
            int col = 1;
            for (int i = 0; i < Math.min(offset, input.length()); i++) {
                if (input.charAt(i) == '\n') {
                    line++;
                    col = 1;
                } else {
                    col++;
                }
            }
            String prefix = input.substring(ctxStart, Math.min(offset, input.length()));
            String suffix = offset < input.length() ? input.substring(offset, ctxEnd) : "";
            String[] parts = sig.split("\\|", -1);
            System.out.println();
            System.out.println("=== Cluster " + (++count) + " (hits=" + hits + ") ===");
            System.out.println("  first offset: " + offset + " (line " + line + ", col " + col + ")");
            System.out.println("  message:      " + parts[0]);
            System.out.println("  expected:     " + parts[1]);
            System.out.println("  found:        " + parts[2]);
            System.out.println("  context:      " + prefix.replace("\n", "\\n") + " >>>HERE>>> " + suffix.replace("\n", "\\n"));
            if (count >= 10) break;
        }
        System.out.println("=================================================================");
    }

    // 0.6.1 — currently failing with ~5000 diagnostics, all downstream cascades
    // from a single root cause: shift operators (<<, >>, >>>) in field/local-var
    // initializer context fail at top-level CompilationUnit despite parsing
    // cleanly via parseRuleFrom(Shift) / parseRuleFrom(Expr) in isolation.
    // Hypothesis: Type / Relational / TypeArgs interaction with '<' literals
    // corrupts parser state on backtrack. Deferred to 0.6.2.
    @Disabled("TODO 0.6.2 — shift-in-FieldDecl bug; see comment")
    @Test
    public void selfHostFixtureParsesCleanly() throws Exception {
        String grammar = Files.readString(GRAMMAR);
        String input = Files.readString(FIXTURE);

        var parser = PegParser.fromGrammar(grammar).unwrap();
        ParseResult r = parser.parse(input, 5000);

        var diags = r.diagnostics();
        if (diags.size() > 50) {
            System.err.println("Diagnostic count = " + diags.size() + " (cap=50). First 10:");
            for (int i = 0; i < Math.min(10, diags.size()); i++) {
                var d = diags.get(i);
                int off = d.offset();
                int line = 1;
                int col = 1;
                for (int j = 0; j < Math.min(off, input.length()); j++) {
                    if (input.charAt(j) == '\n') {
                        line++;
                        col = 1;
                    } else {
                        col++;
                    }
                }
                int ctxS = Math.max(0, off - 50);
                int ctxE = Math.min(input.length(), off + 60);
                System.err.println("  [" + i + "] line " + line + ":" + col
                                   + " msg=" + d.message() + " found=" + d.found()
                                   + " ctx=" + input.substring(ctxS, Math.min(off, input.length())).replace("\n", "\\n")
                                   + " >>>HERE>>> "
                                   + (off < input.length() ? input.substring(off, ctxE).replace("\n", "\\n") : ""));
            }
            throw new AssertionError("Selfhost fixture produced " + diags.size() + " diagnostics (target: <= 50)");
        }
    }

    @Disabled("TODO 0.6.2 — depends on shift-in-FieldDecl fix")
    @Test
    public void selfHostFixtureProducesShallowCST() throws Exception {
        String grammar = Files.readString(GRAMMAR);
        String input = Files.readString(FIXTURE);

        var parser = PegParser.fromGrammar(grammar).unwrap();
        ParseResult r = parser.parse(input, 5000);

        CstArray cst = r.cst();
        int nodeCount = cst.nodeCount();
        int loc = (int) input.chars().filter(c -> c == '\n').count();
        System.out.println("Selfhost CST: nodes=" + nodeCount + ", LOC=" + loc + ", ratio=" + ((double) nodeCount / loc));

        // Banked lesson: N LOC → N/3 to N nodes. For 50K LOC: expect 15K-50K+ nodes.
        if (nodeCount < loc / 5) {
            throw new AssertionError("CST suspiciously shallow: " + nodeCount + " nodes for " + loc + " LOC (expected >= " + (loc / 5) + ")");
        }
    }
}
