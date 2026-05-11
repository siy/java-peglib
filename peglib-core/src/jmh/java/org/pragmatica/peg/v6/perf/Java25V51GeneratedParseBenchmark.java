package org.pragmatica.peg.v6.perf;

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
import org.pragmatica.peg.generator.ErrorReporting;
import org.pragmatica.peg.parser.ParserConfig;

import javax.tools.ToolProvider;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * Phase F.1 ship-vs-ship benchmark — measures the 0.5.x SOURCE-GENERATED parser
 * (the path real users ship), counterpart to {@link Java25V51ParseBenchmark}
 * (interpreter) and {@link Java25V6ParseBenchmark} (0.6.0 lex-then-parse).
 *
 * <p>Setup (one-time, hoisted out of measurement at {@link Level#Trial}):
 * <ol>
 *   <li>Read {@code java25.peg}.</li>
 *   <li>Call {@link PegParser#generateCstParser(String, String, String, ErrorReporting,
 *       ParserConfig)} with the same {@link ParserConfig#DEFAULT} the interpreter
 *       uses, producing the standalone parser source.</li>
 *   <li>Compile via JDK Compiler API (mirrors
 *       {@link org.pragmatica.peg.bench.Java25ParseBenchmark#compileAndLoad}).</li>
 *   <li>Cache the loaded {@code Class}, no-arg {@code Constructor}, and
 *       {@code parse(String)} {@code Method} handles for hot-path reuse.</li>
 * </ol>
 *
 * <p>The benchmark allocates a fresh parser instance per invocation because 0.5.x
 * generated parsers are single-use (mutable state). This matches how the
 * existing 0.5.x corpus benchmark
 * {@link org.pragmatica.peg.bench.Java25ParseBenchmark#parse} is written.
 */
@BenchmarkMode({Mode.AverageTime, Mode.Throughput})
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@State(Scope.Benchmark)
public class Java25V51GeneratedParseBenchmark {

    private static final Path GRAMMAR_PATH = Path.of("src/test/resources/java25.peg");
    private static final Path FIXTURE_DIR = Path.of("src/test/resources/perf-corpus/format-examples");
    private static final String PACKAGE_NAME = "v51.gen";
    private static final String CLASS_NAME = "Java25Parser51";

    @Param({
        "BlankLines.java",
        "Comments.java",
        "CompoundAssignments.java",
        "Enums.java",
        "ExhaustiveSwitchPatterns.java",
        "Imports.java",
        "KeywordPrefixedIdentifiers.java",
        "ModuleInfo.java",
        "Records.java",
        "SwitchExpressions.java",
        "TernaryOperators.java",
        "TextBlocks.java"
    })
    public String fixture;

    private Class<?> parserClass;
    private Constructor<?> parserCtor;
    private Method parseMethod;
    private String input;

    @Setup(Level.Trial)
    public void setup() throws Exception {
        var grammarText = Files.readString(GRAMMAR_PATH, StandardCharsets.UTF_8);
        input = Files.readString(FIXTURE_DIR.resolve(fixture), StandardCharsets.UTF_8);

        var fqcn = PACKAGE_NAME + "." + CLASS_NAME;
        var sourceResult = PegParser.generateCstParser(
                grammarText, PACKAGE_NAME, CLASS_NAME, ErrorReporting.BASIC, ParserConfig.DEFAULT);
        if (sourceResult.isFailure()) {
            throw new IllegalStateException("Failed to generate 0.5.x parser source: " + sourceResult);
        }

        parserClass = compileAndLoad(sourceResult.unwrap(), fqcn);
        parserCtor = parserClass.getDeclaredConstructor();
        parseMethod = parserClass.getMethod("parse", String.class);
    }

    @Benchmark
    public Object parse() throws Exception {
        // 0.5.x generated parsers are single-use (mutable state); allocate per
        // invocation, mirroring org.pragmatica.peg.bench.Java25ParseBenchmark.
        var instance = parserCtor.newInstance();
        return parseMethod.invoke(instance, input);
    }

    private static Class<?> compileAndLoad(String source, String fqcn) throws Exception {
        Path tempDir = Files.createTempDirectory("peglib-v51gen-bench");
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
                Java25V51GeneratedParseBenchmark.class.getClassLoader());
        return classLoader.loadClass(fqcn);
    }
}
