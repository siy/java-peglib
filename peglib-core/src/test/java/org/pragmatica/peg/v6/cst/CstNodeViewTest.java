package org.pragmatica.peg.v6.cst;

import org.pragmatica.peg.v6.token.TokenArrayBuilder;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.peg.v6.token.TokenArray.FIRST_USER_KIND;

class CstNodeViewTest {
    private static final int KIND_SUM = 0;
    private static final int KIND_NUMBER = 1;
    private static final int KIND_ERR = 2;

    private static final String[] RULE_TABLE = {"Sum", "Number", "Err"};

    private static final int TOK_NUMBER = FIRST_USER_KIND;
    private static final int TOK_PLUS = FIRST_USER_KIND + 1;

    private static final String[] TOKEN_NAMES = {"WHITESPACE", "LINE_COMMENT", "BLOCK_COMMENT", "NUMBER", "PLUS"};

    @Test
    void viewAt_branchVariant_whenNodeHasChildren() {
        var cst = buildSumCst(false);
        var view = cst.viewAt(cst.rootIndex());
        assertThat(view)
        .isInstanceOf(CstNode.Branch.class);
        var branch = (CstNode.Branch) view;
        assertThat(branch.index())
        .isEqualTo(cst.rootIndex());
        assertThat(branch.array())
        .isSameAs(cst);
        assertThat(branch.kind())
        .isEqualTo(KIND_SUM);
        assertThat(branch.kindName())
        .isEqualTo("Sum");
        assertThat(branch.spanStart())
        .isZero();
        assertThat(branch.spanEnd())
        .isEqualTo(3);
        assertThat(branch.text()
                         .toString())
        .isEqualTo("1+2");
        assertThat(branch.children()
                         .boxed()
                         .toList())
        .containsExactly(1, 2, 3);
    }

    @Test
    void viewAt_leafVariant_whenNodeHasNoChildrenAndNoErrorFlag() {
        var cst = buildSumCst(false);
        var lhsView = cst.viewAt(1);
        assertThat(lhsView)
        .isInstanceOf(CstNode.Leaf.class);
        var leaf = (CstNode.Leaf) lhsView;
        assertThat(leaf.index())
        .isEqualTo(1);
        assertThat(leaf.kind())
        .isEqualTo(KIND_NUMBER);
        assertThat(leaf.kindName())
        .isEqualTo("Number");
        assertThat(leaf.text()
                       .toString())
        .isEqualTo("1");
        assertThat(leaf.children()
                       .boxed()
                       .toList())
        .isEmpty();
    }

    @Test
    void viewAt_errorVariant_whenErrorFlagSet() {
        var cst = buildErrorCst();
        var view = cst.viewAt(cst.rootIndex());
        assertThat(view)
        .isInstanceOf(CstNode.Error.class);
        var err = (CstNode.Error) view;
        assertThat(err.index())
        .isEqualTo(cst.rootIndex());
        assertThat(err.kind())
        .isEqualTo(KIND_ERR);
        assertThat(err.kindName())
        .isEqualTo("Err");
        assertThat(err.text()
                      .toString())
        .isEqualTo("??");
    }

    @Test
    void viewAt_errorFlagOnBranch_stillBecomesErrorVariant() {
        var input = "??";
        var tb = new TokenArrayBuilder(input);
        tb.append(TOK_NUMBER, 0, 2);
        var tokens = tb.build(TOKEN_NAMES);
        var b = new CstArrayBuilder(input, tokens, RULE_TABLE);
        var root = b.beginNode(KIND_ERR, 0, CstArray.NO_NODE);
        var child = b.beginNode(KIND_NUMBER, 0, root);
        b.endNode(child, 0);
        b.endNode(root, 0);
        b.setFlag(root, CstArray.FLAG_ERROR);
        var cst = b.build(root);
        var view = cst.viewAt(root);
        assertThat(view)
        .isInstanceOf(CstNode.Error.class);
    }

    @Test
    void patternMatch_overSealedHierarchy_compilesAndDispatches() {
        var cst = buildSumCst(false);
        var sumLabel = labelOf(cst.viewAt(cst.rootIndex()));
        var leafLabel = labelOf(cst.viewAt(1));
        assertThat(sumLabel)
        .isEqualTo("branch:Sum");
        assertThat(leafLabel)
        .isEqualTo("leaf:Number");
        var errCst = buildErrorCst();
        var errLabel = labelOf(errCst.viewAt(errCst.rootIndex()));
        assertThat(errLabel)
        .isEqualTo("error:Err");
    }

    @Test
    void allViewMethods_delegateToArray() {
        var cst = buildSumCst(false);
        var view = cst.viewAt(cst.rootIndex());
        assertThat(view.array())
        .isSameAs(cst);
        assertThat(view.index())
        .isEqualTo(cst.rootIndex());
        assertThat(view.kind())
        .isEqualTo(cst.kindAt(cst.rootIndex()));
        assertThat(view.kindName())
        .isEqualTo(cst.kindNameAt(cst.rootIndex()));
        assertThat(view.spanStart())
        .isEqualTo(cst.spanStart(cst.rootIndex()));
        assertThat(view.spanEnd())
        .isEqualTo(cst.spanEnd(cst.rootIndex()));
        assertThat(view.text()
                       .toString())
        .isEqualTo(cst.textAt(cst.rootIndex())
                      .toString());
    }

    private static String labelOf(CstNode view) {
        return switch (view) {
            case CstNode.Branch b -> "branch:" + b.kindName();
            case CstNode.Leaf l -> "leaf:" + l.kindName();
            case CstNode.Error e -> "error:" + e.kindName();
        };
    }

    private CstArray buildSumCst(boolean withError) {
        var input = "1+2";
        var tb = new TokenArrayBuilder(input);
        tb.append(TOK_NUMBER, 0, 1);
        tb.append(TOK_PLUS, 1, 2);
        tb.append(TOK_NUMBER, 2, 3);
        var tokens = tb.build(TOKEN_NAMES);
        var b = new CstArrayBuilder(input, tokens, RULE_TABLE);
        var sum = b.beginNode(KIND_SUM, 0, CstArray.NO_NODE);
        var lhs = b.beginNode(KIND_NUMBER, 0, sum);
        b.endNode(lhs, 0);
        var op = b.beginNode(KIND_NUMBER, 1, sum);
        b.endNode(op, 1);
        var rhs = b.beginNode(KIND_NUMBER, 2, sum);
        b.endNode(rhs, 2);
        b.endNode(sum, 2);
        if (withError) {
            b.setFlag(sum, CstArray.FLAG_ERROR);
        }
        return b.build(sum);
    }

    private CstArray buildErrorCst() {
        var input = "??";
        var tb = new TokenArrayBuilder(input);
        tb.append(TOK_NUMBER, 0, 2);
        var tokens = tb.build(TOKEN_NAMES);
        var b = new CstArrayBuilder(input, tokens, RULE_TABLE);
        var root = b.beginNode(KIND_ERR, 0, CstArray.NO_NODE);
        b.endNode(root, 0);
        b.setFlag(root, CstArray.FLAG_ERROR);
        return b.build(root);
    }
}
