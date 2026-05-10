package org.pragmatica.peg.v6.diagnostic;

import org.junit.jupiter.api.Test;
import org.pragmatica.peg.v6.PegParser;
import org.pragmatica.peg.v6.cst.ParseResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

/**
 * One-shot diagnostic dumper: parses FactoryClassGenerator.java.txt with v6 PegParser
 * + java25.peg and prints first 5 unique diagnostics with 130-char surrounding context.
 *
 * <p>Run via:
 * {@code mvn -pl peglib-core test -Dtest=FactoryClassGeneratorDiagTest -DexcludedGroups= -Djbct.skip=true}
 */
public class FactoryClassGeneratorDiagTest {

    @Test
    public void dumpDiagnostics() throws Exception {
        String grammar = Files.readString(Path.of("src/test/resources/java25.peg"));
        String input = Files.readString(Path.of("src/test/resources/perf-corpus/large/FactoryClassGenerator.java.txt"));

        var parser = PegParser.fromGrammar(grammar).unwrap();
        ParseResult r = parser.parse(input);

        var diags = r.diagnostics();
        System.out.println("=================================================================");
        System.out.println("Input length:        " + input.length());
        System.out.println("Total diagnostics:   " + diags.size());

        Set<String> seen = new HashSet<>();
        int count = 0;
        for (var d : diags) {
            String sig = d.message() + "|" + d.expected() + "|" + d.found();
            if (!seen.add(sig)) continue;
            int offset = d.offset();
            int ctxStart = Math.max(0, offset - 60);
            int ctxEnd = Math.min(input.length(), offset + 70);
            int line = 1;
            int col = 1;
            for (int i = 0; i < Math.min(offset, input.length()); i++) {
                if (input.charAt(i) == '\n') { line++; col = 1; } else col++;
            }
            String prefix = input.substring(ctxStart, Math.min(offset, input.length()));
            String suffix = offset < input.length()
                            ? input.substring(offset, ctxEnd)
                            : "";
            System.out.println();
            System.out.println("=== Diag " + (++count) + " ===");
            System.out.println("  offset:   " + offset + " (line " + line + ", col " + col + ")");
            System.out.println("  length:   " + d.length());
            System.out.println("  severity: " + d.severity());
            System.out.println("  message:  " + d.message());
            System.out.println("  expected: " + d.expected());
            System.out.println("  found:    " + d.found());
            System.out.println("  context:  " + prefix.replace("\n", "\\n") + " >>>HERE>>> " + suffix.replace("\n", "\\n"));
            if (count >= 5) break;
        }
        System.out.println("=================================================================");
    }

    @Test
    public void bisectByPrefix() throws Exception {
        String grammar = Files.readString(Path.of("src/test/resources/java25.peg"));
        String input = Files.readString(Path.of("src/test/resources/perf-corpus/large/FactoryClassGenerator.java.txt"));
        var parser = PegParser.fromGrammar(grammar).unwrap();

        // Try increasing prefixes (closed at line boundaries) and append a final '}' to close the class
        String[] lines = input.split("\n", -1);
        int lo = 0, hi = lines.length;
        // Bisect: smallest prefix line count where adding a closing '}' still produces 0 diagnostics
        // (i.e., the body up to that line is parseable). Then prefix+1 is the offending construct.
        while (lo + 1 < hi) {
            int mid = (lo + hi) / 2;
            String prefix = buildPrefix(lines, mid);
            var r = parser.parse(prefix);
            boolean ok = r.diagnostics().isEmpty();
            if (ok) lo = mid; else hi = mid;
        }
        // lo = last good line, hi = first bad line
        System.out.println("Last GOOD line count: " + lo);
        System.out.println("First BAD line count: " + hi);
        // Show the lines around the boundary
        int show = Math.min(hi, lines.length);
        int from = Math.max(0, lo - 2);
        int to = Math.min(lines.length, show + 3);
        System.out.println("--- context lines " + (from + 1) + ".." + to + " ---");
        for (int i = from; i < to; i++) {
            String marker = (i == lo ? "  LAST_OK > " : (i == hi - 1 ? "  FIRST_BAD > " : "           > "));
            System.out.println(marker + (i + 1) + ": " + lines[i]);
        }

        // Also: show diagnostic for the first failing prefix
        String badPrefix = buildPrefix(lines, hi);
        var rb = parser.parse(badPrefix);
        if (!rb.diagnostics().isEmpty()) {
            var d = rb.diagnostics().get(0);
            int off = d.offset();
            int ctxS = Math.max(0, off - 60);
            int ctxE = Math.min(badPrefix.length(), off + 80);
            System.out.println("First bad-prefix diagnostic:");
            System.out.println("  offset=" + off + " msg=" + d.message() + " found=" + d.found() + " expected=" + d.expected());
            System.out.println("  ctx: " + badPrefix.substring(ctxS, Math.min(badPrefix.length(), off)).replace("\n", "\\n")
                              + " >>>HERE>>> " + badPrefix.substring(off, ctxE).replace("\n", "\\n"));
        }
    }

