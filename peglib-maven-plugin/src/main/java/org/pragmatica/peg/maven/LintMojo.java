package org.pragmatica.peg.maven;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.pragmatica.peg.analyzer.Analyzer;
import org.pragmatica.peg.analyzer.AnalyzerReport;
import org.pragmatica.peg.grammar.Grammar;
import org.pragmatica.peg.grammar.GrammarParser;

import java.io.File;
import java.nio.file.Files;

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
        String grammarText;
        try {
            grammarText = Files.readString(grammarFile.toPath());
        } catch (Exception e) {
            throw new MojoExecutionException("Failed to read grammar: " + grammarFile, e);
        }
        var parsed = GrammarParser.parse(grammarText)
                                  .flatMap(Grammar::validate);
        if (parsed instanceof org.pragmatica.lang.Result.Failure< ? > failure) {
            throw new MojoFailureException("Grammar parse failed: " + failure.cause()
                                                                             .message());
        }
        return Analyzer.analyze(parsed.unwrap());
    }
}
