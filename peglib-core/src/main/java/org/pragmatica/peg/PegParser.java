package org.pragmatica.peg;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
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

    /**
     * Single-flight cache. The value is a future rather than a {@link Parser} so that N threads
     * racing on the same uncached grammar serialise on the BUILD, not merely on the publish:
     * previously each of them ran the full classify → DFA → compile pipeline (~100-500 ms) and
     * all but one result was discarded.
     *
     * <p>A failed build removes its own entry, so a later call retries rather than inheriting a
     * permanent failure, while threads already waiting still receive that failure.
     */
    private static final Map<String, Result<Parser>> CACHE = new ConcurrentHashMap<>();
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
        return singleFlight(grammarText,
                            () -> GrammarParser.parse(grammarText)
                                               .flatMap(PegParser::requireSourceForImports)
                                               .flatMap(grammar -> build(grammar)));
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
        return resolveAndBuild(grammarText, source);
    }

    private static Result<Parser> resolveAndBuild(String grammarText, GrammarSource source) {
        return GrammarParser.parse(grammarText)
                            .flatMap(root -> GrammarResolver.resolve(root, source).map(resolved -> new ResolvedGrammar(grammarText,
                                                                                                                       root,
                                                                                                                       resolved)))
                            .flatMap(PegParser::buildResolved);
    }

    /**
     * A grammar declaring imports is never cached: the cache is keyed by root text, which cannot
     * distinguish two {@link GrammarSource}s. The decision reads the ROOT's declared imports —
     * the resolved grammar's are always empty.
     */
    private static Result<Parser> buildResolved(ResolvedGrammar composed) {
        return composed.root()
                       .imports()
                       .isEmpty()
               ? singleFlight(composed.rootText(), () -> build(composed.resolved()))
               : build(composed.resolved());
    }

    /**
     * Build once per key. {@code computeIfAbsent} runs the mapping function under the bin lock,
     * so N threads racing on the same uncached grammar serialise on the BUILD rather than each
     * running the full classify → DFA → compile pipeline (~100-500 ms) and discarding all but
     * one result.
     *
     * <p>A failed build removes its own entry, so a later call retries instead of inheriting a
     * permanent failure. Safe to block in the mapping function here because nothing in the build
     * path touches {@code CACHE} — this is its only writer.
     */
    private static Result<Parser> singleFlight(String cacheKey, Supplier<Result<Parser>> builder) {
        var result = CACHE.computeIfAbsent(cacheKey, __ -> builder.get());

        result.onFailure(__ -> CACHE.remove(cacheKey, result));

        return result;
    }

    private record ResolvedGrammar(String rootText, Grammar root, Grammar resolved) {}

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

    private static Result<Parser> build(Grammar grammar) {
        long uid = GEN_COUNTER.incrementAndGet();
        String lexerClassName = "GLexer_" + uid;
        String parserClassName = "GParser_" + uid;

        return checkLeftRecursion(grammar).flatMap(PegParser::prepare)
                                 .flatMap(prepared -> compileBoth(prepared, lexerClassName, parserClassName));
    }

    /** Classification plus the DFA it feeds — carried forward together instead of through nested closures. */
    private record Prepared(Grammar grammar, RuleClassifier.Classification classification, DfaBuilder.Built built) {}

    private static Result<Prepared> prepare(Grammar grammar) {
        return RuleClassifier.classify(grammar).flatMap(classification -> DfaBuilder.build(grammar, classification).map(built -> new Prepared(grammar,
                                                                                                                                              classification,
                                                                                                                                              built)));
    }

    /**
     * Lexer and parser compilation are independent — neither consumes the other's output — so
     * they run as a Fork-Join. Chaining them meant a lexer failure hid a simultaneous parser
     * failure, which is the wrong trade for codegen errors a grammar author needs to see whole.
     */
    private static Result<Parser> compileBoth(Prepared prepared, String lexerClassName, String parserClassName) {
        return Result.all(compileLexer(prepared.grammar(),
                                       prepared.classification(),
                                       prepared.built(),
                                       lexerClassName),
                          compileParser(prepared.grammar(),
                                        prepared.classification(),
                                        prepared.built(),
                                        parserClassName))
                     .map((compiledLexer, compiledParser) -> new Parser(prepared.grammar(),
                                                                        compiledLexer,
                                                                        compiledParser));
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
