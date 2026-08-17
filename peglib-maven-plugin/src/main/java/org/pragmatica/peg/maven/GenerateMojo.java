package org.pragmatica.peg.maven;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.peg.grammar.Grammar;
import org.pragmatica.peg.grammar.GrammarParser;
import org.pragmatica.peg.grammar.GrammarResolver;
import org.pragmatica.peg.grammar.GrammarSource;
import org.pragmatica.peg.generator.LexerGenerator;
import org.pragmatica.peg.generator.LexerGenerator.Generated;
import org.pragmatica.peg.generator.ParserGenerator;
import org.pragmatica.peg.generator.ParserGenerator.GeneratedParser;
import org.pragmatica.peg.generator.VisitorGenerator;
import org.pragmatica.peg.generator.VisitorGenerator.GeneratedVisitor;
import org.pragmatica.peg.lexer.DfaBuilder;
import org.pragmatica.peg.lexer.RuleClassifier;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;


/**
 * Emit a standalone lexer + parser + visitor source triple
 * for the supplied grammar. This mojo runs the {@code classify -> DFA build ->
 * generate-lexer/parser/visitor} pipeline at build time and writes three Java
 * source files under {@code outputDirectory/<packageDir>/}.
 *
 * <p>Since 0.7.0 this is the only codegen mojo: the legacy {@code generate}
 * mojo, which targeted the 0.5.x interpreter, was removed together with the
 * rest of the 0.5.x path. This mojo emits the lex-then-parse surface, and
 * the sources it writes depend only on {@code peglib-runtime}.
 *
 * <p>Up-to-date: regeneration is skipped when ALL three target source files
 * are newer than the grammar file. If any one is missing or stale every
 * artifact is regenerated.
 */
@Mojo(name = "generate", defaultPhase = LifecyclePhase.GENERATE_SOURCES, threadSafe = true)
public class GenerateMojo extends AbstractMojo {
    @Parameter(property = "peglib.grammarFile", required = true)
    private File grammarFile;

    @Parameter(property = "peglib.outputDirectory", required = true)
    private File outputDirectory;

    @Parameter(property = "peglib.packageName", required = true)
    private String packageName;

    @Parameter(property = "peglib.lexerClassName", defaultValue = "GLexer")
    private String lexerClassName;

    @Parameter(property = "peglib.parserClassName", defaultValue = "GParser")
    private String parserClassName;

    /**
     * Directory searched for grammars named by {@code %import}. Defaults to the
     * directory holding {@code grammarFile}, so a grammar importing {@code Shared.Rule}
     * finds {@code Shared.peg} beside it. Ignored when the grammar declares no imports.
     *
     * @since 0.7.2
     */
    @Parameter(property = "peglib.importDirectory")
    private File importDirectory;

    @Parameter(property = "peglib.visitorClassName", defaultValue = "GVisitor")
    private String visitorClassName;

    /**
     * JBCT boundary: Maven calls into untyped Java land. The Result pipeline
     * below composes the failure-prone steps; the terminal consumer translates
     * Result.failure(cause) into MojoFailureException(cause.message()).
     */
    @Override
    @SuppressWarnings({"JBCT-RET-01", "JBCT-EX-01"})  // Maven AbstractMojo contract: execute() is void and signals failure by throwing.
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (grammarFile == null || !grammarFile.isFile()) {
            throw new MojoFailureException("grammarFile does not exist: " + grammarFile);
        }

        var lexerTarget = targetSourceFile(lexerClassName);
        var parserTarget = targetSourceFile(parserClassName);
        var visitorTarget = targetSourceFile(visitorClassName);

        if (allUpToDate(lexerTarget, parserTarget, visitorTarget)) {
            getLog().info("peglib:generate skipped (up-to-date, " + GENERATOR_STAMP.substring(STAMP_PREFIX.length()).trim()
                         + "): " + lexerTarget.getFileName()
                         + ", " + parserTarget.getFileName()
                         + ", " + visitorTarget.getFileName());

            return;
        }

        var generated = readGrammar(grammarFile.toPath()).flatMap(this::buildAll);

        if (generated instanceof Result.Failure<?> failure) {
            throw new MojoFailureException(failure.cause().message());
        }

        var bundle = generated.unwrap();
        var write = writeAll(bundle, lexerTarget, parserTarget, visitorTarget);

