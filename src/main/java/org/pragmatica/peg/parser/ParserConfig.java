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
 * for the optimizations each flag controls. Phase 1 flags
 * ({@code fastTrackFailure}, {@code literalFailureCache},
 * {@code charClassFailureCache}, {@code bulkAdvanceLiteral},
 * {@code skipWhitespaceFastPath}, {@code reuseEndLocation}) default to
 * {@code true} in {@link #DEFAULT} after corpus parity validation. Phase 2
 * flags default to {@code false} pending individual validation.
 *
 * <p><b>Scope:</b> {@code fastTrackFailure}, {@code literalFailureCache},
 * {@code charClassFailureCache}, {@code bulkAdvanceLiteral},
 * {@code skipWhitespaceFastPath}, and {@code reuseEndLocation} affect the
 * <b>CST generator emission only</b> ({@code ParserGenerator#generateCst}).
 * The action-bearing non-CST emission path ({@code ParserGenerator#generate})
 * is unaffected by these flags.
 */
public record ParserConfig(
    boolean packratEnabled,
    RecoveryStrategy recoveryStrategy,
    boolean captureTrivia,
    // perf flags (generator-time; phase 1 default on, phase 2 default off)
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
        true, true, true, true, true, true,          // phase 1: all on
        false, false, false, false);                  // phase 2: all off

    /**
     * Convenience factory for the three-field runtime configuration. Phase 1
     * generator-time perf flags default to {@code true}; phase 2 flags default
     * to {@code false}. Equivalent to {@link #DEFAULT} with the caller's three
     * runtime fields substituted.
     */
    public static ParserConfig of(boolean packratEnabled, RecoveryStrategy recoveryStrategy, boolean captureTrivia) {
        return new ParserConfig(packratEnabled, recoveryStrategy, captureTrivia,
            true, true, true, true, true, true,    // phase 1 defaults
            false, false, false, false);            // phase 2 defaults
    }
}
