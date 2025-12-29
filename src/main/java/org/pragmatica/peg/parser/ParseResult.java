package org.pragmatica.peg.parser;

import org.pragmatica.lang.Option;
import org.pragmatica.peg.tree.CstNode;
import org.pragmatica.peg.tree.SourceLocation;
import org.pragmatica.peg.tree.Trivia;

import java.util.List;

/**
 * Result of parsing an expression - either success with a node or failure.
 */
public sealed interface ParseResult {

    boolean isSuccess();

    default boolean isFailure() {
        return !isSuccess();
    }

    /**
     * Successful parse with CST node and new position.
     */
    record Success(
        CstNode node,
        SourceLocation endLocation,
        List<Trivia> trailingTrivia,
        Option<Object> semanticValue
    ) implements ParseResult {

        @Override
        public boolean isSuccess() {
            return true;
        }

        public static Success of(CstNode node, SourceLocation endLocation) {
            return new Success(node, endLocation, List.of(), Option.none());
        }

        public static Success of(CstNode node, SourceLocation endLocation, List<Trivia> trivia) {
            return new Success(node, endLocation, trivia, Option.none());
        }

        public static Success withValue(CstNode node, SourceLocation endLocation, Object value) {
            return new Success(node, endLocation, List.of(), Option.option(value));
        }

        public Success withSemanticValue(Object value) {
            return new Success(node, endLocation, trailingTrivia, Option.option(value));
        }

        /**
         * Check if this result has an explicit semantic value.
         */
        public boolean hasSemanticValue() {
            return semanticValue.isPresent();
        }

        /**
         * Get the semantic value as Option.
         * This is the JBCT-compliant way to access the semantic value.
         */
        public Option<Object> semanticValueOpt() {
            return semanticValue;
        }
    }

    /**
     * Failed parse - no match at current position.
     */
    record Failure(
        SourceLocation location,
        String expected
    ) implements ParseResult {

        @Override
        public boolean isSuccess() {
            return false;
        }

        public static Failure at(SourceLocation location, String expected) {
            return new Failure(location, expected);
        }
    }

    /**
     * Special result for predicates - matched but consumed no input.
     */
    record PredicateSuccess(
        SourceLocation location
    ) implements ParseResult {

        @Override
        public boolean isSuccess() {
            return true;
        }
    }

    /**
     * Special result for ignored expressions - matched but no node produced.
     */
    record Ignored(
        SourceLocation endLocation,
        String matchedText
    ) implements ParseResult {

        @Override
        public boolean isSuccess() {
            return true;
        }
    }

    /**
     * Failure after cut - prevents backtracking to try other alternatives.
     * Used when a cut (^) operator was encountered and subsequent parsing failed.
     */
    record CutFailure(
        SourceLocation location,
        String expected
    ) implements ParseResult {

        @Override
        public boolean isSuccess() {
            return false;
        }

        public static CutFailure at(SourceLocation location, String expected) {
            return new CutFailure(location, expected);
        }
    }
}
