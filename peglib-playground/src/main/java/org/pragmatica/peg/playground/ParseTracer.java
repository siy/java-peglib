package org.pragmatica.peg.playground;

import org.pragmatica.peg.tree.CstNode;
import org.pragmatica.peg.v6.cst.CstArray;
import org.pragmatica.peg.v6.token.TokenArray;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

/**
 * Passive recorder of tracer events for a single parse run. The tracer is
 * strictly additive: without explicit calls from the caller, no events are
 * recorded and parse performance is unchanged.
 *
 * <p>Typical usage in the playground server / REPL:
 * <pre>
 *   var tracer = ParseTracer.start();
 *   var result = parser.parse(input);
 *   var walk = tracer.walkCst(result.cst());
 *   var stats = tracer.stats(walk.nodes(), walk.trivia(), result.diagnostics().size());
 * </pre>
 *
 * <p>The tracer is <em>not</em> an engine hook. A parse-time instrumentation
 * hook would require generated-parser changes; this design intentionally
 * avoids that. Rule-entry / success / failure events are synthesised by
 * walking the finished CST (one entry per node), which is a faithful
 * representation of the rules that <em>successfully</em> produced tree
 * content. Backtracked attempts are not visible — this is a known limitation
 * and documented in docs/PLAYGROUND.md.
 *
 * <p>0.6.x note — the packrat counters ({@link #cacheHits()},
 * {@link #cacheMisses()}, {@link #cachePuts()}) and {@link #cutsFired()}
 * remain on the API because {@link Stats} and the playground frontend consume
 * them, but the 0.6.x lex-then-parse pipeline has no memoization and elides
 * cuts at lex time, so nothing increments them during a v6 walk.
 */
public final class ParseTracer {
    /** Rule name reported for error-flagged CST nodes, which carry no grammar rule of their own. */
    private static final String ERROR_RULE = "<error>";

    private final long startNanos;
    private final List<TraceRecord> records = new ArrayList<>();
    private int ruleEntries;
    private int cacheHits;
    private int cacheMisses;
    private int cachePuts;
    private int cutsFired;

    private ParseTracer() {
        this.startNanos = System.nanoTime();
    }

    public static ParseTracer start() {
        return new ParseTracer();
    }

    public long elapsedNanos() {
        return System.nanoTime() - startNanos;
    }

    public void recordRuleEnter(String rule, int offset) {
        ruleEntries++ ;
        records.add(TraceRecord.traceRecord(TraceRecord.EventKind.RULE_ENTER, rule, offset, elapsedNanos(), ""));
    }

    public void recordRuleSuccess(String rule, int offset) {
        records.add(TraceRecord.traceRecord(TraceRecord.EventKind.RULE_SUCCESS, rule, offset, elapsedNanos(), ""));
    }

    public void recordRuleFailure(String rule, int offset) {
        records.add(TraceRecord.traceRecord(TraceRecord.EventKind.RULE_FAILURE, rule, offset, elapsedNanos(), ""));
    }

    public void recordCacheHit(String rule, int offset) {
        cacheHits++ ;
        records.add(TraceRecord.traceRecord(TraceRecord.EventKind.CACHE_HIT, rule, offset, elapsedNanos(), ""));
    }

    public void recordCacheMiss(String rule, int offset) {
        cacheMisses++ ;
        records.add(TraceRecord.traceRecord(TraceRecord.EventKind.CACHE_MISS, rule, offset, elapsedNanos(), ""));
    }

    public void recordCachePut(String rule, int offset) {
        cachePuts++ ;
        records.add(TraceRecord.traceRecord(TraceRecord.EventKind.CACHE_PUT, rule, offset, elapsedNanos(), ""));
    }

    public void recordCutFired(String rule, int offset) {
        cutsFired++ ;
        records.add(TraceRecord.traceRecord(TraceRecord.EventKind.CUT_FIRED, rule, offset, elapsedNanos(), "cut"));
    }

    public void note(String detail) {
        records.add(TraceRecord.traceRecord(TraceRecord.EventKind.NOTE, "", - 1, elapsedNanos(), detail));
    }

    public List<TraceRecord> records() {
        return List.copyOf(records);
    }

    public int ruleEntries() {
        return ruleEntries;
    }

    public int cacheHits() {
        return cacheHits;
    }

    public int cacheMisses() {
        return cacheMisses;
    }

