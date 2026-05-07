package org.pragmatica.peg.incremental.experimental;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.peg.tree.SourceSpan;
import org.pragmatica.peg.tree.Trivia;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Path D — tests for {@link StableIdSplicer}. Inherits identity-preservation
 * tests from the {@link IdTreeSplicer} pattern, plus tests asserting the
 * stable-id invariant: every ancestor on the new path carries the same id as
 * its old-path counterpart, despite being a structurally distinct record.
 */
final class StableIdSplicerTest {
    private static final SourceSpan SPAN = new SourceSpan(1, 1, 0, 1, 1, 0);
    private static final List<Trivia> NO_TRIVIA = List.of();

    private static IdCstNode.Terminal terminal(IdGenerator gen, String text) {
        return new IdCstNode.Terminal(gen.next(), SPAN, "T", text, NO_TRIVIA, NO_TRIVIA);
    }

    private static IdCstNode.NonTerminal nonTerminal(IdGenerator gen, String rule, List<IdCstNode> children) {
        return new IdCstNode.NonTerminal(gen.next(), SPAN, rule, children, NO_TRIVIA, NO_TRIVIA);
    }

    @Nested
    @DisplayName("splice")
    class SpliceTests {

        @Test
        @DisplayName("Pivot is root: returns newPivot directly, newPath = [newPivot]")
        void pivot_as_root() {
            var gen = new IdGenerator.PerSessionCounter();
            IdCstNode oldRoot = terminal(gen, "old");
            IdCstNode newPivot = terminal(gen, "new");

            var splicer = new StableIdSplicer(gen);
            var result = splicer.splice(List.of(oldRoot), newPivot);

            assertThat(result.newRoot()).isSameAs(newPivot);
            assertThat(result.newPath()).hasSize(1);
            assertThat(result.newPath().get(0)).isSameAs(newPivot);
        }

        @Test
        @DisplayName("Single-level splice: ancestor reuses old id; siblings reference-shared")
        void single_level_stable_id() {
            var gen = new IdGenerator.PerSessionCounter();
            var a = terminal(gen, "A");
            var b = terminal(gen, "B");
            var c = terminal(gen, "C");
            IdCstNode root = nonTerminal(gen, "Root", List.of(a, b, c));
            IdCstNode bPrime = terminal(gen, "B'");

            var splicer = new StableIdSplicer(gen);
            var result = splicer.splice(List.of(root, b), bPrime);

            // Stable id: new root's id matches old root's id.
            assertThat(result.newRoot().id())
                .as("ancestor id reused on splice path")
                .isEqualTo(root.id());
            // But the records are structurally distinct (different children list).
            assertThat(result.newRoot()).isNotSameAs(root);

            var newRootNT = (IdCstNode.NonTerminal) result.newRoot();
            assertThat(newRootNT.rule()).isEqualTo("Root");
            // Siblings are reference-shared (identity invariant).
            assertThat(newRootNT.children().get(0)).isSameAs(a);
            assertThat(newRootNT.children().get(1)).isSameAs(bPrime);
            assertThat(newRootNT.children().get(2)).isSameAs(c);
            // newPath = [newRoot, newPivot].
            assertThat(result.newPath()).hasSize(2);
            assertThat(result.newPath().get(0)).isSameAs(result.newRoot());
            assertThat(result.newPath().get(1)).isSameAs(bPrime);
        }