    private static String buildPrefix(String[] lines, int count) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count && i < lines.length; i++) {
            sb.append(lines[i]).append('\n');
        }
        // Append closing brace to allow class body to terminate
        sb.append("}\n");
        return sb.toString();
    }

    @Test
    public void parseOnlyClassBodyByBisection() throws Exception {
        String grammar = Files.readString(Path.of("src/test/resources/java25.peg"));
        String input = Files.readString(Path.of("src/test/resources/perf-corpus/large/FactoryClassGenerator.java.txt"));
        var parser = PegParser.fromGrammar(grammar).unwrap();

        // Take just from "public class" onwards (no package, no imports)
        int classStart = input.indexOf("public class FactoryClassGenerator");
        String body = input.substring(classStart);
        System.out.println("Body length: " + body.length());
        var r = parser.parse(body);
        System.out.println("Total diagnostics (class-only): " + r.diagnostics().size());
        if (!r.diagnostics().isEmpty()) {
            var d = r.diagnostics().get(0);
            int off = d.offset();
            int line = 1, col = 1;
            for (int i = 0; i < off; i++) {
                if (body.charAt(i) == '\n') { line++; col = 1; } else col++;
            }
            int ctxS = Math.max(0, off - 60);
            int ctxE = Math.min(body.length(), off + 80);
            System.out.println("First diag: offset=" + off + " line=" + line + " col=" + col
                              + " msg=" + d.message() + " found=" + d.found() + " expected=" + d.expected());
            System.out.println("ctx: " + body.substring(ctxS, off).replace("\n", "\\n")
                              + " >>>HERE>>> " + body.substring(off, ctxE).replace("\n", "\\n"));
        }
    }

    @Test
    public void parseClassWithProgressivelyMoreBody() throws Exception {
        String grammar = Files.readString(Path.of("src/test/resources/java25.peg"));
        String input = Files.readString(Path.of("src/test/resources/perf-corpus/large/FactoryClassGenerator.java.txt"));
        var parser = PegParser.fromGrammar(grammar).unwrap();

        int classStart = input.indexOf("public class FactoryClassGenerator");
        String body = input.substring(classStart);
        String[] bodyLines = body.split("\n", -1);

        // Try: just "public class FactoryClassGenerator {}" — empty body.
        String t0 = "public class FactoryClassGenerator {}";
        var r0 = parser.parse(t0);
        System.out.println("[empty class] diags=" + r0.diagnostics().size());

        // Try adding ONE line of the body at a time
        StringBuilder cls = new StringBuilder();
        cls.append("public class FactoryClassGenerator {\n");
        for (int i = 1; i < Math.min(bodyLines.length, 250); i++) {
            cls.append(bodyLines[i]).append('\n');
            String src = cls.toString() + "}\n";
            var r = parser.parse(src);
            boolean ok = r.diagnostics().isEmpty();
            if (!ok) {
                System.out.println("FIRST BAD body line index=" + i + ": " + bodyLines[i]);
                // Show ±3 line context
                int from = Math.max(1, i - 3), to = Math.min(bodyLines.length, i + 4);
                for (int k = from; k < to; k++) {
                    String marker = (k == i) ? "  --> " : "      ";
                    System.out.println(marker + k + ": " + bodyLines[k]);
                }
                // Show the first diagnostic
                var d = r.diagnostics().get(0);
                System.out.println("  diag: msg=" + d.message() + " found=" + d.found() + " expected=" + d.expected());
                return;
            }
        }
        System.out.println("No failure within first 250 lines after class header.");
    }

    @Test
    public void testSingleConstructor() throws Exception {
        String grammar = Files.readString(Path.of("src/test/resources/java25.peg"));
        var parser = PegParser.fromGrammar(grammar).unwrap();

        String[] tests = {
                "public class Foo { public Foo() {} }",
                "public class Foo { public Foo(int x) {} }",
                "public class Foo { public Foo(int x, int y) {} }",
                "public class Foo { public Foo(int x,\n int y) {} }",
                // multiline params with whitespace alignment
                "public class Foo {\n  public Foo(int x,\n             int y,\n             int z) {}\n}\n",
                // Reproduce the actual constructor shape
                "public class Foo {\n" +
                "  public Foo(ProcessingEnvironment processingEnv,\n" +
                "             Filer filer) {\n" +
                "    this.processingEnv = processingEnv;\n" +
                "  }\n" +
                "}\n",
                // method using ::
                "public class Foo { void m() { x.method(impl::method); } }",
                // method using Result.failure( etc
                "public class Foo { Result<String> m() { return cause.result(); } }",
                // Java 25 switch pattern
                "public class Foo { String m(Object o) { return switch (o) { case Integer i -> \"int\"; default -> \"other\"; }; } }",
                // text block
                "public class Foo { String s = \"\"\"\n  hello\n  world\n  \"\"\"; }",
                // /// markdown comment INSIDE body
                "public class Foo { /// some doc\n  void m() {} }",
                // method ref on instance
                "public class Foo { void m() { list.forEach(System.out::println); } }",
                // generic method invocation
                "public class Foo { void m() { Collections.<String>emptyList(); } }",
                // diamond operator
                "public class Foo { List<String> l = new ArrayList<>(); }",
                // sealed
                "public sealed class Foo permits Bar {}",
                // record
                "public record Pair(int a, int b) {}",
                // annotation with array value
                "public class Foo { @SuppressWarnings({\"a\", \"b\"}) void m() {} }",
                // lambda
                "public class Foo { Runnable r = () -> System.out.println(\"hi\"); }",
                // try-with-resources
                "public class Foo { void m() { try (var x = open()) { } } }",
                // anon inner class
                "public class Foo { Runnable r = new Runnable() { public void run() {} }; }",
                // var
                "public class Foo { void m() { var x = 1; } }",
                // instanceof pattern
                "public class Foo { void m(Object o) { if (o instanceof String s) {} } }",
                // String formatted (method ref-less)
                "public class Foo { String s = \"x=%d\".formatted(42); }",
        };
        for (var t : tests) {
            var r = parser.parse(t);
            String oneLine = t.replace("\n", "\\n");
            if (oneLine.length() > 120) oneLine = oneLine.substring(0, 120) + "...";
            System.out.println((r.diagnostics().isEmpty() ? "OK   " : "FAIL ") + oneLine
                              + (r.diagnostics().isEmpty() ? "" :
                                 " || diag: " + r.diagnostics().get(0).message() + " found=" + r.diagnostics().get(0).found()
                                 + " @ off=" + r.diagnostics().get(0).offset()));
        }
    }

    @Test
    public void verifyTryWithResources() throws Exception {
        String grammar = Files.readString(Path.of("src/test/resources/java25.peg"));
        var parser = PegParser.fromGrammar(grammar).unwrap();

        String[] variants = {
                // plain try-catch
                "public class Foo { void m() { try { } catch (Exception e) {} } }",
                // try-finally
                "public class Foo { void m() { try { } finally {} } }",
                // try-with-resources single, var
                "public class Foo { void m() { try (var x = open()) { } } }",
                // try-with-resources single, explicit type
                "public class Foo { void m() { try (Reader r = open()) { } } }",
                // try-with-resources existing variable (Java 9+)
                "public class Foo { void m(Reader r) { try (r) { } } }",
                // try-with-resources multiple
                "public class Foo { void m() { try (var a = open(); var b = open()) { } } }",
                // try-with-resources WITH catch
                "public class Foo { void m() { try (var x = open()) { } catch (Exception e) {} } }",
        };
        for (var t : variants) {
            var r = parser.parse(t);
            System.out.println((r.diagnostics().isEmpty() ? "OK   " : "FAIL ") + t);
        }
    }

    @Test
    public void zoomOnTryWithResources() throws Exception {
        String grammar = Files.readString(Path.of("src/test/resources/java25.peg"));
        var parser = PegParser.fromGrammar(grammar).unwrap();

        // Try each shape of `try (X)`
        String[] cases = {
                "public class Foo { void m() { try () {} } }",            // empty (invalid Java but tests grammar)
                "public class Foo { void m() { try (r) {} } }",           // bare ident
                "public class Foo { void m() { try (a.b) {} } }",         // qualified
                "public class Foo { void m() { try (var x = e) {} } }",   // var decl with init
                "public class Foo { void m() { try (var x = e()) {} } }", // var decl with call
                "public class Foo { void m() { try (T x = e) {} } }",     // typed decl
                "public class Foo { void m() { try (var x = e; var y = e) {} } }", // multiple
        };
        for (var src : cases) {
            var r = parser.parse(src);
            var msg = "";
            if (!r.diagnostics().isEmpty()) {
                var d = r.diagnostics().get(0);
                msg = " || off=" + d.offset() + " msg=" + d.message() + " found=" + d.found() + " expected=" + d.expected();
            }
            System.out.println((r.diagnostics().isEmpty() ? "OK   " : "FAIL ") + src + msg);
        }
    }

    @Test
    public void verifyContextualKeywordAsIdentifier() throws Exception {
        String grammar = Files.readString(Path.of("src/test/resources/java25.peg"));
        var parser = PegParser.fromGrammar(grammar).unwrap();

        // Contextual keywords used as method/var names in regular code positions
        String[] cases = {
                "public class Foo { void m() { open(); } }",                   // call 'open'
                "public class Foo { void m() { module(); } }",                 // call 'module'
                "public class Foo { void m() { requires(); } }",
                "public class Foo { void m() { exports(); } }",
                "public class Foo { void m() { opens(); } }",
                "public class Foo { void m() { uses(); } }",
                "public class Foo { void m() { provides(); } }",
                "public class Foo { void m() { with(); } }",
                "public class Foo { void m() { to(); } }",
                "public class Foo { void m() { record(); } }",
                "public class Foo { void m() { yield(); } }",
                "public class Foo { void m() { sealed(); } }",
                "public class Foo { void m() { permits(); } }",
                "public class Foo { void m() { when(); } }",
                "public class Foo { void m() { var x = open(); } }",            // var = open()
                "public class Foo { void m() { var x = e(); } }",                // var = e()
                "public class Foo { void m() { Object o = file.openWriter(); } }", // qualified .openWriter()
                "public class Foo { void m() { Object o = file.open(); } }",       // qualified .open()
                "public class Foo { int open = 1; }",                              // field named 'open'
                "public class Foo { void m(int open) {} }",                        // param named 'open'
        };
        for (var src : cases) {
            var r = parser.parse(src);
            var msg = "";
            if (!r.diagnostics().isEmpty()) {
                var d = r.diagnostics().get(0);
                msg = " || off=" + d.offset() + " msg=" + d.message() + " found=" + d.found();
            }
            System.out.println((r.diagnostics().isEmpty() ? "OK   " : "FAIL ") + src + msg);
        }
    }

    @Test
    public void bisectByTruncation() throws Exception {
        // Find smallest TAIL truncation that makes the parser succeed.
        // I.e., progressively cut bytes off the end of the file until parse succeeds.
        // That tells us "everything up to byte N parses fine; byte N+1 is the trigger".
        // Bisect to find the maximum N where parse succeeds.
        String grammar = Files.readString(Path.of("src/test/resources/java25.peg"));
        String input = Files.readString(Path.of("src/test/resources/perf-corpus/large/FactoryClassGenerator.java.txt"));
        var parser = PegParser.fromGrammar(grammar).unwrap();

        // First, sanity: parse just "public class Foo {}" prepended with the imports/package from real file.
        int classStart = input.indexOf("public class FactoryClassGenerator");
        String header = input.substring(0, classStart) + "public class FactoryClassGenerator {\n";

        // For each line inside the class body, append it one at a time and check if it parses.
        String body = input.substring(classStart);
        // Find positions of each '\n' within body
        String[] bodyLines = body.split("\n", -1);
        System.out.println("Body lines: " + bodyLines.length);
        // Search for the first body-internal element that breaks parsing
        // Approach: try opening class { LINE_i ... } for incrementally larger i.
        int firstBad = -1;
        int lastOk = -1;
        for (int i = 1; i <= Math.min(bodyLines.length, 200); i++) {
            StringBuilder sb = new StringBuilder(header.length() + 8000);
            sb.append(header);
            // Strip 'public class FactoryClassGenerator {' from line 0
            // bodyLines[0] = "public class FactoryClassGenerator {"
            // Start from bodyLines[1].
            for (int j = 1; j < i && j < bodyLines.length; j++) {
                sb.append(bodyLines[j]).append('\n');
            }
            sb.append("}\n");
            var r = parser.parse(sb.toString());
            boolean ok = r.diagnostics().isEmpty();
            if (ok) lastOk = i; else { firstBad = i; break; }
        }
        System.out.println("lastOk i=" + lastOk + ": " + (lastOk > 0 && lastOk < bodyLines.length ? bodyLines[lastOk - 1] : "<eof>"));
        System.out.println("firstBad i=" + firstBad + ": " + (firstBad > 0 && firstBad < bodyLines.length ? bodyLines[firstBad - 1] : "<eof>"));
        // Show 3 lines of context around the offending line
        if (firstBad > 0) {
            int from = Math.max(1, firstBad - 2);
            int to = Math.min(bodyLines.length, firstBad + 3);
            for (int k = from; k < to; k++) {
                String marker = (k == firstBad - 1) ? "  --> " : "      ";
                System.out.println(marker + k + ": " + bodyLines[k]);
            }
        }
    }

    @Test
    public void bisectMinimalFailingClass() throws Exception {
        String grammar = Files.readString(Path.of("src/test/resources/java25.peg"));
        var parser = PegParser.fromGrammar(grammar).unwrap();

        // Test case 1: just a tiny class
        String t1 = "public class Foo {}";
        // Test case 2: class with one field
        String t2 = "public class Foo { private final int x; }";
        // Test case 3: class with field types from the real file
        String t3 = "public class Foo { private final ProcessingEnvironment processingEnv; }";
        // Test case 4: package + imports + class
        String t4 = "package a.b;\nimport java.util.List;\npublic class Foo {}\n";
        // Test case 5: prefix lines 1-53 of fixture but only including 'public class FactoryClassGenerator {}'
        String t5 = """
            package org.pragmatica.jbct.slice.generator;
            import org.pragmatica.lang.Result;
            /// Generates factory class.
            public class FactoryClassGenerator {}
            """;
        // Test case 6: same as t5 but without the /// comment
        String t6 = """
            package org.pragmatica.jbct.slice.generator;
            import org.pragmatica.lang.Result;
            public class FactoryClassGenerator {}
            """;

        for (var tc : new String[][]{
                {"t1 small empty class", t1},
                {"t2 one field", t2},
                {"t3 field with imported type", t3},
                {"t4 pkg+imports+class", t4},
                {"t5 pkg+import+/// comment+class", t5},
                {"t6 pkg+import+class (no ///)", t6},
        }) {
            var r = parser.parse(tc[1]);
            System.out.println("[" + tc[0] + "] diagnostics=" + r.diagnostics().size()
                              + (r.diagnostics().isEmpty() ? " OK"
                                                            : "  first: offset=" + r.diagnostics().get(0).offset()
                                                              + " msg=" + r.diagnostics().get(0).message()
                                                              + " found=" + r.diagnostics().get(0).found()));
        }
    }
}