    public int cachePuts() {
        return cachePuts;
    }

    public int cutsFired() {
        return cutsFired;
    }

    /**
     * Walk a flat {@link CstArray} and synthesise RULE_ENTER / RULE_SUCCESS
     * events from node structure. Each non-error node contributes one enter
     * event before its children and one success event after them; each
     * error-flagged node contributes a RULE_FAILURE event plus a NOTE naming
     * the skipped region. Call exactly once per parse after the CST is
     * available. Also tallies node and trivia counts.
     *
     * <p>The walk descends through error nodes as well, so {@link WalkResult#nodes()}
     * always agrees with {@link #countNodes(CstArray)} and covers every node
     * reachable from the root.
     */
    public WalkResult walkCst(CstArray cst) {
        return new WalkResult(visitAll(cst),
                              countTrivia(cst));
    }

    private int visitAll(CstArray cst) {
        if (isEmpty(cst)) {
            return 0;
        }
        var walker = new ArrayWalkState(cst);
        walker.visit(cst.rootIndex());
        return walker.nodes;
    }

    public record WalkResult(int nodes, int trivia) {}

    /**
     * Index-based walker over a {@link CstArray}. Classification uses the flat
     * accessors ({@link CstArray#isError}, {@link CstArray#children}) rather
     * than {@link CstArray#viewAt} so no per-node view record is allocated.
     */
    private final class ArrayWalkState {
        private final CstArray cst;
        int nodes;

        ArrayWalkState(CstArray cst) {
            this.cst = cst;
        }

        void visit(int nodeIdx) {
            nodes++ ;
            if (cst.isError(nodeIdx)) {
                visitError(nodeIdx);
            }else {
                visitRule(nodeIdx);
            }
        }

        private void visitError(int nodeIdx) {
            recordRuleFailure(ERROR_RULE,
                              cst.spanStart(nodeIdx));
            note("error region: " + cst.textAt(nodeIdx));
            cst.children(nodeIdx)
               .forEach(this::visit);
        }

        private void visitRule(int nodeIdx) {
            var rule = cst.kindNameAt(nodeIdx);
            var offset = cst.spanStart(nodeIdx);
            recordRuleEnter(rule, offset);
            cst.children(nodeIdx)
               .forEach(this::visit);
            recordRuleSuccess(rule, offset);
        }
    }

    /**
     * Build a {@link Stats} instance by combining tracer-maintained counters
     * with a CST walk result and the number of diagnostics that accompanied
     * the parse.
     */
    public Stats stats(int nodeCount, int triviaCount, int diagnosticCount) {
        long micros = elapsedNanos() / 1000L;
        return new Stats(micros,
                         nodeCount,
                         triviaCount,
                         ruleEntries,
                         cacheHits,
                         cacheMisses,
                         cachePuts,
                         cutsFired,
                         diagnosticCount);
    }

    /**
     * Pretty-print the recorded events to a text string suitable for the
     * CLI REPL output.
     */
    public String pretty() {
        var sb = new StringBuilder();
        sb.append("trace (" + records.size() + " events)\n");
        sb.append(String.format("  rule entries: %d, cache hits: %d, misses: %d, puts: %d, cuts fired: %d%n",
                                ruleEntries,
                                cacheHits,
                                cacheMisses,
                                cachePuts,
                                cutsFired));
        for (var rec : records) {
            sb.append(String.format("  %-14s %-30s @%-5d +%dus %s%n",
                                    rec.kind(),
                                    rec.rule()
                                       .isEmpty()
                                    ? "-"
                                    : rec.rule(),
                                    rec.offset(),
                                    rec.elapsedNanos() / 1000L,
                                    rec.detail()));
        }
        return sb.toString();
    }

    /**
     * THE trivia definition for the playground: the number of DISTINCT trivia
     * tokens in the parse. Every whitespace / comment token is counted exactly
     * once.
     *
     * <p>This must not be computed by summing
     * {@link CstArray#leadingTriviaTokens}/{@link CstArray#trailingTriviaTokens}
     * over nodes. Those scan strictly OUTSIDE a node's token span, so in the
     * 0.6.x flat CST — where a rule node usually spans many tokens rather than
     * one leaf per token — interior trivia is attributed to no node at all
     * (yielding 0), while trivia between two sibling leaves is attributed to
     * both (yielding double). Neither is the count the user is shown.
     *
     * <p>{@link org.pragmatica.peg.playground.v6.PlaygroundEngineV6
     * PlaygroundEngineV6} delegates here for {@link Stats#triviaCount()}, and
     * {@link #walkCst(CstArray)} reports the same number, so the tracer's
     * {@code walk.trivia()} and the engine's stats line cannot drift apart.
     */
    public static int countTrivia(CstArray cst) {
        return countTriviaTokens(cst.tokens());
    }

