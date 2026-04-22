/**
 * peglib-incremental v1 — cursor-anchored incremental CST reparsing.
 *
 * <p>Public entry points:
 * <ul>
 *   <li>{@link org.pragmatica.peg.incremental.IncrementalParser} — factory that
 *       turns a {@link org.pragmatica.peg.grammar.Grammar} into an incremental
 *       parser.</li>
 *   <li>{@link org.pragmatica.peg.incremental.Session} — immutable per-buffer
 *       state; every {@code edit(...)} / {@code moveCursor(...)} call returns a
 *       new {@code Session} sharing untouched CST subtrees with its predecessor.</li>
 *   <li>{@link org.pragmatica.peg.incremental.Edit} — record describing a single
 *       splice over the current buffer ({@code offset}, {@code oldLen},
 *       {@code newText}).</li>
 *   <li>{@link org.pragmatica.peg.incremental.Stats} — per-session bookkeeping
 *       exposed for diagnostics / JMH.</li>
 * </ul>
 *
 * <p>v1 scope (per {@code docs/incremental/SPEC.md} §9): CST-only, wholesale
 * packrat-cache invalidation on every edit, no incremental action execution.
 * Rules whose grammar expression contains any {@code BackReference} fall back
 * to a full reparse on every edit (SPEC §6.3 / §10 mitigation).
 *
 * @since 0.3.1
 */
package org.pragmatica.peg.incremental;
