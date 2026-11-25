package org.pragmatica.peg.incremental;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.pragmatica.lang.Unit;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.peg.Parser;
import org.pragmatica.peg.cst.CstArray;
import org.pragmatica.peg.cst.ParseResult;
import org.pragmatica.peg.diagnostic.Diagnostic;
import org.pragmatica.peg.lexer.UnicodeEscapes;
import org.pragmatica.peg.token.LexFn;
import org.pragmatica.peg.token.TokenArray;


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
     * Default rule names treated as checkpoints when neither the grammar nor
     * the caller supplied a list. As of 0.6.1, grammars may declare their own
     * checkpoints via the {@code %checkpoint RuleName} directive; the 2-arg
     * constructor prefers that grammar-supplied set when non-empty and falls
     * back to this default only when the grammar carries no checkpoint
     * directives. Includes the common Java/C-family statement-and-declaration
     * boundaries.
     */
    public static final Set<String> DEFAULT_CHECKPOINT_RULES = Set.of("Stmt",
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
        this(parser,
             initialInput,
             parser.grammar().checkpointRules().isEmpty()
             ? DEFAULT_CHECKPOINT_RULES
             : parser.grammar().checkpointRules());
    }

    public IncrementalParser(Parser parser, String initialInput, Set<String> checkpointRules) {
        this.parser = parser;
        this.checkpointRules = Set.copyOf(checkpointRules);
        this.ruleKindByName = parser.ruleKinds();
        this.input = initialInput;
        var initial = parser.parse(initialInput);

        this.tokens = initial.cst().tokens();
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
     * <p>Caller is responsible for passing in-range coordinates and a non-null
     * {@code newText}. Out-of-range coordinates trigger JVM string OOB at the
     * substring step.
     */
    public ParseResult edit(int offset, int oldLen, String newText) {
        var newInput = input.substring(0, offset) + newText + input.substring(offset + oldLen);
        // Windowed re-lex — the lex half of the incremental update is real.
        // The bridge to the compiled lexer goes through LexFn (a String -> TokenArray
        // adapter) rather than reaching into Parser internals.
        // A source with a Unicode escape is lexed over translated text (JLS 3.3), which a window of
        // raw text cannot reproduce, so it is lexed whole, exactly as Parser.parse lexes it.
        // The compiled lexer itself, not a method reference: it is resumable, which spliceLex uses.
        LexFn lexFn = parser.lexer();
        var splicedTokens = UnicodeEscapes.translate(newInput).isPresent()
                            ? parser.lex(newInput)
                            : tokens.spliceLex(lexFn, offset, oldLen, newText);
        // Locate the smallest enclosing checkpoint subtree.
        var checkpointNode = cst.findCheckpointAncestor(offset, checkpointRules);
        var partialOpt = tryPartialReparse(checkpointNode, splicedTokens, offset, oldLen, newText);

        if (partialOpt.isPresent()) {
            var partialResult = partialOpt.unwrap();

            partialReparseCount++;
            this.input = newInput;
            this.tokens = splicedTokens;
            this.cst = partialResult.cst();
            this.diagnostics = partialResult.diagnostics();

            return partialResult;
        }
        // Full reparse fallback (no enclosing checkpoint, or checkpoint rule has
        // no kind in the parser's table — e.g. a LEXER rule by mistake).
        fullReparseCount++;
        var result = parser.parse(newInput);

        this.input = newInput;
        this.tokens = result.cst().tokens();
        this.cst = result.cst();
        this.diagnostics = result.diagnostics();
        // Sanity: the windowed splice must agree with the parser's fresh lex on
        // input + count. Mismatches indicate a bug in spliceLex; assert in dev builds.
        assert splicedTokens.input().equals(this.tokens.input()) && splicedTokens.count() == this.tokens.count() : "windowed splice diverged from fresh lex (input or token count differs)";

        return result;
    }

    /**
     * Attempt a partial reparse at {@code checkpointNode}. Returns {@code Option.none()} when
     * the partial path is not applicable (no checkpoint, unknown rule kind, the edit
     * extends past the checkpoint span, or the partial parse failed to consume
     * the same token range as the old subtree).
     */
    private Option<ParseResult> tryPartialReparse(int checkpointNode,
                                                  TokenArray splicedTokens,
                                                  int offset,
                                                  int oldLen,
                                                  String newText) {
        // Error recovery is decided by the whole attempt, not by one subtree, so neither the tree nor
        // the diagnostics of a broken file can be patched locally. Only clean to clean is spliced.
        if (checkpointNode == CstArray.NO_NODE || !diagnostics.isEmpty()) {
            return Option.none();
        }

        var checkpointKindName = cst.kindNameAt(checkpointNode);
        var ruleKindBoxed = ruleKindByName.get(checkpointKindName);

        if (ruleKindBoxed == null) {
            return Option.none();
        }

        var ruleKind = ruleKindBoxed.intValue();
        // Edit must lie entirely within the checkpoint's byte span; otherwise
        // surrounding context (siblings) might also be invalidated and the
        // partial path is unsafe.
        var cpStart = cst.spanStart(checkpointNode);
        var cpEnd = cst.spanEnd(checkpointNode);

        if (offset < cpStart || offset + oldLen > cpEnd) {
            return Option.none();
        }

        var oldFirstToken = cst.firstTokenAt(checkpointNode);
        var oldLastToken = cst.lastTokenAt(checkpointNode);
        var oldTokenCount = tokens.count();
        var newTokenCount = splicedTokens.count();
        var tokenDelta = newTokenCount - oldTokenCount;
        // No room left for the subtree's own tokens, or an empty subtree with no token to anchor to.
        if (oldLastToken < oldFirstToken || oldLastToken + tokenDelta < oldFirstToken) {
            return Option.none();
        }
        // The subtree is reparsed in place, so every token outside it must be the one the surrounding
        // tree was built from: text inserted at either edge can add a token there or change a neighbour.
        if (!sameOutside(tokens, splicedTokens, oldFirstToken, oldLastToken, tokenDelta)) {
            return Option.none();
        }
        // Drive the generated parser into the checkpoint rule starting at its
        // first token. The result's CST has a synthetic _ROOT wrapping the
        // parsed subtree as its first child. Any reflection failure becomes a
        // Result.failure that we map back to Option.none().
        var subtreeOpt = Result.lift(PartialReparseFailed::new,
                                     () -> parser.parseRuleFrom(splicedTokens, oldFirstToken, ruleKind))
                               .option();

        if (subtreeOpt.isEmpty()) {
            return Option.none();
        }

        var subtree = subtreeOpt.unwrap();

        if (!subtree.diagnostics().isEmpty()) {
            return Option.none();
        }

        var subCst = subtree.cst();
        var subRoot = subCst.rootIndex();

        if (subRoot == CstArray.NO_NODE) {
            return Option.none();
        }

        var grafted = subCst.firstChildAt(subRoot);

        if (grafted == CstArray.NO_NODE) {
            return Option.none();
        }
        // The grafted subtree must start at the same token (whitespace inserted at its edge moves it, and
        // its ancestors with it) and end exactly where it should — at the token
        // that corresponds to the old checkpoint's last token plus the delta.
        var subLast = subCst.lastTokenAt(grafted);
        var expectedLast = oldLastToken + tokenDelta;

        if (subCst.firstTokenAt(grafted) != oldFirstToken || subLast != expectedLast) {
            // The partial parse stopped short or overshot: bail to full reparse
            // so we don't produce a CST whose siblings now overlap the parsed
            // region.
            return Option.none();
        }

        if (!subCst.kindNameAt(grafted).equals(checkpointKindName)) {
            return Option.none();
        }

        var newCst = cst.spliceSubtree(checkpointNode, subCst, grafted, splicedTokens, tokenDelta, true);

        return Option.some(new ParseResult(newCst, List.of()));
    }

    /** Whether the tokens before {@code first} and after {@code last} keep their kind, order and text boundaries. */
    private static boolean sameOutside(TokenArray before, TokenArray after, int first, int last, int tokenDelta) {
        for (var i = 0; i < first; i++) {
            if (before.kindAt(i) != after.kindAt(i) || before.startAt(i) != after.startAt(i)) {
                return false;
            }
        }

        if (before.startAt(first) != after.startAt(first)) {
            return false;
        }

        var byteDelta = after.input().length() - before.input().length();

        for (var i = last + 1; i < before.count(); i++) {
            if (before.kindAt(i) != after.kindAt(i + tokenDelta) || before.startAt(i) + byteDelta != after.startAt(i + tokenDelta)) {
                return false;
            }
        }

        return true;
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

    /**
     * Immutable snapshot of the mutable state inside an {@link IncrementalParser}.
     *
     * <p>Tokens, CST and the diagnostics list are themselves immutable (or treated
     * as such by the parser pipeline), so a snapshot just captures references.
     * Restoring is therefore O(1) — useful for benchmarks that need to replay the
     * same edit against the same starting state per invocation, and for callers
     * that want to implement undo/redo on top of an incremental parser without
     * re-running the full initial parse.
     *
     * <p>Counters ({@code partialReparseCount}, {@code fullReparseCount}) are
     * intentionally NOT part of the snapshot — they are observability hooks that
     * track lifetime totals across restores, not state we want to roll back.
     */
    public record Snapshot(String input, TokenArray tokens, CstArray cst, List<Diagnostic> diagnostics) {}

    /** Capture the current mutable state for later {@link #restore(Snapshot)}. */
    public Snapshot snapshot() {
        return new Snapshot(input, tokens, cst, diagnostics);
    }

    /**
     * Restore mutable state from a snapshot taken on this same parser. References
     * are copied verbatim; this is an O(1) operation.
     */
    public Result<Unit> restore(Snapshot snapshot) {
        this.input = snapshot.input();
        this.tokens = snapshot.tokens();
        this.cst = snapshot.cst();
        this.diagnostics = snapshot.diagnostics();

        return Result.unitResult();
    }

    /**
     * Internal cause produced by {@link #tryPartialReparse} when the reflective
     * dispatch into the generated parser's {@code parseRuleFrom} throws. The
     * cause is immediately discarded (mapped to {@link Option#none()}) by the
     * caller, which falls back to a full reparse — but a typed cause is still
     * preferable to an inline lambda for the {@link Result#lift} adapter
     * boundary.
     */
    private record PartialReparseFailed(Throwable cause) implements Cause {
        @Override
        public String message() {
            return "parseRuleFrom reflective dispatch failed: " + cause;
        }
    }
}
