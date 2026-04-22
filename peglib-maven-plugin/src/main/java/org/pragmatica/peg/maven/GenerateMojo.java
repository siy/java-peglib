package org.pragmatica.peg.maven;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.pragmatica.peg.PegParser;
import org.pragmatica.peg.generator.ErrorReporting;

import java.io.File;
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
        ErrorReporting reporting;
        try {
            reporting = ErrorReporting.valueOf(errorReporting);
        } catch (IllegalArgumentException iae) {
            throw new MojoFailureException("Invalid errorReporting: " + errorReporting
                                           + " (expected BASIC or ADVANCED)");
        }
        String grammarText;
        try {
            grammarText = Files.readString(grammarFile.toPath());
        } catch (Exception e) {
            throw new MojoExecutionException("Failed to read grammar: " + grammarFile, e);
        }
        var result = PegParser.generateCstParser(grammarText, packageName, className, reporting);
        if (result instanceof org.pragmatica.lang.Result.Failure< ? > failure) {
            throw new MojoFailureException("Grammar generation failed: " + failure.cause()
                                                                                  .message());
        }
        var source = result.unwrap();
        try {
            Files.createDirectories(targetFile.getParent());
            Files.writeString(targetFile, source);
        } catch (Exception e) {
            throw new MojoExecutionException("Failed to write generated source: " + targetFile, e);
        }
        getLog().info("peglib:generate wrote " + targetFile);
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
