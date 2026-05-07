package org.pragmatica.peg.incremental;

import org.pragmatica.lang.Cause;

/**
 * Failure modes surfaced by an {@link org.pragmatica.peg.incremental.internal.SessionFactory}
 * full-parse, propagated through {@link Session#edit(Edit)} and
 * {@link Session#reparseAll()} as a degraded {@link Session} carrying a
 * single {@link org.pragmatica.peg.tree.CstNode.Error} root (Path A — the
 * public {@code Session.edit}/{@code reparseAll} signatures stay non-Result;
 * callers concerned with parse health inspect {@link Session#parseSuccessful()}
 * and {@link Session#lastParseError()}).
 *
 * <p>Variants:
 * <ul>
 *   <li>{@link ParseFailed} — the backing parser rejected the buffer; carries
 *       the wrapped {@link Cause#message()} from the parser.</li>
 *   <li>{@link NoStartRule} — the grammar has no effective start rule; this
 *       is theoretically unreachable for a grammar that parsed successfully
 *       but is kept as a defensive variant.</li>
 * </ul>
 *
 * @since 0.5.0
 */
@SuppressWarnings("JBCT-SEAL-01")
public sealed interface SessionError extends Cause {
    /**
     * Full-parse failed because the parser rejected the buffer.
     *
     * @param parserMessage the {@link Cause#message()} reported by the
     *                      backing parser, preserved verbatim for diagnostic
     *                      surfaces.
     */
    record ParseFailed(String parserMessage) implements SessionError {
        @Override
        public String message() {
            return "full parse failed: " + parserMessage;
        }
    }

    /**
     * Full-parse failed because the grammar has no effective start rule.
     */
    record NoStartRule() implements SessionError {
        @Override
        public String message() {
            return "full parse failed: No start rule defined in grammar";
        }
    }
}
