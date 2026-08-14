package org.pragmatica.peg.cst;

import org.pragmatica.peg.token.TokenArrayBuilder;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.peg.token.TokenArray.FIRST_USER_KIND;

/**
 * 0.7.1 — single-slot success memo, the {@code %memo} directive backend.
 * {@link CstArrayBuilder#memoArm} records a completed subtree; {@link
 * CstArrayBuilder#truncate} salvages the node records when backtracking drops
 * the range; {@link CstArrayBuilder#memoTryReplay} splices the salvaged copy
 * back at the same token position, rebasing intra-subtree links and severing
 * pointers that escape the subtree (the old parent and any stale sibling).
 */
class CstArrayBuilderMemoTest {
    private static final int KIND_ROOT = 0;
    private static final int KIND_ARGS = 1;
    private static final int KIND_ITEM = 2;

    private static final String[] RULE_TABLE = {"Root", "Args", "Item"};

    private static final int TOK_X = FIRST_USER_KIND;

    private static final String[] TOKEN_NAMES = {"WHITESPACE", "LINE_COMMENT", "BLOCK_COMMENT", "X"};

    @Test
    void salvagedSubtree_replaysAtSamePosition_identicalShape() {
        var b = builder("aaaaaa", 6);
        var root = b.beginNode(KIND_ROOT, 0, CstArray.NO_NODE);
        var savepoint = b.currentNodeCount();
        // "Parse" an Args subtree at token position 1 consuming tokens 1..2:
        // Args wrapping two Item children.
        var args = b.beginNode(KIND_ARGS, 1, root);
        var i0 = b.beginNode(KIND_ITEM, 1, args);
        b.endNode(i0, 1);
        var i1 = b.beginNode(KIND_ITEM, 2, args);
        b.endNode(i1, 2);
        b.endNode(args, 2);
        b.memoArm(KIND_ARGS, 1, 3, args);
        // Enclosing alternative fails: backtracking drops the subtree.
        b.truncate(savepoint);
        assertThat(b.currentNodeCount())
        .isEqualTo(savepoint);
        // Re-parse of Args at the same position replays the salvaged copy.
        var end = b.memoTryReplay(KIND_ARGS, 1, root);
        assertThat(end)
        .isEqualTo(3);
        assertThat(b.currentNodeCount())
        .isEqualTo(savepoint + 3);
        b.endNode(root, 3);
        var cst = b.build(root);
        var replayed = cst.firstChildAt(root);
        assertThat(cst.kindAt(replayed))
        .isEqualTo(KIND_ARGS);
        assertThat(cst.parentAt(replayed))
        .isEqualTo(root);
        assertThat(cst.firstTokenAt(replayed))
        .isEqualTo(1);
        assertThat(cst.lastTokenAt(replayed))
        .isEqualTo(2);
        var c0 = cst.firstChildAt(replayed);
        var c1 = cst.nextSiblingAt(c0);
        assertThat(cst.kindAt(c0))
        .isEqualTo(KIND_ITEM);
        assertThat(cst.kindAt(c1))
        .isEqualTo(KIND_ITEM);
        assertThat(cst.parentAt(c0))
        .isEqualTo(replayed);
        assertThat(cst.parentAt(c1))
        .isEqualTo(replayed);
        assertThat(cst.nextSiblingAt(c1))
        .isEqualTo(CstArray.NO_NODE);
        assertThat(cst.nextSiblingAt(replayed))
        .isEqualTo(CstArray.NO_NODE);
    }

    @Test
    void replayedSubtree_chainsAfterExistingSibling() {
        var b = builder("aaaaaa", 6);
        var root = b.beginNode(KIND_ROOT, 0, CstArray.NO_NODE);
        var c0 = b.beginNode(KIND_ITEM, 0, root);
        b.endNode(c0, 0);
        var savepoint = b.currentNodeCount();
        var args = b.beginNode(KIND_ARGS, 1, root);
        b.endNode(args, 2);
        b.memoArm(KIND_ARGS, 1, 3, args);
        b.truncate(savepoint);
        var end = b.memoTryReplay(KIND_ARGS, 1, root);
        assertThat(end)
        .isEqualTo(3);
        b.endNode(root, 3);
        var cst = b.build(root);
        var replayed = cst.nextSiblingAt(c0);
        assertThat(cst.children(root)
                      .boxed()
                      .toList())
        .containsExactly(c0, replayed);
        assertThat(cst.kindAt(replayed))
        .isEqualTo(KIND_ARGS);
    }

