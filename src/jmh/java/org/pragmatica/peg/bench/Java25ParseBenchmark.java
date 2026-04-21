package org.pragmatica.peg.bench;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import org.pragmatica.peg.PegParser;
import org.pragmatica.peg.error.RecoveryStrategy;
import org.pragmatica.peg.generator.ErrorReporting;
import org.pragmatica.peg.parser.ParserConfig;

import javax.tools.ToolProvider;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * JMH harness for commit #10's default-flipping decision.
 *
 * <p>Parametrized across seven {@link ParserConfig} variants (spec §8, PERF-REWORK-SPEC.md).
 * Each variant compiles a freshly generated parser class at {@link Level#Trial} and benchmarks
 * parsing the 1,900-LOC {@code FactoryClassGenerator.java.txt} fixture with a fresh parser
 * instance per invocation (generated parsers are single-use).
 *
 * <p>Run with: {@code mvn -Pbench clean compile exec:java}.
 */
@BenchmarkMode({Mode.AverageTime, Mode.Throughput})
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(2)
@State(Scope.Benchmark)
public class Java25ParseBenchmark {

    private static final String PACKAGE_NAME = "generated.bench";
    private static final String CLASS_BASE = "Java25BenchParser";
    private static final String GRAMMAR_RESOURCE = "/java25.peg";
    private static final String FIXTURE_RESOURCE = "/perf-corpus/large/FactoryClassGenerator.java.txt";

    @Param({
        "none",
        "phase1",
        "phase1_choiceDispatch",
        "phase1_markResetChildren",
        "phase1_inlineLocations",
        "phase1_allStructural",
        "phase1_allStructural_skipPackrat"
    })
    public String variant;

    private Class<?> parserClass;
    private Method parseMethod;
    private String fixtureSource;

    @Setup(Level.Trial)
    public void setup() throws Exception {
        var grammarText = loadResource(GRAMMAR_RESOURCE);
        fixtureSource = loadResource(FIXTURE_RESOURCE);

        var config = configFor(variant);
        var className = CLASS_BASE + "_" + variant;
        var fqcn = PACKAGE_NAME + "." + className;

        var sourceResult = PegParser.generateCstParser(grammarText, PACKAGE_NAME, className, ErrorReporting.BASIC, config);
        if (sourceResult.isFailure()) {
            throw new IllegalStateException("Failed to generate parser for variant " + variant + ": " + sourceResult);
        }
        parserClass = compileAndLoad(sourceResult.unwrap(), fqcn);
        parseMethod = parserClass.getMethod("parse", String.class);
    }

    @Benchmark
    public Object parse() throws Exception {
        var instance = parserClass.getDeclaredConstructor().newInstance();
        return parseMethod.invoke(instance, fixtureSource);
    }

    private static ParserConfig configFor(String variant) {
        return switch (variant) {
            case "none" -> allOff();
            case "phase1" -> ParserConfig.DEFAULT;
            case "phase1_choiceDispatch" -> withStructural(true, false, false, false, Set.of());
            case "phase1_markResetChildren" -> withStructural(false, true, false, false, Set.of());
            case "phase1_inlineLocations" -> withStructural(false, false, true, false, Set.of());
            case "phase1_allStructural" -> withStructural(true, true, true, false, Set.of());
            case "phase1_allStructural_skipPackrat" -> withStructural(true, true, true, true,
                    Set.of("Identifier", "QualifiedName", "Type"));
            default -> throw new IllegalArgumentException("Unknown variant: " + variant);
        };
    }

    /** All generator-time perf flags off; baseline reference. Runtime flags kept at DEFAULT. */
    private static ParserConfig allOff() {
        return new ParserConfig(
                true,                       // packratEnabled (runtime)
                RecoveryStrategy.BASIC,     // recoveryStrategy (runtime)
                true,                       // captureTrivia (runtime)
                false,                      // fastTrackFailure
                false,                      // literalFailureCache
                false,                      // charClassFailureCache
                false,                      // bulkAdvanceLiteral
                false,                      // skipWhitespaceFastPath
                false,                      // reuseEndLocation
                false,                      // choiceDispatch
                false,                      // markResetChildren
                false,                      // inlineLocations
                false,                      // selectivePackrat
                Set.of());                  // packratSkipRules
    }

    /**
     * Phase-1 flags on (as in DEFAULT) plus caller-selected structural flags. When {@code
     * selectivePackrat} is true, uses the provided skip-set.
     */
    private static ParserConfig withStructural(boolean choiceDispatch,
                                               boolean markResetChildren,
                                               boolean inlineLocations,
                                               boolean selectivePackrat,
                                               Set<String> packratSkipRules) {
        return new ParserConfig(
                true,                       // packratEnabled
                RecoveryStrategy.BASIC,
                true,                       // captureTrivia
                true,                       // fastTrackFailure (phase 1)
                true,                       // literalFailureCache (phase 1)
                true,                       // charClassFailureCache (phase 1)
                true,                       // bulkAdvanceLiteral (phase 1)
                true,                       // skipWhitespaceFastPath (phase 1)
                true,                       // reuseEndLocation (phase 1)
                choiceDispatch,
                markResetChildren,
                inlineLocations,
                selectivePackrat,
                Set.copyOf(packratSkipRules));
    }

    static String loadResource(String resourcePath) throws Exception {
        try (var in = Java25ParseBenchmark.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IllegalStateException("Resource not found on classpath: " + resourcePath);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    static Class<?> compileAndLoad(String source, String fqcn) throws Exception {
        Path tempDir = Files.createTempDirectory("peglib-bench-generated");
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
                sourceFile.toString());
        if (rc != 0) {
            throw new RuntimeException("Compilation failed for " + fqcn + " (rc=" + rc + ")");
        }

        var classLoader = new URLClassLoader(
                new URL[]{tempDir.toUri().toURL()},
                Java25ParseBenchmark.class.getClassLoader());
        return classLoader.loadClass(fqcn);
    }
}
