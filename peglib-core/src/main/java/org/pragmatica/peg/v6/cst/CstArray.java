package org.pragmatica.peg.v6.cst;

import java.util.PrimitiveIterator;
import java.util.Set;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.function.IntConsumer;
import java.util.stream.IntStream;
import java.util.stream.StreamSupport;

import org.pragmatica.peg.v6.token.TokenArray;

/**
 * Phase B.1 — flat-array CST data structure for the 0.6.0 pipeline.
 *
 * <p>Per spec §3.5, every parse produces one {@code CstArray}: the input string, the lex
 * output ({@link TokenArray}), and a single packed {@code int[]} of {@value #NODE_STRIDE}
 * ints per node. No per-node records, no list allocations on the data path. The shape is
 * read-only after {@link CstArrayBuilder#build}.
 *
 * <p>Each node occupies eight slots: parent, kind, firstToken, lastToken, firstChild,
 * nextSibling, flags, reserved.
 *
 * <p>Trivia handling (§3.6) is purely positional: trivia tokens live in the underlying
 * {@code TokenArray}, and the helpers {@link #leadingTriviaTokens(int)} /
 * {@link #trailingTriviaTokens(int)} simply scan the token stream around a node's span.
 */
public final class CstArray {

    public static final int NODE_STRIDE = 8;

    public static final int FLAG_ERROR = 1;

    public static final int NO_NODE = -1;

    private static final int OFFSET_PARENT = 0;
    private static final int OFFSET_KIND = 1;
    private static final int OFFSET_FIRST_TOKEN = 2;
    private static final int OFFSET_LAST_TOKEN = 3;
    private static final int OFFSET_FIRST_CHILD = 4;
    private static final int OFFSET_NEXT_SIBLING = 5;
    private static final int OFFSET_FLAGS = 6;
    private static final int OFFSET_RESERVED = 7;

    private final String input;
    private final TokenArray tokens;
    private final int[] nodes;
    private final int nodeCount;
    private final String[] ruleTable;
    private final int rootIndex;

    public CstArray(String input,
                    TokenArray tokens,
                    int[] nodes,
                    int nodeCount,
                    String[] ruleTable,
                    int rootIndex) {
        if (input == null) {
            throw new IllegalArgumentException("input must not be null");
        }
        if (tokens == null) {
            throw new IllegalArgumentException("tokens must not be null");
        }
        if (nodes == null) {
            throw new IllegalArgumentException("nodes must not be null");
        }
        if (ruleTable == null) {
            throw new IllegalArgumentException("ruleTable must not be null");
        }
        if (nodeCount < 0) {
            throw new IllegalArgumentException("nodeCount must be >= 0, got " + nodeCount);
        }
        if (nodes.length < nodeCount * NODE_STRIDE) {
            throw new IllegalArgumentException(
                "nodes array too small for nodeCount=" + nodeCount + " (need "
                    + (nodeCount * NODE_STRIDE) + ", got " + nodes.length + ")");
        }
        if (nodeCount == 0) {
            if (rootIndex != NO_NODE) {
                throw new IllegalArgumentException(
                    "rootIndex must be NO_NODE when nodeCount=0, got " + rootIndex);
            }
        } else if (rootIndex < 0 || rootIndex >= nodeCount) {
            throw new IllegalArgumentException(
                "rootIndex=" + rootIndex + " out of bounds [0, " + nodeCount + ")");
        }
        this.input = input;
        this.tokens = tokens;
        this.nodes = nodes;
        this.nodeCount = nodeCount;
        this.ruleTable = ruleTable;
        this.rootIndex = rootIndex;
    }

    public int nodeCount() {
        return nodeCount;
    }

    public int rootIndex() {
        return rootIndex;
    }

    public TokenArray tokens() {
        return tokens;
    }

    public String input() {
        return input;
    }

    public String[] ruleTable() {
        return ruleTable;
    }

    public int kindAt(int nodeIdx) {
        checkIndex(nodeIdx);
        return nodes[nodeIdx * NODE_STRIDE + OFFSET_KIND];
    }

