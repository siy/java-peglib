package org.pragmatica.peg.parser;

import org.pragmatica.peg.tree.CstNode;

/**
 * Result of a partial parse via
 * {@link Parser#parseRuleAt(Class, String, int)}. Bundles the produced CST
 * subtree with the offset where parsing stopped.
 *
 * <p>{@code endOffset} is the absolute offset into the original input where
 * the rule finished matching (exclusive). It equals
 * {@code node.span().end().offset()} plus any trailing trivia consumed by the
 * rule — callers interested in the raw matched range should use
 * {@link CstNode#span()} instead.
 *
 * <p>Introduced in 0.3.0 for cursor-anchored incremental reparsing. See
 * {@code docs/incremental/SPEC.md} §5.6.
 *
 * @param node      the CST subtree produced by the rule
 * @param endOffset absolute input offset where the rule finished matching
 */
public record PartialParse(CstNode node, int endOffset) {}
