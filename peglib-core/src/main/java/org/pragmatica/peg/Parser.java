package org.pragmatica.peg;

import java.util.Map;

import org.pragmatica.peg.grammar.Grammar;
import org.pragmatica.peg.cst.ParseResult;
import org.pragmatica.peg.generator.LexerCompiler.CompiledLexer;
import org.pragmatica.peg.generator.ParserCompiler.CompiledParser;
import org.pragmatica.peg.lexer.UnicodeEscapes;
import org.pragmatica.peg.token.TokenArray;


/**
 * Phase C.1 — thin facade over a compiled lexer + parser pair built from a
 * single PEG grammar. Instances are obtained from {@link PegParser#fromGrammar(String)}
 * which manages a process-wide cache keyed by grammar text.
 *
 * <p>This type is intentionally lightweight: every {@link #parse(String)} call
 * performs only a lex pass followed by a parse pass; no grammar compilation
 * happens here.
 */
public final class Parser {
    private final Grammar grammar;
    private final CompiledLexer lexer;
    private final CompiledParser parser;

    Parser(Grammar grammar, CompiledLexer lexer, CompiledParser parser) {
        this.grammar = grammar;
        this.lexer = lexer;
        this.parser = parser;
    }

    /**
     * Lex {@code input} into a {@link TokenArray} and run the generated parser
     * over it, returning the resulting {@link ParseResult}.
     */
    public ParseResult parse(String input) {
        return parser.parse(lexTranslated(input));
    }

    /**
     * Lex {@code input}, applying Java's Unicode-escape pre-pass (JLS 3.3) when the source
     * contains one.
     *
     * <p>{@code \\uXXXX} is substituted before lexing, not by the lexer, so {@code \\u0069f}
     * really is the keyword {@code if} and {@code \\u000a} really does terminate a {@code //}
     * comment. No grammar can express that. The token spans are then pushed back onto the
     * ORIGINAL text so {@code reconstruct()} still returns exactly what the user wrote — the
     * formatter's byte-identical round-trip depends on it.
     *
     * <p>Sources without an escape (almost all of them) pay one substring scan and are lexed
     * unchanged; see {@link UnicodeEscapes#translate}.
     */
    private TokenArray lexTranslated(String input) {
        return UnicodeEscapes.translate(input)
                             .map(t -> lexer.lex(t.text())
                                            .remapOffsets(input,
                                                          t.offsetMap()))
                             .or(() -> lexer.lex(input));
    }

    /**
     * 0.6.1 — Item G — diagnostic-capped parse. Caps the number of recorded
     * diagnostics; once the cap is reached, the recovery loop exits.
     * Semantics:
     * <ul>
     *   <li>{@code maxDiagnostics == 0}: zero diagnostics recorded.</li>
     *   <li>{@code maxDiagnostics < 0}: treated as no cap.</li>
     *   <li>Successful parse: no diagnostics regardless of cap.</li>
     * </ul>
     */
    public ParseResult parse(String input, int maxDiagnostics) {
        return parser.parse(lexTranslated(input), maxDiagnostics);
    }

    /** The compiled lexer; exposed for callers that want raw token access (incremental engine). */
    public CompiledLexer lexer() {
        return lexer;
    }

    /** The compiled parser; exposed for callers that want to drive parsing from pre-lexed tokens. */
    public CompiledParser parserEngine() {
        return parser;
    }

    /** The source grammar this parser was compiled from. */
    public Grammar grammar() {
        return grammar;
    }

    /**
     * Phase D.1.2 — partial parse. Drive the generated parser starting at
     * {@code fromTokenIdx} (after skipping leading trivia) into the rule whose
     * kind constant is {@code ruleKind}. Returns the ParseResult whose CST has
     * a synthetic {@code _ROOT} wrapping the parsed subtree.
     */
    public ParseResult parseRuleFrom(TokenArray tokens, int fromTokenIdx, int ruleKind) {
        return parser.parseRuleFrom(tokens, fromTokenIdx, ruleKind);
    }

    /**
     * Phase D.1.2 — rule-name to rule-kind constant mapping (parser rules only).
     * Used by tooling and the incremental engine to look up the kind argument
     * for {@link #parseRuleFrom}.
     */
    public Map<String, Integer> ruleKinds() {
        return parser.ruleKinds();
    }
}