    public String kindNameAt(int nodeIdx) {
        var k = kindAt(nodeIdx);
        if (k < 0 || k >= ruleTable.length) {
            return "<kind:" + k + ">";
        }
        return ruleTable[k];
    }

    public int firstChildAt(int nodeIdx) {
        checkIndex(nodeIdx);
        return nodes[nodeIdx * NODE_STRIDE + OFFSET_FIRST_CHILD];
    }

    public int nextSiblingAt(int nodeIdx) {
        checkIndex(nodeIdx);
        return nodes[nodeIdx * NODE_STRIDE + OFFSET_NEXT_SIBLING];
    }

    public int parentAt(int nodeIdx) {
        checkIndex(nodeIdx);
        return nodes[nodeIdx * NODE_STRIDE + OFFSET_PARENT];
    }

    public int firstTokenAt(int nodeIdx) {
        checkIndex(nodeIdx);
        return nodes[nodeIdx * NODE_STRIDE + OFFSET_FIRST_TOKEN];
    }

    public int lastTokenAt(int nodeIdx) {
        checkIndex(nodeIdx);
        return nodes[nodeIdx * NODE_STRIDE + OFFSET_LAST_TOKEN];
    }

    public int flagsAt(int nodeIdx) {
        checkIndex(nodeIdx);
        return nodes[nodeIdx * NODE_STRIDE + OFFSET_FLAGS];
    }

    public int reservedAt(int nodeIdx) {
        checkIndex(nodeIdx);
        return nodes[nodeIdx * NODE_STRIDE + OFFSET_RESERVED];
    }

    public boolean isError(int nodeIdx) {
        return (flagsAt(nodeIdx) & FLAG_ERROR) != 0;
    }

    public int spanStart(int nodeIdx) {
        var first = firstTokenAt(nodeIdx);
        if (first < 0 || first >= tokens.count()) {
            return input.length();
        }
        return tokens.startAt(first);
    }

    public int spanEnd(int nodeIdx) {
        var last = lastTokenAt(nodeIdx);
        if (last < 0 || last >= tokens.count()) {
            return spanStart(nodeIdx);
        }
        return tokens.endAt(last);
    }

    public CharSequence textAt(int nodeIdx) {
        return input.subSequence(spanStart(nodeIdx), spanEnd(nodeIdx));
    }

    public IntStream leadingTriviaTokens(int nodeIdx) {
        var first = firstTokenAt(nodeIdx);
        if (first <= 0 || first > tokens.count()) {
            return IntStream.empty();
        }
        var start = first - 1;
        while (start >= 0 && tokens.isTrivia(start)) {
            start--;
        }
        var begin = start + 1;
        if (begin >= first) {
            return IntStream.empty();
        }
        return IntStream.range(begin, first);
    }

    public IntStream trailingTriviaTokens(int nodeIdx) {
        var last = lastTokenAt(nodeIdx);
        var total = tokens.count();
        if (last < 0 || last >= total) {
            return IntStream.empty();
        }
        var begin = last + 1;
        var end = begin;
        while (end < total && tokens.isTrivia(end)) {
            end++;
        }
        if (begin >= end) {
            return IntStream.empty();
        }
        return IntStream.range(begin, end);
    }

    public CharSequence leadingTriviaText(int nodeIdx) {
        return concatTokenText(leadingTriviaTokens(nodeIdx));
    }

    public CharSequence trailingTriviaText(int nodeIdx) {
        return concatTokenText(trailingTriviaTokens(nodeIdx));
    }

    public IntStream children(int nodeIdx) {
        var first = firstChildAt(nodeIdx);
        if (first == NO_NODE) {
            return IntStream.empty();
        }
        return StreamSupport.intStream(
            Spliterators.spliteratorUnknownSize(new SiblingIterator(first), Spliterator.ORDERED | Spliterator.NONNULL),
            false);
    }

