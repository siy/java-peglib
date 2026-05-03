package org.pragmatica.peg.maven;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.peg.PegParser;
import org.pragmatica.peg.generator.ErrorReporting;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Generate a standalone PEG parser Java source file from a grammar.
 *
 * <p>Skips regeneration when the grammar file's modification time is older
 * than the target source file's modification time.
 */
@Mojo(name = "generate", defaultPhase = LifecyclePhase.GENERATE_SOURCES, threadSafe = true)
public class GenerateMojo extends AbstractMojo {

    @Parameter(property = "peglib.grammarFile", required = true)
    private File grammarFile;

    @Parameter(property = "peglib.outputDirectory", required = true)
    private File outputDirectory;

    @Parameter(property = "peglib.packageName", required = true)
    private String packageName;

    @Parameter(property = "peglib.className", required = true)
    private String className;

    @Parameter(property = "peglib.errorReporting", defaultValue = "BASIC")
    private String errorReporting;

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
        var targetFile = targetSourceFile();
        if (isUpToDate(targetFile)) {
            getLog().info("peglib:generate skipped (up-to-date): " + targetFile);
            return;
        }
        var generated = Result.all(parseErrorReporting(errorReporting),
                                   readGrammar(grammarFile.toPath()))
                              .flatMap(this::generateSource);
        if (generated instanceof Result.Failure<?> failure) {
            throw new MojoFailureException(failure.cause()
                                                  .message());
        }
        var write = writeSource(targetFile, generated.unwrap());
        if (write instanceof Result.Failure<?> failure) {
            throw new MojoExecutionException(failure.cause()
                                                    .message());
        }
        getLog().info("peglib:generate wrote " + targetFile);
    }

    private Result<String> generateSource(ErrorReporting reporting, String grammarText) {
        return PegParser.generateCstParser(grammarText, packageName, className, reporting);
    }

    private static Result<ErrorReporting> parseErrorReporting(String value) {
        return Result.lift(t -> Causes.cause("Invalid errorReporting: " + value
                                             + " (expected BASIC or ADVANCED)"),
                           () -> ErrorReporting.valueOf(value));
    }

    private static Result<String> readGrammar(Path path) {
        return Result.lift(t -> Causes.cause("Failed to read grammar: " + path + " — "
                                             + t.getMessage()),
                           () -> Files.readString(path));
    }

    private static Result<Path> writeSource(Path targetFile, String source) {
        return Result.lift(t -> Causes.cause("Failed to write generated source: "
                                             + targetFile + " — " + t.getMessage()),
                           () -> writeSourceUnchecked(targetFile, source));
    }

    private static Path writeSourceUnchecked(Path targetFile, String source) throws IOException {
        Files.createDirectories(targetFile.getParent());
        return Files.writeString(targetFile, source);
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

    public void setClassName(String className) {
        this.className = className;
    }

    public void setErrorReporting(String errorReporting) {
        this.errorReporting = errorReporting;
    }

    private Path targetSourceFile() {
        var packagePath = packageName.replace('.', '/');
        return outputDirectory.toPath()
                              .resolve(packagePath)
                              .resolve(className + ".java");
    }

    private boolean isUpToDate(Path targetFile) {
        var file = targetFile.toFile();
        if (!file.isFile()) {
            return false;
        }
        return grammarFile.lastModified() <= file.lastModified();
    }
}
