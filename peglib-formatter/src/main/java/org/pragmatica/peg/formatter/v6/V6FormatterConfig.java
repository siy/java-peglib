package org.pragmatica.peg.formatter.v6;

import java.util.HashMap;
import java.util.Map;

/**
 * Immutable configuration for a {@link V6Formatter}.
 *
 * <p>Construct via {@link #builder()}. Each {@code with*} mutator on the
 * builder returns a new builder, so the configuration is fully immutable
 * end-to-end.
 *
 * @since 0.6.0
 */
public record V6FormatterConfig(int defaultIndent,
                                int maxLineWidth,
                                V6TriviaPolicy triviaPolicy,
                                Map<String, V6FormatterRule> rules) {
    public V6FormatterConfig {
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
    public static V6FormatterConfig defaultConfig() {
        return new V6FormatterConfig(2, 80, V6TriviaPolicy.PRESERVE, Map.of());
    }

    /** Start a new immutable builder seeded with default values. */
    public static Builder builder() {
        return new Builder(2, 80, V6TriviaPolicy.PRESERVE, Map.of());
    }

    /** Start an immutable builder seeded with this config's values. */
    public Builder toBuilder() {
        return new Builder(defaultIndent, maxLineWidth, triviaPolicy, rules);
    }

    /** Immutable builder. Each mutator returns a new builder; the receiver is untouched. */
    public record Builder(int defaultIndent,
                          int maxLineWidth,
                          V6TriviaPolicy triviaPolicy,
                          Map<String, V6FormatterRule> rules) {
        public Builder {
            if (rules == null) {
                throw new IllegalArgumentException("rules must not be null");
            }
            rules = Map.copyOf(rules);
        }

        public Builder defaultIndent(int amount) {
            if (amount < 0) {
                throw new IllegalArgumentException("defaultIndent must be >= 0");
            }
            return new Builder(amount, maxLineWidth, triviaPolicy, rules);
        }

        public Builder maxLineWidth(int width) {
            if (width <= 0) {
                throw new IllegalArgumentException("maxLineWidth must be > 0");
            }
            return new Builder(defaultIndent, width, triviaPolicy, rules);
        }

        public Builder triviaPolicy(V6TriviaPolicy policy) {
            if (policy == null) {
                throw new IllegalArgumentException("triviaPolicy must not be null");
            }
            return new Builder(defaultIndent, maxLineWidth, policy, rules);
        }

        public Builder rule(String ruleName, V6FormatterRule rule) {
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

        public V6FormatterConfig build() {
            return new V6FormatterConfig(defaultIndent, maxLineWidth, triviaPolicy, rules);
        }
    }
}
