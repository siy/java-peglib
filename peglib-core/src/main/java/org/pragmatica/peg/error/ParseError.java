package org.pragmatica.peg.error;

import org.pragmatica.lang.Cause;
import org.pragmatica.peg.tree.SourceLocation;

/**
 * Parse error with location and context information.
 */
@SuppressWarnings("JBCT-SEAL-01")
public sealed interface ParseError extends Cause {
    SourceLocation location();

    /**
     * Unexpected input error.
     */
    record UnexpectedInput(
    SourceLocation location,
    String found,
    String expected) implements ParseError {
        @Override
        public String message() {
            return "Unexpected '" + found + "' at " + location + ", expected " + expected;
        }
    }

    /**
     * Unexpected end of input.
     */
    record UnexpectedEof(
    SourceLocation location,
    String expected) implements ParseError {
        @Override
        public String message() {
            return "Unexpected end of input at " + location + ", expected " + expected;
        }
    }

    /**
     * Custom error from semantic predicate or action.
     */
    record SemanticError(
    SourceLocation location,
    String reason) implements ParseError {
        @Override
        public String message() {
            return reason + " at " + location;
        }
    }

    /**
     * Action execution error.
     */
    record ActionError(
    SourceLocation location,
    String actionCode,
    Throwable cause) implements ParseError {
        @Override
        public String message() {
            return "Action failed at " + location + ": " + cause.getMessage();
        }
    }
}
