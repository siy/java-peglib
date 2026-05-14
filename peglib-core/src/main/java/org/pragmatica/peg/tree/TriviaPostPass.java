package org.pragmatica.peg.tree;

import org.pragmatica.peg.grammar.Expression;
import org.pragmatica.peg.grammar.Grammar;
import org.pragmatica.peg.grammar.analysis.ExpressionShape;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Step 3 prototype — context-independent trivia attribution.
 *
 * <p>Given {@code (input, cst, grammar)}, produce a NEW CST with the same
 * structure (rule names, spans, IDs, children order) but with leading/trailing
 * trivia <b>re-derived from coordinates</b>: each gap between adjacent node
 * spans is re-scanned against the grammar's {@code %whitespace} rule and
 * attributed by a single deterministic policy.
 *
 * <p>This is the prototype validating the hypothesis that trivia attribution
 * is a function of {@code (input, rule, span)} and does not require the
 * navigational buffer/save/restore machinery currently in {@code PegEngine}.
 *
 * <h2>Attribution policy</h2>
 *
 * <p>The post-pass uses a single policy: <em>leading trivia is the gap before
 * a node; trailing trivia drains into the last-terminal descendant of a
 * non-terminal, matching the engine's Bug C' compensation; terminals never
 * own trailing unless they receive drained orphan trivia.</em>
 *
 * <p>The structural shape of the post-pass CST therefore matches the engine:
 * orphan trivia consumed before a zero-width tail element (empty
 * {@code ZeroOrMore} / {@code Optional}) is drained into the <b>last
 * terminal descendant's trailingTrivia</b> via the recursive helper
 * {@link #attachTrailingToTail}, mirroring {@code PegEngine.attachTrailingToTail}.
 * This keeps {@code CstReconstruct.emit} (which only emits trailing for the
 * last child of a wrapper) byte-equal to the original input even when the
 * orphan-bearing wrapper is not the rightmost child of its parent.
 *
 * <h2>Whitespace scanning</h2>
 *
 * <p>{@link #scanWhitespace(String, int, int, Grammar)} runs the inner
 * expression of {@code %whitespace} (extracted via
 * {@link ExpressionShape#extractInnerExpression(Expression)}) repeatedly over
 * {@code input[from..to)} and emits one {@link Trivia} per successful match.
 * Each chunk is classified by content prefix (matches the engine's
 * {@code classifyTrivia}):
 * <ul>
 *   <li>starts with {@code "//"} &rarr; {@link Trivia.LineComment}</li>
 *   <li>starts with {@code "/*"} &rarr; {@link Trivia.BlockComment}</li>
 *   <li>otherwise &rarr; {@link Trivia.Whitespace}</li>
 * </ul>
 *
 * <p>The matcher is a self-contained recursive evaluator covering the
 * expression types used in {@code %whitespace} rules across the corpus
 * (Literal, CharClass, Any, Reference, Sequence, Choice, ZeroOrMore,
 * OneOrMore, Optional, And, Not, Group, Repetition). It is deliberately
 * simpler than the production engine: no packrat cache, no left recursion,
 * no error recovery, no actions — none of which apply to whitespace rules.
 *
 * @since 0.5.1 (Step 3 prototype)
 */
public final class TriviaPostPass {
    private TriviaPostPass() {}

    /**
     * Re-derive leading/trailing trivia for every node in {@code cst} from
     * {@code (input, span)} coordinates. Returns a new tree with identical
     * structure (id, span, rule, children order preserved) but trivia
     * attribution determined by {@link #scanWhitespace}.
     *
     * <p>If {@code grammar.whitespace()} is empty (no {@code %whitespace}
     * directive), all trivia lists in the result are empty.
     *
     * <p>Equivalent to {@link #assignTrivia(String, CstNode, Grammar, int)}
     * with {@code leadingScanFrom = 0} (full-document scan).
     *
     * @param input   full source text
     * @param cst     root CST node from a previous parse
     * @param grammar the grammar that produced {@code cst}; used only to
     *                resolve {@code %whitespace} and (transitively) any
     *                rules referenced by it
     * @return new CST with re-attributed trivia
     */
    public static CstNode assignTrivia(String input, CstNode cst, Grammar grammar) {
        return assignTrivia(input, cst, grammar, 0);
    }

    /**
     * Same as {@link #assignTrivia(String, CstNode, Grammar)} but constrains
     * the leading-trivia scan window of the root CST to start at
     * {@code leadingScanFrom} instead of 0.
     *
     * <p>Use this overload when {@code cst} represents a partial-reparse
     * subtree and the caller knows the splice point: scanning from offset 0
     * would attribute all preceding trivia to the subtree's leading slot.
     * Supplying the splice offset clamps the scan window to
     * {@code [leadingScanFrom, root.span.start)}, matching the gap between
     * the previous sibling and the spliced subtree.
     *
     * <p>Inner-node leading scans (between siblings inside the subtree) use
     * the standard previous-sibling-end coordinate and are not affected by
     * this argument. The root's trailing-trivia scan (post-EOF window) is
     * also unaffected.
     *
     * @param input            the source text the parse was performed against
     * @param cst              the parsed CST (full or partial-reparse subtree)
     * @param grammar          grammar (for {@code %whitespace} access)
     * @param leadingScanFrom  offset where root-level leading scan begins;
     *                         must satisfy
     *                         {@code 0 <= leadingScanFrom <= cst.span().startOffset()}
     * @return new CST with re-attributed trivia
     * @throws IllegalArgumentException when {@code leadingScanFrom} is
     *         negative or greater than the root span's start offset
     */
    public static CstNode assignTrivia(String input, CstNode cst, Grammar grammar, int leadingScanFrom) {
        int rootStart = cst.span()
                           .startOffset();
        if (leadingScanFrom < 0 || leadingScanFrom > rootStart) {
            throw new IllegalArgumentException(
            "leadingScanFrom " + leadingScanFrom + " out of range [0, " + rootStart + "]");
        }
        // Pre-compute line-start table once per pass — O(N). Eliminates
        // O(N²) re-scan that the previous per-chunk computeSpan performed.
        int[] lineStarts = buildLineStarts(input);
        return rebuildRoot(input, cst, grammar, lineStarts, leadingScanFrom);
    }

    /** Build a 1-based line-start offset table. {@code lineStarts[0] = 0} (line 1). */
    private static int[] buildLineStarts(String input) {
        int len = input.length();
        // Worst case: every char is '\n' → len+1 entries. Common case: ~1 per ~30 chars.
        var starts = new int[Math.max(8, len / 16 + 4)];
        int n = 0;
        starts[n++ ] = 0;
        for (int i = 0; i < len; i++ ) {
            if (input.charAt(i) == '\n') {
                if (n == starts.length) {
                    starts = Arrays.copyOf(starts, starts.length * 2);
                }
                starts[n++ ] = i + 1;
            }
        }
        return Arrays.copyOf(starts, n);
    }

    /** Find {@code [line, col]} (1-based) for {@code offset} via binary search into {@code lineStarts}. */
    private static int[] lineColAt(int[] lineStarts, int offset) {
        int idx = Arrays.binarySearch(lineStarts, offset);
        if (idx < 0) idx = - idx - 2;
        // idx is now the index of the greatest lineStart <= offset.
        int line = idx + 1;
        int col = offset - lineStarts[idx] + 1;
        return new int[]{line, col};
    }

    /**
     * Scan {@code input[from..to)} for trivia chunks, segmented by repeated
     * application of the inner expression of {@code grammar.whitespace()}.
     * Returns an empty list when the grammar has no {@code %whitespace}
     * directive or when {@code from >= to}.
     */
    public static List<Trivia> scanWhitespace(String input, int from, int to, Grammar grammar) {
        if (from >= to || grammar.whitespace()
                                 .isEmpty()) {
            return List.of();
        }
        // Public API: build a fresh line-start table for one-off callers.
        // Internal users (assignTrivia recursion) call scanWhitespaceFast
        // with a shared, pre-built lineStarts array.
        return scanWhitespaceFast(input, from, to, grammar, buildLineStarts(input));
    }

    /**
     * Internal fast variant of {@link #scanWhitespace}: takes a precomputed
     * line-start table to avoid the O(N²) re-scan-from-zero that the previous
     * implementation incurred for every trivia chunk.
     */
    private static List<Trivia> scanWhitespaceFast(String input,
                                                   int from,
                                                   int to,
                                                   Grammar grammar,
                                                   int[] lineStarts) {
        if (from >= to || grammar.whitespace()
                                 .isEmpty()) {
            return List.of();
        }
        var inner = ExpressionShape.extractInnerExpression(grammar.whitespace()
                                                                  .unwrap());
        var trivia = new ArrayList<Trivia>();
        int pos = from;
        while (pos < to) {
            int matched = matchExpression(input, pos, to, inner, grammar);
            if (matched < 0 || matched == pos) {
                break;
            }
            var text = input.substring(pos, matched);
            var span = computeSpanFast(lineStarts, pos, matched);
            trivia.add(classify(span, text));
            pos = matched;
        }
        return List.copyOf(trivia);
    }

    // === Tree rebuild ===
    private static CstNode rebuildRoot(String input,
                                       CstNode root,
                                       Grammar grammar,
                                       int[] lineStarts,
                                       int leadingScanFrom) {
        int rootStart = root.span()
                            .startOffset();
        int rootEnd = root.span()
                          .endOffset();
        var leading = scanWhitespaceFast(input, leadingScanFrom, rootStart, grammar, lineStarts);
        // Root trailing covers the gap from rootEnd to end-of-input (matches
        // current engine behaviour: top-level wrapper carries post-EOF trivia).
        var trailingExternal = scanWhitespaceFast(input, rootEnd, input.length(), grammar, lineStarts);
        return rebuildSelf(input, root, grammar, lineStarts, leading, trailingExternal, List.of());
    }

    /**
     * Rebuild a node with explicit leading/extra-trailing computed by the
     * caller. {@code extraTrailing} sticks on this node's own trailing slot
     * (used only at the root to capture post-EOF trivia). {@code drainExtra}
     * is the orphan-internal-trailing drained from an outer wrapper that
     * must reach the deepest-rightmost-leaf descendant — H1 optimization
     * eliminates the post-construction spine rebuild by threading it during
     * recursion.
     *
     * <p>Cleanup D fast-path: when the rebuild would produce a node with the
     * same content as the input (all incoming trivia lists empty AND existing
     * trivia already empty for leaves), return the input unchanged. For
     * NonTerminals, the same logic is applied per-child inside
     * {@link #rebuildNonTerminal}.
     */
    private static CstNode rebuildSelf(String input,
                                       CstNode node,
                                       Grammar grammar,
                                       int[] lineStarts,
                                       List<Trivia> leading,
                                       List<Trivia> extraTrailing,
                                       List<Trivia> drainExtra) {
        return switch (node) {
            case CstNode.NonTerminal nt -> rebuildNonTerminal(input,
                                                              nt,
                                                              grammar,
                                                              lineStarts,
                                                              leading,
                                                              extraTrailing,
                                                              drainExtra);
            case CstNode.Terminal t -> rebuildTerminal(t, leading, extraTrailing, drainExtra);
            case CstNode.Token tk -> rebuildToken(tk, leading, extraTrailing, drainExtra);
            case CstNode.Error e -> rebuildError(e, leading, extraTrailing, drainExtra);
        };
    }

    /**
     * Cleanup D: skip allocation when the rebuild would produce a leaf
     * identical to the input. The leaf result has {@code leading} as
     * leadingTrivia and {@code combine(drainExtra, extraTrailing)} as
     * trailing. When all three inputs are empty AND the input leaf already
     * has empty leading/trailing, the rebuilt node would be content-equal
     * to the input — return the input reference and avoid allocating.
     */
    private static CstNode.Terminal rebuildTerminal(CstNode.Terminal t,
                                                    List<Trivia> leading,
                                                    List<Trivia> extraTrailing,
                                                    List<Trivia> drainExtra) {
        if (canSkipLeafRebuild(leading, extraTrailing, drainExtra, t.leadingTrivia(), t.trailingTrivia())) {
            return t;
        }
        return new CstNode.Terminal(t.id(),
                                    t.span(),
                                    t.rule(),
                                    t.textSpan(),
                                    leading,
                                    combine(drainExtra, extraTrailing));
    }

    private static CstNode.Token rebuildToken(CstNode.Token tk,
                                              List<Trivia> leading,
                                              List<Trivia> extraTrailing,
                                              List<Trivia> drainExtra) {
        if (canSkipLeafRebuild(leading, extraTrailing, drainExtra, tk.leadingTrivia(), tk.trailingTrivia())) {
            return tk;
        }
        return new CstNode.Token(tk.id(),
                                 tk.span(),
                                 tk.rule(),
                                 tk.textSpan(),
                                 leading,
                                 combine(drainExtra, extraTrailing));
    }

    private static CstNode.Error rebuildError(CstNode.Error e,
                                              List<Trivia> leading,
                                              List<Trivia> extraTrailing,
                                              List<Trivia> drainExtra) {
        if (canSkipLeafRebuild(leading, extraTrailing, drainExtra, e.leadingTrivia(), e.trailingTrivia())) {
            return e;
        }
        return new CstNode.Error(e.id(),
                                 e.span(),
                                 e.skippedText(),
                                 e.expected(),
                                 leading,
                                 combine(drainExtra, extraTrailing));
    }

    private static boolean canSkipLeafRebuild(List<Trivia> leading,
                                              List<Trivia> extraTrailing,
                                              List<Trivia> drainExtra,
                                              List<Trivia> existingLeading,
                                              List<Trivia> existingTrailing) {
        return leading.isEmpty() && extraTrailing.isEmpty() && drainExtra.isEmpty() && existingLeading.isEmpty() && existingTrailing.isEmpty();
    }

    /**
     * Rebuild a non-root child given its preceding sibling's end offset.
     * {@code drainExtra} is the orphan-internal-trailing of THIS wrapper
     * propagated DOWN into the deepest-rightmost descendant of the last
     * child — eliminates the post-construction
     * {@code attachTrailingToTail} spine rebuild (H1).
     *
     * <p>Cleanup D fast-path: leaves with no incoming trivia and already-empty
     * existing trivia are returned unchanged. NonTerminals delegate to
     * {@link #rebuildNonTerminal}, which has its own skip-when-unchanged path.
     */
    private static CstNode rebuildChild(String input,
                                        CstNode child,
                                        Grammar grammar,
                                        int[] lineStarts,
                                        int prevEnd,
                                        List<Trivia> drainExtra) {
        int childStart = child.span()
                              .startOffset();
        var leading = scanWhitespaceFast(input, prevEnd, childStart, grammar, lineStarts);
        return switch (child) {
            case CstNode.NonTerminal nt -> rebuildNonTerminal(input,
                                                              nt,
                                                              grammar,
                                                              lineStarts,
                                                              leading,
                                                              List.of(),
                                                              drainExtra);
            case CstNode.Terminal t -> rebuildTerminal(t, leading, List.of(), drainExtra);
            case CstNode.Token tk -> rebuildToken(tk, leading, List.of(), drainExtra);
            case CstNode.Error e -> rebuildError(e, leading, List.of(), drainExtra);
        };
    }

    private static CstNode.NonTerminal rebuildNonTerminal(String input,
                                                          CstNode.NonTerminal nt,
                                                          Grammar grammar,
                                                          int[] lineStarts,
                                                          List<Trivia> leading,
                                                          List<Trivia> extraTrailing,
                                                          List<Trivia> drainExtra) {
        int spanStart = nt.span()
                          .startOffset();
        int spanEnd = nt.span()
                        .endOffset();
        var children = nt.children();
        int childCount = children.size();
        if (childCount == 0) {
            // No children: the gap between spanStart and spanEnd is internal
            // trailing of this wrapper. Combine with both caller-supplied
            // wrapper-level extraTrailing and any drained extra from outer
            // wrappers (no deeper descendant to absorb it into).
            var internalTrailing = scanWhitespaceFast(input, spanStart, spanEnd, grammar, lineStarts);
            // Cleanup D: skip allocation if rebuild would yield identical content.
            if (leading.isEmpty() && extraTrailing.isEmpty() && drainExtra.isEmpty() && internalTrailing.isEmpty() && nt.leadingTrivia()
                                                                                                                        .isEmpty() && nt.trailingTrivia()
                                                                                                                                        .isEmpty()) {
                return nt;
            }
            return new CstNode.NonTerminal(nt.id(),
                                           nt.span(),
                                           nt.rule(),
                                           children,
                                           leading,
                                           combine(combine(drainExtra, internalTrailing), extraTrailing));
        }
        // H1: drained orphan trailing flows DOWN through rebuildChild instead
        // of bubbling UP via a second-pass spine rebuild. The wrapper itself
        // keeps only its caller-supplied extraTrailing (post-EOF for root,
        // empty for everyone else). Mirrors PegEngine.attachTrailingToTail
        // (Bug C' compensation): the orphan trivia ends up in the
        // deepest-rightmost-leaf's trailing slot, matching CstReconstruct.emit's
        // last-child-only emission semantics.
        int lastChildEnd = children.get(childCount - 1)
                                   .span()
                                   .endOffset();
        var internalTrailing = scanWhitespaceFast(input, lastChildEnd, spanEnd, grammar, lineStarts);
        // The last child's drainExtra is THIS wrapper's internalTrailing plus
        // any drainExtra threaded from an outer wrapper. extraTrailing stays
        // on the wrapper — only post-EOF root trivia uses that slot.
        var lastChildDrain = combine(drainExtra, internalTrailing);
        // Cleanup D: when no incoming trivia and no internal trailing AND existing
        // wrapper trivia already empty, the rebuild can return nt unchanged IFF
        // every rebuilt child is reference-equal to its input (i.e. each child
        // also took its own skip path). Defer the allocation until we discover
        // the first real change.
        boolean canSkip = leading.isEmpty() && extraTrailing.isEmpty() && drainExtra.isEmpty() && internalTrailing.isEmpty() && nt.leadingTrivia()
                                                                                                                                  .isEmpty() && nt.trailingTrivia()
                                                                                                                                                  .isEmpty();
        ArrayList<CstNode> newChildren = null;
        // 0.6.1 — bug fix: previously cursor=spanStart caused scanWhitespaceFast
        // to fail on the parent's opening delimiter (e.g. '{') and silently drop
        // all trivia between the delimiter and the first child. Advance past any
        // non-whitespace prefix of the gap so the trivia scan starts at a
        // position the %whitespace rule can match.
        // See docs/bugs/first-member-trivia-loss-2026-05-12.md.
        int cursor = spanStart;
        if (childCount > 0) {
            int firstChildStart = children.get(0).span().startOffset();
            while (cursor < firstChildStart && !canStartTrivia(input.charAt(cursor))) {
                cursor++;
            }
        }
        for (int i = 0; i < childCount; i++ ) {
            var c = children.get(i);
            var drainForThis = (i == childCount - 1)
                               ? lastChildDrain
                               : List.<Trivia>of();
            var rebuilt = rebuildChild(input, c, grammar, lineStarts, cursor, drainForThis);
            if (newChildren == null) {
                if (rebuilt != c) {
                    // First divergence: materialize the list with the children
                    // we have so far (all reference-equal to input) plus this one.
                    newChildren = new ArrayList<>(childCount);
                    for (int j = 0; j < i; j++ ) {
                        newChildren.add(children.get(j));
                    }
                    newChildren.add(rebuilt);
                }
            }else {
                newChildren.add(rebuilt);
            }
            cursor = c.span()
                      .endOffset();
        }
        if (newChildren == null && canSkip) {
            // All children unchanged AND wrapper-level rebuild would be a no-op.
            return nt;
        }
        var finalChildren = newChildren == null
                            ? children
                            : List.copyOf(newChildren);
        return new CstNode.NonTerminal(nt.id(), nt.span(), nt.rule(), finalChildren, leading, extraTrailing);
    }

    // 0.6.1 — cheap predicate used by rebuildNonTerminal to skip past a parent's
    // opening syntactic prefix (delimiters like '{', '(', '[') before starting
    // the trivia scan. Returns true when the character could start a whitespace
    // run or a comment under any reasonable PEG %whitespace rule.
    private static boolean canStartTrivia(char c) {
        return c == ' ' || c == '\t' || c == '\r' || c == '\n' || c == '/' || c == '#';
    }

    private static List<Trivia> combine(List<Trivia> a, List<Trivia> b) {
        if (a.isEmpty()) return b;
        if (b.isEmpty()) return a;
        var combined = new ArrayList<Trivia>(a.size() + b.size());
        combined.addAll(a);
        combined.addAll(b);
        return List.copyOf(combined);
    }

    // === Trivia classification ===
    private static Trivia classify(SourceSpan span, String text) {
        if (text.startsWith("//")) {
            return new Trivia.LineComment(span, text);
        }else if (text.startsWith("/*")) {
            return new Trivia.BlockComment(span, text);
        }else {
            return new Trivia.Whitespace(span, text);
        }
    }

    // === Span computation ===
    /**
     * Compute a {@link SourceSpan} for {@code input[from..to)} using a
     * precomputed line-start table. Line/column are 1-based to match
     * {@link SourceLocation#START}.
     *
     * <p>Replaces the previous O(from)-per-call implementation that re-scanned
     * {@code [0, from)} for every trivia chunk, producing O(N²) total work in
     * whitespace-rich source. With a precomputed table built once per pass
     * (O(N)), this is O(log N) per call via binary search.
     */
    private static SourceSpan computeSpanFast(int[] lineStarts, int from, int to) {
        int[] startLc = lineColAt(lineStarts, from);
        int[] endLc = lineColAt(lineStarts, to);
        return new SourceSpan(startLc[0], startLc[1], from, endLc[0], endLc[1], to);
    }

    // === Mini-PEG matcher for %whitespace ===
    /**
     * Try to match {@code expr} against {@code input[pos..limit)}. Returns
     * the absolute offset where the match ends, or {@code -1} on failure.
     * Implements the subset of {@link Expression} operators used by
     * realistic {@code %whitespace} rules. Failures in unsupported branches
     * also return {@code -1} (best-effort, conservative).
     */
    private static int matchExpression(String input, int pos, int limit, Expression expr, Grammar grammar) {
        if (pos > limit) return - 1;
        return switch (expr) {
            case Expression.Literal lit -> matchLiteral(input, pos, limit, lit);
            case Expression.CharClass cc -> matchCharClass(input, pos, limit, cc);
            case Expression.Any _ -> pos < limit
                                     ? pos + 1
                                     : - 1;
            case Expression.Reference ref -> matchReference(input, pos, limit, ref, grammar);
            case Expression.Sequence seq -> matchSequence(input, pos, limit, seq, grammar);
            case Expression.Choice ch -> matchChoice(input, pos, limit, ch, grammar);
            case Expression.ZeroOrMore zom -> matchZeroOrMore(input, pos, limit, zom.expression(), grammar);
            case Expression.OneOrMore oom -> matchOneOrMore(input, pos, limit, oom.expression(), grammar);
            case Expression.Optional opt -> matchOptional(input, pos, limit, opt.expression(), grammar);
            case Expression.Repetition rep -> matchRepetition(input, pos, limit, rep, grammar);
            case Expression.And and -> matchExpression(input, pos, limit, and.expression(), grammar) >= 0
                                       ? pos
                                       : - 1;
            case Expression.Not not -> matchExpression(input, pos, limit, not.expression(), grammar) < 0
                                       ? pos
                                       : - 1;
            case Expression.Group g -> matchExpression(input, pos, limit, g.expression(), grammar);
            case Expression.TokenBoundary tb -> matchExpression(input, pos, limit, tb.expression(), grammar);
            case Expression.Ignore ig -> matchExpression(input, pos, limit, ig.expression(), grammar);
            case Expression.Capture cap -> matchExpression(input, pos, limit, cap.expression(), grammar);
            case Expression.CaptureScope cs -> matchExpression(input, pos, limit, cs.expression(), grammar);
            case Expression.BackReference _ -> - 1;
            // not used in %whitespace
            case Expression.Dictionary _ -> - 1;
            // not used in %whitespace
            case Expression.Cut _ -> pos;
        };
    }

    private static int matchLiteral(String input, int pos, int limit, Expression.Literal lit) {
        var text = lit.text();
        int len = text.length();
        if (pos + len > limit) return - 1;
        if (lit.caseInsensitive()) {
            for (int i = 0; i < len; i++ ) {
                if (Character.toLowerCase(input.charAt(pos + i)) != Character.toLowerCase(text.charAt(i))) {
                    return - 1;
                }
            }
        }else {
            for (int i = 0; i < len; i++ ) {
                if (input.charAt(pos + i) != text.charAt(i)) return - 1;
            }
        }
        return pos + len;
    }

    private static int matchCharClass(String input, int pos, int limit, Expression.CharClass cc) {
        if (pos >= limit) return - 1;
        char c = input.charAt(pos);
        boolean inClass = charClassMatches(cc, c);
        boolean ok = cc.negated()
                     ? !inClass
                     : inClass;
        return ok
               ? pos + 1
               : - 1;
    }

    /** Convert PEG char-class pattern (e.g. " \\t\\n") into a single-char predicate. */
    private static boolean charClassMatches(Expression.CharClass cc, char c) {
        var pat = cc.pattern();
        char target = cc.caseInsensitive()
                      ? Character.toLowerCase(c)
                      : c;
        for (int i = 0; i < pat.length();) {
            char ch = pat.charAt(i);
            char unescaped;
            int next;
            if (ch == '\\' && i + 1 < pat.length()) {
                unescaped = unescape(pat.charAt(i + 1));
                next = i + 2;
            }else {
                unescaped = ch;
                next = i + 1;
            }
            // Range: a-b
            if (next < pat.length() && pat.charAt(next) == '-' && next + 1 < pat.length()) {
                char rangeEndRaw = pat.charAt(next + 1);
                char rangeEnd;
                int after;
                if (rangeEndRaw == '\\' && next + 2 < pat.length()) {
                    rangeEnd = unescape(pat.charAt(next + 2));
                    after = next + 3;
                }else {
                    rangeEnd = rangeEndRaw;
                    after = next + 2;
                }
                char lo = unescaped, hi = rangeEnd;
                if (cc.caseInsensitive()) {
                    lo = Character.toLowerCase(lo);
                    hi = Character.toLowerCase(hi);
                }
                if (target >= lo && target <= hi) return true;
                i = after;
            }else {
                char single = cc.caseInsensitive()
                              ? Character.toLowerCase(unescaped)
                              : unescaped;
                if (target == single) return true;
                i = next;
            }
        }
        return false;
    }

    private static char unescape(char escaped) {
        return switch (escaped) {
            case'n' -> '\n';
            case't' -> '\t';
            case'r' -> '\r';
            case'\\' -> '\\';
            case'\'' -> '\'';
            case'"' -> '"';
            case'0' -> '\0';
            case']' -> ']';
            case'[' -> '[';
            case'-' -> '-';
            default -> escaped;
        };
    }

    private static int matchReference(String input, int pos, int limit, Expression.Reference ref, Grammar grammar) {
        var ruleOpt = grammar.rule(ref.ruleName());
        if (ruleOpt.isEmpty()) return - 1;
        return matchExpression(input,
                               pos,
                               limit,
                               ruleOpt.unwrap()
                                      .expression(),
                               grammar);
    }

    private static int matchSequence(String input, int pos, int limit, Expression.Sequence seq, Grammar grammar) {
        int cursor = pos;
        for (var elt : seq.elements()) {
            cursor = matchExpression(input, cursor, limit, elt, grammar);
            if (cursor < 0) return - 1;
        }
        return cursor;
    }

    private static int matchChoice(String input, int pos, int limit, Expression.Choice ch, Grammar grammar) {
        for (var alt : ch.alternatives()) {
            int r = matchExpression(input, pos, limit, alt, grammar);
            if (r >= 0) return r;
        }
        return - 1;
    }

    private static int matchZeroOrMore(String input, int pos, int limit, Expression body, Grammar grammar) {
        int cursor = pos;
        while (true) {
            int next = matchExpression(input, cursor, limit, body, grammar);
            if (next < 0 || next == cursor) break;
            cursor = next;
        }
        return cursor;
    }

    private static int matchOneOrMore(String input, int pos, int limit, Expression body, Grammar grammar) {
        int first = matchExpression(input, pos, limit, body, grammar);
        if (first < 0 || first == pos) return - 1;
        int cursor = first;
        while (true) {
            int next = matchExpression(input, cursor, limit, body, grammar);
            if (next < 0 || next == cursor) break;
            cursor = next;
        }
        return cursor;
    }

    private static int matchOptional(String input, int pos, int limit, Expression body, Grammar grammar) {
        int next = matchExpression(input, pos, limit, body, grammar);
        return next < 0
               ? pos
               : next;
    }

    private static int matchRepetition(String input, int pos, int limit, Expression.Repetition rep, Grammar grammar) {
        int cursor = pos;
        int count = 0;
        int max = rep.max()
                     .isPresent()
                  ? rep.max()
                       .unwrap()
                  : Integer.MAX_VALUE;
        while (count < max) {
            int next = matchExpression(input, cursor, limit, rep.expression(), grammar);
            if (next < 0 || next == cursor) break;
            cursor = next;
            count++ ;
        }
        return count >= rep.min()
               ? cursor
               : - 1;
    }
}