        if (write instanceof Result.Failure<?> failure) {
            throw new MojoExecutionException(failure.cause().message());
        }

        for (var w : bundle.lexer().warnings()) {
            getLog().warn("peglib:generate lexer warning: " + w);
        }

        getLog().info("peglib:generate wrote " + lexerTarget.getFileName()
                     + ", " + parserTarget.getFileName()
                     + ", " + visitorTarget.getFileName());
    }

    record GeneratedBundle(Generated lexer, GeneratedParser parser, GeneratedVisitor visitor) {}

    /**
     * Compose the generator pipeline: parse grammar text, classify rules,
     * build the DFA + token-kind table, then emit lexer / parser / visitor
     * sources. Each step is a Result so failures surface as a Cause.
     */
    private Result<GeneratedBundle> buildAll(String grammarText) {
        return GrammarParser.parse(grammarText)
                            .flatMap(root -> GrammarResolver.resolve(root,
                                                                     importSource()))
                            .flatMap(grammar -> RuleClassifier.classify(grammar).flatMap(classification -> DfaBuilder.build(grammar,
                                                                                                                            classification).flatMap(built -> generateBundle(grammar,
                                                                                                                                                                            classification,
                                                                                                                                                                            built))));
    }

    /**
     * The three generators are independent — none consumes another's output — so they run as a
     * Fork-Join. Chaining them meant a lexer-generation failure hid simultaneous parser or
     * visitor failures, which is the wrong trade in the goal a developer runs to find out why
     * codegen broke. Generation is pure text assembly, so evaluating all three costs nothing
     * material.
     */
    private Result<GeneratedBundle> generateBundle(Grammar grammar,
                                                   RuleClassifier.Classification classification,
                                                   DfaBuilder.Built built) {
        return Result.all(LexerGenerator.generate(grammar,
                                                  classification,
                                                  built.dfa(),
                                                  built.kinds(),
                                                  packageName,
                                                  lexerClassName),
                          ParserGenerator.generate(grammar,
                                                   classification,
                                                   built.kinds(),
                                                   packageName,
                                                   parserClassName),
                          VisitorGenerator.generate(grammar, classification, packageName, visitorClassName))
                     .map(GeneratedBundle::new);
    }

    private GrammarSource importSource() {
        return ImportSources.forGrammar(grammarFile, importDirectory);
    }

    private static Result<String> readGrammar(Path path) {
        return Result.lift(t -> Causes.cause("Failed to read grammar: " + path + " — " + t.getMessage()),
                           () -> Files.readString(path));
    }

    private static Result<Path> writeSource(Path targetFile, String source) {
        var stamped = GENERATOR_STAMP + "\n" + source;

        return Result.lift(t -> Causes.cause("Failed to write generated source: " + targetFile + " — " + t.getMessage()),
                           () -> writeSourceUnchecked(targetFile, stamped));
    }

    // JDK-API adapter: the body of a Result.lift(...) throwing lambda. Files.createDirectories
    // and Files.writeString declare IOException, which lift() is precisely there to capture.
    @SuppressWarnings("JBCT-EX-01")
    private static Path writeSourceUnchecked(Path targetFile, String source) throws IOException {
        Files.createDirectories(targetFile.getParent());

        return Files.writeString(targetFile, source);
    }

    /** Three unrelated files; a failure on one should not hide the others. */
    private static Result<List<Path>> writeAll(GeneratedBundle bundle,
                                               Path lexerTarget,
                                               Path parserTarget,
                                               Path visitorTarget) {
        return Result.all(writeSource(lexerTarget,
                                      bundle.lexer().source()),
                          writeSource(parserTarget,
                                      bundle.parser().source()),
                          writeSource(visitorTarget,
                                      bundle.visitor().source()))
                     .map(List::of);
    }

    private Path targetSourceFile(String className) {
        var packagePath = packageName.replace('.', '/');

        return outputDirectory.toPath()
                              .resolve(packagePath)
                              .resolve(className + ".java");
    }

    /**
     * True when every target is newer than the root grammar.
     *
     * <p>Deliberately returns false for any grammar declaring {@code %import}: the check
     * compares against the ROOT grammar's mtime only, so editing an imported grammar while
     * leaving the root untouched would otherwise leave stale generated sources in place with
     * no warning. Matching the trade already made in {@code PegParser}, which does not cache
     * grammars with imports, correctness wins over skipping work.
     *
     * @since 0.7.2 — the import-aware bail-out
     */
    private boolean allUpToDate(Path... targets) {
        if (declaresImports()) {
            return false;
        }

        long grammarMtime = grammarFile.lastModified();

        for (var target : targets) {
            var file = target.toFile();

            if (!file.isFile() || file.lastModified() < grammarMtime) {
                return false;
            }
            // Mtime alone is not enough: after a plugin or generator upgrade the grammar is
            // unchanged but the emitted code should differ, and skipping leaves silently stale
            // sources behind — worse for projects that commit generated code, where nothing
            // downstream ever reveals it. Regenerate whenever the stamp does not match.
            if (!GENERATOR_STAMP.equals(stampOf(target))) {
                return false;
            }
        }

        return true;
    }

    /** Marker line prepended to every generated file; also the staleness key. */
    private static final String STAMP_PREFIX = "// peglib-generator: ";

    private static final String GENERATOR_STAMP = STAMP_PREFIX + generatorVersion();

    /**
     * Version of the generator actually doing the work — {@code peglib} core, not the plugin,
     * since that is where the emitters live.
     */
    private static String generatorVersion() {
        var pkg = LexerGenerator.class.getPackage();
        var fromManifest = pkg == null
                           ? null
                           : pkg.getImplementationVersion();

        if (fromManifest != null) {
            return fromManifest;
        }

        try (var in = LexerGenerator.class.getResourceAsStream("/META-INF/maven/org.pragmatica-lite/peglib/pom.properties")) {
            if (in != null) {
                var props = new java.util.Properties();

                props.load(in);
                var version = props.getProperty("version");

                if (version != null) {
                    return version;
                }
            }
        } catch (IOException __) {
        // fall through to the unknown marker below
        }

        return "unknown";
    }

    /** First line of {@code target} when it carries a stamp, else empty. */
    private static String stampOf(Path target) {
        try (var lines = Files.lines(target)) {
            return lines.findFirst()
                        .filter(line -> line.startsWith(STAMP_PREFIX))
                        .orElse("");
        } catch (IOException __) {
            return "";
        }
    }

    /** Cheap textual pre-check; a full parse here would duplicate work done moments later. */
    private boolean declaresImports() {
        return readGrammar(grammarFile.toPath()).map(GenerateMojo::hasImportDirective)
                          .or(Boolean.TRUE);
    }

    private static boolean hasImportDirective(String text) {
        return text.lines()
                   .anyMatch(line -> line.strip()
                                         .startsWith("%import"));
    }

    /** For programmatic invocation from tests. */
    @SuppressWarnings("JBCT-RET-01")  // Maven plexus setter injection requires the void setX(T) shape.
    public void setGrammarFile(File grammarFile) {
        this.grammarFile = grammarFile;
    }

    @SuppressWarnings("JBCT-RET-01")  // Maven plexus setter injection requires the void setX(T) shape.
    public void setOutputDirectory(File outputDirectory) {
        this.outputDirectory = outputDirectory;
    }

    @SuppressWarnings("JBCT-RET-01")  // Maven plexus setter injection requires the void setX(T) shape.
    public void setPackageName(String packageName) {
        this.packageName = packageName;
    }

    @SuppressWarnings("JBCT-RET-01")  // Maven plexus setter injection requires the void setX(T) shape.
    public void setLexerClassName(String lexerClassName) {
        this.lexerClassName = lexerClassName;
    }

    @SuppressWarnings("JBCT-RET-01")  // Maven plexus setter injection requires the void setX(T) shape.
    public void setParserClassName(String parserClassName) {
        this.parserClassName = parserClassName;
    }

    @SuppressWarnings("JBCT-RET-01")  // Maven plexus setter injection requires the void setX(T) shape.
    public void setVisitorClassName(String visitorClassName) {
        this.visitorClassName = visitorClassName;
    }

    /** Package-name-aware Cause helper for tests. */
    sealed interface GenerateError extends Cause {
        record GrammarReadError(String message) implements GenerateError {}
    }

    /** For programmatic invocation from tests. */
    @SuppressWarnings("JBCT-RET-01")  // Maven plexus setter injection requires the void setX(T) shape.
    public void setImportDirectory(File importDirectory) {
        this.importDirectory = importDirectory;
    }
}
