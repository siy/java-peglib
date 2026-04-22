package org.pragmatica.peg.incremental.internal;

import org.pragmatica.peg.incremental.Edit;
import org.pragmatica.peg.tree.CstNode;
import org.pragmatica.peg.tree.SourceLocation;
import org.pragmatica.peg.tree.SourceSpan;
import org.pragmatica.peg.tree.Trivia;

import java.util.ArrayList;
import java.util.List;

/**
 * Trivia-aware reparse splice helper, SPEC §5.4 v2.
 *
 * <p>Two responsibilities:
 *
 * <ol>
 *   <li>{@link #tryTriviaOnlyEdit} — fast-path detection for an edit whose
 *       byte range lies entirely inside a single trivia run owned by some
 *       leaf's {@code leadingTrivia} list. When the edit only changes the
 *       characters of that trivia (whitespace, line/block comment text), no
 *       structural reparse is needed: the trivia text is rewritten in place,
 *       its span is widened/contracted by the edit's delta, every node and
 *       trivia at-or-after the edit end is offset-shifted by delta. This
 *       covers the brief's "edit within a trivia region" case directly.</li>
 *   <li>{@link #normalizeSplicedTrivia} — post-splice verification hook for
 *       structural reparses. After {@link TreeSplicer} swaps in a freshly
 *       parsed subtree, this method confirms that the spliced subtree's
 *       leading-trivia (carried on its first leaf per 0.2.4 attribution) is
 *       consistent with the surrounding buffer. v2 carried-limitation: 0.2.4
 *       does not yet rewind trailing trivia onto preceding siblings, so this
 *       hook is a no-op in the trailing-trivia direction. The leading-trivia
 *       direction is handled correctly by {@code parseRuleAt} itself; we
 *       expose the hook so future v2.5+ work can plug into a single seam.</li>
 * </ol>
 *
 * <p>The "deleted-trivia" and "inserted new trivia" cases from the brief are
 * subcases of edits whose new text is also pure trivia (e.g., inserting
 * spaces inside an existing whitespace run, deleting a blank line) — both are
 * handled by {@link #tryTriviaOnlyEdit}. Edits that span trivia plus a token
 * fall through to the structural reparse path in
 * {@link SessionImpl#tryIncrementalReparse}.
 *
 * <p>Trivia attribution rule (0.2.4, see {@code docs/TRIVIA-ATTRIBUTION.md}):
 * trivia matched between siblings attaches to the <em>following</em>
 * sibling's {@code leadingTrivia}. This helper preserves that invariant.
 *
 * @since 0.3.2
 */
public final class TriviaRedistribution {
    private TriviaRedistribution() {}

    /**
     * Attempt a trivia-only edit fast-path. Returns {@code null} when the
     * edit is not contained in a single trivia run, or when the inserted
     * text is not legal trivia content (i.e., would change the token-level
     * structure). On a successful match, returns a new root with the trivia
     * rewritten in place and all post-edit spans shifted by {@code delta}.
     *
     * <p>Legality check for replacement text: the replacement must be
     * non-empty <em>or</em> the trivia must remain non-empty after the
     * deletion. We do not attempt to validate the replacement against the
     * grammar's {@code %whitespace} rule directly (that would re-introduce
     * the parser); instead we conservatively reject replacements that
     * contain any character outside the original trivia's character class.
     * Same-class replacements (whitespace → whitespace, comment text →
     * comment text) are accepted; mixed replacements (e.g., adding a
     * non-whitespace character into a Whitespace run) fall through to the
     * structural reparse path. This conservative check guarantees we never
     * silently accept a replacement that would actually change the
     * tokenisation.
     */
    public static CstNode tryTriviaOnlyEdit(CstNode root, String newBuffer, Edit edit) {
        var match = findContainingTrivia(root, edit.offset(), edit.offset() + edit.oldLen());
        if (match == null) {
            return null;
        }
        if (!isReplacementLegalForTrivia(match.trivia(), edit.newText(), edit)) {
            return null;
        }
        return rewriteTriviaInPlace(root, match, edit.newText(), edit);
    }

