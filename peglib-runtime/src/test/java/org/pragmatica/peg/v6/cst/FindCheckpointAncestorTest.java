package org.pragmatica.peg.v6.cst;

import org.pragmatica.peg.v6.token.TokenArrayBuilder;

import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.peg.v6.token.TokenArray.FIRST_USER_KIND;

/**
 * Phase D.1 — coverage for {@link CstArray#findCheckpointAncestor}.
 *
 * <p>Builds a small synthetic CST shaped like a method body containing two
 * statement blocks, then asks for checkpoint ancestors at various offsets to
 * verify the smallest enclosing checkpoint is returned and that out-of-range
 * offsets resolve to {@link CstArray#NO_NODE}.
 */
class FindCheckpointAncestorTest {
    private static final int KIND_FILE = 0;
    private static final int KIND_METHOD = 1;
    private static final int KIND_STATEMENT_BLOCK = 2;
    private static final int KIND_STATEMENT = 3;
    private static final int KIND_TOKEN = 4;

    private static final String[] RULE_TABLE = {"File", "Method", "StatementBlock", "Statement", "Token"};

    private static final int TOK = FIRST_USER_KIND;

    private static final String[] TOKEN_NAMES = {"WHITESPACE", "LINE_COMMENT", "BLOCK_COMMENT", "TOK"};

    private static final Set<String>CHECKPOINTS = Set.of("StatementBlock", "Method");

    /**
     * Build a tree shaped like
     * {@code File [ Method [ StatementBlock [ Statement Statement ] StatementBlock [ Statement ] ] ]}
     * over the input "aabbccdd" with one token per two characters.
     *
     * Token spans:
     *   0: [0,2)  "aa" — Statement #1 in block #1
     *   1: [2,4)  "bb" — Statement #2 in block #1
     *   2: [4,6)  "cc" — Statement #1 in block #2
     *   3: [6,8)  "dd" — Statement #2 in block #2
     */
    private record Built(CstArray cst,
                         int file,
                         int method,
                         int block1,
                         int stmt1a,
                         int stmt1b,
                         int block2,
                         int stmt2a,
                         int stmt2b) {}

    private static Built build() {
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
        var block1 = b.beginNode(KIND_STATEMENT_BLOCK, 0, method);
        var stmt1a = b.beginNode(KIND_STATEMENT, 0, block1);
        b.endNode(stmt1a, 0);
        var stmt1b = b.beginNode(KIND_STATEMENT, 1, block1);
        b.endNode(stmt1b, 1);
        b.endNode(block1, 1);
        var block2 = b.beginNode(KIND_STATEMENT_BLOCK, 2, method);
        var stmt2a = b.beginNode(KIND_STATEMENT, 2, block2);
        b.endNode(stmt2a, 2);
        var stmt2b = b.beginNode(KIND_STATEMENT, 3, block2);
        b.endNode(stmt2b, 3);
        b.endNode(block2, 3);
        b.endNode(method, 3);
        b.endNode(file, 3);
        var cst = b.build(file);
        return new Built(cst, file, method, block1, stmt1a, stmt1b, block2, stmt2a, stmt2b);
    }

    @Test
    void offsetInsideFirstStatement_returnsEnclosingBlock() {
        var built = build();
        assertThat(built.cst.findCheckpointAncestor(0, CHECKPOINTS))
        .isEqualTo(built.block1);
        assertThat(built.cst.findCheckpointAncestor(1, CHECKPOINTS))
        .isEqualTo(built.block1);
    }

    @Test
    void offsetInsideSecondStatementOfFirstBlock_returnsFirstBlock() {
        var built = build();
        assertThat(built.cst.findCheckpointAncestor(2, CHECKPOINTS))
        .isEqualTo(built.block1);
        assertThat(built.cst.findCheckpointAncestor(3, CHECKPOINTS))
        .isEqualTo(built.block1);
    }

    @Test
    void offsetInsideSecondBlock_returnsSecondBlock() {
        var built = build();
        assertThat(built.cst.findCheckpointAncestor(4, CHECKPOINTS))
        .isEqualTo(built.block2);
        assertThat(built.cst.findCheckpointAncestor(7, CHECKPOINTS))
        .isEqualTo(built.block2);
    }

    @Test
    void offsetAtBlockSpanStart_returnsThatBlock() {
        var built = build();
        assertThat(built.cst.findCheckpointAncestor(0, CHECKPOINTS))
        .isEqualTo(built.block1);
        assertThat(built.cst.findCheckpointAncestor(4, CHECKPOINTS))
        .isEqualTo(built.block2);
    }

    @Test
    void offsetAtRootSpanEnd_returnsNoNode() {
        var built = build();
        assertThat(built.cst.findCheckpointAncestor(8, CHECKPOINTS))
        .isEqualTo(CstArray.NO_NODE);
    }

    @Test
    void offsetBeyondRootSpan_returnsNoNode() {
        var built = build();
        assertThat(built.cst.findCheckpointAncestor(99, CHECKPOINTS))
        .isEqualTo(CstArray.NO_NODE);
    }

    @Test
    void negativeOffset_returnsNoNode() {
        var built = build();
        assertThat(built.cst.findCheckpointAncestor( - 1, CHECKPOINTS))
        .isEqualTo(CstArray.NO_NODE);
    }

    @Test
    void emptyCheckpointSet_returnsNoNode() {
        var built = build();
        assertThat(built.cst.findCheckpointAncestor(2,
                                                    Set.of()))
        .isEqualTo(CstArray.NO_NODE);
    }

    @Test
    void noEnclosingCheckpointInSet_returnsNoNode() {
        var built = build();
        assertThat(built.cst.findCheckpointAncestor(2,
                                                    Set.of("Unrelated")))
        .isEqualTo(CstArray.NO_NODE);
    }

    @Test
    void onlyMethodInCheckpointSet_returnsMethodForAnyOffset() {
        var built = build();
        var methodOnly = Set.of("Method");
        assertThat(built.cst.findCheckpointAncestor(0, methodOnly))
        .isEqualTo(built.method);
        assertThat(built.cst.findCheckpointAncestor(4, methodOnly))
        .isEqualTo(built.method);
        assertThat(built.cst.findCheckpointAncestor(7, methodOnly))
        .isEqualTo(built.method);
    }

    @Test
    void rootInCheckpointSet_butSmallerCheckpointEncloses_returnsSmallest() {
        var built = build();
        var bothPlusFile = Set.of("File", "StatementBlock", "Method");
        assertThat(built.cst.findCheckpointAncestor(2, bothPlusFile))
        .isEqualTo(built.block1);
        assertThat(built.cst.findCheckpointAncestor(5, bothPlusFile))
        .isEqualTo(built.block2);
    }

    @Test
    void emptyCst_returnsNoNode() {
        var input = "";
        var tokens = new TokenArrayBuilder(input).build(TOKEN_NAMES);
        var cst = new CstArrayBuilder(input, tokens, RULE_TABLE).build(CstArray.NO_NODE);
        assertThat(cst.findCheckpointAncestor(0, CHECKPOINTS))
        .isEqualTo(CstArray.NO_NODE);
    }

}
