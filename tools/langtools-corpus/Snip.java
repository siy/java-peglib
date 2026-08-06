import org.pragmatica.peg.PegParser;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Minimal single-file probe: prints peglib diagnostics for one Java source file.
 * Usage: java Snip &lt;grammar.peg&gt; &lt;file.java&gt;
 */
public final class Snip {
    public static void main(String[] args) throws Exception {
        var parser = PegParser.fromGrammar(Files.readString(Path.of(args[0]))).unwrap();
        var src = Files.readString(Path.of(args[1]));
        var r = parser.parse(src);

        System.out.println("diagnostics=" + r.diagnostics().size()
                           + " cstNodes=" + (r.diagnostics().isEmpty() ? r.cst().nodeCount() : -1)
                           + " roundTrip=" + (r.cst().reconstruct().equals(src) ? "OK" : "MISMATCH"));
        r.diagnostics().stream().limit(5)
         .forEach(d -> System.out.println("  " + d.formatRustStyle(args[1], src)));
    }
}
