package org.pragmatica.peg.playground;

/**
 * Single tracer event recorded by {@link ParseTracer} during a parse.
 * Events are append-only and form a chronological log of how the engine
 * walked the grammar.
 *
 * <p>Each event carries the rule name, the input offset at which it
 * occurred, and an {@link EventKind} tag. Events that have no natural
 * input offset (e.g. aggregate counters at parse end) use offset = -1.
 *
 * @param kind    the event category
 * @param rule    the rule name the event belongs to; empty for global events
 * @param offset  input offset at which the event occurred, or -1 if not applicable
 * @param elapsedNanos elapsed time since tracer construction when the event fired
 * @param detail  free-form detail string (e.g. "hit", "put", "cut fired"); may be empty
 */
public record TraceRecord(EventKind kind,
                          String rule,
                          int offset,
                          long elapsedNanos,
                          String detail) {

    public enum EventKind {
        RULE_ENTER,
        RULE_SUCCESS,
        RULE_FAILURE,
        CACHE_HIT,
        CACHE_MISS,
        CACHE_PUT,
        CUT_FIRED,
        NOTE
    }

    public static TraceRecord of(EventKind kind,
                                 String rule,
                                 int offset,
                                 long elapsedNanos,
                                 String detail) {
        return new TraceRecord(kind,
                               rule == null ? "" : rule,
                               offset,
                               elapsedNanos,
                               detail == null ? "" : detail);
    }
}
