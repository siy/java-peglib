package org.pragmatica.peg.maven;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.peg.grammar.Grammar;
import org.pragmatica.peg.grammar.GrammarParser;
import org.pragmatica.peg.v6.generator.LexerGenerator;
import org.pragmatica.peg.v6.generator.LexerGenerator.Generated;
import org.pragmatica.peg.v6.generator.ParserGenerator;
import org.pragmatica.peg.v6.generator.ParserGenerator.GeneratedParser;
import org.pragmatica.peg.v6.generator.VisitorGenerator;
import org.pragmatica.peg.v6.generator.VisitorGenerator.GeneratedVisitor;
import org.pragmatica.peg.v6.lexer.DfaBuilder;
import org.pragmatica.peg.v6.lexer.RuleClassifier;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

/**
 * 0.6.0 v6 parallel: emit a standalone lexer + parser + visitor source triple
 * for the supplied grammar. This mojo runs the {@code classify -> DFA build ->
 * generate-lexer/parser/visitor} pipeline at build time and writes three Java
 * source files under {@code outputDirectory/<packageDir>/}.
 *
 * <p>Unlike {@link GenerateMojo} (which targets the 0.5.x interpreter +
 * standalone-parser path), this mojo emits the lex-then-parse v6 surface and
 * is intended for projects opting in to the new tier-1 throughput engine. It
 * lives next to the legacy mojo so users can migrate one project at a time.
 *
 * <p>Up-to-date: regeneration is skipped when ALL three target source files
 * are newer than the grammar file. If any one is missing or stale every
 * artifact is regenerated.
 */
@Mojo(name = "generate-v6", defaultPhase = LifecyclePhase.GENERATE_SOURCES, threadSafe = true)
public class GenerateV6Mojo extends AbstractMojo {
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

    @Parameter(property = "peglib.visitorClassName", defaultValue = "GVisitor")
    private String visitorClassName;

    /**
     * JBCT boundary: Maven calls into untyped Java land. The Result pipeline
     * below composes the failure-prone steps; the terminal consumer translates
     * Result.failure(cause) into MojoFailureException(cause.message()).
     */
    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (grammarFile == null || !grammarFile.isFile()) {
            throw new MojoFailureException("grammarFile does not exist: " + grammarFile);
        }
        var lexerTarget = targetSourceFile(lexerClassName);
        var parserTarget = targetSourceFile(parserClassName);
        var visitorTarget = targetSourceFile(visitorClassName);
        if (allUpToDate(lexerTarget, parserTarget, visitorTarget)) {
            getLog().info("peglib:generate-v6 skipped (up-to-date): "
                + lexerTarget.getFileName() + ", "
                + parserTarget.getFileName() + ", "
                + visitorTarget.getFileName());
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
            getLog().warn("peglib:generate-v6 lexer warning: " + w);
        }
        getLog().info("peglib:generate-v6 wrote "
            + lexerTarget.getFileName() + ", "
            + parserTarget.getFileName() + ", "
            + visitorTarget.getFileName());
    }

    record GeneratedBundle(Generated lexer, GeneratedParser parser, GeneratedVisitor visitor) {}

    /**
     * Compose the v6 generator pipeline: parse grammar text, classify rules,
     * build the DFA + token-kind table, then emit lexer / parser / visitor
     * sources. Each step is a Result so failures surface as a Cause.
     */
    private Result<GeneratedBundle> buildAll(String grammarText) {
        return GrammarParser.parse(grammarText)
            .flatMap(grammar -> RuleClassifier.classify(grammar)
                .flatMap(classification -> DfaBuilder.build(grammar, classification)
                    .flatMap(built -> generateBundle(grammar, classification, built))));
    }

    private Result<GeneratedBundle> generateBundle(Grammar grammar,
                                                   RuleClassifier.Classification classification,
                                                   DfaBuilder.Built built) {
        return LexerGenerator.generate(grammar, classification, built.dfa(), built.kinds(),
                                       packageName, lexerClassName)
            .flatMap(lexer -> ParserGenerator.generate(grammar, classification, built.kinds(),
                                                       packageName, parserClassName)
                .flatMap(parser -> VisitorGenerator.generate(grammar, classification,
                                                             packageName, visitorClassName)
                    .map(visitor -> new GeneratedBundle(lexer, parser, visitor))));
    }

    private static Result<String> readGrammar(Path path) {
        return Result.lift(t -> Causes.cause("Failed to read grammar: " + path + " — " + t.getMessage()),
                           () -> Files.readString(path));
    }

    private static Result<Path> writeSource(Path targetFile, String source) {
        return Result.lift(t -> Causes.cause("Failed to write generated source: " + targetFile + " — " + t.getMessage()),
                           () -> writeSourceUnchecked(targetFile, source));
    }

    private static Path writeSourceUnchecked(Path targetFile, String source) throws IOException {
        Files.createDirectories(targetFile.getParent());
        return Files.writeString(targetFile, source);
    }

    private static Result<List<Path>> writeAll(GeneratedBundle bundle,
                                               Path lexerTarget,
                                               Path parserTarget,
                                               Path visitorTarget) {
        return writeSource(lexerTarget, bundle.lexer().source())
            .flatMap(l -> writeSource(parserTarget, bundle.parser().source())
                .flatMap(p -> writeSource(visitorTarget, bundle.visitor().source())
                    .map(v -> List.of(l, p, v))));
    }

    private Path targetSourceFile(String className) {
        var packagePath = packageName.replace('.', '/');
        return outputDirectory.toPath().resolve(packagePath).resolve(className + ".java");
    }

    private boolean allUpToDate(Path... targets) {
        long grammarMtime = grammarFile.lastModified();
        for (var target : targets) {
            var file = target.toFile();
            if (!file.isFile() || file.lastModified() < grammarMtime) {
                return false;
            }
        }
        return true;
    }

    /** For programmatic invocation from tests. */
    public void setGrammarFile(File grammarFile) {
        this.grammarFile = grammarFile;
    }

    public void setOutputDirectory(File outputDirectory) {
        this.outputDirectory = outputDirectory;
    }

    public void setPackageName(String packageName) {
        this.packageName = packageName;
    }

    public void setLexerClassName(String lexerClassName) {
        this.lexerClassName = lexerClassName;
    }

    public void setParserClassName(String parserClassName) {
        this.parserClassName = parserClassName;
    }

    public void setVisitorClassName(String visitorClassName) {
        this.visitorClassName = visitorClassName;
    }

    /** Package-name-aware Cause helper for tests. */
    sealed interface GenerateError extends Cause {
        record GrammarReadError(String message) implements GenerateError {}
    }
}
