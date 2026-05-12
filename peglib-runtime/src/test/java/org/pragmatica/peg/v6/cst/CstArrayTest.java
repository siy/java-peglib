package org.pragmatica.peg.v6.cst;

import org.pragmatica.peg.v6.token.TokenArrayBuilder;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.pragmatica.peg.v6.token.TokenArray.FIRST_USER_KIND;
import static org.pragmatica.peg.v6.token.TokenArray.KIND_WHITESPACE;

class CstArrayTest {
    private static final int KIND_SUM = 0;
    private static final int KIND_NUMBER = 1;
    private static final int KIND_PLUS = 2;
    private static final int KIND_ROOT = 3;

    private static final String[] RULE_TABLE = {"Sum", "Number", "Plus", "Root"};

    private static final int TOK_NUMBER = FIRST_USER_KIND;
    private static final int TOK_PLUS = FIRST_USER_KIND + 1;

    private static final String[] TOKEN_NAMES = {
        "WHITESPACE", "LINE_COMMENT", "BLOCK_COMMENT", "DOC_LINE_COMMENT", "DOC_BLOCK_COMMENT", "NUMBER", "PLUS"
    };

    @Test
    void emptyCst_singleLeafRoot_spanFromTokens() {
        var input = "1";
        var tb = new TokenArrayBuilder(input);
        tb.append(TOK_NUMBER, 0, 1);
        var tokens = tb.build(TOKEN_NAMES);
        var b = new CstArrayBuilder(input, tokens, RULE_TABLE);
        var root = b.beginNode(KIND_ROOT, 0, CstArray.NO_NODE);
        b.endNode(root, 0);
        var cst = b.build(root);
        assertThat(cst.nodeCount())
        .isEqualTo(1);
        assertThat(cst.rootIndex())
        .isZero();
        assertThat(cst.kindAt(root))
        .isEqualTo(KIND_ROOT);
        assertThat(cst.kindNameAt(root))
        .isEqualTo("Root");
        assertThat(cst.parentAt(root))
        .isEqualTo(CstArray.NO_NODE);
        assertThat(cst.firstChildAt(root))
        .isEqualTo(CstArray.NO_NODE);
        assertThat(cst.nextSiblingAt(root))
        .isEqualTo(CstArray.NO_NODE);
        assertThat(cst.spanStart(root))
        .isZero();
        assertThat(cst.spanEnd(root))
        .isEqualTo(1);
        assertThat(cst.textAt(root)
                      .toString())
        .isEqualTo("1");
        assertThat(cst.isError(root))
        .isFalse();
    }

    @Test
    void smallTree_sumOfTwoNumbers_linksAndSpans() {
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
        var op = b.beginNode(KIND_PLUS, 1, sum);
        b.endNode(op, 1);
        var rhs = b.beginNode(KIND_NUMBER, 2, sum);
        b.endNode(rhs, 2);
        b.endNode(sum, 2);
        var cst = b.build(sum);
        assertThat(cst.nodeCount())
        .isEqualTo(4);
        assertThat(cst.rootIndex())
        .isEqualTo(sum);
        assertThat(cst.parentAt(sum))
        .isEqualTo(CstArray.NO_NODE);
        assertThat(cst.parentAt(lhs))
        .isEqualTo(sum);
        assertThat(cst.parentAt(op))
        .isEqualTo(sum);
        assertThat(cst.parentAt(rhs))
        .isEqualTo(sum);
        assertThat(cst.firstChildAt(sum))
        .isEqualTo(lhs);
        assertThat(cst.nextSiblingAt(lhs))
        .isEqualTo(op);
        assertThat(cst.nextSiblingAt(op))
        .isEqualTo(rhs);
        assertThat(cst.nextSiblingAt(rhs))
        .isEqualTo(CstArray.NO_NODE);
        assertThat(cst.firstChildAt(lhs))
        .isEqualTo(CstArray.NO_NODE);
        assertThat(cst.firstChildAt(op))
        .isEqualTo(CstArray.NO_NODE);
        assertThat(cst.firstChildAt(rhs))
        .isEqualTo(CstArray.NO_NODE);
        assertThat(cst.kindAt(sum))
        .isEqualTo(KIND_SUM);
        assertThat(cst.kindNameAt(lhs))
        .isEqualTo("Number");
        assertThat(cst.kindNameAt(op))
        .isEqualTo("Plus");
        assertThat(cst.spanStart(sum))
        .isZero();
        assertThat(cst.spanEnd(sum))
        .isEqualTo(3);
        assertThat(cst.textAt(sum)
                      .toString())
        .isEqualTo("1+2");
        assertThat(cst.textAt(lhs)
                      .toString())
        .isEqualTo("1");
        assertThat(cst.textAt(op)
                      .toString())
        .isEqualTo("+");
        assertThat(cst.textAt(rhs)
                      .toString())
        .isEqualTo("2");
    }

