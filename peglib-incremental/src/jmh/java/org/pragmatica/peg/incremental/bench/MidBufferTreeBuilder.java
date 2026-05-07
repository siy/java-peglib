package org.pragmatica.peg.incremental.bench;

import org.pragmatica.peg.incremental.experimental.IdCstNode;
import org.pragmatica.peg.incremental.experimental.IdGenerator;
import org.pragmatica.peg.incremental.experimental.OffsetDecoupledNode;
import org.pragmatica.peg.incremental.experimental.SpanIndex;
import org.pragmatica.peg.tree.SourceSpan;
import org.pragmatica.peg.tree.Trivia;

import java.util.ArrayList;
import java.util.List;

/**
 * Synthesizes a "long method body" tree shape for {@link MidBufferBench} —
 * a single root with N sibling children, mimicking a long sequence of
 * statement nodes in a Java method body. Mid-buffer edits replace the
 * middle child, exposing the right-of-edit shift cost that
 * {@code TreeSplicer.spliceAndShift} pays in production.
 *
 * <p>Each child has a span of width 10, so child {@code i} spans
 * {@code [i*10, i*10 + 10)}. The mid-buffer edit point is index N/2.
 *
 * <p>The shape is intentionally unbalanced — flat with high fan-out — to
 * stress the right-of-edit path. The Phase 0 spike used a balanced 4-ary
 * tree where the splice depth-3 found the pivot near the leaves before
 * many right siblings; this is why the 67× speedup didn't translate to
 * mid-buffer edits in real source.
 *
 * <p>Sandbox-only; not referenced by {@code peglib-core}.
 *
 * @since 0.5.0
 */
final class MidBufferTreeBuilder {

    static final int CHILD_WIDTH = 10;
    private static final List<Trivia> NO_TRIVIA = List.of();

    private MidBufferTreeBuilder() {}

    /**
     * Build an {@link IdCstNode} tree: Root with {@code childCount} terminal
     * children, child {@code i} at offsets {@code [i*10, i*10+10)}.
     */
    static IdCstNode buildIdTree(int childCount, IdGenerator idGen) {
        var children = new ArrayList<IdCstNode>(childCount);
        for (int i = 0; i < childCount; i++) {
            int start = i * CHILD_WIDTH;
            int end = start + CHILD_WIDTH;
            var span = new SourceSpan(1, 1, start, 1, 1, end);
            children.add(new IdCstNode.Terminal(idGen.next(), span, "Stmt", "x", NO_TRIVIA, NO_TRIVIA));
        }
        var rootSpan = new SourceSpan(1, 1, 0, 1, 1, childCount * CHILD_WIDTH);
        return new IdCstNode.NonTerminal(idGen.next(), rootSpan, "Body", List.copyOf(children), NO_TRIVIA, NO_TRIVIA);
    }

    /**
     * Build an {@link OffsetDecoupledNode} tree with the same shape, plus
     * a populated {@link SpanIndex}.
     */
    static OffsetDecoupledTree buildOffsetDecoupledTree(int childCount, IdGenerator idGen) {
        var spans = new SpanIndex(childCount * 2);
        var children = new ArrayList<OffsetDecoupledNode>(childCount);
        for (int i = 0; i < childCount; i++) {
            int start = i * CHILD_WIDTH;
            int end = start + CHILD_WIDTH;
            long id = idGen.next();
            children.add(new OffsetDecoupledNode.Terminal(id, "Stmt", "x", NO_TRIVIA, NO_TRIVIA));
            spans.put(id, start, end);
        }
        long rootId = idGen.next();
        var root = new OffsetDecoupledNode.NonTerminal(rootId, "Body", List.copyOf(children), NO_TRIVIA, NO_TRIVIA);
        spans.put(rootId, 0, childCount * CHILD_WIDTH);
        return new OffsetDecoupledTree(root, spans);
    }

    record OffsetDecoupledTree(OffsetDecoupledNode root, SpanIndex spans) {}
}