        @Test
        @DisplayName("Deep splice: every ancestor on path reuses its old id; pivot keeps caller-supplied id")
        void deep_splice_stable_ids() {
            // 4-level tree: root → mid → inner → pivot, with siblings at each level.
            var gen = new IdGenerator.PerSessionCounter();
            var lvl1Sib = terminal(gen, "lvl1Sib");
            var midSib = terminal(gen, "midSib");
            var innerSib = terminal(gen, "innerSib");
            var pivot = terminal(gen, "PIVOT");
            IdCstNode inner = nonTerminal(gen, "Inner", List.of(innerSib, pivot));
            IdCstNode mid = nonTerminal(gen, "Mid", List.of(midSib, inner));
            IdCstNode root = nonTerminal(gen, "Root", List.of(lvl1Sib, mid));

            IdCstNode newPivot = terminal(gen, "NEW_PIVOT");
            var oldPath = List.of(root, mid, inner, (IdCstNode) pivot);

            var splicer = new StableIdSplicer(gen);
            var result = splicer.splice(oldPath, newPivot);

            assertThat(result.newPath()).hasSize(4);
            // Ancestors at indices [0, oldPath.size() - 2) carry stable ids.
            for (int i = 0; i < oldPath.size() - 1; i++) {
                assertThat(result.newPath().get(i).id())
                    .as("ancestor at depth %d must reuse old id", i)
                    .isEqualTo(oldPath.get(i).id());
                // ...but the records are structurally distinct.
                assertThat(result.newPath().get(i))
                    .as("ancestor at depth %d is a fresh record", i)
                    .isNotSameAs(oldPath.get(i));
            }
            // The pivot slot carries the caller-supplied newPivot, with whatever
            // id the caller gave it (typically fresh).
            assertThat(result.newPath().get(3)).isSameAs(newPivot);
            assertThat(result.newPath().get(3).id())
                .as("pivot id is whatever the caller built it with")
                .isEqualTo(newPivot.id());
            // Sibling identity preservation (inherited invariant).
            var newRootNT = (IdCstNode.NonTerminal) result.newRoot();
            assertThat(newRootNT.children().get(0)).isSameAs(lvl1Sib);
            var newMidNT = (IdCstNode.NonTerminal) result.newPath().get(1);
            assertThat(newMidNT.children().get(0)).isSameAs(midSib);
            var newInnerNT = (IdCstNode.NonTerminal) result.newPath().get(2);
            assertThat(newInnerNT.children().get(0)).isSameAs(innerSib);
            assertThat(newInnerNT.children().get(1)).isSameAs(newPivot);
        }

        @Test
        @DisplayName("Structural inequality despite ID equality: new ancestor records are not == old, but ids match")
        void structural_inequality_with_id_equality() {
            // Verify the central Path D claim spelled out: same id, different
            // record. This is the exact invariant that lets StableIdNodeIndex
            // skip the ancestor-removal and sibling-rewire steps.
            var gen = new IdGenerator.PerSessionCounter();
            var a = terminal(gen, "A");
            var b = terminal(gen, "B");
            IdCstNode root = nonTerminal(gen, "Root", List.of(a, b));
            IdCstNode bPrime = terminal(gen, "B'");

            var splicer = new StableIdSplicer(gen);
            var result = splicer.splice(List.of(root, b), bPrime);

            assertThat(result.newPath().get(0))
                .as("structural inequality: different record")
                .isNotSameAs(root);
            assertThat(result.newPath().get(0).id())
                .as("ID equality: same logical node")
                .isEqualTo(root.id());
            // The children lists differ — that's the structural delta.
            var newRootNT = (IdCstNode.NonTerminal) result.newPath().get(0);
            var oldRootNT = (IdCstNode.NonTerminal) root;
            assertThat(newRootNT.children()).isNotEqualTo(oldRootNT.children());
        }

        @Test
        @DisplayName("Generator is not consumed for ancestor rebuild — unlike IdTreeSplicer")
        void generator_unused_for_ancestor_rebuild() {
            // IdTreeSplicer would have called gen.next() for the new root;
            // StableIdSplicer reuses the old root's id. The generator's state
            // must not advance during the splice itself.
            var gen = new IdGenerator.PerSessionCounter();
            var a = terminal(gen, "A");
            var b = terminal(gen, "B");
            IdCstNode root = nonTerminal(gen, "Root", List.of(a, b));
            // Caller pre-allocates the newPivot's id (consumes gen).
            IdCstNode bPrime = terminal(gen, "B'");

            long preSpliceCounter = gen.next();
            // Reset the gen to the same state by capturing pre-splice value
            // and asserting post-splice gen returns the very next value.
            // (IdGenerator has no peek(); we just verify monotonic +1.)
            var splicer = new StableIdSplicer(gen);
            var result = splicer.splice(List.of(root, b), bPrime);

            long postSpliceCounter = gen.next();
            assertThat(postSpliceCounter)
                .as("StableIdSplicer must not consume the generator during ancestor rebuild")
                .isEqualTo(preSpliceCounter + 1L);
            assertThat(result.newRoot().id()).isEqualTo(root.id());
        }
    }
}
