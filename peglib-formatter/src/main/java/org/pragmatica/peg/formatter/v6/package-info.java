/**
 * Parallel v6 formatter package — a Wadler-Lindig pretty-printer that walks the
 * 0.6.0 flat-array CST ({@link org.pragmatica.peg.v6.cst.CstArray}) instead of
 * the 0.5.x record-based {@link org.pragmatica.peg.tree.CstNode}.
 *
 * <p>The doc algebra ({@link org.pragmatica.peg.formatter.Doc} /
 * {@link org.pragmatica.peg.formatter.Docs}) and renderer
 * ({@link org.pragmatica.peg.formatter.internal.Renderer}) are reused
 * verbatim; only the walker, configuration, rule, context, and trivia-policy
 * types are v6-specific.
 *
 * <p>Entry points:
 * <ul>
 *   <li>{@link org.pragmatica.peg.formatter.v6.V6Formatter} — main facade.</li>
 *   <li>{@link org.pragmatica.peg.formatter.v6.V6FormatterConfig} — config + builder.</li>
 *   <li>{@link org.pragmatica.peg.formatter.v6.V6FormatterRule} — per-rule formatter.</li>
 *   <li>{@link org.pragmatica.peg.formatter.v6.V6FormatContext} — per-call context.</li>
 *   <li>{@link org.pragmatica.peg.formatter.v6.V6TriviaPolicy} — trivia handling strategy.</li>
 * </ul>
 *
 * @since 0.6.0
 */
package org.pragmatica.peg.formatter.v6;
