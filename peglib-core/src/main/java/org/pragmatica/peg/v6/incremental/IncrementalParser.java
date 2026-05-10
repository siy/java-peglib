package org.pragmatica.peg.v6.incremental;

import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.pragmatica.peg.v6.Parser;
import org.pragmatica.peg.v6.cst.CstArray;
import org.pragmatica.peg.v6.cst.ParseResult;
import org.pragmatica.peg.v6.diagnostic.Diagnostic;
import org.pragmatica.peg.v6.token.LexFn;
import org.pragmatica.peg.v6.token.TokenArray;

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
    public static final Set<String> DEFAULT_CHECKPOINT_RULES =
        Set.of("Stmt", "Statement", "MethodDecl", "TypeDecl", "ClassMember", "Block");

    private final Parser parser;
    private final Set<String> checkpointRules;
    private String input;
    private TokenArray tokens;
    private CstArray cst;
    private List<Diagnostic> diagnostics;

    public IncrementalParser(Parser parser, String initialInput) {
        this(parser, initialInput, DEFAULT_CHECKPOINT_RULES);
    }

    public IncrementalParser(Parser parser, String initialInput, Set<String> checkpointRules) {
        Objects.requireNonNull(parser, "parser");
        Objects.requireNonNull(initialInput, "initialInput");
        Objects.requireNonNull(checkpointRules, "checkpointRules");
        this.parser = parser;
        this.checkpointRules = Set.copyOf(checkpointRules);
        this.input = initialInput;
        var initial = parser.parse(initialInput);
        this.tokens = initial.cst().tokens();
        this.cst = initial.cst();
        this.diagnostics = initial.diagnostics();
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
                "edit out of bounds: offset=" + offset + ", oldLen=" + oldLen
                    + ", inputLen=" + input.length());
        }
        var newInput = input.substring(0, offset) + newText + input.substring(offset + oldLen);

        // Windowed re-lex — the lex half of the incremental update is real today.
        // The bridge to the compiled lexer goes through LexFn (a String -> TokenArray
        // adapter) rather than reaching into Parser internals.
        LexFn lexFn = parser.lexer()::lex;
        var splicedTokens = tokens.spliceLex(lexFn, offset, oldLen, newText);

        // Side observation: locate the checkpoint ancestor for the edit point.
        // Recorded for future use; no behavioural effect today because the parse half
        // is still full-reparse.
        @SuppressWarnings("unused")
        var checkpoint = cst.findCheckpointAncestor(offset, checkpointRules);

        // Parse half: until D.1.2 wires partial reparse, run the full parser. The
        // result's tokens come from the parser's own lex of newInput; we adopt them as
        // the new state to keep things consistent.
        var result = parser.parse(newInput);
        this.input = newInput;
        this.tokens = result.cst().tokens();
        this.cst = result.cst();
        this.diagnostics = result.diagnostics();
        // Sanity: the windowed splice must agree with the parser's fresh lex on
        // input + count. Mismatches indicate a bug in spliceLex; assert in dev builds.
        assert splicedTokens.input().equals(this.tokens.input())
            && splicedTokens.count() == this.tokens.count()
            : "windowed splice diverged from fresh lex (input or token count differs)";
        return result;
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
