package org.pragmatica.peg.v6.cst;

import org.pragmatica.peg.v6.token.TokenArray;

import java.util.Arrays;

/**
 * Append-style mutable builder for {@link CstArray}. Single-shot: one call to
 * {@link #build(int)} produces the array; subsequent calls fail fast.
 *
 * <p>Storage uses a single packed {@code int[]} of {@value CstArray#NODE_STRIDE}
 * ints per node, grown by doubling. Sibling chains are tracked as the builder runs by
 * maintaining a stack of "previous sibling per parent": when the next child of the same
 * parent is appended, the previous sibling's {@code nextSibling} slot is patched.
 */
public final class CstArrayBuilder {
    private static final int DEFAULT_INITIAL_NODE_CAPACITY = 64;

    private final String input;
    private final TokenArray tokens;
    private final String[] ruleTable;

    private int[] nodes;
    private int nodeCount;
    private int[] lastChild;
    private int lastChildCount;
    private boolean built;

    public CstArrayBuilder(String input, TokenArray tokens, String[] ruleTable) {
        this(input, tokens, ruleTable, DEFAULT_INITIAL_NODE_CAPACITY);
    }

    public CstArrayBuilder(String input, TokenArray tokens, String[] ruleTable, int initialNodeCapacity) {
        if (input == null) {
            throw new IllegalArgumentException("input must not be null");
        }
        if (tokens == null) {
            throw new IllegalArgumentException("tokens must not be null");
        }
        if (ruleTable == null) {
            throw new IllegalArgumentException("ruleTable must not be null");
        }
        if (initialNodeCapacity < 0) {
            throw new IllegalArgumentException(
            "initialNodeCapacity must be >= 0, got " + initialNodeCapacity);
        }
        var cap = Math.max(initialNodeCapacity, 1);
        this.input = input;
        this.tokens = tokens;
        this.ruleTable = ruleTable;
        this.nodes = new int[cap * CstArray.NODE_STRIDE];
        this.nodeCount = 0;
        this.lastChild = new int[16];
        this.lastChildCount = 0;
        this.built = false;
    }

    /**
     * Allocate a new node, link it under {@code parent} (or as root if {@code parent == -1}),
     * and return its index. The new node becomes the open child of {@code parent}: subsequent
     * {@code beginNode} calls with this node's index as {@code parent} will create children of
     * it. The new node has {@code lastToken} pre-set to {@code firstToken} so that if no
     * {@link #endNode} is called the span is at least non-negative; {@link #endNode} sets the
     * final value.
     */
    public int beginNode(int kind, int firstToken, int parent) {
        checkNotBuilt();
        if (kind < 0) {
            throw new IllegalArgumentException("kind must be >= 0, got " + kind);
        }
        if (firstToken < 0) {
            throw new IllegalArgumentException("firstToken must be >= 0, got " + firstToken);
        }
        if (parent != CstArray.NO_NODE && (parent < 0 || parent >= nodeCount)) {
            throw new IllegalArgumentException(
            "parent=" + parent + " out of range [0, " + nodeCount + ") or != NO_NODE");
        }
        var newIdx = nodeCount;
        ensureNodeCapacity(newIdx + 1);
        var base = newIdx * CstArray.NODE_STRIDE;
        nodes[base] = parent;
        nodes[base + 1] = kind;
        nodes[base + 2] = firstToken;
        nodes[base + 3] = firstToken;
        nodes[base + 4] = CstArray.NO_NODE;
        nodes[base + 5] = CstArray.NO_NODE;
        nodes[base + 6] = 0;
        nodes[base + 7] = 0;
        nodeCount++ ;
        if (parent != CstArray.NO_NODE) {
            linkAsChildOf(parent, newIdx);
        }
        return newIdx;
    }

    /**
     * Set the {@code lastToken} of an existing node. Does not "pop" any builder state — the
     * {@code lastChild} stack is keyed by parent index, so children can still be appended in
     * a depth-first manner without an explicit stack discipline. Validation guards against
     * passing a node index that has not yet been allocated.
     */
    public void endNode(int nodeIdx, int lastToken) {
        checkNotBuilt();
        if (nodeIdx < 0 || nodeIdx >= nodeCount) {
            throw new IllegalArgumentException(
            "nodeIdx=" + nodeIdx + " out of range [0, " + nodeCount + ")");
        }
        if (lastToken < 0) {
            throw new IllegalArgumentException("lastToken must be >= 0, got " + lastToken);
        }
        var first = nodes[nodeIdx * CstArray.NODE_STRIDE + 2];
        if (lastToken < first) {
            throw new IllegalArgumentException(
            "lastToken=" + lastToken + " < firstToken=" + first + " for node " + nodeIdx);
        }
        nodes[nodeIdx * CstArray.NODE_STRIDE + 3] = lastToken;
    }

    public void setFlag(int nodeIdx, int flag) {
        checkNotBuilt();
        if (nodeIdx < 0 || nodeIdx >= nodeCount) {
            throw new IllegalArgumentException(
            "nodeIdx=" + nodeIdx + " out of range [0, " + nodeCount + ")");
        }
        nodes[nodeIdx * CstArray.NODE_STRIDE + 6] |= flag;
    }

