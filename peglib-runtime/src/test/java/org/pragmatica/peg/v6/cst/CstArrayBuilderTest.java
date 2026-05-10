package org.pragmatica.peg.v6.cst;

import org.pragmatica.peg.v6.token.TokenArrayBuilder;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.peg.v6.token.TokenArray.FIRST_USER_KIND;

class CstArrayBuilderTest {
    private static final int KIND_ROOT = 0;
    private static final int KIND_CHILD = 1;

    private static final String[] RULE_TABLE = {"Root", "Child"};

    private static final int TOK_X = FIRST_USER_KIND;

    private static final String[] TOKEN_NAMES = {"WHITESPACE", "LINE_COMMENT", "BLOCK_COMMENT", "X"};

    @Test
    void sequentialAppend_producesCorrectSiblingChain() {
        var b = builder("aaaa", 4);
        var root = b.beginNode(KIND_ROOT, 0, CstArray.NO_NODE);
        var c0 = b.beginNode(KIND_CHILD, 0, root);
        b.endNode(c0, 0);
        var c1 = b.beginNode(KIND_CHILD, 0, root);
        b.endNode(c1, 0);
        var c2 = b.beginNode(KIND_CHILD, 0, root);
        b.endNode(c2, 0);
        var c3 = b.beginNode(KIND_CHILD, 0, root);
        b.endNode(c3, 0);
        b.endNode(root, 0);
        var cst = b.build(root);
        assertThat(cst.firstChildAt(root))
        .isEqualTo(c0);
        assertThat(cst.nextSiblingAt(c0))
        .isEqualTo(c1);
        assertThat(cst.nextSiblingAt(c1))
        .isEqualTo(c2);
        assertThat(cst.nextSiblingAt(c2))
        .isEqualTo(c3);
        assertThat(cst.nextSiblingAt(c3))
        .isEqualTo(CstArray.NO_NODE);
        assertThat(cst.children(root)
                      .boxed()
                      .toList())
        .containsExactly(c0, c1, c2, c3);
    }

    @Test
    void multipleNestingLevels_parentChildLinksMaintained() {
        var b = builder("xxxx", 4);
        var a = b.beginNode(KIND_ROOT, 0, CstArray.NO_NODE);
        var b1 = b.beginNode(KIND_CHILD, 0, a);
        var c = b.beginNode(KIND_CHILD, 0, b1);
        var d = b.beginNode(KIND_CHILD, 0, c);
        b.endNode(d, 0);
        b.endNode(c, 0);
        b.endNode(b1, 0);
        var b2 = b.beginNode(KIND_CHILD, 0, a);
        b.endNode(b2, 0);
        b.endNode(a, 0);
        var cst = b.build(a);
        assertThat(cst.parentAt(a))
        .isEqualTo(CstArray.NO_NODE);
        assertThat(cst.parentAt(b1))
        .isEqualTo(a);
        assertThat(cst.parentAt(c))
        .isEqualTo(b1);
        assertThat(cst.parentAt(d))
        .isEqualTo(c);
        assertThat(cst.parentAt(b2))
        .isEqualTo(a);
        assertThat(cst.firstChildAt(a))
        .isEqualTo(b1);
        assertThat(cst.firstChildAt(b1))
        .isEqualTo(c);
        assertThat(cst.firstChildAt(c))
        .isEqualTo(d);
        assertThat(cst.firstChildAt(d))
        .isEqualTo(CstArray.NO_NODE);
        assertThat(cst.nextSiblingAt(b1))
        .isEqualTo(b2);
        assertThat(cst.nextSiblingAt(b2))
        .isEqualTo(CstArray.NO_NODE);
        assertThat(cst.descendants(a)
                      .boxed()
                      .toList())
        .containsExactly(a, b1, c, d, b2);
    }

    @Test
    void buildIsSingleShot_isBuiltSetAfterFirstCall() {
        var b = builder("a", 1);
        var n = b.beginNode(KIND_ROOT, 0, CstArray.NO_NODE);
        b.endNode(n, 0);
        b.build(n);
        assertThat(b.isBuilt()).isTrue();
    }

