package org.pragmatica.peg.maven;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.pragmatica.peg.PegParser;

import java.io.File;
import java.nio.file.Files;

/**
 * Check a grammar end-to-end: run the analyzer, then build a runtime parser
 * from the grammar and parse a minimal smoke-test input to confirm the
 * grammar is syntactically and semantically viable.
 *
 * <p>If {@code smokeInput} is set, the parser must succeed on it; otherwise
 * the check simply verifies the parser builds.
 */
@Mojo(name = "check", defaultPhase = LifecyclePhase.VERIFY, threadSafe = true)
public class CheckMojo extends AbstractMojo {

    @Parameter(property = "peglib.grammarFile", required = true)
    private File grammarFile;

    @Parameter(property = "peglib.failOnWarning", defaultValue = "false")
    private boolean failOnWarning;

    @Parameter(property = "peglib.smokeInput")
    private String smokeInput;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        // Step 1: lint
        var lint = new LintMojo();
        lint.setGrammarFile(grammarFile);
        lint.setFailOnWarning(failOnWarning);
        var report = lint.runAnalyzer();
        getLog().info(report.formatRustStyle(grammarFile.toString()));
        if (report.hasErrors()) {
            throw new MojoFailureException("peglib:check failed — analyzer reported errors");
        }
        if (failOnWarning && report.hasWarnings()) {
            throw new MojoFailureException("peglib:check failed — warnings present (failOnWarning=true)");
        }
        // Step 2: build runtime parser from the grammar
        String grammarText;
        try {
            grammarText = Files.readString(grammarFile.toPath());
        } catch (Exception e) {
            throw new MojoExecutionException("Failed to read grammar: " + grammarFile, e);
        }
        var parserResult = PegParser.fromGrammar(grammarText);
        if (parserResult instanceof org.pragmatica.lang.Result.Failure< ? > failure) {
            throw new MojoFailureException("peglib:check failed — parser build failed: "
                                           + failure.cause()
                                                    .message());
        }
        if (smokeInput != null && !smokeInput.isEmpty()) {
            var parser = parserResult.unwrap();
            var parseResult = parser.parseCst(smokeInput);
            if (parseResult instanceof org.pragmatica.lang.Result.Failure< ? > failure) {
                throw new MojoFailureException("peglib:check failed — smoke parse failed: "
                                               + failure.cause()
                                                        .message());
            }
        }
        getLog().info("peglib:check OK for " + grammarFile);
    }

    /** For programmatic invocation from tests. */
    public void setGrammarFile(File grammarFile) {
        this.grammarFile = grammarFile;
    }

    public void setFailOnWarning(boolean failOnWarning) {
        this.failOnWarning = failOnWarning;
    }

    public void setSmokeInput(String smokeInput) {
        this.smokeInput = smokeInput;
    }
}
