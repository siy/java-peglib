package org.pragmatica.peg.v6.cst;

import org.pragmatica.peg.v6.token.TokenArrayBuilder;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.pragmatica.peg.v6.token.TokenArray.FIRST_USER_KIND;

/**
 * Phase D.1.1 — coverage for {@link CstArray#spliceSubtree}.
 *
 * <p>Builds small synthetic CSTs mirroring the layouts exercised by
 * {@link FindCheckpointAncestorTest} and verifies that the spliced CST is
 * structurally consistent: same root kind and span layout as the analogous
 * tree built from scratch, with token references correctly remapped around
 * the splice point.
 */
class SpliceSubtreeTest {
    private static final int KIND_FILE = 0;
    private static final int KIND_METHOD = 1;
    private static final int KIND_BLOCK = 2;
    private static final int KIND_STMT = 3;
    private static final int KIND_REPLACED = 4;

    private static final String[] RULE_TABLE = {"File", "Method", "Block", "Stmt", "Replaced"};

    private static final int TOK = FIRST_USER_KIND;

    private static final String[] TOKEN_NAMES = {"WHITESPACE", "LINE_COMMENT", "BLOCK_COMMENT", "TOK"};

    /** File [ Method [ Block1 [ Stmt0 Stmt1 ] Block2 [ Stmt2 Stmt3 ] ] ] over "aabbccdd". */
    private record Built(CstArray cst,
                         int file,
                         int method,
                         int block1,
                         int stmt0,
                         int stmt1,
                         int block2,
                         int stmt2,
                         int stmt3) {}

    private static Built buildBaseline() {
        var input = "aabbccdd";
        var tb = new TokenArrayBuilder(input);
        tb.append(TOK, 0, 2);
        tb.append(TOK, 2, 4);
        tb.append(TOK, 4, 6);
        tb.append(TOK, 6, 8);
        var tokens = tb.build(TOKEN_NAMES);
        var b = new CstArrayBuilder(input, tokens, RULE_TABLE);
        var file = b.beginNode(KIND_FILE, 0, CstArray.NO_NODE);
        var method = b.beginNode(KIND_METHOD, 0, file);
        var block1 = b.beginNode(KIND_BLOCK, 0, method);
        var stmt0 = b.beginNode(KIND_STMT, 0, block1);
        b.endNode(stmt0, 0);
        var stmt1 = b.beginNode(KIND_STMT, 1, block1);
        b.endNode(stmt1, 1);
        b.endNode(block1, 1);
        var block2 = b.beginNode(KIND_BLOCK, 2, method);
        var stmt2 = b.beginNode(KIND_STMT, 2, block2);
        b.endNode(stmt2, 2);
        var stmt3 = b.beginNode(KIND_STMT, 3, block2);
        b.endNode(stmt3, 3);
        b.endNode(block2, 3);
        b.endNode(method, 3);
        b.endNode(file, 3);
        return new Built(b.build(file), file, method, block1, stmt0, stmt1, block2, stmt2, stmt3);
    }

    /**
     * Build a freestanding subtree {@code Replaced [ Stmt Stmt Stmt ]} spanning three
     * tokens [0,2)[2,4)[4,6). Caller-controlled kind/token-count makes it easy to verify
     * that splicing changes the tree shape and shifts the suffix correctly.
     */
    private record Sub(CstArray cst, int root) {}

    private static Sub buildSubtree(int tokenCount, int rootKind) {
        var sb = new StringBuilder();
        for (var i = 0; i < tokenCount; i++ ) {
            sb.append("xx");
        }
        var input = sb.toString();
        var tb = new TokenArrayBuilder(input);
        for (var i = 0; i < tokenCount; i++ ) {
            tb.append(TOK, i * 2, i * 2 + 2);
        }
        var tokens = tb.build(TOKEN_NAMES);
        var b = new CstArrayBuilder(input, tokens, RULE_TABLE);
        var root = b.beginNode(rootKind, 0, CstArray.NO_NODE);
        for (var i = 0; i < tokenCount; i++ ) {
            var s = b.beginNode(KIND_STMT, i, root);
            b.endNode(s, i);
        }
        b.endNode(root, Math.max(0, tokenCount - 1));
        return new Sub(b.build(root), root);
    }

    /** Build a token array equivalent to baseline but with {@code newTokenCount} tokens
     * inside the spliced range, otherwise unchanged. Returns the merged token stream the
     * splice will see (its {@link org.pragmatica.peg.v6.token.TokenArray#input() input}
     * matches the post-splice text). */
    private static org.pragmatica.peg.v6.token.TokenArray buildPostSpliceTokens(int oldFirst,
                                                                                int oldLast,
                                                                                int newTokenCount) {
        // Original: 4 tokens of "xx" each = "aabbccdd" (8 bytes).
        // Spliced subtree replaces tokens [oldFirst, oldLast] with newTokenCount tokens
        // (each "xx", 2 bytes wide).
        var oldCount = 4;
        var prefixCount = oldFirst;
        var suffixCount = oldCount - oldLast - 1;
        var totalCount = prefixCount + newTokenCount + suffixCount;
        var sb = new StringBuilder();
        for (var i = 0; i < totalCount; i++ ) {
            sb.append("xx");
        }
        var input = sb.toString();
        var tb = new TokenArrayBuilder(input);
        for (var i = 0; i < totalCount; i++ ) {
            tb.append(TOK, i * 2, i * 2 + 2);
        }
        return tb.build(TOKEN_NAMES);
    }