    @Test
    void capacityGrowth_pastInitialNodeCapacity() {
        var n = 500;
        var input = "x".repeat(n);
        var tb = new TokenArrayBuilder(input);
        for (var i = 0; i < n; i++ ) {
            tb.append(TOK_X, i, i + 1);
        }
        var tokens = tb.build(TOKEN_NAMES);
        var b = new CstArrayBuilder(input, tokens, RULE_TABLE, 4);
        var root = b.beginNode(KIND_ROOT, 0, CstArray.NO_NODE);
        for (var i = 0; i < n; i++ ) {
            var child = b.beginNode(KIND_CHILD, i, root);
            b.endNode(child, i);
        }
        b.endNode(root, n - 1);
        var cst = b.build(root);
        assertThat(cst.nodeCount())
        .isEqualTo(n + 1);
        assertThat(cst.children(root)
                      .count())
        .isEqualTo(n);
        var dfs = cst.descendants(root)
                     .count();
        assertThat(dfs)
        .isEqualTo(n + 1);
    }

    @Test
    void zeroChildCapacity_isAccepted() {
        var input = "a";
        var tb = new TokenArrayBuilder(input);
        tb.append(TOK_X, 0, 1);
        var tokens = tb.build(TOKEN_NAMES);
        var b = new CstArrayBuilder(input, tokens, RULE_TABLE, 0);
        var n = b.beginNode(KIND_ROOT, 0, CstArray.NO_NODE);
        b.endNode(n, 0);
        var cst = b.build(n);
        assertThat(cst.nodeCount())
        .isEqualTo(1);
    }

    @Test
    void emptyBuilder_buildsEmptyCstWithNoNodeRoot() {
        var input = "";
        var tokens = new TokenArrayBuilder(input).build(TOKEN_NAMES);
        var b = new CstArrayBuilder(input, tokens, RULE_TABLE);
        var cst = b.build(CstArray.NO_NODE);
        assertThat(cst.nodeCount())
        .isZero();
        assertThat(cst.rootIndex())
        .isEqualTo(CstArray.NO_NODE);
        assertThat(cst.reconstruct())
        .isEmpty();
    }

    @Test
    void currentNodeCount_reflectsAllocations() {
        var b = builder("aaa", 3);
        assertThat(b.currentNodeCount())
        .isZero();
        var root = b.beginNode(KIND_ROOT, 0, CstArray.NO_NODE);
        assertThat(b.currentNodeCount())
        .isEqualTo(1);
        var child = b.beginNode(KIND_CHILD, 0, root);
        assertThat(b.currentNodeCount())
        .isEqualTo(2);
        b.endNode(child, 0);
        b.endNode(root, 0);
        assertThat(b.currentNodeCount())
        .isEqualTo(2);
    }

    @Test
    void setFlag_orsBitsTogether() {
        var b = builder("a", 1);
        var n = b.beginNode(KIND_ROOT, 0, CstArray.NO_NODE);
        b.endNode(n, 0);
        b.setFlag(n, CstArray.FLAG_ERROR);
        b.setFlag(n, 4);
        var cst = b.build(n);
        assertThat(cst.flagsAt(n))
        .isEqualTo(CstArray.FLAG_ERROR | 4);
        assertThat(cst.isError(n))
        .isTrue();
    }

    @Test
    void truncate_dropsTrailingSiblings_lastSurvivingHasNoNextSibling() {
        var b = builder("aaaaa", 5);
        var root = b.beginNode(KIND_ROOT, 0, CstArray.NO_NODE);
        var c0 = b.beginNode(KIND_CHILD, 0, root);
        b.endNode(c0, 0);
        var c1 = b.beginNode(KIND_CHILD, 1, root);
        b.endNode(c1, 1);
        var savepoint = b.currentNodeCount();
        var c2 = b.beginNode(KIND_CHILD, 2, root);
        b.endNode(c2, 2);
        var c3 = b.beginNode(KIND_CHILD, 3, root);
        b.endNode(c3, 3);
        var c4 = b.beginNode(KIND_CHILD, 4, root);
        b.endNode(c4, 4);
        b.truncate(savepoint);
        assertThat(b.currentNodeCount())
        .isEqualTo(savepoint);
        var c5 = b.beginNode(KIND_CHILD, 2, root);
        b.endNode(c5, 2);
        b.endNode(root, 2);
        var cst = b.build(root);
        assertThat(cst.nodeCount())
        .isEqualTo(4);
        assertThat(cst.children(root)
                      .boxed()
                      .toList())
        .containsExactly(c0, c1, c5);
        assertThat(cst.nextSiblingAt(c5))
        .isEqualTo(CstArray.NO_NODE);
        assertThat(cst.firstChildAt(root))
        .isEqualTo(c0);
    }

