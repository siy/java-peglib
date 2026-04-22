package org.pragmatica.peg.generator;
/**
 * Controls error reporting style in generated parsers.
 */
public enum ErrorReporting {
    /**
     * Basic error reporting - simple ParseError with line/column/reason.
     * Minimal code footprint, suitable for simple parsers.
     */
    BASIC,
    /**
     * Advanced Rust-style error reporting with rich diagnostics.
     * Includes source context, underlines, labels, and multi-error collection.
     *
     * <p>Example output:
     * <pre>
     * error: unexpected token
     *   --> input.txt:3:15
     *    |
     *  3 |     let x = @invalid;
     *    |             ^^^^^^^^ expected expression
     * </pre>
     */
    ADVANCED
}