    public IntStream descendants(int nodeIdx) {
        checkIndex(nodeIdx);
        return StreamSupport.intStream(
            Spliterators.spliteratorUnknownSize(new DescendantIterator(nodeIdx), Spliterator.ORDERED | Spliterator.NONNULL),
            false);
    }

    public CstNode viewAt(int nodeIdx) {
        if (isError(nodeIdx)) {
            return new CstNode.Error(nodeIdx, this);
        }
        if (firstChildAt(nodeIdx) != NO_NODE) {
            return new CstNode.Branch(nodeIdx, this);
        }
        return new CstNode.Leaf(nodeIdx, this);
    }

    /**
     * Phase D.1 — find the smallest CST node enclosing {@code offset} whose rule name is
     * a member of {@code checkpointRules}. Returns {@link #NO_NODE} when no enclosing
     * checkpoint exists (offset outside the root span, or no ancestor's rule matches).
     *
     * <p>Used by the incremental engine to locate the smallest subtree that may need
     * re-parsing after an edit (see {@link org.pragmatica.peg.v6.incremental.IncrementalParser
     * IncrementalParser}). The D.1.1 implementation of that engine still performs a full
     * reparse; checkpoint-driven partial reparse is the D.1.2 follow-up.
     */
    public int findCheckpointAncestor(int offset, Set<String> checkpointRules) {
        if (checkpointRules == null) {
            throw new IllegalArgumentException("checkpointRules must not be null");
        }
        if (nodeCount == 0 || rootIndex == NO_NODE) {
            return NO_NODE;
        }
        if (offset < spanStart(rootIndex) || offset >= spanEnd(rootIndex)) {
            return NO_NODE;
        }
        var current = rootIndex;
        outer:
        while (true) {
            var child = firstChildAt(current);
            while (child != NO_NODE) {
                if (offset >= spanStart(child) && offset < spanEnd(child)) {
                    current = child;
                    continue outer;
                }
                child = nextSiblingAt(child);
            }
            break;
        }
        var node = current;
        while (node != NO_NODE) {
            var k = kindAt(node);
            if (k >= 0 && k < ruleTable.length && checkpointRules.contains(ruleTable[k])) {
                return node;
            }
            node = parentAt(node);
        }
        return NO_NODE;
    }

    /**
     * Phase D.1.1 — replace the subtree rooted at {@code oldNodeIdx} with
     * {@code newSubtree}, returning a freshly built {@link CstArray}.
     *
     * <p>SIMPLE-FIRST implementation: rebuilds the entire CST via depth-first
     * traversal through a {@link CstArrayBuilder}. The cost is O(N) in the total
     * node count of the result. A future optimisation (D.1.2 or later) would
     * splice in place, copying only nodes whose indices change.
     *
     * <p>Token-index handling: the spliced subtree spans tokens
     * {@code [oldFirst, oldLast]} in the old token array; the new subtree
     * occupies the same starting token index but contains
     * {@code (oldLast - oldFirst + 1) + tokenDelta} tokens. Token references
     * outside the spliced range are remapped:
     * <ul>
     *   <li>{@code firstToken > oldLast}: shifted by {@code tokenDelta}.</li>
     *   <li>{@code lastToken >= oldLast}: shifted by {@code tokenDelta}.</li>
     *   <li>Otherwise: copied verbatim (descendants entirely before the splice;
     *       and ancestor first-tokens that lie within or before the splice
     *       range, which keep their textual position).</li>
     * </ul>
     *
     * @param oldNodeIdx index of the node in this CST to replace
     * @param newSubtree freshly parsed CST whose {@link #rootIndex()} is the
     *     replacement subtree (the entire CST is taken as the new subtree)
     * @param newTokens  token array for the result CST; typically the output of
     *     {@link org.pragmatica.peg.v6.token.TokenArray#spliceLex(
     *     org.pragmatica.peg.v6.token.LexFn, int, int, String) TokenArray.spliceLex}
     * @param tokenDelta {@code newTokens.count() - this.tokens().count()};
     *     equivalently the change in size of the spliced subtree's token span
     * @throws IllegalArgumentException when {@code oldNodeIdx} is out of bounds,
     *     when arguments are null, or when {@code newSubtree.ruleTable()} differs
     *     from this CST's rule table (must share the same rule-id space)
     */
    public CstArray spliceSubtree(int oldNodeIdx,
                                  CstArray newSubtree,
                                  TokenArray newTokens,
                                  int tokenDelta) {
        if (newSubtree == null) {
            throw new IllegalArgumentException("newSubtree must not be null");
        }
        if (newTokens == null) {
            throw new IllegalArgumentException("newTokens must not be null");
        }
        checkIndex(oldNodeIdx);
        if (!java.util.Arrays.equals(ruleTable, newSubtree.ruleTable)) {
            throw new IllegalArgumentException(
                "newSubtree must share the same ruleTable as this CST");
        }
        var newSubtreeRoot = newSubtree.rootIndex();
        if (newSubtreeRoot == NO_NODE) {
            throw new IllegalArgumentException("newSubtree must have a root");
        }

        var oldFirst = firstTokenAt(oldNodeIdx);
        var oldLast = lastTokenAt(oldNodeIdx);

        var builder = new CstArrayBuilder(newTokens.input(), newTokens, ruleTable);
        var newRoot = rebuildWithSubtree(builder, this, rootIndex,
            oldNodeIdx, oldFirst, oldLast,
            newSubtree, newSubtreeRoot, tokenDelta, NO_NODE);
        return builder.build(newRoot);
    }

