package org.pragmatica.peg.cst;

import java.util.Arrays;

import org.pragmatica.peg.token.TokenArray;


/**
 * Append-style mutable builder for {@link CstArray}. Single-shot: one call to
 * {@link #build(int)} produces the array; subsequent calls fail fast.
 *
 * <p>Storage uses a single packed {@code int[]} of {@value CstArray#NODE_STRIDE}
 * ints per node, grown by doubling. Sibling chains are linked on SUCCESS, not on
 * allocation: {@link #beginNode} only reserves and initialises the slot, and
 * {@link #endNode} links the node into its parent's child chain. A node abandoned
 * by backtracking — truncated before being ended — was therefore never linked and
 * needs no link repair, which is what keeps {@link #truncate(int)} cheap on the
 * dominant begin-fail-truncate path.
 *
 * <p>This builder is an internal hot-path helper invoked from generated parser code and
 * a small number of trusted Java callers (CST splice, recovery emit). Defensive
 * argument validation is intentionally omitted: callers are responsible for passing
 * sane indices, and the JVM's array bounds checks catch any genuine bugs.
 */
/*
 * Parse hot path — imperative by design.
 *
 * JBCT-PAT-01 (raw loops) and JBCT-UTIL-02 (Verify.Is:: predicates) are suppressed for this
 * class as a deliberate policy, not an oversight. These methods run per-token / per-node on
 * every parse, and this is the most correctness-critical code in the project: a mechanical
 * rewrite to functional iteration would carry real regression risk for no user-visible gain.
 *
 * Note the honest framing: this is NOT a claim that streams measure slower here — nobody has
 * profiled that, and per the project's "profile-first, theorize never" rule such a claim would
 * be worth little. The argument is risk-versus-benefit, and it stands on that alone. If the
 * rewrite is ever attempted, bench it (Java25ParseBenchmark / Java25LargeFixturesBenchmark
 * from peglib-core/) rather than assuming either direction.
 */
