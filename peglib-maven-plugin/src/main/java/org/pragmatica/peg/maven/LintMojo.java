package org.pragmatica.peg.maven;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.peg.analyzer.Analyzer;
import org.pragmatica.peg.analyzer.AnalyzerReport;
import org.pragmatica.peg.grammar.Grammar;
import org.pragmatica.peg.grammar.GrammarParser;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Run the grammar analyzer against a grammar file. Fails the build when any
 * ERROR-level findings are produced. Warnings and info findings are logged
 * but do not fail the build unless {@code failOnWarning} is set.
 */
@Mojo(name = "lint", defaultPhase = LifecyclePhase.VALIDATE, threadSafe = true)
public class LintMojo extends AbstractMojo {

    @Parameter(property = "peglib.grammarFile", required = true)
    private File grammarFile;

    @Parameter(property = "peglib.failOnWarning", defaultValue = "false")
    private boolean failOnWarning;

    /**
     * JBCT boundary: Maven calls into untyped Java land. The Result pipeline in
     * runAnalyzer() composes the failure-prone steps; the terminal consumer
     * translates Result.failure(cause) into MojoFailureException(cause.message()).
     */
    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        var report = runAnalyzer();
        getLog().info(report.formatRustStyle(grammarFile.toString()));
        if (report.hasErrors()) {
            throw new MojoFailureException("peglib:lint produced errors — see log above");
        }
        if (failOnWarning && report.hasWarnings()) {
            throw new MojoFailureException("peglib:lint produced warnings (failOnWarning=true)");
        }
    }

    /** For programmatic invocation from tests. */
    public void setGrammarFile(File grammarFile) {
        this.grammarFile = grammarFile;
    }

    public void setFailOnWarning(boolean failOnWarning) {
        this.failOnWarning = failOnWarning;
    }

    AnalyzerReport runAnalyzer() throws MojoExecutionException, MojoFailureException {
        if (grammarFile == null || !grammarFile.isFile()) {
            throw new MojoFailureException("grammarFile does not exist: " + grammarFile);
        }
        // 0.4.0 — Grammar.grammar(...) factory validates at construction; the
        // parse step (when there are no %imports) returns a validated Grammar
        // directly. Lint targets standalone grammar files, so we don't run the
        // resolver here.
        var pipeline = readGrammar(grammarFile.toPath())
            .flatMap(LintMojo::parseGrammar)
            .map(Analyzer::analyze);
        if (pipeline instanceof Result.Failure<?> failure) {
            throw new MojoFailureException(failure.cause()
                                                  .message());
        }
        return pipeline.unwrap();
    }

    private static Result<Grammar> parseGrammar(String text) {
        return GrammarParser.parse(text)
                            .mapError(c -> Causes.cause("Grammar parse failed: " + c.message()));
    }

    private static Result<String> readGrammar(Path path) {
        return Result.lift(t -> Causes.cause("Failed to read grammar: " + path + " — "
                                             + t.getMessage()),
                           () -> Files.readString(path));
    }
}
