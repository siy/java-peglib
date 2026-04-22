/**
 * peglib-formatter v1 — Wadler-style pretty-printer framework for peglib CSTs.
 *
 * <p>Entry points:
 * <ul>
 *   <li>{@link org.pragmatica.peg.formatter.Formatter} — fluent builder + CST
 *       walker; register per-rule {@link org.pragmatica.peg.formatter.FormatterRule}
 *       functions, then call {@code format(CstNode)} to obtain a string.</li>
 *   <li>{@link org.pragmatica.peg.formatter.Doc} / {@link org.pragmatica.peg.formatter.Docs}
 *       — the pretty-print algebra (text / line / softline / group / indent /
 *       concat) and its static builder functions.</li>
 *   <li>{@link org.pragmatica.peg.formatter.TriviaPolicy} — how whitespace /
 *       comments attached to CST nodes are emitted during formatting.</li>
 * </ul>
 *
 * <p>See the module {@code README.md} for a quick start, and
 * {@code docs/PRETTY-PRINTING.md} for design notes.
 *
 * @since 0.3.3
 */
package org.pragmatica.peg.formatter;
