package org.pragmatica.peg.v6.incremental;

import org.pragmatica.peg.v6.Parser;
import org.pragmatica.peg.v6.cst.CstArray;
import org.pragmatica.peg.v6.cst.ParseResult;
import org.pragmatica.peg.v6.diagnostic.Diagnostic;
import org.pragmatica.peg.v6.token.LexFn;
import org.pragmatica.peg.v6.token.TokenArray;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Phase D.2 / D.1.1 — incremental-edit wrapper around a {@link Parser}.
 *
 * <p>Per spec §3.7. The current implementation wires together the Phase D building
 * blocks already in place:
 * <ul>
 *   <li>{@link TokenArray#spliceLex(LexFn, int, int, String)} — windowed re-lex (D.0.1):
 *       only the affected token range is re-lexed; the unaffected prefix and suffix
 *       token spans are spliced around it with offsets shifted by the edit delta.</li>
 *   <li>{@link CstArray#findCheckpointAncestor(int, Set)} — locates the smallest
 *       enclosing checkpoint subtree for the edit (D.1).</li>
 *   <li>{@link CstArray#spliceSubtree(int, CstArray, TokenArray, int)} — replaces an
 *       old subtree with a freshly-parsed one (D.1.1).</li>
 * </ul>
 *
 * <p>The remaining piece is "parse rule X starting at token N" — a parser entry point
 * that does NOT yet exist on the generated parser surface. Until D.1.2 adds it, the
 * D.1.1 implementation calls {@link Parser#parse(String)} on the post-edit input for
 * the parse half. The lex half already uses windowed splice.
 *
 * <p>Instances are stateful: the latest input, tokens, CST, and diagnostics are kept as
 * fields and replaced atomically on every edit. The class is not thread-safe; concurrent
 * edits must be externally synchronised.
 */
public final class IncrementalParser {
    /**
     * Default rule names treated as checkpoints when no grammar-supplied list is
     * available. Hardcoded for D.1.1; the {@code %checkpoint} grammar directive is
     * deferred to D.2.x. Includes the common Java/C-family statement-and-declaration
     * boundaries; if a grammar uses different names, override via
     * {@link #checkpointRules()} (subclassing) or wait for D.2.x.
     */
    public static final Set<String>DEFAULT_CHECKPOINT_RULES = Set.of("Stmt",
                                                                     "Statement",
                                                                     "MethodDecl",
                                                                     "TypeDecl",
                                                                     "ClassMember",
                                                                     "Block");

    private final Parser parser;
    private final Set<String> checkpointRules;
    private final Map<String, Integer> ruleKindByName;
    private String input;
    private TokenArray tokens;
    private CstArray cst;
    private List<Diagnostic> diagnostics;

    /**
     * D.1.2 telemetry — increments every time a partial parse path was taken.
     * Visible to tests via {@link #partialReparseCount()}; not exposed in the
     * public hot path.
     */
    private int partialReparseCount;
    private int fullReparseCount;

    public IncrementalParser(Parser parser, String initialInput) {
        this(parser, initialInput, DEFAULT_CHECKPOINT_RULES);
    }

    public IncrementalParser(Parser parser, String initialInput, Set<String> checkpointRules) {
        Objects.requireNonNull(parser, "parser");
        Objects.requireNonNull(initialInput, "initialInput");
        Objects.requireNonNull(checkpointRules, "checkpointRules");
        this.parser = parser;
        this.checkpointRules = Set.copyOf(checkpointRules);
        this.ruleKindByName = parser.ruleKinds();
        this.input = initialInput;
        var initial = parser.parse(initialInput);
        this.tokens = initial.cst()
                             .tokens();
        this.cst = initial.cst();
        this.diagnostics = initial.diagnostics();
        this.partialReparseCount = 0;
        this.fullReparseCount = 0;
    }

    /**
     * Apply an edit replacing {@code input[offset, offset + oldLen)} with {@code newText}
     * and reparse.
     *
     * <p>D.1.1 wiring:
     * <ol>
     *   <li>Splice the input string.</li>
     *   <li>Run windowed re-lex via {@link TokenArray#spliceLex(LexFn, int, int, String)}
     *       to produce a new {@link TokenArray} byte-for-byte equivalent to a fresh lex
     *       of the post-edit input. (Verified by the parity assertions in the splice
     *       tests.)</li>
     *   <li>Locate the smallest enclosing checkpoint via
     *       {@link CstArray#findCheckpointAncestor(int, Set)} for use by the future
     *       partial-reparse path. Stored as a side observation; the current path still
     *       does a full {@link Parser#parse(String)}.</li>
     * </ol>
     *
     * <p>True partial reparse from a checkpoint requires a parser entry point of the
     * form {@code parseRule(ruleName, tokens, fromToken)} which does not exist on the
     * generated parser surface yet; that is the D.1.2 deliverable.
     *
     * @throws IllegalArgumentException when the edit coordinates are out of bounds or
     *     {@code newText} is null
     */
    public ParseResult edit(int offset, int oldLen, String newText) {
        Objects.requireNonNull(newText, "newText");
        if (offset < 0 || oldLen < 0 || offset + oldLen > input.length()) {
            throw new IllegalArgumentException(
            "edit out of bounds: offset=" + offset + ", oldLen=" + oldLen + ", inputLen=" + input.length());
        }
        var newInput = input.substring(0, offset) + newText + input.substring(offset + oldLen);
        // Windowed re-lex — the lex half of the incremental update is real.
        // The bridge to the compiled lexer goes through LexFn (a String -> TokenArray
        // adapter) rather than reaching into Parser internals.
        LexFn lexFn = parser.lexer()::lex;
        var splicedTokens = tokens.spliceLex(lexFn, offset, oldLen, newText);
        // Locate the smallest enclosing checkpoint subtree.
        var checkpointNode = cst.findCheckpointAncestor(offset, checkpointRules);
        var partialResult = tryPartialReparse(checkpointNode, splicedTokens, offset, oldLen, newText);
        if (partialResult != null) {
            partialReparseCount++ ;
            this.input = newInput;
            this.tokens = splicedTokens;
            this.cst = partialResult.cst();
            this.diagnostics = partialResult.diagnostics();
            return partialResult;
        }
        // Full reparse fallback (no enclosing checkpoint, or checkpoint rule has
        // no kind in the parser's table — e.g. a LEXER rule by mistake).
        fullReparseCount++ ;
        var result = parser.parse(newInput);
        this.input = newInput;
        this.tokens = result.cst()
                            .tokens();
        this.cst = result.cst();
        this.diagnostics = result.diagnostics();
        // Sanity: the windowed splice must agree with the parser's fresh lex on
        // input + count. Mismatches indicate a bug in spliceLex; assert in dev builds.
        assert splicedTokens.input()
                            .equals(this.tokens.input()) && splicedTokens.count() == this.tokens.count() : "windowed splice diverged from fresh lex (input or token count differs)";
        return result;
    }

    /**
     * Attempt a partial reparse at {@code checkpointNode}. Returns null when the
     * partial path is not applicable (no checkpoint, unknown rule kind, the edit
     * extends past the checkpoint span, or the partial parse failed to consume
     * the same token range as the old subtree).
     */
    private ParseResult tryPartialReparse(int checkpointNode,
                                          TokenArray splicedTokens,
                                          int offset,
                                          int oldLen,
                                          String newText) {
        if (checkpointNode == CstArray.NO_NODE) {
            return null;
        }
        var checkpointKindName = cst.kindNameAt(checkpointNode);
        var ruleKindBoxed = ruleKindByName.get(checkpointKindName);
        if (ruleKindBoxed == null) {
            return null;
        }
        var ruleKind = ruleKindBoxed.intValue();
        // Edit must lie entirely within the checkpoint's byte span; otherwise
        // surrounding context (siblings) might also be invalidated and the
        // partial path is unsafe.
        var cpStart = cst.spanStart(checkpointNode);
        var cpEnd = cst.spanEnd(checkpointNode);
        if (offset < cpStart || offset + oldLen > cpEnd) {
            return null;
        }
        var oldFirstToken = cst.firstTokenAt(checkpointNode);
        var oldLastToken = cst.lastTokenAt(checkpointNode);
        var oldTokenCount = tokens.count();
        var newTokenCount = splicedTokens.count();
        var tokenDelta = newTokenCount - oldTokenCount;
        // Map the checkpoint's first-token byte offset into the new token stream.
        // Tokens before the edit keep their byte position; the first token of the
        // checkpoint is always at-or-before the edit (we just verified the edit is
        // inside the checkpoint span), so its new index is simply oldFirstToken
        // when the edit is strictly inside, since prefix tokens are preserved
        // verbatim by spliceLex. We still scan defensively to find a token whose
        // start matches the old start (lex may merge boundary tokens).
        var oldStartByte = oldFirstToken < oldTokenCount
                           ? tokens.startAt(oldFirstToken)
                           : 0;
        var newFirstToken = findTokenStartingAt(splicedTokens, oldStartByte, oldFirstToken);
        if (newFirstToken < 0) {
            return null;
        }
        // Drive the generated parser into the checkpoint rule starting at the
        // mapped token. The result's CST has a synthetic _ROOT wrapping the
        // parsed subtree as its first child.
        ParseResult subtree;
        try{
            subtree = parser.parseRuleFrom(splicedTokens, newFirstToken, ruleKind);
        } catch (RuntimeException ex) {
            // Defensive: any reflection / generated-parser failure falls back to
            // full reparse.
            return null;
        }
        var subCst = subtree.cst();
        var subRoot = subCst.rootIndex();
        if (subRoot == CstArray.NO_NODE) {
            return null;
        }
        var grafted = subCst.firstChildAt(subRoot);
        if (grafted == CstArray.NO_NODE) {
            return null;
        }
        // The grafted subtree must end exactly where it should — at the token
        // that corresponds to the old checkpoint's last token plus the delta.
        var subLast = subCst.lastTokenAt(grafted);
        var expectedLast = oldLastToken + tokenDelta;
        if (subLast != expectedLast) {
            // The partial parse stopped short or overshot: bail to full reparse
            // so we don't produce a CST whose siblings now overlap the parsed
            // region.
            return null;
        }
        if (!subCst.kindNameAt(grafted)
                   .equals(checkpointKindName)) {
            return null;
        }
        var newCst = cst.spliceSubtree(checkpointNode, subCst, grafted, splicedTokens, tokenDelta, true);
        // Diagnostics: keep diagnostics that apply outside the old checkpoint
        // span (with byte offsets shifted past the edit); merge in the partial
        // parse's diagnostics (which have correct byte offsets relative to the
        // new input because parseRuleFrom received splicedTokens whose input is
        // the post-edit string).
        var byteDelta = newText.length() - oldLen;
        var mergedDiagnostics = mergeDiagnostics(diagnostics, subtree.diagnostics(), cpStart, cpEnd, byteDelta);
        return new ParseResult(newCst, mergedDiagnostics);
    }

    /**
     * Return the token index in {@code arr} whose start byte equals
     * {@code targetStart}. The hint is used to seed a small linear scan; if no
     * token matches we fall back to a wider scan and finally return -1.
     */
    private static int findTokenStartingAt(TokenArray arr, int targetStart, int hint) {
        var n = arr.count();
        if (n == 0) {
            return - 1;
        }
        // Hint search window: scan +/- 4 around the hint first.
        var lo = Math.max(0, hint - 4);
        var hi = Math.min(n, hint + 5);
        for (var i = lo; i < hi; i++ ) {
            if (arr.startAt(i) == targetStart) {
                return i;
            }
        }
        // Full scan fallback.
        for (var i = 0; i < n; i++ ) {
            if (arr.startAt(i) == targetStart) {
                return i;
            }
        }
        return - 1;
    }

    /**
     * Merge old diagnostics that fall outside the spliced byte range with the
     * partial parse's diagnostics. Old diagnostics whose offset is past
     * {@code oldEnd} get shifted by {@code byteDelta}; ones inside
     * {@code [oldStart, oldEnd)} are dropped (they were superseded by the
     * partial parse, which produced its own diagnostics for that region).
     */
    private static List<Diagnostic> mergeDiagnostics(List<Diagnostic> oldDiagnostics,
                                                     List<Diagnostic> subDiagnostics,
                                                     int oldStart,
                                                     int oldEnd,
                                                     int byteDelta) {
        var out = new ArrayList<Diagnostic>(oldDiagnostics.size() + subDiagnostics.size());
        for (var d : oldDiagnostics) {
            if (d.offset() < oldStart) {
                out.add(d);
            }else if (d.offset() >= oldEnd) {
                out.add(new Diagnostic(d.severity(),
                                       d.offset() + byteDelta,
                                       d.length(),
                                       d.message(),
                                       d.expected(),
                                       d.found()));
            }
        }
        out.addAll(subDiagnostics);
        return out;
    }

    /** D.1.2 telemetry — number of partial-reparse paths taken since construction. */
    public int partialReparseCount() {
        return partialReparseCount;
    }

    /** D.1.2 telemetry — number of full-reparse fallbacks taken since construction. */
    public int fullReparseCount() {
        return fullReparseCount;
    }

    public CstArray current() {
        return cst;
    }

    public TokenArray currentTokens() {
        return tokens;
    }

    public List<Diagnostic> diagnostics() {
        return diagnostics;
    }

    public String input() {
        return input;
    }

    public Parser parser() {
        return parser;
    }

    public Set<String> checkpointRules() {
        return checkpointRules;
    }
}
