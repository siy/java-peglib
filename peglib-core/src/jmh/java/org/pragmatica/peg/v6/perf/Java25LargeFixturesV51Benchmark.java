package org.pragmatica.peg.v6.perf;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import javax.tools.ToolProvider;

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

/**
 * 0.5.x SOURCE-GENERATED parser benchmark on canonical LARGE Java25 fixtures.
 *
 * <p>Mirrors {@link Java25LargeFixturesBenchmark} so the two paths can be compared
 * apples-to-apples. The "selfhost" fixture is the 0.6.0 v6 generator's emitted
 * parser source — same selfhost input both 0.5.x-gen and 0.6.0 are asked to parse,
 * which is the realistic stress test for grammars that ship parser code.
 *
 * <p>Setup (one-time, hoisted out of measurement at {@link Level#Trial}):
 * <ol>
 *   <li>Read {@code java25.peg}.</li>
 *   <li>Generate 0.5.x parser source via
 *       {@link PegParser#generateCstParser(String, String, String, ErrorReporting, ParserConfig)}.</li>
 *   <li>Compile with the JDK Compiler API and cache reflective handles.</li>
 *   <li>Read/produce the input fixture.</li>
 * </ol>
 *
 * <p>0.5.x generated parsers are single-use (mutable state); the benchmark allocates
 * a fresh instance per invocation, matching {@link Java25V51GeneratedParseBenchmark}.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Fork(1)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
public class Java25LargeFixturesV51Benchmark {

    private static final Path GRAMMAR_PATH = Path.of("src/test/resources/java25.peg");
    private static final Path REFERENCE_FIXTURE =
        Path.of("src/test/resources/perf-corpus/large/FactoryClassGenerator.java.txt");
    /**
     * Pre-generated, ASCII-sanitized 0.5.x source-generated parser. Same file the v6 bench reads —
     * guarantees apples-to-apples comparison on identical input bytes.
     */
    private static final Path SELFHOST_FIXTURE =
        Path.of("src/test/resources/bench-fixtures/Java25SelfHost-v51.java.txt");
    private static final String PACKAGE_NAME = "v51.gen.large";
    private static final String CLASS_NAME = "Java25Parser51Large";

    @Param({"reference", "selfhost"})
    public String fixture;

    private Constructor<?> parserCtor;
    private Method parseMethod;
    private String input;

    @Setup(Level.Trial)
    public void setup() throws Exception {
        var grammarText = Files.readString(GRAMMAR_PATH, StandardCharsets.UTF_8);

        var fqcn = PACKAGE_NAME + "." + CLASS_NAME;
        var sourceResult = PegParser.generateCstParser(
            grammarText, PACKAGE_NAME, CLASS_NAME, ErrorReporting.BASIC, ParserConfig.DEFAULT);
        if (sourceResult.isFailure()) {
            throw new IllegalStateException("Failed to generate 0.5.x parser source: " + sourceResult);
        }

        var parserClass = compileAndLoad(sourceResult.unwrap(), fqcn);
        parserCtor = parserClass.getDeclaredConstructor();
        parseMethod = parserClass.getMethod("parse", String.class);

        if ("reference".equals(fixture)) {
            input = sanitizeForJava25Lexer(Files.readString(REFERENCE_FIXTURE, StandardCharsets.UTF_8));
        } else if ("selfhost".equals(fixture)) {
            // Pre-generated, ASCII-sanitized 0.5.x source-generated parser. SAME bytes as
            // the v6 bench reads — guarantees apples-to-apples comparison.
            input = Files.readString(SELFHOST_FIXTURE, StandardCharsets.UTF_8);
        } else {
            throw new IllegalStateException("unknown fixture: " + fixture);
        }
    }

    /**
     * Same sanitization as {@link Java25LargeFixturesBenchmark#sanitizeForJava25Lexer(String)}.
     * Keeps both paths apples-to-apples on identical input bytes.
     */
    private static String sanitizeForJava25Lexer(String s) {
        var sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            sb.append(c < 0x80 ? c : ' ');
        }
        return sb.toString();
    }

    @Benchmark
    public Object parse() throws Exception {
        // Allocate fresh per invocation — 0.5.x generated parsers carry mutable state.
        var instance = parserCtor.newInstance();
        return parseMethod.invoke(instance, input);
    }

    private static Class<?> compileAndLoad(String source, String fqcn) throws Exception {
        Path tempDir = Files.createTempDirectory("peglib-v51gen-large-bench");
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
            Java25LargeFixturesV51Benchmark.class.getClassLoader());
        return classLoader.loadClass(fqcn);
    }
}