    /** Trivia tally over a raw token stream; see {@link #countTrivia(CstArray)}. */
    public static int countTriviaTokens(TokenArray tokens) {
        return (int) IntStream.range(0, tokens.count())
                              .filter(tokens::isTrivia)
                              .count();
    }

    /**
     * Count nodes reachable from the root of a {@link CstArray}.
     */
    public static int countNodes(CstArray cst) {
        if (isEmpty(cst)) {
            return 0;
        }
        return (int) cst.descendants(cst.rootIndex())
                        .count();
    }

    /**
     * Classify a token kind to one of the five well-known trivia kinds.
     * Useful for JSON encoding when the frontend wants to distinguish
     * whitespace from the four comment flavours. Non-trivia kinds report
     * {@code "content"}.
     */
    public static String triviaKind(int tokenKind) {
        return switch (tokenKind) {
            case TokenArray.KIND_WHITESPACE -> "whitespace";
            case TokenArray.KIND_LINE_COMMENT -> "line-comment";
            case TokenArray.KIND_BLOCK_COMMENT -> "block-comment";
            case TokenArray.KIND_DOC_LINE_COMMENT -> "doc-line-comment";
            case TokenArray.KIND_DOC_BLOCK_COMMENT -> "doc-block-comment";
            default -> "content";
        };
    }

    private static boolean isEmpty(CstArray cst) {
        return cst.nodeCount() == 0 || cst.rootIndex() == CstArray.NO_NODE;
    }

    // ------------------------------------------------------------------
    // LEGACY 0.5.x SHIM — delete together with PlaygroundEngine / PlaygroundRepl
    // and the org.pragmatica.peg.tree package. These three overloads exist only
    // because PlaygroundEngine still walks the recursive CstNode tree; nothing
    // else references them.
    // ------------------------------------------------------------------

    /**
     * Legacy recursive-CST walk. Superseded by {@link #walkCst(CstArray)}.
     */
    public WalkResult walkCst(CstNode root) {
        var walker = new WalkState();
        walker.visit(root);
        return new WalkResult(walker.nodes, walker.trivia);
    }

    private final class WalkState {
        int nodes;
        int trivia;

        void visit(CstNode node) {
            nodes++ ;
            trivia += node.leadingTrivia()
                          .size() + node.trailingTrivia()
                                       .size();
            int offset = node.span()
                             .startOffset();
            switch (node) {
                case CstNode.NonTerminal nt -> {
                    recordRuleEnter(nt.rule(), offset);
                    for (var child : nt.children()) {
                        visit(child);
                    }
                    recordRuleSuccess(nt.rule(), offset);
                }
                case CstNode.Terminal t -> {
                    recordRuleEnter(t.rule(), offset);
                    recordRuleSuccess(t.rule(), offset);
                }
                case CstNode.Token tk -> {
                    recordRuleEnter(tk.rule(), offset);
                    recordRuleSuccess(tk.rule(), offset);
                }
                case CstNode.Error err -> {
                    recordRuleFailure(ERROR_RULE, offset);
                    note("error region: " + err.skippedText());
                }
            }
        }
    }

    /**
     * Legacy recursive trivia tally. Superseded by {@link #countTrivia(CstArray)}.
     */
    public static int countTrivia(CstNode root) {
        int count = root.leadingTrivia()
                        .size() + root.trailingTrivia()
                                     .size();
        if (root instanceof CstNode.NonTerminal nt) {
            for (var child : nt.children()) {
                count += countTrivia(child);
            }
        }
        return count;
    }

    /**
     * Legacy recursive node tally. Superseded by {@link #countNodes(CstArray)}.
     */
    public static int countNodes(CstNode root) {
        int count = 1;
        if (root instanceof CstNode.NonTerminal nt) {
            for (var child : nt.children()) {
                count += countNodes(child);
            }
        }
        return count;
    }
}
