package org.pragmatica.peg.maven;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.peg.analyzer.Analyzer;
import org.pragmatica.peg.analyzer.AnalyzerReport;
import org.pragmatica.peg.grammar.Grammar;
import org.pragmatica.peg.grammar.GrammarParser;
import org.pragmatica.peg.grammar.GrammarResolver;
import org.pragmatica.peg.grammar.GrammarSource;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;


/**
 * Run the grammar analyzer against a grammar file. Fails the build when any
 * ERROR-level findings are produced. Warnings and info findings are logged
 * but do not fail the build unless {@code failOnWarning} is set.
 */
@Mojo(name = "lint", defaultPhase = LifecyclePhase.VALIDATE, threadSafe = true)
public class LintMojo extends AbstractMojo {
    @Parameter(property = "peglib.grammarFile", required = true)
    private File grammarFile;

    /**
     * Directory searched for grammars named by {@code %import}. Defaults to the
     * directory holding {@code grammarFile}.
     *
     * @since 0.7.2
     */
    @Parameter(property = "peglib.importDirectory")
    private File importDirectory;

    @Parameter(property = "peglib.failOnWarning", defaultValue = "false")
    private boolean failOnWarning;

    /**
     * JBCT boundary: Maven calls into untyped Java land. The Result pipeline in
     * runAnalyzer() composes the failure-prone steps; the terminal consumer
     * translates Result.failure(cause) into MojoFailureException(cause.message()).
     */
    @Override
    @SuppressWarnings({"JBCT-RET-01", "JBCT-EX-01"})  // Maven AbstractMojo contract: execute() is void and signals failure by throwing.
    public void execute() throws MojoExecutionException, MojoFailureException {
        var outcome = runAnalyzer();

        if (outcome instanceof Result.Failure<?> failure) {
            throw new MojoFailureException(failure.cause().message());
        }

        var report = outcome.unwrap();

        getLog().info(report.formatRustStyle(grammarFile.toString()));
        if (report.hasErrors()) {
            throw new MojoFailureException("peglib:lint produced errors — see log above");
        }

        if (failOnWarning && report.hasWarnings()) {
            throw new MojoFailureException("peglib:lint produced warnings (failOnWarning=true)");
        }
    }

    /** For programmatic invocation from tests. */
    @SuppressWarnings("JBCT-RET-01")  // Maven plexus setter injection requires the void setX(T) shape.
    public void setGrammarFile(File grammarFile) {
        this.grammarFile = grammarFile;
    }

    @SuppressWarnings("JBCT-RET-01")  // Maven plexus setter injection requires the void setX(T) shape.
    public void setFailOnWarning(boolean failOnWarning) {
        this.failOnWarning = failOnWarning;
    }

    Result<AnalyzerReport> runAnalyzer() {
        if (grammarFile == null || !grammarFile.isFile()) {
            return Causes.cause("grammarFile does not exist: " + grammarFile).result();
        }
        // 0.4.0 — Grammar.grammar(...) factory validates at construction; the
        // parse step (when there are no %imports) returns a validated Grammar
        // directly. Lint targets standalone grammar files, so we don't run the
        // resolver here.
        return readGrammar(grammarFile.toPath()).flatMap(this::parseGrammar)
                          .map(Analyzer::analyze);
    }

    /** Resolution failures are labelled for their own stage — they are not parse errors. */
    private Result<Grammar> resolveImports(Grammar root) {
        return GrammarResolver.resolve(root, importSource()).mapError(c -> Causes.cause("Import resolution failed: " + c.message()));
    }

    private Result<Grammar> parseGrammar(String text) {
        return GrammarParser.parse(text)
                            .mapError(c -> Causes.cause("Grammar parse failed: " + c.message()))
                            .flatMap(this::resolveImports);
    }

    private GrammarSource importSource() {
        return ImportSources.forGrammar(grammarFile, importDirectory);
    }

    private static Result<String> readGrammar(Path path) {
        return Result.lift(t -> Causes.cause("Failed to read grammar: " + path + " — " + t.getMessage()),
                           () -> Files.readString(path));
    }

    /** For programmatic invocation from tests. */
    @SuppressWarnings("JBCT-RET-01")  // Maven plexus setter injection requires the void setX(T) shape.
    public void setImportDirectory(File importDirectory) {
        this.importDirectory = importDirectory;
    }
}
