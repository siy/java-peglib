package org.pragmatica.peg.v6;

import org.pragmatica.lang.Result;
import org.pragmatica.peg.grammar.Grammar;
import org.pragmatica.peg.grammar.GrammarParser;
import org.pragmatica.peg.v6.analyzer.LeftRecursionCause;
import org.pragmatica.peg.v6.analyzer.LeftRecursionDetector;
import org.pragmatica.peg.v6.analyzer.NamedCaptureCause;
import org.pragmatica.peg.v6.analyzer.NamedCaptureDetector;
import org.pragmatica.peg.v6.generator.LexerCompiler;
import org.pragmatica.peg.v6.generator.LexerCompiler.CompiledLexer;
import org.pragmatica.peg.v6.generator.LexerGenerator;
import org.pragmatica.peg.v6.generator.ParserCompiler;
import org.pragmatica.peg.v6.generator.ParserCompiler.CompiledParser;
import org.pragmatica.peg.v6.generator.ParserGenerator;
import org.pragmatica.peg.v6.lexer.DfaBuilder;
import org.pragmatica.peg.v6.lexer.RuleClassifier;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Phase C.1 — top-level entry point for the 0.6.0 generate-compile-cache pipeline.
 *
 * <p>{@link #fromGrammar(String)} runs the full classify → DFA → generate-lexer →
 * compile-lexer → generate-parser → compile-parser pipeline on first call (~100-500ms
 * cold) and caches the resulting {@link Parser} keyed by exact grammar text. Subsequent
 * calls with the same grammar text return the cached parser in lookup-only time
 * (sub-millisecond).
 *
 * <p>Generated class names are uniquified per cache miss with a process-wide
 * {@link AtomicLong} counter so two different grammars never collide on a class
 * name in the JVM's class loader.
 */
public final class PegParser {
    private static final String GENERATED_PACKAGE = "org.pragmatica.peg.v6.runtime";
    private static final Map<String, Parser>CACHE = new ConcurrentHashMap<>();
    private static final AtomicLong GEN_COUNTER = new AtomicLong();

    private PegParser() {}

    /**
     * Compile {@code grammarText} into a {@link Parser}, caching the result by
     * exact text. The pipeline is:
     * <ol>
     *   <li>{@link GrammarParser#parse(String)} — text → {@link Grammar}.</li>
     *   <li>{@link RuleClassifier#classify(Grammar)} — per-rule LEXER/PARSER/MIXED labelling.</li>
     *   <li>{@link DfaBuilder#build} — combined DFA + token-kind table for all LEXER rules + inline literals.</li>
     *   <li>{@link LexerGenerator#generate} + {@link LexerCompiler#compile} — emit and load the lexer class.</li>
     *   <li>{@link ParserGenerator#generate} + {@link ParserCompiler#compile} — emit and load the parser class.</li>
     * </ol>
     */
    public static Result<Parser> fromGrammar(String grammarText) {
        if (grammarText == null) {
            throw new IllegalArgumentException("grammarText must not be null");
        }
        Parser cached = CACHE.get(grammarText);
        if (cached != null) {
            return Result.success(cached);
        }
        long uid = GEN_COUNTER.incrementAndGet();
        String lexerClassName = "GLexer_" + uid;
        String parserClassName = "GParser_" + uid;
        return GrammarParser.parse(grammarText)
                            .flatMap(PegParser::checkLeftRecursion)
                            .flatMap(PegParser::checkNamedCaptures)
                            .flatMap(grammar -> RuleClassifier.classify(grammar)
                                                              .flatMap(classification -> DfaBuilder.build(grammar,
                                                                                                          classification)
                                                                                                   .flatMap(built -> compileLexer(grammar,
                                                                                                                                  classification,
                                                                                                                                  built,
                                                                                                                                  lexerClassName)
                                                                                                                     .flatMap(compiledLexer -> compileParser(grammar,
                                                                                                                                                             classification,
                                                                                                                                                             built,
                                                                                                                                                             parserClassName)
                                                                                                                                               .map(compiledParser -> cacheAndReturn(grammarText,
                                                                                                                                                                                     grammar,
                                                                                                                                                                                     compiledLexer,
                                                                                                                                                                                     compiledParser))))));
    }

    private static Result<Grammar> checkLeftRecursion(Grammar grammar) {
        return LeftRecursionDetector.detect(grammar)
                                    .flatMap(result -> result.hasErrors()
                                                       ? LeftRecursionCause.of(result)
                                                                           .result()
                                                       : Result.success(grammar));
    }

    private static Result<Grammar> checkNamedCaptures(Grammar grammar) {
        return NamedCaptureDetector.detect(grammar)
                                   .flatMap(result -> result.hasOccurrences()
                                                      ? NamedCaptureCause.of(result)
                                                                         .result()
                                                      : Result.success(grammar));
    }

    /** Number of cached grammars; useful for tests verifying cache behaviour. */
    public static int cacheSize() {
        return CACHE.size();
    }

    /** Drop every cached parser. Intended for tests that want a clean slate per case. */
    public static void clearCache() {
        CACHE.clear();
    }

    private static Parser cacheAndReturn(String grammarText,
                                         Grammar grammar,
                                         CompiledLexer compiledLexer,
                                         CompiledParser compiledParser) {
        Parser parser = new Parser(grammar, compiledLexer, compiledParser);
        Parser existing = CACHE.putIfAbsent(grammarText, parser);
        return existing != null
               ? existing
               : parser;
    }

    private static Result<CompiledLexer> compileLexer(Grammar grammar,
                                                      RuleClassifier.Classification classification,
                                                      DfaBuilder.Built built,
                                                      String className) {
        return LexerGenerator.generate(grammar,
                                       classification,
                                       built.dfa(),
                                       built.kinds(),
                                       GENERATED_PACKAGE,
                                       className)
                             .flatMap(LexerCompiler::compile);
    }

    private static Result<CompiledParser> compileParser(Grammar grammar,
                                                        RuleClassifier.Classification classification,
                                                        DfaBuilder.Built built,
                                                        String className) {
        return ParserGenerator.generate(grammar,
                                        classification,
                                        built.kinds(),
                                        GENERATED_PACKAGE,
                                        className)
                              .flatMap(ParserCompiler::compile);
    }
}
