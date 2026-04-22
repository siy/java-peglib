package org.pragmatica.peg.incremental.internal;

import org.pragmatica.peg.tree.CstNode;

import java.util.List;

/**
 * Structural hash of a {@link CstNode} tree. The hash folds in rule name, node
 * kind, span (start-offset, end-offset), and recursively children / text —
 * enough to make hash equality imply structural equality for practical
 * purposes (modulo pathological hash collisions, which tests still tolerate
 * by using full structural equality as a tie-break).
 *
 * <p>Used as the primary correctness oracle for the parity fuzz harness:
 * after every incremental edit the session's CST hash must match the
 * full-reparse hash on the post-edit buffer
 * (SPEC §6.1 parity invariant).
 *
 * <p>Trivia is intentionally excluded from the hash. peglib-core's trivia
 * attribution is not bit-stable under all incremental reshapes (see the
 * pre-existing {@code RoundTripTest} skip and the v2 "trivia-aware reparse"
 * roadmap item); v1 guarantees structural parity of the rule-node spine,
 * not trivia-character assignment. This matches SPEC §4.4 invariants:
 * {@code CstHash} is defined over span+rule+children+text only.
 *
 * @since 0.3.1
 */
public final class CstHash {
    private CstHash() {}

    public static long of(CstNode node) {
        if (node == null) {
            return 0L;
        }
        long h = 1125899906842597L; // large prime seed
        h = 31 * h + node.getClass().getName().hashCode();
        h = 31 * h + safeHash(node.rule());
        h = 31 * h + node.span().start().offset();
        h = 31 * h + node.span().end().offset();
        switch (node) {
            case CstNode.Terminal t -> h = 31 * h + safeHash(t.text());
            case CstNode.Token t -> h = 31 * h + safeHash(t.text());
            case CstNode.Error e -> {
                h = 31 * h + safeHash(e.skippedText());
                h = 31 * h + safeHash(e.expected());
            }
            case CstNode.NonTerminal nt -> h = 31 * h + hashChildren(nt.children());
        }
        return h;
    }

    private static long hashChildren(List<CstNode> children) {
        long h = 17;
        for (var child : children) {
            h = 31 * h + of(child);
        }
        return h;
    }

    private static int safeHash(String s) {
        return s == null ? 0 : s.hashCode();
    }
}