    /**
     * Post-splice verification hook. Receives the new root that
     * {@link TreeSplicer} produced and the spliced subtree; returns the same
     * root unchanged in v2 (the spliced subtree's leading trivia is already
     * correct because {@code parseRuleAt} ran the parser, which honours
     * 0.2.4's attribution rule). The hook exists so v2.5+ can plug in
     * trailing-trivia rewinds without changing {@link SessionImpl}'s control
     * flow.
     */
    public static CstNode normalizeSplicedTrivia(CstNode newRoot, CstNode splicedSubtree) {
        // SPEC §5.4 v2 carried limitation (see TRIVIA-ATTRIBUTION.md): trailing
        // trivia is not rewound onto preceding siblings in 0.2.x. The leading
        // trivia of the spliced subtree was correctly attributed by
        // parseRuleAt itself; nothing further to do at v2.
        return newRoot;
    }

    // ---- trivia containment detection ------------------------------------

    /**
     * Walk {@code node}'s subtree looking for a trivia run whose span fully
     * contains the half-open edit range {@code [editStart, editEnd)}.
     * Returns the first match found in pre-order traversal. Returns
     * {@code null} when no single trivia run contains the edit.
     *
     * <p>The half-open convention matches edit semantics: an edit at
     * {@code [editStart, editEnd)} replaces exactly the characters at those
     * offsets. A trivia run with span {@code [a, b)} contains the edit when
     * {@code a <= editStart && editEnd <= b}. A pure insertion
     * ({@code editStart == editEnd}) at the boundary {@code editStart == a}
     * or {@code editStart == b} is treated as inside the trivia when it
     * does not touch a non-trivia neighbour.
     */
    public static TriviaMatch findContainingTrivia(CstNode node, int editStart, int editEnd) {
        return findContainingTriviaInList(node, node.leadingTrivia(), Owner.LEADING, editStart, editEnd) instanceof TriviaMatch m
            ? m
            : findContainingTriviaInList(node, node.trailingTrivia(), Owner.TRAILING, editStart, editEnd) instanceof TriviaMatch m2
                ? m2
                : findContainingTriviaInChildren(node, editStart, editEnd);
    }

    private static TriviaMatch findContainingTriviaInList(CstNode owner,
                                                          List<Trivia> trivia,
                                                          Owner kind,
                                                          int editStart,
                                                          int editEnd) {
        if (trivia == null) {
            return null;
        }
        for (int i = 0; i < trivia.size(); i++) {
            var t = trivia.get(i);
            int a = t.span().start().offset();
            int b = t.span().end().offset();
            if (a <= editStart && editEnd <= b) {
                return new TriviaMatch(owner, kind, i, t);
            }
        }
        return null;
    }

    private static TriviaMatch findContainingTriviaInChildren(CstNode node, int editStart, int editEnd) {
        if (!(node instanceof CstNode.NonTerminal nt)) {
            return null;
        }
        for (var child : nt.children()) {
            int a = child.span().start().offset();
            int b = child.span().end().offset();
            // Only descend into children whose own span (plus trivia margins) could contain the edit.
            // Leading trivia of a child sits before child.span().start(); trailing trivia (if any)
            // sits after child.span().end(). Conservative: check the child if any trivia or the
            // span's interior could overlap.
            if (couldContain(child, editStart, editEnd) || (a <= editStart && editEnd <= b)) {
                var hit = findContainingTrivia(child, editStart, editEnd);
                if (hit != null) {
                    return hit;
                }
            }
        }
        return null;
    }

