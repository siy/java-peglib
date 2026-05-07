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
import org.pragmatica.peg.incremental.experimental.StableIdNodeIndex;
import org.pragmatica.peg.incremental.experimental.StableIdSplicer;
import org.pragmatica.peg.tree.SourceSpan;
import org.pragmatica.peg.tree.Trivia;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Path D — perf-gate JMH bench. Three-way comparison on the same flat-tree
 * mid-buffer-splice shape used by {@link MidBufferBench}, isolating where the
 * Path D win comes from.
 *
 * <h2>Three arms</h2>
 *
 * <ul>
 *   <li><strong>productionStyle</strong>: original spec §2 algorithm. Fresh
 *       ancestor IDs (via {@link IdTreeSplicer}) and full
 *       {@link IdNodeIndex#applyIncremental} including step 3
 *       sibling-rewire. The baseline.
 *   <li><strong>stableIdNoOpt</strong>: stable ancestor IDs (via
 *       {@link StableIdSplicer}) but unoptimized
 *       {@link IdNodeIndex#applyIncremental}. Measures whether the splicer
 *       change alone pays off — it shouldn't, because step 3 still walks all
 *       direct children of the new (stable-ID) root and rewrites their parent
 *       links to a value that's already correct.
 *   <li><strong>pathD</strong>: stable IDs + optimized
 *       {@link StableIdNodeIndex#applyIncremental}. The actual O(δ)
 *       algorithm — skips ancestor-removal and sibling-rewire entirely.
 * </ul>
 *
 * <h2>Tree shape & edit</h2>
 *
 * <p>Identical to {@link MidBufferBench}: single root with N terminal children;
 * splice replaces the child at index N/2 with a single fresh terminal.
 *
 * <h2>Perf gate</h2>
 *
 * <p>Path D passes the gate iff {@code productionStyle / pathD ≥ 5} at tree
 * size ≥ 1000. NO-GO falls back to investigating further architectural
 * changes (lazy parent-link computation, persistent map structures, etc).
 *
 * <h2>How to run</h2>
 * <pre>{@code
 *   mvn -pl peglib-incremental -am -Pbench -DskipTests package
 *   java -jar peglib-incremental/target/benchmarks.jar PathDBench \
 *     -rf json -rff peglib-incremental/target/path-d.json -i 3 -wi 2 -f 1
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
public class PathDBench {

    private static final List<Trivia> NO_TRIVIA = List.of();

    @Param({"100", "1000", "10000"})
    private int treeSize;

    // --- Shared trial state (built once per @Param value) ---
    private IdCstNode oldRoot;             // fresh-ID-style baseline root (used by productionStyle + stableIdNoOpt — same tree, both splicers operate on it)
    private IdCstNode oldTarget;           // child being replaced
    private List<IdCstNode> oldPath;       // [oldRoot, oldTarget]
    private IdGenerator idGenProd;
    private IdGenerator idGenStable;

    // Pre-built splice results — productionStyle (fresh ancestor IDs).
    private IdCstNode newRoot_freshIds;
    private List<IdCstNode> newPath_freshIds;

    // Pre-built splice results — stable ancestor IDs.
    private IdCstNode newRoot_stableIds;
    private List<IdCstNode> newPath_stableIds;

    // Per-invocation indices (rebuilt fresh because applyIncremental mutates).
    private IdNodeIndex prodIndex;
    private IdNodeIndex stableNoOptIndex;
    private StableIdNodeIndex pathDIndex;

    @Setup(Level.Trial)
    public void buildTrees() {
        idGenProd = new IdGenerator.PerSessionCounter();
        oldRoot = MidBufferTreeBuilder.buildIdTree(treeSize, idGenProd);
        var oldChildren = ((IdCstNode.NonTerminal) oldRoot).children();
        int midIndex = treeSize / 2;
        oldTarget = oldChildren.get(midIndex);
        oldPath = List.of(oldRoot, oldTarget);

        // Pre-build the new pivot once. Same span/text for both arms.
        int targetStart = midIndex * MidBufferTreeBuilder.CHILD_WIDTH;
        var newPivotSpan = new SourceSpan(1, 1, targetStart, 1, 1, targetStart + 13);

        // productionStyle splicer (fresh ancestor IDs). Pre-compute the splice
        // result once — the bench measures only applyIncremental cost.
        IdCstNode newPivotProd = new IdCstNode.Terminal(
            idGenProd.next(), newPivotSpan, "Stmt", "yyy", NO_TRIVIA, NO_TRIVIA);
        var freshSplicer = new IdTreeSplicer(idGenProd);
        var freshResult = freshSplicer.splice(oldPath, newPivotProd);
        newRoot_freshIds = freshResult.newRoot();
        newPath_freshIds = freshResult.newPath();

        // stable-ID splicer. Use a separate generator so ID streams don't
        // interleave; same target span.
        idGenStable = new IdGenerator.PerSessionCounter();
        // Re-prime idGenStable past the existing tree's IDs to avoid collision
        // when StableIdSplicer is asked to allocate (it doesn't, in current
        // impl, but defensive in case future variants do).
        for (int i = 0; i <= treeSize + 2; i++) {
            idGenStable.next();
        }
        IdCstNode newPivotStable = new IdCstNode.Terminal(
            idGenStable.next(), newPivotSpan, "Stmt", "yyy", NO_TRIVIA, NO_TRIVIA);
        var stableSplicer = new StableIdSplicer(idGenStable);
        var stableResult = stableSplicer.splice(oldPath, newPivotStable);
        newRoot_stableIds = stableResult.newRoot();
        newPath_stableIds = stableResult.newPath();
    }

    /**
     * Per-invocation: rebuild all three arms' indices, because
     * {@code applyIncremental} mutates the receiver's parents map. Build cost
     * is identical across arms ({@code O(N)} on the same tree); the bench
     * measures the differential cost of the {@code applyIncremental} call
     * itself.
     */
    @Setup(Level.Invocation)
    public void rebuildIndices() {
        prodIndex = IdNodeIndex.build(oldRoot);
        stableNoOptIndex = IdNodeIndex.build(oldRoot);
        pathDIndex = StableIdNodeIndex.build(oldRoot);
    }

    /**
     * Original spec §2 algorithm: fresh ancestor IDs + step-3 sibling rewire.
     */
    @Benchmark
    public IdNodeIndex productionStyle() {
        return prodIndex.applyIncremental(newRoot_freshIds, oldPath, newPath_freshIds);
    }

    /**
     * Stable IDs but unoptimized applyIncremental — measures whether the
     * splicer change alone is sufficient. (Expectation: no — step 3 still
     * walks every direct child.)
     */
    @Benchmark
    public IdNodeIndex stableIdNoOpt() {
        return stableNoOptIndex.applyIncremental(newRoot_stableIds, oldPath, newPath_stableIds);
    }

    /**
     * Path D — stable IDs + optimized applyIncremental. The actual O(δ)
     * algorithm.
     */
    @Benchmark
    public StableIdNodeIndex pathD() {
        return pathDIndex.applyIncremental(newRoot_stableIds, oldPath, newPath_stableIds);
    }
}