    /**
     * Walks the old CST in DFS pre-order through {@code builder}. When the walk
     * reaches {@code targetOldIdx}, the new subtree is grafted in; otherwise the
     * current node is copied with token indices remapped per the rules in
     * {@link #spliceSubtree}.
     */
    private static int rebuildWithSubtree(CstArrayBuilder builder,
                                          CstArray oldCst,
                                          int currentOldIdx,
                                          int targetOldIdx,
                                          int oldFirst,
                                          int oldLast,
                                          CstArray newSubtree,
                                          int newSubtreeRoot,
                                          int tokenDelta,
                                          int parentNewIdx) {
        if (currentOldIdx == targetOldIdx) {
            return copySubtreeIntoBuilder(builder, newSubtree, newSubtreeRoot, parentNewIdx, oldFirst);
        }
        var kind = oldCst.kindAt(currentOldIdx);
        var firstTok = oldCst.firstTokenAt(currentOldIdx);
        var lastTok = oldCst.lastTokenAt(currentOldIdx);
        var newFirst = (firstTok > oldLast) ? firstTok + tokenDelta : firstTok;
        var newLast = (lastTok >= oldLast) ? lastTok + tokenDelta : lastTok;

        var thisIdx = builder.beginNode(kind, newFirst, parentNewIdx);
        if (oldCst.isError(currentOldIdx)) {
            builder.setFlag(thisIdx, FLAG_ERROR);
        }

        var child = oldCst.firstChildAt(currentOldIdx);
        while (child != NO_NODE) {
            rebuildWithSubtree(builder, oldCst, child, targetOldIdx,
                oldFirst, oldLast, newSubtree, newSubtreeRoot, tokenDelta, thisIdx);
            child = oldCst.nextSiblingAt(child);
        }
        builder.endNode(thisIdx, newLast);
        return thisIdx;
    }

