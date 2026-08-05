/**
 * peglib-formatter core — the backend-agnostic Wadler-style pretty-printing
 * algebra shared by every peglib formatter.
 *
 * <p>This package holds no CST walker of its own: it contributes the document
 * model and the renderer that turns a document into text. The CST-walking entry
 * point lives in {@link org.pragmatica.peg.formatter.Formatter}, which
 * builds documents from a {@link org.pragmatica.peg.cst.CstArray} and renders
 * them with the types below.
 *
 * <p>Contents:
 * <ul>
 *   <li>{@link org.pragmatica.peg.formatter.Doc} — the pretty-print algebra
 *       (text / line / softline / group / indent / concat) as a sealed
 *       document tree.</li>
 *   <li>{@link org.pragmatica.peg.formatter.Docs} — static builder functions
 *       for composing {@code Doc} values.</li>
 *   <li>{@link org.pragmatica.peg.formatter.internal.Renderer} — the
 *       best-fit layout engine that renders a {@code Doc} to a string at a
 *       given maximum line width.</li>
 * </ul>
 *
 * <p>See the module {@code README.md} for a quick start, and
 * {@code docs/PRETTY-PRINTING.md} for design notes.
 *
 * @since 0.3.3
 */
package org.pragmatica.peg.formatter;
