package org.pragmatica.peg.incremental;

/**
 * Per-session diagnostic counters.
 *
 * <p>Stats are accumulated across the edit lineage: a session derived from a
 * predecessor inherits its counters. This lets tooling (JMH harness, editor
 * diagnostics) observe how often the reparse-boundary walk reaches the root
 * ({@link #fullReparseCount} / {@link #reparseCount} ratio), how large the
 * reparsed subtrees typically are, and the wall time of the last edit.
 *
 * @param reparseCount          total number of non-no-op edits processed by
 *                              this lineage so far (incremental + fallback)
 * @param fullReparseCount      edits that triggered the full-reparse fallback
 *                              (back-reference grammar rule, boundary reached
 *                              root, or the reparsed rule failed to validate)
 * @param lastReparsedRule      rule name of the last subtree spliced in, or
 *                              {@code "<root>"} for full-reparse fallbacks, or
 *                              {@code ""} for the initial session / no-op edits
 * @param lastReparsedNodeCount node count of the last reparsed subtree
 *                              (includes the subtree root)
 * @param lastReparseNanos      wall time of the last edit, in nanoseconds
 * @since 0.3.1
 */
public record Stats(
    int reparseCount,
    int fullReparseCount,
    String lastReparsedRule,
    int lastReparsedNodeCount,
    long lastReparseNanos
) {
    /** Fresh stats for a just-initialized session. */
    public static final Stats INITIAL = new Stats(0, 0, "", 0, 0L);

    /** Last reparse time expressed in milliseconds (convenience for logs). */
    public double lastReparseMs() {
        return lastReparseNanos / 1_000_000.0;
    }
}
