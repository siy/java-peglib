package org.pragmatica.peg.parser;

import org.pragmatica.peg.error.RecoveryStrategy;

/**
 * Parser configuration options.
 */
public record ParserConfig(
    boolean packratEnabled,
    RecoveryStrategy recoveryStrategy,
    boolean captureTrivia
) {
    public static final ParserConfig DEFAULT = new ParserConfig(
        true,
        RecoveryStrategy.BASIC,
        true
    );
}
