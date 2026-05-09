package org.pragmatica.peg.tree;

import java.util.List;
import java.util.Objects;

/**
 * Concrete Syntax Tree node - lossless representation preserving all source details.
 * Can be used for formatting, linting, and round-trip transformations.
 *
 * <p>v0.5.0 (Phase 1.2): every variant carries a stable {@code long id} for
 * downstream incremental-parser machinery (Lever A in
 * {@code docs/incremental/ARCHITECTURE-0.5.0.md} §2). IDs are produced by
 * {@link IdGenerator} during parse and must be excluded from structural
 * equality (§7 R1) so two parses of the same input produce equal trees.
 *
 * <p>v0.5.1 (Cleanup E): {@link Terminal} and {@link Token} carry a
 * {@link StringSpan textSpan} component instead of a fully-materialized
 * {@code String}, deferring substring allocation. The {@code text()} accessor
 * remains a {@link String}-returning method for source-compat with consumers.
 */
public sealed interface CstNode {
    /**
     * Stable identifier within the owning parse-session's lineage.
     *
     * <p>Excluded from {@code equals}/{@code hashCode}: see §7 R1 of the v0.5.0
     * spec. Two structurally identical nodes with different IDs are equal.
     */
    long id();

    /**
     * The source span covered by this node (excluding trivia).
     */
    SourceSpan span();

    /**
     * The rule name that produced this node.
     */
    String rule();

    /**
     * Leading trivia (whitespace/comments before this node).
     */
    List<Trivia> leadingTrivia();

    /**
     * Trailing trivia (whitespace/comments after this node).
     */
    List<Trivia> trailingTrivia();

    /**
     * Terminal node - a leaf that matched literal text.
     *
     * <p>v0.5.1 (Cleanup E): the {@code textSpan} component defers substring
     * materialization. Use {@link #text()} to obtain a {@link String}.
     */
    record Terminal(
    long id,
    SourceSpan span,
    String rule,
    StringSpan textSpan,
    List<Trivia> leadingTrivia,
    List<Trivia> trailingTrivia) implements CstNode {
        /**
         * Convenience constructor accepting a {@link String} for the text. Wraps the
         * string in a fully-materialized {@link StringSpan}, so no substring is taken.
         */
        public Terminal(long id,
                        SourceSpan span,
                        String rule,
                        String text,
                        List<Trivia> leadingTrivia,
                        List<Trivia> trailingTrivia) {
            this(id, span, rule, StringSpan.ofString(text), leadingTrivia, trailingTrivia);
        }

        /**
         * The matched text as a {@link String}. Lazily materializes the underlying
         * {@link StringSpan} on first access.
         */
        public String text() {
            return textSpan.toString();
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Terminal that && Objects.equals(span, that.span) && Objects.equals(rule, that.rule) && Objects.equals(textSpan,
                                                                                                                                          that.textSpan) && Objects.equals(leadingTrivia,
                                                                                                                                                                           that.leadingTrivia) && Objects.equals(trailingTrivia,
                                                                                                                                                                                                                 that.trailingTrivia);
        }

        @Override
        public int hashCode() {
            return Objects.hash(Terminal.class, span, rule, textSpan, leadingTrivia, trailingTrivia);
        }
    }

    /**
     * Non-terminal node - an interior node with children.
     */
    record NonTerminal(
    long id,
    SourceSpan span,
    String rule,
    List<CstNode> children,
    List<Trivia> leadingTrivia,
    List<Trivia> trailingTrivia) implements CstNode {
        @Override
        public boolean equals(Object other) {
            return other instanceof NonTerminal that && Objects.equals(span, that.span) && Objects.equals(rule,
                                                                                                          that.rule) && Objects.equals(children,
                                                                                                                                       that.children) && Objects.equals(leadingTrivia,
                                                                                                                                                                        that.leadingTrivia) && Objects.equals(trailingTrivia,
                                                                                                                                                                                                              that.trailingTrivia);
        }

        @Override
        public int hashCode() {
            return Objects.hash(NonTerminal.class, span, rule, children, leadingTrivia, trailingTrivia);
        }
    }

    /**
     * Token node - result of token boundary operator {@code < >}.
     * Captures the matched text as a single unit.
     *
     * <p>v0.5.1 (Cleanup E): the {@code textSpan} component defers substring
     * materialization. Use {@link #text()} to obtain a {@link String}.
     */
    record Token(
    long id,
    SourceSpan span,
    String rule,
    StringSpan textSpan,
    List<Trivia> leadingTrivia,
    List<Trivia> trailingTrivia) implements CstNode {
        /**
         * Convenience constructor accepting a {@link String} for the text. Wraps the
         * string in a fully-materialized {@link StringSpan}, so no substring is taken.
         */
        public Token(long id,
                     SourceSpan span,
                     String rule,
                     String text,
                     List<Trivia> leadingTrivia,
                     List<Trivia> trailingTrivia) {
            this(id, span, rule, StringSpan.ofString(text), leadingTrivia, trailingTrivia);
        }

        /**
         * The matched text as a {@link String}. Lazily materializes the underlying
         * {@link StringSpan} on first access.
         */
        public String text() {
            return textSpan.toString();
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Token that && Objects.equals(span, that.span) && Objects.equals(rule, that.rule) && Objects.equals(textSpan,
                                                                                                                                       that.textSpan) && Objects.equals(leadingTrivia,
                                                                                                                                                                        that.leadingTrivia) && Objects.equals(trailingTrivia,
                                                                                                                                                                                                              that.trailingTrivia);
        }

        @Override
        public int hashCode() {
            return Objects.hash(Token.class, span, rule, textSpan, leadingTrivia, trailingTrivia);
        }
    }

    /**
     * Error node - represents unparseable input during error recovery.
     * Contains the skipped text and what was expected at this position.
     *
     * @param id              Stable session-scoped identifier
     * @param span            Source span of the error region
     * @param skippedText     The input that couldn't be parsed
     * @param expected        What the parser expected at this position
     * @param leadingTrivia   Trivia before the error
     * @param trailingTrivia  Trivia after the error (usually empty)
     */
    record Error(
    long id,
    SourceSpan span,
    String skippedText,
    String expected,
    List<Trivia> leadingTrivia,
    List<Trivia> trailingTrivia) implements CstNode {
        @Override
        public String rule() {
            return "<error>";
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Error that && Objects.equals(span, that.span) && Objects.equals(skippedText,
                                                                                                    that.skippedText) && Objects.equals(expected,
                                                                                                                                        that.expected) && Objects.equals(leadingTrivia,
                                                                                                                                                                         that.leadingTrivia) && Objects.equals(trailingTrivia,
                                                                                                                                                                                                               that.trailingTrivia);
        }

        @Override
        public int hashCode() {
            return Objects.hash(Error.class, span, skippedText, expected, leadingTrivia, trailingTrivia);
        }
    }
}
