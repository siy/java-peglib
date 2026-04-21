package org.pragmatica.peg.parser;

import org.pragmatica.peg.error.RecoveryStrategy;

/**
 * Parser configuration options.
 *
 * <p>The first three fields ({@code packratEnabled}, {@code recoveryStrategy},
 * {@code captureTrivia}) control runtime parsing behaviour and apply to both
 * the interpreter ({@code PegEngine}) and the generated parser.
 *
 * <p>The remaining ten boolean fields are <b>generator-time</b> performance
 * flags consumed by {@code ParserGenerator}. They select which helper variant
 * the generator emits; they do not produce runtime {@code if (flag)} branches
 * in the generated output. See {@code docs/PERF-REWORK-SPEC.md} sections 6-7
 * for the optimizations each flag controls. All perf flags default to
 * {@code false} in {@link #DEFAULT} and are off until individually validated
 * on the perf corpus.
 */
public record ParserConfig(
    boolean packratEnabled,
    RecoveryStrategy recoveryStrategy,
    boolean captureTrivia,
    // perf flags (generator-time; default off)
    boolean fastTrackFailure,
    boolean literalFailureCache,
    boolean charClassFailureCache,
    boolean bulkAdvanceLiteral,
    boolean skipWhitespaceFastPath,
    boolean reuseEndLocation,
    boolean choiceDispatch,
    boolean markResetChildren,
    boolean inlineLocations,
    boolean selectivePackrat) {

    public static final ParserConfig DEFAULT = new ParserConfig(
        true, RecoveryStrategy.BASIC, true,
        false, false, false, false, false, false, false, false, false, false);

    /**
     * Convenience factory for the three-field runtime configuration. All
     * generator-time perf flags default to {@code false}. Equivalent to the
     * pre-perf-rework constructor signature.
     */
    public static ParserConfig of(boolean packratEnabled, RecoveryStrategy recoveryStrategy, boolean captureTrivia) {
        return new ParserConfig(packratEnabled, recoveryStrategy, captureTrivia,
            false, false, false, false, false, false, false, false, false, false);
    }
}