    @Test
    void truncate_dropsAllChildrenOfParent_firstChildResetsToNoNode() {
        var b = builder("aaa", 3);
        var root = b.beginNode(KIND_ROOT, 0, CstArray.NO_NODE);
        var savepoint = b.currentNodeCount();
        var c0 = b.beginNode(KIND_CHILD, 0, root);
        b.endNode(c0, 0);
        var c1 = b.beginNode(KIND_CHILD, 1, root);
        b.endNode(c1, 1);
        b.truncate(savepoint);
        assertThat(b.currentNodeCount())
        .isEqualTo(savepoint);
        b.endNode(root, 0);
        var cst = b.build(root);
        assertThat(cst.nodeCount())
        .isEqualTo(1);
        assertThat(cst.firstChildAt(root))
        .isEqualTo(CstArray.NO_NODE);
        assertThat(cst.children(root)
                      .count())
        .isZero();
    }

    @Test
    void truncate_nestedSubtree_multipleLevelsRestored() {
        var b = builder("xxxxxxxx", 8);
        var root = b.beginNode(KIND_ROOT, 0, CstArray.NO_NODE);
        var a = b.beginNode(KIND_CHILD, 0, root);
        var aChild = b.beginNode(KIND_CHILD, 0, a);
        b.endNode(aChild, 0);
        b.endNode(a, 0);
        var savepoint = b.currentNodeCount();
        var b1 = b.beginNode(KIND_CHILD, 1, root);
        var b1c0 = b.beginNode(KIND_CHILD, 1, b1);
        b.endNode(b1c0, 1);
        var b1c1 = b.beginNode(KIND_CHILD, 2, b1);
        var b1c1Deep = b.beginNode(KIND_CHILD, 2, b1c1);
        b.endNode(b1c1Deep, 2);
        b.endNode(b1c1, 2);
        b.endNode(b1, 2);
        b.truncate(savepoint);
        assertThat(b.currentNodeCount())
        .isEqualTo(savepoint);
        var c = b.beginNode(KIND_CHILD, 1, root);
        b.endNode(c, 1);
        b.endNode(root, 1);
        var cst = b.build(root);
        assertThat(cst.nodeCount())
        .isEqualTo(savepoint + 1);
        assertThat(cst.children(root)
                      .boxed()
                      .toList())
        .containsExactly(a, c);
        assertThat(cst.nextSiblingAt(a))
        .isEqualTo(c);
        assertThat(cst.nextSiblingAt(c))
        .isEqualTo(CstArray.NO_NODE);
        assertThat(cst.firstChildAt(a))
        .isEqualTo(aChild);
    }

    @Test
    void truncate_repeatedRollbackToSameSavepoint_consistent() {
        var b = builder("aaaaaa", 6);
        var root = b.beginNode(KIND_ROOT, 0, CstArray.NO_NODE);
        var c0 = b.beginNode(KIND_CHILD, 0, root);
        b.endNode(c0, 0);
        var savepoint = b.currentNodeCount();
        for (var attempt = 0; attempt < 4; attempt++ ) {
            var c1 = b.beginNode(KIND_CHILD, attempt + 1, root);
            var c1Deep = b.beginNode(KIND_CHILD, attempt + 1, c1);
            b.endNode(c1Deep, attempt + 1);
            b.endNode(c1, attempt + 1);
            b.truncate(savepoint);
            assertThat(b.currentNodeCount())
            .isEqualTo(savepoint);
        }
        var c1 = b.beginNode(KIND_CHILD, 1, root);
        b.endNode(c1, 1);
        b.endNode(root, 1);
        var cst = b.build(root);
        assertThat(cst.children(root)
                      .boxed()
                      .toList())
        .containsExactly(c0, c1);
        assertThat(cst.nextSiblingAt(c1))
        .isEqualTo(CstArray.NO_NODE);
        assertThat(cst.firstChildAt(c0))
        .isEqualTo(CstArray.NO_NODE);
    }

    private CstArrayBuilder builder(String input, int tokenCount) {
        var tb = new TokenArrayBuilder(input);
        for (var i = 0; i < tokenCount; i++ ) {
            tb.append(TOK_X, i, i + 1);
        }
        var tokens = tb.build(TOKEN_NAMES);
        return new CstArrayBuilder(input, tokens, RULE_TABLE);
    }
}
