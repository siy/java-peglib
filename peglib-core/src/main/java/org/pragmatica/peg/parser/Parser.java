package org.pragmatica.peg.parser;

import org.pragmatica.lang.Result;
import org.pragmatica.peg.action.RuleId;
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

    /**
     * Parse input with error recovery and return CST along with diagnostics.
     * This method attempts to continue parsing after errors, collecting all
     * diagnostics and inserting Error nodes for unparseable regions.
     *
     * <p>Requires {@link org.pragmatica.peg.error.RecoveryStrategy#ADVANCED} for
     * full error recovery. With other strategies, behaves like {@link #parseCst(String)}
     * but returns diagnostics.
     */
    ParseResultWithDiagnostics parseCstWithDiagnostics(String input);

    /**
     * Parse input with error recovery starting from a specific rule.
     */
    ParseResultWithDiagnostics parseCstWithDiagnostics(String input, String startRule);

    /**
     * Parse the rule identified by {@code ruleId} starting at {@code offset} in
     * {@code input}. Unlike {@link #parseCst(String)}, the matched rule is not
     * required to consume all remaining input — parsing stops when the rule
     * itself finishes, and the returned {@link PartialParse#endOffset()}
     * reports the absolute offset at which it stopped.
     *
     * <p>Intended for cursor-anchored incremental reparsing (see
     * {@code docs/incremental/SPEC.md} §5.6) and grammar-debugging tooling.
     * Uses the same packrat cache, trivia capture, and action machinery as
     * {@link #parseCst(String)}.
     *
     * @param ruleId class whose {@link RuleId#name()} identifies the rule to
     *               invoke (default: {@link Class#getSimpleName()})
     * @param input  full buffer text
     * @param offset absolute input offset at which to begin parsing
     * @return {@code Result.success} with the CST subtree and the absolute
     *         offset where parsing stopped, or {@code Result.failure} when
     *         the rule class is unknown, {@code offset} is out of range, or
     *         the rule fails to match at {@code offset}.
     * @since 0.3.0
     */
    Result<PartialParse> parseRuleAt(Class< ? extends RuleId> ruleId, String input, int offset);
}
