import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.stream.Collectors;

import org.pragmatica.peg.PegParser;
import org.pragmatica.peg.cst.CstArray;

/**
 * Differences a grammar against itself with every {@code %memo} directive stripped,
 * over a corpus of source files.
 *
 * <p>{@code %memo} is an optimisation: replaying a salvaged subtree must be
 * observationally identical to re-parsing it. So the only useful check is a
 * differential — same grammar, same input, directive on vs off — comparing
 * diagnostics, {@code reconstruct()}, node count and the full preorder
 * (kind, firstToken, lastToken) signature.
 *
 * <p>Usage: {@code java MemoDiff <module-root> [more-roots...]}
 * where each root is scanned for {@code .java} / {@code .java.txt} files under
 * {@code src/test/resources}. The grammar is read from
 * {@code <first-root>/src/test/resources/java25.peg}.
 *
 * <p>Exits 0 when every file agrees, 1 on any divergence, 2 if the grammar
 * declares no {@code %memo} (nothing to test).
 *
 * @see MemoErrorDiff for the error-recovery half, which this cannot reach —
 * a corpus of valid files never exercises replay of an Error-bearing subtree.
 */
public final class MemoDiff {
    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("usage: java MemoDiff <module-root> [more-roots...]");
            System.exit(2);
        }

        var root = Path.of(args[0]);
        var memoGrammar = Files.readString(root.resolve("src/test/resources/java25.peg"));

        if (!memoGrammar.contains("%memo")) {
            System.out.println("grammar declares no %memo — nothing to differentiate");
            System.exit(2);
        }

        // Strip every %memo line rather than one hardcoded rule, so this stays
        // correct if the memo set changes.
        var plainGrammar = memoGrammar.lines()
                                      .filter(line -> !line.strip().startsWith("%memo"))
                                      .collect(Collectors.joining("\n"));
        var memoParser = PegParser.fromGrammar(memoGrammar).unwrap();
        var plainParser = PegParser.fromGrammar(plainGrammar).unwrap();
        var files = new LinkedHashSet<Path>();

        for (var arg : args) {
            var base = Path.of(arg).resolve("src/test/resources");

            if (Files.isDirectory(base)) {
                try (var walk = Files.walk(base)) {
                    walk.filter(Files::isRegularFile)
                        .filter(p -> p.toString().endsWith(".java") || p.toString().endsWith(".java.txt"))
                        .forEach(files::add);
                }
            }
        }

        var checked = 0;
        var mismatched = 0;
        var totalLoc = 0L;

        for (var file : files) {
            var input = Files.readString(file);
            var memo = memoParser.parse(input);
            var plain = plainParser.parse(input);
            var problems = new ArrayList<String>();

            if (memo.diagnostics().size() != plain.diagnostics().size()) {
                problems.add("diagnostics " + memo.diagnostics().size() + " vs " + plain.diagnostics().size());
            }
            if (memo.isSuccess() != plain.isSuccess()) {
                problems.add("success " + memo.isSuccess() + " vs " + plain.isSuccess());
            }
            if (!memo.cst().reconstruct().equals(plain.cst().reconstruct())) {
                problems.add("reconstruct differs");
            }
            if (!memo.cst().reconstruct().equals(input)) {
                problems.add("memo reconstruct != input");
            }
            if (memo.cst().nodeCount() != plain.cst().nodeCount()) {
                problems.add("nodeCount " + memo.cst().nodeCount() + " vs " + plain.cst().nodeCount());
            }
            if (!signature(memo.cst()).equals(signature(plain.cst()))) {
                problems.add("CST signature differs");
            }

            checked++;
            totalLoc += input.lines().count();

            if (!problems.isEmpty()) {
                mismatched++;
                System.out.println("MISMATCH " + file + " :: " + String.join("; ", problems));
            }
        }

        System.out.println("---");
        System.out.println("files checked : " + checked);
        System.out.println("total LOC     : " + totalLoc);
        System.out.println("mismatches    : " + mismatched);
        System.out.println(mismatched == 0 ? "RESULT: IDENTICAL" : "RESULT: DIVERGENCE");
        System.exit(mismatched == 0 ? 0 : 1);
    }

    /**
     * Iterative preorder walk. Deliberately not recursive: real Java files nest
     * deeply enough to blow the stack on a recursive signature.
     */
    static String signature(CstArray cst) {
        var sb = new StringBuilder();
        var stack = new ArrayDeque<Integer>();

        stack.push(cst.rootIndex());

        while (!stack.isEmpty()) {
            var idx = stack.pop();

            sb.append('(')
              .append(cst.kindAt(idx))
              .append(':')
              .append(cst.firstTokenAt(idx))
              .append('-')
              .append(cst.lastTokenAt(idx))
              .append(')');

            List<Integer> kids = cst.children(idx).boxed().collect(Collectors.toList());

            for (var i = kids.size() - 1; i >= 0; i--) {
                stack.push(kids.get(i));
            }
        }

        return sb.toString();
    }
}
