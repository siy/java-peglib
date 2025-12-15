package org.pragmatica.peg.error;

/**
 * Error recovery strategy configuration.
 */
public enum RecoveryStrategy {
    /**
     * Fail immediately on first error.
     */
    NONE,

    /**
     * Basic recovery - report error location and expected tokens.
     */
    BASIC,

    /**
     * Advanced recovery - attempt to continue parsing, collect multiple errors.
     */
    ADVANCED
}
