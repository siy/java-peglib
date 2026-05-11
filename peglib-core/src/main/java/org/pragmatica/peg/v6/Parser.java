package org.pragmatica.peg.v6;

import org.pragmatica.peg.grammar.Grammar;
import org.pragmatica.peg.v6.cst.ParseResult;
import org.pragmatica.peg.v6.generator.LexerCompiler.CompiledLexer;
import org.pragmatica.peg.v6.generator.ParserCompiler.CompiledParser;
import org.pragmatica.peg.v6.token.TokenArray;

import java.util.Map;

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
        TokenArray tokens = lexer.lex(input);
        return parser.parse(tokens);
    }

    /**
     * Phase F-stub — diagnostic capping. The {@code maxDiagnostics} parameter
     * is currently ignored; full diagnostic-cap plumbing through the generated
     * parser will land in Phase F. Today this is identical to {@link #parse(String)}.
     */
    public ParseResult parse(String input, int maxDiagnostics) {
        return parse(input);
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
