package org.pragmatica.peg.incremental.experimental;

import org.pragmatica.peg.tree.SourceSpan;
import org.pragmatica.peg.tree.Trivia;

import java.util.List;
import java.util.Objects;

/**
 * Sandbox CST node carrying a stable {@code long id}.
 *
 * <p>Phase 0b spike for the v0.5.0 architectural rework
 * (see {@code docs/incremental/ARCHITECTURE-0.5.0.md} §2 Lever A and §7 R1).
 * Mirrors the production {@link org.pragmatica.peg.tree.CstNode} shape and
 * reuses the production {@link SourceSpan} and {@link Trivia} types directly;
 * the sole structural delta is a leading {@code long id} component on every
 * variant.
 *
 * <p><strong>Equality contract (R1 mitigation).</strong> Per spec §7 R1, IDs
 * are <em>metadata</em> and must not participate in identity. Each variant's
 * {@code equals} and {@code hashCode} compare structural fields only and
 * exclude {@code id}. Two nodes of the same variant with identical structure
 * but different IDs are equal and share a hash code; nodes of different
 * variants are never equal even if their structural fields match.
 *
 * <p>This type is sandbox-only — it is not referenced by {@code peglib-core}
 * and will be promoted, reshaped, or deleted at the Phase 0 GO/NO-GO gate.
 *
 * @since 0.5.0
 */
public sealed interface IdCstNode {
    /** Stable identifier within the owning {@code Session}'s lineage. */
    long id();

    /** Source span covered by this node (excluding trivia). */
    SourceSpan span();

    /** Rule name that produced this node. */
    String rule();

    /** Trivia preceding this node. */
    List<Trivia> leadingTrivia();

    /** Trivia following this node. */
    List<Trivia> trailingTrivia();

    /** Terminal node — leaf that matched literal text. */
    record Terminal(long id,
                    SourceSpan span,
                    String rule,
                    String text,
                    List<Trivia> leadingTrivia,
                    List<Trivia> trailingTrivia) implements IdCstNode {
        @Override
        public boolean equals(Object other) {
            return other instanceof Terminal that
                && Objects.equals(span, that.span)
                && Objects.equals(rule, that.rule)
                && Objects.equals(text, that.text)
                && Objects.equals(leadingTrivia, that.leadingTrivia)
                && Objects.equals(trailingTrivia, that.trailingTrivia);
        }

        @Override
        public int hashCode() {
            return Objects.hash(Terminal.class, span, rule, text, leadingTrivia, trailingTrivia);
        }
    }

    /** Non-terminal node — interior node with children. */
    record NonTerminal(long id,
                       SourceSpan span,
                       String rule,
                       List<IdCstNode> children,
                       List<Trivia> leadingTrivia,
                       List<Trivia> trailingTrivia) implements IdCstNode {
        @Override
        public boolean equals(Object other) {
            return other instanceof NonTerminal that
                && Objects.equals(span, that.span)
                && Objects.equals(rule, that.rule)
                && Objects.equals(children, that.children)
                && Objects.equals(leadingTrivia, that.leadingTrivia)
                && Objects.equals(trailingTrivia, that.trailingTrivia);
        }

        @Override
        public int hashCode() {
            return Objects.hash(NonTerminal.class, span, rule, children, leadingTrivia, trailingTrivia);
        }
    }

    /**
     * Token node — result of the token boundary operator {@code < >}.
     * Captures the matched text as a single unit.
     */
    record Token(long id,
                 SourceSpan span,
                 String rule,
                 String text,
                 List<Trivia> leadingTrivia,
                 List<Trivia> trailingTrivia) implements IdCstNode {
        @Override
        public boolean equals(Object other) {
            return other instanceof Token that
                && Objects.equals(span, that.span)
                && Objects.equals(rule, that.rule)
                && Objects.equals(text, that.text)
                && Objects.equals(leadingTrivia, that.leadingTrivia)
                && Objects.equals(trailingTrivia, that.trailingTrivia);
        }

        @Override
        public int hashCode() {
            return Objects.hash(Token.class, span, rule, text, leadingTrivia, trailingTrivia);
        }
    }

    /**
     * Error node — unparseable input region during error recovery.
     * Mirrors the production {@code CstNode.Error} shape; {@link #rule()}
     * returns {@code "<error>"}.
     */
    record Error(long id,
                 SourceSpan span,
                 String skippedText,
                 String expected,
                 List<Trivia> leadingTrivia,
                 List<Trivia> trailingTrivia) implements IdCstNode {
        @Override
        public String rule() {
            return "<error>";
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Error that
                && Objects.equals(span, that.span)
                && Objects.equals(skippedText, that.skippedText)
                && Objects.equals(expected, that.expected)
                && Objects.equals(leadingTrivia, that.leadingTrivia)
                && Objects.equals(trailingTrivia, that.trailingTrivia);
        }

        @Override
        public int hashCode() {
            return Objects.hash(Error.class, span, skippedText, expected, leadingTrivia, trailingTrivia);
        }
    }
}
