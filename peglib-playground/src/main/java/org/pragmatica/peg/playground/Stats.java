package org.pragmatica.peg.playground;

/**
 * Per-parse statistics returned alongside the CST / diagnostics by the
 * playground server and REPL. Cheaply computable from the tracer event
 * log and a node-count walk of the resulting tree.
 *
 * @param timeMicros      wall-clock parse time in microseconds
 * @param nodeCount       total number of CST nodes (terminals + non-terminals + tokens + errors)
 * @param triviaCount     total number of trivia items attached anywhere in the tree
 * @param ruleEntries     total number of rule-entry events the tracer saw
 * @param cacheHits       number of packrat cache hits observed by the tracer
 * @param cacheMisses     number of packrat cache misses observed by the tracer
 * @param cachePuts       number of packrat cache puts observed by the tracer
 * @param cutsFired       number of cut-commit events observed by the tracer
 * @param diagnosticCount number of diagnostics (errors + warnings) attached to the parse result
 */
public record Stats(long timeMicros,
                    int nodeCount,
                    int triviaCount,
                    int ruleEntries,
                    int cacheHits,
                    int cacheMisses,
                    int cachePuts,
                    int cutsFired,
                    int diagnosticCount) {

    public static Stats empty() {
        return new Stats(0L, 0, 0, 0, 0, 0, 0, 0, 0);
    }
}
