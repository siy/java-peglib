package org.pragmatica.peg.maven;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.peg.PegParser;
import org.pragmatica.peg.parser.Parser;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

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

    /**
     * JBCT boundary: Maven calls into untyped Java land. The Result pipeline
     * below composes the failure-prone steps (read, parser build, smoke
     * parse); the terminal consumer translates Result.failure(cause) into
     * MojoFailureException(cause.message()).
     */
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
        // Step 2: build runtime parser and run smoke input (if configured) as a Result pipeline.
        var pipeline = readGrammar(grammarFile.toPath())
            .flatMap(CheckMojo::buildParser)
            .flatMap(this::runSmoke);
        if (pipeline instanceof Result.Failure<?> failure) {
            throw new MojoFailureException(failure.cause()
                                                  .message());
        }
        getLog().info("peglib:check OK for " + grammarFile);
    }

    private static Result<Parser> buildParser(String grammarText) {
        return PegParser.fromGrammar(grammarText)
                        .mapError(c -> Causes.cause("peglib:check failed — parser build failed: "
                                                    + c.message()));
    }

    private Result<Unit> runSmoke(Parser parser) {
        if (smokeInput == null || smokeInput.isEmpty()) {
            return Result.unitResult();
        }
        return parser.parseCst(smokeInput)
                     .mapError(c -> Causes.cause("peglib:check failed — smoke parse failed: "
                                                 + c.message()))
                     .mapToUnit();
    }

    private static Result<String> readGrammar(Path path) {
        return Result.lift(t -> Causes.cause("Failed to read grammar: " + path + " — "
                                             + t.getMessage()),
                           () -> Files.readString(path));
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
