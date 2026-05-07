package org.pragmatica.peg.incremental.bench;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.pragmatica.peg.incremental.experimental.IdCstNode;
import org.pragmatica.peg.incremental.experimental.IdGenerator;
import org.pragmatica.peg.incremental.experimental.IdNodeIndex;
import org.pragmatica.peg.incremental.experimental.OffsetDecoupledNode;
import org.pragmatica.peg.incremental.experimental.OffsetDecoupledNodeIndex;
import org.pragmatica.peg.incremental.experimental.OffsetDecoupledSplicer;
import org.pragmatica.peg.incremental.experimental.SpanIndex;
import org.pragmatica.peg.tree.SourceSpan;
import org.pragmatica.peg.tree.Trivia;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Phase 1.0/1.1 — perf-gate JMH bench for path-A (offset decoupling) on
 * mid-buffer edits.
 *
 * <p><strong>Why this bench exists.</strong> The Phase 0 spike measured a
 * 38-67× speedup on a balanced 4-ary tree with a depth-3 splice. That tree
 * shape didn't trigger the right-of-edit deep-copy that
 * {@code TreeSplicer.spliceAndShift} pays for siblings whose offsets must
 * shift. Real source files (long method bodies, sequential top-level
 * declarations) produce wide, sequential children at the spine, and
 * mid-buffer edits force deep-copy of ~half of them. This bench measures
 * that case.
 *
 * <h2>Tree shape</h2>
 *
 * <p>{@code Root[child_0, child_1, ..., child_{N-1}]} — a single
 * NonTerminal root with {@code N} terminal children. Each child spans
 * {@code [i*10, i*10+10)}. Mimics a Java method body of {@code N} statements.
 *
 * <h2>Edit</h2>
 *
 * <p>Replace child at index {@code N/2} with a freshly-built terminal of
 * width 13 (delta = +3). The edit is mid-buffer: ~N/2 siblings to the right
 * have offsets {@code >= editEnd} and need shifting.
 *
 * <h2>Two arms compared</h2>
 *
 * <ul>
 *   <li><strong>productionStyle</strong>: replicates
 *       {@code TreeSplicer.spliceAndShift} on {@link IdCstNode} — for every
 *       child slot, if the child starts at or after {@code editEnd}, deep-copy
 *       it via {@code shiftAll} (rebuild record with new {@link SourceSpan}).
 *       Then run {@link IdNodeIndex#applyIncremental}.
 *   <li><strong>offsetDecoupled</strong>: {@link OffsetDecoupledSplicer#splice}
 *       does the work — the SpanIndex is shifted in place via a single walk
 *       over a primitive {@code long[]}; sibling records are reference-shared.
 *       Then run {@link OffsetDecoupledNodeIndex#applyIncremental}.
 * </ul>
 *
 * <p>The "production-style" arm is implemented inline (rather than calling
 * the production {@code TreeSplicer}) for two reasons: (1) {@code TreeSplicer}
 * works on production {@link org.pragmatica.peg.tree.CstNode}, not
 * {@link IdCstNode}; (2) we need the index update to operate on the same
 * record type for an apples-to-apples comparison. The inlined logic mirrors
 * {@code TreeSplicer.shiftAll} and {@code TreeSplicer.rebuildNonTerminal}
 * exactly.
 *
 * <h2>Perf gate</h2>
 *
 * <p>Path A passes the gate iff {@code productionStyle / offsetDecoupled ≥ 5}
 * at tree size ≥ 1000 children. NO-GO falls back to path B (lazy shift) or
 * path C (accept partial win).
 *
 * <h2>How to run</h2>
 * <pre>{@code
 *   mvn -pl peglib-incremental -am -Pbench -DskipTests package
 *   java -jar peglib-incremental/target/benchmarks.jar MidBufferBench \
 *     -rf json -rff phase1-spanindex.json -i 3 -wi 2 -f 1
 * }</pre>
 *
 * @since 0.5.0
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 2)
@Fork(2)
@State(Scope.Benchmark)
public class MidBufferBench {

    private static final List<Trivia> NO_TRIVIA = List.of();

    @Param({"100", "1000", "10000"})
    private int treeSize;

    // --- production-style arm state ---
    private IdCstNode idRoot;
    private IdCstNode idTarget;        // the child being replaced
    private IdCstNode idNewPivot;
    private IdGenerator idGenProd;     // for ancestor rebuild ids
    private IdNodeIndex idOldIndex;    // pre-built; rebuilt per Level.Invocation
    private int editEnd;
    private int delta;

    // --- offset-decoupled arm state ---
    private OffsetDecoupledNode odRoot;
    private OffsetDecoupledNode odTarget;
    private OffsetDecoupledNode odNewPivot;
    private SpanIndex odBaselineSpans;
    private OffsetDecoupledSplicer odSplicer;
    private OffsetDecoupledNodeIndex odOldIndex; // rebuilt per Level.Invocation
    private IdGenerator idGenOd;

    @Setup(Level.Trial)
    public void buildTrees() {
        // Production-style tree.
        idGenProd = new IdGenerator.PerSessionCounter();
        idRoot = MidBufferTreeBuilder.buildIdTree(treeSize, idGenProd);
        var idChildren = ((IdCstNode.NonTerminal) idRoot).children();
        int midIndex = treeSize / 2;
        idTarget = idChildren.get(midIndex);
        // Replacement: width 13 (delta = +3) at the same start offset.
        int targetStart = midIndex * MidBufferTreeBuilder.CHILD_WIDTH;
        var newPivotSpan = new SourceSpan(1, 1, targetStart, 1, 1, targetStart + 13);
        idNewPivot = new IdCstNode.Terminal(idGenProd.next(), newPivotSpan, "Stmt", "yyy", NO_TRIVIA, NO_TRIVIA);
        editEnd = targetStart + MidBufferTreeBuilder.CHILD_WIDTH; // old end
        delta = 3;

        // Offset-decoupled tree (same shape, fresh ids).
        idGenOd = new IdGenerator.PerSessionCounter();
        var odTree = MidBufferTreeBuilder.buildOffsetDecoupledTree(treeSize, idGenOd);
        odRoot = odTree.root();
        odBaselineSpans = odTree.spans();
        var odChildren = ((OffsetDecoupledNode.NonTerminal) odRoot).children();
        odTarget = odChildren.get(midIndex);
        odNewPivot = new OffsetDecoupledNode.Terminal(idGenOd.next(), "Stmt", "yyy", NO_TRIVIA, NO_TRIVIA);
        // Caller responsibility: register newPivot's span.
        odBaselineSpans.put(odNewPivot.id(), targetStart, targetStart + 13);
        odSplicer = new OffsetDecoupledSplicer(idGenOd);
    }

    /**
     * Per-invocation: rebuild both arm's old indices, because
     * {@link IdNodeIndex#applyIncremental} and
     * {@link OffsetDecoupledNodeIndex#applyIncremental} mutate the receiver's
     * parent map. Cost is the same on both sides ({@code O(N)} build); the
     * bench measures the differential cost of the splice + index update arm.
     */
    @Setup(Level.Invocation)
    public void rebuildIndices() {
        idOldIndex = IdNodeIndex.build(idRoot);
        odOldIndex = OffsetDecoupledNodeIndex.build(odRoot);
    }

    /**
     * Production-style: deep-copy every right-of-edit sibling, rebuild root
     * with new spans, then call {@code applyIncremental} on the index.
     *
     * <p>Mirrors {@code TreeSplicer.spliceAndShift} for a single-level tree —
     * step 1 is the spans-baked-in rebuild that path A claims to eliminate.
     */
    @Benchmark
    public Object productionStyle() {
        // Step 1: build new root with the spliced child + shifted right siblings.
        var oldNT = (IdCstNode.NonTerminal) idRoot;
        var oldChildren = oldNT.children();
        var newChildren = new ArrayList<IdCstNode>(oldChildren.size());
        for (var child : oldChildren) {
            if (child == idTarget) {
                newChildren.add(idNewPivot);
            } else if (child.span().startOffset() >= editEnd) {
                newChildren.add(shiftAll(child, delta));
            } else {
                newChildren.add(child);
            }
        }
        // Rebuild root span: end shifted because old end >= editEnd.
        var oldSpan = oldNT.span();
        var newRootSpan = new SourceSpan(
            oldSpan.startLine(), oldSpan.startColumn(), oldSpan.startOffset(),
            oldSpan.endLine(), oldSpan.endColumn(), oldSpan.endOffset() + delta);
        var newRoot = new IdCstNode.NonTerminal(
            idGenProd.next(), newRootSpan, oldNT.rule(), List.copyOf(newChildren),
            oldNT.leadingTrivia(), oldNT.trailingTrivia());

        // Step 2: incremental index update.
        // oldPath: [idRoot, idTarget]; newPath: [newRoot, idNewPivot].
        return idOldIndex.applyIncremental(newRoot, List.of(idRoot, idTarget), List.of(newRoot, idNewPivot));
    }

    /**
     * Path A: SpanIndex.shift + reference-shared siblings + incremental index update.
     */
    @Benchmark
    public Object offsetDecoupled() {
        var oldPath = List.of(odRoot, odTarget);
        var spliceResult = odSplicer.splice(odBaselineSpans, oldPath, odNewPivot, editEnd, delta);

        return odOldIndex.applyIncremental(spliceResult.newRoot(), oldPath, spliceResult.newPath());
    }

    // --- inline production-style helpers (mirror TreeSplicer.shiftAll) ---

    private static IdCstNode shiftAll(IdCstNode node, int delta) {
        if (delta == 0) {
            return node;
        }
        var span = shiftSpan(node.span(), delta);
        return switch (node) {
            case IdCstNode.Terminal t -> new IdCstNode.Terminal(
                t.id(), span, t.rule(), t.text(), t.leadingTrivia(), t.trailingTrivia());
            case IdCstNode.Token t -> new IdCstNode.Token(
                t.id(), span, t.rule(), t.text(), t.leadingTrivia(), t.trailingTrivia());
            case IdCstNode.Error e -> new IdCstNode.Error(
                e.id(), span, e.skippedText(), e.expected(), e.leadingTrivia(), e.trailingTrivia());
            case IdCstNode.NonTerminal nt -> {
                var shifted = new ArrayList<IdCstNode>(nt.children().size());
                for (var child : nt.children()) {
                    shifted.add(shiftAll(child, delta));
                }
                yield new IdCstNode.NonTerminal(
                    nt.id(), span, nt.rule(), List.copyOf(shifted),
                    nt.leadingTrivia(), nt.trailingTrivia());
            }
        };
    }

    private static SourceSpan shiftSpan(SourceSpan span, int delta) {
        return new SourceSpan(
            span.startLine(), span.startColumn(), span.startOffset() + delta,
            span.endLine(), span.endColumn(), span.endOffset() + delta);
    }
}
