package org.pragmatica.peg;

import org.pragmatica.lang.Result;
import org.pragmatica.peg.action.Actions;
import org.pragmatica.peg.error.RecoveryStrategy;
import org.pragmatica.peg.generator.ErrorReporting;
import org.pragmatica.peg.generator.ParserGenerator;
import org.pragmatica.peg.grammar.Grammar;
import org.pragmatica.peg.grammar.GrammarParser;
import org.pragmatica.peg.grammar.GrammarResolver;
import org.pragmatica.peg.grammar.GrammarSource;
import org.pragmatica.peg.parser.Parser;
import org.pragmatica.peg.parser.ParserConfig;
import org.pragmatica.peg.parser.PegEngine;

/**
 * Entry point for creating PEG parsers.
 *
 * <p>Example usage:
 * <pre>{@code
 * var parser = PegParser.fromGrammar("""
 *     Number <- < [0-9]+ >
 *     %whitespace <- [ \\t]*
 *     """).unwrap();
 *
 * var result = parser.parseCst("123");
 * }</pre>
 */
public final class PegParser {
    private PegParser() {}

    /**
     * Create a parser from grammar text.
     */
    public static Result<Parser> fromGrammar(String grammarText) {
        return fromGrammar(grammarText, ParserConfig.DEFAULT);
    }

    /**
     * Create a parser from grammar text with custom configuration.
     */
    public static Result<Parser> fromGrammar(String grammarText, ParserConfig config) {
        return fromGrammar(grammarText, config, GrammarSource.empty());
    }

    /**
     * 0.2.8 — Create a parser from grammar text with a {@link GrammarSource} for
     * resolving {@code %import} directives. If the grammar declares no imports,
     * {@code source} is never consulted; default callers can stay on
     * {@link #fromGrammar(String, ParserConfig)} which uses
     * {@link GrammarSource#empty()}.
     */
    public static Result<Parser> fromGrammar(String grammarText, ParserConfig config, GrammarSource source) {
        return GrammarParser.parse(grammarText)
                            .flatMap(grammar -> GrammarResolver.resolve(grammar, source))
                            .flatMap(grammar -> fromGrammar(grammar, config));
    }

    /**
     * 0.2.8 — As {@link #fromGrammar(String, ParserConfig, GrammarSource)} with
     * programmatically attached actions.
     */
    public static Result<Parser> fromGrammar(String grammarText,
                                             ParserConfig config,
                                             Actions actions,
                                             GrammarSource source) {
        return GrammarParser.parse(grammarText)
                            .flatMap(grammar -> GrammarResolver.resolve(grammar, source))
                            .flatMap(grammar -> fromGrammar(grammar, config, actions));
    }

    /**
     * Create a parser from a pre-parsed grammar.
     */
    public static Result<Parser> fromGrammar(Grammar grammar) {
        return fromGrammar(grammar, ParserConfig.DEFAULT);
    }

    /**
     * Create a parser from a pre-parsed grammar with custom configuration.
     *
     * <p>0.4.0 — {@code grammar} is assumed to be validated; produce one via
     * {@link Grammar#grammar(java.util.List, org.pragmatica.lang.Option,
     * org.pragmatica.lang.Option, org.pragmatica.lang.Option, java.util.List,
     * java.util.List)} or by parsing through {@link GrammarParser#parse(String)}.
     */
    public static Result<Parser> fromGrammar(Grammar grammar, ParserConfig config) {
        return PegEngine.create(grammar, config)
                        .map(engine -> (Parser) engine);
    }

    /**
     * 0.2.6 — create a parser with programmatically attached lambda actions.
     * Lambdas override any inline grammar actions for rules whose name matches
     * the attached {@link org.pragmatica.peg.action.RuleId} class's simple name.
     * See {@link Actions} for the composable, immutable builder API.
     */
    public static Result<Parser> fromGrammar(String grammarText, ParserConfig config, Actions actions) {
        return GrammarParser.parse(grammarText)
                            .flatMap(grammar -> fromGrammar(grammar, config, actions));
    }

    /**
     * 0.2.6 — as {@link #fromGrammar(String, ParserConfig, Actions)} but
     * starting from an already-parsed {@link Grammar}.
     */
    public static Result<Parser> fromGrammar(Grammar grammar, ParserConfig config, Actions actions) {
        return PegEngine.create(grammar, config, actions)
                        .map(engine -> (Parser) engine);
    }

    /**
     * 0.2.6 — as {@link #fromGrammar(String, ParserConfig, Actions)} with the
     * {@link ParserConfig#DEFAULT} configuration.
     */
    public static Result<Parser> fromGrammar(String grammarText, Actions actions) {
        return fromGrammar(grammarText, ParserConfig.DEFAULT, actions);
    }

    /**
     * Create a parser from grammar without compiling actions.
     * Useful for CST-only parsing where actions are not needed.
     */
    public static Result<Parser> fromGrammarWithoutActions(Grammar grammar, ParserConfig config) {
        return PegEngine.createWithoutActions(grammar, config)
                        .map(engine -> (Parser) engine);
    }

