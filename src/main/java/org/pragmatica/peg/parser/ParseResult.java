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
     * Marker to distinguish explicit null return from no value.
     */
    Object EXPLICIT_NULL = new Object() {
        @Override
        public String toString() {
            return "EXPLICIT_NULL";
        }
    };

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
            // Use EXPLICIT_NULL marker to distinguish null from no value
            var wrapped = value == null ? EXPLICIT_NULL : value;
            return new Success(node, endLocation, List.of(), Option.some(wrapped));
        }

        public Success withSemanticValue(Object value) {
            var wrapped = value == null ? EXPLICIT_NULL : value;
            return new Success(node, endLocation, trailingTrivia, Option.some(wrapped));
        }

        /**
         * Check if this result has an explicit semantic value (including explicit null).
         */
        public boolean hasSemanticValue() {
            return semanticValue.isPresent();
        }

        /**
         * Get the unwrapped semantic value, converting EXPLICIT_NULL back to null.
         */
        public Object unwrapSemanticValue() {
            if (semanticValue.isEmpty()) {
                return null;
            }
            var value = semanticValue.unwrap();
            return value == EXPLICIT_NULL ? null : value;
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
}
