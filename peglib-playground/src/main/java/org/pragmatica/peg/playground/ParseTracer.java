package org.pragmatica.peg.playground;

import org.pragmatica.peg.tree.CstNode;
import org.pragmatica.peg.tree.Trivia;

import java.util.ArrayList;
import java.util.List;

/**
 * Passive recorder of tracer events for a single parse run. The tracer is
 * strictly additive: without explicit calls from the caller, no events are
 * recorded and parse performance is unchanged.
 *
 * <p>Typical usage in the playground server / REPL:
 * <pre>
 *   var tracer = ParseTracer.start();
 *   var result = parser.parseCst(input);
 *   tracer.walkCst(result.unwrap());
 *   var stats = tracer.stats(result, diagnosticsSize);
 * </pre>
 *
 * <p>A parse-time instrumentation hook would require engine changes; v1
 * intentionally avoids that. Rule-entry / success / failure events are
 * synthesised from the resulting CST (one entry per non-terminal node),
 * which is a faithful representation of the rules that <em>successfully</em>
 * produced tree content. Backtracked attempts are not visible — this is a
 * known limitation and documented in docs/PLAYGROUND.md.
 */
public final class ParseTracer {
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
        ruleEntries++;
        records.add(TraceRecord.of(TraceRecord.EventKind.RULE_ENTER, rule, offset, elapsedNanos(), ""));
    }

    public void recordRuleSuccess(String rule, int offset) {
        records.add(TraceRecord.of(TraceRecord.EventKind.RULE_SUCCESS, rule, offset, elapsedNanos(), ""));
    }

    public void recordRuleFailure(String rule, int offset) {
        records.add(TraceRecord.of(TraceRecord.EventKind.RULE_FAILURE, rule, offset, elapsedNanos(), ""));
    }

    public void recordCacheHit(String rule, int offset) {
        cacheHits++;
        records.add(TraceRecord.of(TraceRecord.EventKind.CACHE_HIT, rule, offset, elapsedNanos(), ""));
    }

    public void recordCacheMiss(String rule, int offset) {
        cacheMisses++;
        records.add(TraceRecord.of(TraceRecord.EventKind.CACHE_MISS, rule, offset, elapsedNanos(), ""));
    }

    public void recordCachePut(String rule, int offset) {
        cachePuts++;
        records.add(TraceRecord.of(TraceRecord.EventKind.CACHE_PUT, rule, offset, elapsedNanos(), ""));
    }

    public void recordCutFired(String rule, int offset) {
        cutsFired++;
        records.add(TraceRecord.of(TraceRecord.EventKind.CUT_FIRED, rule, offset, elapsedNanos(), "cut"));
    }

    public void note(String detail) {
        records.add(TraceRecord.of(TraceRecord.EventKind.NOTE, "", -1, elapsedNanos(), detail));
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
     * Walk the CST and synthesise RULE_ENTER / RULE_SUCCESS events from
     * node structure. Each non-terminal contributes one enter + one
     * success event (terminals + tokens become enter+success for the
     * rule they carry). Call exactly once per parse after the CST is
     * available. Also tallies node and trivia counts.
     */
    public WalkResult walkCst(CstNode root) {
        var walker = new WalkState();
        walker.visit(root);
        return new WalkResult(walker.nodes, walker.trivia);
    }

    public record WalkResult(int nodes, int trivia) {}

    private final class WalkState {
        int nodes;
        int trivia;

        void visit(CstNode node) {
            nodes++;
            trivia += node.leadingTrivia().size() + node.trailingTrivia().size();
            int offset = node.span().start().offset();
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
                    recordRuleFailure("<error>", offset);
                    note("error region: " + err.skippedText());
                }
            }
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
                                ruleEntries, cacheHits, cacheMisses, cachePuts, cutsFired));
        for (var rec : records) {
            sb.append(String.format("  %-14s %-30s @%-5d +%dus %s%n",
                                    rec.kind(),
                                    rec.rule().isEmpty() ? "-" : rec.rule(),
                                    rec.offset(),
                                    rec.elapsedNanos() / 1000L,
                                    rec.detail()));
        }
        return sb.toString();
    }

    /**
     * Tally trivia on a node recursively; public helper for callers that
     * need the count without constructing a WalkResult.
     */
    public static int countTrivia(CstNode root) {
        int count = root.leadingTrivia().size() + root.trailingTrivia().size();
        if (root instanceof CstNode.NonTerminal nt) {
            for (var child : nt.children()) {
                count += countTrivia(child);
            }
        }
        return count;
    }

    /**
     * Count nodes recursively.
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

    /**
     * Classify a trivia instance to one of the three well-known kinds.
     * Useful for JSON encoding when the frontend wants to distinguish
     * whitespace from comments.
     */
    public static String triviaKind(Trivia trivia) {
        return switch (trivia) {
            case Trivia.Whitespace _ -> "whitespace";
            case Trivia.LineComment _ -> "line-comment";
            case Trivia.BlockComment _ -> "block-comment";
        };
    }
}