    /**
     * Generate standalone parser source code from grammar text.
     * The generated parser returns Object values.
     *
     * @param grammarText the PEG grammar
     * @param packageName target package for generated class
     * @param className name of generated parser class
     * @return generated Java source code, or error if grammar is invalid
     */
    public static Result<String> generateParser(String grammarText, String packageName, String className) {
        return generateParser(grammarText, packageName, className, ParserConfig.DEFAULT);
    }

    /**
     * Generate standalone parser source code from grammar text with custom parser configuration.
     * The generated parser returns Object values.
     *
     * @param grammarText the PEG grammar
     * @param packageName target package for generated class
     * @param className name of generated parser class
     * @param config parser configuration (perf flags are consumed at generation time)
     * @return generated Java source code, or error if grammar is invalid
     */
    public static Result<String> generateParser(String grammarText,
                                                String packageName,
                                                String className,
                                                ParserConfig config) {
        return GrammarResolver.resolveText(grammarText,
                                           GrammarSource.empty())
                              .map(grammar -> ParserGenerator.parserGenerator(grammar, packageName, className, config)
                                                             .generate());
    }

    /**
     * Generate standalone CST parser source code from grammar text.
     * The generated parser returns CstNode with full tree structure and trivia.
     *
     * @param grammarText the PEG grammar
     * @param packageName target package for generated class
     * @param className name of generated parser class
     * @return generated Java source code, or error if grammar is invalid
     */
    public static Result<String> generateCstParser(String grammarText, String packageName, String className) {
        return generateCstParser(grammarText, packageName, className, ErrorReporting.BASIC, ParserConfig.DEFAULT);
    }

    /**
     * Generate standalone CST parser source code from grammar text with custom parser configuration.
     * Uses {@code ErrorReporting.BASIC}.
     *
     * @param grammarText the PEG grammar
     * @param packageName target package for generated class
     * @param className name of generated parser class
     * @param config parser configuration (perf flags are consumed at generation time)
     * @return generated Java source code, or error if grammar is invalid
     */
    public static Result<String> generateCstParser(String grammarText,
                                                   String packageName,
                                                   String className,
                                                   ParserConfig config) {
        return generateCstParser(grammarText, packageName, className, ErrorReporting.BASIC, config);
    }

    /**
     * Generate standalone CST parser source code from grammar text with configurable error reporting.
     * The generated parser returns CstNode with full tree structure and trivia.
     *
     * @param grammarText the PEG grammar
     * @param packageName target package for generated class
     * @param className name of generated parser class
     * @param errorReporting BASIC for simple errors, ADVANCED for Rust-style diagnostics
     * @return generated Java source code, or error if grammar is invalid
     */
    public static Result<String> generateCstParser(String grammarText,
                                                   String packageName,
                                                   String className,
                                                   ErrorReporting errorReporting) {
        return generateCstParser(grammarText, packageName, className, errorReporting, ParserConfig.DEFAULT);
    }

    /**
     * Generate standalone CST parser source code with both error reporting and parser configuration.
     *
     * @param grammarText the PEG grammar
     * @param packageName target package for generated class
     * @param className name of generated parser class
     * @param errorReporting BASIC for simple errors, ADVANCED for Rust-style diagnostics
     * @param config parser configuration (perf flags are consumed at generation time)
     * @return generated Java source code, or error if grammar is invalid
     */
    public static Result<String> generateCstParser(String grammarText,
                                                   String packageName,
                                                   String className,
                                                   ErrorReporting errorReporting,
                                                   ParserConfig config) {
        return GrammarResolver.resolveText(grammarText,
                                           GrammarSource.empty())
                              .map(grammar -> ParserGenerator.parserGenerator(grammar,
                                                                              packageName,
                                                                              className,
                                                                              errorReporting,
                                                                              config)
                                                             .generateCst());
    }

    /**
     * Create a builder for more complex parser configuration.
     */
    public static Builder builder(String grammarText) {
        return new Builder(grammarText);
    }

    public static final class Builder {
        private final String grammarText;
        private boolean packratEnabled = true;
        private RecoveryStrategy recoveryStrategy = RecoveryStrategy.BASIC;
        private boolean captureTrivia = true;

        private Builder(String grammarText) {
            this.grammarText = grammarText;
        }

        public Builder packrat(boolean enabled) {
            this.packratEnabled = enabled;
            return this;
        }

        public Builder recovery(RecoveryStrategy strategy) {
            this.recoveryStrategy = strategy;
            return this;
        }

        public Builder trivia(boolean capture) {
            this.captureTrivia = capture;
            return this;
        }

        public Result<Parser> build() {
            var config = ParserConfig.parserConfig(packratEnabled, recoveryStrategy, captureTrivia);
            return fromGrammar(grammarText, config);
        }
    }
}