    /**
     * Copies {@code newSubtree[srcRoot]} (and all descendants) under {@code parentNewIdx}
     * in {@code builder}. Token indices in the new subtree are stored relative to the
     * new subtree's own token array (which starts at byte position {@code 0}); they map
     * directly into the merged token array's {@code [oldFirst, oldFirst + newCount)}
     * window because {@link CstArrayBuilder#build} treats them as opaque indices into
     * its supplied {@link TokenArray}.
     *
     * <p>Wait — we must shift: the new subtree was parsed standalone (its tokens are
     * indexed [0, newCount)), but in the result CST those tokens occupy positions
     * {@code [oldFirst, oldFirst + newCount)}. Therefore every token reference in the
     * new subtree gets shifted by {@code +oldFirst}.
     */
    private static int copySubtreeIntoBuilder(CstArrayBuilder builder,
                                              CstArray newSubtree,
                                              int srcIdx,
                                              int parentNewIdx,
                                              int tokenBaseShift) {
        var kind = newSubtree.kindAt(srcIdx);
        var firstTok = newSubtree.firstTokenAt(srcIdx) + tokenBaseShift;
        var lastTok = newSubtree.lastTokenAt(srcIdx) + tokenBaseShift;

        var thisIdx = builder.beginNode(kind, firstTok, parentNewIdx);
        if (newSubtree.isError(srcIdx)) {
            builder.setFlag(thisIdx, FLAG_ERROR);
        }
        var child = newSubtree.firstChildAt(srcIdx);
        while (child != NO_NODE) {
            copySubtreeIntoBuilder(builder, newSubtree, child, thisIdx, tokenBaseShift);
            child = newSubtree.nextSiblingAt(child);
        }
        builder.endNode(thisIdx, lastTok);
        return thisIdx;
    }

    /**
     * Round-trip reconstruction: append every token's text in order. Equals {@link #input()}
     * byte-for-byte when the lexer covered the whole input (the Phase B gate).
     */
    public String reconstruct() {
        var sb = new StringBuilder(input.length());
        for (var i = 0; i < tokens.count(); i++) {
            sb.append(tokens.textAt(i));
        }
        return sb.toString();
    }

    private CharSequence concatTokenText(IntStream stream) {
        var sb = new StringBuilder();
        stream.forEach((IntConsumer) i -> sb.append(tokens.textAt(i)));
        return sb;
    }

    private void checkIndex(int nodeIdx) {
        if (nodeIdx < 0 || nodeIdx >= nodeCount) {
            throw new IndexOutOfBoundsException(
                "node index " + nodeIdx + " out of bounds [0, " + nodeCount + ")");
        }
    }

    private final class SiblingIterator implements PrimitiveIterator.OfInt {

        private int next;

        SiblingIterator(int first) {
            this.next = first;
        }

        @Override
        public boolean hasNext() {
            return next != NO_NODE;
        }

        @Override
        public int nextInt() {
            if (next == NO_NODE) {
                throw new java.util.NoSuchElementException();
            }
            var current = next;
            next = nodes[current * NODE_STRIDE + OFFSET_NEXT_SIBLING];
            return current;
        }
    }

    private final class DescendantIterator implements PrimitiveIterator.OfInt {

        private int[] stack;
        private int top;

        DescendantIterator(int root) {
            this.stack = new int[Math.max(8, Math.min(nodeCount, 32))];
            this.top = 0;
            this.stack[top++] = root;
        }

        @Override
        public boolean hasNext() {
            return top > 0;
        }

        @Override
        public int nextInt() {
            if (top == 0) {
                throw new java.util.NoSuchElementException();
            }
            var current = stack[--top];
            pushReversed(current);
            return current;
        }

        private void pushReversed(int parent) {
            var first = nodes[parent * NODE_STRIDE + OFFSET_FIRST_CHILD];
            if (first == NO_NODE) {
                return;
            }
            var head = top;
            var c = first;
            while (c != NO_NODE) {
                ensureCapacity(top + 1);
                stack[top++] = c;
                c = nodes[c * NODE_STRIDE + OFFSET_NEXT_SIBLING];
            }
            var i = head;
            var j = top - 1;
            while (i < j) {
                var tmp = stack[i];
                stack[i] = stack[j];
                stack[j] = tmp;
                i++;
                j--;
            }
        }

        private void ensureCapacity(int required) {
            if (required <= stack.length) {
                return;
            }
            var newCap = stack.length;
            while (newCap < required) {
                newCap = newCap << 1;
                if (newCap < 0) {
                    newCap = Integer.MAX_VALUE - 8;
                }
            }
            stack = java.util.Arrays.copyOf(stack, newCap);
        }
    }
}
