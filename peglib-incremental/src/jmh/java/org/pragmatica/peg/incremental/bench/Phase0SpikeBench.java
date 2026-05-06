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
import org.pragmatica.peg.incremental.experimental.IdTreeSplicer;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Phase 0d.2 — perf-gate JMH bench for the v0.5.0 architectural rework
 * (see {@code docs/incremental/ARCHITECTURE-0.5.0.md} §6 Phase 0 and §9
 * perf targets).
 *
 * <p><strong>Central perf claim under test:</strong>
 * {@link IdNodeIndex#applyIncremental} is asymptotically {@code O(δ)} —
 * specifically {@code O(splicedSize + depth × branching)} — and therefore
 * <em>≥5× faster</em> than the {@code O(N)} {@link IdNodeIndex#build} at
 * representative tree sizes (≥1000 nodes). The Phase 0 GO/NO-GO gate fails
 * if this benchmark reports less than 5× speedup at the larger sizes; the
 * spec explicitly accommodates a NO-GO outcome.
 *
 * <h2>Bench design</h2>
 *
 * <p>For each parameterized {@code treeSize}, build a balanced 4-ary tree
 * (mirroring typical PEG CST fan-out) and splice a small replacement subtree
 * in at depth 3. Both benchmarks operate on the SAME post-edit tree:
 *
 * <ul>
 *   <li>{@link #incrementalUpdate} — measures the full
 *       {@link IdNodeIndex#applyIncremental} cost (steps 1-3: remove dead +
 *       insert spliced + rewire siblings). Pre-built {@code oldIndex} is
 *       recreated per invocation because {@code applyIncremental} mutates the
 *       receiver's parent map per Phase 0c semantics.
 *   <li>{@link #fullRebuild} — measures {@link IdNodeIndex#build} on the new
 *       root: the comparison floor representing "rebuild after edit"
 *       (≈ what 0.4.3 does today, modulo IdCstNode + LongLongMap constant
 *       factors).
 * </ul>
 *
 * <p>The {@code Level.Invocation} setup penalty (~tens of ns to allocate a
 * fresh {@code IdNodeIndex}) is well below the bench timings (μs range) and
 * is documented as acceptable by JMH for this class of bench.
 *
 * <h2>Caveats</h2>
 *
 * <ul>
 *   <li>Synthetic balanced tree, branching 4. Real CST topology is irregular;
 *       perf may differ on the 1900-LOC fixture (Phase 1 deliverable).
 *   <li>Depth-3 splice with a leaf-sized pivot. Worse-case (deep splice with
 *       large pivot) is not in this bench.
 *   <li>{@link IdNodeIndex#applyIncremental} mutates the receiver. Production
 *       v0.5.0 will likely use a persistent map to recover snapshot semantics
 *       — that adds cost not measured here.
 * </ul>
 *
 * <h2>How to run</h2>
 * <pre>{@code
 *   mvn -pl peglib-incremental -am -Pbench -DskipTests package
 *   java -jar peglib-incremental/target/benchmarks.jar Phase0SpikeBench \
 *     -rf json -rff phase0-spike.json -i 5 -wi 3 -f 2
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
public class Phase0SpikeBench {

    /** Splice depth — depth 3 puts the pivot at a representative interior. */
    private static final int SPLICE_DEPTH = 3;
    /** Branching factor (typical PEG CST fan-out). */
    private static final int BRANCHING = 4;
    /** Pivot subtree size — leaf. The "small token edit" case in spec §9. */
    private static final int PIVOT_DEPTH = 0;

    @Param({"100", "1000", "10000"})
    private int treeSize;

    private IdCstNode newRoot;
    private List<IdCstNode> oldPath;
    private List<IdCstNode> newPath;
    private IdNodeIndex oldIndex;

    // Held across invocations so we can rebuild oldIndex per invocation
    // without re-running the splicer.
    private IdCstNode oldRoot;

    @Setup(Level.Trial)
    public void buildTrees() {
        var gen = new IdGenerator.PerSessionCounter();
        oldRoot = SyntheticTreeBuilder.buildBalanced(treeSize, BRANCHING, gen);
        var actualOldPath = SyntheticTreeBuilder.findPathAtDepth(oldRoot, SPLICE_DEPTH);
        var pivot = SyntheticTreeBuilder.buildPivot(PIVOT_DEPTH, BRANCHING, gen);

        var splicer = new IdTreeSplicer(gen);
        var spliceResult = splicer.splice(actualOldPath, pivot);

        oldPath = actualOldPath;
        newRoot = spliceResult.newRoot();
        newPath = spliceResult.newPath();
    }

    /**
     * Rebuilt fresh per invocation: {@link IdNodeIndex#applyIncremental}
     * mutates the receiver's parent map. Re-running the bench against an
     * already-mutated index would measure no-op behaviour, not the algorithm
     * under test.
     *
     * <p>JMH's {@code Level.Invocation} adds ~tens of ns of overhead; the
     * bench timings are in μs. Acceptable for a spike.
     */
    @Setup(Level.Invocation)
    public void rebuildOldIndex() {
        oldIndex = IdNodeIndex.build(oldRoot);
    }

    /** The 0.5.0 GO target: O(splicedSize + depth × branching). */
    @Benchmark
    public IdNodeIndex incrementalUpdate() {
        return oldIndex.applyIncremental(newRoot, oldPath, newPath);
    }

    /** The "if NO-GO, rebuild from scratch" floor: O(N). */
    @Benchmark
    public IdNodeIndex fullRebuild() {
        return IdNodeIndex.build(newRoot);
    }
}