    @Test
    void leadingAndTrailingTrivia_resolvedFromTokenStream() {
        var input = "  1  +  2  ";
        var tb = new TokenArrayBuilder(input);
        tb.append(KIND_WHITESPACE, 0, 2);
        // 0
        tb.append(TOK_NUMBER, 2, 3);
        // 1
        tb.append(KIND_WHITESPACE, 3, 5);
        // 2
        tb.append(TOK_PLUS, 5, 6);
        // 3
        tb.append(KIND_WHITESPACE, 6, 8);
        // 4
        tb.append(TOK_NUMBER, 8, 9);
        // 5
        tb.append(KIND_WHITESPACE, 9, 11);
        // 6
        var tokens = tb.build(TOKEN_NAMES);
        var b = new CstArrayBuilder(input, tokens, RULE_TABLE);
        var sum = b.beginNode(KIND_SUM, 1, CstArray.NO_NODE);
        var lhs = b.beginNode(KIND_NUMBER, 1, sum);
        b.endNode(lhs, 1);
        var op = b.beginNode(KIND_PLUS, 3, sum);
        b.endNode(op, 3);
        var rhs = b.beginNode(KIND_NUMBER, 5, sum);
        b.endNode(rhs, 5);
        b.endNode(sum, 5);
        var cst = b.build(sum);
        var lhsLeading = cst.leadingTriviaTokens(lhs)
                            .boxed()
                            .toList();
        assertThat(lhsLeading)
        .containsExactly(0);
        assertThat(cst.leadingTriviaText(lhs)
                      .toString())
        .isEqualTo("  ");
        var rhsTrailing = cst.trailingTriviaTokens(rhs)
                             .boxed()
                             .toList();
        assertThat(rhsTrailing)
        .containsExactly(6);
        assertThat(cst.trailingTriviaText(rhs)
                      .toString())
        .isEqualTo("  ");
        var sumLeading = cst.leadingTriviaTokens(sum)
                            .boxed()
                            .toList();
        assertThat(sumLeading)
        .containsExactly(0);
        var sumTrailing = cst.trailingTriviaTokens(sum)
                             .boxed()
                             .toList();
        assertThat(sumTrailing)
        .containsExactly(6);
        assertThat(cst.leadingTriviaTokens(op)
                      .boxed()
                      .toList())
        .containsExactly(2);
        assertThat(cst.trailingTriviaTokens(op)
                      .boxed()
                      .toList())
        .containsExactly(4);
    }

    @Test
    void leadingTrivia_emptyAtStartOfInput() {
        var input = "1";
        var tb = new TokenArrayBuilder(input);
        tb.append(TOK_NUMBER, 0, 1);
        var tokens = tb.build(TOKEN_NAMES);
        var b = new CstArrayBuilder(input, tokens, RULE_TABLE);
        var n = b.beginNode(KIND_NUMBER, 0, CstArray.NO_NODE);
        b.endNode(n, 0);
        var cst = b.build(n);
        assertThat(cst.leadingTriviaTokens(n)
                      .boxed()
                      .toList())
        .isEmpty();
        assertThat(cst.trailingTriviaTokens(n)
                      .boxed()
                      .toList())
        .isEmpty();
        assertThat(cst.leadingTriviaText(n)
                      .toString())
        .isEmpty();
        assertThat(cst.trailingTriviaText(n)
                      .toString())
        .isEmpty();
    }

    @Test
    void leadingTrivia_multipleConsecutiveTriviaTokens() {
        var input = "   1";
        var tb = new TokenArrayBuilder(input);
        tb.append(KIND_WHITESPACE, 0, 1);
        tb.append(KIND_WHITESPACE, 1, 2);
        tb.append(KIND_WHITESPACE, 2, 3);
        tb.append(TOK_NUMBER, 3, 4);
        var tokens = tb.build(TOKEN_NAMES);
        var b = new CstArrayBuilder(input, tokens, RULE_TABLE);
        var n = b.beginNode(KIND_NUMBER, 3, CstArray.NO_NODE);
        b.endNode(n, 3);
        var cst = b.build(n);
        assertThat(cst.leadingTriviaTokens(n)
                      .boxed()
                      .toList())
        .containsExactly(0, 1, 2);
        assertThat(cst.leadingTriviaText(n)
                      .toString())
        .isEqualTo("   ");
    }

    @Test
    void descendants_preOrderIncludesNodeItself() {
        var cst = buildSumCst();
        var dfs = cst.descendants(cst.rootIndex())
                     .boxed()
                     .toList();
        assertThat(dfs)
        .containsExactly(0, 1, 2, 3);
    }

    @Test
    void descendants_fromSubtreeRoot_yieldsOnlySubtree() {
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
        var op = b.beginNode(KIND_PLUS, 1, sum);
        b.endNode(op, 1);
        b.endNode(sum, 1);
        var cst = b.build(sum);
        assertThat(cst.descendants(lhs)
                      .boxed()
                      .toList())
        .containsExactly(lhs);
        assertThat(cst.descendants(op)
                      .boxed()
                      .toList())
        .containsExactly(op);
        assertThat(cst.descendants(sum)
                      .boxed()
                      .toList())
        .containsExactly(sum, lhs, op);
    }

