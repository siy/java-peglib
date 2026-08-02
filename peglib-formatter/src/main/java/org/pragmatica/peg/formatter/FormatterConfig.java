package org.pragmatica.peg.formatter;

import java.util.HashMap;
import java.util.Map;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;


/**
 * Immutable configuration for a {@link Formatter}.
 *
 * <p>Construct via {@link #builder()}. Each {@code with*} mutator on the
 * builder returns a new builder, so the configuration is fully immutable
 * end-to-end.
 *
 * <p>Validation happens once, in {@link Builder#build()}, which returns a
 * {@link Result}. The mutators themselves are total, which keeps the fluent
 * chain readable — {@code builder().defaultIndent(4).maxLineWidth(120).build()}
 * — while still surfacing invalid combinations as a failure rather than an
 * exception.
 *
 * @since 0.6.0
 */
public record FormatterConfig(int defaultIndent,
                              int maxLineWidth,
                              TriviaPolicy triviaPolicy,
                              Map<String, FormatterRule> rules) {
    public FormatterConfig {
        rules = rules == null
                ? Map.of()
                : Map.copyOf(rules);
    }

    /** Validating factory. The only sanctioned way to build a config from untrusted values. */
    public static Result<FormatterConfig> formatterConfig(int defaultIndent,
                                                          int maxLineWidth,
                                                          TriviaPolicy triviaPolicy,
                                                          Map<String, FormatterRule> rules) {
        if (defaultIndent < 0) {
            return Causes.cause("defaultIndent must be >= 0, was " + defaultIndent).result();
        }

        if (maxLineWidth <= 0) {
            return Causes.cause("maxLineWidth must be > 0, was " + maxLineWidth).result();
        }

        if (triviaPolicy == null) {
            return Causes.cause("triviaPolicy must not be null").result();
        }

        return Result.success(new FormatterConfig(defaultIndent, maxLineWidth, triviaPolicy, rules));
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

    /** Immutable builder. Each mutator returns a new builder; the receiver is untouched. */
    public record Builder(int defaultIndent,
                          int maxLineWidth,
                          TriviaPolicy triviaPolicy,
                          Map<String, FormatterRule> rules) {
        public Builder {
            rules = rules == null
                    ? Map.of()
                    : Map.copyOf(rules);
        }

        public Builder defaultIndent(int amount) {
            return new Builder(amount, maxLineWidth, triviaPolicy, rules);
        }

        public Builder maxLineWidth(int width) {
            return new Builder(defaultIndent, width, triviaPolicy, rules);
        }

        public Builder triviaPolicy(TriviaPolicy policy) {
            return new Builder(defaultIndent, maxLineWidth, policy, rules);
        }

        public Builder rule(String ruleName, FormatterRule rule) {
            if (ruleName == null || ruleName.isEmpty() || rule == null) {
                return this;
            }

            var next = new HashMap<>(rules);

            next.put(ruleName, rule);

            return new Builder(defaultIndent, maxLineWidth, triviaPolicy, next);
        }

        /** Validate the accumulated settings and produce the config. */
        public Result<FormatterConfig> build() {
            return formatterConfig(defaultIndent, maxLineWidth, triviaPolicy, rules);
        }
    }
}
