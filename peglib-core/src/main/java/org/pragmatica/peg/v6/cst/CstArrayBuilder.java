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
    /**
     * Parallel array sized to {@code nodes / NODE_STRIDE}. {@code lastChildBefore[i]}
     * stores the value of {@code lastChild[parentOf(i)]} BEFORE node {@code i} was
     * appended (i.e., the would-be previous sibling, or {@link CstArray#NO_NODE} if
     * {@code i} is its parent's first child or has no parent). Used as an undo log
     * by {@link #truncate(int)} so rollback cost is O(dropped) rather than
     * O(surviving).
     */
    private int[] lastChildBefore;
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
        // Pre-size lastChild generously so ensureLastChildCapacity rarely fires
        // in steady state. Quarter of node capacity is a reasonable upper bound
        // on distinct parent indices touched per backtrack window.
        this.lastChild = new int[Math.max(64, cap / 4)];
        this.lastChildCount = 0;
        this.lastChildBefore = new int[cap];
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
        // Record the would-be previous sibling BEFORE linkAsChildOf overwrites
        // lastChild[parent]. truncate() consults this slot during rollback to
        // restore lastChild[parent] in O(dropped) time.
        if (parent != CstArray.NO_NODE && parent < lastChildCount) {
            lastChildBefore[newIdx] = lastChild[parent];
        }else {
            lastChildBefore[newIdx] = CstArray.NO_NODE;
        }
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
     * <p>Uses a parallel undo log {@link #lastChildBefore} populated by
     * {@link #beginNode}. For each dropped index {@code i} we restore
     * {@code lastChild[parentOf(i)]} to {@code lastChildBefore[i]} and clear the
     * sibling/firstChild link that pointed to {@code i}. Cost is O(dropped),
     * independent of the size of the surviving prefix — which dominates time when
     * the parser performs many shallow rollbacks deep into the input.
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
        // Walk the dropped range backward, undoing the link that beginNode
        // recorded for each node. Two writes per dropped node:
        //   1. Restore lastChild[parent] to the pre-link value.
        //   2. Clear the slot that pointed to this node (parent's firstChild
        //      when prev == NO_NODE, otherwise prev's nextSibling).
        // Multi-sibling drops resolve correctly: processing reverse order
        // means the LAST iteration for any parent restores the value that
        // was current before the FIRST (lowest-index) of that parent's
        // dropped children was added.
        for (var i = nodeCount - 1; i >= newCount; i-- ) {
            var base = i * CstArray.NODE_STRIDE;
            var parent = nodes[base];
            if (parent == CstArray.NO_NODE) {
                continue;
            }
            var prev = lastChildBefore[i];
            if (prev == CstArray.NO_NODE) {
                nodes[parent * CstArray.NODE_STRIDE + 4] = CstArray.NO_NODE;
            }else {
                nodes[prev * CstArray.NODE_STRIDE + 5] = CstArray.NO_NODE;
            }
            // Note: parent may itself be in the dropped range (>= newCount).
            // The write into lastChild[parent] is safe because beginNode
            // ensured lastChild has capacity through any parent it ever saw,
            // and writing to a soon-discarded slot is harmless.
            lastChild[parent] = prev;
        }
        nodeCount = newCount;
        // Clip lastChildCount so that future linkAsChildOf calls with a parent
        // index in [newCount, oldLastChildCount) take the init path and reset
        // the slot to NO_NODE. The back-walk above wrote restored values into
        // lastChild for parents that were themselves dropped; those writes are
        // stale relative to any node that may be re-allocated at the same
        // index, and clipping forces correct re-initialisation.
        if (lastChildCount > newCount) {
            lastChildCount = newCount;
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
        lastChildBefore = null;
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
        var nodeCap = newCap / CstArray.NODE_STRIDE;
        if (lastChildBefore.length < nodeCap) {
            lastChildBefore = Arrays.copyOf(lastChildBefore, nodeCap);
        }
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
