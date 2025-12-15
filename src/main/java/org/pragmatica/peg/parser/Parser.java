package org.pragmatica.peg.parser;

import org.pragmatica.lang.Result;
import org.pragmatica.peg.tree.AstNode;
import org.pragmatica.peg.tree.CstNode;

/**
 * Parser interface - parses input text according to a grammar.
 */
public interface Parser {

    /**
     * Parse input and return CST (lossless, preserves trivia).
     */
    Result<CstNode> parseCst(String input);

    /**
     * Parse input and return AST (optimized, no trivia).
     */
    Result<AstNode> parseAst(String input);

    /**
     * Parse input and return semantic value from actions.
     */
    Result<Object> parse(String input);

    /**
     * Parse input starting from a specific rule.
     */
    Result<CstNode> parseCst(String input, String startRule);

    /**
     * Parse input starting from a specific rule.
     */
    Result<AstNode> parseAst(String input, String startRule);

    /**
     * Parse input starting from a specific rule.
     */
    Result<Object> parse(String input, String startRule);
}
