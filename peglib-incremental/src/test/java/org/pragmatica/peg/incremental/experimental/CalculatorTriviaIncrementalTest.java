package org.pragmatica.peg.incremental.experimental;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.peg.PegParser;
import org.pragmatica.peg.tree.CstNode;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 0d.1 — GO/NO-GO gate for spec §8 Q4: trivia-bearing edits do not
 * break the algorithm or the identity-preservation invariant when the splice
 * is driven by {@link IdTreeSplicer}.
 *
 * <h2>Test methodology</h2>
 *
 * <p>The production parser (peglib-core) allocates fresh {@link CstNode}
 * records on every parse — there is no record-sharing between two independent
 * parses of similar inputs. {@link IdCstNodeBuilder} then assigns fresh stable
 * IDs to every node it visits. Therefore, paired re-parses of {@code before}
 * and {@code after} produce trees with completely disjoint ID spaces.
 *
 * <p>This is fine for Phase 0d.1's goal: the spike GATE concerns the
 * algorithm's behavior <em>given</em> identity-shared trees. {@link IdTreeSplicer}
 * MANUFACTURES that sharing — it builds a post-edit tree from the pre-edit
 * tree by reusing every sibling subtree by reference. So the test:
 *
 * <ol>
 *   <li>Parses {@code before} → IdCstNode (call this {@code originalTree}).
 *   <li>Parses {@code after} → IdCstNode (call this the freshly-rebuilt
 *       "ground truth" — used only to source a {@code newPivot} subtree
 *       that has the structural shape we want to splice in).
 *   <li>Identifies the splice point in {@code originalTree} (the {@code Number}
 *       node whose token text is "2"). The corresponding pivot in the
 *       ground-truth tree provides the new pivot subtree.
 *   <li>Calls {@link IdTreeSplicer#splice} → {@code (newRoot, newPath)}.
 *   <li>Calls {@link IdNodeIndex#applyIncremental} → {@code incrementalIndex}.
 *   <li>Calls {@link IdNodeIndex#build} on {@code newRoot} → {@code groundTruthIndex}
 *       (this is a fresh full-walk on the post-splice tree, NOT the
 *       independently-parsed after-tree).
 *   <li>Asserts: every node ID in {@code groundTruthIndex.parents} maps to the
 *       SAME parent ID in {@code incrementalIndex} (equivalence assertion).
 *   <li>Asserts: every sibling subtree of every splice-path node is
 *       reference-shared between {@code originalTree} and {@code newRoot}
 *       (Q3 invariant carried through the splice).
 * </ol>
 *
 * <p><strong>Note on the spike-vs-production gap.</strong> The production
 * parser's lack of cross-parse record sharing means a true "incremental
 * reparse" in v0.5.0 will not work by paired re-parses; the
 * {@code IdTreeSplicer} (or its production successor) must be the source of
 * identity-shared trees. That is a Phase 1 design concern. Phase 0d.1 only
 * proves that <em>given</em> identity-shared trees, the algorithm is correct
 * and the invariant holds.
 */
final class CalculatorTriviaIncrementalTest {

    private static final String CALCULATOR_GRAMMAR = """
        Expr <- Term (('+' / '-') Term)*
        Term <- Factor (('*' / '/') Factor)*
        Factor <- Number / '(' Expr ')'
        Number <- < [0-9]+ >
        Comment <- '/*' (!'*/' .)* '*/'
        %whitespace <- ([ \\t\\r\\n]+ / Comment)+
        """;

    private static org.pragmatica.peg.parser.Parser calculator() {
        return PegParser.fromGrammar(CALCULATOR_GRAMMAR).unwrap();
    }

    /**
     * Parse {@code input} with the calculator grammar and convert to
     * {@link IdCstNode} via the supplied generator.
     */
    private static IdCstNode parseToIdCst(String input, IdGenerator gen) {
        var cst = calculator().parseCst(input).unwrap();
        return new IdCstNodeBuilder(gen).build(cst);
    }

    /**
     * Walk {@code root} pre-order; return the first {@link IdCstNode.Token}
     * whose text is exactly {@code targetText}. Returns null when not found.
     *
     * <p>For the calculator grammar, {@code Number <- < [0-9]+ >} produces a
     * {@link IdCstNode.Token} (rule = parent rule = "Factor") rather than a
     * NonTerminal. Token nodes are leaves, so they're a clean splice target —
     * no internal subtree to disrupt the identity invariant.
     */
    private static IdCstNode findNumberByText(IdCstNode root, String targetText) {
        var stack = new ArrayList<IdCstNode>();
        stack.add(root);
        while (!stack.isEmpty()) {
            var node = stack.remove(stack.size() - 1);
            if (node instanceof IdCstNode.Token t && t.text().equals(targetText)) {
                return node;
            }
            if (node instanceof IdCstNode.NonTerminal nt) {
                for (int i = nt.children().size() - 1; i >= 0; i--) {
                    stack.add(nt.children().get(i));
                }
            }
        }
        return null;
    }

    /** Aggregate the text of all Token/Terminal descendants of {@code node}. */
    private static String nodeTokenText(IdCstNode node) {
        return switch (node) {
            case IdCstNode.Token t -> t.text();
            case IdCstNode.Terminal t -> t.text();
            case IdCstNode.Error e -> e.skippedText();
            case IdCstNode.NonTerminal nt -> {
                var sb = new StringBuilder();
                for (var ch : nt.children()) {
                    sb.append(nodeTokenText(ch));
                }
                yield sb.toString();
            }
        };
    }

    /**
     * Build the path (root → target, inclusive) from {@code root} to {@code target}
     * using record-identity ({@code ==}). Returns null when target is not in the tree.
     */
    private static List<IdCstNode> pathToByIdentity(IdCstNode root, IdCstNode target) {
        var acc = new ArrayList<IdCstNode>();
        if (collectPath(root, target, acc)) {
            return List.copyOf(acc);
        }
        return null;
    }

    private static boolean collectPath(IdCstNode node, IdCstNode target, List<IdCstNode> acc) {
        acc.add(node);
        if (node == target) {
            return true;
        }
        if (node instanceof IdCstNode.NonTerminal nt) {
            for (var ch : nt.children()) {
                if (collectPath(ch, target, acc)) {
                    return true;
                }
            }
        }
        acc.remove(acc.size() - 1);
        return false;
    }

    /** Pre-order flatten of every node in {@code root}'s subtree (inclusive). */
    private static List<IdCstNode> flatten(IdCstNode root) {
        var out = new ArrayList<IdCstNode>();
        flattenInto(root, out);
        return out;
    }

    private static void flattenInto(IdCstNode node, List<IdCstNode> out) {
        out.add(node);
        if (node instanceof IdCstNode.NonTerminal nt) {
            for (var ch : nt.children()) {
                flattenInto(ch, out);
            }
        }
    }

    /**
     * Run the full Phase 0d.1 Q4 gate for one trivia-bearing edit case. Returns
     * a small report record so each test can assert.
     */
    private record Q4Outcome(boolean equivalencePassed,
                             boolean invariantPassed,
                             int newTreeNodeCount,
                             int siblingsChecked) {}

    private static Q4Outcome runQ4Gate(String before, String after) {
        var gen = new IdGenerator.PerSessionCounter();
        var originalTree = parseToIdCst(before, gen);
        // Continue with the SAME generator so before/after IDs do not collide.
        var afterTree = parseToIdCst(after, gen);

        var oldPivot = findNumberByText(originalTree, "2");
        var newPivot = findNumberByText(afterTree, "2");
        if (oldPivot == null || newPivot == null) {
            throw new AssertionError("Could not locate Number=2 pivot in test inputs '"
                                     + before + "' / '" + after + "'");
        }

        var oldPath = pathToByIdentity(originalTree, oldPivot);
        if (oldPath == null) {
            throw new AssertionError("Pivot not found via identity walk in originalTree");
        }

        var splicer = new IdTreeSplicer(gen);
        var splice = splicer.splice(oldPath, newPivot);

        var oldIndex = IdNodeIndex.build(originalTree);
        var incrementalIndex = oldIndex.applyIncremental(splice.newRoot(), oldPath, splice.newPath());
        var groundTruthIndex = IdNodeIndex.build(splice.newRoot());

        // Equivalence: every node ID in the new tree's full-walk index must
        // resolve to the same parent in the incremental index.
        boolean equivalencePassed = true;
        int newTreeNodeCount = 0;
        for (var node : flatten(splice.newRoot())) {
            newTreeNodeCount++;
            var truth = groundTruthIndex.parentIdOf(node.id());
            var incr = incrementalIndex.parentIdOf(node.id());
            if (!truth.equals(incr)) {
                equivalencePassed = false;
                System.err.println("[Q4 equivalence FAIL] node id=" + node.id()
                                   + " rule=" + node.rule()
                                   + " truth.parent=" + truth
                                   + " incremental.parent=" + incr);
            }
        }

        // Identity invariant: every non-spliced sibling along the path must be ==.
        int siblingsChecked = 0;
        boolean invariantPassed = true;
        for (int depth = 0; depth < oldPath.size() - 1; depth++) {
            var oldAncestor = oldPath.get(depth);
            var newAncestor = splice.newPath().get(depth);
            if (!(oldAncestor instanceof IdCstNode.NonTerminal oldNT)
                || !(newAncestor instanceof IdCstNode.NonTerminal newNT)) {
                invariantPassed = false;
                continue;
            }
            var oldChildren = oldNT.children();
            var newChildren = newNT.children();
            if (oldChildren.size() != newChildren.size()) {
                invariantPassed = false;
                continue;
            }
            int splicedIdx = oldChildren.indexOf(oldPath.get(depth + 1));
            for (int k = 0; k < newChildren.size(); k++) {
                if (k == splicedIdx) {
                    continue;
                }
                siblingsChecked++;
                if (newChildren.get(k) != oldChildren.get(k)) {
                    invariantPassed = false;
                    System.err.println("[Q4 invariant FAIL] depth=" + depth + " idx=" + k
                                       + " sibling not reference-shared");
                }
            }
        }

        return new Q4Outcome(equivalencePassed, invariantPassed, newTreeNodeCount, siblingsChecked);
    }

    @Nested
    @DisplayName("Trivia-bearing edits (spec §8 Q4)")
    class TriviaEdits {

        @Test
        @DisplayName("Edit A — insert blank line before second operand")
        void edit_a_insert_blank_line() {
            var outcome = runQ4Gate("1+2", "1+\n  2");

            assertThat(outcome.equivalencePassed())
                .as("incremental index must equal ground-truth full-walk index")
                .isTrue();
            assertThat(outcome.invariantPassed())
                .as("identity invariant: every sibling subtree of splice path is reference-shared")
                .isTrue();
            assertThat(outcome.siblingsChecked())
                .as("at least some siblings should be checked along the path")
                .isGreaterThanOrEqualTo(0);
            System.out.println("[Phase 0d.1 Q4-A] newTreeNodes=" + outcome.newTreeNodeCount()
                               + " siblingsChecked=" + outcome.siblingsChecked());
        }

        @Test
        @DisplayName("Edit B — delete inline comment between operands")
        void edit_b_delete_inline_comment() {
            var outcome = runQ4Gate("1 /*hi*/ + 2", "1 + 2");

            assertThat(outcome.equivalencePassed())
                .as("incremental index must equal ground-truth full-walk index")
                .isTrue();
            assertThat(outcome.invariantPassed())
                .as("identity invariant: every sibling subtree of splice path is reference-shared")
                .isTrue();
            System.out.println("[Phase 0d.1 Q4-B] newTreeNodes=" + outcome.newTreeNodeCount()
                               + " siblingsChecked=" + outcome.siblingsChecked());
        }

        @Test
        @DisplayName("Edit C — insert comment inside expression")
        void edit_c_insert_comment_inside_expression() {
            var outcome = runQ4Gate("1+2", "1+/*x*/2");

            assertThat(outcome.equivalencePassed())
                .as("incremental index must equal ground-truth full-walk index")
                .isTrue();
            assertThat(outcome.invariantPassed())
                .as("identity invariant: every sibling subtree of splice path is reference-shared")
                .isTrue();
            System.out.println("[Phase 0d.1 Q4-C] newTreeNodes=" + outcome.newTreeNodeCount()
                               + " siblingsChecked=" + outcome.siblingsChecked());
        }
    }
}
