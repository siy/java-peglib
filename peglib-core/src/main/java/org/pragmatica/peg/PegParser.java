package org.pragmatica.peg;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.peg.error.ParseError;
import org.pragmatica.peg.grammar.Grammar;
import org.pragmatica.peg.grammar.GrammarParser;
import org.pragmatica.peg.grammar.GrammarResolver;
import org.pragmatica.peg.grammar.GrammarSource;
import org.pragmatica.peg.grammar.Import;
import org.pragmatica.peg.source.SourceLocation;
import org.pragmatica.peg.analyzer.LeftRecursionCause;
import org.pragmatica.peg.analyzer.LeftRecursionDetector;
import org.pragmatica.peg.generator.LexerCompiler;
import org.pragmatica.peg.generator.LexerCompiler.CompiledLexer;
import org.pragmatica.peg.generator.LexerGenerator;
import org.pragmatica.peg.generator.ParserCompiler;
import org.pragmatica.peg.generator.ParserCompiler.CompiledParser;
import org.pragmatica.peg.generator.ParserGenerator;
import org.pragmatica.peg.lexer.DfaBuilder;
import org.pragmatica.peg.lexer.RuleClassifier;


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
    private static final String GENERATED_PACKAGE = "org.pragmatica.peg.runtime";
    private static final Map<String, Parser> CACHE = new ConcurrentHashMap<>();
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
        return Option.option(CACHE.get(grammarText))
                     .map(Result::success)
                     .or(() -> GrammarParser.parse(grammarText)
                                            .flatMap(PegParser::requireSourceForImports)
                                            .flatMap(grammar -> build(grammarText, grammar, true)));
    }

    /**
     * Compile {@code grammarText} into a {@link Parser}, resolving any {@code %import}
     * directives through {@code source} first.
     *
     * <p>The transitive import closure is resolved by {@link GrammarResolver} before the
     * generate-compile pipeline runs, so the composed grammar is what gets classified,
     * lexed and generated. A grammar declaring no imports behaves exactly as it does under
     * {@link #fromGrammar(String)}.
     *
     * <p><b>Caching.</b> The parser cache is keyed by grammar text alone, which cannot
     * distinguish the same root text resolved against two different sources. Results are
     * therefore cached only when the root grammar declares no imports; a grammar that does
     * declare imports is recompiled on every call. That costs a cold compile (~100-500 ms)
     * per call and is a deliberate trade — a wrong cache hit would hand back a parser built
     * from someone else's imports.
     *
     * @since 0.7.2
     */
    public static Result<Parser> fromGrammar(String grammarText, GrammarSource source) {
        return Option.option(CACHE.get(grammarText))
                     .map(Result::success)
                     .or(() -> resolveAndBuild(grammarText, source));
    }

    private static Result<Parser> resolveAndBuild(String grammarText, GrammarSource source) {
        return GrammarParser.parse(grammarText)
                            .flatMap(root -> GrammarResolver.resolve(root, source).map(resolved -> new ResolvedGrammar(root,
                                                                                                                       resolved)))
                            .flatMap(composed -> build(grammarText,
                                                       composed.resolved(),
                                                       composed.root().imports().isEmpty()));
    }

    /** Pairs the parsed root with its resolved form, so the cache decision can be made from the
     * ROOT's declared imports rather than the resolved grammar's (which are always empty). */
    private record ResolvedGrammar(Grammar root, Grammar resolved) {}

    /**
     * A grammar that declares {@code %import} cannot be compiled without a
     * {@link GrammarSource}: the imported rules are never brought in, and the failure
     * surfaces much later as a misleading "references undefined rule". Fail here instead,
     * naming the fix.
     */
    private static Result<Grammar> requireSourceForImports(Grammar grammar) {
        if (grammar.imports().isEmpty()) {
            return Result.success(grammar);
        }

        var names = grammar.imports().stream().map(Import::grammarName).distinct().collect(Collectors.joining(", "));

        return new ParseError.SemanticError(SourceLocation.START,
                                            "grammar declares %import (" + names
                                           + ") but no GrammarSource was supplied; "
                                           + "use fromGrammar(grammarText, source)").result();
    }

    private static Result<Parser> build(String cacheKey, Grammar grammar, boolean cacheable) {
        long uid = GEN_COUNTER.incrementAndGet();
        String lexerClassName = "GLexer_" + uid;
        String parserClassName = "GParser_" + uid;

        return checkLeftRecursion(grammar).flatMap(checked -> RuleClassifier.classify(checked).flatMap(classification -> DfaBuilder.build(checked,
                                                                                                                                          classification).flatMap(built -> compileLexer(checked,
                                                                                                                                                                                        classification,
                                                                                                                                                                                        built,
                                                                                                                                                                                        lexerClassName).flatMap(compiledLexer -> compileParser(checked,
                                                                                                                                                                                                                                               classification,
                                                                                                                                                                                                                                               built,
                                                                                                                                                                                                                                               parserClassName).map(compiledParser -> toParser(cacheKey,
                                                                                                                                                                                                                                                                                               checked,
                                                                                                                                                                                                                                                                                               compiledLexer,
                                                                                                                                                                                                                                                                                               compiledParser,
                                                                                                                                                                                                                                                                                               cacheable))))));
    }

    private static Parser toParser(String cacheKey,
                                   Grammar grammar,
                                   CompiledLexer compiledLexer,
                                   CompiledParser compiledParser,
                                   boolean cacheable) {
        return cacheable
               ? cacheAndReturn(cacheKey, grammar, compiledLexer, compiledParser)
               : new Parser(grammar, compiledLexer, compiledParser);
    }

    private static Result<Grammar> checkLeftRecursion(Grammar grammar) {
        return LeftRecursionDetector.detect(grammar).flatMap(result -> grammarOrCause(grammar, result));
    }

    private static Result<Grammar> grammarOrCause(Grammar grammar, LeftRecursionDetector.DetectionResult result) {
        return result.hasErrors()
               ? LeftRecursionCause.of(result).result()
               : Result.success(grammar);
    }

    /** Number of cached grammars; useful for tests verifying cache behaviour. */
    public static int cacheSize() {
        return CACHE.size();
    }

    /** Drop every cached parser. Intended for tests that want a clean slate per case. */
    @SuppressWarnings("JBCT-RET-01")
    public static void clearCache() {
        CACHE.clear();
    }

    private static Parser cacheAndReturn(String grammarText,
                                         Grammar grammar,
                                         CompiledLexer compiledLexer,
                                         CompiledParser compiledParser) {
        Parser parser = new Parser(grammar, compiledLexer, compiledParser);

        return Option.option(CACHE.putIfAbsent(grammarText, parser)).or(parser);
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
        var skippedReasons = built.skipped()
                                  .stream()
                                  .collect(java.util.stream.Collectors.toMap(DfaBuilder.SkippedRule::ruleName,
                                                                             DfaBuilder.SkippedRule::reason,
                                                                             (a, __) -> a));

        return ParserGenerator.generate(grammar,
                                        classification,
                                        built.kinds(),
                                        skippedReasons,
                                        GENERATED_PACKAGE,
                                        className)
                              .flatMap(ParserCompiler::compile);
    }
}