    @Test
    void spliceLeafSubtree_replacesNodeAndShiftsSuffix() {
        var baseline = buildBaseline();
        var sub = buildSubtree(2, KIND_REPLACED);
        // same token width as block1
        var newTokens = buildPostSpliceTokens(0, 1, 2);
        // unchanged shape
        var spliced = baseline.cst.spliceSubtree(baseline.block1, sub.cst, newTokens, 0);
        // Root is still File (the splice target was Block1, deep in the tree).
        assertThat(spliced.kindAt(spliced.rootIndex()))
        .isEqualTo(KIND_FILE);
        // The first child of Method is now KIND_REPLACED instead of Block.
        var method = spliced.firstChildAt(spliced.rootIndex());
        var firstBlock = spliced.firstChildAt(method);
        assertThat(spliced.kindAt(firstBlock))
        .isEqualTo(KIND_REPLACED);
        // Replaced has 2 Stmt children.
        var stmtIter = spliced.children(firstBlock)
                              .iterator();
        assertThat(stmtIter.nextInt())
        .isNotNegative();
        assertThat(stmtIter.nextInt())
        .isNotNegative();
        assertThat(stmtIter.hasNext())
        .isFalse();
        // Suffix: second block still present and untouched.
        var secondBlock = spliced.nextSiblingAt(firstBlock);
        assertThat(spliced.kindAt(secondBlock))
        .isEqualTo(KIND_BLOCK);
        assertThat(spliced.firstTokenAt(secondBlock))
        .isEqualTo(2);
        // unchanged (delta=0)
        assertThat(spliced.lastTokenAt(secondBlock))
        .isEqualTo(3);
    }

    @Test
    void spliceWithGrowingSubtree_shiftsSuffixTokenIndices() {
        var baseline = buildBaseline();
        // Grow block1 from 2 tokens to 4. tokenDelta = +2.
        var sub = buildSubtree(4, KIND_REPLACED);
        var newTokens = buildPostSpliceTokens(0, 1, 4);
        var spliced = baseline.cst.spliceSubtree(baseline.block1, sub.cst, newTokens, 2);
        var method = spliced.firstChildAt(spliced.rootIndex());
        var firstBlock = spliced.firstChildAt(method);
        var secondBlock = spliced.nextSiblingAt(firstBlock);
        // Suffix tokens shifted by +2: old [2,3] -> new [4,5].
        assertThat(spliced.firstTokenAt(secondBlock))
        .isEqualTo(4);
        assertThat(spliced.lastTokenAt(secondBlock))
        .isEqualTo(5);
        // Method's lastToken (was 3 = oldLast of File) -> 3 + 2 = 5.
        assertThat(spliced.lastTokenAt(method))
        .isEqualTo(5);
        // Root File's lastToken also shifted.
        assertThat(spliced.lastTokenAt(spliced.rootIndex()))
        .isEqualTo(5);
        // Root File's firstToken unchanged.
        assertThat(spliced.firstTokenAt(spliced.rootIndex()))
        .isEqualTo(0);
    }

    @Test
    void spliceWithShrinkingSubtree_shiftsSuffixDownward() {
        var baseline = buildBaseline();
        // Shrink block2 from 2 tokens to 1. tokenDelta = -1.
        var sub = buildSubtree(1, KIND_REPLACED);
        var newTokens = buildPostSpliceTokens(2, 3, 1);
        var spliced = baseline.cst.spliceSubtree(baseline.block2, sub.cst, newTokens, - 1);
        var method = spliced.firstChildAt(spliced.rootIndex());
        // Block1 unchanged (entirely before splice).
        var firstBlock = spliced.firstChildAt(method);
        assertThat(spliced.kindAt(firstBlock))
        .isEqualTo(KIND_BLOCK);
        assertThat(spliced.firstTokenAt(firstBlock))
        .isEqualTo(0);
        assertThat(spliced.lastTokenAt(firstBlock))
        .isEqualTo(1);
        // Replaced subtree at block2 position.
        var secondBlock = spliced.nextSiblingAt(firstBlock);
        assertThat(spliced.kindAt(secondBlock))
        .isEqualTo(KIND_REPLACED);
        assertThat(spliced.firstTokenAt(secondBlock))
        .isEqualTo(2);
        assertThat(spliced.lastTokenAt(secondBlock))
        .isEqualTo(2);
        // Method's lastToken: was 3 (== oldLast); now 3 + (-1) = 2.
        assertThat(spliced.lastTokenAt(method))
        .isEqualTo(2);
    }

