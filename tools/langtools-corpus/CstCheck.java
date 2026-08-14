import org.pragmatica.peg.PegParser;
import org.pragmatica.peg.cst.CstArray;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * CST structural invariant checker.
 *
 * <p>Walks the tree depth-first collecting LEAF nodes (no children) and checks the one
 * invariant that must hold for any correct parse: the leaves, read left to right, cover each
 * non-trivia token exactly once and in order. A leaked alternative shows up here as a token
 * index appearing twice — which is what makes the formatter emit 'publicpublic'.
 *
 * Usage: java CstCheck &lt;grammar.peg&gt; &lt;file.java&gt;
 */
public final class CstCheck {

    public static void main(String[] args) throws Exception {
        var parser = PegParser.fromGrammar(Files.readString(Path.of(args[0]))).unwrap();
        var src = Files.readString(Path.of(args[1]));
        var r = parser.parse(src);

        if (!r.diagnostics().isEmpty()) {
            System.out.println("diagnostics=" + r.diagnostics().size() + " (checking anyway)");
        }

        var cst = r.cst();
        var leafTokens = new ArrayList<Integer>();
        collectLeaves(cst, cst.rootIndex(), leafTokens);

        // Which token indices are covered, and how often
        var counts = new int[cst.tokens().count() + 1];
        for (var t : leafTokens) {
            if (t >= 0 && t < counts.length) {
                counts[t]++;
            }
        }

        var dupes = new ArrayList<String>();
        var missing = new ArrayList<String>();

        for (var t = 0; t < cst.tokens().count(); t++) {
            if (cst.tokens().isTrivia(t)) {
                continue;
            }
            var text = src.substring(cst.tokens().startAt(t), cst.tokens().endAt(t));
            if (counts[t] > 1) {
                dupes.add("tok#" + t + " '" + text + "' x" + counts[t]);
            } else if (counts[t] == 0) {
                missing.add("tok#" + t + " '" + text + "'");
            }
        }

        // Out-of-order check: leaf token indices should be non-decreasing
        var outOfOrder = 0;
        for (var i = 1; i < leafTokens.size(); i++) {
            if (leafTokens.get(i) < leafTokens.get(i - 1)) {
                outOfOrder++;
            }
        }

        System.out.println("nodes=" + cst.nodeCount() + " leaves=" + leafTokens.size()
                           + " tokens=" + cst.tokens().count()
                           + " duplicatedTokens=" + dupes.size()
                           + " uncoveredTokens=" + missing.size()
                           + " outOfOrderLeaves=" + outOfOrder);

        dupes.stream().limit(10).forEach(d -> System.out.println("  DUP  " + d));
        missing.stream().limit(10).forEach(m -> System.out.println("  MISS " + m));
    }

    private static void collectLeaves(CstArray cst, int node, List<Integer> out) {
        var child = cst.firstChildAt(node);

        if (child == CstArray.NO_NODE) {
            for (var t = cst.firstTokenAt(node); t <= cst.lastTokenAt(node); t++) {
                if (!cst.tokens().isTrivia(t)) {
                    out.add(t);
                }
            }
            return;
        }

        while (child != CstArray.NO_NODE) {
            collectLeaves(cst, child, out);
            child = cst.nextSiblingAt(child);
        }
    }
}
