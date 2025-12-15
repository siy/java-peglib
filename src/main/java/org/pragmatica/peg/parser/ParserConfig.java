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

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private boolean packratEnabled = true;
        private RecoveryStrategy recoveryStrategy = RecoveryStrategy.BASIC;
        private boolean captureTrivia = true;

        private Builder() {}

        public Builder packrat(boolean enabled) {
            this.packratEnabled = enabled;
            return this;
        }

        public Builder recovery(RecoveryStrategy strategy) {
            this.recoveryStrategy = strategy;
            return this;
        }

        public Builder captureTrivia(boolean capture) {
            this.captureTrivia = capture;
            return this;
        }

        public ParserConfig build() {
            return new ParserConfig(packratEnabled, recoveryStrategy, captureTrivia);
        }
    }
}