    @Test
    void spliceRootItself_yieldsCstWhoseRootIsNewSubtreeRoot() {
        var baseline = buildBaseline();
        var sub = buildSubtree(4, KIND_REPLACED);
        var newTokens = buildPostSpliceTokens(0, 3, 4);
        var spliced = baseline.cst.spliceSubtree(baseline.file, sub.cst, newTokens, 0);
        assertThat(spliced.kindAt(spliced.rootIndex()))
        .isEqualTo(KIND_REPLACED);
        // Root has 4 Stmt children (one per token).
        var count = (int) spliced.children(spliced.rootIndex())
                                .count();
        assertThat(count)
        .isEqualTo(4);
    }

    @Test
    void splicedCst_parentLinksAreConsistent() {
        var baseline = buildBaseline();
        var sub = buildSubtree(2, KIND_REPLACED);
        var newTokens = buildPostSpliceTokens(0, 1, 2);
        var spliced = baseline.cst.spliceSubtree(baseline.block1, sub.cst, newTokens, 0);
        // Walk every node and verify each non-root node's parent claims it as a child.
        for (var i = 0; i < spliced.nodeCount(); i++ ) {
            var parent = spliced.parentAt(i);
            if (parent == CstArray.NO_NODE) {
                assertThat(i)
                .as("only root has NO_NODE parent")
                .isEqualTo(spliced.rootIndex());
                continue;
            }
            var found = false;
            var c = spliced.firstChildAt(parent);
            while (c != CstArray.NO_NODE) {
                if (c == i) {
                    found = true;
                    break;
                }
                c = spliced.nextSiblingAt(c);
            }
            assertThat(found)
            .as("child %d listed under parent %d", i, parent)
            .isTrue();
        }
    }

    @Test
    void splicedCst_tokenIndicesAreInBounds() {
        var baseline = buildBaseline();
        var sub = buildSubtree(4, KIND_REPLACED);
        var newTokens = buildPostSpliceTokens(0, 1, 4);
        var spliced = baseline.cst.spliceSubtree(baseline.block1, sub.cst, newTokens, 2);
        for (var i = 0; i < spliced.nodeCount(); i++ ) {
            assertThat(spliced.firstTokenAt(i))
            .as("firstTokenAt(%d) within [0, %d)",
                i,
                newTokens.count())
            .isBetween(0,
                       newTokens.count() - 1);
            assertThat(spliced.lastTokenAt(i))
            .as("lastTokenAt(%d) within [0, %d)",
                i,
                newTokens.count())
            .isBetween(0,
                       newTokens.count() - 1);
            assertThat(spliced.lastTokenAt(i))
            .as("lastTokenAt(%d) >= firstTokenAt(%d)",
                i,
                i)
            .isGreaterThanOrEqualTo(spliced.firstTokenAt(i));
        }
    }

    @Test
    void splicedCst_tokensFieldEqualsSuppliedTokens() {
        var baseline = buildBaseline();
        var sub = buildSubtree(2, KIND_REPLACED);
        var newTokens = buildPostSpliceTokens(0, 1, 2);
        var spliced = baseline.cst.spliceSubtree(baseline.block1, sub.cst, newTokens, 0);
        assertThat(spliced.tokens())
        .isSameAs(newTokens);
        assertThat(spliced.input())
        .isEqualTo(newTokens.input());
    }

    @Test
    void nullNewSubtree_throws() {
        var baseline = buildBaseline();
        var newTokens = buildPostSpliceTokens(0, 1, 2);
        assertThatThrownBy(() -> baseline.cst.spliceSubtree(baseline.block1, null, newTokens, 0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("newSubtree");
    }

    @Test
    void nullNewTokens_throws() {
        var baseline = buildBaseline();
        var sub = buildSubtree(2, KIND_REPLACED);
        assertThatThrownBy(() -> baseline.cst.spliceSubtree(baseline.block1, sub.cst, null, 0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("newTokens");
    }

    @Test
    void outOfBoundsNodeIdx_throws() {
        var baseline = buildBaseline();
        var sub = buildSubtree(2, KIND_REPLACED);
        var newTokens = buildPostSpliceTokens(0, 1, 2);
        assertThatThrownBy(() -> baseline.cst.spliceSubtree(999, sub.cst, newTokens, 0))
        .isInstanceOf(IndexOutOfBoundsException.class);
    }

    @Test
    void mismatchedRuleTable_throws() {
        var baseline = buildBaseline();
        var input = "xx";
        var tb = new TokenArrayBuilder(input);
        tb.append(TOK, 0, 2);
        var tokens = tb.build(TOKEN_NAMES);
        var differentTable = new String[] {"AAA", "BBB"};
        var b = new CstArrayBuilder(input, tokens, differentTable);
        var root = b.beginNode(0, 0, CstArray.NO_NODE);
        b.endNode(root, 0);
        var foreignSub = b.build(root);
        var newTokens = buildPostSpliceTokens(0, 1, 1);
        assertThatThrownBy(() -> baseline.cst.spliceSubtree(baseline.block1, foreignSub, newTokens, - 1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("ruleTable");
    }
}
