package org.pragmatica.peg.incremental.experimental;

import org.pragmatica.peg.tree.Trivia;

import java.util.List;
import java.util.Objects;

/**
 * Path-A spike CST node — carries a stable {@code long id} but does NOT
 * carry a {@link org.pragmatica.peg.tree.SourceSpan}. Spans live externally
 * in a {@link SpanIndex} keyed by id.
 *
 * <p>This is the design fork of the v0.5.0 architectural rework
 * (see {@code docs/incremental/ARCHITECTURE-0.5.0.md} §2 + the path-A blocker
 * resolution). Mirrors {@link IdCstNode} structurally but with the
 * {@code SourceSpan span} component removed from every variant. Callers
 * resolve offsets via {@code spanIndex.startOffset(node.id())} instead of
 * {@code node.span().startOffset()}.
 *
 * <h2>Why decouple offsets</h2>
 *
 * <p>{@code TreeSplicer.spliceAndShift} (production) deep-copies every sibling
 * subtree right of an edit because the offset shift requires rewriting the
 * embedded {@code SourceSpan} on every record. With offsets external, the
 * splicer reference-shares both flanking subtrees and the shift becomes a
 * single in-place walk over the {@link SpanIndex} primitive array — see
 * {@link OffsetDecoupledSplicer}.
 *
 * <h2>Equality contract</h2>
 *
 * <p>Per spec §7 R1, IDs are metadata and must not participate in identity.
 * Each variant's {@code equals}/{@code hashCode} compares structural fields
 * only and excludes {@code id}. Spans are also excluded from equality (they
 * are not part of the record), which deviates from {@link IdCstNode}; this
 * is intentional — equality on path-A nodes is purely structural, and a span
 * comparator (via {@link SpanIndex}) must be applied explicitly when needed.
 *
 * <h2>Trivia note</h2>
 *
 * <p>Production {@link Trivia} still carries {@link org.pragmatica.peg.tree.SourceSpan}
 * components. For this prove-out we leave Trivia as-is — decoupling Trivia
 * spans from records is a separate scope (a future {@code TriviaIndex}).
 * The bench operates on trees with empty trivia lists, so this does not
 * affect the perf comparison.
 *
 * <h2>Skipped variant</h2>
 *
 * <p>{@code Error} is omitted: the bench fixture is synthetic and the
 * production calculator/Java grammars used by the spike do not produce
 * Error nodes. Adding it is mechanical if the prove-out goes GREEN and
 * the design is migrated.
 *
 * <p>This sandbox class is not referenced by {@code peglib-core} and will
 * be promoted, reshaped, or deleted at the Phase 1.0/1.1 GO/NO-GO gate.
 *
 * @since 0.5.0
 */
public sealed interface OffsetDecoupledNode {
    /** Stable identifier within the owning Session's lineage. */
    long id();

    /** Rule name that produced this node. */
    String rule();

    /** Trivia preceding this node (production type — still carries spans). */
    List<Trivia> leadingTrivia();

    /** Trivia following this node (production type — still carries spans). */
    List<Trivia> trailingTrivia();

    /** Terminal node — leaf that matched literal text. */
    record Terminal(long id,
                    String rule,
                    String text,
                    List<Trivia> leadingTrivia,
                    List<Trivia> trailingTrivia) implements OffsetDecoupledNode {
        @Override
        public boolean equals(Object other) {
            return other instanceof Terminal that
                && Objects.equals(rule, that.rule)
                && Objects.equals(text, that.text)
                && Objects.equals(leadingTrivia, that.leadingTrivia)
                && Objects.equals(trailingTrivia, that.trailingTrivia);
        }

        @Override
        public int hashCode() {
            return Objects.hash(Terminal.class, rule, text, leadingTrivia, trailingTrivia);
        }
    }

    /** Non-terminal node — interior node with children. */
    record NonTerminal(long id,
                       String rule,
                       List<OffsetDecoupledNode> children,
                       List<Trivia> leadingTrivia,
                       List<Trivia> trailingTrivia) implements OffsetDecoupledNode {
        @Override
        public boolean equals(Object other) {
            return other instanceof NonTerminal that
                && Objects.equals(rule, that.rule)
                && Objects.equals(children, that.children)
                && Objects.equals(leadingTrivia, that.leadingTrivia)
                && Objects.equals(trailingTrivia, that.trailingTrivia);
        }

        @Override
        public int hashCode() {
            return Objects.hash(NonTerminal.class, rule, children, leadingTrivia, trailingTrivia);
        }
    }

    /**
     * Token node — result of the token boundary operator {@code < >}.
     * Captures the matched text as a single unit.
     */
    record Token(long id,
                 String rule,
                 String text,
                 List<Trivia> leadingTrivia,
                 List<Trivia> trailingTrivia) implements OffsetDecoupledNode {
        @Override
        public boolean equals(Object other) {
            return other instanceof Token that
                && Objects.equals(rule, that.rule)
                && Objects.equals(text, that.text)
                && Objects.equals(leadingTrivia, that.leadingTrivia)
                && Objects.equals(trailingTrivia, that.trailingTrivia);
        }

        @Override
        public int hashCode() {
            return Objects.hash(Token.class, rule, text, leadingTrivia, trailingTrivia);
        }
    }
}
