import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import org.pragmatica.peg.PegParser;

/**
 * The error-recovery half of the {@code %memo} differential.
 *
 * <p>{@link MemoDiff} runs over a corpus of valid source files, which by
 * construction never exercises the case that actually worries: a memoised
 * subtree containing an {@code Error} node produced by panic-mode recovery.
 * Replay re-splices those nodes but does not re-record the diagnostic, so a
 * divergence would surface as a differing diagnostic count or text.
 *
 * <p>Every input below is malformed <em>inside an argument list</em> — the
 * memoised rule — and sits in the JLS 14.8 double-parse shape that makes the
 * memo fire at all. Each is compared against the same grammar with every
 * {@code %memo} line stripped.
 *
 * <p>Usage: {@code java MemoErrorDiff <module-root>}, reading the grammar from
 * {@code <module-root>/src/test/resources/java25.peg}. Exits 0 when all cases
 * agree, 1 on any divergence.
 */
public final class MemoErrorDiff {
    private static final List<String> CASES = List.of(
        "class A { void m() { foo(a, ); } }",
        "class A { void m() { foo(a b); } }",
        "class A { void m() { foo(a,) = 1; } }",
        "class A { void m() { foo(a, bar(, c)); } }",
        "class A { void m() { foo(a,,b); baz(c); } }",
        "class A { void m() { foo(bar(a,), c) = 1; qux(d); } }",
        "class A { void m() { foo(a) = ; bar(b); } }",
        "class A { void m() { foo(]); bar(b); } }",
        "class A { void m() { foo(a, b; } }",
        "class A { void m() { foo(new int[); bar(c); } }"
    );

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("usage: java MemoErrorDiff <module-root>");
            System.exit(2);
        }

        var memoGrammar = Files.readString(Path.of(args[0], "src/test/resources/java25.peg"));

        if (!memoGrammar.contains("%memo")) {
            System.out.println("grammar declares no %memo — nothing to differentiate");
            System.exit(2);
        }

        var plainGrammar = memoGrammar.lines()
                                      .filter(line -> !line.strip().startsWith("%memo"))
                                      .collect(Collectors.joining("\n"));
        var memoParser = PegParser.fromGrammar(memoGrammar).unwrap();
        var plainParser = PegParser.fromGrammar(plainGrammar).unwrap();
        var divergent = 0;

        for (var src : CASES) {
            var memo = memoParser.parse(src);
            var plain = plainParser.parse(src);
            var problems = new ArrayList<String>();

            if (memo.diagnostics().size() != plain.diagnostics().size()) {
                problems.add("diag count " + memo.diagnostics().size() + " vs " + plain.diagnostics().size());
            }
            if (memo.isSuccess() != plain.isSuccess()) {
                problems.add("success " + memo.isSuccess() + " vs " + plain.isSuccess());
            }
            if (!memo.cst().reconstruct().equals(src)) {
                problems.add("memo reconstruct != input");
            }
            if (memo.cst().nodeCount() != plain.cst().nodeCount()) {
                problems.add("nodeCount " + memo.cst().nodeCount() + " vs " + plain.cst().nodeCount());
            }
            if (!MemoDiff.signature(memo.cst()).equals(MemoDiff.signature(plain.cst()))) {
                problems.add("CST signature differs");
            }
            // Diagnostic TEXT, not just count: replaying an Error-bearing subtree
            // could plausibly preserve the count while shifting a span.
            if (!render(memo).equals(render(plain))) {
                problems.add("diagnostic text differs");
            }

            if (problems.isEmpty()) {
                System.out.println("ok   [" + memo.diagnostics().size() + " diag] " + src);
            } else {
                divergent++;
                System.out.println("BAD  " + src);
                System.out.println("     " + String.join("; ", problems));
            }
        }

        System.out.println("---");
        System.out.println("cases: " + CASES.size() + "  divergent: " + divergent);
        System.out.println(divergent == 0 ? "RESULT: IDENTICAL ON ERROR PATHS" : "RESULT: DIVERGENCE ON ERROR PATHS");
        System.exit(divergent == 0 ? 0 : 1);
    }

    private static String render(org.pragmatica.peg.cst.ParseResult result) {
        return result.diagnostics().stream().map(Object::toString).collect(Collectors.joining("|"));
    }
}