    @Test
    void descendants_handlesDeepTree() {
        var input = "abcd";
        var tb = new TokenArrayBuilder(input);
        tb.append(TOK_NUMBER, 0, 4);
        var tokens = tb.build(TOKEN_NAMES);
        var b = new CstArrayBuilder(input, tokens, RULE_TABLE);
        var depth = 50;
        var indices = new int[depth];
        indices[0] = b.beginNode(KIND_ROOT, 0, CstArray.NO_NODE);
        for (var i = 1; i < depth; i++ ) {
            indices[i] = b.beginNode(KIND_NUMBER, 0, indices[i - 1]);
        }
        for (var i = depth - 1; i >= 0; i-- ) {
            b.endNode(indices[i], 0);
        }
        var cst = b.build(indices[0]);
        var dfs = cst.descendants(indices[0])
                     .boxed()
                     .toList();
        assertThat(dfs)
        .hasSize(depth);
        for (var i = 0; i < depth; i++ ) {
            assertThat(dfs.get(i))
            .isEqualTo(indices[i]);
        }
    }

    @Test
    void children_yieldsDirectChildrenInOrder() {
        var cst = buildSumCst();
        var kids = cst.children(cst.rootIndex())
                      .boxed()
                      .toList();
        assertThat(kids)
        .containsExactly(1, 2, 3);
        assertThat(cst.children(1)
                      .boxed()
                      .toList())
        .isEmpty();
    }

    @Test
    void errorFlag_setAndQueried_viaIsError() {
        var input = "??";
        var tb = new TokenArrayBuilder(input);
        tb.append(TOK_NUMBER, 0, 2);
        var tokens = tb.build(TOKEN_NAMES);
        var b = new CstArrayBuilder(input, tokens, RULE_TABLE);
        var n = b.beginNode(KIND_ROOT, 0, CstArray.NO_NODE);
        b.endNode(n, 0);
        b.setFlag(n, CstArray.FLAG_ERROR);
        var cst = b.build(n);
        assertThat(cst.isError(n))
        .isTrue();
        assertThat(cst.flagsAt(n) & CstArray.FLAG_ERROR)
        .isEqualTo(CstArray.FLAG_ERROR);
    }

    @Test
    void reconstruct_concatenatesAllTokens_matchesInput() {
        var input = "  1  +  2  ";
        var tb = new TokenArrayBuilder(input);
        tb.append(KIND_WHITESPACE, 0, 2);
        tb.append(TOK_NUMBER, 2, 3);
        tb.append(KIND_WHITESPACE, 3, 5);
        tb.append(TOK_PLUS, 5, 6);
        tb.append(KIND_WHITESPACE, 6, 8);
        tb.append(TOK_NUMBER, 8, 9);
        tb.append(KIND_WHITESPACE, 9, 11);
        var tokens = tb.build(TOKEN_NAMES);
        var b = new CstArrayBuilder(input, tokens, RULE_TABLE);
        var sum = b.beginNode(KIND_SUM, 1, CstArray.NO_NODE);
        var lhs = b.beginNode(KIND_NUMBER, 1, sum);
        b.endNode(lhs, 1);
        var op = b.beginNode(KIND_PLUS, 3, sum);
        b.endNode(op, 3);
        var rhs = b.beginNode(KIND_NUMBER, 5, sum);
        b.endNode(rhs, 5);
        b.endNode(sum, 5);
        var cst = b.build(sum);
        assertThat(cst.reconstruct())
        .isEqualTo(input);
    }

    // constructor_validatesArguments removed: defensive null/range checks were
    // dropped from CstArray as part of the JBCT conformance refactor — the
    // constructor is package-internal and trusts callers (CstArrayBuilder).

    @Test
    void accessors_indexOutOfBounds_throw() {
        var cst = buildSumCst();
        assertThatThrownBy(() -> cst.kindAt(99))
        .isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> cst.kindAt( - 1))
        .isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> cst.firstChildAt(99))
        .isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> cst.parentAt(99))
        .isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> cst.flagsAt(99))
        .isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> cst.firstTokenAt(99))
        .isInstanceOf(IndexOutOfBoundsException.class);
    }

    @Test
    void kindNameAt_outOfRangeKind_returnsFallbackString() {
        var input = "x";
        var tokens = new TokenArrayBuilder(input);
        tokens.append(TOK_NUMBER, 0, 1);
        var t = tokens.build(TOKEN_NAMES);
        var b = new CstArrayBuilder(input, t, new String[] {"OnlyOne"});
        var n = b.beginNode(5, 0, CstArray.NO_NODE);
        b.endNode(n, 0);
        var cst = b.build(n);
        assertThat(cst.kindNameAt(n))
        .isEqualTo("<kind:5>");
    }

    @Test
    void ruleTable_returnsCopiedTable_reflectsBuilderInput() {
        var cst = buildSumCst();
        var table = cst.ruleTable();
        assertThat(table)
        .containsExactly("Sum", "Number", "Plus", "Root");
    }

    private CstArray buildSumCst() {
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
        var op = b.beginNode(KIND_PLUS, 1, sum);
        b.endNode(op, 1);
        var rhs = b.beginNode(KIND_NUMBER, 2, sum);
        b.endNode(rhs, 2);
        b.endNode(sum, 2);
        return b.build(sum);
    }
}
