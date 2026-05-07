package org.pragmatica.peg.incremental.experimental;

import org.pragmatica.peg.tree.CstNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Walks a production {@link CstNode} tree and emits the corresponding
 * {@link IdCstNode} tree, assigning a stable {@code long id} to every node
 * via the supplied {@link IdGenerator}.
 *
 * <p>Phase 0b sandbox component for the v0.5.0 architectural rework
 * (see {@code docs/incremental/ARCHITECTURE-0.5.0.md} §2 Lever A). It exists
 * to prove the ID-assignment plumbing without disturbing the production
 * {@link CstNode} surface.
 *
 * <p><strong>Order of assignment.</strong> Children are converted before
 * their parents (post-order). The parent therefore always carries an ID
 * strictly greater than any descendant's ID under
 * {@link IdGenerator.PerSessionCounter}. The algorithm does not depend on
 * this; it is a debugging convenience aligned with the spec phrase
 * "ID assignment ... at construction".
 *
 * <p><strong>Trivia handling.</strong> Trivia lists are copied by reference.
 * The production tree treats them as immutable; preserving reference identity
 * lets equality tests assert pre/post identity cheaply and avoids needless
 * allocation.
 *
 * @since 0.5.0
 */
public final class IdCstNodeBuilder {
    private final IdGenerator idGen;

    public IdCstNodeBuilder(IdGenerator idGen) {
        this.idGen = idGen;
    }

    /** Convert {@code source} and every descendant into an {@link IdCstNode}. */
    public IdCstNode build(CstNode source) {
        return switch (source) {
            case CstNode.Terminal t -> buildTerminal(t);
            case CstNode.Token t -> buildToken(t);
            case CstNode.Error e -> buildError(e);
            case CstNode.NonTerminal n -> buildNonTerminal(n);
        };
    }

    private IdCstNode buildTerminal(CstNode.Terminal t) {
        return new IdCstNode.Terminal(idGen.next(), t.span(), t.rule(), t.text(), t.leadingTrivia(), t.trailingTrivia());
    }

    private IdCstNode buildToken(CstNode.Token t) {
        return new IdCstNode.Token(idGen.next(), t.span(), t.rule(), t.text(), t.leadingTrivia(), t.trailingTrivia());
    }

    private IdCstNode buildError(CstNode.Error e) {
        return new IdCstNode.Error(idGen.next(),
                                   e.span(),
                                   e.skippedText(),
                                   e.expected(),
                                   e.leadingTrivia(),
                                   e.trailingTrivia());
    }

    private IdCstNode buildNonTerminal(CstNode.NonTerminal n) {
        // Post-order: convert children first so descendants get lower IDs.
        var sourceChildren = n.children();
        var converted = new ArrayList<IdCstNode>(sourceChildren.size());
        for (CstNode child : sourceChildren) {
            converted.add(build(child));
        }
        return new IdCstNode.NonTerminal(idGen.next(),
                                         n.span(),
                                         n.rule(),
                                         List.copyOf(converted),
                                         n.leadingTrivia(),
                                         n.trailingTrivia());
    }
}