@SuppressWarnings({"JBCT-PAT-01", "JBCT-UTIL-02"})
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
     * Link journal: one {@code (child, previousLastChild)} pair per {@link #endNode}
     * that linked a node into its parent, in link order, packed two ints per entry.
     * {@link #truncate(int)} pops the suffix whose child index falls in the dropped
     * range and undoes exactly those links. Nodes that failed before reaching
     * {@code endNode} were never linked, have no entry, and cost nothing to drop.
     */
    private int[] linkLog;
    private int linkLogCount;
    private boolean built;

    public CstArrayBuilder(String input, TokenArray tokens, String[] ruleTable) {
        this(input, tokens, ruleTable, DEFAULT_INITIAL_NODE_CAPACITY);
    }

    public CstArrayBuilder(String input, TokenArray tokens, String[] ruleTable, int initialNodeCapacity) {
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
        this.linkLog = new int[Math.max(128, cap)];
        this.linkLogCount = 0;
        this.built = false;
    }

    /**
     * Allocate a new node and return its index. The node records {@code parent}
     * (or {@code -1} for a root) but is NOT yet linked into the parent's child chain —
     * linking happens in {@link #endNode}. Until then the node is invisible to
     * child/sibling traversal; every node that survives to {@link #build(int)} must be
     * ended exactly once, on its success path. Children may attach to a still-open
     * parent: {@code beginNode} calls with this node's index as {@code parent} work
     * immediately. {@code lastToken} is pre-set to {@code firstToken} so the span is
     * at least non-negative; {@link #endNode} sets the final value.
     */
    public int beginNode(int kind, int firstToken, int parent) {
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
        nodeCount++;

        return newIdx;
    }

    /**
     * Set the {@code lastToken} of {@code nodeIdx} and link it into its parent's child
     * chain, journalling the parent's previous last-child so {@link #truncate(int)} can
     * undo the link if a later failure drops this node. Must be called exactly once per
     * node, on its success path only: siblings succeed in source order, so linking at
     * end time preserves child order. Calling it twice would double-link the node.
     */
    @SuppressWarnings("JBCT-RET-01")
    public void endNode(int nodeIdx, int lastToken) {
        var base = nodeIdx * CstArray.NODE_STRIDE;

        nodes[base + 3] = lastToken;
        var parent = nodes[base];

        if (parent != CstArray.NO_NODE) {
            linkAsChildOf(parent, nodeIdx);
        }
    }

    @SuppressWarnings("JBCT-RET-01")
    public void setFlag(int nodeIdx, int flag) {
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
     * <p>Only nodes that reached {@link #endNode} were ever linked, and their journal
     * entries form a contiguous suffix of {@link #linkLog}: between a savepoint and its
     * truncate, every {@code endNode} call is for a node allocated after the savepoint,
     * because ending an older node would require returning out of the rule invocation
     * that holds the savepoint. So rollback pops that suffix — cost is
     * O(linked-and-dropped), and the common begin-fail-truncate churn (no completed
     * children) reduces to resetting {@code nodeCount}.
     */
    @SuppressWarnings("JBCT-RET-01")
    public void truncate(int newCount) {
        if (newCount == nodeCount) {
            return;
        }

        while (linkLogCount > 0 && linkLog[(linkLogCount - 1) * 2]>= newCount) {
            linkLogCount--;
            var child = linkLog[linkLogCount * 2];
            var parent = nodes[child * CstArray.NODE_STRIDE];
            // A dropped parent's chain slots are themselves in the dropped range
            // (children always have higher indices than their parent), so only
            // links into surviving parents need repair. Reverse pop order means
            // the last pop for any surviving parent restores the value current
            // before its first dropped child linked.
            if (parent < newCount) {
                var prev = linkLog[linkLogCount * 2 + 1];

                if (prev == CstArray.NO_NODE) {
                    nodes[parent * CstArray.NODE_STRIDE + 4] = CstArray.NO_NODE;
                } else {
                    nodes[prev * CstArray.NODE_STRIDE + 5] = CstArray.NO_NODE;
                }

                lastChild[parent] = prev;
            }
        }

        nodeCount = newCount;
        // Clip lastChildCount so that a future linkAsChildOf call with a parent
        // index in [newCount, oldLastChildCount) takes the init path and resets
        // the slot to NO_NODE: those slots may hold values for dropped nodes
        // re-allocated at the same index.
        if (lastChildCount > newCount) {
            lastChildCount = newCount;
        }
    }

    public CstArray build(int rootIndex) {
        var trimmed = Arrays.copyOf(nodes, nodeCount * CstArray.NODE_STRIDE);
        var ruleTableCopy = ruleTable.clone();

        built = true;
        nodes = null;
        lastChild = null;
        linkLog = null;

        return new CstArray(input, tokens, trimmed, nodeCount, ruleTableCopy, rootIndex);
    }

    public boolean isBuilt() {
        return built;
    }

    private void linkAsChildOf(int parent, int child) {
        ensureLastChildCapacity(parent + 1);
        if (lastChildCount < parent + 1) {
            for (var i = lastChildCount; i < parent + 1; i++) {
                lastChild[i] = CstArray.NO_NODE;
            }

            lastChildCount = parent + 1;
        }

        var prev = lastChild[parent];

        if (prev == CstArray.NO_NODE) {
            nodes[parent * CstArray.NODE_STRIDE + 4] = child;
        } else {
            nodes[prev * CstArray.NODE_STRIDE + 5] = child;
        }

        lastChild[parent] = child;
        ensureLinkLogCapacity(linkLogCount + 1);
        linkLog[linkLogCount * 2] = child;
        linkLog[linkLogCount * 2 + 1] = prev;
        linkLogCount++;
    }

    private void ensureNodeCapacity(int requiredNodes) {
        var requiredInts = requiredNodes * CstArray.NODE_STRIDE;

        if (requiredInts <= nodes.length) {
            return;
        }

        var newCap = nodes.length;

        while (newCap < requiredInts) {
            newCap = newCap << 1;
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
            newCap = newCap << 1;
            if (newCap < 0) {
                newCap = Integer.MAX_VALUE - 8;
            }
        }

        lastChild = Arrays.copyOf(lastChild, newCap);
    }

    private void ensureLinkLogCapacity(int requiredEntries) {
        var requiredInts = requiredEntries * 2;

        if (requiredInts <= linkLog.length) {
            return;
        }

        var newCap = linkLog.length;

        while (newCap < requiredInts) {
            newCap = newCap << 1;
            if (newCap < 0) {
                newCap = Integer.MAX_VALUE - 8;
            }
        }

        linkLog = Arrays.copyOf(linkLog, newCap);
    }
}
