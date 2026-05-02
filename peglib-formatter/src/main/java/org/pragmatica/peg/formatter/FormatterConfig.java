package org.pragmatica.peg.formatter;

import java.util.HashMap;
import java.util.Map;

/**
 * Immutable configuration for a {@link Formatter}.
 *
 * <p>Carries the renderer parameters (indent / max line width), the active
 * {@link TriviaPolicy}, and the set of registered {@link FormatterRule}s
 * keyed by CST rule name.
 *
 * <p>Construct via {@link #builder()} (or {@link Formatter#builder()}, which
 * delegates here). Each {@code with*} method on the builder returns a new
 * builder instance, so configuration is fully immutable end-to-end.
 *
 * <pre>{@code
 * var config = FormatterConfig.builder()
 *     .defaultIndent(2)
 *     .maxLineWidth(80)
 *     .triviaPolicy(TriviaPolicy.DROP_ALL)
 *     .rule("Block", (ctx, kids) -> ...)
 *     .build();
 *
 * var formatter = Formatter.formatter(config);
 * }</pre>
 *
 * @since 0.4.0
 */
public record FormatterConfig(int defaultIndent,
                              int maxLineWidth,
                              TriviaPolicy triviaPolicy,
                              Map<String, FormatterRule> rules) {

    public FormatterConfig {
        if (defaultIndent < 0) {
            throw new IllegalArgumentException("defaultIndent must be >= 0");
        }
        if (maxLineWidth <= 0) {
            throw new IllegalArgumentException("maxLineWidth must be > 0");
        }
        if (triviaPolicy == null) {
            throw new IllegalArgumentException("triviaPolicy must not be null");
        }
        if (rules == null) {
            throw new IllegalArgumentException("rules must not be null");
        }
        rules = Map.copyOf(rules);
    }

    /** Default values: indent=2, maxLineWidth=80, triviaPolicy=PRESERVE, no rules. */
    public static FormatterConfig defaultConfig() {
        return new FormatterConfig(2, 80, TriviaPolicy.PRESERVE, Map.of());
    }

    /** Start a new immutable builder seeded with default values. */
    public static Builder builder() {
        return new Builder(2, 80, TriviaPolicy.PRESERVE, Map.of());
    }

    /** Start an immutable builder seeded with this config's values. */
    public Builder toBuilder() {
        return new Builder(defaultIndent, maxLineWidth, triviaPolicy, rules);
    }

    /**
     * Immutable builder for {@link FormatterConfig}. Each {@code with*} /
     * mutator method returns a new builder; the receiver is unmodified.
     */
    public record Builder(int defaultIndent,
                          int maxLineWidth,
                          TriviaPolicy triviaPolicy,
                          Map<String, FormatterRule> rules) {

        public Builder {
            if (rules == null) {
                throw new IllegalArgumentException("rules must not be null");
            }
            rules = Map.copyOf(rules);
        }

        /** New builder with updated default indent. */
        public Builder defaultIndent(int amount) {
            if (amount < 0) {
                throw new IllegalArgumentException("defaultIndent must be >= 0");
            }
            return new Builder(amount, maxLineWidth, triviaPolicy, rules);
        }

        /** New builder with updated max line width. */
        public Builder maxLineWidth(int width) {
            if (width <= 0) {
                throw new IllegalArgumentException("maxLineWidth must be > 0");
            }
            return new Builder(defaultIndent, width, triviaPolicy, rules);
        }

        /** New builder with updated trivia policy. */
        public Builder triviaPolicy(TriviaPolicy policy) {
            if (policy == null) {
                throw new IllegalArgumentException("triviaPolicy must not be null");
            }
            return new Builder(defaultIndent, maxLineWidth, policy, rules);
        }

        /**
         * New builder with the given rule registered (or replaced). Last
         * registration wins on a per-name basis.
         */
        public Builder rule(String ruleName, FormatterRule rule) {
            if (ruleName == null || ruleName.isEmpty()) {
                throw new IllegalArgumentException("ruleName must be non-empty");
            }
            if (rule == null) {
                throw new IllegalArgumentException("rule must not be null");
            }
            var next = new HashMap<>(rules);
            next.put(ruleName, rule);
            return new Builder(defaultIndent, maxLineWidth, triviaPolicy, next);
        }

        /** Materialise the configured values as a {@link FormatterConfig}. */
        public FormatterConfig build() {
            return new FormatterConfig(defaultIndent, maxLineWidth, triviaPolicy, rules);
        }
    }
}
