package org.pragmatica.peg;

import org.pragmatica.lang.Result;
import org.pragmatica.peg.error.RecoveryStrategy;
import org.pragmatica.peg.generator.ErrorReporting;
import org.pragmatica.peg.generator.ParserGenerator;
import org.pragmatica.peg.grammar.Grammar;
import org.pragmatica.peg.grammar.GrammarParser;
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
        return GrammarParser.parse(grammarText)
                            .flatMap(grammar -> fromGrammar(grammar, config));
    }

    /**
     * Create a parser from a pre-parsed grammar.
     */
    public static Result<Parser> fromGrammar(Grammar grammar) {
        return fromGrammar(grammar, ParserConfig.DEFAULT);
    }

    /**
     * Create a parser from a pre-parsed grammar with custom configuration.
     */
    public static Result<Parser> fromGrammar(Grammar grammar, ParserConfig config) {
        return grammar.validate()
                      .flatMap(g -> PegEngine.create(g, config)
                                             .map(engine -> (Parser) engine));
    }

    /**
     * Create a parser from grammar without compiling actions.
     * Useful for CST-only parsing where actions are not needed.
     */
    public static Result<Parser> fromGrammarWithoutActions(Grammar grammar, ParserConfig config) {
        return grammar.validate()
                      .map(g -> PegEngine.createWithoutActions(g, config));
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
        return GrammarParser.parse(grammarText)
                            .flatMap(Grammar::validate)
                            .map(grammar -> ParserGenerator.create(grammar, packageName, className)
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
        return GrammarParser.parse(grammarText)
                            .flatMap(Grammar::validate)
                            .map(grammar -> ParserGenerator.create(grammar, packageName, className)
                                                           .generateCst());
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
        return GrammarParser.parse(grammarText)
                            .flatMap(Grammar::validate)
                            .map(grammar -> ParserGenerator.create(grammar, packageName, className, errorReporting)
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
            var config = new ParserConfig(packratEnabled, recoveryStrategy, captureTrivia);
            return fromGrammar(grammarText, config);
        }
    }
}