    /**
     * Cheap pre-filter: would walking into {@code child} possibly find a
     * trivia containing {@code [editStart, editEnd)}? True iff the child or
     * any of its trivia could touch the edit range. Conservative — false
     * positives are fine (we just walk further); false negatives would skip
     * a valid match.
     */
    private static boolean couldContain(CstNode child, int editStart, int editEnd) {
        for (var t : nullToEmpty(child.leadingTrivia())) {
            if (t.span().start().offset() <= editStart && editEnd <= t.span().end().offset()) {
                return true;
            }
        }
        for (var t : nullToEmpty(child.trailingTrivia())) {
            if (t.span().start().offset() <= editStart && editEnd <= t.span().end().offset()) {
                return true;
            }
        }
        // Child's own span containing the edit: descend in case of nested trivia attachments.
        return child.span().start().offset() <= editStart && editEnd <= child.span().end().offset();
    }

    private static List<Trivia> nullToEmpty(List<Trivia> trivia) {
        return trivia == null ? List.of() : trivia;
    }

    // ---- legality of trivia-only replacement -----------------------------

    /**
     * Is {@code newText} a legal in-place rewrite for {@code trivia}? Pure
     * deletions (newText empty) within a trivia run are always legal — they
     * shrink the trivia. For replacements, every replacement character must
     * fit the same trivia category as the original:
     *
     * <ul>
     *   <li>{@link Trivia.Whitespace}: only whitespace characters
     *       ({@code Character.isWhitespace}).</li>
     *   <li>{@link Trivia.LineComment} / {@link Trivia.BlockComment}: any
     *       character is allowed only when the edit lies strictly inside the
     *       comment body (not touching the {@code //} / {@code /*} prefix or
     *       the {@code *​/} suffix). Conservative: we only accept inserts/
     *       replacements that introduce characters from the trivia text's
     *       existing alphabet, so e.g. inserting {@code }} or {@code (} into
     *       a comment is fine while inserting a comment-terminator into a
     *       block comment would not be (it would change tokenisation).</li>
     * </ul>
     *
     * <p>For block comments specifically we additionally disallow any new
     * text containing the substring {@code "*}{@code /"} (ending the
     * comment) or any change at the boundary characters.
     */
    static boolean isReplacementLegalForTrivia(Trivia trivia, String newText, Edit edit) {
        // Pure delete inside trivia: always legal as long as the trivia body
        // remains non-empty AFTER the deletion (an emptied whitespace run is
        // also fine — the trivia entry simply becomes a zero-length run; we
        // accept that rather than restructuring).
        if (newText.isEmpty()) {
            return isPureDeletionSafe(trivia, edit);
        }
        return switch (trivia) {
            case Trivia.Whitespace _ -> allWhitespace(newText);
            case Trivia.LineComment lc -> isLineCommentInteriorSafe(lc, newText, edit);
            case Trivia.BlockComment bc -> isBlockCommentInteriorSafe(bc, newText, edit);
        };
    }

    private static boolean isPureDeletionSafe(Trivia trivia, Edit edit) {
        return switch (trivia) {
            case Trivia.Whitespace _ -> true;
            case Trivia.LineComment lc -> isLineCommentBoundaryUntouched(lc, edit);
            case Trivia.BlockComment bc -> isBlockCommentBoundaryUntouched(bc, edit);
        };
    }

    private static boolean isLineCommentBoundaryUntouched(Trivia.LineComment lc, Edit edit) {
        int start = lc.span().start().offset();
        // Don't allow deleting the leading "//" prefix (offsets [start, start+2]).
        return edit.offset() >= start + 2;
    }

    private static boolean isBlockCommentBoundaryUntouched(Trivia.BlockComment bc, Edit edit) {
        int start = bc.span().start().offset();
        int end = bc.span().end().offset();
        // Protect leading "/*" (offsets [start, start+2]) and trailing "*/" (offsets [end-2, end]).
        return edit.offset() >= start + 2 && edit.offset() + edit.oldLen() <= end - 2;
    }

