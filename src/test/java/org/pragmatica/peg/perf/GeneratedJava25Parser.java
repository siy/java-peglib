package org.pragmatica.peg.perf;

import org.pragmatica.lang.Result;
import org.pragmatica.peg.PegParser;
import org.pragmatica.peg.generator.ErrorReporting;
import org.pragmatica.peg.parser.ParserConfig;

import javax.tools.ToolProvider;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Helper that generates, compiles, and caches an instance of the Java 25 generated parser
 * once per JVM. Callers invoke {@link #parse(String)} to parse input; a fresh parser instance
 * is created per call (the generated parser is single-use — cache is keyed per-parse).
 *
 * <p>Generated source is compiled to a temp directory; no generated code is checked into
 * the repo or into {@code target/}.
 *
 * <p>Optionally accepts a {@link ParserConfig} so tests can exercise the generator's
 * phase-1 perf flags. Each distinct config produces a separately compiled parser class,
 * cached per-config for the lifetime of the JVM.
 */
public final class GeneratedJava25Parser {

    private static final String PACKAGE_NAME = "generated.perf";
    private static final String CLASS_NAME = "Java25PerfParser";
    private static final String FQCN = PACKAGE_NAME + "." + CLASS_NAME;

    private static volatile Class<?> parserClass;
    private static volatile Method parseMethod;
    private static final Object LOCK = new Object();

    // Per-config cache for phase-1 parity testing. Key is a derived class name suffix.
    private record CompiledParser(Class<?> parserClass, Method parseMethod) {}
    private static final ConcurrentHashMap<String, CompiledParser> CONFIGURED_CACHE = new ConcurrentHashMap<>();

    private GeneratedJava25Parser() {}

    /**
     * Load the grammar, generate a CST parser for it, compile, and cache. Idempotent.
     */
    public static void ensureLoaded() {
        if (parserClass != null) return;
        synchronized (LOCK) {
            if (parserClass != null) return;
            var grammarText = loadGrammar();
            var sourceResult = PegParser.generateCstParser(grammarText, PACKAGE_NAME, CLASS_NAME, ErrorReporting.BASIC);
            if (sourceResult.isFailure()) {
                throw new IllegalStateException("Failed to generate parser: " + sourceResult);
            }
            var source = sourceResult.unwrap();
            try {
                parserClass = compileAndLoad(source, FQCN);
                parseMethod = parserClass.getMethod("parse", String.class);
            } catch (Exception e) {
                throw new IllegalStateException("Failed to compile generated Java25 parser", e);
            }
        }
    }

    /**
     * Parse {@code input} with a fresh instance of the generated parser. Returns the generated
     * parser's inner {@code Result<CstNode>} as a raw Object — callers use reflection (or the
     * project's {@code Result} unwrap shape) to extract the CST.
     */
    public static Object parse(String input) {
        ensureLoaded();
        try {
            var instance = parserClass.getDeclaredConstructor().newInstance();
            return parseMethod.invoke(instance, input);
        } catch (Exception e) {
            throw new RuntimeException("Parse invocation failed", e);
        }
    }

    /**
     * Parse {@code input} and return the CST node on success, or throw with a descriptive
     * message on failure. This adapts the generated parser's {@code Result} (which is the
     * library's {@code org.pragmatica.lang.Result}) via its public API.
     */
    public static Object parseToCst(String input) {
        var rawResult = parse(input);
        if (!(rawResult instanceof Result<?> result)) {
            throw new IllegalStateException("Unexpected parse return type: " + rawResult.getClass());
        }
        if (result.isFailure()) {
            throw new RuntimeException("Parse failed: " + result);
        }
        return result.unwrap();
    }

    /**
     * Parse {@code input} with a parser generated using the given {@link ParserConfig}.
     * The compiled parser is cached per-config for the lifetime of the JVM.
     *
     * @param input input text
     * @param config generator-time configuration (phase-1 perf flags etc.)
     * @return CST node on success; throws on failure
     */
    public static Object parseToCst(String input, ParserConfig config) {
        var compiled = compiledFor(config);
        try {
            var instance = compiled.parserClass.getDeclaredConstructor().newInstance();
            var raw = compiled.parseMethod.invoke(instance, input);
            if (!(raw instanceof Result<?> result)) {
                throw new IllegalStateException("Unexpected parse return type: " + raw.getClass());
            }
            if (result.isFailure()) {
                throw new RuntimeException("Parse failed: " + result);
            }
            return result.unwrap();
        } catch (Exception e) {
            throw new RuntimeException("Parse invocation failed", e);
        }
    }

    private static CompiledParser compiledFor(ParserConfig config) {
        var key = configKey(config);
        return CONFIGURED_CACHE.computeIfAbsent(key, k -> compileForConfig(k, config));
    }

    private static CompiledParser compileForConfig(String key, ParserConfig config) {
        var grammarText = loadGrammar();
        var className = CLASS_NAME + "_" + key;
        var fqcn = PACKAGE_NAME + "." + className;
        var sourceResult = PegParser.generateCstParser(grammarText, PACKAGE_NAME, className, ErrorReporting.BASIC, config);
        if (sourceResult.isFailure()) {
            throw new IllegalStateException("Failed to generate parser for config " + key + ": " + sourceResult);
        }
        var source = sourceResult.unwrap();
        try {
            var cls = compileAndLoad(source, fqcn);
            var m = cls.getMethod("parse", String.class);
            return new CompiledParser(cls, m);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to compile generated Java25 parser for config " + key, e);
        }
    }

    private static String configKey(ParserConfig c) {
        var sb = new StringBuilder();
        sb.append(c.packratEnabled() ? 'P' : 'p');
        sb.append(c.captureTrivia() ? 'T' : 't');
        sb.append(c.fastTrackFailure() ? 'F' : 'f');
        sb.append(c.literalFailureCache() ? 'L' : 'l');
        sb.append(c.charClassFailureCache() ? 'C' : 'c');
        sb.append(c.bulkAdvanceLiteral() ? 'B' : 'b');
        sb.append(c.skipWhitespaceFastPath() ? 'W' : 'w');
        sb.append(c.reuseEndLocation() ? 'E' : 'e');
        sb.append(c.choiceDispatch() ? 'D' : 'd');
        sb.append(c.markResetChildren() ? 'M' : 'm');
        sb.append(c.inlineLocations() ? 'I' : 'i');
        sb.append(c.selectivePackrat() ? 'S' : 's');
        return sb.toString();
    }

    public static String loadGrammar() {
        try (var in = GeneratedJava25Parser.class.getResourceAsStream("/java25.peg")) {
            if (in == null) {
                throw new IllegalStateException("java25.peg not found on classpath");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load java25.peg", e);
        }
    }

    private static Class<?> compileAndLoad(String source, String fqcn) throws Exception {
        var tempDir = Files.createTempDirectory("peglib-perf-generated");
        var packagePath = fqcn.substring(0, fqcn.lastIndexOf('.')).replace('.', '/');
        var simpleClassName = fqcn.substring(fqcn.lastIndexOf('.') + 1);

        var packageDir = tempDir.resolve(packagePath);
        Files.createDirectories(packageDir);

        var sourceFile = packageDir.resolve(simpleClassName + ".java");
        Files.writeString(sourceFile, source);

        var compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("JDK compiler not available (not running on a JDK?)");
        }
        int rc = compiler.run(null, null, null,
            "-d", tempDir.toString(),
            "-cp", System.getProperty("java.class.path"),
            sourceFile.toString()
        );
        if (rc != 0) {
            throw new RuntimeException("Compilation failed for " + fqcn + " (rc=" + rc + ")");
        }

        var classLoader = new URLClassLoader(
            new URL[]{tempDir.toUri().toURL()},
            GeneratedJava25Parser.class.getClassLoader()
        );
        return classLoader.loadClass(fqcn);
    }
}