    @Test
    void replay_missesOnDifferentKindOrPosition() {
        var b = builder("aaaaaa", 6);
        var root = b.beginNode(KIND_ROOT, 0, CstArray.NO_NODE);
        var savepoint = b.currentNodeCount();
        var args = b.beginNode(KIND_ARGS, 1, root);
        b.endNode(args, 2);
        b.memoArm(KIND_ARGS, 1, 3, args);
        b.truncate(savepoint);
        assertThat(b.memoTryReplay(KIND_ITEM, 1, root))
        .isEqualTo(-1);
        assertThat(b.memoTryReplay(KIND_ARGS, 2, root))
        .isEqualTo(-1);
        assertThat(b.currentNodeCount())
        .isEqualTo(savepoint);
    }

    @Test
    void armedButLiveRange_isNotReplayable() {
        // The subtree is still linked in the tree — a replay would duplicate it.
        // Only a range salvaged by truncate is replayable.
        var b = builder("aaaaaa", 6);
        var root = b.beginNode(KIND_ROOT, 0, CstArray.NO_NODE);
        var args = b.beginNode(KIND_ARGS, 1, root);
        b.endNode(args, 2);
        b.memoArm(KIND_ARGS, 1, 3, args);
        assertThat(b.memoTryReplay(KIND_ARGS, 1, root))
        .isEqualTo(-1);
    }

    @Test
    void truncateEntirelyAboveRange_keepsMemoLive_noSalvage() {
        var b = builder("aaaaaa", 6);
        var root = b.beginNode(KIND_ROOT, 0, CstArray.NO_NODE);
        var args = b.beginNode(KIND_ARGS, 1, root);
        b.endNode(args, 2);
        b.memoArm(KIND_ARGS, 1, 3, args);
        var afterArgs = b.currentNodeCount();
        var later = b.beginNode(KIND_ITEM, 3, root);
        b.truncate(afterArgs);
        assertThat(later)
        .isEqualTo(afterArgs);
        // The armed range survived untouched, so it is still live, not salvaged.
        assertThat(b.memoTryReplay(KIND_ARGS, 1, root))
        .isEqualTo(-1);
    }

    @Test
    void replayTwice_bufferSurvivesFirstReplay() {
        var b = builder("aaaaaa", 6);
        var root = b.beginNode(KIND_ROOT, 0, CstArray.NO_NODE);
        var savepoint = b.currentNodeCount();
        var args = b.beginNode(KIND_ARGS, 1, root);
        var item = b.beginNode(KIND_ITEM, 1, args);
        b.endNode(item, 1);
        b.endNode(args, 2);
        b.memoArm(KIND_ARGS, 1, 3, args);
        b.truncate(savepoint);
        assertThat(b.memoTryReplay(KIND_ARGS, 1, root))
        .isEqualTo(3);
        // A later backtrack drops the replayed copy too...
        b.truncate(savepoint);
        // ...and a third parse at the same position replays again.
        assertThat(b.memoTryReplay(KIND_ARGS, 1, root))
        .isEqualTo(3);
        b.endNode(root, 3);
        var cst = b.build(root);
        var replayed = cst.firstChildAt(root);
        assertThat(cst.kindAt(replayed))
        .isEqualTo(KIND_ARGS);
        assertThat(cst.kindAt(cst.firstChildAt(replayed)))
        .isEqualTo(KIND_ITEM);
    }

    @Test
    void rearm_overwritesPreviousMemo() {
        var b = builder("aaaaaa", 6);
        var root = b.beginNode(KIND_ROOT, 0, CstArray.NO_NODE);
        var savepoint = b.currentNodeCount();
        var argsA = b.beginNode(KIND_ARGS, 1, root);
        b.endNode(argsA, 1);
        b.memoArm(KIND_ARGS, 1, 2, argsA);
        var argsB = b.beginNode(KIND_ARGS, 3, root);
        b.endNode(argsB, 3);
        b.memoArm(KIND_ARGS, 3, 4, argsB);
        b.truncate(savepoint);
        // Single slot: only the last armed subtree is replayable.
        assertThat(b.memoTryReplay(KIND_ARGS, 1, root))
        .isEqualTo(-1);
        assertThat(b.memoTryReplay(KIND_ARGS, 3, root))
        .isEqualTo(4);
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