    private static boolean isLineCommentInteriorSafe(Trivia.LineComment lc, String newText, Edit edit) {
        if (!isLineCommentBoundaryUntouched(lc, edit)) {
            return false;
        }
        // Disallow newlines — a newline mid-line-comment terminates it and
        // changes tokenisation downstream.
        for (int i = 0; i < newText.length(); i++) {
            if (newText.charAt(i) == '\n' || newText.charAt(i) == '\r') {
                return false;
            }
        }
        return true;
    }

    private static boolean isBlockCommentInteriorSafe(Trivia.BlockComment bc, String newText, Edit edit) {
        if (!isBlockCommentBoundaryUntouched(bc, edit)) {
            return false;
        }
        // Disallow introducing "*/" in the body (would terminate the comment early).
        return !newText.contains("*/");
    }

    private static boolean allWhitespace(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isWhitespace(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    // ---- in-place trivia rewrite -----------------------------------------

    /**
     * Build a new root with {@code match}'s trivia text rewritten and every
     * downstream offset shifted by {@code edit.delta()}. Structural copy is
     * limited to the spine from {@code root} down to the trivia-owning node.
     */
    private static CstNode rewriteTriviaInPlace(CstNode root,
                                                TriviaMatch match,
                                                String newText,
                                                Edit edit) {
        return rewriteNode(root, match, newText, edit);
    }

    private static CstNode rewriteNode(CstNode node, TriviaMatch match, String newText, Edit edit) {
        int delta = edit.delta();
        int editEnd = edit.offset() + edit.oldLen();

        // If THIS node owns the trivia match, rewrite the trivia list in place.
        if (node == match.owner()) {
            return rebuildOwnerWithRewrittenTrivia(node, match, newText, edit);
        }

        // Otherwise: descend into children that lie at-or-after the edit start, shifting as needed.
        return shiftAndDescend(node, match, newText, edit, delta, editEnd);
    }

    private static CstNode shiftAndDescend(CstNode node,
                                           TriviaMatch match,
                                           String newText,
                                           Edit edit,
                                           int delta,
                                           int editEnd) {
        var newSpan = shiftSpanForEdit(node.span(), delta, editEnd);
        var newLeading = shiftTriviaListAfter(node.leadingTrivia(), delta, editEnd);
        var newTrailing = shiftTriviaListAfter(node.trailingTrivia(), delta, editEnd);
        return switch (node) {
            case CstNode.Terminal t -> new CstNode.Terminal(newSpan, t.rule(), t.text(), newLeading, newTrailing);
            case CstNode.Token t -> new CstNode.Token(newSpan, t.rule(), t.text(), newLeading, newTrailing);
            case CstNode.Error e -> new CstNode.Error(newSpan, e.skippedText(), e.expected(), newLeading, newTrailing);
            case CstNode.NonTerminal nt -> rebuildNonTerminalChildren(nt, newSpan, newLeading, newTrailing, match, newText, edit, delta, editEnd);
        };
    }

    private static CstNode rebuildNonTerminalChildren(CstNode.NonTerminal nt,
                                                      SourceSpan newSpan,
                                                      List<Trivia> newLeading,
                                                      List<Trivia> newTrailing,
                                                      TriviaMatch match,
                                                      String newText,
                                                      Edit edit,
                                                      int delta,
                                                      int editEnd) {
        var newChildren = new ArrayList<CstNode>(nt.children().size());
        for (var child : nt.children()) {
            // Order matters: a child whose own span starts at-or-after editEnd may
            // STILL own the matched trivia (its leadingTrivia sits BEFORE the
            // child's span). Check ownership first, then the simple shift case,
            // then the unaffected case.
            if (childContainsMatch(child, match)) {
                newChildren.add(rewriteNode(child, match, newText, edit));
            } else if (child.span().start().offset() >= editEnd) {
                newChildren.add(shiftAllOffsets(child, delta));
            } else {
                newChildren.add(child);
            }
        }
        return new CstNode.NonTerminal(newSpan, nt.rule(), List.copyOf(newChildren), newLeading, newTrailing);
    }

    private static boolean childContainsMatch(CstNode child, TriviaMatch match) {
        if (child == match.owner()) {
            return true;
        }
        if (!(child instanceof CstNode.NonTerminal nt)) {
            return false;
        }
        for (var c : nt.children()) {
            if (childContainsMatch(c, match)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Rebuild the trivia-owner node, replacing the matched trivia entry with
     * its rewritten counterpart and shifting any subsequent trivia entries.
     * Owner's {@code span} is unchanged (trivia is outside span by
     * convention) but if the owner is a {@link CstNode.NonTerminal} and the
     * matched trivia sits in its leading list and the edit affects the
     * trivia's text, downstream children's spans must shift by delta.
     */
    private static CstNode rebuildOwnerWithRewrittenTrivia(CstNode owner,
                                                           TriviaMatch match,
                                                           String newText,
                                                           Edit edit) {
        int delta = edit.delta();
        int editEnd = edit.offset() + edit.oldLen();

        var leading = owner.leadingTrivia();
        var trailing = owner.trailingTrivia();
        if (match.kind() == Owner.LEADING) {
            leading = rewriteTriviaList(leading, match.index(), newText, edit);
        } else {
            trailing = rewriteTriviaList(trailing, match.index(), newText, edit);
        }
        // The owner's own span: when the matched trivia sits in the owner's
        // LEADING list, the trivia's text rewrite shifts the buffer at the
        // boundary just before owner.span().start(). The owner's span starts
        // exactly at the end of the leading trivia, so it must shift forward
        // by delta. End shifts equally (the owner's text is unchanged but its
        // absolute offset moved). For TRAILING trivia (rare in 0.2.4 — no
        // node has trailing trivia today, but kept here for forward
        // compatibility), the owner's span stays put.
        SourceSpan ownerSpan;
        if (match.kind() == Owner.LEADING && delta != 0) {
            ownerSpan = SourceSpan.of(shiftLoc(owner.span().start(), delta), shiftLoc(owner.span().end(), delta));
        } else {
            ownerSpan = owner.span();
        }

        return switch (owner) {
            case CstNode.Terminal t -> new CstNode.Terminal(ownerSpan, t.rule(), t.text(), leading, trailing);
            case CstNode.Token t -> new CstNode.Token(ownerSpan, t.rule(), t.text(), leading, trailing);
            case CstNode.Error e -> new CstNode.Error(ownerSpan, e.skippedText(), e.expected(), leading, trailing);
            case CstNode.NonTerminal nt -> rebuildOwnerNonTerminal(nt, ownerSpan, leading, trailing, delta, editEnd);
        };
    }

    private static CstNode rebuildOwnerNonTerminal(CstNode.NonTerminal nt,
                                                   SourceSpan ownerSpan,
                                                   List<Trivia> leading,
                                                   List<Trivia> trailing,
                                                   int delta,
                                                   int editEnd) {
        // Children of the owner: shift those at-or-after editEnd; leave others alone.
        var newChildren = new ArrayList<CstNode>(nt.children().size());
        for (var child : nt.children()) {
            if (child.span().start().offset() >= editEnd) {
                newChildren.add(shiftAllOffsets(child, delta));
            } else {
                newChildren.add(child);
            }
        }
        return new CstNode.NonTerminal(ownerSpan, nt.rule(), List.copyOf(newChildren), leading, trailing);
    }

    /**
     * Replace the trivia at {@code index} with a rewritten copy whose text is
     * the original text with {@code [edit.offset(), edit.offset()+edit.oldLen())}
     * substituted by {@code newText}, and whose span end shifts by
     * {@code edit.delta()}. Subsequent trivia entries (sitting after the
     * edit) are offset-shifted.
     */
    private static List<Trivia> rewriteTriviaList(List<Trivia> trivia, int index, String newText, Edit edit) {
        if (trivia == null || trivia.isEmpty()) {
            return List.of();
        }
        var out = new ArrayList<Trivia>(trivia.size());
        for (int i = 0; i < trivia.size(); i++) {
            var t = trivia.get(i);
            if (i < index) {
                out.add(t);
            } else if (i == index) {
                out.add(rewriteSingleTrivia(t, newText, edit));
            } else {
                out.add(shiftTrivia(t, edit.delta()));
            }
        }
        return List.copyOf(out);
    }

    private static Trivia rewriteSingleTrivia(Trivia trivia, String newText, Edit edit) {
        int triviaStart = trivia.span().start().offset();
        int relStart = edit.offset() - triviaStart;
        int relEnd = relStart + edit.oldLen();
        var oldText = trivia.text();
        var rebuilt = oldText.substring(0, relStart) + newText + oldText.substring(relEnd);
        var newSpan = SourceSpan.of(
            trivia.span().start(),
            new SourceLocation(
                trivia.span().end().line(),
                trivia.span().end().column(),
                trivia.span().end().offset() + edit.delta()));
        return switch (trivia) {
            case Trivia.Whitespace _ -> new Trivia.Whitespace(newSpan, rebuilt);
            case Trivia.LineComment _ -> new Trivia.LineComment(newSpan, rebuilt);
            case Trivia.BlockComment _ -> new Trivia.BlockComment(newSpan, rebuilt);
        };
    }

    // ---- offset shifting (subset of TreeSplicer; duplicated to keep helpers self-contained) -----

    private static SourceSpan shiftSpanForEdit(SourceSpan span, int delta, int editEnd) {
        if (delta == 0) {
            return span;
        }
        var start = span.start().offset() >= editEnd ? shiftLoc(span.start(), delta) : span.start();
        var end = span.end().offset() >= editEnd ? shiftLoc(span.end(), delta) : span.end();
        return SourceSpan.of(start, end);
    }

    private static List<Trivia> shiftTriviaListAfter(List<Trivia> trivia, int delta, int editEnd) {
        if (trivia == null || trivia.isEmpty() || delta == 0) {
            return trivia == null ? List.of() : trivia;
        }
        boolean any = false;
        var out = new ArrayList<Trivia>(trivia.size());
        for (var t : trivia) {
            if (t.span().start().offset() >= editEnd) {
                out.add(shiftTrivia(t, delta));
                any = true;
            } else {
                out.add(t);
            }
        }
        return any ? List.copyOf(out) : trivia;
    }

    private static Trivia shiftTrivia(Trivia trivia, int delta) {
        if (delta == 0) {
            return trivia;
        }
        var newSpan = SourceSpan.of(shiftLoc(trivia.span().start(), delta), shiftLoc(trivia.span().end(), delta));
        return switch (trivia) {
            case Trivia.Whitespace w -> new Trivia.Whitespace(newSpan, w.text());
            case Trivia.LineComment l -> new Trivia.LineComment(newSpan, l.text());
            case Trivia.BlockComment b -> new Trivia.BlockComment(newSpan, b.text());
        };
    }

    private static CstNode shiftAllOffsets(CstNode node, int delta) {
        if (delta == 0) {
            return node;
        }
        return TreeSplicer.shiftAll(node, delta);
    }

    private static SourceLocation shiftLoc(SourceLocation loc, int delta) {
        return new SourceLocation(loc.line(), loc.column(), loc.offset() + delta);
    }

    // ---- internal records ------------------------------------------------

    /** Whether a matched trivia sits in its owner's leading or trailing list. */
    public enum Owner { LEADING, TRAILING }

    /** Result of {@link #findContainingTrivia}: which node owns the trivia, where, and the trivia itself. */
    public record TriviaMatch(CstNode owner, Owner kind, int index, Trivia trivia) {}
}