    public int currentNodeCount() {
        return nodeCount;
    }

    /**
     * Phase B.3 — truncate the node array to {@code newCount}, dropping every node
     * whose index is {@code >= newCount}. Supports backtracking in the generated
     * parser: a call site saves {@link #currentNodeCount()} before attempting an
     * alternative and calls this method to roll back partial progress on failure.
     *
     * <p>The {@code lastChild} table is rebuilt from the surviving nodes so a parent
     * whose last child was truncated correctly re-links the next appended child.
     *
     * @throws IllegalArgumentException when {@code newCount} is outside
     *     {@code [0, currentNodeCount()]}
     */
    public void truncate(int newCount) {
        checkNotBuilt();
        if (newCount < 0 || newCount > nodeCount) {
            throw new IllegalArgumentException(
            "newCount=" + newCount + " out of range [0, " + nodeCount + "]");
        }
        if (newCount == nodeCount) {
            return;
        }
        // Drop the trailing nodes wholesale; rebuild lastChild from the surviving
        // nodes. The cheapest correct approach is to replay the parent links that
        // remain. Parents themselves are at indices < newCount so the loop is
        // bounded by the surviving range.
        nodeCount = newCount;
        for (var p = 0; p < lastChildCount; p++ ) {
            lastChild[p] = CstArray.NO_NODE;
        }
        lastChildCount = 0;
        if (newCount == 0) {
            return;
        }
        // Walk surviving nodes and reconstruct lastChild + repair stale
        // firstChild/nextSibling pointers that referenced truncated nodes.
        for (var i = 0; i < newCount; i++ ) {
            var firstChildSlot = i * CstArray.NODE_STRIDE + 4;
            var firstChild = nodes[firstChildSlot];
            if (firstChild != CstArray.NO_NODE && firstChild >= newCount) {
                nodes[firstChildSlot] = CstArray.NO_NODE;
            }
            var nextSibSlot = i * CstArray.NODE_STRIDE + 5;
            var nextSib = nodes[nextSibSlot];
            if (nextSib != CstArray.NO_NODE && nextSib >= newCount) {
                nodes[nextSibSlot] = CstArray.NO_NODE;
            }
        }
        // Rebuild lastChild table from surviving sibling chains.
        for (var i = 0; i < newCount; i++ ) {
            var parent = nodes[i * CstArray.NODE_STRIDE];
            if (parent == CstArray.NO_NODE) {
                continue;
            }
            ensureLastChildCapacity(parent + 1);
            if (lastChildCount < parent + 1) {
                for (var j = lastChildCount; j < parent + 1; j++ ) {
                    lastChild[j] = CstArray.NO_NODE;
                }
                lastChildCount = parent + 1;
            }
            // Track the last child seen so far for each parent.
            lastChild[parent] = i;
        }
    }

    public CstArray build(int rootIndex) {
        checkNotBuilt();
        if (nodeCount == 0) {
            if (rootIndex != CstArray.NO_NODE) {
                throw new IllegalArgumentException(
                "rootIndex must be NO_NODE for empty builder, got " + rootIndex);
            }
        }else if (rootIndex < 0 || rootIndex >= nodeCount) {
            throw new IllegalArgumentException(
            "rootIndex=" + rootIndex + " out of range [0, " + nodeCount + ")");
        }
        var trimmed = Arrays.copyOf(nodes, nodeCount * CstArray.NODE_STRIDE);
        var ruleTableCopy = ruleTable.clone();
        built = true;
        nodes = null;
        lastChild = null;
        return new CstArray(input, tokens, trimmed, nodeCount, ruleTableCopy, rootIndex);
    }

    private void linkAsChildOf(int parent, int child) {
        ensureLastChildCapacity(parent + 1);
        if (lastChildCount < parent + 1) {
            for (var i = lastChildCount; i < parent + 1; i++ ) {
                lastChild[i] = CstArray.NO_NODE;
            }
            lastChildCount = parent + 1;
        }
        var prev = lastChild[parent];
        if (prev == CstArray.NO_NODE) {
            nodes[parent * CstArray.NODE_STRIDE + 4] = child;
        }else {
            nodes[prev * CstArray.NODE_STRIDE + 5] = child;
        }
        lastChild[parent] = child;
    }

    private void ensureNodeCapacity(int requiredNodes) {
        var requiredInts = requiredNodes * CstArray.NODE_STRIDE;
        if (requiredInts <= nodes.length) {
            return;
        }
        var newCap = nodes.length;
        while (newCap < requiredInts) {
            newCap = newCap<< 1;
            if (newCap < 0) {
                newCap = Integer.MAX_VALUE - 8;
            }
        }
        nodes = Arrays.copyOf(nodes, newCap);
    }

    private void ensureLastChildCapacity(int required) {
        if (required <= lastChild.length) {
            return;
        }
        var newCap = lastChild.length;
        while (newCap < required) {
            newCap = newCap<< 1;
            if (newCap < 0) {
                newCap = Integer.MAX_VALUE - 8;
            }
        }
        lastChild = Arrays.copyOf(lastChild, newCap);
    }

    private void checkNotBuilt() {
        if (built) {
            throw new IllegalStateException("builder already built; cannot mutate further");
        }
    }
}
