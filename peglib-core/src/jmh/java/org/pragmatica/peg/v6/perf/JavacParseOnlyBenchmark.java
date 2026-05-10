package org.pragmatica.peg.v6.perf;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.JavacTask;

import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

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
import org.openjdk.jmh.infra.Blackhole;

/**
 * Apples-to-apples comparison: javac's parse phase isolated via JavacTask.parse(),
 * on the SAME canonical large Java25 fixtures used by {@link Java25LargeFixturesBenchmark}.
 *
 * <p>{@link JavacTask#parse()} runs only the parser phase (no enter, no attribute, no
 * resolve), giving a clean parse-only baseline. A fresh task is created per invocation
 * because parsing state is per-task.
 *
 * <p>Run:
 * <pre>{@code
 * java -jar target/benchmarks.jar JavacParseOnlyBenchmark -prof gc -wi 3 -i 5 -f 1
 * }</pre>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Fork(1)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
public class JavacParseOnlyBenchmark {

    private static final Path REFERENCE_FIXTURE =
        Path.of("src/test/resources/perf-corpus/large/FactoryClassGenerator.java.txt");
    private static final Path SELFHOST_FIXTURE =
        Path.of("src/test/resources/bench-fixtures/Java25SelfHost-v51.java.txt");

    @Param({"reference", "selfhost"})
    public String fixture;

    private JavaCompiler compiler;
    private List<JavaFileObject> sources;

    @Setup(Level.Trial)
    public void setup() throws Exception {
        String content;
        String name;
        if ("reference".equals(fixture)) {
            content = Files.readString(REFERENCE_FIXTURE, StandardCharsets.UTF_8);
            name = "FactoryClassGenerator.java";
        } else if ("selfhost".equals(fixture)) {
            content = Files.readString(SELFHOST_FIXTURE, StandardCharsets.UTF_8);
            name = "Java25SelfHost.java";
        } else {
            throw new IllegalStateException("unknown fixture: " + fixture);
        }

        // Wrap in an in-memory JavaFileObject. javac requires a Kind.SOURCE entry;
        // the URI scheme can be anything as long as we override getCharContent.
        final String fname = name;
        final String src = content;
        sources = List.of(new SimpleJavaFileObject(
            URI.create("mem:///" + fname),
            JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return src;
            }
        });

        compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException(
                "No JavaCompiler available — JDK (not JRE) required");
        }
    }

    @Benchmark
    public Iterable<? extends CompilationUnitTree> javacParse(Blackhole bh) throws Exception {
        // Fresh task per invocation: parsing state is per-task. null DiagnosticListener
        // and null Writer route diagnostics to System.err; we don't expect any on the
        // reference fixture (hand-written Java 25). For selfhost (synthesized parser
        // source), syntactic diagnostics may appear but parse() still returns a tree.
        JavacTask task = (JavacTask) compiler.getTask(null, null, null, null, null, sources);
        Iterable<? extends CompilationUnitTree> trees = task.parse();
        bh.consume(trees);
        return trees;
    }
}
