package org.pragmatica.peg.maven;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Collectors;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.peg.Parser;
import org.pragmatica.peg.PegParser;
import org.pragmatica.peg.cst.ParseResult;
import org.pragmatica.peg.diagnostic.Diagnostic;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;


/**
 * Check a grammar end-to-end: run the analyzer, then build a 0.6.x lex-then-parse
 * runtime parser from the grammar and parse a minimal smoke-test input to confirm
 * the grammar is syntactically and semantically viable.
 *
 * <p>If {@code smokeInput} is set, the parser must consume it without emitting a
 * single diagnostic; otherwise the check simply verifies the parser builds.
 *
 * <p>Note that the pipeline only emits a parser when the grammar contains at
 * least one PARSER or MIXED rule — an all-literal single-rule grammar classifies
 * as LEXER-only and cannot be smoke-tested.
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
    @SuppressWarnings({"JBCT-RET-01", "JBCT-EX-01"})  // Maven AbstractMojo contract: execute() is void and signals failure by throwing.
    public void execute() throws MojoExecutionException, MojoFailureException {
        // Step 1: lint
        var lint = new LintMojo();

        lint.setGrammarFile(grammarFile);
        lint.setFailOnWarning(failOnWarning);
        var outcome = lint.runAnalyzer();

        if (outcome instanceof Result.Failure<?> failure) {
            throw new MojoFailureException(failure.cause().message());
        }

        var report = outcome.unwrap();

        getLog().info(report.formatRustStyle(grammarFile.toString()));
        if (report.hasErrors()) {
            throw new MojoFailureException("peglib:check failed — analyzer reported errors");
        }

        if (failOnWarning && report.hasWarnings()) {
            throw new MojoFailureException("peglib:check failed — warnings present (failOnWarning=true)");
        }
        // Step 2: build runtime parser and run smoke input (if configured) as a Result pipeline.
        var pipeline = readGrammar(grammarFile.toPath()).flatMap(CheckMojo::buildParser).flatMap(this::runSmoke);

        if (pipeline instanceof Result.Failure<?> failure) {
            throw new MojoFailureException(failure.cause().message());
        }

        getLog().info("peglib:check OK for " + grammarFile);
    }

    private static Result<Parser> buildParser(String grammarText) {
        return PegParser.fromGrammar(grammarText).mapError(c -> Causes.cause("peglib:check failed — parser build failed: " + c.message()));
    }

    private Result<Unit> runSmoke(Parser parser) {
        if (smokeInput == null || smokeInput.isEmpty()) {
            return Result.unitResult();
        }

        return verifySmoke(parser.parse(smokeInput));
    }

    /**
     * The {@code parse} never fails outright — it always yields a tree plus a
     * diagnostics list — so the Result channel is re-established here: an empty
     * diagnostics list is success, anything else is a Cause carrying the
     * rendered diagnostics.
     */
    private static Result<Unit> verifySmoke(ParseResult result) {
        return result.isSuccess()
               ? Result.unitResult()
               : Causes.cause("peglib:check failed — smoke parse failed: " + describe(result)).result();
    }

    private static String describe(ParseResult result) {
        return result.diagnostics()
                     .stream()
                     .map(CheckMojo::describe)
                     .collect(Collectors.joining("; "));
    }

    private static String describe(Diagnostic diagnostic) {
        return diagnostic.severity()
                         .label() + " at offset " + diagnostic.offset() + ": " + diagnostic.message();
    }

    private static Result<String> readGrammar(Path path) {
        return Result.lift(t -> Causes.cause("Failed to read grammar: " + path + " — " + t.getMessage()),
                           () -> Files.readString(path));
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

    @SuppressWarnings("JBCT-RET-01")  // Maven plexus setter injection requires the void setX(T) shape.
    public void setSmokeInput(String smokeInput) {
        this.smokeInput = smokeInput;
    }
}
